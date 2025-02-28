package ch.epfl.scala

trait GithubDependencyGraphPluginCompat {
  val Inc: sbt.Result.Inc.type = sbt.Result.Inc
  val Value: sbt.Result.Value.type = sbt.Result.Value
}
