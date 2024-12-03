import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import ch.epfl.scala.DependencySnapshotInput
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkTest = taskKey[Unit]("Check munit_3 is in the manifest ")
val ignoreTestConfig = taskKey[StateTransform]("Ignore the test config in the submit input")
val checkIgnoreTest = taskKey[Unit]("Check scaladoc_3 is absent in the manifest")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    scalaVersion := "3.2.1"
  )
)

Global / ignoreTestConfig := {
  val input = DependencySnapshotInput(None, Vector.empty, ignoredConfigs = Vector("test"), correlator = None)
  StateTransform(state => state.put(githubSnapshotInputKey, input))
}

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.3" % Test,
    checkTest := {
      val manifest = githubDependencyManifest.value.get
      checkDependency(manifest, "org.scalameta:munit_3:1.0.3")(
        expectedRelationship = DependencyRelationship.direct,
        expectedScope = DependencyScope.development,
        expectedConfig = "test"
      )
    },
    checkIgnoreTest := {
      val manifest = githubDependencyManifest.value.get
      val suspicious = manifest.resolved.keys.filter(dep => dep.contains("munit_3"))
      assert(suspicious.isEmpty, s"The manifest should not contain munit_3, found ${suspicious.mkString(", ")}")
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
