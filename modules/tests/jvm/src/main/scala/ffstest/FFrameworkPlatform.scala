package ffstest

import munit.CatsEffectSuite

trait FTestPlatform extends CatsEffectSuite {
  def resourcesPath(d: fs2.io.file.Path) = d
}
