import scala.scalanative.build.*

lazy val `scala-2.13`     = "2.13.16"
lazy val `scala-3`        = "3.3.6"
lazy val `scala-3-latest` = "3.7.2"

ThisBuild / tlBaseVersion      := "0.6"
ThisBuild / startYear          := Some(2023)
ThisBuild / scalaVersion       := `scala-3`
ThisBuild / crossScalaVersions := Seq(`scala-3`, `scala-2.13`)

ThisBuild / organization := "dev.rolang"
ThisBuild / licenses     := Seq(License.MIT)
ThisBuild / developers   := List(
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
lazy val macOsArm   = "macos-14"
lazy val macOsIntel = "macos-13"
lazy val macOses    = Seq(macOsIntel, macOsArm)
ThisBuild / githubWorkflowOSes ++= Seq(macOsIntel, macOsArm)
ThisBuild / githubWorkflowBuildMatrixExclusions ++= macOses.map(os =>
  MatrixExclude(Map("os" -> os, "project" -> "rootJVM"))
)
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("21"), JavaSpec.temurin("17"))
ThisBuild / tlCiHeaderCheck            := true
ThisBuild / tlCiScalafixCheck          := false

lazy val llvmVersion  = "20"
lazy val brewFormulas = Set("s2n", "utf8proc")
lazy val isTagCond    = "startsWith(github.ref, 'refs/tags/')"

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List(
      s"/home/linuxbrew/.linuxbrew/bin/brew install llvm@$llvmVersion ${brewFormulas.mkString(" ")}",
      s"""echo "LLVM_BIN=/home/linuxbrew/.linuxbrew/opt/llvm@$llvmVersion/bin" >> $$GITHUB_ENV""",
    ),
    cond = Some("(matrix.project == 'rootNative') && startsWith(matrix.os, 'ubuntu')"),
    name = Some("Install native dependencies (ubuntu)"),
  )
)
ThisBuild / githubWorkflowBuildPreamble ++= List(
  macOsIntel -> "/usr/local/opt",
  macOsArm   -> "/opt/homebrew/opt",
).map { case (os, llvmBase) =>
  WorkflowStep.Run(
    commands = List(
      s"brew install llvm@$llvmVersion ${brewFormulas.mkString(" ")}",
      s"""echo "LLVM_BIN=$llvmBase/llvm@$llvmVersion/bin" >> $$GITHUB_ENV""",
    ),
    cond = Some(s"(matrix.project == 'rootNative') && matrix.os == '$os'"),
    name = Some(s"Install native dependencies ($os)"),
  )
}

ThisBuild / githubWorkflowBuild := {
  WorkflowStep.Sbt(
    List("Test/copyResources; scalafixAll --check; all scalafmtSbtCheck scalafmtCheckAll"),
    name = Some("Check scalafix/scalafmt lints"),
    cond = Some(
      "matrix.java == 'temurin@21' && (matrix.scala == '3') && matrix.project == 'rootJVM' && startsWith(matrix.os, 'ubuntu')"
    ),
  ) +: (ThisBuild / githubWorkflowBuild).value
}

// override Test step to run on ubuntu only due to requirement of docker
ThisBuild / githubWorkflowBuild := {
  (ThisBuild / githubWorkflowBuild).value.flatMap {
    case s if s.name.contains("Test") =>
      List(
        WorkflowStep.Run(
          commands = List("docker compose up -d"),
          name = Some("Start up Postgres"),
          cond = Some("startsWith(matrix.os, 'ubuntu')"),
        ),
        WorkflowStep.Sbt(
          List("test"),
          name = Some("Test"),
          cond = Some("startsWith(matrix.os, 'ubuntu')"),
        ),
      )
    case s => List(s)
  }
}

ThisBuild / githubWorkflowBuild += WorkflowStep.Run(
  commands = List("sbt cliNative/test"),
  name = Some("CLI test"),
  cond = Some("matrix.project == 'rootNative' && (matrix.scala == '3')"),
)

