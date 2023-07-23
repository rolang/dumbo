import cats.effect.{IO, IOApp}
import dumbo.Dumbo
import natchez.Trace.Implicits.noop
import skunk.Session

object ExampleApp extends IOApp.Simple {
  override def run: IO[Unit] = for {
    result <- Session
                .single[IO](
                  host = "localhost",
                  port = 5432,
                  user = "postgres",
                  database = "postgres",
                  password = Some("postgres"),
                )
                .use(
                  Dumbo[IO](
                    sourceDir = fs2.io.file.Path("db") / "migration",
                    defaultSchema = "public",
                  ).migrate
                )
    _ <- IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
  } yield ()
}
