// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

import scala.util.matching.Regex

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.implicits.*
import dumbo.internal.FsPlatform
import fs2.Stream
import fs2.io.file.*
import skunk.*
import skunk.data.Completion
import skunk.implicits.*
import skunk.util.Origin
import skunk.Command as SqlCommand

class Dumbo[F[_]: Sync: Console: Files](
  sourceDir: Path,
  defaultSchema: String = "public",
  schemas: Set[String] = Set.empty,
  schemaHistoryTable: String = "flyway_schema_history",
  validateOnMigrate: Boolean = true, // validate applied migrations against the available ones
) {
  private val allSchemas   = NonEmptyList.of(defaultSchema, schemas.toList*)
  private val historyTable = s"${defaultSchema}.${schemaHistoryTable}"
  private val dumboHistory = History(historyTable)

  private def initSchemaCmd(schema: String) = sql"CREATE SCHEMA IF NOT EXISTS #${schema}".command

  private def transact(source: SourceFile, fs: FsPlatform[F], session: Session[F]): F[HistoryEntry] =
    for {
      _ <-
        Console[F].println(s"""Migrating schema "$defaultSchema" to version ${source.rank} - ${source.description}""")

      statements <- fs.readUtf8Lines(source.path)
                      .compile
                      .toList
                      .map(_.mkString("\n").split(";").toList.filter(_.trim.nonEmpty))

      (duration, _) <- Sync[F].timed {
                         statements.traverse(statement =>
                           session.execute(
                             SqlCommand(
                               sql = statement,
                               origin = Origin(source.path.toString, line = 0),
                               Void.codec,
                             )
                           )
                         )
                       }
      entry <- session
                 .unique(dumboHistory.insertSQLEntry)(
                   HistoryEntry.New(
                     installedRank = source.rank,
                     version = source.rank.toString(),
                     description = source.description,
                     `type` = "SQL",
                     script = source.path.fileName.toString,
                     checksum = Some(source.checksum),
                     executionTimeMs = duration.toMillis.toInt,
                     success = true,
                   )
                 )
      _ <- Console[F].println(s"Migration to version ${source.rank} - ${source.description} completed")
    } yield entry

  private def validationGuard(session: Session[F], sourceFiles: List[SourceFile]) =
    sourceFiles match {
      case head :: _ =>
        for {
          history       <- session.execute(dumboHistory.loadAllQuery)(head.rank)
          sourceFilesMap = sourceFiles.map(s => (s.rank, s)).toMap
          _ <- history.filter(_.`type` == "SQL").traverse_ { h =>
                 sourceFilesMap.get(h.installedRank) match {
                   case None =>
                     Sync[F].raiseError[Unit](
                       new dumbo.exception.DumboValidationException(
                         s"Detected applied migration not resolved locally ${h.script}"
                       )
                     )
                   case Some(value) if Some(value.checksum) != h.checksum =>
                     Sync[F].raiseError[Unit](
                       new dumbo.exception.DumboValidationException(
                         s"""|Validate failed: Migrations have failed validation
                             |Migration checksum mismatch for migration version ${h.installedRank}
                             |  -> Applied to database : ${h.checksum.fold("null")(_.toString)}
                             |  -> Resolved locally    : ${value.checksum}
                             |Either revert the changes to the migration ${value.path.fileName} or update the checksum in $historyTable""".stripMargin
                       )
                     )
                   case _ => Applicative[F].unit
                 }
               }
        } yield ()
      case _ => Applicative[F].unit
    }

  private def migrateToNext(
    session: Session[F],
    fs: FsPlatform[F],
  )(sourceFiles: List[SourceFile]): F[Option[(HistoryEntry, List[SourceFile])]] =
    sourceFiles match {
      case Nil => Sync[F].pure(None)
      case _ =>
        session.transaction.use { _ =>
          for {
            _               <- session.execute(sql"LOCK #${historyTable}".command)
            latestInstalled <- session.unique(dumboHistory.findLatestRank).map(_.getOrElse(0))
            _               <- Console[F].println(s"Current version of database: $latestInstalled")
            result <- sourceFiles.dropWhile(_.rank <= latestInstalled) match {
                        case head :: tail =>
                          for {
                            _     <- session.execute(sql"SET SEARCH_PATH = #${allSchemas.toList.mkString(",")}".command)
                            entry <- transact(head, fs, session)
                          } yield (entry, tail).some
                        case _ => Sync[F].pure(None)
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

  def migrate(session: Session[F]): F[Dumbo.MigrationResult] = for {
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
           case _            => Sync[F].unit
         }

    migrationResult <- FsPlatform.forDir[F](sourceDir).use { fs =>
                         for {
                           sourceFiles <- Dumbo
                                            .readSourceFiles[F](sourceDir, fs)
                                            .compile
                                            .toList
                                            .flatMap { sf =>
                                              val ranks = sf.map(_.rank)

                                              ranks.diff(ranks.distinct) match {
                                                case Nil => Applicative[F].pure(sf.sortBy(_.rank))
                                                case diff =>
                                                  Sync[F].raiseError[List[SourceFile]](
                                                    new Throwable(
                                                      s"Found more than one migration with versions ${diff.mkString(", ")}"
                                                    )
                                                  )
                                              }
                                            }
                           _ <- Console[F].println(
                                  s"Found ${sourceFiles.size} versioned migration files in ${fs.sourcesUri}"
                                )
                           _ <- if (validateOnMigrate) validationGuard(session, sourceFiles) else Applicative[F].unit
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

  def listMigrationFiles: Stream[F, SourceFile] = Stream.resource(FsPlatform.forDir[F](sourceDir)).flatMap { fs =>
    Dumbo.readSourceFiles[F](sourceDir, fs)
  }
}

object Dumbo {
  final case class MigrationResult(migrations: List[HistoryEntry]) {
    val migrationsExecuted: Int = migrations.length
  }

  def apply[F[_]: Sync: Console: Files](
    sourceDir: Path,
    defaultSchema: String = "public",
    schemas: Set[String] = Set.empty[String],
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
  ) = new Dumbo[F](
    sourceDir = sourceDir,
    defaultSchema = defaultSchema,
    schemas = schemas,
    schemaHistoryTable = schemaHistoryTable,
    validateOnMigrate = validateOnMigrate,
  )

  private[dumbo] def readSourceFiles[F[_]: Sync](dir: Path, fs: FsPlatform[F]): Stream[F, SourceFile] =
    fs.list(dir)
      .filter(_.extName.endsWith(".sql"))
      .map { p =>
        val pattern: Regex = "^V(\\d+)__(.+)\\.sql$".r

        p.fileName.toString match {
          case pattern(rank, name) => Right((p, rank.toInt, name.replace("_", " ")))
          case other               => Left(s"Invalid file name $other")
        }
      }
      .evalMap {
        case Right((path, rank, desc)) =>
          for {
            checksum     <- checksum[F](path, fs)
            lastModified <- fs.getLastModifiedTime(path)
          } yield SourceFile(
            rank = rank,
            description = desc,
            path = path,
            checksum = checksum,
            lastModified = lastModified,
          )
        case Left(err) => Sync[F].raiseError[SourceFile](new Throwable(err))
      }

  // implementation of checksum from Flyway
  // https://github.com/flyway/flyway/blob/main/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java#L59
  private[dumbo] def checksum[F[_]: Sync](p: Path, fs: FsPlatform[F]): F[Int] =
    for {
      crc32 <- Ref.of[F, CRC32](new CRC32())
      _ <- fs.readUtf8Lines(p)
             .evalMap { line =>
               crc32.update { c => c.update(line.getBytes(StandardCharsets.UTF_8)); c }
             }
             .compile
             .drain
      value <- crc32.get.map(_.getValue().toInt)
    } yield value

}
