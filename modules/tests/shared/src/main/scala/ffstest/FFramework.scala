// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import scala.concurrent.duration.*

import cats.data.ValidatedNec
import cats.effect.{IO, Resource, std}
import cats.implicits.*
import dumbo.exception.DumboValidationException
import dumbo.{Dumbo, DumboWithResourcesPartiallyApplied, History, HistoryEntry}
import munit.CatsEffectSuite
import natchez.Trace.Implicits.noop
import skunk.implicits.*
import skunk.{Session, *}

trait FTest extends CatsEffectSuite with FTestPlatform {
  def postgresPort: Int = 5432

  def dbTest(name: String)(f: => IO[Unit]): Unit = test(name)(dropSchemas >> f)

  def session: Resource[IO, Session[IO]] = Session
    .single[IO](
      host = "localhost",
      port = postgresPort,
      user = "postgres",
      database = "postgres",
      password = Some("postgres"),
    )

  def loadHistory(schema: String, tableName: String = "flyway_schema_history"): IO[List[HistoryEntry]] =
    session.use(_.execute(History(s"$schema.$tableName").loadAllQuery))

  def dumboMigrate(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
    schemaHistoryTable: String = "flyway_schema_history",
    validateOnMigrate: Boolean = true,
    logMigrationStateAfter: Duration = Duration.Inf,
  )(implicit c: std.Console[IO]): IO[Dumbo.MigrationResult] =
    (if (logMigrationStateAfter.isFinite) {
       withResources.withMigrationStateLogAfter(FiniteDuration(logMigrationStateAfter.toMillis, MILLISECONDS))(
         sessionResource = session,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     } else {
       withResources.apply(
         sessionResource = session,
         defaultSchema = defaultSchema,
         schemas = schemas.toSet,
         schemaHistoryTable = schemaHistoryTable,
         validateOnMigrate = validateOnMigrate,
       )
     }).runMigration

  def validateWithAppliedMigrations(
    defaultSchema: String,
    withResources: DumboWithResourcesPartiallyApplied[IO],
    schemas: List[String] = Nil,
  ): IO[ValidatedNec[DumboValidationException, Unit]] =
    withResources(
      sessionResource = session,
      defaultSchema = defaultSchema,
      schemas = schemas.toSet,
    ).runValidationWithHistory

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
