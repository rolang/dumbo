package dumbo
import dumbo.internal.MultipleResoucesException
import cats.effect.IO
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
