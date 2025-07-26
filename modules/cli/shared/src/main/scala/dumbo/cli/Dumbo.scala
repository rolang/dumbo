// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.cli

import cats.data.Validated.{Invalid, Valid}
import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import dumbo.BuildInfo
import dumbo.Dumbo.defaults
import dumbo.exception.DumboValidationException
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import dumbo.logging.Implicits.consolePrettyWithTimestamp

object Dumbo extends IOApp {
  private def printHelp(cmd: Option[Command] = None) = {
    val tab = "    "

    def helpMapStr(m: Map[String, String]) = {
      val colSize = m.keySet.maxByOption(_.length()).map(_.length()).getOrElse(0) + 3

      m.map { case (k, v) => s"${k + Array.fill(colSize - k.length())(" ").mkString}$v" }
        .mkString(tab, s"\n$tab", "\n")
    }

    val usageExample = cmd match
      case None =>
        s"""|
            |Dumbo usage example
            |${tab}dumbo -user=postgres -password="my safe passw0rd" -url=postgresql://localhost:5432/postgres -location=/path/to/db/migration migrate
            |${tab}dumbo help migrate""".stripMargin
      case Some(_) => ""

    val commandsHelp = cmd match
      case None    => s"dumbo help [command]\nCommands\n${helpMapStr(Command.helpMap)}\n"
      case Some(_) => ""

    val configsHelp = {
      val title = "Configuration parameters (Format: -key=value)"

      cmd match
        case None                          => Some(s"\n$title\n${helpMapStr(Config.helpMapAll)}")
        case Some(c) if c.configs.nonEmpty => Some(s"\n$title\n${helpMapStr(Config.helpMap(c.configs))}")
        case _                             => None
    }

    val help =
      s"""|Usage
          |${tab}dumbo ${configsHelp.map(_ => "[options] ").getOrElse("")}${cmd
           .flatMap(_.keys.headOption)
           .getOrElse("[command]")}
          |$commandsHelp${configsHelp.getOrElse("")}$usageExample""".stripMargin

    Console[IO].println(help)
  }

  private[dumbo] def dumboFromConfigs(
    configs: List[(Config[?], String)]
  ): Either[String, (dumbo.Dumbo[IO], dumbo.ConnectionConfig)] = {
    def collectConfig[T](config: Config[T]): Option[Either[String, T]] =
      configs.collectFirst { case (c, v) if c == config => config.parse(v) }

    for {
      uri <- collectConfig(Config.Url).toRight("Missing url").flatten
      _   <- Option(uri.getScheme()) match
             case None               => Left(s"Missing scheme in $uri")
             case Some("postgresql") => Right(())
             case Some(invalid)      => Left(s"Unsupported scheme $invalid")
      host     <- Option(uri.getHost()).toRight(s"Missing or invalid hostname in $uri")
      port      = { val p = uri.getPort(); if (p > -1) p else defaults.port }
      database <- Option(uri.getPath()).flatMap(_.split("/").drop(1).headOption).toRight(s"Missing database in $uri")
      user     <- collectConfig(Config.User).toRight("Missing user").flatten
      password <- collectConfig(Config.Password) match
                    case None            => Right(None)
                    case Some(Left(err)) => Left(err)
                    case Some(Right(pw)) => Right(Some(pw))
      ssl      <- collectConfig(Config.Ssl).getOrElse(Right(dumbo.ConnectionConfig.SSL.None))
      location <- collectConfig(Config.Location).toRight("Missing location path").flatten
      schemas  <- collectConfig(Config.Schemas).getOrElse(Right(Set.empty))
      table    <- collectConfig(Config.Table) match
                 case None            => Right(None)
                 case Some(Left(err)) => Left(err)
                 case Some(Right(v))  => Right(Some(v))
      validateOnMigrate <- collectConfig(Config.ValidateOnMigrate) match
                             case None            => Right(None)
                             case Some(Left(err)) => Left(err)
                             case Some(Right(v))  => Right(Some(v))
      connection = dumbo.ConnectionConfig(
                     host = host,
                     port = port,
                     user = user,
                     database = database,
                     password = password,
                     ssl = ssl,
                   )
    } yield (
      dumbo.Dumbo
        .withFilesIn[IO](location)
        .apply(
          connection = connection,
          defaultSchema = schemas.headOption.getOrElse(defaults.defaultSchema),
          schemas = schemas,
          schemaHistoryTable = table.getOrElse(defaults.schemaHistoryTable),
          validateOnMigrate = validateOnMigrate.getOrElse(defaults.validateOnMigrate),
        ),
      connection,
    )

  }

  private def runMigration(options: List[(Config[?], String)]): IO[ExitCode] =
    dumboFromConfigs(options) match
      case Left(value)   => Console[IO].errorln(s"Invalid configuration: $value").as(ExitCode.Error)
      case Right((d, _)) => d.runMigration.as(ExitCode.Success)

  private def runValidation(options: List[(Config[?], String)]): IO[ExitCode] =
    dumboFromConfigs(options) match
      case Left(value)   => Console[IO].errorln(s"Invalid configuration: $value").as(ExitCode.Error)
      case Right((d, _)) =>
        d.runValidationWithHistory.flatMap {
          case Valid(_)   => Console[IO].println("Validation result: ok").as(ExitCode.Success)
          case Invalid(e) =>
            val errs = e.toNonEmptyList.toList.map(_.getMessage())
            Console[IO].errorln(s"Errors on validation: ${errs.mkString("\n", "\n", "")}").as(ExitCode.Success)
        }

  def run(args: List[String]): IO[ExitCode] =
    val argsResult = Arguments.parse(args)

    argsResult.unknown match
      case Nil =>
        argsResult.commands match
          case Nil                     => printHelp().as(ExitCode.Success)
          case Command.Help :: Nil     => printHelp().as(ExitCode.Success)
          case Command.Migrate :: Nil  => runMigration(argsResult.configs)
          case Command.Validate :: Nil => runValidation(argsResult.configs)
          case Command.Version :: Nil  =>
            Console[IO]
              .println(
                s"""|Dumbo
                    |Version: ${BuildInfo.version}
                    |Built using Scala ${BuildInfo.scalaVersion} and Scala Native ${BuildInfo.scalaNativeVersion}""".stripMargin
              )
              .as(ExitCode.Success)
          case Command.Help :: cmd :: Nil => printHelp(Some(cmd)).as(ExitCode.Success)
          case multiple                   =>
            Console[IO]
              .errorln(s"Multiple commands given: ${multiple.map(_.toString().toLowerCase()).mkString(", ")}")
              .as(ExitCode.Error)

      case unknowns => Console[IO].errorln(s"Invalid arguments: ${unknowns.mkString(", ")}").as(ExitCode.Error)
}
