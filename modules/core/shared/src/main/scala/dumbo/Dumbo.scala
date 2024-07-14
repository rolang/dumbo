// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

import scala.concurrent.duration.*

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyChain, ValidatedNec}
import cats.effect.kernel.Clock
import cats.effect.std.Console
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.implicits.*
import dumbo.exception.DumboValidationException
import dumbo.internal.{ResourceReader, Statements}
import fs2.Stream
import fs2.io.file.*
import fs2.io.net.Network
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*
import skunk.util.{Origin, Typer}

final class DumboWithResourcesPartiallyApplied[F[_]](reader: ResourceReader[F]) {
  def apply(
    connection: ConnectionConfig,
    defaultSchema: String = Dumbo.defaults.defaultSchema,
    schemas: Set[String] = Dumbo.defaults.schemas,
    schemaHistoryTable: String = Dumbo.defaults.schemaHistoryTable,
    validateOnMigrate: Boolean = Dumbo.defaults.validateOnMigrate,
  )(implicit S: Sync[F], T: Temporal[F], C: Console[F], TRC: Tracer[F], N: Network[F]) =
    new Dumbo[F](
      resReader = reader,
      sessionResource = toSessionResource(connection, defaultSchema, schemas),
      connection = connection,
      defaultSchema = defaultSchema,
      schemas = schemas,
      schemaHistoryTable = schemaHistoryTable,
      validateOnMigrate = validateOnMigrate,
    )

  def withMigrationStateLogAfter(logMigrationStateAfter: FiniteDuration)(
    connection: ConnectionConfig,
    defaultSchema: String = Dumbo.defaults.defaultSchema,
    schemas: Set[String] = Dumbo.defaults.schemas,
    schemaHistoryTable: String = Dumbo.defaults.schemaHistoryTable,
    validateOnMigrate: Boolean = Dumbo.defaults.validateOnMigrate,
  )(implicit A: Async[F], C: Console[F], TRC: Tracer[F]): Dumbo[F] = {
    implicit val network: Network[F] = Network.forAsync(A)
    val sessionResource              = toSessionResource(connection, defaultSchema, schemas)

    new Dumbo[F](
      resReader = reader,
      sessionResource = sessionResource,
      connection = connection,
      defaultSchema = defaultSchema,
      schemas = schemas,
      schemaHistoryTable = schemaHistoryTable,
      validateOnMigrate = validateOnMigrate,
      progressMonitor = Resource
        .eval(sessionResource.use(s => Dumbo.hasTableLockSupport(s, s"${defaultSchema}.${schemaHistoryTable}")))
        .flatMap {
          case false => Resource.eval(Console[F].println("Progress monitor is not supported for current database"))
          case true =>
            Async[F].background {
              Stream
                .evalSeq(
                  sessionResource
                    .use(
                      _.execute(
                        sql"""SELECT ps.pid, ps.query_start, ps.state_change, ps.state, ps.wait_event_type, ps.wait_event, ps.query
                        FROM pg_locks l
                        JOIN pg_stat_all_tables t ON t.relid = l.relation
                        JOIN pg_stat_activity ps ON ps.pid = l.pid
                        WHERE t.schemaname = '#${defaultSchema}' and t.relname = '#${schemaHistoryTable}'"""
                          .query(int4 *: timestamptz *: timestamptz *: text *: text.opt *: text.opt *: text)
                      ).map(_.groupByNel { case pid *: _ => pid }.toList.map(_._2.head))
                    )
                )
                .evalMap { case pid *: start *: changed *: state *: eventType *: event *: query *: _ =>
                  for {
                    now         <- Clock[F].realTimeInstant
                    startedAgo   = now.getEpochSecond() - start.toEpochSecond()
                    changedAgo   = now.getEpochSecond() - changed.toEpochSecond()
                    queryLogSize = 150
                    queryLog     = query.take(queryLogSize) + (if (query.size > queryLogSize) "..." else "")
                    _ <-
                      Console[F].println(
                        s"Awaiting query with pid: $pid started: ${startedAgo}s ago (state: $state / last changed: ${changedAgo}s ago, " +
                          s"eventType: ${eventType.getOrElse("")}, event: ${event.getOrElse("")}):\n${queryLog}"
                      )
                  } yield ()
                }
                .repeat
                .metered(logMigrationStateAfter)
                .compile
                .drain
            }.void
        },
    )
  }

