// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.effect.IO
import dumbo.exception.MultipleResoucesException

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
}
