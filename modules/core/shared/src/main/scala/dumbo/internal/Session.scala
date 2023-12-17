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
import dumbo.internal.net.Protocol
import fs2.concurrent.Signal
import fs2.io.net.{Network, SocketGroup, SocketOption}
import fs2.{Pipe, Stream}
import natchez.Trace
import skunk.codec.all.bool
import skunk.data.{Identifier, *}
import skunk.net.SSLNegotiation
import skunk.net.protocol.{Describe, Parse}
import skunk.util.*
import skunk.{Session as SkunkSession, *}

// extension of skunk.Session to support any multi-query statements with discarded results
// this could be removed in the future if it can be made part of skunk
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

  def single[F[_]: Temporal: Trace: Network: Console](
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
    ).apply(Trace[F])

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
  ): Trace[F] => Resource[F, Session[F]] =
    Kleisli((_: Trace[F]) =>
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
    ).flatMap(f => Kleisli((T: Trace[F]) => f(T))).run

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
  ): Resource[F, Trace[F] => Resource[F, Session[F]]] = {

    def session(socketGroup: SocketGroup[F], sslOp: Option[SSLNegotiation.Options[F]], cache: Describe.Cache[F])(
      implicit T: Trace[F]
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
      pool  <- Pool.ofF((T: Trace[F]) => session(Network[F], sslOp, dc)(T), max)(Session.Recyclers.full)
    } yield pool
  }

  def fromSocketGroup[F[_]: Temporal: Trace: Console](
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
  ): Resource[F, Session[F]] =
    for {
      namer <- Resource.eval(Namer[F])
      proto <- Protocol.apply[F](
                 host,
                 port,
                 debug,
                 namer,
                 socketGroup,
                 socketOptions,
                 sslOptions,
                 describeCache,
                 parseCache,
                 readTimeout,
               )
      _    <- Resource.eval(proto.startup(user, database, password, parameters))
      sess <- Resource.make(SkunkSession.fromProtocol(proto, namer, strategy))(_ => proto.cleanup)
    } yield new Session[F] {
      override def execute_(statement: Statement[Void]): F[Unit]  = proto.execute_(statement)
      override def parameters: Signal[F, Map[String, String]]     = sess.parameters
      def parameter(key: String): Stream[F, String]               = sess.parameter(key)
      def transactionStatus: Signal[F, TransactionStatus]         = sess.transactionStatus
      def execute[A](query: Query[Void, A]): F[List[A]]           = sess.execute(query)
      def execute[A, B](query: Query[A, B])(args: A): F[List[B]]  = sess.execute(query)(args)
      def unique[A](query: Query[Void, A]): F[A]                  = sess.unique(query)
      def unique[A, B](query: Query[A, B])(args: A): F[B]         = sess.unique(query)(args)
      def option[A](query: Query[Void, A]): F[Option[A]]          = sess.option(query)
      def option[A, B](query: Query[A, B])(args: A): F[Option[B]] = sess.option(query)(args)
      def stream[A, B](command: Query[A, B])(args: A, chunkSize: Int): Stream[F, B] =
        sess.stream(command)(args, chunkSize)
      def cursor[A, B](query: Query[A, B])(args: A): Resource[F, Cursor[F, B]] = sess.cursor(query)(args)
      def execute(command: Command[Void]): F[Completion]                       = sess.execute(command)
      def execute[A](command: Command[A])(args: A): F[Completion]              = sess.execute(command)(args)
      def prepare[A, B](query: Query[A, B]): F[PreparedQuery[F, A, B]]         = sess.prepare(query)
      def prepare[A](command: Command[A]): F[PreparedCommand[F, A]]            = sess.prepare(command)
      def pipe[A](command: Command[A]): Pipe[F, A, Completion]                 = sess.pipe(command)
      def pipe[A, B](query: Query[A, B], chunkSize: Int): Pipe[F, A, B]        = sess.pipe(query, chunkSize)
      def channel(name: Identifier): Channel[F, String, String]                = sess.channel(name)
      def transaction[A]: Resource[F, Transaction[F]]                          = sess.transaction
      def transaction[A](
        isolationLevel: TransactionIsolationLevel,
        accessMode: TransactionAccessMode,
      ): Resource[F, Transaction[F]] = sess.transaction(isolationLevel, accessMode)
      def typer: Typer                     = sess.typer
      def describeCache: Describe.Cache[F] = sess.describeCache
      def parseCache: Parse.Cache[F]       = sess.parseCache
    }
}
