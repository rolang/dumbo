import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import cats.effect.ExitCode

object TestLib extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Dumbo
      .withResourcesIn[IO]("sample_lib")
      .apply(
        connection = ConnectionConfig(
          host = "localhost",
          port = 5432,
          user = "root",
          database = "postgres",
          password = None,
          ssl = ConnectionConfig.SSL.None,
        )
      )
      .runMigration
      .flatMap { result =>
        val expectedMigrations = 2

        if (result.migrationsExecuted != expectedMigrations) {
          IO.println(
            s"Expected execution of $expectedMigrations migrations, but got ${result.migrationsExecuted}"
          ).as(ExitCode.Error)
        } else {
          IO.println("Test ok").as(ExitCode.Success)
        }
      }
}
