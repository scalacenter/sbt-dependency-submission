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
    useCoursier := false,
    scalaVersion := "2.13.14"
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
        expectedPackageUrl =
          "pkg:maven/org.lwjgl/lwjgl@3.3.1?packaging=natives-linux&packaging=natives-macos&packaging=natives-windows"
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
