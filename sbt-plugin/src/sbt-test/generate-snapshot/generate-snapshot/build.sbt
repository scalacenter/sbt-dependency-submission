import scala.util.Properties
import sbt.internal.util.complete.Parsers._

val checkManifests = inputKey[Unit]("Check the number of manifests")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    // use Ivy because Coursier does not allow several classifier on the same dep
    useCoursier := false,
    scalaVersion := "2.13.8"
  )
)

val a = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.8",
    crossScalaVersions := Seq(
      "2.12.16",
      "2.13.8",
      "3.1.3"
    ),
    libraryDependencies ++= Seq(
      // a dependency with many classifiers
      ("org.lwjgl" % "lwjgl" % "3.3.1")
        .classifier("natives-windows")
        .classifier("natives-linux")
        .classifier("natives-macos")
    )
  )

// b is not cross-compiled
// but we should still be able to resolve the manifests of the build on 2.12.16 and 3.1.3
// this pattern is taken from scalameta/metals where metals, not cross-compiled, depends on mtags
// which is cross-compiled
val b = project
  .in(file("b"))
  .settings(
    scalaVersion := "2.13.8"
  )
  .dependsOn(a)

Global / checkManifests := {
  val logger = streams.value.log
  val expectedSize: Int = (Space ~> NatBasic).parsed
  val manifests = state.value.get(githubManifestsKey).getOrElse {
    throw new MessageOnlyException(s"Not found ${githubManifestsKey.label} attribute")
  }
  logger.info(s"found ${manifests.size} manifests")
  assert(
    manifests.size == expectedSize,
    s"expected $expectedSize manifests, found ${manifests.size}"
  )
}