ThisBuild / githubWorkflowBuild ++= List(
  "ubuntu" -> Map("SCALANATIVE_LTO" -> LTO.thin.toString()),
  "macos"  -> Map.empty,
).flatMap { case (os, envs) =>
  val base = WorkflowStep.Run(
    commands = List("sbt buildCliBinary"),
    name = Some(s"Generate CLI native binary"),
    cond = Some(s"matrix.project == 'rootNative' && (matrix.scala == '3') && startsWith(matrix.os, '$os')"),
  )

  List(
    // debug mode
    base.withName(base.name.map(_ + s" ($os, debug)")).withCond(base.cond.map(_ + s" && !$isTagCond")),
    // release mode (takes long time...)
    base
      .withName(base.name.map(_ + s" ($os, release)"))
      .withEnv(env = Map("SCALANATIVE_MODE" -> Mode.releaseFast.toString()) ++ envs)
      .withCond(base.cond.map(_ + s" && $isTagCond")),
  )
}

ThisBuild / githubWorkflowBuild += WorkflowStep.Use(
  ref = UseRef.Public("actions", "upload-artifact", "v4"),
  params = Map("name" -> "cli-bin-${{ matrix.os }}", "path" -> "modules/cli/native/target/bin/*"),
  name = Some("Upload command line binaries"),
  cond = Some(s"matrix.project == 'rootNative' && (matrix.scala == '3') && $isTagCond"),
)

ThisBuild / githubWorkflowGeneratedCI := (ThisBuild / githubWorkflowGeneratedCI).value.flatMap {
  case j if j.id == "publish" =>
    List(
      j,
      WorkflowJob(
        id = "publish-cli-bin",
        name = "Publish command line binaries",
        needs = List("build"),
        cond = Some(isTagCond),
        scalas = Nil,
        javas = List(JavaSpec.temurin("21")),
        steps = List(
          WorkflowStep.Use(
            ref = UseRef.Public("actions", "download-artifact", "v4"),
            params = Map("pattern" -> "cli-bin-*", "path" -> "target-cli/bin", "merge-multiple" -> "true"),
            name = Some("Download command line binaries"),
          ),
          WorkflowStep.Use(
            ref = UseRef.Public("softprops", "action-gh-release", "v1"),
            name = Some("Upload release binaries"),
            params = Map("files" -> "target-cli/bin/*"),
          ),
        ),
      ),
      WorkflowJob(
        id = "publish-cli-docker",
        name = "Publish command line docker image",
        needs = List("build"),
        cond = Some(isTagCond),
        scalas = Nil,
        javas = List(JavaSpec.temurin("21")),
        steps = List(
          WorkflowStep.Checkout,
          WorkflowStep.Use(
            name = Some("Download command line linux build"),
            ref = UseRef.Public("actions", "download-artifact", "v4"),
            params = Map("name" -> "target-${{ matrix.os }}-${{ matrix.java }}-3-rootNative"),
          ),
          WorkflowStep.Run(
            name = Some("Inflate command line linux build"),
            commands = List("tar xf targets.tar", "rm targets.tar"),
          ),
          WorkflowStep.Run(
            name = Some("Release docker image"),
            commands = List(
              """echo -n "${DOCKER_PASSWORD}" | docker login docker.io -u rolang --password-stdin""",
              "export RELEASE_TAG=${GITHUB_REF_NAME#'v'}",
              "cp -r modules/cli/native/target/bin docker-build/bin",
              "docker build ./docker-build -t rolang/dumbo:${RELEASE_TAG}-alpine",
              "docker run rolang/dumbo:${RELEASE_TAG}-alpine", // run for health-checking the docker image
              "docker tag rolang/dumbo:${RELEASE_TAG}-alpine rolang/dumbo:latest-alpine",
              "docker push rolang/dumbo:${RELEASE_TAG}-alpine",
              "docker push rolang/dumbo:latest-alpine",
            ),
            env = Map(
              "DOCKER_PASSWORD" -> "${{ secrets.DOCKER_PASSWORD }}"
            ),
          ),
        ),
      ),
    )
  case j => List(j)
}

