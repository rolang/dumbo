package dumbo.internal

import java.net.{URI, URL}
import java.nio.file.{FileSystem, FileSystems, Files, Path as JPath}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

import cats.effect.{Resource, Sync}
import fs2.Stream
import fs2.io.file.{Files as Fs2Files, Path}

private[dumbo] class MultipleResoucesException(message: String) extends Throwable(message)

private[dumbo] trait FileSystemPlatform {

  private def jarFs[F[_]: Sync: Fs2Files](sourceUrl: URL): Resource[F, FsPlatform[F]] = for {
    srcUri <- Resource.eval(Sync[F].delay(sourceUrl.toURI()))
    fs <- Resource
            .fromAutoCloseable[F, FileSystem]({
              Sync[F].delay {
                FileSystems.newFileSystem(srcUri, new java.util.HashMap[String, Object]())
              }
            })
  } yield new FsPlatform[F] {
    override val sourcesUri: URI = srcUri
    override def list(path: Path): Stream[F, Path] =
      Stream
        .fromIterator(Files.list(fs.getPath(path.toNioPath.toString())).iterator().asScala, 16)
        .map(Path.fromNioPath(_))

    override def readUtf8Lines(path: Path): Stream[F, String] =
      Fs2Files[F].readUtf8Lines(fs2.io.file.Path.fromFsPath(fs, path.toString))

    override def getLastModifiedTime(path: Path): F[FiniteDuration] =
      Fs2Files[F].getLastModifiedTime(fs2.io.file.Path.fromFsPath(fs, path.toString))
  }

  def forDir[F[_]: Sync: Fs2Files](sourceDir: Path): Resource[F, FsPlatform[F]] =
    for {
      resouceUrls <- Resource
                       .eval(
                         Sync[F].delay {
                           getClass().getClassLoader().getResources(sourceDir.toString).asScala.toList
                         }
                       )
      fs <- resouceUrls match {
              case Nil                                       => FsPlatform.fileFs(sourceDir = sourceDir)
              case u :: Nil if u.toString.startsWith("jar:") => jarFs(u)
              case u :: Nil =>
                val baseDir =
                  Try(u.toURI()).toOption.map { uri =>
                    // given absolutePath = /a/b/c/d and sourceDir = c/d return /a/b as baseDir
                    Path(JPath.of(uri).toString().stripSuffix(sourceDir.toString))
                  }
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
