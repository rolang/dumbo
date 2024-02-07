// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

object ExampleApp extends IOApp.Simple {
  override def run: IO[Unit] = Dumbo
    .withResourcesIn[IO]("db/migration")
    .apply(
      connection = ConnectionConfig(
        host = "localhost",
        port = 5432,
        user = "postgres",
        database = "postgres",
        password = Some("postgres"),
        ssl = skunk.SSL.None, // skunk.SSL config, default is skunk.SSL.None
      ),
      defaultSchema = "dumbo",
    )
    .runMigration
    .flatMap { result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
    }
}
