// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.io.Source
import scala.jdk.CollectionConverters.*

import cats.effect.Sync
import dumbo.{ResourceFile, ResourceFilePath}

private[dumbo] trait ResourceReader[F[_]] {
  def relativeResourcePath(resource: ResourceFile): String

  def location: Option[String]

  def list: F[List[ResourceFilePath]]

  def readUtf8(path: ResourceFilePath): F[String]

  def readUtf8Lines(path: ResourceFilePath): F[List[String]]

  def exists(path: ResourceFilePath): F[Boolean]
}

private[dumbo] object ResourceReader {
  def fileFs[F[_]: Sync](sourceDir: Path): ResourceReader[F] = {
    val base = Path.of(new java.io.File("").toURI())

    @inline def absolutePath(p: Path) = if (p.isAbsolute) p else Path.of(base.toString(), p.toString())

    new ResourceReader[F] {

      override def relativeResourcePath(resource: ResourceFile): String =
        absolutePath(sourceDir).relativize(absolutePath(Path.of(resource.path.value))).toString

      override val location: Option[String]        = Some(absolutePath(sourceDir).toString)
      override def list: F[List[ResourceFilePath]] =
        Sync[F].delay(
          Files
            .walk(absolutePath(sourceDir))
            .iterator()
            .asScala
            .toList
            .map(p => ResourceFilePath(p.toString()))
        )

      def readUtf8Lines(path: ResourceFilePath): F[List[String]] =
        Sync[F].delay(Files.readAllLines(Path.of(path.value), StandardCharsets.UTF_8).asScala.toList)

      override def readUtf8(path: ResourceFilePath): F[String] =
        Sync[F].delay(Files.readString(Path.of(path.value)))

      override def exists(path: ResourceFilePath): F[Boolean] =
        Sync[F].delay(Files.exists(absolutePath(Path.of(path.value))))
    }
  }

  def embeddedResources[F[_]: Sync](
    readResources: F[List[ResourceFilePath]],
    locationInfo: Option[String] = None,
    locationRelative: Option[String] = None,
  ): ResourceReader[F] =
    new ResourceReader[F] {
      override def relativeResourcePath(resource: ResourceFile): String =
        locationRelative match {
          case Some(l) => resource.path.value.stripPrefix(s"/$l/")
          case _       => resource.fileName
        }

      override val location: Option[String] = locationInfo

      override def list: F[List[ResourceFilePath]] = readResources

      override def readUtf8Lines(path: ResourceFilePath): F[List[String]] =
        Sync[F].delay(
          Source
            .fromInputStream(getClass().getResourceAsStream(path.value), StandardCharsets.UTF_8.toString())
            .getLines()
            .toList
        )

      override def readUtf8(path: ResourceFilePath): F[String] =
        Sync[F].delay(
          Source
            .fromInputStream(getClass().getResourceAsStream(path.value), StandardCharsets.UTF_8.toString())
            .mkString
        )

      override def exists(path: ResourceFilePath): F[Boolean] =
        Sync[F].delay(getClass().getResourceAsStream(path.value) != null)
    }
}
