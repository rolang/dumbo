// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.logging

import java.time.Instant

import cats.effect.kernel.Clock
import cats.effect.std.Console as CatsConsole
import cats.implicits.*
import cats.{Applicative, FlatMap, Show}
import dumbo.logging.LogLevel.{Info, Warn}

trait Logger[F[_]] {
  def apply(level: LogLevel, message: => String): F[Unit]

  final def logInfo(message: => String) = apply(LogLevel.Info, message = message)

  final def logWarn(message: => String) = apply(LogLevel.Warn, message = message)
}

object Logger {
  def apply[F[_]](implicit L: Logger[F]): Logger[F] = L

  def noop[F[_]: Applicative] = new Logger[F] {
    def apply(level: LogLevel, message: => String) = Applicative[F].unit
  }

  private def consolePrintln[F[_]](
    console: CatsConsole[F],
    timestamp: Option[Instant],
    message: String,
    level: LogLevel,
    pretty: Boolean,
  ): F[Unit] = {
    val formattedMsg = if (pretty) {
      val lc = level match {
        case Info => Console.CYAN
        case Warn => Console.YELLOW
      }

      val mc = level match {
        case Info => Console.CYAN
        case Warn => Console.YELLOW
      }

      val tsStr = timestamp match {
        case None     => ""
        case Some(ts) => s"${Console.BLUE}${ts.toString()}${Console.RESET} "
      }

      s"$tsStr$lc${level.show}${Console.RESET} $mc$message${Console.RESET}"
    } else {
      val tsStr = timestamp match {
        case None     => ""
        case Some(ts) => s"${ts.toString()} "
      }

      s"$tsStr${level.show} $message"
    }

    console.println(formattedMsg)
  }

  def fromConsole[F[_]](
    console: CatsConsole[F],
    pretty: Boolean = true,
  ) = new Logger[F] {

    override def apply(
      ll: LogLevel,
      message: => String,
    ): F[Unit] = consolePrintln(
      console = console,
      timestamp = None,
      message = message,
      level = ll,
      pretty = pretty,
    )
  }

  def fromConsoleWithTimestamp[F[_]: Clock: FlatMap](
    console: CatsConsole[F],
    pretty: Boolean = true,
  ) = new Logger[F] {
    override def apply(
      ll: LogLevel,
      message: => String,
    ): F[Unit] =
      Clock[F].realTimeInstant.flatMap { ts =>
        consolePrintln(
          console = console,
          timestamp = Some(ts),
          message = message,
          level = ll,
          pretty = pretty,
        )
      }
  }
}

sealed trait LogLevel
object LogLevel {
  case object Info extends LogLevel
  case object Warn extends LogLevel

  implicit val show: Show[LogLevel] = new Show[LogLevel] {
    override def show(t: LogLevel): String = t match {
      case Info => "info"
      case Warn => "warn"
    }
  }
}

object Implicits {
  implicit def console[F[_]: CatsConsole]: Logger[F] =
    Logger.fromConsole(console = CatsConsole[F], pretty = false)

  implicit def consolePretty[F[_]: CatsConsole]: Logger[F] =
    Logger.fromConsole(console = CatsConsole[F], pretty = true)

  implicit def consoleWithTimestamp[F[_]: CatsConsole: Clock: FlatMap]: Logger[F] =
    Logger.fromConsoleWithTimestamp(console = CatsConsole[F], pretty = false)

  implicit def consolePrettyWithTimestamp[F[_]: CatsConsole: Clock: FlatMap]: Logger[F] =
    Logger.fromConsoleWithTimestamp(console = CatsConsole[F], pretty = true)

  implicit def noop[F[_]: Applicative]: Logger[F] = Logger.noop[F]
}
