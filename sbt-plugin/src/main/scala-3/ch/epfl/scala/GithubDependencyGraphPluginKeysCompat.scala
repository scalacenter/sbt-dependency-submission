package ch.epfl.scala

trait GithubDependencyGraphPluginKeysCompat {
  final val internalGithubDependencyGraphUseCoursier = sbt.settingKey[Boolean]("Shim for useCoursier key in sbt 1.x")
}
