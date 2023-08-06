// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}

import cats.data.NonEmptyList
import fs2.io.file.Path

final case class SourceFile(
  description: SourceFileDescription,
  checksum: Int,
  lastModified: FiniteDuration,
) extends Ordered[SourceFile] {
  def versionRaw: String         = description.version.raw
  def version: SourceFileVersion = description.version
  def scriptDescription: String  = description.description
  def path: Path                 = description.path

  override def hashCode: Int = version.hashCode

  def compare(that: SourceFile): Int = version.compare(that.version)

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: SourceFile => version.equals(s.version)
    case _             => false
  }
}

final case class SourceFileDescription(
  version: SourceFileVersion,
  description: String,
  path: Path,
) extends Ordered[SourceFileDescription] {
  def compare(that: SourceFileDescription): Int = this.version.compare(that.version)

  override def hashCode: Int = this.version.hashCode

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: SourceFileDescription => this.version.equals(s.version)
    case _                        => false
  }
}

object SourceFileDescription {
  def fromFilePath(p: Path): Either[String, SourceFileDescription] = {
    val pattern = "^V(.+)__(.+)\\.sql$".r

    p.fileName.toString match {
      case pattern(version, name) =>
        SourceFileVersion.fromString(version).map { v =>
          SourceFileDescription(
            version = v,
            description = name.replace("_", " "),
            path = p,
          )
        }

      case other => Left(s"Invalid file name $other")
    }
  }
}

final case class SourceFileVersion(
  raw: String,
  parts: NonEmptyList[Long],
) extends Ordered[SourceFileVersion] {
  def compare(that: SourceFileVersion): Int = {
    @tailrec
    def cmpr(a: List[Long], b: List[Long]): Int =
      (a, b) match {
        case (xa :: xsa, xb :: xsb) if xa == xb => cmpr(xsa, xsb)
        case (xa :: _, xb :: _)                 => xa.compare(xb)
        case (xa :: _, Nil)                     => xa.compare(0L)
        case (Nil, xb :: _)                     => xb.compare(0L)
        case (Nil, Nil)                         => 0
      }

    cmpr(this.parts.toList, that.parts.toList)
  }

  // strip trailing 0
  // 1.0 -> 1
  // 0.01.0.0 -> 0.1
  override def toString: String =
    parts.reverse.toList.dropWhile(_ <= 0).map(_.toString).reverse.mkString(".")

  // 1.0 should yield same hash code as 1 or 1.0.0 etc.
  override def hashCode: Int = parts.reverse.foldLeft("")(_ + _.toString).toInt

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: SourceFileVersion => this.compare(s) == 0
    case _                    => false
  }
}

object SourceFileVersion {
  def fromString(version: String): Either[String, SourceFileVersion] =
    Try(version.split('.').map(_.toLong)) match {
      case Success(Array(x, xs*)) =>
        Right(
          SourceFileVersion(
            raw = version,
            parts = NonEmptyList.of(x, xs*),
          )
        )
      case _ => Left(s"Invalid version $version")
    }
}
