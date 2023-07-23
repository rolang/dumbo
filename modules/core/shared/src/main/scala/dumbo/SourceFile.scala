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
