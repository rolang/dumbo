// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.*

import cats.Show
import cats.data.Validated.Invalid
import cats.effect.IO
import cats.effect.std.Console
import cats.implicits.*

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
      _ = vRes match {
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
      _ = assert(res.left.exists { err =>
            val message = err.getMessage()
            message.contains("description mismatch") &&
              message.contains("test changed") &&
              message.contains("test base")
          })
      vRes <- validateWithAppliedMigrations(schema, dumboWithResources("db/test_0_desc_changed"))
      _ = vRes match {
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
      _ = vRes match {
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
      _ = db match {
            case Db.Postgres(11) =>
              assert(errLines.exists(_.matches(".*ALTER TYPE .* cannot run inside a transaction block.*")))
            case Db.Postgres(_) =>
              assert(errLines.exists(_.matches(""".*Unsafe use of new value ".*" of enum type.*""")))
            case Db.CockroachDb =>
              assert(errLines.exists(_.matches(".*enum value is not yet public.")))
          }
    } yield ()
  }

  {
    class TestConsole extends Console[IO] {
      val logs: AtomicReference[Vector[String]] = new AtomicReference(Vector.empty[String])

      override def readLineWithCharset(charset: Charset): IO[String] = ???
      override def print[A](a: A)(implicit S: Show[A]): IO[Unit]     = ???
      override def println[A](a: A)(implicit S: Show[A]): IO[Unit]   = IO(logs.getAndUpdate(_ :+ S.show(a))).void
      override def error[A](a: A)(implicit S: Show[A]): IO[Unit]     = IO.println(S.show(a))
      override def errorln[A](a: A)(implicit S: Show[A]): IO[Unit]   = IO.println(S.show(a))
    }

    val withResources                = dumboWithResources("db/test_long_running")
    def logMatch(s: String): Boolean = s.startsWith("Awaiting query with pid")

    dbTest("don't log on waiting for lock release if under provided duration") {
      val testConsole = new TestConsole()
      for {
        _ <- dumboMigrate("schema_1", withResources, logMigrationStateAfter = 5.second)(testConsole)
        _  = assert(testConsole.logs.get().count(logMatch) == 0)
      } yield ()
    }

    dbTest("log on waiting for lock release longer than provided duration") {
      val testConsole = new TestConsole()

      for {
        _ <- dumboMigrate("schema_1", withResources, logMigrationStateAfter = 800.millis)(testConsole)
        _ = db match {
              case Db.Postgres(_) => assert(testConsole.logs.get().count(logMatch) >= 2)
              case Db.CockroachDb =>
                assert(testConsole.logs.get().count(_.startsWith("Progress monitor is not supported")) == 1)
            }
      } yield ()
    }
  }
}

sealed trait Db
object Db {
  case class Postgres(version: Int) extends Db
  case object CockroachDb           extends Db
}

class DumboSpecPostgresLatest extends DumboMigrationSpec {
  override val db: Db            = Db.Postgres(16)
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
