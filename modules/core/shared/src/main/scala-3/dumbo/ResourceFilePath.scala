// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import scala.quoted.*
import scala.jdk.CollectionConverters.*
import java.io.File
import java.nio.file.{Paths, Path as JPath}
import fs2.io.file.Path

opaque type ResourceFilePath = String
object ResourceFilePath:
  inline def fromResourcesDir(name: String): List[ResourceFilePath] =
    ${ listResourcesImpl('name) }

  private def listResourcesImpl(x: Expr[String])(using Quotes): Expr[List[ResourceFilePath]] =
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

  private def applyImpl(x: Expr[String])(using Quotes): Expr[ResourceFilePath] =
    import quotes.reflect.report
    val location = x.valueOrAbort
    if getClass().getResourceAsStream(location) != null then x
    else report.errorAndAbort(s"resource ${location} was not found")

  inline def apply(name: String): ResourceFilePath       = ${ applyImpl('name) }
  inline private def fromPath(p: Path): ResourceFilePath = p.toString()

  extension (s: ResourceFilePath)
    inline def value: String    = s
    inline def toNioPath: JPath = Paths.get(s)
