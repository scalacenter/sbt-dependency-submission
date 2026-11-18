package ch.epfl.scala

import sbt.SettingKey

trait GithubDependencyGraphPluginKeysCompat {
  final val internalGithubDependencyGraphUseCoursier: SettingKey[Boolean] =
    sbt.settingKey[Boolean]("Shim for useCoursier key in sbt 1.x")
}
