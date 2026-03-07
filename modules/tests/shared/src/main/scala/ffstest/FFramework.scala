// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.*
import scala.util.Random

import cats.data.ValidatedNec
import cats.effect.std.Console
import cats.effect.{IO, Resource}
import cats.implicits.*
import dumbo.exception.DumboValidationException
import dumbo.logging.{LogLevel, Logger}
import dumbo.{ConnectionConfig, Dumbo, DumboWithResourcesPartiallyApplied, History, HistoryEntry}
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.Session
import skunk.Session.Credentials
import skunk.implicits.*

trait FTest extends CatsEffectSuite with FTestPlatform {
  def postgresPort: Int = 5432

  def dbTest(name: String)(f: => IO[Unit]): Unit = test(name)(dropSchemas >> f)

  // note: schema name should not start with "pg_" or "crdb_" to avoid conflicts with reserved ones
  def someSchemaName: String = {
    val chars = "abcdefghijklmnopqrstuvwxyz"
    LazyList.continually(chars.charAt(Random.nextInt(chars.length))).take(15).mkString
  }

  lazy val connectionConfig: ConnectionConfig = ConnectionConfig(
    host = "localhost",
    port = postgresPort,
    user = "root",
    database = "postgres",
    password = None,
  )

  def session(params: Map[String, String] = Map.empty): Resource[IO, Session[IO]] =
    Session
      .Builder[IO]
      .withHost(connectionConfig.host)
      .withPort(connectionConfig.port)
      .withDatabase(connectionConfig.database)
      .withCredentials(Credentials(user = connectionConfig.user, password = connectionConfig.password))
      .withTypingStrategy(skunk.TypingStrategy.BuiltinsOnly)
      .withConnectionParameters(Session.DefaultConnectionParameters ++ params)
      .single

  def loadHistory(schema: String, tableName: String = "flyway_schema_history"): IO[List[HistoryEntry]] =
    session().use(_.execute(History(schema, tableName).loadAllQuery))

  def dumboMigrate(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
    logMigrationStateAfter: Duration = Duration.Inf,
  )(implicit l: Logger[IO]): IO[Dumbo.MigrationResult] =
    (if (logMigrationStateAfter.isFinite) {
       withResources.withMigrationStateLogAfter(FiniteDuration(logMigrationStateAfter.toMillis, MILLISECONDS))(
         connection = connectionConfig,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     } else {
       withResources.apply(
         connection = connectionConfig,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     }).runMigration

  def dumboMigrateWithSession(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    session: Resource[IO, Session[IO]],
    schemas: List[String] = Nil,
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
  )(implicit l: Logger[IO]): IO[Dumbo.MigrationResult] =
    withResources
      .withSession(
        sessionResource = session,
        defaultSchema = defaultSchema,
        schemas = schemas.toSet,
        schemaHistoryTable = schemaHistoryTable,
        validateOnMigrate = validateOnMigrate,
      )
      .runMigration

  def validateWithAppliedMigrations(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
  ): IO[ValidatedNec[DumboValidationException, Unit]] = {
    import dumbo.logging.Implicits.consolePrettyWithTimestamp
    withResources(
      connection = connectionConfig,
      defaultSchema = defaultSchema,
      schemas = schemas.toSet,
    ).runValidationWithHistory
  }

  def dumboClean(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
    schemaHistoryTable: String = "flyway_schema_history",
  )(implicit l: Logger[IO]): IO[Unit] =
    withResources
      .apply(
        connection = connectionConfig,
        defaultSchema = defaultSchema,
        schemas = schemas.toSet,
        schemaHistoryTable = schemaHistoryTable,
        cleanDisabled = false,
      )
      .runClean

  def dropSchemas: IO[Unit] = session().use { s =>
    for {
      customSchemas <-
        s.execute(
          sql"""|SELECT schema_name::text
                |FROM information_schema.schemata
                |WHERE schema_name NOT LIKE 'pg\_%' AND schema_name NOT LIKE 'crdb\_%' AND schema_name NOT IN ('information_schema', 'public')""".stripMargin
            .query(skunk.codec.text.text)
        )
      _ <- IO.println(s"Dropping schemas ${customSchemas.mkString(", ")}")
      c <- customSchemas.traverse(schema => s.execute(sql"DROP SCHEMA IF EXISTS #${schema} CASCADE".command))
      _ <- IO.println(s"Schema drop result ${c.mkString(", ")}")
    } yield ()
  }
}

class TestLogger extends Logger[IO] {
  private val underlying = Logger.fromConsoleWithTimestamp(console = Console[IO], pretty = true)

  val logs: AtomicReference[Vector[(LogLevel, String)]] = new AtomicReference(Vector.empty)

  def flush(): Unit = logs.set(Vector.empty)

  override def apply(level: LogLevel, message: => String): IO[Unit] = underlying.apply(level, message) *> IO {
    logs.getAndUpdate(_ :+ (level, message))
  }.void
}
