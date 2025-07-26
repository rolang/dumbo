import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

object ExampleLog4Cats extends IOApp.Simple:
  def run =
    Slf4jLogger
      .create[IO]
      .flatMap: logger =>
        given dumbo.logging.Logger[IO] =
          case (dumbo.logging.LogLevel.Info, message) => logger.info(message)
          case (dumbo.logging.LogLevel.Warn, message) => logger.warn(message)

        Dumbo
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
            defaultSchema = "dumbo",
          )
          .runMigration
          .flatMap: result =>
            logger.info(s"Migration completed with ${result.migrationsExecuted} migrations")
