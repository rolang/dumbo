// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.effect.IO
import dumbo.exception.{MultipleResoucesException, ResourcesLocationNotFund}

class DumboJvmSpecScala2 extends ffstest.FTest {
  test("fail on multiple resources") {
    for {
      result <- Dumbo.withResourcesIn[IO]("duplicated").listMigrationFiles.attempt
      _ = result match {
            case Right(_)  => fail("Expecting a failure")
            case Left(err) => assert(err.isInstanceOf[MultipleResoucesException])
          }
    } yield ()
  }

  test("fail with ResourcesLocationNotFund") {
    for {
      result <- Dumbo.withResourcesIn[IO]("db/non_existing/path").listMigrationFiles.attempt
      _       = assert(result.isLeft)
      _ = assert(
            result.left.exists(e =>
              e.isInstanceOf[ResourcesLocationNotFund] && e.getMessage().contains("db/non_existing/path")
            )
          )
    } yield ()
  }
}
