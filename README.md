# Dumbo

[![Sonatype Releases](https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.rolang/dumbo_3.svg?label=Sonatype%20Release)](https://oss.sonatype.org/content/repositories/releases/dev/rolang/dumbo_3/)
[![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.rolang/dumbo_3.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/rolang/dumbo_3/)
[![dumbo Scala version support](https://index.scala-lang.org/rolang/dumbo/dumbo/latest-by-scala-version.svg?platform=native0.4)](https://index.scala-lang.org/rolang/dumbo/dumbo)
[![dumbo Scala version support](https://index.scala-lang.org/rolang/dumbo/dumbo/latest-by-scala-version.svg?platform=jvm)](https://index.scala-lang.org/rolang/dumbo/dumbo)

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

For usage via command line see [command-line](#command-line) section.

In a sbt project dumbo can be added like:

```scala
libraryDependencies += "dev.rolang" %% "dumbo" % "0.1.x"
```

_For compatibility with skunk `0.6.x` / natchez / Scala 2.12.x use release series `0.0.x`_:

```scala
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

## Command-line

Dumbo ships with a command line tool as a native binary.

### Download and installation

Note: the executable depends on [utf8proc](https://github.com/JuliaStrings/utf8proc) and [s2n-tls](https://github.com/aws/s2n-tls).

#### Linux

##### Arch

```shell
# install prerequisites
sudo pacman -S s2n-tls libutf8proc

# download and run dumbo
curl -L https://github.com/rolang/dumbo/releases/latest/download/dumbo-cli-x86_64-linux > dumbo && chmod +x dumbo
./dumbo -v
```

##### Alpine

```shell
# install prerequisites
apk update && apk add gcompat libgcc libstdc++ s2n-tls utf8proc

# download and run dumbo
wget https://github.com/rolang/dumbo/releases/latest/download/dumbo-cli-x86_64-linux -O dumbo && chmod +x dumbo
./dumbo -v
```

##### Ubuntu / Debian

As of now `s2n-tls` is not available as apt package.
For utf8proc the package `libutf8proc3` is required which is currently only available from Ubuntu `24.04` / `noble` via apt.  
Alternatively one can install the depenedncies via [homebrew](https://brew.sh) as follows:

```shell
# install homebrew if not already installed https://docs.brew.sh/Homebrew-on-Linux
sudo apt-get install build-essential procps curl file git
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# install prerequisites and include homebrew lib
/home/linuxbrew/.linuxbrew/bin/brew install s2n utf8proc
export LD_LIBRARY_PATH="/home/linuxbrew/.linuxbrew/lib"

# download and run dumbo
curl -L https://github.com/rolang/dumbo/releases/latest/download/dumbo-cli-x86_64-linux > dumbo && chmod +x dumbo
./dumbo -v
```

#### Docker

A docker image with the command line is published to docker hub: [rolang/dumbo](https://hub.docker.com/r/rolang/dumbo).

To print the command line help run:

```shell
docker run rolang/dumbo:latest-alpine help
```

To run the example migrations in this repository, run from repository's root directory:

1. Boot up a Postgres instance

```shell
docker compose up pg_1
```

2. Run example migration

```shell
docker run --net="host" \
  -v ./modules/example/src/main/resources/db/migration:/migration \
  rolang/dumbo:latest-alpine \
  -user=postgres \
  -password=postgres \
  -url=postgresql://localhost:5432/postgres \
  -location=/migration \
  migrate
```

### Command-line usage

```
dumbo [options] [command]
```

##### Commands:

| Command                | Description                                                       |
| ---------------------- | ----------------------------------------------------------------- |
| help                   | Print this usage info and exit                                    |
| migrate                | Migrates the database                                             |
| validate               | Validates the applied migrations against the ones in the location |
| version, -v, --version | Print the Dumbo version                                           |

##### Configuration parameters (Format: -key=value):

| Configuration     | Description                                                                                               | Default                 |
| ----------------- | --------------------------------------------------------------------------------------------------------- | ----------------------- |
| location          | Path to directory to scan for migrations                                                                  |                         |
| table             | The name of Dumbo's schema history table                                                                  | `flyway_schema_history` |
| password          | Password to use to connect to the database                                                                |                         |
| url               | Url to use to connect to the database                                                                     |                         |
| validateOnMigrate | Validate when running migrate                                                                             | `true`                  |
| user              | User to use to connect to the database                                                                    |                         |
| schemas           | Comma-separated list of the schemas managed by Dumbo. First schema will be used as default schema if set. | `public`                |
| ssl               | SSL mode to use: `none`, `trusted` or `system`.                                                           | `none`                  |

##### Examples:

```shell
dumbo \
  -user=postgres \
  -password="my safe passw0rd" \
  -url=postgresql://localhost:5432/postgres \
  -location=/path/to/db/migration \
  migrate
```

```shell
dumbo help migrate
```
