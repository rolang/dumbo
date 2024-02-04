// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal.net

import scala.concurrent.duration.Duration

import cats.effect.std.Console
import cats.effect.{Concurrent, Resource, Temporal}
import cats.syntax.all.*
import dumbo.internal.net.protocol.Query
import fs2.Stream
import fs2.concurrent.Signal
import fs2.io.net.Socket
import org.typelevel.otel4s.trace.Tracer
import skunk.data.*
import skunk.net.protocol.{Query as SkunkProtocolQuery, *}
import skunk.net.{Protocol as SkunkProtocol, *}
import skunk.util.{Namer, Typer}
import skunk.{Command, Query as SkunkQuery, RedactionStrategy, Statement, Void}

// extension of skunk.net.Protocol to support any multi-query statements with discarded results
// this could be removed in the future if it can be made part of skunk: https://github.com/typelevel/skunk/pull/1023
private[dumbo] trait Protocol[F[_]] extends SkunkProtocol[F] {
  def execute_(statement: Statement[Void]): F[Unit]
}

private[dumbo] object Protocol {

  def apply[F[_]: Temporal: Tracer: Console](
    debug: Boolean,
    nam: Namer[F],
    sockets: Resource[F, Socket[F]],
    sslOptions: Option[SSLNegotiation.Options[F]],
    describeCache: Describe.Cache[F],
    parseCache: Parse.Cache[F],
    readTimeout: Duration,
    redactionStrategy: RedactionStrategy,
  ): Resource[F, Protocol[F]] =
    for {
      bms <- BufferedMessageSocket[F](256, debug, sockets, sslOptions, readTimeout)
      p   <- Resource.eval(fromMessageSocket(bms, nam, describeCache, parseCache, redactionStrategy))
    } yield p

  def fromMessageSocket[F[_]: Concurrent: Tracer](
    bms: BufferedMessageSocket[F],
    nam: Namer[F],
    dc: Describe.Cache[F],
    pc: Parse.Cache[F],
    redactionStrategy: RedactionStrategy,
  ): F[Protocol[F]] =
    Exchange[F].map { ex =>
      new Protocol[F] {
        implicit val ms: MessageSocket[F]   = bms
        implicit val na: Namer[F]           = nam
        implicit val ExchangeF: Exchange[F] = ex

        override def notifications(maxQueued: Int): Resource[F, Stream[F, Notification[String]]] =
          bms.notifications(maxQueued)

        override def parameters: Signal[F, Map[String, String]] = bms.parameters

        override def prepare[A](command: Command[A], ty: Typer): F[SkunkProtocol.PreparedCommand[F, A]] =
          Prepare[F](describeCache, parseCache, redactionStrategy).apply(command, ty)

        override def prepare[A, B](query: SkunkQuery[A, B], ty: Typer): F[SkunkProtocol.PreparedQuery[F, A, B]] =
          Prepare[F](describeCache, parseCache, redactionStrategy).apply(query, ty)

        override def execute(command: Command[Void]): F[Completion] =
          SkunkProtocolQuery[F](redactionStrategy).apply(command)

        override def execute[B](query: SkunkQuery[Void, B], ty: Typer): F[List[B]] =
          SkunkProtocolQuery[F](redactionStrategy).apply(query, ty)
        override def execute_(statement: Statement[Void]): F[Unit] = Query[F].apply(statement)
        override def startup(
          user: String,
          database: String,
          password: Option[String],
          parameters: Map[String, String],
        ): F[Unit] = Startup[F].apply(user, database, password, parameters)

        override def cleanup: F[Unit] = parseCache.value.values.flatMap(xs => xs.traverse_(Close[F].apply))

        override def transactionStatus: Signal[F, TransactionStatus] = bms.transactionStatus

        override val describeCache: Describe.Cache[F] = dc

        override val parseCache: Parse.Cache[F] = pc
      }
    }

}