  def listMigrationFiles(implicit S: Sync[F]): F[ValidatedNec[DumboValidationException, List[ResourceFile]]] =
    Dumbo.listMigrationFiles(reader)

  private def toSessionResource(
    connection: ConnectionConfig,
    defaultSchema: String,
    schemas: Set[String],
  )(implicit T: Temporal[F], C: Console[F], TRC: Tracer[F], N: Network[F]) = {
    val searchPath = (schemas + defaultSchema).mkString(",")
    val params     = Session.DefaultConnectionParameters ++ Map("search_path" -> searchPath)

    Session.single[F](
      host = connection.host,
      port = connection.port,
      user = connection.user,
      database = connection.database,
      password = connection.password,
      strategy = Typer.Strategy.BuiltinsOnly,
      ssl = connection.ssl,
      parameters = params,
    )
  }
}

class Dumbo[F[_]: Sync: Console](
  private[dumbo] val resReader: ResourceReader[F],
  sessionResource: Resource[F, Session[F]],
  private[dumbo] val connection: ConnectionConfig,
  defaultSchema: String,
  schemas: Set[String],
  schemaHistoryTable: String,
  private[dumbo] val validateOnMigrate: Boolean,
  progressMonitor: Resource[F, Unit] = Resource.unit[F],
) {
  import Dumbo.*

  private[dumbo] val allSchemas   = Set(defaultSchema) ++ schemas
  private[dumbo] val historyTable = s"${defaultSchema}.${schemaHistoryTable}"
  private val dumboHistory        = History(historyTable)

  private def initSchemaCmd(schema: String) = sql"CREATE SCHEMA IF NOT EXISTS #${schema}".command

  private def transact(source: ResourceFile, fs: ResourceReader[F], session: Session[F]): F[HistoryEntry.New] =
    for {
      _ <-
        Console[F].println(
          s"""Migrating schema "$defaultSchema" to version ${source.versionText} - ${source.scriptDescription}"""
            + s"${if (!source.executeInTransaction) " [non-transactional]" else ""}"
        )

      statements <- fs.readUtf8(source.path)
                      .compile
                      .toList
                      .map(_.mkString)
                      .map { sql =>
                        // for non transactional operations we need to split the content into single statements
                        // When a simple Query message contains more than one SQL statement (separated by semicolons), those statements are executed as a single transaction.
                        // https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-FLOW-MULTI-STATEMENT
                        if (source.executeInTransaction) Vector(sql) else Statements.intoSingleStatements(sql)
                      }

      (duration, _) <- Clock[F].timed {
                         statements
                           .map(statementSql =>
                             new Statement[Void] {
                               override val sql: String            = statementSql
                               override val origin: Origin         = Origin(source.path.toString, line = 0)
                               override val encoder: Encoder[Void] = Void.codec
                               override val cacheKey: Statement.CacheKey =
                                 Statement.CacheKey(statementSql, encoder.types, Nil)
                             }
                           )
                           .traverse_(session.executeDiscard(_))
                       }
      _ <- Console[F].println(s"Migration to version ${source.versionText} - ${source.scriptDescription} completed")
    } yield HistoryEntry.New(
      version = source.versionText,
      description = source.scriptDescription,
      `type` = "SQL",
      script = source.path.fileName,
      checksum = Some(source.checksum),
      executionTimeMs = duration.toMillis.toInt,
      success = true,
    )

  private def validationGuard(session: Session[F], sourceFiles: List[ResourceFile]) =
    if (sourceFiles.nonEmpty) {
      session
        .execute(dumboHistory.loadAllQuery)
        .map(history => validate(history, sourceFiles))
        .flatMap {
          case Valid(_) => ().pure[F]
          case Invalid(e) =>
            new DumboValidationException(s"Error on validation:\n${e.toList.map(_.getMessage).mkString("\n")}")
              .raiseError[F, Unit]
        }
    } else ().pure[F]

  private def migrateToNext(
    session: Session[F],
    fs: ResourceReader[F],
    tableLockSupport: Boolean,
  )(
    resources: (List[ResourceVersioned], List[ResourceFile])
  ): F[MigrateToNextResult] =
    resources match {
      case (Nil, Nil) => none.pure[F]
      case (versioned, repeatables) =>
        (for {
          txn <- session.transaction
          _   <- progressMonitor
        } yield txn).use { _ =>
          for {
            _ <- if (tableLockSupport) lockTable(session, historyTable).void
                 else session.executeDiscard(sql"SELECT * FROM #${historyTable} FOR UPDATE".command)
            res <- processVersioned(versioned, session, fs).flatMap {
                     case Some((newEntry, files)) => (newEntry, (files, repeatables)).some.pure[F]
                     case _ =>
                       processRepeatables(repeatables, session, fs).flatMap[MigrateToNextResult] {
                         case Some((newEntry, files)) =>
                           (newEntry, (versionedNil, files)).some.pure[F]
                         case _ => none.pure[F]
                       }
                   }
          } yield res
        }
    }
  private def processVersioned(
    versioned: List[(ResourceVersion.Versioned, ResourceFile)],
    session: Session[F],
    fs: ResourceReader[F],
  ): F[Option[(HistoryEntry, List[ResourceVersioned])]] = if (versioned.isEmpty)
    none.pure[F]
  else
    for {
      latestInstalled <- session.option(dumboHistory.latestVersionedInstalled)
      latestInstalledV = latestInstalled.flatMap(_.sourceFileVersion)
      result <- versioned.dropWhile { case (v, _) => latestInstalledV.exists(v <= _) } match {
                  case (_, x) :: xs =>
                    // acquire a new session for non-transactional operation
                    val transactSession: Resource[F, Session[F]] =
                      if (x.executeInTransaction) Resource.pure(session) else sessionResource

                    transactSession.use { s =>
                      transact(x, fs, s)
                        .flatMap(updateHistory(latestInstalled, s))
                        .map((_, xs).some)
                    }
                  case _ => none.pure[F]
                }
    } yield result

  private def processRepeatables(
    repeatables: List[ResourceFile],
    session: Session[F],
    fs: ResourceReader[F],
  ): F[Option[(HistoryEntry, List[ResourceFile])]] = if (repeatables.isEmpty) none.pure[F]
  else
    for {
      latestRepeatables <- session.execute(dumboHistory.latestRepeatablesInstalled)
      res <- repeatables.filter { f =>
               latestRepeatables.find(_.script == f.fileName).forall(_.checksum != Some(f.checksum))
             } match {
               case x :: xs =>
                 // acquire a new session for non-transactional operation
                 val transactSession: Resource[F, Session[F]] =
                   if (x.executeInTransaction) Resource.pure(session) else sessionResource

                 transactSession.use { s =>
                   transact(x, fs, s).flatMap(updateHistory(None, s)).map((_, xs).some)
                 }
               case Nil => none.pure[F]
             }
    } yield res

  private def updateHistory(latestInstalled: Option[HistoryEntry], session: Session[F])(newEntry: HistoryEntry.New) =
    latestInstalled match {
      case Some(value) if !value.success => session.unique(dumboHistory.updateSQLEntry)(newEntry -> value.installedRank)
      case _                             => session.unique(dumboHistory.insertSQLEntry)(newEntry)
    }

  // it's supposed to be prevented by IF NOT EXISTS clause when running concurrently
  // but it doesn't always seem to prevent it, maybe better to lock another table instead of catching those?
  // https://www.postgresql.org/docs/current/errcodes-appendix.html
  private val duplicateErrorCodes = Set(
    "42710", // duplicate_object
    "23505", // unique_violation
    "42P07", // duplicate_table
  )

  def runMigration: F[MigrationResult] = sessionResource.use(migrateBySession)

  private def migrateBySession(session: Session[F]): F[Dumbo.MigrationResult] = for {
    dbVersion <- session.unique(sql"SELECT version()".query(text))
    _         <- Console[F].println(s"Starting migration on $dbVersion")
    schemaRes <-
      allSchemas.toList
        .flatTraverse(schema =>
          session.execute(initSchemaCmd(schema)).attempt.map {
            case Right(Completion.CreateSchema)                                                          => List(schema)
            case Left(e: skunk.exception.PostgresErrorException) if duplicateErrorCodes.contains(e.code) => Nil
            case _                                                                                       => Nil
          }
        )
    _ <- session.execute(dumboHistory.createTableCommand).void.recover {
           case e: skunk.exception.PostgresErrorException if duplicateErrorCodes.contains(e.code) => ()
         }
    tableLockSupport <- hasTableLockSupport(session, historyTable)
    _ <- schemaRes match {
           case e @ (_ :: _) => session.execute(dumboHistory.insertSchemaEntry)(e.mkString("\"", "\",\"", "\"")).void
           case _            => ().pure[F]
         }

    migrationResult <- for {
                         sourceFiles <- listMigrationFiles(resReader).flatMap {
                                          case Valid(f) => f.pure[F]
                                          case Invalid(errs) =>
                                            new DumboValidationException(
                                              s"Error while reading migration files:\n${errs.toList.mkString("\n")}"
                                            ).raiseError[F, List[ResourceFile]]
                                        }
                         _ <- {
                           val inLocation = resReader.location.map(l => s" in $l").getOrElse("")
                           Console[F].println(s"Found ${sourceFiles.size} versioned migration files$inLocation")
                         }
                         _ <- if (validateOnMigrate) validationGuard(session, sourceFiles) else ().pure[F]
                         resources = sourceFiles.map(f => (f.version, f)).partitionMap {
                                       case (v: ResourceVersion.Versioned, f) => Left((v, f))
                                       case (ResourceVersion.Repeatable, f)   => Right(f)
                                     }
                         migrationResult <-
                           Stream
                             .unfoldEval(resources)(migrateToNext(session, resReader, tableLockSupport))
                             .compile
                             .toList
                             .map(Dumbo.MigrationResult(_))
                       } yield migrationResult

    _ <- migrationResult.migrations.sorted(Ordering[HistoryEntry].reverse).headOption match {
           case None => Console[F].println(s"Schema ${defaultSchema} is up to date. No migration necessary")
           case Some(latestInstalled) =>
             val version = latestInstalled.version.getOrElse(latestInstalled.description)
             Console[F]
               .println(
                 s"Successfully applied ${migrationResult.migrations.length} migrations, now at version $version"
               )
         }
  } yield migrationResult

  private def validate(
    history: List[HistoryEntry],
    sourceFiles: List[ResourceFile],
  ): ValidatedNec[DumboValidationException, Unit] = {
    val versionedMap = sourceFiles.collect {
      case f @ ResourceFile(ResourceFileDescription(ResourceVersion.Versioned(v, _), _, _), _, _) => (v, f)
    }.toMap

    val repeatablesMap = sourceFiles.collect {
      case f @ ResourceFile(ResourceFileDescription(ResourceVersion.Repeatable, _, p), _, _) => (p.fileName, f)
    }.toMap

    history
      .filter(_.`type` == "SQL")
      .traverse { h =>
        (versionedMap.get(h.version.getOrElse("")), repeatablesMap.get(h.script)) match {
          case (None, None) =>
            new DumboValidationException(s"Detected applied migration not resolved locally ${h.script}")
              .invalidNec[Unit]

          case (Some(value), _) if Some(value.checksum) != h.checksum =>
            new DumboValidationException(
              s"""|Validate failed: Migrations have failed validation
                  |Migration checksum mismatch for migration version ${h.version}
                  |-> Applied to database : ${h.checksum.fold("null")(_.toString)}
                  |-> Resolved locally    : ${value.checksum}
                  |Either revert the changes to the migration ${value.description.fileName} or update the checksum in $historyTable""".stripMargin
            ).invalidNec[Unit]

          case (Some(value), _) if value.scriptDescription != h.description =>
            new DumboValidationException(
              s"""|Migration description mismatch for migration version ${h.version}
                  |-> Applied to database : ${value.scriptDescription}
                  |-> Resolved locally    : ${h.description}
                  |Either revert the changes to the migration, or update the description in $historyTable.""".stripMargin
            ).invalidNec[Unit]

          case _ => ().validNec[DumboValidationException]
        }
      }
      .void
  }

  def runValidationWithHistory: F[ValidatedNec[DumboValidationException, Unit]] =
    listMigrationFiles(resReader).flatMap {
      case Valid(sourceFiles) =>
        sessionResource.use(_.execute(dumboHistory.loadAllQuery).map(history => validate(history, sourceFiles)))
      case Invalid(c) => c.invalid.pure[F]
    }
}

