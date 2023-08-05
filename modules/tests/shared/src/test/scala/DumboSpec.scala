// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.implicits.*
import fs2.io.file.Path

class DumboSpec extends ffstest.FTest {
  override val postgresPort: Int = 5432

  def assertEqualHistory(histA: List[HistoryEntry], histB: List[HistoryEntry]): Unit = {
    def toCompare(h: HistoryEntry) =
      (h.installedRank, h.version, h.script, h.checksum, h.`type`, h.installedBy, h.success)

    assertEquals(histA.map(toCompare), histB.map(toCompare))
  }

  test("Run multiple migrations concurrently") {
    val schema = "schema_1"

    (1 to 5).toList.traverse_ { _ =>
      for {
        _       <- dropSchemas
        res     <- (1 to 20).toList.parTraverse(_ => dumboMigrate(schema, Path("db/test_1")))
        ranks    = res.flatMap(_.migrations.map(_.installedRank)).sorted
        _        = assertEquals(ranks, List(1, 2, 3))
        history <- loadHistory(schema)
        _        = assert(history.length == 4)
      } yield ()
    }
  }

  dbTest("Validate checksum with validation enabled") {
    val schema = "schema_1"

    for {
      _    <- dumboMigrate(schema, Path("db/test_1"))
      res  <- dumboMigrate(schema, Path("db/test_1_changed_checksum"), validateOnMigrate = true).attempt
      _     = assert(res.isLeft)
      _     = assert(res.left.exists(_.getMessage().contains("checksum mismatch")))
      vRes <- validateWithAppliedMigrations(schema, Path("db/test_1_changed_checksum"))
      _ = vRes match {
            case Invalid(errs) => assert(errs.toList.exists(_.getMessage().contains("checksum mismatch")))
            case _             => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Validate description with validation enabled") {
    val schema = "schema_1"

    for {
      _   <- dumboMigrate(schema, Path("db/test_1"))
      res <- dumboMigrate(schema, Path("db/test_1_desc_changed"), validateOnMigrate = true).attempt
      _    = assert(res.isLeft)
      _ = assert(res.left.exists { err =>
            val message = err.getMessage()
            message.contains("description mismatch") &&
              message.contains("test changed") &&
              message.contains("test b")
          })
      vRes <- validateWithAppliedMigrations(schema, Path("db/test_1_desc_changed"))
      _ = vRes match {
            case Invalid(errs) =>
              assert(errs.exists { err =>
                val message = err.getMessage()
                message.contains("description mismatch") &&
                  message.contains("test changed") &&
                  message.contains("test b")
              })
            case _ => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Validate for missing files with validation enabled") {
    val schema = "schema_1"

    for {
      _    <- dumboMigrate(schema, Path("db/test_1"))
      res  <- dumboMigrate(schema, Path("db/test_1_missing_file"), validateOnMigrate = true).attempt
      _     = assert(res.isLeft)
      _     = assert(res.left.exists(_.isInstanceOf[dumbo.exception.DumboValidationException]))
      _     = assert(res.left.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
      vRes <- validateWithAppliedMigrations(schema, Path("db/test_1_missing_file"))
      _ = vRes match {
            case Invalid(errs) =>
              assert(errs.toList.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
            case _ => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Ignore missing files or missing checksum on validation disabled") {
    val schema = "schema_1"

    for {
      _    <- dumboMigrate(schema, Path("db/test_1"))
      resA <- dumboMigrate(schema, Path("db/test_1_missing_file"), validateOnMigrate = false).attempt
      resB <- dumboMigrate(schema, Path("db/test_1_changed_checksum"), validateOnMigrate = false).attempt
      _     = assert(resA.isRight && resB.isRight)
    } yield ()
  }

  test("list migration files from resources") {
    for {
      files <- Dumbo[IO](resourcesPath(Path("db/test_1"))).listMigrationFiles
      _ = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (SourceFileVersion("1", NonEmptyList.of(1)), "V1__test.sql"),
                  (SourceFileVersion("2", NonEmptyList.of(2)), "V2__test_b.sql"),
                  (SourceFileVersion("3", NonEmptyList.of(3)), "V3__test_c.sql"),
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("list migration files from relative path") {
    for {
      files <- Dumbo[IO](Path("modules/tests/shared/src/test/non_resource/db/test_1")).listMigrationFiles
      _ = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (SourceFileVersion("1", NonEmptyList.of(1)), "V1__non_resource.sql")
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("list migration files from absolute path") {
    for {
      files <- Dumbo[IO](
                 Path("modules/tests/shared/src/test/non_resource/db/test_1").absolute
               ).listMigrationFiles
      _ = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (SourceFileVersion("1", NonEmptyList.of(1)), "V1__non_resource.sql")
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("fail with NoSuchFileException") {
    for {
      result <- Dumbo[IO](resourcesPath(Path("db/non_existing/path"))).listMigrationFiles.attempt
      _       = assert(result.isLeft)
      _ = assert(
            result.left.exists(e =>
              e.isInstanceOf[java.nio.file.NoSuchFileException] && e.getMessage().endsWith("db/non_existing/path")
            )
          )
    } yield ()
  }

  dbTest("fail on files with same versions") {
    for {
      result <- Dumbo[IO](resourcesPath(Path("db/test_duplicate_versions"))).listMigrationFiles
      _ = result match {
            case Invalid(errs) =>
              assert(errs.toList.exists { err =>
                val message = err.getMessage()

                message.contains("Found more than one migration with versions 0.1, 1") &&
                  message.contains("V01__test.sql") &&
                  message.contains("V1.0__test.sql") &&
                  message.contains("V001__test.sql") &&
                  message.contains("V0.1__test.sql") &&
                  message.contains("V0.001.0__test.sql") &&
                  message.contains("V0.1.0.0__test.sql")
              })
            case _ => fail("expected failure")
          }
    } yield ()
  }
}
