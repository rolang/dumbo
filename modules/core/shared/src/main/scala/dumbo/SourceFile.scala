// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.concurrent.duration.FiniteDuration

import fs2.io.file.Path

final case class SourceFile(
  rank: Int,
  description: String,
  path: Path,
  checksum: Int,
  lastModified: FiniteDuration,
)
