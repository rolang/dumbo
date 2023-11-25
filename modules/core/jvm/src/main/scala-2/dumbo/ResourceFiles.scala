// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.io.File
import java.nio.file.{Path, Paths}

import scala.jdk.CollectionConverters.*

import cats.effect.kernel.Sync
import cats.implicits.*
import dumbo.exception.*

final case class ResourceFilePath(value: String) extends AnyVal {
  def toNioPath: Path = Paths.get(value)
}

object ResourceFilePath {
  def fromResourcesDir[F[_]: Sync](location: String): F[List[ResourceFilePath]] =
    Sync[F].delay(getClass().getClassLoader().getResources(location).asScala.toList).flatMap {
      case head :: Nil =>
        Sync[F].delay {
          val base = Paths.get(head.toURI())
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
}
