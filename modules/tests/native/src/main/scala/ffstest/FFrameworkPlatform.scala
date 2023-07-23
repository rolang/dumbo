package ffstest

import epollcat.unsafe.EpollRuntime
import fs2.io.file.Path
import munit.CatsEffectSuite

trait FTestPlatform extends CatsEffectSuite {
  override def munitIORuntime = EpollRuntime.global

  def resourcesPath(d: Path): Path = Path("modules/tests/shared/src/test/resources") / d
}
