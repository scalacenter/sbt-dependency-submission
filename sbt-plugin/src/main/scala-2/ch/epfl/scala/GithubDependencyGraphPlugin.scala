package ch.epfl.scala

trait GithubDependencyGraphPluginCompat {
  val Inc: sbt.Inc.type = sbt.Inc
  val Value: sbt.Value.type = sbt.Value
}
