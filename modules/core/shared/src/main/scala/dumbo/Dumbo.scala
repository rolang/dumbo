// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

import scala.concurrent.duration.*

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyChain, NonEmptyList, ValidatedNec}
import cats.effect.kernel.Clock
import cats.effect.std.Console
import cats.effect.{Async, Resource, Sync}
import cats.implicits.*
import dumbo.exception.DumboValidationException
import dumbo.internal.FsPlatform
import fs2.Stream
import fs2.io.file.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*
import skunk.util.Origin
import skunk.Command as SqlCommand

class Dumbo[F[_]: Async: Console: Files](
  sourceDir: Path,
  sessionResource: Resource[F, Session[F]],
  defaultSchema: String = "public",
  schemas: Set[String] = Set.empty,
  schemaHistoryTable: String = "flyway_schema_history",
  validateOnMigrate: Boolean = true, // validate applied migrations against the available ones
  logMigrationStateAfter: Duration = Duration.Inf,
) {
  import Dumbo.*

  private val allSchemas   = NonEmptyList.of(defaultSchema, schemas.toList*)
  private val historyTable = s"${defaultSchema}.${schemaHistoryTable}"
  private val dumboHistory = History(historyTable)

  private def initSchemaCmd(schema: String) = sql"CREATE SCHEMA IF NOT EXISTS #${schema}".command

  private def transact(source: SourceFile, fs: FsPlatform[F], session: Session[F]): F[HistoryEntry] =
    for {
      _ <-
        Console[F].println(
          s"""Migrating schema "$defaultSchema" to version ${source.versionRaw} - ${source.scriptDescription}"""
        )

      statement <- fs.readUtf8(source.path)
                     .compile
                     .toList
                     .map(_.mkString)

      (duration, _) <- Sync[F].timed {
                         session
                           .execute(
                             SqlCommand(
                               sql = statement,
                               origin = Origin(source.path.toString, line = 0),
                               Void.codec,
                             )
                           )
                       }
      entry <- session
                 .unique(dumboHistory.insertSQLEntry)(
                   HistoryEntry.New(
                     version = source.versionRaw,
                     description = source.scriptDescription,
                     `type` = "SQL",
                     script = source.path.fileName.toString,
                     checksum = Some(source.checksum),
                     executionTimeMs = duration.toMillis.toInt,
                     success = true,
                   )
                 )
      _ <- Console[F].println(s"Migration to version ${source.versionRaw} - ${source.scriptDescription} completed")
    } yield entry

  private def validationGuard(session: Session[F], sourceFiles: List[SourceFile]) =
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

  private def awaitLockMonitoring =
    if (logMigrationStateAfter.isFinite) {
      val interval = FiniteDuration(logMigrationStateAfter.toMillis, MILLISECONDS)

      Async[F].background {
        (for {
          _ <- Stream.sleep[F](FiniteDuration(logMigrationStateAfter.toMillis, MILLISECONDS))
          _ <-
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
              .evalMap {
                case pid *: start *: changed *: state *: eventType *: event *: query *: _ =>
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

                case _ => Sync[F].unit
              }
              .repeat
              .metered(interval)
        } yield ()).compile.drain
      }
    } else {
      Resource.unit[F]
    }

  private def migrateToNext(
    session: Session[F],
    fs: FsPlatform[F],
  )(sourceFiles: List[SourceFile]): F[Option[(HistoryEntry, List[SourceFile])]] =
    sourceFiles match {
      case Nil => none.pure[F]
      case _ =>
        (for {
          txn <- session.transaction
          _   <- awaitLockMonitoring
        } yield txn).use { _ =>
          for {
            _               <- session.execute(sql"LOCK TABLE #${historyTable} IN ACCESS EXCLUSIVE MODE".command)
            latestInstalled <- session.unique(dumboHistory.findLatestInstalled).map(_.flatMap(_.sourceFileVersion))
            result <- sourceFiles.dropWhile(s => latestInstalled.exists(s.version <= _)) match {
                        case head :: tail =>
                          for {
                            _     <- session.execute(sql"SET SEARCH_PATH = #${allSchemas.toList.mkString(",")}".command)
                            entry <- transact(head, fs, session)
                          } yield (entry, tail).some
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

  @deprecated("Use runMigration by passing sessionResource: Resource[F, Session[F]] to the Dumbo constructor", "0.0.3")
  def migrate(session: Session[F]): F[Dumbo.MigrationResult] = migrateBySession(session)

  private def migrateBySession(session: Session[F]): F[Dumbo.MigrationResult] = for {
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

    migrationResult <- FsPlatform.forDir[F](sourceDir).use { fs =>
                         for {
                           sourceFiles <- listMigrationFiles(sourceDir, fs).flatMap {
                                            case Valid(f) => f.pure[F]
                                            case Invalid(errs) =>
                                              new DumboValidationException(
                                                s"Error while reading migration files:\n${errs.toList.mkString("\n")}"
                                              ).raiseError[F, List[SourceFile]]
                                          }
                           _ <- Console[F].println(
                                  s"Found ${sourceFiles.size} versioned migration files in ${fs.sourcesUri}"
                                )
                           _ <- if (validateOnMigrate) validationGuard(session, sourceFiles) else ().pure[F]
                           migrationResult <- Stream
                                                .unfoldEval(sourceFiles)(migrateToNext(session, fs))
                                                .compile
                                                .toList
                                                .map(Dumbo.MigrationResult(_))
                         } yield migrationResult
                       }

    _ <- migrationResult.migrations.map(_.installedRank).sorted(Ordering[Int].reverse).headOption match {
           case None =>
             Console[F]
               .println(s"Schema ${defaultSchema} is up to date. No migration necessary")
           case Some(latestInstalled) =>
             Console[F]
               .println(
                 s"Successfully applied ${migrationResult.migrations.length} migrations, now at version ${latestInstalled}"
               )
         }
  } yield migrationResult

  private def validate(
    history: List[HistoryEntry],
    sourceFiles: List[SourceFile],
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

  @deprecated(
    "Use runValidationWithHistory by passing sessionResource: Resource[F, Session[F]] to the Dumbo constructor",
    "0.0.3",
  )
  def validateWithAppliedMigrations(session: Session[F]): F[ValidatedNec[DumboValidationException, Unit]] =
    listMigrationFiles(sourceDir).flatMap {
      case Valid(sourceFiles) =>
        session.execute(dumboHistory.loadAllQuery).map(history => validate(history, sourceFiles))
      case Invalid(c) => c.invalid.pure[F]
    }

  def runValidationWithHistory: F[ValidatedNec[DumboValidationException, Unit]] =
    listMigrationFiles(sourceDir).flatMap {
      case Valid(sourceFiles) =>
        sessionResource.use(_.execute(dumboHistory.loadAllQuery).map(history => validate(history, sourceFiles)))
      case Invalid(c) => c.invalid.pure[F]
    }
}

object Dumbo {
  final case class MigrationResult(migrations: List[HistoryEntry]) {
    val migrationsExecuted: Int = migrations.length
  }

  def apply[F[_]: Async: Console: Files](
    sourceDir: Path,
    sessionResource: Resource[F, Session[F]],
    defaultSchema: String = "public",
    schemas: Set[String] = Set.empty[String],
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
    logMigrationStateAfter: Duration = Duration.Inf,
  ) = new Dumbo[F](
    sourceDir = sourceDir,
    sessionResource = sessionResource,
    defaultSchema = defaultSchema,
    schemas = schemas,
    schemaHistoryTable = schemaHistoryTable,
    validateOnMigrate = validateOnMigrate,
    logMigrationStateAfter = logMigrationStateAfter,
  )

  def listMigrationFiles[F[_]: Sync: Files](
    sourceDir: Path
  ): F[ValidatedNec[DumboValidationException, List[SourceFile]]] =
    FsPlatform.forDir[F](sourceDir).use(listMigrationFiles(sourceDir, _))

  private[dumbo] def listMigrationFiles[F[_]: Sync](
    sourceDir: Path,
    fs: FsPlatform[F],
  ): F[ValidatedNec[DumboValidationException, List[SourceFile]]] =
    readSourceFiles[F](sourceDir, fs).compile.toList.map { sf =>
      val (errs, files) = (sf.collect { case Left(err) => err }, sf.collect { case Right(v) => v })
      val duplicates    = files.groupBy(_.version).filter(_._2.length > 1).toList

      (duplicates, errs.map(new DumboValidationException(_))) match {
        case (Nil, Nil)     => files.sorted.validNec[DumboValidationException]
        case (Nil, x :: xs) => NonEmptyChain(x, xs*).invalid[List[SourceFile]]
        case (diff, exceptions) =>
          NonEmptyChain(
            new DumboValidationException(
              s"""|Found more than one migration with versions ${diff.map(_._1.toString).mkString(", ")}\n
                  |Offenders:\n${diff.flatMap(_._2.map(_.path)).mkString("\n")}""".stripMargin
            ),
            exceptions*
          ).invalid[List[SourceFile]]
      }
    }

  private[dumbo] def readSourceFiles[F[_]: Sync](dir: Path, fs: FsPlatform[F]): Stream[F, Either[String, SourceFile]] =
    fs.list(dir)
      .filter(_.extName.endsWith(".sql")) // TODO: include .conf files
      .evalMap { path =>
        SourceFileDescription.fromFilePath(path) match {
          case Right(desc) =>
            for {
              checksum     <- checksum[F](path, fs)
              lastModified <- fs.getLastModifiedTime(path)
            } yield SourceFile(
              description = desc,
              checksum = checksum,
              lastModified = lastModified,
            ).asRight[String]
          case Left(err) => err.asLeft[SourceFile].pure[F]
        }
      }

  // implementation of checksum from Flyway
  // https://github.com/flyway/flyway/blob/main/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java#L59
  private[dumbo] def checksum[F[_]: Sync](p: Path, fs: FsPlatform[F]): F[Int] =
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
