// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import java.nio.file.Paths as JPaths

import scala.jdk.CollectionConverters.*
import scala.util.Try

import cats.effect.{Resource, Sync}
import fs2.io.file.{Files as Fs2Files, Path}

private[dumbo] class MultipleResoucesException(message: String) extends Throwable(message)

private[dumbo] trait FileSystemPlatform {

  def forDir[F[_]: Sync: Fs2Files](sourceDir: Path): Resource[F, FsPlatform[F]] =
    for {
      resouceUrls <- Resource
                       .eval(
                         Sync[F].delay {
                           getClass().getClassLoader().getResources(sourceDir.toString).asScala.toList.map(_.toURI())
                         }
                       )
      fs <- resouceUrls match {
              case Nil                                           => FsPlatform.fileFs(sourceDir = sourceDir)
              case uri :: Nil if uri.toString.startsWith("jar:") => FsPlatform.jarFs(uri, sourceDir)
              case uri :: Nil                                    =>
                // given absolutePath = /a/b/c/d and sourceDir = c/d return /a/b as baseDir
                val baseDir = Try(Path(JPaths.get(uri).toString().stripSuffix(sourceDir.toString))).toOption
                FsPlatform.fileFs(sourceDir = sourceDir, baseDir = baseDir)
              case multiple =>
                Resource.raiseError(
                  new MultipleResoucesException(
                    s"Multiple resources found: ${multiple.mkString("\n", ",\n", "")}"
                  ): Throwable
                )
            }
    } yield fs

}
