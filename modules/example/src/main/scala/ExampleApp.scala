// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

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
        ssl = skunk.SSL.None,
      )
    )
    .runMigration
    .flatMap { result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
    }
}
