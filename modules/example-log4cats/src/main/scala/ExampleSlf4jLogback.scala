import java.nio.charset.Charset

import cats.Show
import cats.effect.std.Console
import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

object ExampleSlf4jLogback extends IOApp.Simple:
  given [F[_]: Logger]: Console[F] = new Console[F] {
    override def readLineWithCharset(charset: Charset): F[String] = ???
    override def print[A](a: A)(using S: Show[A]): F[Unit]        = Logger[F].info(S.show(a))
    override def println[A](a: A)(using S: Show[A]): F[Unit]      = Logger[F].info(S.show(a))
    override def error[A](a: A)(using S: Show[A]): F[Unit]        = Logger[F].error(S.show(a))
    override def errorln[A](a: A)(using S: Show[A]): F[Unit]      = Logger[F].error(S.show(a))
  }

  def run =
    Slf4jLogger.create[IO].flatMap { implicit logger =>
      Dumbo
        .withResourcesIn[IO]("db/migration")
        .apply(
          connection = ConnectionConfig(
            host = "localhost",
            port = 5432,
            user = "postgres",
            database = "postgres",
            password = Some("postgres"),
            ssl = skunk.SSL.None,
          ),
          defaultSchema = "dumbo",
        )
        .runMigration
        .flatMap { result =>
          logger.info(s"Migration completed with ${result.migrationsExecuted} migrations")
        }
    }
