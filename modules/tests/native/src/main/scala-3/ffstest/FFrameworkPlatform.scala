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

  inline def dumboWithResources(path: String): DumboWithResourcesPartiallyApplied[IO] = Dumbo.withResourcesIn(path)

  def dumboWithFiles(path: Path): DumboWithResourcesPartiallyApplied[IO] = Dumbo.withFilesIn(path)
}
