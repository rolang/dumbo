// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.data.NonEmptyList
import fs2.io.file.Path

class SourceFileDescriptionSpec extends ffstest.FTest {
  test("extract source file version from file path") {
    val fileNames = List(
      "V-003__test.sql",
      "V002__test.sql",
      "V1__test.sql",
      "V5.2__test.sql",
      "V-1.2__test.sql",
      "V1.2.3.4.5.6.7.8.9__test.sql",
      "V205.68__test.sql",
      "V20130115113556__test.sql",
      "V2013.2.15.11.35.56__test.sql",
      "V2013.01.15.11.35.56__test.sql",
    )

    val versions = fileNames
      .map(s => SourceFileDescription.fromFilePath(Path(s)))
      .collect { case Right(r) => r }
      .sorted
      .map(_.version)

    val expected = List(
      SourceFileVersion("-003", NonEmptyList.of(-3L)),
      SourceFileVersion("-1.2", NonEmptyList.of(-1L, 2L)),
      SourceFileVersion("1", NonEmptyList.of(1L)),
      SourceFileVersion("1.2.3.4.5.6.7.8.9", NonEmptyList.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)),
      SourceFileVersion("002", NonEmptyList.of(2L)),
      SourceFileVersion("5.2", NonEmptyList.of(5L, 2L)),
      SourceFileVersion("205.68", NonEmptyList.of(205L, 68L)),
      SourceFileVersion("2013.01.15.11.35.56", NonEmptyList.of(2013L, 1L, 15L, 11L, 35L, 56L)),
      SourceFileVersion("2013.2.15.11.35.56", NonEmptyList.of(2013L, 2L, 15L, 11L, 35L, 56L)),
      SourceFileVersion("20130115113556", NonEmptyList.of(20130115113556L)),
    )

    assertEquals(versions, expected)
  }

  test("distinct by version") {
    val fileNames = List(
      "V1__test.sql",
      "V01__test.sql",
      "V0001__test.sql",
      "V1.0__test.sql",
      "V1.0.0.0__test.sql",
    )

    val versions = fileNames
      .map(s => SourceFileDescription.fromFilePath(Path(s)))
      .collect { case Right(r) => r }

    val versionsDistinct = versions.distinct
    val versionsSet      = versions.toSet

    assertEquals(versions.length, 5)

    assertEquals(versionsDistinct.map(_.version.parts.head), List(1L))
    assertEquals(versionsSet.map(_.version.parts.head), Set(1L))
  }
}
