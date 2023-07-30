package ffstest

import cats.effect.{IO, Resource}
import cats.implicits.*
import dumbo.{Dumbo, History, HistoryEntry}
import fs2.io.file.Path
import munit.CatsEffectSuite
import natchez.Trace.Implicits.noop
import skunk.implicits.*
import skunk.{Session, *}

trait FTest extends CatsEffectSuite with FTestPlatform {
  def dbTest(name: String)(f: => IO[Unit]): Unit = test(name)(dropSchemas >> f)

  def session: Resource[IO, Session[IO]] = Session
    .single[IO](
      host = "localhost",
      port = 5432,
      user = "postgres",
      database = "postgres",
      password = Some("postgres"),
    )

  def loadHistory(schema: String, tableName: String = "flyway_schema_history"): IO[List[HistoryEntry]] =
    session.use(_.execute(History(s"$schema.$tableName").loadAllQuery)(0))

  def dumboMigrate(
    defaultSchema: String,
    sourcesPath: Path,
    schemas: List[String] = Nil,
    validateOnMigrate: Boolean = true,
  ): IO[Dumbo.MigrationResult] =
    session.use(
      Dumbo[IO](
        sourceDir = resourcesPath(sourcesPath),
        defaultSchema = defaultSchema,
        schemas = schemas.toSet,
        validateOnMigrate = validateOnMigrate,
      ).migrate
    )

  def dropSchemas: IO[Unit] = session.use { s =>
    for {
      customSchemas <-
        s.execute(sql"""
        SELECT schema_name 
        FROM information_schema.schemata 
        WHERE schema_name NOT LIKE 'pg_%' AND schema_name != 'information_schema'""".query(skunk.codec.text.name))
      _ <- IO.println(s"Dropping schemas ${customSchemas.mkString(", ")}")
      c <- customSchemas.traverse(schema => s.execute(sql"DROP SCHEMA IF EXISTS #${schema} CASCADE".command))
      _ <- IO.println(s"Schema drop result ${c.mkString(", ")}")
    } yield ()
  }
}
