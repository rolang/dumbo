addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.8")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")

lazy val sbtTlVersion = "0.7.7"

addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % sbtTlVersion)

addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTlVersion)

addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew" % "0.3.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
