// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo
import cats.effect.IO
import dumbo.internal.MultipleResoucesException
import fs2.io.file.Path

class DumboJvmSpec extends ffstest.FTest {

  test("find resource in main") {
    for {
      result <- Dumbo[IO](Path("main")).listMigrationFiles.compile.toList.attempt
      _       = assert(result.isRight)
      _       = assert(result.exists(_.exists(_.path.fileName.toString == "V1__dummy.sql")))
    } yield ()
  }

  test("fail on multiple resources") {
    for {
      result <- Dumbo[IO](Path("duplicated")).listMigrationFiles.compile.toList.attempt
      _       = assert(result.isLeft)
      _ = assert(
            result.left.exists(e => e.isInstanceOf[MultipleResoucesException])
          )
    } yield ()
  }
}
