// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

// NOTE: Copied from skunk 0.6.0 to allow to run multiple-query statements as long as it is not supported: https://github.com/typelevel/skunk/issues/695
// Postgres docs: https://www.postgresql.org/docs/current/protocol-flow.html#id-1.10.6.7.4
package dumbo.internal.net.protocol

import cats.MonadError
import cats.syntax.all.*
import natchez.Trace
import skunk.exception.*
import skunk.net.MessageSocket
import skunk.net.message.{Query as QueryMessage, *}
import skunk.net.protocol.{Query as _, *}
import skunk.{Statement, Void}

// new Query type to support any multi-query statements with discarded results
// this could be removed in the future if it can be made part of skunk
private[dumbo] trait Query[F[_]] {
  def apply(statement: Statement[Void]): F[Unit]
}

private[dumbo] object Query {

  def apply[F[_]: Exchange: MessageSocket: Trace](implicit ev: MonadError[F, Throwable]): Query[F] =
    new Query[F] {

      def finishCopyOut: F[Unit] = receive.iterateUntil {
        case CommandComplete(_) => true
        case _                  => false
      }.void

      // Finish up any single or multi-query statement, discard returned completions and/or rows
      // Fail with first encountered error
      def finishUpDiscard(stmt: Statement[?], error: Option[SkunkException]): F[Unit] =
        flatExpect {
          case ReadyForQuery(_) =>
            error match {
              case None    => ().pure[F]
              case Some(e) => e.raiseError[F, Unit]
            }

          case RowDescription(_) | RowData(_) | CommandComplete(_) | EmptyQueryResponse =>
            finishUpDiscard(stmt, error)

          case NoticeResponse(info) =>
            error match {
              case None =>
                for {
                  hi <- history(Int.MaxValue)
                  err = new PostgresErrorException(stmt.sql, Some(stmt.origin), info, hi)
                  c  <- finishUpDiscard(stmt, Some(err))
                } yield c
              case _ => finishUpDiscard(stmt, error)
            }

          case ErrorResponse(info) =>
            error match {
              case None =>
                for {
                  hi <- history(Int.MaxValue)
                  err = new PostgresErrorException(stmt.sql, Some(stmt.origin), info, hi)
                  c  <- finishUpDiscard(stmt, Some(err))
                } yield c
              case _ => finishUpDiscard(stmt, error)
            }

          // We don't support COPY FROM STDIN yet but we do need to be able to clean up if a user
          // tries it.
          case CopyInResponse(_) =>
            send(CopyFail) *>
              expect { case ErrorResponse(_) => } *>
              finishUpDiscard(stmt, error.orElse(new CopyNotSupportedException(stmt).some))

          case CopyOutResponse(_) =>
            finishCopyOut *> finishUpDiscard(stmt, error.orElse(new CopyNotSupportedException(stmt).some))
        }

      override def apply(command: Statement[Void]): F[Unit] = exchange("query") {
        Trace[F].put("command.sql" -> command.sql) *> send(QueryMessage(command.sql)) *> finishUpDiscard(command, None)
      }

    }

}
