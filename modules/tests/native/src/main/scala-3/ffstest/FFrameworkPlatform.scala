// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import epollcat.unsafe.EpollRuntime
import fs2.io.file.Path
import munit.CatsEffectSuite
import dumbo.DumboWithResourcesPartiallyApplied
import cats.effect.IO
import dumbo.Dumbo

trait FTestPlatform extends CatsEffectSuite {
  override def munitIORuntime = EpollRuntime.global

  inline def dumboWithResources(path: String): DumboWithResourcesPartiallyApplied[IO] =
    // using this to disable embdedded resources for now in CI as it causes flaky tests: https://github.com/scala-native/scala-native/issues/2024
    Dumbo.withResourcesIn(path)
    // if sys.env.get("CI").isEmpty then Dumbo.withResourcesIn(path)
    // else Dumbo.withFilesIn(Path("modules/tests/shared/src/test/resources") / path)

  def dumboWithFiles(path: Path): DumboWithResourcesPartiallyApplied[IO] = Dumbo.withFilesIn(path)
}
