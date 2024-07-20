// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

class DumboUtilSpec extends ffstest.FTest {
  test("format execution time") {
    List(
      -100L    -> "00:00.000s",
      -1000L   -> "00:00.000s",
      -60000L  -> "00:00.000s",
      0L       -> "00:00.000s",
      1L       -> "00:00.001s",
      10L      -> "00:00.010s",
      1000L    -> "00:01.000s",
      1001L    -> "00:01.001s",
      60001L   -> "01:00.001s",
      61001L   -> "01:01.001s",
      70001L   -> "01:10.001s",
      600010L  -> "10:00.010s",
      6000000L -> "100:00.000s",
    ).foreach { case (input, expected) =>
      assertEquals(Dumbo.formatDuration(input), expected, s"input ${input}ms")
    }
  }
}
