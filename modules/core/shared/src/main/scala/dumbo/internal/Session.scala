// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import scala.concurrent.duration.Duration

import cats.*
import cats.data.Kleisli
import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import com.comcast.ip4s.{Hostname, Port, SocketAddress}
import dumbo.internal.net.Protocol
import fs2.Stream
import fs2.concurrent.Signal
import fs2.io.net.{Network, Socket, SocketGroup, SocketOption}
import org.typelevel.otel4s.trace.Tracer
import skunk.codec.all.bool
import skunk.data.{Identifier, *}
import skunk.net.SSLNegotiation
import skunk.net.protocol.{Describe, Parse}
import skunk.util.*
import skunk.util.Typer.Strategy.{BuiltinsOnly, SearchPath}
import skunk.{Session as SkunkSession, *}

// extension of skunk.Session to support any multi-query statements with discarded results
// this could be removed in the future if it can be made part of skunk: https://github.com/typelevel/skunk/pull/1023
private[dumbo] trait Session[F[_]] extends SkunkSession[F] {
  def execute_(statement: Statement[skunk.Void]): F[Unit]
}

private[dumbo] object Session {

  object Recyclers {
    def full[F[_]: Monad]: Recycler[F, Session[F]] =
      ensureIdle[F] <+> unlistenAll <+> resetAll

    def minimal[F[_]: Monad]: Recycler[F, Session[F]] =
      ensureIdle[F] <+> Recycler(_.unique(Query("VALUES (true)", Origin.unknown, Void.codec, bool)))

    def ensureIdle[F[_]: Monad]: Recycler[F, Session[F]] =
      Recycler(_.transactionStatus.get.map(_ == TransactionStatus.Idle))

    def unlistenAll[F[_]: Functor]: Recycler[F, Session[F]] =
      Recycler(_.execute(Command("UNLISTEN *", Origin.unknown, Void.codec)).as(true))

    def resetAll[F[_]: Functor]: Recycler[F, Session[F]] =
      Recycler(_.execute(Command("RESET ALL", Origin.unknown, Void.codec)).as(true))
  }

  def single[F[_]: Temporal: Tracer: Network: Console](
    host: String,
    port: Int = 5432,
    user: String,
    database: String,
    password: Option[String] = none,
    debug: Boolean = false,
    strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    ssl: SSL = SSL.None,
    parameters: Map[String, String] = SkunkSession.DefaultConnectionParameters,
    commandCache: Int = 1024,
    queryCache: Int = 1024,
    parseCache: Int = 1024,
    readTimeout: Duration = Duration.Inf,
  ): Resource[F, Session[F]] =
    singleF[F](
      host,
      port,
      user,
      database,
      password,
      debug,
      strategy,
      ssl,
      parameters,
      commandCache,
      queryCache,
      parseCache,
      readTimeout,
    ).apply(Tracer[F])

  def singleF[F[_]: Temporal: Network: Console](
    host: String,
    port: Int = 5432,
    user: String,
    database: String,
    password: Option[String] = none,
    debug: Boolean = false,
    strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    ssl: SSL = SSL.None,
    parameters: Map[String, String] = SkunkSession.DefaultConnectionParameters,
    commandCache: Int = 1024,
    queryCache: Int = 1024,
    parseCache: Int = 1024,
    readTimeout: Duration = Duration.Inf,
  ): Tracer[F] => Resource[F, Session[F]] =
    Kleisli((_: Tracer[F]) =>
      pooledF(
        host = host,
        port = port,
        user = user,
        database = database,
        password = password,
        max = 1,
        debug = debug,
        strategy = strategy,
        ssl = ssl,
        parameters = parameters,
        commandCache = commandCache,
        queryCache = queryCache,
        parseCache = parseCache,
        readTimeout = readTimeout,
      )
    ).flatMap(f => Kleisli((T: Tracer[F]) => f(T))).run

  def pooledF[F[_]: Temporal: Network: Console](
    host: String,
    port: Int = 5432,
    user: String,
    database: String,
    password: Option[String] = none,
    max: Int,
    debug: Boolean = false,
    strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    ssl: SSL = SSL.None,
    parameters: Map[String, String] = SkunkSession.DefaultConnectionParameters,
    socketOptions: List[SocketOption] = SkunkSession.DefaultSocketOptions,
    commandCache: Int = 1024,
    queryCache: Int = 1024,
    parseCache: Int = 1024,
    readTimeout: Duration = Duration.Inf,
  ): Resource[F, Tracer[F] => Resource[F, Session[F]]] = {

    def session(socketGroup: SocketGroup[F], sslOp: Option[SSLNegotiation.Options[F]], cache: Describe.Cache[F])(
      implicit T: Tracer[F]
    ): Resource[F, Session[F]] =
      for {
        pc <- Resource.eval(Parse.Cache.empty[F](parseCache))
        s <- fromSocketGroup[F](
               socketGroup,
               host,
               port,
               user,
               database,
               password,
               debug,
               strategy,
               socketOptions,
               sslOp,
               parameters,
               cache,
               pc,
               readTimeout,
             )
      } yield s

    val logger: String => F[Unit] = s => Console[F].println(s"TLS: $s")

    for {
      dc    <- Resource.eval(Describe.Cache.empty[F](commandCache, queryCache))
      sslOp <- ssl.toSSLNegotiationOptions(if (debug) logger.some else none)
      pool  <- Pool.ofF((T: Tracer[F]) => session(Network[F], sslOp, dc)(T), max)(Session.Recyclers.full)
    } yield pool
  }

  def fromSocketGroup[F[_]: Tracer: Console](
    socketGroup: SocketGroup[F],
    host: String,
    port: Int = 5432,
    user: String,
    database: String,
    password: Option[String] = none,
    debug: Boolean = false,
    strategy: Typer.Strategy = Typer.Strategy.BuiltinsOnly,
    socketOptions: List[SocketOption],
    sslOptions: Option[SSLNegotiation.Options[F]],
    parameters: Map[String, String],
    describeCache: Describe.Cache[F],
    parseCache: Parse.Cache[F],
    readTimeout: Duration = Duration.Inf,
    redactionStrategy: RedactionStrategy = RedactionStrategy.OptIn,
  )(implicit ev: Temporal[F]): Resource[F, Session[F]] = {
    def fail[A](msg: String): Resource[F, A] =
      Resource.eval(ev.raiseError(new Exception(msg)))

    def sock: Resource[F, Socket[F]] =
      (Hostname.fromString(host), Port.fromInt(port)) match {
        case (Some(validHost), Some(validPort)) =>
          socketGroup.client(SocketAddress(validHost, validPort), socketOptions)
        case (None, _) => fail(s"""Hostname: "$host" is not syntactically valid.""")
        case (_, None) => fail(s"Port: $port falls out of the allowed range.")
      }

    for {
      namer <- Resource.eval(Namer[F])
      proto <- Protocol[F](debug, namer, sock, sslOptions, describeCache, parseCache, readTimeout, redactionStrategy)
      _     <- Resource.eval(proto.startup(user, database, password, parameters))
      sess  <- Resource.make(fromProtocol(proto, namer, strategy, redactionStrategy))(_ => proto.cleanup)
    } yield sess
  }

  def fromProtocol[F[_]](
    proto: Protocol[F],
    namer: Namer[F],
    strategy: Typer.Strategy,
    redactionStrategy: RedactionStrategy,
  )(implicit ev: MonadCancel[F, Throwable]): F[Session[F]] = {
    val ft: F[Typer] =
      strategy match {
        case BuiltinsOnly => Typer.Static.pure[F]
        case SearchPath   => Typer.fromProtocol(proto)
      }

    ft.map { typ =>
      new SkunkSession.Impl[F] with Session[F] {
        override val typer: Typer                                          = typ
        override def execute_(statement: Statement[Void]): F[Unit]         = proto.execute_(statement)
        override def execute(command: Command[Void]): F[Completion]        = proto.execute(command)
        override def channel(name: Identifier): Channel[F, String, String] = Channel.fromNameAndProtocol(name, proto)
        override def parameters: Signal[F, Map[String, String]]            = proto.parameters
        override def parameter(key: String): Stream[F, String]             = parameters.discrete.map(_.get(key)).unNone.changes
        override def transactionStatus: Signal[F, TransactionStatus]       = proto.transactionStatus
        override def execute[A](query: Query[Void, A]): F[List[A]]         = proto.execute(query, typer)
        override def unique[A](query: Query[Void, A]): F[A] =
          execute(query).flatMap {
            case a :: Nil => a.pure[F]
            case Nil      => ev.raiseError(new RuntimeException("Expected exactly one row, none returned."))
            case _        => ev.raiseError(new RuntimeException("Expected exactly one row, more returned."))
          }
        override def option[A](query: Query[Void, A]): F[Option[A]] =
          execute(query).flatMap {
            case a :: Nil => a.some.pure[F]
            case Nil      => none[A].pure[F]
            case _        => ev.raiseError(new RuntimeException("Expected at most one row, more returned."))
          }
        override def prepare[A, B](query: Query[A, B]): F[PreparedQuery[F, A, B]] =
          proto.prepare(query, typer).map(PreparedQuery.fromProto(_, redactionStrategy))
        override def prepare[A](command: Command[A]): F[PreparedCommand[F, A]] =
          proto.prepare(command, typer).map(PreparedCommand.fromProto(_))
        override def transaction[A]: Resource[F, Transaction[F]] = Transaction.fromSession(this, namer, none, none)
        override def transaction[A](
          isolationLevel: TransactionIsolationLevel,
          accessMode: TransactionAccessMode,
        ): Resource[F, Transaction[F]] = Transaction.fromSession(this, namer, isolationLevel.some, accessMode.some)
        override def describeCache: Describe.Cache[F] = proto.describeCache
        override def parseCache: Parse.Cache[F]       = proto.parseCache
      }
    }
  }
}
