// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import epollcat.unsafe.EpollRuntime
import fs2.io.file.Path
import munit.CatsEffectSuite

trait FTestPlatform extends CatsEffectSuite {
  override def munitIORuntime = EpollRuntime.global

  def resourcesPath(d: Path): Path = Path("modules/tests/shared/src/test/resources") / d
}
