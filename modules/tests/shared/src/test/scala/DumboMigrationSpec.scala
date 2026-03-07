// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.concurrent.duration.*

import cats.data.Validated.Invalid
import cats.implicits.*
import dumbo.logging.Implicits.consolePrettyWithTimestamp
import dumbo.logging.LogLevel
import ffstest.TestLogger
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.codec.all.*
import skunk.implicits.*

trait DumboMigrationSpec extends ffstest.FTest {
  def db: Db

  def assertEqualHistory(histA: List[HistoryEntry], histB: List[HistoryEntry]): Unit = {
    def toCompare(h: HistoryEntry) =
      (h.installedRank, h.version, h.script, h.checksum, h.`type`, h.installedBy, h.success)

    assertEquals(histA.map(toCompare), histB.map(toCompare))
  }

  test("Run multiple migrations concurrently") {
    dropSchemas >> (1 to 5).toList.traverse_ { _ =>
      val schema = someSchemaName

      for {
        res     <- (1 to 20).toList.parTraverse(_ => dumboMigrate(schema, dumboWithResources("db/test_1")))
        ranks    = res.flatMap(_.migrations.map(_.installedRank)).sorted
        _        = assertEquals(ranks, List(1, 2, 3, 4))
        history <- loadHistory(schema)
        _        = assert(history.length == 5)
      } yield ()
    }
  }

