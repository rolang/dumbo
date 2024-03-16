// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.file.Paths

final case class ResourceFilePath(value: String) extends AnyVal {
  def fileName: String                    = Paths.get(value).getFileName.toString()
  def append(p: String): ResourceFilePath = ResourceFilePath(value + p)
}
