import ch.epfl.scala.githubapi.DependencyRelationship
import ch.epfl.scala.githubapi.DependencyScope
import ch.epfl.scala.githubapi.Manifest
import sjsonnew.shaded.scalajson.ast.unsafe.JString

val checkManifest = taskKey[Unit]("Check the Github manifest of a project")

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    version := "1.2.0-SNAPSHOT",
    useCoursier := false, // use Ivy
    scalaVersion := "2.13.17"
  )
)

lazy val p1 = project
  .in(file("p1"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-generic" % "0.14.1",
      "org.tpolecat" %% "doobie-core" % "0.13.4",
      "org.scalatest" %% "scalatest" % "3.2.2" % Test
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value.get
      assert(manifest.name == "ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(manifest.resolved.values.forall(n => n.dependencies.forall(manifest.resolved.contains)))

      checkDependency(manifest, "io.circe:circe-generic_2.12:0.14.1")(
        expectedDeps = Seq("com.chuusai:shapeless_2.12:2.3.7")
      )
      checkDependency(manifest, "org.tpolecat:doobie-core_2.12:0.13.4")(
        expectedDeps = Seq("com.chuusai:shapeless_2.12:2.3.7")
      )
      checkDependency(manifest, "com.chuusai:shapeless_2.12:2.3.7")(
        expectedRelationship = DependencyRelationship.indirect
      )
      checkDependency(manifest, "org.scalatest:scalatest_2.12:3.2.2")(
        expectedScope = DependencyScope.development,
        expectedConfig = "test",
        expectedDeps = Seq("org.scalatest:scalatest-core_2.12:3.2.2")
      )
      checkDependency(manifest, "org.scalatest:scalatest-core_2.12:3.2.2")(
        expectedRelationship = DependencyRelationship.indirect,
        expectedScope = DependencyScope.development,
        expectedConfig = "test"
      )
    }
  )

lazy val p2 = project
  .in(file("p2"))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % "10.2.8"
    ),
    checkManifest := {
      val manifest = githubDependencyManifest.value.get
      assert(manifest.name == "ch.epfl.scala:p2_2.12:1.2.0-SNAPSHOT")

      // all dependencies are defined
      assert(manifest.resolved.values.forall(n => n.dependencies.forall(manifest.resolved.contains)))

      checkDependency(manifest, "com.typesafe.akka:akka-http_2.12:10.2.8")()
      checkDependency(manifest, "ch.epfl.scala:p1_2.12:1.2.0-SNAPSHOT")()

      // transitively depends on circe through p1
      checkDependency(manifest, "io.circe:circe-generic_2.12:0.14.1")(
        expectedRelationship = DependencyRelationship.indirect,
        expectedDeps = Seq("com.chuusai:shapeless_2.12:2.3.7")
      )

      // p2 does not depend on scalatest
      assert(manifest.resolved.get("org.scalatest:scalatest_2.12:3.2.2").isEmpty)
    }
  )
  .dependsOn(p1)

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
