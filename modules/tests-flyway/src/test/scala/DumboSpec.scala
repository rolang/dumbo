package dumbo

import cats.data.NonEmptyList
import cats.effect.IO
import fs2.io.file.Path
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

class DumboSpec extends ffstest.FTest {
  def flywayMigrate(defaultSchema: String, sourcesPath: Path, schemas: List[String] = Nil): IO[MigrateResult] = IO(
    Flyway
      .configure()
      .defaultSchema(defaultSchema)
      .schemas(schemas*)
      .locations(sourcesPath.toString)
      .dataSource(
        s"jdbc:postgresql://localhost:5432/postgres?ssl=false",
        "postgres",
        "postgres",
      )
      .load()
      .migrate()
  )

  def assertEqualHistory(histA: List[HistoryEntry], histB: List[HistoryEntry]): Unit = {
    def toCompare(h: HistoryEntry) =
      (h.installedRank, h.version, h.script, h.checksum, h.`type`, h.installedBy, h.success)

    assertEquals(histA.map(toCompare), histB.map(toCompare))
  }

  dbTest("Same behaviour on changed checksum") {
    val schema = "schema_1"

    for {
      res       <- flywayMigrate(schema, Path("flyway/test_1"))
      _          = assert(res.migrationsExecuted == 3)
      flywayRes <- flywayMigrate(schema, Path("flyway/test_1_changed_checksum")).attempt
      _          = assert(flywayRes.left.exists(_.getMessage().contains("checksum mismatch")))
      dumboRes  <- dumboMigrate(schema, Path("flyway/test_1_changed_checksum")).attempt
      _          = assert(dumboRes.left.exists(_.getMessage().contains("checksum mismatch")))
    } yield ()
  }

  dbTest("Dumbo is compatible with Flyway history state") {
    val path: Path    = Path("flyway/test_1")
    val defaultSchema = "test_a"

    for {
      flywayRes <- flywayMigrate(defaultSchema, path)
      _          = assert(flywayRes.success)
      _          = assertEquals(flywayRes.migrationsExecuted, 3)
      histA     <- loadHistory(defaultSchema)
      resDumbo  <- dumboMigrate(defaultSchema, path)
      _          = assertEquals(resDumbo.migrationsExecuted, 0)
      histB     <- loadHistory(defaultSchema)
      _          = assertEquals(histA, histB) // history unchanged
    } yield ()
  }

  dbTest("Flyway is compatible with Dumbo history state") {
    val path: Path    = Path("flyway/test_1")
    val defaultSchema = "test_a"

    for {
      resDumbo  <- dumboMigrate(defaultSchema, path)
      _          = assertEquals(resDumbo.migrationsExecuted, 3)
      histA     <- loadHistory(defaultSchema)
      flywayRes <- flywayMigrate(defaultSchema, path)
      _          = assert(flywayRes.success)
      _          = assertEquals(flywayRes.migrationsExecuted, 0)
      histB     <- loadHistory(defaultSchema)
      _          = assertEquals(histA, histB) // history unchanged
    } yield ()
  }

  dbTest("Updates for different default schemas from Flyway to Dumbo") {
    val path: Path = Path("flyway/test_1")
    val schemaA    = "test_a"
    val schemaB    = "test_b"

    for {
      resFlywayA <- flywayMigrate(schemaA, path)
      resFlywayB <- flywayMigrate(schemaB, path)
      _           = assertEquals(resFlywayA.migrationsExecuted, 3)
      _           = assertEquals(resFlywayB.migrationsExecuted, 3)
      resDumboA  <- dumboMigrate(schemaA, path)
      resDumboB  <- dumboMigrate(schemaB, path)
      _           = assertEquals(resDumboA.migrationsExecuted, 0)
      _           = assertEquals(resDumboB.migrationsExecuted, 0)
    } yield ()
  }

  dbTest("Updates for different default schemas from Dumbo to Flyway") {
    val path: Path = Path("flyway/test_1")
    val schemaA    = "test_a"
    val schemaB    = "test_b"

    for {
      resDumboA <- dumboMigrate(schemaA, path)
      resDumboB <- dumboMigrate(schemaB, path)
      _          = assertEquals(resDumboA.migrationsExecuted, 3)
      _          = assertEquals(resDumboB.migrationsExecuted, 3)

      resFlywayA <- flywayMigrate(schemaA, path)
      resFlywayB <- flywayMigrate(schemaB, path)
      _           = assertEquals(resFlywayA.migrationsExecuted, 0)
      _           = assertEquals(resFlywayB.migrationsExecuted, 0)
    } yield ()
  }

  dbTest("Updates for multiple schemas with missing schema config") {
    val path: Path = Path("flyway/test_three_schemas")
    val schemas    = NonEmptyList.of("schema_1", "schema_2")

    for {
      flywayRes     <- flywayMigrate(schemas.head, path, schemas.tail).attempt
      _              = assert(flywayRes.isLeft)
      flywayHistory <- loadHistory(schemas.head)
      _             <- dropSchemas
      dumboRes      <- dumboMigrate(schemas.head, path, schemas.tail).attempt
      _              = assert(dumboRes.isLeft)
      dumboHistory  <- loadHistory(schemas.head)
      _              = assertEqualHistory(flywayHistory, dumboHistory)
    } yield ()
  }

  dbTest("Updates for multiple schemas") {
    val path: Path = Path("flyway/test_three_schemas")
    val schemas    = NonEmptyList.of("schema_1", "schema_2", "schema_3")

    for {
      flywayRes     <- flywayMigrate(schemas.head, path, schemas.tail)
      _              = assert(flywayRes.migrationsExecuted == 1)
      flywayHistory <- loadHistory(schemas.head)
      _             <- dropSchemas
      dumboRes      <- dumboMigrate(schemas.head, path, schemas.tail)
      _              = assert(dumboRes.migrationsExecuted == 1)
      dumboHistory  <- loadHistory(schemas.head)
      _              = assertEqualHistory(flywayHistory, dumboHistory)
    } yield ()
  }

  dbTest("Same behaviour on non-transactional operations") {
    val path: Path = Path("flyway/test_non_transactional")
    val schema     = "schema_1"

    for {
      flywayRes <- flywayMigrate(schema, path).attempt
      _ = assert(
            flywayRes.left.exists(_.getMessage().contains("New enum values must be committed before they can be used"))
          )
      flywayHistory <- loadHistory(schema)
      _             <- dropSchemas
      dumboRes      <- dumboMigrate(schema, path).attempt
      _ = assert(
            dumboRes.left.exists(_.getMessage().contains("New enum values must be committed before they can be used"))
          )
      dumboHistory <- loadHistory(schema)
      _             = assertEqualHistory(flywayHistory, dumboHistory)
    } yield ()
  }
}
