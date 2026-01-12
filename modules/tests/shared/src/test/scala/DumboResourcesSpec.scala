// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.implicits.*
import fs2.io.file.Path

class DumboResourcesSpec extends ffstest.FTest {
  test("list migration files from resources") {
    for {
      files <- dumboWithResources("db/test_1").listMigrationFiles
      _      = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (ResourceVersion.Versioned("1", NonEmptyList.of(1)), "V1__test.sql"),
                  (ResourceVersion.Versioned("2", NonEmptyList.of(2)), "V2__test_b.sql"),
                  (ResourceVersion.Versioned("3", NonEmptyList.of(3)), "V3__test_c.sql"),
                  (ResourceVersion.Repeatable("test view"), "R__test_view.sql"),
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("list migration files from resources with subirectories") {
    for {
      files <- dumboWithResources("db/nested").listMigrationFiles
      _      = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (ResourceVersion.Versioned("1", NonEmptyList.of(1)), "V1__test.sql"),
                  (ResourceVersion.Versioned("2", NonEmptyList.of(2)), "V2__test.sql"),
                  (ResourceVersion.Versioned("3", NonEmptyList.of(3)), "V3__test.sql"),
                  (ResourceVersion.Versioned("4", NonEmptyList.of(4)), "V4__test.sql"),
                  (ResourceVersion.Repeatable("a"), "R__a.sql"),
                  (ResourceVersion.Repeatable("b"), "R__b.sql"),
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("list migration files from relative path") {
    for {
      files <- Dumbo.withFilesIn[IO](Path("modules/tests/shared/src/test/non_resource/db/test_1")).listMigrationFiles
      _      = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (ResourceVersion.Versioned("1", NonEmptyList.of(1)), "V1__non_resource.sql"),
                  (ResourceVersion.Versioned("2", NonEmptyList.of(2)), "V2__non_resource.sql"),
                  (ResourceVersion.Versioned("3", NonEmptyList.of(3)), "V3__non_resource.sql"),
                  (ResourceVersion.Versioned("4", NonEmptyList.of(4)), "V4__non_resource.sql"),
                  (ResourceVersion.Repeatable("a"), "R__a.sql"),
                  (ResourceVersion.Repeatable("b"), "R__b.sql"),
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("list migration files from absolute path") {
    for {
      files <-
        Dumbo.withFilesIn[IO](Path("modules/tests/shared/src/test/non_resource/db/test_1").absolute).listMigrationFiles
      _ = files match {
            case Valid(files) =>
              assert(
                files.sorted.map(f => (f.version, f.path.fileName.toString)) == List(
                  (ResourceVersion.Versioned("1", NonEmptyList.of(1)), "V1__non_resource.sql"),
                  (ResourceVersion.Versioned("2", NonEmptyList.of(2)), "V2__non_resource.sql"),
                  (ResourceVersion.Versioned("3", NonEmptyList.of(3)), "V3__non_resource.sql"),
                  (ResourceVersion.Versioned("4", NonEmptyList.of(4)), "V4__non_resource.sql"),
                  (ResourceVersion.Repeatable("a"), "R__a.sql"),
                  (ResourceVersion.Repeatable("b"), "R__b.sql"),
                )
              )
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

  test("fail on files with same versions") {
    for {
      result <- dumboWithResources("db/test_duplicate_versions").listMigrationFiles
      _       = result match {
            case Invalid(errs) =>
              assert(errs.toList.exists { err =>
                val message = err.getMessage()

                message.contains("Found more than one migration with versions") &&
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

  test("handle non-existent directory without NPE") {
    for {
      files <- Dumbo.withFilesIn[IO](Path("/non/existent/directory")).listMigrationFiles
      _      = files match {
            case Valid(files) =>
              assert(files.isEmpty, "Expected empty list for non-existent directory")
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }
}
