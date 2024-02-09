// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.nio.file.Path as JPath

import scala.annotation.tailrec
import scala.util.{Success, Try}

import cats.data.NonEmptyList
import cats.implicits.*
import fs2.io.file.Path

final case class ResourceFile(
  description: ResourceFileDescription,
  checksum: Int,
  configs: Set[ResourceFileConfig],
) extends Ordered[ResourceFile] {
  def versionRaw: String           = description.version.raw
  def version: ResourceFileVersion = description.version
  def scriptDescription: String    = description.description
  def path: Path                   = description.path

  override def hashCode: Int = version.hashCode

  def compare(that: ResourceFile): Int = version.compare(that.version)

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: ResourceFile => version.equals(s.version)
    case _               => false
  }

  def executeInTransaction: Boolean =
    configs.collectFirst { case ResourceFileConfig.ExecuteInTransaction(v) => v }.getOrElse(true)
}

sealed abstract class ResourceFileConfig(protected val key: String) {
  override def hashCode(): Int = key.hashCode()

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: ResourceFileConfig => s.key == key
    case _                     => false
  }
}

object ResourceFileConfig {
  final case class ExecuteInTransaction(value: Boolean) extends ResourceFileConfig(txn)

  private val txn = "executeInTransaction"

  private def invalidBoolean(key: String, v: String) =
    s"Invalid value for $key (should be either true or false): $v".asLeft[List[ResourceFileConfig]]

  private def unknownProperty(key: String) =
    s"Unknown configuration property: $key".asLeft[List[ResourceFileConfig]]

  def fromLines(lines: List[String]): Either[String, Set[ResourceFileConfig]] =
    (lines
      .filter(_.trim().nonEmpty)
      .map(_.split("=").map(_.trim()).toList)
      .flatTraverse {
        case `txn` :: "false" :: Nil => List(ExecuteInTransaction(false)).asRight[String]
        case `txn` :: "true" :: Nil  => List(ExecuteInTransaction(true)).asRight[String]
        case `txn` :: v              => invalidBoolean(txn, v.mkString("="))
        case unknown :: _            => unknownProperty(unknown)
        case Nil                     => Nil.asRight[String]
      })
      .flatMap { l =>
        l.diff(l.distinct) match {
          case x :: _ => Left(s"""Multiple configurations for "${x.key}"""")
          case Nil    => Right(l.toSet)
        }
      }
}

final case class ResourceFileDescription(
  version: ResourceFileVersion,
  description: String,
  path: Path,
) extends Ordered[ResourceFileDescription] {
  def compare(that: ResourceFileDescription): Int = this.version.compare(that.version)

  override def hashCode: Int = this.version.hashCode

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: ResourceFileDescription => this.version.equals(s.version)
    case _                          => false
  }
}

object ResourceFileDescription {
  def fromNioPath(p: JPath): Either[String, ResourceFileDescription] = fromFilePath(Path.fromNioPath(p))
  def fromFilePath(p: Path): Either[String, ResourceFileDescription] = {
    val pattern = "^V([^_]+)__(.+)\\.sql$".r

    p.fileName.toString match {
      case pattern(version, name) =>
        ResourceFileVersion.fromString(version).map { v =>
          ResourceFileDescription(
            version = v,
            description = name.replace("_", " "),
            path = p,
          )
        }

      case other => Left(s"Invalid file name $other")
    }
  }
}

final case class ResourceFileVersion(
  raw: String,
  parts: NonEmptyList[Long],
) extends Ordered[ResourceFileVersion] {
  def compare(that: ResourceFileVersion): Int = {
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
    case s: ResourceFileVersion => this.compare(s) == 0
    case _                      => false
  }
}

object ResourceFileVersion {
  def fromString(version: String): Either[String, ResourceFileVersion] =
    Try(version.split('.').map(_.toLong)) match {
      case Success(Array(x, xs*)) =>
        Right(
          ResourceFileVersion(
            raw = version,
            parts = NonEmptyList.of(x, xs*),
          )
        )
      case _ => Left(s"Invalid version $version")
    }
}