ThisBuild / githubWorkflowBuild += WorkflowStep.Sbt(
  List("example/runMain ExampleApp"),
  name = Some("Run example (covers reading resources from a jar at runtime)"),
  cond = Some("matrix.project == 'rootJVM' && matrix.scala == '3'"),
)

ThisBuild / githubWorkflowBuild += WorkflowStep.Run(
  commands = List("sbt -Dsample_lib_test 'testLib/clean; sampleLib/publishLocal; testLib/run'"),
  name = Some("Test reading resources from a jar at compile time"),
  cond = Some("matrix.project == 'rootJVM' && matrix.scala == '3'"),
)

addCommandAlias("fix", "; +Test/copyResources; +scalafixAll; +scalafmtAll; scalafmtSbt")
addCommandAlias("check", "; +Test/copyResources; +scalafixAll --check; +scalafmtCheckAll; scalafmtSbtCheck")

lazy val commonSettings = List(
  // Headers
  headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
  headerLicense  := Some(
    HeaderLicense.Custom(
      """|Copyright (c) 2023 by Roman Langolf
         |This software is licensed under the MIT License (MIT).
         |For more information see LICENSE or https://opensource.org/licenses/MIT
         |""".stripMargin
    )
  ),
  libraryDependencies ++= {
    if (scalaVersion.value.startsWith("3"))
      Seq()
    else
      Seq(
        compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.4").cross(CrossVersion.full)),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      )
  },
  Compile / scalacOptions ++= {
    if (scalaVersion.value.startsWith("3"))
      Seq("-source:future")
    else
      Seq("-Xsource:3")
  },
)

lazy val root = tlCrossRootProject
  .settings(name := "dumbo")
  .aggregate(core, tests, testsFlyway)
  .settings(commonSettings)

lazy val skunkVersion = "1.0.0-M11"

lazy val munitVersion = "1.0.0"

lazy val munitCEVersion = "2.1.0"

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
    buildInfoKeys    := {
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
    nativeBrewFormulas ++= brewFormulas
  )

lazy val cliNative      = cli.native
lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := {
  def normalise(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]+", "")
  val props                = sys.props.toMap
  val os                   = normalise(props.getOrElse("os.name", "")) match {
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
    Test / nativeBrewFormulas ++= brewFormulas,
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1"),
    nativeConfig ~= {
      _.withEmbedResources(true)
    },
  )

lazy val flywayVersion     = "11.0.0"
lazy val postgresqlVersion = "42.7.8"
lazy val testsFlyway       = project
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
  .enablePlugins(NoPublishPlugin)
  .dependsOn(core.jvm)
  .settings(commonSettings)
  .settings(
    scalaVersion          := `scala-3-latest`,
    crossScalaVersions    := Seq(`scala-3-latest`),
    Compile / run / fork  := true,
    Compile / headerCheck := Nil,
    scalacOptions -= "-Werror",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j"  % "2.7.1",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
    ),
  )

lazy val sampleLib = project
  .in(file("modules/sample-lib"))
  .enablePlugins((if (!sys.props.contains("sample_lib_test")) Seq(NoPublishPlugin) else Nil) *)
  .settings(
    version               := "0.0.1-SNAPSHOT",
    scalaVersion          := `scala-3`,
    crossScalaVersions    := Seq(`scala-3`),
    name                  := "sample-lib",
    Compile / headerCheck := Nil,
  )

lazy val testLib = project
  .in(file("modules/test-lib"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .dependsOn(core.jvm)
  .settings(
    Compile / run / fork                := true,
    libraryDependencies += "dev.rolang" %% "sample-lib" % "0.0.1-SNAPSHOT",
    scalaVersion                        := `scala-3`,
    crossScalaVersions                  := Seq(`scala-3`),
    name                                := "sample-lib-test",
    Compile / headerCheck               := Nil,
  )
