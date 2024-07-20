// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.annotation.tailrec
import scala.util.{Success, Try}

import cats.data.NonEmptyList
import cats.implicits.*

final case class ResourceFiles(
  versioned: List[ResourceFileVersioned],
  repeatable: List[ResourceFileRepeatable],
) {
  def length: Int       = versioned.length + repeatable.length
  def nonEmpty: Boolean = versioned.nonEmpty || repeatable.nonEmpty
}

object ResourceFiles {
  def fromResources(resources: List[ResourceFile]): ResourceFiles = {
    val (versioned, repeatable) = resources.partitionMap {
      case f @ ResourceFile(ResourceFileDescription(v: ResourceVersion.Versioned, _, _), _, _)  => Left((v, f))
      case f @ ResourceFile(ResourceFileDescription(v: ResourceVersion.Repeatable, _, _), _, _) => Right((v, f))
    }

    ResourceFiles(versioned, repeatable)
  }
}

final case class ResourceFile(
  description: ResourceFileDescription,
  checksum: Int,
  configs: Set[ResourceFileConfig],
) extends Ordered[ResourceFile] {
  def versionText: Option[String] = description.version.versionText
  def version: ResourceVersion    = description.version
  def scriptDescription: String   = description.description
  def path: ResourceFilePath      = description.path
  def fileName: String            = description.path.fileName

  override def hashCode: Int = version.hashCode

  def compare(that: ResourceFile): Int = version.compare(that.version)

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: ResourceFile => version.equals(s.version)
    case _               => false
  }

  val executeInTransaction: Boolean =
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
        case `txn` :: "false" :: Nil => List(ExecuteInTransaction(value = false)).asRight[String]
        case `txn` :: "true" :: Nil  => List(ExecuteInTransaction(value = true)).asRight[String]
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
  version: ResourceVersion,
  description: String,
  path: ResourceFilePath,
) extends Ordered[ResourceFileDescription] {
  def compare(that: ResourceFileDescription): Int = this.version.compare(that.version)

  override def hashCode: Int = this.version.hashCode

  override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
    case s: ResourceFileDescription => this.version.equals(s.version)
    case _                          => false
  }

  def fileName: String = path.fileName
}

object ResourceFileDescription {
  def fromResourcePath(p: ResourceFilePath): Either[String, ResourceFileDescription] = {
    val versioned  = "^V([^_]+)__(.+)\\.sql$".r
    val repeatable = "^R__(.+)\\.sql$".r

    p.fileName.toString match {
      case versioned(version, name) =>
        ResourceVersion.Versioned.fromString(version).map { v =>
          ResourceFileDescription(
            version = v,
            description = name.replace("_", " "),
            path = p,
          )
        }
      case repeatable(name) =>
        val description = name.replace("_", " ")
        Right(
          ResourceFileDescription(
            version = ResourceVersion.Repeatable(description),
            description = description,
            path = p,
          )
        )
      case other => Left(s"Invalid file name $other")
    }
  }
}

sealed trait ResourceVersion extends Ordered[ResourceVersion] {
  import ResourceVersion.*

  def compare(that: ResourceVersion): Int = {
    @tailrec
    def cmprVersioned(a: List[Long], b: List[Long]): Int =
      (a, b) match {
        case (xa :: xsa, xb :: xsb) if xa == xb => cmprVersioned(xsa, xsb)
        case (xa :: _, xb :: _)                 => xa.compare(xb)
        case (xa :: _, Nil)                     => xa.compare(0L)
        case (Nil, xb :: _)                     => xb.compare(0L)
        case (Nil, Nil)                         => 0
      }

    (this, that) match {
      case (Repeatable(_), Versioned(_, _))             => 1
      case (Versioned(_, _), Repeatable(_))             => -1
      case (Repeatable(descThis), Repeatable(descThat)) => descThis.compare(descThat)
      case (Versioned(_, thisParts), Versioned(_, thatParts)) =>
        cmprVersioned(thisParts.toList, thatParts.toList)
    }
  }

  def versionText: Option[String] = this match {
    case Repeatable(_)       => None
    case Versioned(plain, _) => Some(plain)
  }
}

object ResourceVersion {
  case class Repeatable(description: String)                          extends ResourceVersion
  final case class Versioned(text: String, parts: NonEmptyList[Long]) extends ResourceVersion {
    // strip trailing 0
    // 1.0 -> 1
    // 0.01.0.0 -> 0.1
    override def toString: String =
      parts.reverse.toList.dropWhile(_ <= 0).map(_.toString).reverse.mkString(".")

    // 1.0 should yield same hash code as 1 or 1.0.0 etc.
    override def hashCode: Int = parts.reverse.foldLeft("")(_ + _.toString).toInt

    override def equals(b: Any): Boolean = b.asInstanceOf[Matchable] match {
      case s: Versioned => this.compare(s) == 0
      case _            => false
    }
  }

  object Versioned {
    def fromString(version: String): Either[String, Versioned] =
      Try(version.split('.').map(_.toLong)) match {
        case Success(Array(x, xs*)) =>
          Right(
            Versioned(
              text = version,
              parts = NonEmptyList.of(x, xs*),
            )
          )
        case _ => Left(s"Invalid version $version")
      }
  }
}
