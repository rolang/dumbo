// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.implicits.*

class DumboJvmSpec extends ffstest.FTest {

  test("find resource in main") {
    for {
      result <- Dumbo.withResourcesIn[IO]("main").listMigrationFiles
      _       = result match {
            case Valid(f)      => assert(f.exists(_.path.fileName.toString == "V1__dummy.sql"))
            case Invalid(errs) => fail(errs.toList.mkString("\n"))
          }
    } yield ()
  }

}
