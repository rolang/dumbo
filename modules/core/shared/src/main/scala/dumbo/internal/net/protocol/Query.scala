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

      def finishUp(stmt: Statement[?]): F[Unit] =
        flatExpect {
          case ReadyForQuery(_) => ().pure[F]

          case RowDescription(_) | RowData(_) | CommandComplete(_) | ErrorResponse(_) | EmptyQueryResponse =>
            finishUp(stmt)

          case CopyInResponse(_) =>
            send(CopyFail) *>
              expect { case ErrorResponse(_) => } *>
              finishUp(stmt)

          case CopyOutResponse(_) =>
            finishCopyOut *> finishUp(stmt)
        }

      def discard(stmt: Statement[?]): F[Unit] = flatExpect {
        case RowData(_)         => discard(stmt)
        case CommandComplete(_) => finishUp(stmt).void
      }

      override def apply(command: Statement[Void]): F[Unit] = exchange("query") {
        Trace[F].put("command.sql" -> command.sql) *> send(QueryMessage(command.sql)) *> flatExpect {

          case CommandComplete(_) => finishUp(command).void

          case EmptyQueryResponse => finishUp(command).void

          case ErrorResponse(e) =>
            for {
              _ <- finishUp(command)
              h <- history(Int.MaxValue)
              c <-
                new PostgresErrorException(command.sql, Some(command.origin), e, h, Nil, None).raiseError[F, Unit]
            } yield c

          case NoticeResponse(e) =>
            for {
              _ <- expect { case CommandComplete(_) => }
              _ <- finishUp(command)
              h <- history(Int.MaxValue)
              _ <- new PostgresErrorException(command.sql, Some(command.origin), e, h, Nil, None).raiseError[F, Unit]
            } yield ()

          // we want to allow to run queries as Flyway does (for whatever reasons) and going to discard
          case RowDescription(_) => discard(command)

          // We don't support COPY FROM STDIN yet but we do need to be able to clean up if a user
          // tries it.
          case CopyInResponse(_) =>
            send(CopyFail) *>
              expect { case ErrorResponse(_) => } *>
              finishUp(command) *>
              new CopyNotSupportedException(command).raiseError[F, Unit]

          case CopyOutResponse(_) =>
            finishCopyOut *>
              finishUp(command) *>
              new CopyNotSupportedException(command).raiseError[F, Unit]
        }
      }

    }

}
