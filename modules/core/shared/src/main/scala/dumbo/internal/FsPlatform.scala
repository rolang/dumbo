package dumbo.internal

import java.net.URI
import java.nio.file.Path as JPath

import scala.concurrent.duration.FiniteDuration

import cats.effect.Resource
import fs2.Stream
import fs2.io.file.{Files as Fs2Files, Path}

private[dumbo] trait FsPlatform[F[_]] {
  def sourcesUri: java.net.URI

  def list(path: Path): fs2.Stream[F, Path]

  def readUtf8Lines(path: Path): fs2.Stream[F, String]

  def getLastModifiedTime(path: Path): F[FiniteDuration]
}

private[dumbo] object FsPlatform extends FileSystemPlatform {
  def fileFs[F[_]: Fs2Files](sourceDir: Path, baseDir: Option[Path] = None): Resource[F, FsPlatform[F]] =
    Resource.pure {
      val base = baseDir.getOrElse(Path.fromNioPath(JPath.of(new java.io.File("").toURI())))

      @inline def absolutePath(p: Path) = if (p.isAbsolute) p else base / p

      new FsPlatform[F] {
        override val sourcesUri: URI = absolutePath(sourceDir).toNioPath.toUri()
        override def list(path: Path): Stream[F, Path] =
          Fs2Files[F].list(absolutePath(path))
        override def readUtf8Lines(path: Path): Stream[F, String] =
          Fs2Files[F].readUtf8Lines(absolutePath(path))

        override def getLastModifiedTime(path: Path): F[FiniteDuration] =
          Fs2Files[F].getLastModifiedTime(absolutePath(path))
      }
    }
}
