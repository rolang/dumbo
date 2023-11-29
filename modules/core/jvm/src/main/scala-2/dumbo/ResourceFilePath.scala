// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.implicits.*
import dumbo.exception.*

final case class ResourceFilePath(value: String) extends AnyVal {
  def toNioPath: Path = Paths.get(value)
}

object ResourceFilePath {
  def fromResourcesDir[F[_]: Sync](location: String): F[List[ResourceFilePath]] =
    Sync[F].delay(getClass().getClassLoader().getResources(location).asScala.toList).flatMap {
      case url :: Nil if url.toString.startsWith("jar:") => listInJar(url.toURI(), location)
      case url :: Nil =>
        Sync[F].delay {
          val base = Paths.get(url.toURI())
          val resources =
            new File(base.toString()).list().map(fileName => apply(Paths.get("/", location, fileName))).toList
          resources
        }
      case Nil => Sync[F].raiseError(new ResourcesLocationNotFund(s"resource ${location} was not found"))
      case multiple =>
        Sync[F].raiseError(
          new MultipleResoucesException(
            s"found multiple resource locations for ${location} in:\n${multiple.mkString("\n")}"
          )
        )
    }

  def apply(p: Path): ResourceFilePath = ResourceFilePath(p.toString())

  private def listInJar[F[_]: Sync](jarUri: URI, location: String): F[List[ResourceFilePath]] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        val srcUriStr   = jarUri.toString()
        val jarFilePath = srcUriStr.slice(srcUriStr.lastIndexOf(":") + 1, srcUriStr.lastIndexOf("!"))
        new ZipFile(jarFilePath)
      }
    }.use { fs =>
      Sync[F].delay {
        fs
          .entries()
          .asScala
          .toList
          .filter(_.getName().startsWith(location))
          .map(entry => ResourceFilePath(s"/${entry.getName()}"))
      }
    }

}
