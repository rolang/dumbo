// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import java.net.URI

import scala.concurrent.duration.FiniteDuration

import cats.effect.Resource
import fs2.Stream
import fs2.io.file.{Files as Fs2Files, Flags, Path}

private[dumbo] trait FsPlatform[F[_]] {
  def sourcesUri: java.net.URI

  def list(path: Path): fs2.Stream[F, Path]

  def readUtf8(path: Path): fs2.Stream[F, String]

  def readUtf8Lines(path: Path): fs2.Stream[F, String]

  def getLastModifiedTime(path: Path): F[FiniteDuration]
}

private[dumbo] object FsPlatform extends FileSystemPlatform {
  def fileFs[F[_]: Fs2Files](sourceDir: Path, baseDir: Option[Path] = None): Resource[F, FsPlatform[F]] =
    Resource.pure {
      val base = baseDir.getOrElse(Path.fromNioPath(java.nio.file.Paths.get(new java.io.File("").toURI())))

      @inline def absolutePath(p: Path) = if (p.isAbsolute) p else base / p

      new FsPlatform[F] {
        override val sourcesUri: URI = absolutePath(sourceDir).toNioPath.toUri()
        override def list(path: Path): Stream[F, Path] =
          Fs2Files[F].list(absolutePath(path))
        override def readUtf8Lines(path: Path): Stream[F, String] =
          Fs2Files[F].readUtf8Lines(absolutePath(path))

        override def readUtf8(path: Path): Stream[F, String] =
          Fs2Files[F].readAll(absolutePath(path), 64 * 2048, Flags.Read).through(fs2.text.utf8.decode)

        override def getLastModifiedTime(path: Path): F[FiniteDuration] =
          Fs2Files[F].getLastModifiedTime(absolutePath(path))
      }
    }
}
