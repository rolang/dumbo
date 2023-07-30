// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ffstest

import munit.CatsEffectSuite

trait FTestPlatform extends CatsEffectSuite {
  def resourcesPath(d: fs2.io.file.Path) = d
}
