package dumbo.internal

import cats.effect.Resource
import fs2.io.file.{Files as Fs2Files, Path}

private[dumbo] trait FileSystemPlatform {
  def forDir[F[_]: Fs2Files](sourceDir: Path): Resource[F, FsPlatform[F]] = FsPlatform.fileFs(sourceDir)
}
