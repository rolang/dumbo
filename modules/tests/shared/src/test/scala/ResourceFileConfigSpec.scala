// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

class ResourceFileConfigSpec extends ffstest.FTest {
  test("parse config file") {
    assertEquals(
      ResourceFileConfig.fromLines(List("executeInTransaction=true")),
      Right[String, Set[ResourceFileConfig]](Set(ResourceFileConfig.ExecuteInTransaction(value = true))),
    )

    assertEquals(
      ResourceFileConfig.fromLines(List("executeInTransaction=false")),
      Right[String, Set[ResourceFileConfig]](Set(ResourceFileConfig.ExecuteInTransaction(value = false))),
    )

    assertEquals(
      ResourceFileConfig.fromLines(List(" ", " executeInTransaction =  true ", "")),
      Right[String, Set[ResourceFileConfig]](Set(ResourceFileConfig.ExecuteInTransaction(value = true))),
    )

    assertEquals(
      ResourceFileConfig.fromLines(List("executeInTransaction=true", "executeInTransaction=true")),
      Left("Multiple configurations for \"executeInTransaction\""),
    )

    assertEquals(
      ResourceFileConfig.fromLines(List("executeInTransaction=false", "executeInTransaction=true")),
      Left("Multiple configurations for \"executeInTransaction\""),
    )

    assertEquals(
      ResourceFileConfig.fromLines(List("executeInTransaction=true ", "unknown=abc")),
      Left("Unknown configuration property: unknown"),
    )

    assertEquals(ResourceFileConfig.fromLines(List("unknown=true")), Left("Unknown configuration property: unknown"))
    assertEquals(ResourceFileConfig.fromLines(List("abc")), Left("Unknown configuration property: abc"))

    assertEquals(
      ResourceFileConfig
        .fromLines(List("executeInTransaction=true=false")),
      Left("Invalid value for executeInTransaction (should be either true or false): true=false"),
    )

    assertEquals(
      ResourceFileConfig
        .fromLines(List("executeInTransaction=abc")),
      Left("Invalid value for executeInTransaction (should be either true or false): abc"),
    )
  }
}
