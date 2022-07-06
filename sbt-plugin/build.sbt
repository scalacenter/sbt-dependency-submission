def isRelease() =
  System.getenv("GITHUB_REPOSITORY") == "scalacenter/sbt-github-dependency-graph" &&
    System.getenv("GITHUB_WORKFLOW") == "Release"

def isCI = System.getenv("CI") != null

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/sbt-dependency-graph-action")),
    onLoadMessage := s"Welcome to sbt-github-dependency-graph ${version.value}",
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := Developers.all,
    version ~= { dynVer =>
      if (isRelease) dynVer
      else "1.1.0-SNAPSHOT" // only for local publishing
    },
    // Scalafix settings
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixDependencies ++= List(
      "com.github.liancheng" %% "organize-imports" % "0.6.0"
    )
  )
)

val `sbt-github-dependency-graph` = project
  .in(file("."))
  .enablePlugins(SbtPlugin, ContrabandPlugin, JsonCodecPlugin, BuildInfoPlugin)
  .settings(
    name := "sbt-github-dependency-graph",
    sbtVersion := "1.5.8",
    scalaVersion := "2.12.15",
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Ywarn-unused-import"
    ),
    libraryDependencies ++= Seq(
      "com.eed3si9n" %% "gigahorse-apache-http" % "0.7.0",
      "org.scalameta" %% "munit" % "0.7.29" % Test
    ),
    buildInfoKeys := Seq[BuildInfoKey](name, version, homepage),
    buildInfoPackage := "ch.epfl.scala",
    buildInfoObject := "SbtGithubDependencyGraph",
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    scriptedBufferLog := false,
    Compile / generateContrabands / contrabandFormatsForType := ContrabandConfig.getFormats,
    scriptedDependencies := {
      publishLocal.value
    }
  )
