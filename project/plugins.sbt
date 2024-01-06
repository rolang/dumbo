addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.16")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")

lazy val sbtTlVersion = "0.6.4"

addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % sbtTlVersion)

addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTlVersion)
