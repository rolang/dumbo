// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.data.NonEmptyList
import fs2.io.file.Path

class ResourceFileDescriptionSpec extends ffstest.FTest {
  test("extract source file version from file path") {
    val fileNames = List(
      "V-003__test.sql",
      "V002__test.sql",
      "V1__test.sql",
      "V3__V4__test.sql",
      "V3.2____V4____test.sql",
      "V5.2__test.sql",
      "V-1.2__test.sql",
      "V1.2.3.4.5.6.7.8.9__test.sql",
      "V205.68__test.sql",
      "V20130115113556__test.sql",
      "V2013.2.15.11.35.56__test.sql",
      "V2013.01.15.11.35.56__test.sql",
    )

    val results              = fileNames.map(s => ResourceFileDescription.fromFilePath(Path(s)))
    val (failures, versions) = (results.collect { case Left(e) => e }, results.collect { case Right(v) => v })

    assertEquals(failures, Nil)

    val expected = List(
      ResourceFileVersion("-003", NonEmptyList.of(-3L)),
      ResourceFileVersion("-1.2", NonEmptyList.of(-1L, 2L)),
      ResourceFileVersion("1", NonEmptyList.of(1L)),
      ResourceFileVersion("1.2.3.4.5.6.7.8.9", NonEmptyList.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)),
      ResourceFileVersion("002", NonEmptyList.of(2L)),
      ResourceFileVersion("3", NonEmptyList.of(3L)),
      ResourceFileVersion("3.2", NonEmptyList.of(3L, 2L)),
      ResourceFileVersion("5.2", NonEmptyList.of(5L, 2L)),
      ResourceFileVersion("205.68", NonEmptyList.of(205L, 68L)),
      ResourceFileVersion("2013.01.15.11.35.56", NonEmptyList.of(2013L, 1L, 15L, 11L, 35L, 56L)),
      ResourceFileVersion("2013.2.15.11.35.56", NonEmptyList.of(2013L, 2L, 15L, 11L, 35L, 56L)),
      ResourceFileVersion("20130115113556", NonEmptyList.of(20130115113556L)),
    )

    assertEquals(versions.sorted.map(_.version), expected)
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
      .map(s => ResourceFileDescription.fromFilePath(Path(s)))
      .collect { case Right(r) => r }

    val versionsDistinct = versions.distinct
    val versionsSet      = versions.toSet

    assertEquals(versions.length, 5)

    assertEquals(versionsDistinct.map(_.version.parts.head), List(1L))
    assertEquals(versionsSet.map(_.version.parts.head), Set(1L))
  }
}
