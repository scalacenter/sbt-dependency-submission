import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

// using the default scalaVersion
inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    checkManifest := {
      val manifest = githubDependencyManifest.value.get
      assert(manifest.name == "ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(manifest.resolved.values.forall(n => n.dependencies.forall(manifest.resolved.contains)))

      checkDependency(manifest, "org.scala-lang:scala-library:2.12.14")()
      checkDependency(manifest, "org.scala-lang:scala-compiler:2.12.14")(
        expectedScope = DependencyScope.development,
        expectedConfig = "scala-tool"
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
