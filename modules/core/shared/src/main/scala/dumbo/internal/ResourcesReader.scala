// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import cats.effect.Sync
import cats.implicits.*
import dumbo.ResourceFilePath
import fs2.io.file.{Files as Fs2Files, Flags, Path}
import fs2.{Stream, text}

private[dumbo] trait ResourceReader[F[_]] {
  def location: Option[String]

  def list: fs2.Stream[F, Path]

  def readUtf8(path: Path): fs2.Stream[F, String]

  def readUtf8Lines(path: Path): fs2.Stream[F, String]

  def exists(path: Path): F[Boolean]
}

private[dumbo] object ResourceReader {
  def fileFs[F[_]: Fs2Files](sourceDir: Path): ResourceReader[F] = {
    val base = Path.fromNioPath(java.nio.file.Paths.get(new java.io.File("").toURI()))

    @inline def absolutePath(p: Path) = if (p.isAbsolute) p else base / p

    new ResourceReader[F] {
      override val location: Option[String] = Some(absolutePath(sourceDir).toString)
      override def list: Stream[F, Path] =
        Fs2Files[F].list(absolutePath(sourceDir))

      override def readUtf8Lines(path: Path): Stream[F, String] =
        Fs2Files[F].readUtf8Lines(absolutePath(path))

      override def readUtf8(path: Path): Stream[F, String] =
        Fs2Files[F].readAll(absolutePath(path), 64 * 2048, Flags.Read).through(fs2.text.utf8.decode)

      override def exists(path: Path): F[Boolean] = Fs2Files[F].exists(absolutePath(path))
    }
  }

  def embeddedResources[F[_]: Sync](
    readResources: F[List[ResourceFilePath]],
    locationInfo: Option[String] = None,
  ): ResourceReader[F] =
    new ResourceReader[F] {
      private def toResourcePath(p: Path) =
        p.toString.split(java.io.File.separatorChar).filter(_.nonEmpty).mkString("/", "/", "")

      override val location: Option[String] = locationInfo

      override def list: Stream[F, Path] = Stream.evals(readResources).map(r => Path.fromNioPath(r.toNioPath))

      override def readUtf8Lines(path: Path): Stream[F, String] = readUtf8(path).through(text.lines)

      override def readUtf8(path: Path): Stream[F, String] =
        fs2.io
          .readInputStream(
            Sync[F].delay(
              getClass().getResourceAsStream(toResourcePath(path))
            ),
            64 * 2048,
            closeAfterUse = true,
          )
          .through(fs2.text.utf8.decode)

      override def exists(path: Path): F[Boolean] =
        Sync[F].delay(getClass().getResourceAsStream(toResourcePath(path)) != null)
    }
}
