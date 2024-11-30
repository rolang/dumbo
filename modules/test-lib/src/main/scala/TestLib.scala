import cats.effect.{IO, IOApp}
import cats.effect.ExitCode
import dumbo.ResourceFilePath

object TestLib extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    val resources = ResourceFilePath.fromResourcesDir("sample_lib")
    val expected = List(
      "/sample_lib/V1__test.sql",
      "/sample_lib/V2__test_b.sql",
    )

    if expected == expected
    then IO.println("Test ok").as(ExitCode.Success)
    else IO.println(s"Expected resources $expected\nbut got $resources").as(ExitCode.Error)
}
