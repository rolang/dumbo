// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import cats.effect.Resource
import fs2.io.file.{Files as Fs2Files, Path}

private[dumbo] trait FileSystemPlatform {
  def forDir[F[_]: Fs2Files](sourceDir: Path): Resource[F, FsPlatform[F]] = FsPlatform.fileFs(sourceDir)
}
