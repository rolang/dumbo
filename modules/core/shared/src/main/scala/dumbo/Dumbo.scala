package dumbo

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

import scala.util.matching.Regex

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.implicits.*
import cats.{Applicative, ApplicativeError}
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

  private def migrateToNext(
    session: Session[F],
    fs: FsPlatform[F],
  )(sourceFiles: Map[Int, SourceFile]): F[Option[(HistoryEntry, Map[Int, SourceFile])]] =
    session.transaction.use { _ =>
      for {
        _              <- session.execute(sql"LOCK #${historyTable}".command)
        history        <- session.execute(dumboHistory.loadAllQuery)
        latestInstalled = history.map(_.installedRank).sorted(Ordering[Int].reverse).headOption.getOrElse(0)
        _              <- Console[F].println(s"Current version of database: $latestInstalled")
        _ <- history.filter(_.`type` == "SQL").traverse_ { h =>
               sourceFiles.get(h.installedRank) match {
                 case None =>
                   Sync[F].raiseError[Unit](new Throwable(s"Missing source file ${h.script}"))
                 case Some(value) if Some(value.checksum) != h.checksum =>
                   Sync[F].raiseError[Unit](
                     new Throwable(
                       s"""|Validate failed: Migrations have failed validation
                           |Migration checksum mismatch for migration version ${h.installedRank}
                           |  -> Applied to database : ${h.checksum.fold("null")(_.toString)}
                           |  -> Resolved locally    : ${value.checksum}
                           |Either revert the changes to the migration ${value.path.fileName} or update the checksum in $historyTable""".stripMargin
                     )
                   )
                 case _ => Sync[F].unit
               }
             }
        result <- sourceFiles.values.toList.sortBy(_.rank).find(_.rank > latestInstalled) match {
                    case Some(x) =>
                      for {
                        _     <- session.execute(sql"SET SEARCH_PATH = #${allSchemas.toList.mkString(",")}".command)
                        entry <- transact(x, fs, session)
                      } yield (entry, sourceFiles).some
                    case _ => Sync[F].pure(None)
                  }
      } yield result
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
                           sourceFiles <- Dumbo.readSourceFiles[F](sourceDir, fs).compile.toList.flatMap { sf =>
                                            val map = sf.map(s => (s.rank, s)).toMap

                                            sf.diff(map.values.toList) match {
                                              case Nil => Applicative[F].pure(map)
                                              case diff =>
                                                ApplicativeError[F, Throwable].raiseError[Map[Int, SourceFile]](
                                                  new Throwable(
                                                    s"Found more than one migration with versions ${diff.map(_.rank).mkString(", ")}"
                                                  )
                                                )
                                            }
                                          }
                           _ <- Console[F].println(
                                  s"Found ${sourceFiles.size} versioned migration files in ${fs.sourcesUri}"
                                )
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
  ) = new Dumbo[F](
    sourceDir = sourceDir,
    defaultSchema = defaultSchema,
    schemas = schemas,
    schemaHistoryTable = schemaHistoryTable,
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
