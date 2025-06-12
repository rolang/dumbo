// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.cli

import dumbo.cli.Dumbo.dumboFromConfigs
import munit.FunSuite

class CliSpec extends FunSuite {
  val minValidConfig = List(
    Config.Url      -> "postgresql://localhost/test_db",
    Config.User     -> "pg_user",
    Config.Location -> "/abd/efg",
  )

  test("parse arguments") {
    val noargs = Arguments.empty

    List(
      List("help")                                            -> noargs.withCommand(Command.Help),
      List("--help")                                          -> noargs.withFlag(Flag.Help),
      List("-h")                                              -> noargs.withFlag(Flag.Help),
      List("-?")                                              -> noargs.withFlag(Flag.Help),
      List("help", "migrate")                                 -> noargs.copy(commands = List(Command.Help, Command.Migrate)),
      List("version")                                         -> noargs.withCommand(Command.Version),
      List("migrate")                                         -> noargs.withCommand(Command.Migrate),
      List("validate")                                        -> noargs.withCommand(Command.Validate),
      List("-ssl=none")                                       -> noargs.withConfig(Config.Ssl, "none"),
      List("-schemas=public,schema1")                         -> noargs.withConfig(Config.Schemas, "public,schema1"),
      List("-user=user_name")                                 -> noargs.withConfig(Config.User, "user_name"),
      List("-user='user_name'")                               -> noargs.withConfig(Config.User, "user_name"),
      List("-user=\"user_name\"")                             -> noargs.withConfig(Config.User, "user_name"),
      List("-password=abc*&\\' ^%$#=@!}{\"")                  -> noargs.withConfig(Config.Password, "abc*&\\' ^%$#=@!}{\""),
      List("-password=\"abc*&\\' ^%$#=@!}{\"\"")              -> noargs.withConfig(Config.Password, "abc*&\\' ^%$#=@!}{\""),
      List("-password='abc*&\\' ^%$#=@!}{\"'")                -> noargs.withConfig(Config.Password, "abc*&\\' ^%$#=@!}{\""),
      List("-location=/abc/efg")                              -> noargs.withConfig(Config.Location, "/abc/efg"),
      List("-table=some_table")                               -> noargs.withConfig(Config.Table, "some_table"),
      List("-validateOnMigrate=false")                        -> noargs.withConfig(Config.ValidateOnMigrate, "false"),
      List("abc", "efg", "-x", "--y")                         -> noargs.copy(unknown = List("abc", "efg", "-x", "--y")),
      List("-url=postgresql://localhost/postgres")            -> noargs.withConfig(Config.Url, "postgresql://localhost/postgres"),
      List("migrate", "-url=postgresql://localhost/postgres") -> noargs
        .withCommand(Command.Migrate)
        .withConfig(Config.Url, "postgresql://localhost/postgres"),
    ).foreach { (input, expected) =>
      val result = Arguments.parse(input)
      assertEquals(result, expected)
    }
  }

  test("create dumbo instance from minimal config") {
    val res = dumbo.cli.Dumbo.dumboFromConfigs(minValidConfig)

    assert(res.isRight)
    val (dmb, connection) = res.toOption.get
    assertEquals(dmb.allSchemas, List("public"))
    assertEquals(dmb.historyTable, "public.flyway_schema_history")
    assertEquals(dmb.validateOnMigrate, true)
    assertEquals(dmb.resReader.location, Some("/abd/efg"))
    assertEquals(connection.database, "test_db")
    assertEquals(connection.host, "localhost")
    assertEquals(connection.port, dumbo.Dumbo.defaults.port)
    assertEquals(connection.ssl, dumbo.ConnectionConfig.SSL.None)
    assertEquals(connection.user, "pg_user")
    assertEquals(connection.password, None)
  }

  test("create dumbo instance with all configs") {
    val configs = List(
      Config.Url               -> "postgresql://127.0.0.1:5555/test_db",
      Config.Schemas           -> "schema1,schema2",
      Config.User              -> "pg_user_v2",
      Config.Password          -> "abc*&\\'^%$#=@!}{\"",
      Config.Ssl               -> "trusted",
      Config.Location          -> "/abd/efg",
      Config.Table             -> "some_table",
      Config.ValidateOnMigrate -> "false",
    )

    val res = dumbo.cli.Dumbo.dumboFromConfigs(configs)

    assert(res.isRight)
    val (dmb, connection) = res.toOption.get
    assertEquals(dmb.allSchemas, List("schema1", "schema2"))
    assertEquals(dmb.historyTable, "schema1.some_table")
    assertEquals(dmb.validateOnMigrate, false)
    assertEquals(dmb.resReader.location, Some("/abd/efg"))
    assertEquals(connection.database, "test_db")
    assertEquals(connection.host, "127.0.0.1")
    assertEquals(connection.port, 5555)
    assertEquals(connection.ssl, dumbo.ConnectionConfig.SSL.Trusted)
    assertEquals(connection.user, "pg_user_v2")
    assertEquals(connection.password, Some("abc*&\\'^%$#=@!}{\""))
  }

  test("fail to create dumbo instance from invalid configs") {
    val noLocationConf = minValidConfig.filter(_._1 != Config.Location)
    assertEquals(dumboFromConfigs(noLocationConf), Left("Missing location path"))
    assertEquals(dumboFromConfigs((Config.Location, "") :: noLocationConf), Left("Missing location path"))

    val noUriConf = minValidConfig.filter(_._1 != Config.Url)
    assertEquals(dumboFromConfigs(noUriConf), Left("Missing url"))

    // invalid urls
    List(
      "Missing database in postgresql://localhost:5555"        -> "postgresql://localhost:5555",
      "Missing or invalid hostname in postgresql:///test_db"   -> "postgresql:///test_db",
      "Missing or invalid hostname in postgresql://@#/test_db" -> "postgresql://@#/test_db",
      "Missing scheme in //localhost:5555/test_db"             -> "//localhost:5555/test_db",
      "Unsupported scheme http"                                -> "http://localhost:5555/test_db",
      "Unsupported scheme jdbc"                                -> "jdbc:postgresql://localhost:5555/test_db",
    ).foreach((clue, uri) =>
      assertEquals(
        dumboFromConfigs((Config.Url -> uri) :: noUriConf),
        Left(clue),
      )
    )

    assertEquals(
      dumboFromConfigs(minValidConfig.filter(_._1 != Config.User)),
      Left("Missing user"),
    )

    assertEquals(
      dumboFromConfigs((Config.ValidateOnMigrate, "invalid") :: minValidConfig),
      Left("Invalid value for validateOnMigrate: invalid"),
    )

    assertEquals(
      dumboFromConfigs((Config.Ssl, "invalid") :: minValidConfig),
      Left("Invalid ssl option invalid"),
    )
  }
}
