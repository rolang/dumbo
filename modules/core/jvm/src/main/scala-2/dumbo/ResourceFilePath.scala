// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import cats.effect.Resource
import cats.effect.kernel.Sync
import dumbo.exception.*

final case class ResourceFilePath(value: String) extends AnyVal {
  def fileName: String                    = Paths.get(value).getFileName.toString()
  def append(p: String): ResourceFilePath = ResourceFilePath(value + p)
}

object ResourceFilePath {
  private[dumbo] def fromResourcesDir[F[_]: Sync](location: String): (String, F[List[ResourceFilePath]]) =
    Try(getClass().getClassLoader().getResources(location).asScala.toList) match {
      case Failure(err)                                           => ("", Sync[F].raiseError(err))
      case Success(Nil)                                           => ("", Sync[F].raiseError(new ResourcesLocationNotFund(s"resource ${location} was not found")))
      case Success(url :: Nil) if url.toString.startsWith("jar:") => (url.toString, listInJar(url.toURI(), location))
      case Success(url :: Nil) =>
        (
          url.toString,
          Sync[F].delay {
            val base = Paths.get(url.toURI())
            val resources =
              new File(base.toString())
                .list()
                .map(fileName => ResourceFilePath(s"/$location/$fileName"))
                .toList
            resources
          },
        )
      case Success(multiple) =>
        (
          "",
          Sync[F].raiseError(
            new MultipleResoucesException(
              s"found multiple resource locations for ${location} in:\n${multiple.mkString("\n")}"
            )
          ),
        )

    }

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
