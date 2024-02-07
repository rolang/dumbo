# Dumbo

[![Sonatype Releases](https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.rolang/dumbo_2.13.svg?label=Sonatype%20Release)](https://oss.sonatype.org/content/repositories/releases/dev/rolang/dumbo_2.13/)
[![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.rolang/dumbo_2.13.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/rolang/dumbo_2.13/)

![Logo](./docs/assets/logo.png)

Simple database migration tool for Scala + Postgres with [skunk](https://typelevel.org/skunk/) that can be deployed on JVM and Native.  
Supports a subset of [Flyway](https://flywaydb.org) features and keeps a Flyway compatible history state to allow you to switch to Flyway if necessary.  
You might also be able to simply switch from Flyway to Dumbo without any changes in migration files or the history state, depending on used Flyway features.

## Currently supports:

### Versioned Migrations as specified by Flyway

![Versioned Migrations](./docs/assets/versioned_migrations.png)

Each versioned migration must be assigned a unique version.  
 A simple increasing integer or any version is valid as long as it conforms to the usual dotted notation:

- 1
- 001
- 5.2
- 1.2.3.4.5.6.7.8.9
- 205.68
- 20130115113556
- 2013.1.15.11.35.56
- 2013.01.15.11.35.56

### Script Config Files

Similar to [Flyway script config files](https://documentation.red-gate.com/flyway/flyway-cli-and-api/configuration/script-config-files) it's possible to configure migrations on a per-script basis.

This is achieved by creating a script configuration file in the same folder as the migration.  
The script configuration file name must match the migration file name, with the `.conf` suffix added.

For example a migration file `db/V1__my_script.sql` would have a script configuration file `db/V1__my_script.sql.conf`.

#### Structure

Script config files have the following structure:

```
key=value
```

#### Reference

- **executeInTransaction**  
  Manually determine whether or not to execute this migration in a transaction.

  This is useful where certain statements can only execute outside a transaction (like `CREATE INDEX CONCURRENTLY` etc.)  
  Example:

  ```
  executeInTransaction=false
  ```

## Usage example

For usage via command line see [command-line](https://github.com/rolang/dumbo#command-line) section in the main branch.

In a sbt project dumbo can be added like:

```
libraryDependencies += "dev.rolang" %% "dumbo" % "0.0.x"
```

To include snapshot releases, add snapshot resolver:

```
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
```

Examples can be viewed in [modules/example](./modules/example/).  
Similar to usage of the Flyway Java library, given versioned migrations in the resources folder:

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

The migration can be executed like:

```scala
import cats.effect.{IO, IOApp}
import dumbo.{ConnectionConfig, Dumbo}
import natchez.Trace.Implicits.noop

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
      defaultSchema = "public",
    )
    .runMigration
    .flatMap { result =>
      IO.println(s"Migration completed with ${result.migrationsExecuted} migrations")
    }
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

## Configurations

### Configure the resources

To read migration scripts from embedded resources:

```scala
val dumboWithResouces = Dumbo.withResourcesIn[IO]("db/migration")
```

Notes:

- In Scala 3 the resource files will be listed / checked at compile time.
  In case the resource location can't be found in the classpath or multiple locations were found, a compilation error will appear.
- For Scala Native ensure to have [embedded resources](https://scala-native.org/en/stable/lib/javalib.html?highlight=resources#embedding-resources) enabled.
- In Scala 2 the resource location will be checked at runtime
- In Scala 2 + Native you'll be required to pass a list of resources as we can't list resources from a location at runtime, e.g. like:

```scala
val dumboWithResouces = Dumbo.withResources(
  List(
    ResourceFilePath("/db/migration/V1__test.sql"),
    ResourceFilePath("/db/migration/V2__test_b.sql"))
  )
```

To read migration scripts from the files system use:

```scala
val dumboWithResouces = Dumbo.withFilesIn[IO](
  fs2.io.file.Path("modules/example/src/main/resources/db/migration")
)
```

### Apply further configuration:

```scala
dumboWithResouces.apply(
  // connection config
  connection: dumbo.ConnectionConfig = dumbo.ConnectionConfig(
    host = "localhost",
    port = 5432,
    user = "postgres",
    database = "postgres",
    password = Some("postgres"),
    ssl = skunk.SSL.None, // skunk.SSL config, default is skunk.SSL.None
  ),

  // default schema (the history state is going to be stored under that schema)
  defaultSchema: String = "public",

  // schemas to include in the search
  schemas: Set[String] = Set.empty[String],

  // migration history table name
  schemaHistoryTable: String = "flyway_schema_history",

  // compare migration files with applied migrations
  // check e.g. for changed file content/description or missing files before migration
  validateOnMigrate: Boolean = true
)

// migration progress logs can be added optionally in case you'd like dumbo to provide some feedback on longer running queries
// it will perform requests to Postgres in given interval to check for queries that are causing the lock on migration history table
dumboWithResouces.withMigrationStateLogAfter[IO](5.seconds)(
  /* use config as above */
)
```
