package ch.epfl.scala

import ch.epfl.scala.githubapi._
import sbt._
import sbt.internal.util.complete._

object AnalyzeDependencyGraphDetailed {

  object Model {
    trait DetailedAnalysisAction {
      def name: String
      def help: String
    }
    object DetailedAnalysisAction {
      case object Get extends DetailedAnalysisAction {
        val name = "get"
        val help = "search for a pattern in the dependencies (requires githubGenerateSnapshot)"
      }
      case object List extends DetailedAnalysisAction {
        val name = "list"
        val help = "list all dependencies matching a pattern (requires githubGenerateSnapshot)"
      }
      val values: Seq[DetailedAnalysisAction] = Seq(Get, List)
      def fromString(str: String): Option[DetailedAnalysisAction] = values.find(_.name == str)
      case class AnalysisParams(action: DetailedAnalysisAction, arg: Option[String])
    }
    case class AnalysisParams(action: DetailedAnalysisAction, arg: Option[String])
  }
  import Model._
  import AnalyzeDependencyGraph.getGithubManifest

  val AnalyzeDependenciesDetailed = "githubDetaileledAnalyzeDependencies"
  val AnalyzeDependenciesUsage: String =
    s"""$AnalyzeDependenciesDetailed [${DetailedAnalysisAction.values.map(_.name).mkString("|")}] [pattern]"""
  val AnalyzeDependenciesDetail: String = s"""Analyze the dependencies based on a search pattern:
  ${DetailedAnalysisAction.values.map(a => s"${a.name}: ${a.help}").mkString("\n  ")}
  """

  private def highlight(string: String, pattern: String): String =
    string.replaceAll(pattern, s"\u001b[32m${pattern}\u001b[0m")

  private def analyzeDependenciesInternal(
      state: State,
      action: DetailedAnalysisAction,
      pattern: String,
      originalPattern: String
  ): Unit = {
    def getDeps(dependencies: Seq[String], pattern: String): Seq[String] =
      dependencies.filter(_.contains(pattern)).map(highlight(_, originalPattern))

    def blue(str: String): String = s"\u001b[34m${str}\u001b[0m"

    def resolvedDeps(
        tabs: String,
        acc: Seq[String],
        resolvedByName: Map[String, DependencyNode],
        pattern: String,
        originalPattern: String
    ): Seq[String] =
      acc ++ resolvedByName.toSeq.flatMap {
        case (name, resolved) =>
          val matchingDependencies = getDeps(resolved.dependencies, pattern)
          if (matchingDependencies.isEmpty) {
            if (name.contains(pattern)) Seq(tabs + highlight(name, originalPattern)) else Nil
          } else {
            matchingDependencies.flatMap { matchingDependency =>
              resolvedDeps("  " + tabs, acc :+ (tabs + matchingDependency), resolvedByName, name, originalPattern)
            }
          }
      }

    val matches = getGithubManifest(state)
      .flatMap { manifests =>
        manifests.map {
          case (name, manifest) =>
            manifest -> resolvedDeps("", Nil, manifest.resolved, pattern, originalPattern = pattern)
        }
      }
      .toMap

    action match {
      case DetailedAnalysisAction.Get =>
        matches.foreach {
          case (manifest, deps) =>
            println(s"📁 ${blue(manifest.name)}")
            println(deps.map(dep => s"  $dep").mkString("\n"))
        }
      case DetailedAnalysisAction.List =>
        println(matches.values.flatten.filter(_.contains(pattern)).toSet.mkString("\n"))
    }
  }

  private def extractPattern(state: State): Parser[AnalysisParams] =
    Parsers.any.*.map { raw =>
      raw.mkString.trim.split(" ").toSeq match {
        case Seq(action, arg) => AnalysisParams(DetailedAnalysisAction.fromString(action).get, Some(arg))
        case Seq(action)      => AnalysisParams(DetailedAnalysisAction.fromString(action).get, None)
      }
    }.failOnException

  val commands: Seq[Command] = Seq(
    Command(AnalyzeDependenciesDetailed, (AnalyzeDependenciesUsage, AnalyzeDependenciesDetail), AnalyzeDependenciesDetail)(
      extractPattern
    )(analyzeDependencies)
  )

  private def analyzeDependencies(state: State, params: AnalysisParams): State = {
    params.arg.foreach(pattern => analyzeDependenciesInternal(state, params.action, pattern, pattern))
    state
  }
}