  dbTest("Validate checksum with validation enabled") {
    val schema = someSchemaName

    for {
      _    <- dumboMigrate(schema, dumboWithResources("db/test_0"))
      res  <- dumboMigrate(schema, dumboWithResources("db/test_0_changed_checksum"), validateOnMigrate = true).attempt
      _     = assert(res.isLeft)
      _     = assert(res.left.exists(_.getMessage().contains("checksum mismatch")))
      vRes <- validateWithAppliedMigrations(schema, dumboWithResources("db/test_0_changed_checksum"))
      _     = vRes match {
            case Invalid(errs) => assert(errs.toList.exists(_.getMessage().contains("checksum mismatch")))
            case _             => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Validate description with validation enabled") {
    val schema = someSchemaName

    for {
      _   <- dumboMigrate(schema, dumboWithResources("db/test_0"))
      res <- dumboMigrate(schema, dumboWithResources("db/test_0_desc_changed"), validateOnMigrate = true).attempt
      _    = assert(res.isLeft)
      _    = assert(res.left.exists { err =>
            val message = err.getMessage()
            message.contains("description mismatch") &&
              message.contains("test changed") &&
              message.contains("test base")
          })
      vRes <- validateWithAppliedMigrations(schema, dumboWithResources("db/test_0_desc_changed"))
      _     = vRes match {
            case Invalid(errs) =>
              assert(errs.exists { err =>
                val message = err.getMessage()
                message.contains("description mismatch") &&
                  message.contains("test changed") &&
                  message.contains("test base")
              })
            case _ => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Validate for missing files with validation enabled") {
    val schema = someSchemaName

    for {
      _    <- dumboMigrate(schema, dumboWithResources("db/test_0"))
      res  <- dumboMigrate(schema, dumboWithResources("db/test_0_missing_file"), validateOnMigrate = true).attempt
      _     = assert(res.isLeft)
      _     = assert(res.left.exists(_.isInstanceOf[dumbo.exception.DumboValidationException]))
      _     = assert(res.left.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
      vRes <- validateWithAppliedMigrations(schema, dumboWithResources("db/test_0_missing_file"))
      _     = vRes match {
            case Invalid(errs) =>
              assert(errs.toList.exists(_.getMessage().contains("Detected applied migration not resolved locally")))
            case _ => fail("expected failure")
          }
    } yield ()
  }

  dbTest("Ignore missing files or missing checksum on validation disabled") {
    val schema = someSchemaName

    for {
      _    <- dumboMigrate(schema, dumboWithResources("db/test_0"))
      resA <- dumboMigrate(schema, dumboWithResources("db/test_0_missing_file"), validateOnMigrate = false).attempt
      resB <- dumboMigrate(schema, dumboWithResources("db/test_0_changed_checksum"), validateOnMigrate = false).attempt
      resC <- dumboMigrate(schema, dumboWithResources("db/test_0_desc_changed"), validateOnMigrate = false).attempt
      _     = assert(resA.isRight && resB.isRight && resC.isRight)
    } yield ()
  }

  dbTest("Fail with CopyNotSupportedException") {
    val schema = someSchemaName

    for {
      dumboResA <- dumboMigrate(schema, dumboWithResources("db/test_copy_from")).attempt
      _          = assert(dumboResA.left.exists(_.isInstanceOf[skunk.exception.CopyNotSupportedException]))
      dumboResB <- dumboMigrate(schema, dumboWithResources("db/test_copy_to")).attempt
      _          = assert(dumboResB.left.exists(_.isInstanceOf[skunk.exception.CopyNotSupportedException]))
    } yield ()
  }

  dbTest("Fail on non-transactional operations") {
    val withResources = dumboWithResources("db/test_non_transactional")
    val schema        = someSchemaName

    for {
      dumboRes <- dumboMigrate(schema, withResources).attempt
      _         = assert(dumboRes.isLeft)
      errLines  = dumboRes.swap.toOption.get.getMessage().linesIterator
      _         = db match {
            case Db.Postgres(11) =>
              assert(errLines.exists(_.matches(".*ALTER TYPE .* cannot run inside a transaction block.*")))
            case Db.Postgres(_) =>
              assert(errLines.exists(_.matches(""".*Unsafe use of new value ".*" of enum type.*""")))
            case Db.CockroachDb =>
              assert(errLines.exists(_.matches(".*enum value is not yet public.")))
          }
    } yield ()
  }

  dbTest("Fail on non-transactional operations") {
    val withResources = dumboWithResources("db/test_non_transactional")
    val schema        = someSchemaName

    for {
      dumboRes <- dumboMigrate(schema, withResources).attempt
      _         = assert(dumboRes.isLeft)
      errLines  = dumboRes.swap.toOption.get.getMessage().linesIterator
      _         = db match {
            case Db.Postgres(11) =>
              assert(errLines.exists(_.matches(".*ALTER TYPE .* cannot run inside a transaction block.*")))
            case Db.Postgres(_) =>
              assert(errLines.exists(_.matches(""".*Unsafe use of new value ".*" of enum type.*""")))
            case Db.CockroachDb =>
              assert(errLines.exists(_.matches(".*enum value is not yet public.")))
          }
    } yield ()
  }

  dbTest("schemas are included in the search path") {
    val withResources = dumboWithResources("db/test_search_path")
    val schemas       = List("schema_1", "schema_2")

    for {
      dumboRes <- dumboMigrate(schemas.head, withResources, schemas.tail).attempt
      _         = assert(dumboRes.isRight)
      history  <- loadHistory(schemas.head)
      _         = assert(history.length != 2)
    } yield ()
  }

  dbTest("warn if schemas are not included in the search path for custom sessions") {
    val withResources                      = dumboWithResources("db/test_search_path")
    val schemas                            = List("schema_1", "schema_2")
    val testConsole                        = new TestLogger
    def hasWarning(l: LogLevel, m: String) =
      l == LogLevel.Warn && m.contains("""The search_path will be set to '"schema_1", "schema_2"'""")
    def hasMissingSchemaWarning(l: LogLevel, m: String) =
      m.contains(
        """Following schemas are not included in the search path '"$user", public': schema_1, schema_2"""
      ) && hasWarning(l, m)

    def hasWrongOrderWarning(l: LogLevel, m: String) =
      m.contains(
        """Default schema 'schema_1' is not in the right position of the search path 'schema_2, schema_1'"""
      ) && hasWarning(l, m)

    def migrateBySession(params: Map[String, String] = Map.empty) =
      dumboMigrateWithSession(schemas.head, withResources, session(params), schemas.tail)(testConsole).attempt

    for {
      // warn about missing schemas in the search_path
      dumboRes <- migrateBySession()
      _         = assert(dumboRes.isRight)
      _         = assert(testConsole.logs.get().exists(t => hasMissingSchemaWarning(t._1, t._2)))
      // warn about wrong order in the search_path
      _         <- { testConsole.flush(); dropSchemas }
      dumboResB <- migrateBySession(Map("search_path" -> "schema_2,schema_1"))
      _          = assert(dumboResB.isRight)
      _          = assert(testConsole.logs.get().exists(t => hasWrongOrderWarning(t._1, t._2)))
      // succeed without warning if search_path is set as expected
      _         <- { testConsole.flush(); dropSchemas }
      dumboResC <- migrateBySession(Map("search_path" -> "schema_1,schema_2"))
      _          = assert(dumboResC.isRight)
      _          = assert(!testConsole.logs.get().exists(t => hasWarning(t._1, t._2)))
    } yield ()
  }

  dbTest("migrate by different schema using custom session") {
    val withResources = dumboWithResources("db/test_1")
    val schemaA       = "test_a"
    val schemaB       = "test_b"

    for {
      resDumboA <- dumboMigrateWithSession(schemaA, withResources, session())
      resDumboB <- dumboMigrateWithSession(schemaB, withResources, session())
      _          = assertEquals(resDumboA.migrationsExecuted, 4)
      _          = assertEquals(resDumboB.migrationsExecuted, 4)
    } yield ()
  }

  dbTest("default schema is used when no schema is specified in migration sripts") {
    val withResources = dumboWithResources("db/test_default_schema")

    (1 to 5).toList.traverse_ { _ =>
      val schemaDefault = someSchemaName
      val schemas       = List.fill(scala.util.Random.nextInt(10))(someSchemaName)

      def assertDefaultSchemaHasTable =
        session()
          .use(
            _.execute(sql"""|SELECT table_schema::text
                            |FROM information_schema.tables
                            |WHERE table_name = 'test_default_schema'""".stripMargin.query(text))
          )
          .map { schemas =>
            assertEquals(schemas, List(schemaDefault))
          }

      for {
        _ <- dropSchemas
        // migrate by connection config
        _ <- dumboMigrate(schemaDefault, withResources, schemas)
        _ <- assertDefaultSchemaHasTable
        _ <- dropSchemas
        // migrate by custom session
        _ <- dumboMigrateWithSession(schemaDefault, withResources, session(), schemas)
        _ <- assertDefaultSchemaHasTable
        // migrate by custom session with random search_path order
        _ <- dropSchemas
        _ <- dumboMigrateWithSession(
               schemaDefault,
               withResources,
               session(Map("search_path" -> scala.util.Random.shuffle(schemaDefault :: schemas).mkString(","))),
               schemas,
             )
        _ <- assertDefaultSchemaHasTable
      } yield ()
    }
  }

  {
    val withResources                             = dumboWithResources("db/test_long_running")
    def logMatch(l: LogLevel, s: String): Boolean = l == LogLevel.Info && s.startsWith("Awaiting query with pid")

    dbTest("don't log on waiting for lock release if under provided duration") {
      val testLogger = new TestLogger()
      for {
        _ <- dumboMigrate("schema_1", withResources, logMigrationStateAfter = 5.second)(testLogger)
        _  = assert(testLogger.logs.get().count(t => logMatch(t._1, t._2)) == 0)
      } yield ()
    }

    dbTest("log on waiting for lock release longer than provided duration") {
      val testLogger = new TestLogger()

      for {
        _ <- dumboMigrate("schema_1", withResources, logMigrationStateAfter = 800.millis)(testLogger)
        _  = db match {
              case Db.Postgres(_) => assert(testLogger.logs.get().count(t => logMatch(t._1, t._2)) >= 2)
              case Db.CockroachDb =>
                assert(testLogger.logs.get().count { case (level, message) =>
                  level == LogLevel.Warn && message.startsWith("Progress monitor is not supported")
                } == 1)
            }
      } yield ()
    }
  }

  dbTest("Clean drops all objects and allows re-migration") {
    val schema = someSchemaName

    for {
      res1    <- dumboMigrate(schema, dumboWithResources("db/test_1"))
      _        = assert(res1.migrationsExecuted > 0)
      history <- loadHistory(schema)
      _        = assert(history.nonEmpty)
      _       <- dumboClean(schema, dumboWithResources("db/test_1"))
      // after clean, migrating again should re-apply all migrations
      res2 <- dumboMigrate(schema, dumboWithResources("db/test_1"))
      _     = assertEquals(res2.migrationsExecuted, res1.migrationsExecuted)
    } yield ()
  }

  dbTest("Clean on empty schema is idempotent") {
    val schema = someSchemaName

    for {
      _ <- session().use(_.execute(sql"CREATE SCHEMA IF NOT EXISTS #${schema}".command))
      _ <- dumboClean(schema, dumboWithResources("db/test_1"))
      // should be able to migrate after cleaning an empty schema
      res <- dumboMigrate(schema, dumboWithResources("db/test_1"))
      _    = assert(res.migrationsExecuted > 0)
    } yield ()
  }

  dbTest("Clean with multiple schemas") {
    val schema1 = someSchemaName
    val schema2 = someSchemaName

    for {
      _ <- dumboMigrate(schema1, dumboWithResources("db/test_1"), schemas = List(schema1, schema2))
      _ <- dumboClean(schema1, dumboWithResources("db/test_1"), schemas = List(schema1, schema2))
      // both schemas should be recreated empty, migration should work again
      res <- dumboMigrate(schema1, dumboWithResources("db/test_1"), schemas = List(schema1, schema2))
      _    = assert(res.migrationsExecuted > 0)
    } yield ()
  }

  dbTest("Clean fails when cleanDisabled is true") {
    val schema        = someSchemaName
    val withResources = dumboWithResources("db/test_1")

    for {
      result <- withResources
                  .apply(
                    connection = connectionConfig,
                    defaultSchema = schema,
                  )
                  .runClean
                  .attempt
      _ = assert(result.isLeft)
      _ = assert(result.left.exists(_.isInstanceOf[exception.DumboCleanException]))
    } yield ()
  }
}

sealed trait Db
object Db {
  case class Postgres(version: Int) extends Db
  case object CockroachDb           extends Db
}

class DumboSpecPostgresLatest extends DumboMigrationSpec {
  override val db: Db            = Db.Postgres(17)
  override val postgresPort: Int = 5432
}

class DumboSpecPostgres11 extends DumboMigrationSpec {
  override val db: Db            = Db.Postgres(11)
  override val postgresPort: Int = 5434
}

class DumboSpecCockroachDb extends DumboMigrationSpec {
  override val db: Db            = Db.CockroachDb
  override val postgresPort: Int = 5436
}