object Dumbo extends internal.DumboPlatform {
  private type ResourceVersioned = (ResourceVersion.Versioned, ResourceFile)
  private type MigrateToNextResult =
    Option[(HistoryEntry, (List[ResourceVersioned], List[ResourceFile]))]

  private val versionedNil: List[ResourceVersioned] = List.empty[ResourceVersioned]

  object defaults {
    val defaultSchema: String      = "public"
    val schemas: Set[String]       = Set.empty[String]
    val schemaHistoryTable: String = "flyway_schema_history"
    val validateOnMigrate: Boolean = true
    val port: Int                  = 5432
  }

  final case class MigrationResult(migrations: List[HistoryEntry]) {
    val migrationsExecuted: Int = migrations.length
  }

  def withResources[F[_]: Sync](resources: List[ResourceFilePath]): DumboWithResourcesPartiallyApplied[F] =
    new DumboWithResourcesPartiallyApplied[F](ResourceReader.embeddedResources(Sync[F].pure(resources)))

  def withFilesIn[F[_]: Files](dir: Path): DumboWithResourcesPartiallyApplied[F] =
    new DumboWithResourcesPartiallyApplied[F](ResourceReader.fileFs(dir))

  private[dumbo] def hasTableLockSupport[F[_]: Sync](session: Session[F], table: String) =
    session.transaction.use(_ =>
      lockTable(session, table).attempt.map {
        case Right(Completion.LockTable) => true
        case _                           => false
      }
    )

