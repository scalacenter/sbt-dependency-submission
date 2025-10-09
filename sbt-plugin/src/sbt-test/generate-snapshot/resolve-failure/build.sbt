import scala.util.Properties
import sbt.internal.util.complete.Parsers._

val checkManifests = inputKey[Unit]("Check the number of manifests")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    // use Ivy because Coursier does not allow several classifier on the same dep
    internalGithubDependencyGraphUseCoursier := false,
    scalaVersion := "2.13.8"
  )
)

val a = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.8"
  )

// Update on b fails, because b on 2.12.16 depends on a on 2.13.8
val b = project
  .in(file("b"))
  .settings(
    scalaVersion := "2.12.16"
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
