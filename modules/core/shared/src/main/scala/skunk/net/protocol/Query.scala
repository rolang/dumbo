// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

// NOTE: Copied from skunk 0.6.0 to allow to run multiple-query statements as long as it is not supported: https://github.com/typelevel/skunk/issues/695
// Postgres docs: https://www.postgresql.org/docs/current/protocol-flow.html#id-1.10.6.7.4
package skunk.net.protocol

import cats.MonadError
import cats.syntax.all.*
import natchez.Trace
import skunk.data.Completion
import skunk.exception.{EmptyStatementException, UnknownOidException, *}
import skunk.net.MessageSocket
import skunk.net.message.{Query as QueryMessage, *}
import skunk.util.Typer
import skunk.{Command, Statement, Void}

trait Query[F[_]] {
  def apply(command: Command[Void]): F[Completion]
  def apply[B](query: skunk.Query[Void, B], ty: Typer): F[List[B]]
}

object Query {

  def apply[F[_]: Exchange: MessageSocket: Trace](implicit
    ev: MonadError[F, Throwable]
  ): Query[F] =
    new Unroll[F] with Query[F] {

      def finishCopyOut: F[Unit] =
        receive.iterateUntil {
          case CommandComplete(_) => true
          case _                  => false
        }.void

      def finishUp(stmt: Statement[?], messages: List[BackendMessage] = Nil): F[List[BackendMessage]] =
        flatExpect {

          case ReadyForQuery(_) => messages.pure[F]
          // new SkunkException(
          //   message = "Multi-statement queries and commands are not supported.",
          //   hint = Some("Break up semicolon-separated statements and execute them independently."),
          //   sql = Some(stmt.sql),
          //   sqlOrigin = Some(stmt.origin),
          // ).raiseError[F, Unit].whenA(multipleStatements)

          case r @ (RowDescription(_) | RowData(_) | CommandComplete(_) | ErrorResponse(_) | EmptyQueryResponse) =>
            finishUp(stmt, r :: messages)

          case r @ CopyInResponse(_) =>
            send(CopyFail) *>
              expect { case ErrorResponse(_) => } *>
              finishUp(stmt, r :: messages)

          case r @ CopyOutResponse(_) =>
            finishCopyOut *>
              finishUp(stmt, r :: messages)
        }

      // If there is an error we just want to receive and discard everything until we have seen
      // CommandComplete followed by ReadyForQuery.
      def discard(stmt: Statement[?]): F[Unit] =
        flatExpect {
          case RowData(_)         => discard(stmt)
          case CommandComplete(_) => finishUp(stmt).void
        }

      override def apply[B](query: skunk.Query[Void, B], ty: Typer): F[List[B]] =
        exchange("query") {
          Trace[F].put(
            "query.sql" -> query.sql
          ) *> send(QueryMessage(query.sql)) *> flatExpect {

            // If we get a RowDescription back it means we have a valid query as far as Postgres is
            // concerned, and we will soon receive zero or more RowData followed by CommandComplete.
            case rd @ RowDescription(_) =>
              // If our decoder lines up with the RowDescription we can decode the rows, otherwise
              // we have to discard them and then raise an error. All the args are necessary context
              // if we have a decoding failure and need to report an error.
              rd.typed(ty) match {

                case Right(td) =>
                  if (query.isDynamic || query.decoder.types === td.types) {
                    unroll(
                      extended = false,
                      sql = query.sql,
                      sqlOrigin = query.origin,
                      args = Void,
                      argsOrigin = None,
                      encoder = Void.codec,
                      rowDescription = td,
                      decoder = query.decoder,
                    ).map(_._1) <* finishUp(query)
                  } else {
                    discard(query) *> ColumnAlignmentException(query, td).raiseError[F, List[B]]
                  }

                case Left(err) =>
                  discard(query) *> UnknownOidException(query, err, ty.strategy).raiseError[F, List[B]]

              }

            // We don't support COPY FROM STDIN yet but we do need to be able to clean up if a user
            // tries it.
            case CopyInResponse(_) =>
              send(CopyFail) *>
                expect { case ErrorResponse(_) => } *>
                finishUp(query) *>
                new CopyNotSupportedException(query).raiseError[F, List[B]]

            case CopyOutResponse(_) =>
              finishCopyOut *>
                finishUp(query) *>
                new CopyNotSupportedException(query).raiseError[F, List[B]]

            // Query is empty, whitespace, all comments, etc.
            case EmptyQueryResponse =>
              finishUp(query) *> new EmptyStatementException(query).raiseError[F, List[B]]

            // If we get CommandComplete it means our Query was actually a Command. Postgres doesn't
            // distinguish these but we do, so this is an error.
            case CommandComplete(_) =>
              finishUp(query) *> NoDataException(query).raiseError[F, List[B]]

            // If we get an ErrorResponse it means there was an error in the query. In this case we
            // simply await ReadyForQuery and then raise an error.
            case ErrorResponse(e) =>
              for {
                hi <- history(Int.MaxValue)
                _  <- finishUp(query)
                rs <- (new PostgresErrorException(
                        sql = query.sql,
                        sqlOrigin = Some(query.origin),
                        info = e,
                        history = hi,
                      )).raiseError[F, List[B]]
              } yield rs

            // We can get a warning if this was actually a command and something wasn't quite
            // right. In this case we'll report the first error because it's probably more
            // informative.
            case NoticeResponse(e) =>
              for {
                hi <- history(Int.MaxValue)
                _  <- expect { case CommandComplete(_) => }
                _  <- finishUp(query)
                rs <- (new PostgresErrorException(
                        sql = query.sql,
                        sqlOrigin = Some(query.origin),
                        info = e,
                        history = hi,
                      )).raiseError[F, List[B]]
              } yield rs

          }
        }

      override def apply(command: Command[Void]): F[Completion] =
        exchange("query") {
          Trace[F].put(
            "command.sql" -> command.sql
          ) *> send(QueryMessage(command.sql)) *> flatExpect {

            case CommandComplete(c) =>
              finishUp(command)
                .map(m =>
                  (
                    m.collect { case err @ ErrorResponse(_) => err },
                    m.collect { case CommandComplete(cc) => cc },
                  )
                )
                .flatMap {
                  case (Nil, Nil) => c.pure[F]
                  case (Nil, cs)  => (Completion.Multiple(c :: cs): Completion).pure[F]
                  case (errors, _) =>
                    history(Int.MaxValue).flatMap { h =>
                      new PostgresErrorException(
                        command.sql,
                        Some(command.origin),
                        errors.flatMap(_.info).toMap,
                        h,
                        Nil,
                        None,
                      ).raiseError[F, Completion]
                    }
                }

            case EmptyQueryResponse =>
              // we don't care about empty query results
              // need to find a better way...
              finishUp(command).as(Completion.Multiple(Nil))

            case ErrorResponse(e) =>
              for {
                _ <- finishUp(command)
                h <- history(Int.MaxValue)
                c <- new PostgresErrorException(command.sql, Some(command.origin), e, h, Nil, None)
                       .raiseError[F, Completion]
              } yield c

            case NoticeResponse(e) =>
              for {
                _ <- expect { case CommandComplete(_) => }
                _ <- finishUp(command)
                h <- history(Int.MaxValue)
                c <- new PostgresErrorException(command.sql, Some(command.origin), e, h, Nil, None)
                       .raiseError[F, Completion]
              } yield c

            // we want to allow to run queries as Flyway does (for whatever reasons) and going to discard instead of failing
            // need to find a better way...
            case RowDescription(_) => discard(command).as(Completion.Multiple(Nil))

            // We don't support COPY FROM STDIN yet but we do need to be able to clean up if a user
            // tries it.
            case CopyInResponse(_) =>
              send(CopyFail) *>
                expect { case ErrorResponse(_) => } *>
                finishUp(command) *>
                new CopyNotSupportedException(command).raiseError[F, Completion]

            case CopyOutResponse(_) =>
              finishCopyOut *>
                finishUp(command) *>
                new CopyNotSupportedException(command).raiseError[F, Completion]

          }

        }

    }

}
