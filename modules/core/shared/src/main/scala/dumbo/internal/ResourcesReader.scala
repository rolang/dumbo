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

  def list: fs2.Stream[F, ResourceFilePath]

  def readUtf8(path: ResourceFilePath): fs2.Stream[F, String]

  def readUtf8Lines(path: ResourceFilePath): fs2.Stream[F, String]

  def exists(path: ResourceFilePath): F[Boolean]
}

private[dumbo] object ResourceReader {
  def fileFs[F[_]: Fs2Files](sourceDir: Path): ResourceReader[F] = {
    val base = Path.fromNioPath(java.nio.file.Paths.get(new java.io.File("").toURI()))

    @inline def absolutePath(p: Path) = if (p.isAbsolute) p else base / p

    new ResourceReader[F] {
      override val location: Option[String] = Some(absolutePath(sourceDir).toString)
      override def list: Stream[F, ResourceFilePath] =
        Fs2Files[F]
          .list(absolutePath(sourceDir))
          .map(p => ResourceFilePath(p.toString))

      override def readUtf8Lines(path: ResourceFilePath): Stream[F, String] =
        Fs2Files[F].readUtf8Lines(absolutePath(Path(path.value)))

      override def readUtf8(path: ResourceFilePath): Stream[F, String] =
        Fs2Files[F].readAll(absolutePath(Path(path.value)), 64 * 2048, Flags.Read).through(fs2.text.utf8.decode)

      override def exists(path: ResourceFilePath): F[Boolean] = Fs2Files[F].exists(absolutePath(Path(path.value)))
    }
  }

  def embeddedResources[F[_]: Sync](
    readResources: F[List[ResourceFilePath]],
    locationInfo: Option[String] = None,
  ): ResourceReader[F] =
    new ResourceReader[F] {
      override val location: Option[String] = locationInfo

      override def list: Stream[F, ResourceFilePath] = Stream.evals(readResources)

      override def readUtf8Lines(path: ResourceFilePath): Stream[F, String] = readUtf8(path).through(text.lines)

      override def readUtf8(path: ResourceFilePath): Stream[F, String] =
        fs2.io
          .readInputStream(
            Sync[F].delay(getClass().getResourceAsStream(path.value)),
            64 * 2048,
            closeAfterUse = true,
          )
          .through(fs2.text.utf8.decode)

      override def exists(path: ResourceFilePath): F[Boolean] =
        Sync[F].delay(getClass().getResourceAsStream(path.value) != null)
    }
}
