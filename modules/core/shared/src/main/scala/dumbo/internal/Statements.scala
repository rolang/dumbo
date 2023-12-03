// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import scala.annotation.tailrec

private[dumbo] object Statements {
  def intoSingleStatements(sql: String): Vector[String] = {
    @tailrec
    def build(stmts: Vector[String], stmt: String, sc: Option[(String, Int)], next: Seq[Char]): Vector[String] =
      (sc, next) match {
        // single quotes
        case (None, '\'' +: xs)           => build(stmts, stmt + '\'', Some(("'", 0)), xs)
        case (Some(("'", _)), '\'' +: xs) => build(stmts, stmt + '\'', None, xs)

        // double quotes
        case (None, '"' +: xs)            => build(stmts, stmt + '"', Some(("\"", 0)), xs)
        case (Some(("\"", _)), '"' +: xs) => build(stmts, stmt + '"', None, xs)

        // function body
        case (Some(("$$", _)), '$' +: '$' +: xs) => build(stmts, stmt + "$$", None, xs)
        case (None, '$' +: '$' +: xs)            => build(stmts, stmt + "$$", Some(("$$", 0)), xs)

        // comments: The line separator is taken as `"\n"`, `"\r"`, or `"\r\n"`
        case (Some(("--", _)), '\r' +: '\n' +: xs) => build(stmts, stmt + "\r\n", None, xs)
        case (Some(("--", _)), '\n' +: xs)         => build(stmts, stmt + "\n", None, xs)
        case (Some(("--", _)), '\r' +: xs)         => build(stmts, stmt + "\r", None, xs)
        case (None, '-' +: '-' +: xs)              => build(stmts, stmt + "--", Some(("--", 0)), xs)

        // multilne comments (including nested)
        case (Some(("/*", 0)), '*' +: '/' +: xs) => build(stmts, stmt + "*/", None, xs)
        case (Some(("/*", i)), '*' +: '/' +: xs) => build(stmts, stmt + "*/", Some(("/*", i - 1)), xs)
        case (Some(("/*", i)), '/' +: '*' +: xs) => build(stmts, stmt + "/*", Some(("/*", i + 1)), xs)
        case (None, '/' +: '*' +: xs)            => build(stmts, stmt + "/*", Some(("/*", 0)), xs)

        //
        case (None, ';' +: xs) => build(stmts :+ stmt, "", None, xs)
        case (s, c +: xs)      => build(stmts, stmt + c, s, xs)
        case _                 => stmts :+ stmt
      }

    build(Vector.empty[String], "", None, sql.toCharArray().toIndexedSeq)
  }
}
