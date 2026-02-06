import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.0.0"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      // flink-avro has transitive dependencies that cause version eviction
      // This will pull in different versions of jackson-core and avro
      // which results in some versions being evicted
      "org.apache.flink" % "flink-avro" % "1.18.1",
      "org.apache.avro" % "avro" % "1.9.2"
    ),
    checkManifest := {
      val scalaBinVersion = scalaBinaryVersion.value
      val manifest = githubDependencyManifest.value.get
      assert(manifest.name == s"ch.epfl.scala:p1_$scalaBinVersion:1.0.0")

      assert(manifest.resolved.values.forall(n => n.dependencies.forall(manifest.resolved.contains)),
        "Some dependencies reference non-existent modules")

      checkDependency(manifest, "org.apache.flink:flink-avro:1.18.1")(
        expectedRelationship = DependencyRelationship.direct
      )

      val resolvedKeys = manifest.resolved.keySet

      val moduleVersions = resolvedKeys.map { key =>
        val parts = key.split(":")
        if (parts.length >= 3) {
          // Extract org:name pattern
          s"${parts(0)}:${parts(1)}"
        } else {
          key
        }
      }.groupBy(identity).mapValues(_.size)

      assert(moduleVersions.forall(_._2 == 1), "Each module should have only one version")
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
