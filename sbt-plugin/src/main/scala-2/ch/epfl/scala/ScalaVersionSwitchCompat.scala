package ch.epfl.scala

private[scala] object ScalaVersionSwitchCompat {
  // sbt 1 switches every project with the same Scala binary version when it
  // receives an exact version.
  def selector(scalaVersion: String): String = scalaVersion
}
