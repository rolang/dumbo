# Dumbo

[![CircleCI](https://dl.circleci.com/status-badge/img/gh/rolang/dumbo/tree/main.svg?style=svg)](https://dl.circleci.com/status-badge/redirect/gh/rolang/dumbo/tree/main)
[![Sonatype Releases](https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.rolang/dumbo_2.13.svg?label=Sonatype%20Release)](https://oss.sonatype.org/content/repositories/releases/dev/rolang/dumbo_2.13/)
[![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.rolang/dumbo_2.13.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/rolang/dumbo_2.13/)

![Logo](./docs/assets/logo.png)

Simple database migration tool for Scala + Postgres with [skunk](https://typelevel.org/skunk/) that can be deployed on JVM and Native.  
Supports a subset of [Flyway](https://flywaydb.org) features and keeps a Flyway compatible history state to allow you to switch to Flyway if necessary.

Currently supports:
 - Versioned Migrations in the filesystem as specified by Flyway:  
  ![Versioned Migrayions](./docs/assets/versioned_migrations.png)

## Usage example
Examples can be viewed in [modules/example](./modules/example/).  
Similar to usage of the Flyway Java library, given versioned migrations in the filesystem in the resource folder: 
```
example
  src
    main
      resources
        db
          migration
            V1__test.sql
            V3__test_c.sql
            V2__test_b.sql
```
The migration can be exectured in the process:
```scala
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
```

To run the example locally with docker and sbt, start a Postgres docker container:
```shell
 docker run -p 5432:5432 --rm --name dumbo -e POSTGRES_PASSWORD=postgres postgres:15-alpine
```

Run example with sbt:
```shell
sbt 'example/run'
```