  private def lockTable[F[_]](session: Session[F], table: String) =
    session.execute(sql"LOCK TABLE #${table} IN ACCESS EXCLUSIVE MODE".command)

  private[dumbo] def listMigrationFiles[F[_]: Sync](
    fs: ResourceReader[F]
  ): F[ValidatedNec[DumboValidationException, List[ResourceFile]]] =
    readResourceFiles[F](fs).compile.toList.map { sf =>
      val (errs, files) = (sf.collect { case Left(err) => err }, sf.collect { case Right(v) => v })
      val duplicates    = files.groupBy(_.version).filter(_._2.length > 1).toList

      (duplicates, errs.map(new DumboValidationException(_))) match {
        case (Nil, Nil)     => files.sorted.validNec[DumboValidationException]
        case (Nil, x :: xs) => NonEmptyChain(x, xs*).invalid[List[ResourceFile]]
        case (diff, exceptions) =>
          NonEmptyChain(
            new DumboValidationException(
              s"""|Found more than one migration with versions ${diff.map(_._1.toString).mkString(", ")}\n
                  |Offenders:\n${diff.flatMap(_._2.map(_.path)).mkString("\n")}""".stripMargin
            ),
            exceptions*
          ).invalid[List[ResourceFile]]
      }
    }

  private[dumbo] def readResourceFiles[F[_]: Sync](
    fs: ResourceReader[F]
  ): Stream[F, Either[String, ResourceFile]] =
    fs.list
      .filter(f => f.value.endsWith(".sql"))
      .evalMap { path =>
        val confPath = path.append(".conf")

        fs.exists(confPath)
          .flatMap {
            case true  => fs.readUtf8Lines(confPath).compile.toList.map(ResourceFileConfig.fromLines)
            case false => Set.empty[ResourceFileConfig].asRight[String].pure[F]
          }
          .flatMap {
            case Right(configs) =>
              ResourceFileDescription.fromResourcePath(path) match {
                case Right(desc) =>
                  for {
                    checksum <- checksum[F](path, fs)
                  } yield ResourceFile(
                    description = desc,
                    checksum = checksum,
                    configs = configs,
                  ).asRight[String]
                case Left(err) => err.asLeft[ResourceFile].pure[F]
              }

            case Left(err) => err.asLeft[ResourceFile].pure[F]
          }
      }

  // implementation of checksum from Flyway
  // https://github.com/flyway/flyway/blob/main/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java#L59
  private[dumbo] def checksum[F[_]: Sync](p: ResourceFilePath, fs: ResourceReader[F]): F[Int] =
    for {
      crc32 <- (new CRC32()).pure[F]
      _ <- fs.readUtf8Lines(p)
             .map { line =>
               crc32.update(line.getBytes(StandardCharsets.UTF_8))
             }
             .compile
             .drain
    } yield crc32.getValue().toInt
}
