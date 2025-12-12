def isRelease() =
  System.getenv("GITHUB_REPOSITORY") == "scalacenter/sbt-dependency-submission" &&
    System.getenv("GITHUB_WORKFLOW") == "Release"

def isCI = System.getenv("CI") != null

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/sbt-dependency-submission")),
    onLoadMessage := s"Welcome to sbt-github-dependency-submission ${version.value}",
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := Developers.all,
    version ~= { dynVer =>
      if (isRelease) dynVer
      else "3.2.0-SNAPSHOT" // only for local publishing
    },
    // Scalafix settings
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

val scala2 = "2.12.21"
val scala3 = "3.7.3"

val `sbt-github-dependency-submission` = project
  .in(file("."))
  .enablePlugins(SbtPlugin, ContrabandPlugin, JsonCodecPlugin, BuildInfoPlugin)
  .settings(
    name := "sbt-github-dependency-submission",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.5.8"
        case _      => "2.0.0-RC6"
      }
    },
    scalaVersion := scala2,
    crossScalaVersions := Seq(scala2, scala3),
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked"
    ) ++ (scalaBinaryVersion.value match {
      case "2.12" => Seq("-Ywarn-unused-import", "-Xsource:3", "-Xfatal-warnings")
      case _      => Seq("-Wunused:imports")
    }),
    libraryDependencies ++= Seq(
      "com.eed3si9n" %% "gigahorse-asynchttpclient" % "0.9.3",
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    buildInfoKeys := Seq[BuildInfoKey](name, version, homepage),
    buildInfoPackage := "ch.epfl.scala",
    buildInfoObject := "SbtGithubDependencySubmission",
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    scriptedBufferLog := false,
    Compile / generateContrabands / contrabandFormatsForType := ContrabandConfig.getFormats,
    scriptedDependencies :=
      publishLocal.value
  )
