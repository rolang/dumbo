import scala.scalanative.build.*

lazy val `scala-2.13` = "2.13.13"
lazy val `scala-3`    = "3.3.3"

ThisBuild / tlBaseVersion      := "0.2"
ThisBuild / startYear          := Some(2023)
ThisBuild / scalaVersion       := `scala-3`
ThisBuild / crossScalaVersions := Seq(`scala-3`, `scala-2.13`)

ThisBuild / organization := "dev.rolang"
ThisBuild / licenses     := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://rolang.dev"))
)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / description   := "Simple database migration tool for Scala + Postgres"
ThisBuild / homepage      := Some(url("https://github.com/rolang/dumbo"))

ThisBuild / scalafmt          := true
ThisBuild / scalafmtSbtCheck  := true
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision // use Scalafix compatible version

// githubWorkflow
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"), JavaSpec.temurin("17"))
ThisBuild / tlCiHeaderCheck            := true
ThisBuild / tlCiScalafixCheck          := false

lazy val brewFormulas = Set("s2n", "utf8proc")

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List(
      s"sudo apt-get update && sudo apt-get install clang && /home/linuxbrew/.linuxbrew/bin/brew install ${brewFormulas.mkString(" ")}"
    ),
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
    List("Test/copyResources; scalafixAll --check"),
    name = Some("Check scalafix lints"),
    cond = Some("matrix.java == 'temurin@21' && (matrix.scala == '3')"),
  ) +: (ThisBuild / githubWorkflowBuild).value
}

ThisBuild / githubWorkflowBuild += WorkflowStep.Run(
  commands = List("sbt cliNative/test"),
  name = Some("CLI test"),
  cond = Some("matrix.project == 'rootNative' && (matrix.scala == '3')"),
)

ThisBuild / githubWorkflowBuild += WorkflowStep.Run(
  commands = List("sbt buildCliBinary"),
  name = Some("Generate CLI native binary"),
  cond = Some("matrix.project == 'rootNative' && (matrix.scala == '3')"),
  env = Map(
    "SCALANATIVE_MODE" -> Mode.releaseFast.toString(),
    "SCALANATIVE_LTO"  -> LTO.thin.toString(),
  ),
)

ThisBuild / githubWorkflowPublish += WorkflowStep.Use(
  ref = UseRef.Public("softprops", "action-gh-release", "v1"),
  name = Some("Upload release binaries"),
  params = Map(
    "files" -> "modules/cli/native/target/bin/*"
  ),
  cond = Some("startsWith(github.ref, 'refs/tags/')"),
)

ThisBuild / githubWorkflowPublish += WorkflowStep.Run(
  name = Some("Release docker image"),
  commands = List(
    """echo -n "${DOCKER_PASSWORD}" | docker login docker.io -u rolang --password-stdin""",
    "export RELEASE_TAG=${GITHUB_REF_NAME#'v'}",
    "cp -r modules/cli/native/target/bin docker-build/bin",
    "docker build ./docker-build -t rolang/dumbo:${RELEASE_TAG}-alpine",
    "docker tag rolang/dumbo:${RELEASE_TAG}-alpine rolang/dumbo:latest-alpine",
    "docker push rolang/dumbo:${RELEASE_TAG}-alpine",
    "docker push rolang/dumbo:latest-alpine",
  ),
  env = Map(
    "DOCKER_PASSWORD" -> "${{ secrets.DOCKER_PASSWORD }}"
  ),
  cond = Some("startsWith(github.ref, 'refs/tags/')"),
)

ThisBuild / githubWorkflowBuild += WorkflowStep.Sbt(
  List("example/run"),
  name = Some("Run example (covers reading resources from a jar)"),
  cond = Some("matrix.project == 'rootJVM'"),
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
      Seq("-source:future")
    else
      Seq("-Xsource:3")
  },
)

lazy val root = tlCrossRootProject
  .settings(name := "dumbo")
  .aggregate(core, tests, testsFlyway, example)
  .settings(commonSettings)

lazy val skunkVersion = "1.0.0-M4"

lazy val epollcatVersion = "0.1.6"

