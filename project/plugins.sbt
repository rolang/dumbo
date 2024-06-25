addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.4")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")

lazy val sbtTlVersion = "0.7.1"

addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % sbtTlVersion)

addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTlVersion)

addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew" % "0.3.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
