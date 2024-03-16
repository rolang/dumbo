// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.cli

import java.net.URI

import scala.annotation.tailrec
import scala.util.Try

import dumbo.Dumbo.defaults
import fs2.io.file.Path
import skunk.SSL

final case class Arguments(
  commands: List[Command],
  configs: List[(Config[?], String)],
  flags: List[Flag],
  unknown: List[String],
) {
  def withCommand(c: Command)             = copy(commands = c :: commands)
  def withConfig(c: Config[?], v: String) = copy(configs = (c, v) :: configs)
  def withFlag(f: Flag)                   = copy(flags = f :: flags)
  def withUnknown(arg: String)            = copy(unknown = arg :: unknown)
}

object Arguments:
  val empty: Arguments = Arguments(Nil, Nil, Nil, Nil)

  private def toKeyValue(s: String): (String, Option[String]) =
    s.splitAt(s.indexOf('=')) match
      case ("", v) => (v, None)
      case (k, v) =>
        (
          k,
          Some(
            v.stripPrefix("=").toSeq match
              case '"' +: vv :+ '"'   => vv.mkString
              case '\'' +: vv :+ '\'' => vv.mkString
              case o                  => o.mkString
          ),
        )

  def parse(arguments: List[String]): Arguments =
    @tailrec
    def walk(args: List[String], result: Arguments): Arguments =
      args match
        case Nil => result
        case arg :: tail =>
          Command.values.find(_.keys.contains(arg)) match
            case Some(cmd) => walk(tail, result.withCommand(cmd))
            case None =>
              toKeyValue(arg) match
                case (k, Some(value)) =>
                  Config.values.find(_.key == k) match
                    case None       => walk(tail, result.withUnknown(arg))
                    case Some(conf) => walk(tail, result.withConfig(conf, value))
                case (k, _) =>
                  Flag.values.find(_.keys.contains(k)) match
                    case None       => walk(tail, result.withUnknown(arg))
                    case Some(flag) => walk(tail, result.withFlag(flag))

    walk(args = arguments.reverse, Arguments.empty)

enum Command(val keys: Set[String], val desc: String, val configs: List[Config[?]], val flags: List[Flag]):
  case Help    extends Command(Set("help"), "Print this usage info and exit", Nil, Nil)
  case Migrate extends Command(Set("migrate"), "Migrates the database", Config.values.toList, Nil)
  case Validate
      extends Command(
        Set("validate"),
        "Validates the applied migrations against the ones in the location",
        Config.values.toList,
        Nil,
      )
  case Version extends Command(Set("version", "-v", "--version"), "Print the Dumbo version", Nil, Nil)

object Command:
  val helpMap: Map[String, String] = Command.values.map { c =>
    c.keys.mkString(", ") -> c.desc
  }.toList.toMap

enum Config[T](val key: String, val desc: String, val parse: String => Either[String, T]):
  case Url
      extends Config[URI](
        key = "-url",
        desc = "Url to use to connect to the database",
        (v: String) => Try(new java.net.URI(v)).toEither.left.map(_.getMessage()),
      )

  case User
      extends Config[String](
        key = "-user",
        desc = "User to use to connect to the database",
        Right(_),
      )

  case Password
      extends Config[String](
        key = "-password",
        desc = "Password to use to connect to the database",
        Right(_),
      )

  case Ssl
      extends Config[SSL](
        key = "-ssl",
        desc = "SSL mode to use: \"none\", \"trusted\" or \"system\". Default is \"none\"",
        {
          case "none"    => Right(skunk.SSL.None)
          case "trusted" => Right(skunk.SSL.Trusted)
          case "system"  => Right(skunk.SSL.System)
          case other     => Left(s"Invalid ssl option $other")
        },
      )

  case Schemas
      extends Config[Set[String]](
        key = "-schemas",
        desc = "Comma-separated list of the schemas managed by Dumbo. "
          + "First schema will be used as default schema if set (default value is \"public\").",
        (v: String) => Right(v.split(",").map(_.trim()).filter(_.nonEmpty).toSet),
      )

  case Table
      extends Config[String](
        key = "-table",
        desc = s"The name of Dumbo's schema history table (default: ${defaults.schemaHistoryTable})",
        Right(_),
      )

  case Location
      extends Config[Path](
        key = "-location",
        desc = "Path to directory to scan for migrations",
        (v: String) => if v.isEmpty then Left("Missing location path") else Right(Path(v)),
      )

  case ValidateOnMigrate
      extends Config[Boolean](
        key = "-validateOnMigrate",
        desc = "Validate when running migrate",
        {
          case "true"  => Right(true)
          case "false" => Right(false)
          case other   => Left(s"Invalid value for validateOnMigrate: $other")
        },
      )

object Config:
  def helpMap(configs: List[Config[?]]) = configs.map(c => c.key -> c.desc).toMap
  val helpMapAll: Map[String, String]   = helpMap(Config.values.toList)

enum Flag(val keys: Set[String]):
  case Help extends Flag(Set("--help", "-h", "-?"))
