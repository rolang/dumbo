// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import cats.effect.IO
import dumbo.{Dumbo, DumboWithResourcesPartiallyApplied}
import fs2.io.file.Path
import munit.CatsEffectSuite

trait FTestPlatform extends CatsEffectSuite {
  def dumboWithResources(path: String): DumboWithResourcesPartiallyApplied[IO] =
    dumboWithFiles(Path("modules/tests/shared/src/test/resources/" + path))

  def dumboWithFiles(path: Path): DumboWithResourcesPartiallyApplied[IO] = Dumbo.withFilesIn(path)
}
