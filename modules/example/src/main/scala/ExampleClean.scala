import cats.effect.{IO, IOApp}
import dumbo.logging.Implicits.console
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import org.typelevel.otel4s.metrics.Meter.Implicits.noop

object ExampleClean extends IOApp.Simple:
  def run = Dumbo
    .withResourcesIn[IO]("db/migration")
    .apply(
      connection = ConnectionConfig(
        host = "localhost",
        port = 5432,
        user = "root",
        database = "postgres",
        password = None,
        ssl = ConnectionConfig.SSL.None,
      ),
      cleanDisabled = false,
    )
    .runClean
