import sbt.{given, *}
import scala.scalanative.build.*

lazy val `scala-2.13`     = "2.13.18"
lazy val `scala-3`        = "3.3.8"
lazy val `scala-3-latest` = "3.7.4"

startYear          := Some(2023)
scalaVersion       := `scala-3`
crossScalaVersions := Seq(`scala-3`, `scala-2.13`)

organization := "dev.rolang"
licenses     := Seq(License.MIT)
developers   := List(
  Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://rolang.dev"))
)
evictionWarningOptions ~= (_.withConfigurations(List(Compile)))
versionScheme := Some("early-semver")
description   := "Simple database migration tool for Scala + Postgres"
homepage      := Some(uri("https://github.com/rolang/dumbo"))

semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

addCommandAlias("fix", "; +Test/copyResources; +scalafixAll; +scalafmtAll; scalafmtSbt")
addCommandAlias("check", "; +Test/copyResources; +scalafixAll --check; +scalafmtCheckAll; scalafmtSbtCheck")

lazy val commonSettings = Seq(
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

// source directory configuration for cross-project modules (shared + platform-specific dirs)
def scalaVerDir(binVer: String): String =
  if (binVer.startsWith("2")) "scala-2" else s"scala-$binVer"

def crossSourceDirSettings(matrixBase: File): Seq[Setting[_]] = Seq(
  Compile / unmanagedSourceDirectories := {
    val binVer = scalaBinaryVersion.value
    Seq(
      matrixBase / "shared" / "src" / "main" / "scala",
      matrixBase / "shared" / "src" / "main" / scalaVerDir(binVer),
    ).filter(_.exists).map(_.getCanonicalFile)
  },
  Test / unmanagedSourceDirectories := {
    val binVer = scalaBinaryVersion.value
    Seq(
      matrixBase / "shared" / "src" / "test" / "scala",
      matrixBase / "shared" / "src" / "test" / scalaVerDir(binVer),
    ).filter(_.exists).map(_.getCanonicalFile)
  },
  Test / unmanagedResourceDirectories ++= {
    Seq(matrixBase / "shared" / "src" / "test" / "resources").filter(_.exists).map(_.getCanonicalFile)
  },
)

def jvmSourceDirSettings(matrixBase: File): Seq[Setting[_]] = Seq(
  Compile / unmanagedSourceDirectories ++= {
    val binVer = scalaBinaryVersion.value
    Seq(
      matrixBase / "jvm" / "src" / "main" / "scala",
      matrixBase / "jvm" / "src" / "main" / scalaVerDir(binVer),
    ).filter(_.exists).map(_.getCanonicalFile)
  },
  Test / unmanagedSourceDirectories ++= {
    val binVer = scalaBinaryVersion.value
    Seq(
      matrixBase / "jvm" / "src" / "test" / "scala",
      matrixBase / "jvm" / "src" / "test" / scalaVerDir(binVer),
    ).filter(_.exists).map(_.getCanonicalFile)
  },
  Compile / unmanagedResourceDirectories ++= {
    Seq(matrixBase / "jvm" / "src" / "main" / "resources").filter(_.exists).map(_.getCanonicalFile)
  },
)

def nativeSourceDirSettings(matrixBase: File): Seq[Setting[_]] = Seq(
  Compile / unmanagedSourceDirectories ++= {
    Seq(matrixBase / "native" / "src" / "main" / "scala").filter(_.exists).map(_.getCanonicalFile)
  },
  Test / unmanagedSourceDirectories ++= {
    Seq(matrixBase / "native" / "src" / "test" / "scala").filter(_.exists).map(_.getCanonicalFile)
  },
)

lazy val root = project.in(file("."))
  .settings(
    name := "dumbo-root",
    publish / skip := true,
  )

lazy val skunkVersion = "1.0.0"

lazy val munitVersion = "1.3.3"

lazy val munitCEVersion = "2.2.0"

lazy val coreBase = file("modules/core")

lazy val core = (projectMatrix in coreBase)
  .enablePlugins(AutomateHeaderPlugin, BuildInfoPlugin)
  .settings(commonSettings*)
  .settings(crossSourceDirSettings(coreBase)*)
  .settings(
    name := "dumbo",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % skunkVersion
    ),
    buildInfoPackage := "dumbo",
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, scalaBinaryVersion),
  )
  .jvmPlatform(
    scalaVersions = Seq(`scala-3`, `scala-2.13`),
    settings = jvmSourceDirSettings(coreBase),
  )
  .nativePlatform(
    scalaVersions = Seq(`scala-3`),
    settings = nativeSourceDirSettings(coreBase) ++ Seq[Setting[_]](
      buildInfoKeys ++= Seq[BuildInfoKey]("scalaNativeVersion" -> nativeVersion),
    ),
  )

lazy val coreJVM3    = LocalProject("core")
lazy val coreJVM2_13 = LocalProject("core2_13")
lazy val coreNative3 = LocalProject("coreNative")

lazy val llvmVersion  = "22"
lazy val brewFormulas = Set("s2n", "utf8proc")

lazy val cliBase = file("modules/cli")

