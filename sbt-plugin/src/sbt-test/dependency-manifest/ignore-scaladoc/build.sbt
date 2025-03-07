import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import ch.epfl.scala.DependencySnapshotInput
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkScaladoc = taskKey[Unit]("Check scaladoc_3 is in the manifest ")
val ignoreScaladoc = taskKey[StateTransform]("Ignore the scala-doc-tool in the submit input")
val checkIgnoreScaladoc = taskKey[Unit]("Check scaladoc_3 is absent in the manifest")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    scalaVersion := "3.2.1"
  )
)

Global / ignoreScaladoc := {
  val input = DependencySnapshotInput(
    None,
    Vector.empty,
    ignoredConfigs = Vector("scala-doc-tool"),
    correlator = None,
    shaOverride = None,
    refOverride = None,
    manifestOverride = None
  )
  StateTransform(state => state.put(githubSnapshotInputKey, input))
}

lazy val p1 = project
  .in(file("p1"))
  .settings(
    checkScaladoc := {
      val manifest = githubDependencyManifest.value.get
      checkDependency(manifest, "org.scala-lang:scaladoc_3:3.2.1")(
        expectedRelationship = DependencyRelationship.direct,
        expectedScope = DependencyScope.development,
        expectedConfig = "scala-doc-tool"
      )
    },
    checkIgnoreScaladoc := {
      val manifest = githubDependencyManifest.value.get
      val suspicious = manifest.resolved.keys.filter(dep => dep.contains("scaladoc_3"))
      assert(suspicious.isEmpty, s"The manifest should not contain scaladoc_3, found ${suspicious.mkString(", ")}")
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
