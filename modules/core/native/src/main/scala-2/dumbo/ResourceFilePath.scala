// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.file.{Path, Paths}

final case class ResourceFilePath(value: String) extends AnyVal {
  def toNioPath: Path = Paths.get(value)
}
