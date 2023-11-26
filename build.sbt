lazy val `scala-2.12` = "2.12.18"
lazy val `scala-2.13` = "2.13.12"
lazy val `scala-3`    = "3.3.1"

ThisBuild / tlBaseVersion      := "0.0"
ThisBuild / startYear          := Some(2023)
ThisBuild / scalaVersion       := `scala-3`
ThisBuild / crossScalaVersions := Seq(`scala-3`, `scala-2.13`, `scala-2.12`)

ThisBuild / organization := "dev.rolang"
ThisBuild / licenses     := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://rolang.dev"))
)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / description   := "Simple database migration tool for Scala + Postgres"
ThisBuild / homepage      := Some(url("https://github.com/rolang/dumbo"))

ThisBuild / scalafmt                   := true
ThisBuild / scalafmtSbtCheck           := true
ThisBuild / semanticdbEnabled          := true
ThisBuild / semanticdbVersion          := scalafixSemanticdb.revision // use Scalafix compatible version
ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(`scala-2.13`)

// githubWorkflow
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"), JavaSpec.temurin("17"))
ThisBuild / tlCiHeaderCheck            := true
ThisBuild / tlCiScalafixCheck          := false
ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List("sudo apt update && sudo apt install libutf8proc-dev"),
    cond = Some("(matrix.project == 'rootNative') && startsWith(matrix.os, 'ubuntu')"),
    name = Some("Install native dependencies (ubuntu)"),
  )
)
ThisBuild / githubWorkflowJobSetup ++= Seq(
  WorkflowStep.Run(
    commands = List("docker-compose up -d"),
    name = Some("Start up Postgres"),
  )
)
ThisBuild / githubWorkflowBuild := {
  WorkflowStep.Sbt(
    List("scalafixAll --check"),
    name = Some("Check scalafix lints"),
    cond = Some("(matrix.project == 'rootJVM') && (matrix.scala == '2.13')"),
  ) +: (ThisBuild / githubWorkflowBuild).value
}
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  MatrixExclude(Map("project" -> "rootNative", "scala" -> "2.12")),
  MatrixExclude(Map("project" -> "rootNative", "scala" -> "2.13")),
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("fix", "; all scalafixAll; all scalafmtSbt scalafmtAll")
addCommandAlias("check", "; scalafmtSbtCheck; scalafmtCheckAll; scalafixAll --check")

lazy val commonSettings = List(
  // Headers
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense := Some(
    HeaderLicense.Custom(
      """|Copyright (c) 2023 by Roman Langolf
         |This software is licensed under the MIT License (MIT).
         |For more information see LICENSE or https://opensource.org/licenses/MIT
         |""".stripMargin
    )
  ),
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
      Seq("-source:future", "-language:adhocExtensions")
    else
      Seq("-Xsource:3")
  },
)

lazy val root = tlCrossRootProject
  .settings(name := "dumbo")
  .aggregate(core, tests, testsFlyway, example)
  .settings(commonSettings)

lazy val skunkVersion = "0.6.1"
lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .enablePlugins(AutomateHeaderPlugin)
  .in(file("modules/core"))
  .settings(
    name := "dumbo",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "skunk-core" % skunkVersion
    ),
  )
  .settings(commonSettings)

lazy val tests = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .in(file("modules/tests"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"             % "1.0.0-M10",
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
    libraryDependencies += "com.armanbilge" %%% "epollcat" % "0.1.6",
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1"),
    scalaVersion := `scala-3`,
    nativeConfig ~= {
      _.withEmbedResources(
        // disabling embdedded resources for now in CI as it causes flaky tests.
        // should remove once this is resolved: https://github.com/scala-native/scala-native/issues/2024
        sys.env.get("CI").isEmpty
      )
    },
  )

lazy val flywayVersion     = "9.22.3"
lazy val postgresqlVersion = "42.6.0"
lazy val testsFlyway = project
  .in(file("modules/tests-flyway"))
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .dependsOn(core.jvm, tests.jvm % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "org.flywaydb"   % "flyway-core" % flywayVersion,
      "org.postgresql" % "postgresql"  % postgresqlVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val example = project
  .in(file("modules/example"))
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .dependsOn(core.jvm)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    scalacOptions -= "-Xfatal-warnings",
  )