lazy val cli = (projectMatrix in cliBase)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(core)
  .settings(commonSettings*)
  .settings(crossSourceDirSettings(cliBase)*)
  .settings(
    name := "dumbo-cli",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ),
  )
  .nativePlatform(
    scalaVersions = Seq(`scala-3`),
    settings = nativeSourceDirSettings(cliBase) ++ Seq[Setting[_]](
      nativeConfig ~= { c =>
        brewFormulas.foldLeft(c) { (cfg, formula) =>
          cfg
            .withLinkingOptions(cfg.linkingOptions :+ s"-L${System.getProperty(s"user.home")}/.linuxbrew/opt/$formula/lib")
            .withCompileOptions(cfg.compileOptions :+ s"-I${System.getProperty(s"user.home")}/.linuxbrew/opt/$formula/include")
        }
      },
    ),
  )

lazy val cliNative = cli.native(`scala-3`)

lazy val buildCliBinary = taskKey[File]("")
buildCliBinary := Def.uncached {
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

  val name      = s"dumbo-cli-$arch-$os"
  val built     = (cliNative / Compile / nativeLink).value
  val converter = fileConverter.value
  val builtFile = converter.toPath(built).toFile
  val destBin   = (cliNative / target).value / "bin" / name
  val destZip   = (cliNative / target).value / "bin" / s"$name.zip"

  IO.copyFile(builtFile, destBin)
  sLog.value.info(s"Built cli binary in $destBin")

  IO.zip(Seq((builtFile, "dumbo")), destZip, None)
  sLog.value.info(s"Built cli binary zip in $destZip")

  destBin
}

lazy val testsBase = file("modules/tests")

lazy val tests = (projectMatrix in testsBase)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(core)
  .settings(commonSettings*)
  .settings(crossSourceDirSettings(testsBase)*)
  .settings(
    publish / skip := true,
    Test / scalacOptions -= "-Werror",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"             % munitVersion   % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCEVersion % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    testOptions += {
      if (System.getProperty("os.arch").startsWith("aarch64")) {
        Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=X86ArchOnly")
      } else Tests.Argument()
    },
  )
  .jvmPlatform(
    scalaVersions = Seq(`scala-3`, `scala-2.13`),
    settings = jvmSourceDirSettings(testsBase),
  )
  .nativePlatform(
    scalaVersions = Seq(`scala-3`),
    settings = nativeSourceDirSettings(testsBase) ++ Seq[Setting[_]](
      Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1"),
      Test / testOptions ++= {
        if (sys.env.contains("CI")) Seq(Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=CockroachDbTest"))
        else Seq.empty
      },
      nativeConfig ~= {
        _.withEmbedResources(true)
      },
    ),
  )

lazy val testsJVM3    = tests.jvm(`scala-3`)
lazy val testsJVM2_13 = tests.jvm(`scala-2.13`)
lazy val testsNative3 = tests.native(`scala-3`)

lazy val flywayVersion = "12.9.0"

lazy val postgresqlVersion = "42.7.11"

lazy val testsFlyway = project
  .in(file("modules/tests-flyway"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(LocalProject("core"), LocalProject("tests") % "compile->compile;test->test")
  .settings(commonSettings*)
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
  .dependsOn(LocalProject("core"))
  .settings(commonSettings*)
  .settings(
    scalaVersion          := `scala-3-latest`,
    crossScalaVersions    := Seq(`scala-3-latest`),
    Compile / run / fork  := true,
    Compile / headerCheck := Nil,
    scalacOptions -= "-Werror",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j"  % "2.8.0",
      "ch.qos.logback" % "logback-classic" % "1.5.32",
    ),
  )

lazy val sampleLib = project
  .in(file("modules/sample-lib"))
  .settings(
    version               := "0.0.1-SNAPSHOT",
    scalaVersion          := `scala-3`,
    crossScalaVersions    := Seq(`scala-3`),
    name                  := "sample-lib",
    Compile / headerCheck := Nil,
  )

lazy val testLib = project
  .in(file("modules/test-lib"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(LocalProject("core"))
  .settings(commonSettings*)
  .settings(
    Compile / run / fork                := true,
    libraryDependencies += "dev.rolang" %% "sample-lib" % "0.0.1-SNAPSHOT",
    scalaVersion                        := `scala-3`,
    crossScalaVersions                  := Seq(`scala-3`),
    name                                := "sample-lib-test",
    Compile / headerCheck               := Nil,
  )

lazy val generateReadme =
  taskKey[Unit]("Generate README.md from docs/README.md replacing @VERSION@ and @EXAMPLE(<path>) placeholders")

generateReadme := {
  import scala.sys.process.*
  import scala.util.matching.Regex

  val latestTag = "git tag -l --sort=-v:refname".!!.linesIterator
    .map(_.trim)
    .filter(_.nonEmpty)
    .find(_ => true)
    .getOrElse(sys.error("No git tags found"))
  val version = latestTag.stripPrefix("v")

  def readExample(path: String): String =
    sbt.IO
      .read(file(path))
      .linesIterator
      .dropWhile(_.startsWith("//> using"))
      .dropWhile(_.trim.isEmpty)
      .mkString("\n")

  val examplePattern = """@EXAMPLE\((.+?)\)""".r
  val template       = sbt.IO.read(file("docs/README.md"))
  val withVersion    = template.replace("@VERSION@", version)
  val result         = examplePattern.replaceAllIn(withVersion, m => Regex.quoteReplacement(readExample(m.group(1))))

  sbt.IO.write(file("README.md"), result)
  streams.value.log.info(s"Generated README.md with version $version")
}
