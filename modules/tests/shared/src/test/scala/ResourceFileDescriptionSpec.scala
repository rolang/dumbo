// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import cats.data.NonEmptyList

class ResourceFileDescriptionSpec extends ffstest.FTest {
  test("extract source file version from file path") {
    val fileNames = List(
      "R__c_view.sql",
      "R__a_view.sql",
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
      "R__b_view.sql",
    )

    val (failures, versions) = fileNames
      .map(s => ResourceFileDescription.fromResourcePath(ResourceFilePath(s)))
      .partitionMap(r => r)

    assertEquals(failures, Nil)

    val expected = List(
      ResourceVersion.Versioned("-003", NonEmptyList.of(-3L)),
      ResourceVersion.Versioned("-1.2", NonEmptyList.of(-1L, 2L)),
      ResourceVersion.Versioned("1", NonEmptyList.of(1L)),
      ResourceVersion.Versioned("1.2.3.4.5.6.7.8.9", NonEmptyList.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)),
      ResourceVersion.Versioned("002", NonEmptyList.of(2L)),
      ResourceVersion.Versioned("3", NonEmptyList.of(3L)),
      ResourceVersion.Versioned("3.2", NonEmptyList.of(3L, 2L)),
      ResourceVersion.Versioned("5.2", NonEmptyList.of(5L, 2L)),
      ResourceVersion.Versioned("205.68", NonEmptyList.of(205L, 68L)),
      ResourceVersion.Versioned("2013.01.15.11.35.56", NonEmptyList.of(2013L, 1L, 15L, 11L, 35L, 56L)),
      ResourceVersion.Versioned("2013.2.15.11.35.56", NonEmptyList.of(2013L, 2L, 15L, 11L, 35L, 56L)),
      ResourceVersion.Versioned("20130115113556", NonEmptyList.of(20130115113556L)),
      // repeatables are sorted by description
      ResourceVersion.Repeatable("a view"),
      ResourceVersion.Repeatable("b view"),
      ResourceVersion.Repeatable("c view"),
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
      .map(s => ResourceFileDescription.fromResourcePath(ResourceFilePath(s)))
      .collect { case Right(r) => r }

    val versionsDistinct = versions.distinct.collect {
      case ResourceFileDescription(v: ResourceVersion.Versioned, _, _) => v
    }
    val versionsSet = versions.collect { case ResourceFileDescription(v: ResourceVersion.Versioned, _, _) => v }.toSet

    assertEquals(versions.length, 5)

    assertEquals(versionsDistinct.map(_.parts.head), List(1L))
    assertEquals(versionsSet.map(_.parts.head), Set(1L))
  }
}
