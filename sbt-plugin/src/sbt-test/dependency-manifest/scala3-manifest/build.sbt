import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    scalaVersion := "3.1.0"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.1"
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value
      assert(manifest.name == "ch.epfl.scala:p1_3:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(manifest.resolved.values.forall(n => n.dependencies.forall(manifest.resolved.contains)))

      checkDependency(manifest, "io.circe:circe-core_3:0.14.1")(
        expectedDeps = Seq("org.scala-lang:scala3-library_3:3.1.0")
      )
      checkDependency(manifest, "org.scala-lang:scala3-library_3:3.1.0")()
      checkDependency(manifest, "org.scala-lang:scala3-compiler_3:3.1.0")(
        expectedConfig = "scala-doc-tool",
        expectedScope = DependencyScope.development
      )
    }
  )

def checkDependency(manifest: Manifest, name: String)(
    expectedRelationship: DependencyRelationship = DependencyRelationship.direct,
    expectedScope: DependencyScope = DependencyScope.runtime,
    expectedConfig: String = "compile",
    expectedDeps: Seq[String] = Seq.empty
): Unit = {
  val node = manifest.resolved(name)
  assert(node.package_url.startsWith("pkg:maven/"), s"Wrong package_url for node $name: ${node.package_url}")
  assert(node.relationship.contains(expectedRelationship), s"Wrong relationship for node $name: ${node.relationship}")
  assert(node.scope.contains(expectedScope), s"Wrong scope for node $name: ${node.scope}")
  val configurations = node.metadata.get("config").collect { case JString(c) => c }
  assert(configurations.contains(expectedConfig), s"Wrong config in metadata for node $name: $configurations")
  expectedDeps.foreach(d => assert(node.dependencies.contains(d), s"missing dependency $d in node $name"))
}
