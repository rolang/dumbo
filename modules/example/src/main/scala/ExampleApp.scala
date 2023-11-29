// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

import cats.effect.{IO, IOApp}
import dumbo.Dumbo
import natchez.Trace.Implicits.noop

object ExampleApp extends IOApp.Simple {
  override def run: IO[Unit] = Dumbo
    .withResourcesIn[IO]("db/migration")
    .apply(
      sessionResource = skunk.Session.single[IO](
        host = "localhost",
        port = 5432,
        user = "postgres",
        database = "postgres",
        password = Some("postgres"),
      ),
      defaultSchema = "dumbo",
    )
    .runMigration
    .flatMap { result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
    }
}
