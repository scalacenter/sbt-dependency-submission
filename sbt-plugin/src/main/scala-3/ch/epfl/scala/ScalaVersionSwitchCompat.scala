package ch.epfl.scala

import sbt.CrossVersion

private[scala] object ScalaVersionSwitchCompat {
  // sbt 2 switches only projects whose crossScalaVersions match its semantic
  // selector. Select the binary series so project dependencies using another
  // compatible compiler version are switched as well.
  def selector(scalaVersion: String): String =
    s"${CrossVersion.binaryScalaVersion(scalaVersion)}.x"
}
