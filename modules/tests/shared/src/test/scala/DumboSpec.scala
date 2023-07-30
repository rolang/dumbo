// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.effect.IO
import cats.implicits.*
import fs2.io.file.Path

class DumboSpec extends ffstest.FTest {
  def assertEqualHistory(histA: List[HistoryEntry], histB: List[HistoryEntry]): Unit = {
    def toCompare(h: HistoryEntry) =
      (h.installedRank, h.version, h.script, h.checksum, h.`type`, h.installedBy, h.success)

    assertEquals(histA.map(toCompare), histB.map(toCompare))
  }

  test("Run multiple migrations concurrently") {
    val schema = "schema_1"

    (1 to 5).toList.traverse_ { _ =>
      for {
        _              <- dropSchemas
        res            <- (1 to 20).toList.parTraverse(_ => dumboMigrate(schema, Path("db/test_1"))).attempt
        _               = res.left.foreach(e => println(s"Error: $e"))
        _               = assert(res.isRight)
        totalMigrations = res.map(_.map(_.migrationsExecuted).sum).getOrElse(0)
        _               = println(s"Total migrations: $totalMigrations")
        _               = assert(totalMigrations == 3)
        history        <- loadHistory(schema)
        _               = assert(history.length == 4)
      } yield ()
    }
  }

  dbTest("Validate checksum with validation enabled") {
    val schema = "schema_1"

    for {
      _   <- dumboMigrate(schema, Path("db/test_1"))
      res <- dumboMigrate(schema, Path("db/test_1_changed_checksum"), validateOnMigrate = true).attempt
      _    = assert(res.isLeft)
      _    = assert(res.left.exists(_.getMessage().contains("checksum mismatch")))
    } yield ()
  }

  dbTest("Validate for missing files with validation enabled") {
    val schema = "schema_1"

    for {
      _   <- dumboMigrate(schema, Path("db/test_1"))
      res <- dumboMigrate(schema, Path("db/test_1_missing_file"), validateOnMigrate = true).attempt
      _    = assert(res.isLeft)
      _    = assert(res.left.exists(_.isInstanceOf[dumbo.exception.DumboValidationException]))
      _    = assert(res.left.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
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
      files <- Dumbo[IO](resourcesPath(Path("db/test_1"))).listMigrationFiles.compile.toList
      _ = assert(
            files.sortBy(_.rank).map(f => (f.rank, f.path.fileName.toString)) == List(
              (1, "V1__test.sql"),
              (2, "V2__test_b.sql"),
              (3, "V3__test_c.sql"),
            )
          )
    } yield ()
  }

  test("list migration files from relative path") {
    for {
      files <- Dumbo[IO](Path("modules/tests/shared/src/test/non_resource/db/test_1")).listMigrationFiles.compile.toList
      _ = assert(
            files.sortBy(_.rank).map(f => (f.rank, f.path.fileName.toString)) == List(
              (1, "V1__non_resource.sql")
            )
          )
    } yield ()
  }

  test("list migration files from absolute path") {
    for {
      files <- Dumbo[IO](
                 Path("modules/tests/shared/src/test/non_resource/db/test_1").absolute
               ).listMigrationFiles.compile.toList
      _ = assert(
            files.sortBy(_.rank).map(f => (f.rank, f.path.fileName.toString)) == List(
              (1, "V1__non_resource.sql")
            )
          )
    } yield ()
  }

  test("fail with NoSuchFileException") {
    for {
      result <- Dumbo[IO](resourcesPath(Path("db/non_existing/path"))).listMigrationFiles.compile.toList.attempt
      _       = assert(result.isLeft)
      _ = assert(
            result.left.exists(e =>
              e.isInstanceOf[java.nio.file.NoSuchFileException] && e.getMessage().endsWith("db/non_existing/path")
            )
          )
    } yield ()
  }
}
