// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.quoted.*
import scala.jdk.CollectionConverters.*
import java.io.File
import java.nio.file.{Paths, Path as JPath}
import fs2.io.file.Path

opaque type SourceFilePath = String
object SourceFilePath:
  inline def fromResourcesDir(name: String): List[SourceFilePath] =
    ${ listResourcesImpl('name) }

  private def listResourcesImpl(x: Expr[String])(using Quotes): Expr[List[SourceFilePath]] =
    import quotes.reflect.report
    val location = x.valueOrAbort

    getClass().getClassLoader().getResources(location).asScala.toList match
      case head :: Nil =>
        val base = Paths.get(head.toURI())
        val resources =
          new File(base.toString()).list().map(fileName => fromPath(Path("/") / location / fileName)).toList
        Expr(resources)
      case Nil => report.errorAndAbort(s"resource ${location} was not found")
      case multiple =>
        report.errorAndAbort(s"found multiple resource locations for ${location} in:\n${multiple.mkString("\n")}")

  private def applyImpl(x: Expr[String])(using Quotes): Expr[SourceFilePath] =
    import quotes.reflect.report
    val location = x.valueOrAbort
    if getClass().getResourceAsStream(location) != null then x
    else report.errorAndAbort(s"resource ${location} was not found")

  inline def apply(name: String): SourceFilePath       = ${ applyImpl('name) }
  inline private def fromPath(p: Path): SourceFilePath = p.toString()

  extension (s: SourceFilePath)
    inline def value: String    = s
    inline def toNioPath: JPath = Paths.get(s)