lazy val munitVersion = "1.0.0-M11"

lazy val munitCEVersion = "2.0.0-M4"

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .enablePlugins(AutomateHeaderPlugin, BuildInfoPlugin)
  .in(file("modules/core"))
  .settings(
    name := "dumbo",
    libraryDependencies ++= Seq(
      "org.tpolecat" %%% "skunk-core" % skunkVersion
    ),
    buildInfoPackage := "dumbo",
    buildInfoKeys := {
      val isNative = crossProjectPlatform.value.identifier == "native"
      Seq[BuildInfoKey](
        version,
        scalaVersion,
        scalaBinaryVersion,
      ) ++ (if (isNative) Seq(BuildInfoKey("scalaNativeVersion" -> nativeVersion)) else Seq())
    },
  )
  .settings(commonSettings)

lazy val cli = crossProject(NativePlatform)
  .crossType(CrossType.Full)
  .enablePlugins(AutomateHeaderPlugin)
  .in(file("modules/cli"))
  .dependsOn(core)
  .settings(
    scalaVersion := `scala-3`,
    name         := "dumbo-cli",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test
    ),
  )
  .settings(commonSettings)
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .nativeSettings(
    libraryDependencies += "com.armanbilge" %%% "epollcat" % epollcatVersion,
    nativeBrewFormulas ++= brewFormulas,
  )

lazy val cliNative      = cli.native
lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := {
  def normalise(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]+", "")
  val props                = sys.props.toMap
  val os = normalise(props.getOrElse("os.name", "")) match {
    case p if p.startsWith("linux")                         => "linux"
    case p if p.startsWith("windows")                       => "windows"
    case p if p.startsWith("osx") || p.startsWith("macosx") => "macosx"
    case _                                                  => "unknown"
  }

  val arch = (
    normalise(props.getOrElse("os.arch", "")),
    props.getOrElse("sun.arch.data.model", "64"),
  ) match {
    case ("amd64" | "x64" | "x8664" | "x86", bits) => s"x86_${bits}"
    case ("aarch64" | "arm64", bits)               => s"aarch$bits"
    case _                                         => "unknown"
  }

  val name    = s"dumbo-cli-$arch-$os"
  val built   = (cliNative / Compile / nativeLink).value
  val destBin = (cliNative / target).value / "bin" / name
  val destZip = (cliNative / target).value / "bin" / s"$name.zip"

  IO.copyFile(built, destBin)
  sLog.value.info(s"Built cli binary in $destBin")

  IO.zip(Seq((built, "dumbo")), destZip, None)
  sLog.value.info(s"Built cli binary zip in $destZip")

  destBin
}

lazy val tests = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .in(file("modules/tests"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    Test / scalacOptions -= "-Werror",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"             % munitVersion,
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    testOptions += {
      if (System.getProperty("os.arch").startsWith("aarch64")) {
        Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=X86ArchOnly")
      } else Tests.Argument()
    },
  )
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .nativeSettings(
    libraryDependencies += "com.armanbilge" %%% "epollcat" % epollcatVersion,
    Test / nativeBrewFormulas ++= brewFormulas,
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1"),
    nativeConfig ~= {
      _.withEmbedResources(true)
    },
  )

lazy val flywayVersion     = "10.10.0"
lazy val postgresqlVersion = "42.7.3"
lazy val testsFlyway = project
  .in(file("modules/tests-flyway"))
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .dependsOn(core.jvm, tests.jvm % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    Test / scalacOptions -= "-Werror",
    libraryDependencies ++= Seq(
      "org.flywaydb"   % "flyway-core"                % flywayVersion,
      "org.flywaydb"   % "flyway-database-postgresql" % flywayVersion,
      "org.postgresql" % "postgresql"                 % postgresqlVersion,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )

lazy val example = project
  .in(file("modules/example"))
  .enablePlugins(AutomateHeaderPlugin, NoPublishPlugin)
  .dependsOn(core.jvm)
  .settings(commonSettings)
  .settings(
    Compile / run / fork := true,
    publish / skip       := true,
    scalacOptions -= "-Werror",
  )
