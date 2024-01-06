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
import dumbo.internal.{ResourceReader, Session as DumboSession, Statements}
import fs2.Stream
import fs2.io.file.*
import fs2.io.net.Network
import natchez.Trace
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*
import skunk.util.{Origin, Typer}
import skunk.{Session as SkunkSession, *}

final class DumboWithResourcesPartiallyApplied[F[_]](reader: ResourceReader[F]) {
  def apply(
    connection: ConnectionConfig,
    defaultSchema: String = Dumbo.defaults.defaultSchema,
    schemas: Set[String] = Dumbo.defaults.schemas,
    schemaHistoryTable: String = Dumbo.defaults.schemaHistoryTable,
    validateOnMigrate: Boolean = Dumbo.defaults.validateOnMigrate,
  )(implicit S: Sync[F], T: Temporal[F], C: Console[F], TRC: Trace[F], N: Network[F]) =
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
  )(implicit A: Async[F], C: Console[F], TRC: Trace[F]): Dumbo[F] = {
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
      progressMonitor = Async[F].background {
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
      }.void,
    )
  }

  def listMigrationFiles(implicit S: Sync[F]): F[ValidatedNec[DumboValidationException, List[ResourceFile]]] =
    Dumbo.listMigrationFiles(reader)

  private def toSessionResource(
    connection: ConnectionConfig,
    defaultSchema: String,
    schemas: Set[String],
  )(implicit T: Temporal[F], C: Console[F], TRC: Trace[F], N: Network[F]) = {
    val searchPath = (schemas + defaultSchema).mkString(",")
    val params     = SkunkSession.DefaultConnectionParameters ++ Map("search_path" -> searchPath)

    DumboSession.single[F](
      host = connection.host,
      port = connection.port,
      user = connection.user,
      database = connection.database,
      password = connection.password,
      strategy = Typer.Strategy.SearchPath,
      ssl = connection.ssl,
      parameters = params,
    )
  }
}

class Dumbo[F[_]: Sync: Console](
  private[dumbo] val resReader: ResourceReader[F],
  sessionResource: Resource[F, DumboSession[F]],
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

  private def transact(source: ResourceFile, fs: ResourceReader[F], session: DumboSession[F]): F[HistoryEntry.New] =
    for {
      _ <-
        Console[F].println(
          s"""Migrating schema "$defaultSchema" to version ${source.versionRaw} - ${source.scriptDescription}"""
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
                           .traverse_(session.execute_(_))
                       }
      _ <- Console[F].println(s"Migration to version ${source.versionRaw} - ${source.scriptDescription} completed")
    } yield HistoryEntry.New(
      version = source.versionRaw,
      description = source.scriptDescription,
      `type` = "SQL",
      script = source.path.fileName.toString,
      checksum = Some(source.checksum),
      executionTimeMs = duration.toMillis.toInt,
      success = true,
    )

  private def validationGuard(session: DumboSession[F], sourceFiles: List[ResourceFile]) =
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
    session: DumboSession[F],
    fs: ResourceReader[F],
  )(sourceFiles: List[ResourceFile]): F[Option[(HistoryEntry, List[ResourceFile])]] =
    sourceFiles match {
      case Nil => none.pure[F]
      case _ =>
        (for {
          txn <- session.transaction
          _   <- progressMonitor
        } yield txn).use { _ =>
          for {
            _               <- session.execute(sql"LOCK TABLE #${historyTable} IN ACCESS EXCLUSIVE MODE".command)
            latestInstalled <- session.unique(dumboHistory.findLatestInstalled).map(_.flatMap(_.sourceFileVersion))
            result <- sourceFiles.dropWhile(s => latestInstalled.exists(s.version <= _)) match {
                        case head :: tail =>
                          // acquire a new session for non-transactional operation
                          val transactSession: Resource[F, DumboSession[F]] =
                            if (head.executeInTransaction) Resource.pure(session) else sessionResource

                          transactSession
                            .use(transact(head, fs, _))
                            .flatMap { newEntry =>
                              session.unique(dumboHistory.insertSQLEntry)(newEntry)
                            }
                            .map((_, tail).some)
                        case _ => none.pure[F]
                      }
          } yield result
        }
    }

  // it's supposed to be prevented by IF NOT EXISTS clause when running concurrently
  // but it doesn't always seem to prevent it, maybe better to lock another table instead of catching those?
  // https://www.postgresql.org/docs/15/errcodes-appendix.html
  private val duplicateErrorCodes = Set(
    "42710", // duplicate_object
    "23505", // unique_violation
    "42P07", // duplicate_table
  )

  def runMigration: F[MigrationResult] = sessionResource.use(migrateBySession)

  private def migrateBySession(session: DumboSession[F]): F[Dumbo.MigrationResult] = for {
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
                         _ <- Console[F].println(
                                s"Found ${sourceFiles.size} versioned migration files"
                              )
                         _ <- if (validateOnMigrate) validationGuard(session, sourceFiles) else ().pure[F]
                         migrationResult <- Stream
                                              .unfoldEval(sourceFiles)(migrateToNext(session, resReader))
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
    val sourceFilesMap = sourceFiles.map(s => (s.versionRaw, s)).toMap

    history
      .filter(_.`type` == "SQL")
      .traverse { h =>
        sourceFilesMap.get(h.version.getOrElse("")) match {
          case None =>
            new DumboValidationException(
              s"Detected applied migration not resolved locally ${h.script}"
            ).invalidNec[Unit]
          case Some(value) if Some(value.checksum) != h.checksum =>
            new DumboValidationException(
              s"""|Validate failed: Migrations have failed validation
                  |Migration checksum mismatch for migration version ${h.version}
                  |-> Applied to database : ${h.checksum.fold("null")(_.toString)}
                  |-> Resolved locally    : ${value.checksum}
                  |Either revert the changes to the migration ${value.description.path.fileName} or update the checksum in $historyTable""".stripMargin
            ).invalidNec[Unit]

          case Some(value) if value.scriptDescription != h.description =>
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
      .filter(f => f.extName.endsWith(".sql") || f.extName.endsWith(".sql.conf"))
      .evalMap { path =>
        val confPath = Path(path.toString + ".conf")

        fs.exists(confPath)
          .flatMap {
            case true  => fs.readUtf8Lines(confPath).compile.toList.map(ResourceFileConfig.fromLines)
            case false => Set.empty[ResourceFileConfig].asRight[String].pure[F]
          }
          .flatMap {
            case Right(configs) =>
              ResourceFileDescription.fromFilePath(path) match {
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
  private[dumbo] def checksum[F[_]: Sync](p: Path, fs: ResourceReader[F]): F[Int] =
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
