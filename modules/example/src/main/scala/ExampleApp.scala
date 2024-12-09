//> using scala 3.3.3
//> using resourceDir ../resources
//> using dep "dev.rolang::dumbo::0.5.3"

import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

object ExampleApp extends IOApp.Simple {
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
      )
    )
    .runMigration
    .flatMap { result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
    }
}
