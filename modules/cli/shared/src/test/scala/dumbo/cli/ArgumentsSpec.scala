// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.cli

import munit.FunSuite

class ArgumentsSpec extends FunSuite {
  test("parse arguments") {
    val noargs = Arguments.empty

    List(
      "help"     -> noargs.withCommand(Command.Help),
      "version"  -> noargs.withCommand(Command.Version),
      "migrate"  -> noargs.withCommand(Command.Migrate),
      "validate" -> noargs.withCommand(Command.Validate),
      "-h"       -> noargs.withFlag(Flag.Help),
      "-url=postgresql://localhost:5432/postgres" -> noargs.withConfig(
        Config.Url,
        "postgresql://localhost:5432/postgres",
      ),
      "-ssl=none" -> noargs.withConfig(
        Config.Ssl,
        "none",
      ),
      "-schemas=public,schema1,schema2" -> noargs.withConfig(
        Config.Schemas,
        "public,schema1,schema2",
      ),
      "abc efg -x --y" -> noargs.copy(unknown = List("abc", "efg", "-x", "--y")),
    ).foreach { (input, expected) =>
      val argsList = input.split(" ").toList
      val result   = Arguments.parse(argsList)
      assertEquals(result, expected)
    }
  }
}
