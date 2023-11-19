// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import munit.CatsEffectSuite
import dumbo.{DumboWithResourcesPartiallyApplied, Dumbo}
import fs2.io.file.Path
import cats.effect.IO

trait FTestPlatform extends CatsEffectSuite {
  inline def dumboWithResources(path: String): DumboWithResourcesPartiallyApplied[IO] = Dumbo.withResourcesIn(path)
  def dumboWithFiles(path: Path): DumboWithResourcesPartiallyApplied[IO]       = Dumbo.withFilesIn(path)
}
