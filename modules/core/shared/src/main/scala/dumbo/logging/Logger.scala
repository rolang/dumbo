// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.logging

import cats.effect.std.Console
import cats.implicits.*
import cats.{Applicative, Show}

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

  def fromConsole[F[_]](c: Console[F]) = new Logger[F] {
    override def apply(
      ll: LogLevel,
      message: => String,
    ): F[Unit] = c.println(s"[${ll.show}] $message")
  }
}

sealed trait LogLevel
object LogLevel {
  case object Info extends LogLevel
  case object Warn extends LogLevel

  implicit val show: Show[LogLevel] = new Show[LogLevel] {
    override def show(t: LogLevel): String = t match {
      case Info => "INFO"
      case Warn => "WARN"
    }
  }
}

object Implicits {
  implicit def console[F[_]: Console]: Logger[F] = Logger.fromConsole(Console[F])

  implicit def noop[F[_]: Applicative]: Logger[F] = Logger.noop[F]
}
