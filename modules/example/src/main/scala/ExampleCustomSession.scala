import cats.effect.{IO, IOApp}
import dumbo.logging.Implicits.console
import dumbo.Dumbo
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import skunk.Session
import skunk.Session.Credentials

object ExampleCustomSession extends IOApp.Simple:
  def run = Dumbo
    .withResourcesIn[IO]("db/migration")
    .withSession(
      sessionResource = Session
        .Builder[IO]
        .withHost("localhost")
        .withPort(5432)
        .withDatabase("postgres")
        .withCredentials(Credentials(user = "postgres", password = Some("postgres")))
        // add schemas to the search path
        // those are added by default when using a dumbo.ConnectionConfig
        .withConnectionParameters(
          Session.DefaultConnectionParameters ++ Map(
            "search_path" -> "schema_1"
          )
        )
        // a strategy other than BuiltinsOnly should not be required for running migrations
        .withTypingStrategy(skunk.TypingStrategy.BuiltinsOnly)
        .single,
      defaultSchema = "schema_1",
    )
    .runMigration
    .flatMap: result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
