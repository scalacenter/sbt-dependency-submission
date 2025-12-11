import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    // use Ivy because Coursier does not allow several classifier on the same dep
    internalGithubDependencyGraphUseCoursier := false,
    scalaVersion := "2.12.20"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      ("com.google.inject" % "guice" % "4.0").classifier("no_aop"),
      ("org.lwjgl" % "lwjgl" % "3.3.1")
        .classifier("natives-windows")
        .classifier("natives-linux")
        .classifier("natives-macos")
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value.get

      checkDependency(manifest, "com.google.inject:guice:4.0")(
        expectedPackageUrl = "pkg:maven/com.google.inject/guice@4.0?packaging=no_aop"
      )
      checkDependency(manifest, "org.lwjgl:lwjgl:3.3.1")(
        expectedPackageUrl = sbtVersion.value match {
          case v if v.startsWith("1.") =>
            "pkg:maven/org.lwjgl/lwjgl@3.3.1?packaging=natives-linux,natives-macos,natives-windows"
          // Ivy support was removed in sbt 2 (https://github.com/sbt/sbt/pull/7712), so coursier is always used
          // Since coursier doesn't allow several classifiers on the same dep, the package URL only has the last one
          case v if v.startsWith("2.") =>
            "pkg:maven/org.lwjgl/lwjgl@3.3.1?packaging=natives-macos"
        }
      )
    }
  )

def checkDependency(manifest: Manifest, name: String)(expectedPackageUrl: String): Unit = {
  val node = manifest.resolved(name)
  assert(
    node.package_url == expectedPackageUrl,
    s"Wrong package_url for node $name:\nfound: ${node.package_url}\nexpected:$expectedPackageUrl"
  )
}
