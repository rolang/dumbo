lazy val `scala-2.12` = "2.12.18"
lazy val `scala-2.13` = "2.13.11"
lazy val `scala-3`    = "3.3.0"

ThisBuild / scalaVersion       := `scala-2.13`
ThisBuild / crossScalaVersions := Seq(`scala-2.12`, `scala-2.13`, `scala-3`)

ThisBuild / organization := "dev.rolang"
ThisBuild / licenses     := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(
    id = "rolang",
    name = "Roman Langolf",
    email = "rolang@pm.me",
    url = url("https://rolang.dev"),
  )
)

ThisBuild / scalafmt          := true
ThisBuild / scalafmtSbtCheck  := true
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbOptions ++= { if (scalaVersion.value == `scala-3`) Seq() else Seq("-P:semanticdb:synthetics:on") }
ThisBuild / semanticdbVersion          := scalafixSemanticdb.revision // use Scalafix compatible version
ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / scalafixDependencies ++= List(
  "com.github.liancheng" %% "organize-imports" % "0.5.0",
  "com.github.vovapolu"  %% "scaluzzi"         % "0.1.23",
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("fix", "; all scalafixAll; all scalafmtSbt scalafmtAll")
addCommandAlias("check", "; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check")

lazy val commonSettings = List(
  libraryDependencies ++= {
    if (scalaVersion.value == `scala-3`)
      Seq()
    else
      Seq(
        compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.2").cross(CrossVersion.full)),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      )
  },
  Compile / scalacOptions ++= {
    if (scalaVersion.value == `scala-3`)
      Seq("-source:future")
    else
      Seq("-Xsource:3")
  },
  version ~= (v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v),
)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

val releaseSettings = List(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
)

lazy val root =
  (project in file("."))
    .dependsOn(core.jvm, core.native)
    .aggregate(core.jvm, core.native)
    .settings(commonSettings)
    .settings(noPublishSettings)

lazy val skunkVersion = "0.6.0"
lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .settings(
    name := "dumbo",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "skunk-core" % skunkVersion
    ),
  )
  .settings(commonSettings)
  .settings(releaseSettings)
  .jvmSettings(
    javacOptions ++= Seq("-source", "17", "-target", "17"),
    Compile / scalacOptions ++= Seq("-release:17"),
  )

lazy val tests = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/tests"))
  .dependsOn(core)
  .settings(noPublishSettings)
  .settings(commonSettings)
  .settings(
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"             % "1.0.0-M7",
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0-M3",
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    testOptions += {
      if (System.getProperty("os.arch").startsWith("aarch64")) {
        Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=X86ArchOnly")
      } else Tests.Argument()
    },
  )
  .nativeSettings(
    libraryDependencies += "com.armanbilge" %%% "epollcat" % "0.1.5",
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1"),
  )

lazy val flywayVersion     = "9.20.0"
lazy val postgresqlVersion = "42.6.0"
lazy val testsFlyway = project
  .in(file("modules/tests-flyway"))
  .dependsOn(core.jvm, tests.jvm % "compile->compile;test->test")
  .settings(noPublishSettings)
  .settings(commonSettings)
  .settings(
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.flywaydb"   % "flyway-core" % flywayVersion,
      "org.postgresql" % "postgresql"  % postgresqlVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val example = project
  .in(file("modules/example"))
  .dependsOn(core.jvm)
  .settings(noPublishSettings)
  .settings(commonSettings)
  .settings(
    scalacOptions -= "-Xfatal-warnings"
  )
