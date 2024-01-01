// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.cli

import cats.effect.IO
import cats.effect.ExitCode
import natchez.Trace.Implicits.noop
import skunk.SSL
import cats.effect.std.Console
import dumbo.exception.DumboValidationException
import cats.data.Validated.Valid
import cats.data.Validated.Invalid

object Dumbo extends PlatformApp {
  private def printHelp(cmd: Option[Command] = None) = {
    val tab = "    "

    def helpMap(m: Map[String, String]) = {
      val colSize = m.keySet.maxBy(_.length()).length() + 3

      m.map { case (k, v) => s"${k + Array.fill(colSize - k.length())(" ").mkString}$v" }
        .mkString(tab, s"\n$tab", "\n")
    }

    val usageExample = cmd match
      case None =>
        s"""|
            |Dumbo usage example
            |${tab}dumbo -user=postgres -password=postgres -url=postgresql://localhost:5432/postgres -location=/path/to/db/migration migrate
            |${tab}dumbo help migrate""".stripMargin
      case Some(_) => ""

    val commandsHelp = cmd match
      case None    => s"dumbo help [command]\nCommands\n${helpMap(Command.helpMap)}\n"
      case Some(_) => ""

    val help =
      s"""
         |Usage
         |${tab}dumbo [options] ${cmd.flatMap(_.keys.headOption).getOrElse("[command]")}
         |$commandsHelp
         |Configuration parameters (Format: -key=value)
         |
         |${helpMap(cmd.map(c => Config.helpMap(c.configs)).getOrElse(Config.helpMapAll))}
         |$usageExample
    """.stripMargin

    IO.println(help)
  }

  private def dumboFromConfigs(configs: List[(Config[?], String)]): Either[String, dumbo.Dumbo[IO]] = {
    def collectConfig[T](config: Config[T]): Option[Either[String, T]] =
      configs.collectFirst { case (c, v) if c == config => config.parse(v) }

    for {
      uri      <- collectConfig(Config.Url).toRight("Missing url").flatten
      database <- uri.getPath().split("/").drop(1).headOption.toRight(s"Missing database in $uri")
      host      = uri.getHost()
      port      = Option(uri.getPort()).getOrElse(5432)
      user     <- collectConfig(Config.User).toRight("Missing user").flatten
      password <- collectConfig(Config.Password) match
                    case None            => Right(None)
                    case Some(Left(err)) => Left(err)
                    case Some(Right(pw)) => Right(Some(pw))
      ssl      <- collectConfig(Config.Ssl).getOrElse(Right(SSL.None))
      location <- collectConfig(Config.Location).toRight("Missing location path").flatten
      schemas  <- collectConfig(Config.Schemas).getOrElse(Right(Set.empty))
      table <- collectConfig(Config.Table) match
                 case None            => Right(None)
                 case Some(Left(err)) => Left(err)
                 case Some(Right(v))  => Right(Some(v))
      validateOnMigrate <- collectConfig(Config.ValidateOnMigrate) match
                             case None            => Right(None)
                             case Some(Left(err)) => Left(err)
                             case Some(Right(v))  => Right(Some(v))
    } yield dumbo.Dumbo
      .withFilesIn[IO](location)
      .apply(
        connection = dumbo.ConnectionConfig(
          host = host,
          port = port,
          user = user,
          database = database,
          password = password,
          ssl = skunk.SSL.None,
        ),
        defaultSchema = schemas.headOption.getOrElse("public"),
        schemas = schemas,
        schemaHistoryTable = table.getOrElse("flyway_schema_history"),
        validateOnMigrate = validateOnMigrate.getOrElse(true),
      )

  }

  private def runMigration(options: List[(Config[?], String)]): IO[Unit] =
    dumboFromConfigs(options) match
      case Left(value) => Console[IO].errorln(s"Invalid configuration: $value")
      case Right(d)    => d.runMigration.void

  private def runValidation(options: List[(Config[?], String)]): IO[Unit] =
    dumboFromConfigs(options) match
      case Left(value) => Console[IO].errorln(s"Invalid configuration: $value")
      case Right(d) =>
        d.runValidationWithHistory.flatMap {
          case Valid(_) => Console[IO].println("Validation result: ok")
          case Invalid(e) =>
            val errs = e.toNonEmptyList.toList.map(_.getMessage())
            Console[IO].errorln(s"Errors on validation: ${errs.mkString("\n", "\n", "")}")
        }

  def run(args: List[String]): IO[ExitCode] =
    val argsResult = Arguments.parse(args)

    argsResult.unknown match
      case Nil =>
        argsResult.commands match
          case Nil                        => printHelp().as(ExitCode.Success)
          case Command.Help :: Nil        => printHelp().as(ExitCode.Success)
          case Command.Migrate :: Nil     => runMigration(argsResult.configs).void.as(ExitCode.Success)
          case Command.Validate :: Nil    => runValidation(argsResult.configs).void.as(ExitCode.Success)
          case Command.Version :: Nil     => IO.println(s"Dumbo version ${dumbo.version.value}").as(ExitCode.Success)
          case Command.Help :: cmd :: Nil => printHelp(Some(cmd)).as(ExitCode.Success)
          case multiple =>
            Console[IO].errorln(s"Multiple commands given: ${multiple.mkString(", ")}").as(ExitCode.Error)

      case unknowns => Console[IO].errorln(s"Invalid arguments: ${unknowns.mkString(", ")}").as(ExitCode.Error)
}
