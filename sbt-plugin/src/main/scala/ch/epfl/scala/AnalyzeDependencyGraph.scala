package ch.epfl.scala

import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Properties, Try}
import ch.epfl.scala.GithubDependencyGraphPlugin.autoImport._
import ch.epfl.scala.githubapi._
import gigahorse.support.asynchttpclient.Gigahorse
import sbt._
import sbt.internal.util.complete._
import sjsonnew.shaded.scalajson.ast.unsafe.{JArray, JObject, JField, JString}
import gigahorse.{FullResponse, HttpClient}
import sjsonnew.support.scalajson.unsafe.{Parser => JsonParser}

import scala.sys.process._

object AnalyzeDependencyGraph {

  object Model {
    sealed trait AnalysisAction {
      def name: String
      def help: String
    }

    object AnalysisAction {
      case object Get extends AnalysisAction {
        val name = "get"
        val help = "search for a pattern in the dependencies (requires githubGenerateSnapshot)"
      }
      case object List extends AnalysisAction {
        val name = "list"
        val help = "list all dependencies matching a pattern (requires githubGenerateSnapshot)"
      }
      case object Alerts extends AnalysisAction {
        val name = "alerts"
        val help = "download and display CVEs alerts from Github (use hub or gh local config or GIT_TOKEN env var to authenticate)"
      }
      case object Cves extends AnalysisAction {
        val name = "cves"
        val help = "analyze CVEs alerts against the dependencies (requires githubGenerateSnapshot and githubAnalyzeDependencies alerts)"
      }

      val values: Seq[AnalysisAction] = Seq(Get, List, Alerts, Cves)

      def fromString(str: String): Option[AnalysisAction] = values.find(_.name == str)
    }

    def blue(str: String): String = s"\u001b[34m${str}\u001b[0m"

    case class Vulnerability(
      packageId: String,
      vulnerableVersionRange: String,
      firstPatchedVersion: String,
      severity: String
    ) {
      def severityColor: String = severity match {
        case "critical" => "\u001b[31m"
        case "high"     => "\u001b[31m"
        case "medium"   => "\u001b[33m"
        case "low"      => "\u001b[32m"
        case _          => "\u001b[0m"
      }

      def coloredSeverity: String = s"${severityColor}${severity}\u001b[0m"

      override def toString: String = s"${blue(packageId)} [ $vulnerableVersionRange ] fixed: $firstPatchedVersion $coloredSeverity"
    }

    case class AnalysisParams(action: AnalysisAction, arg: Option[String])

    sealed trait Vulnerable
    object Good extends Vulnerable
    object Bad extends Vulnerable
    object No extends Vulnerable
  }

  import Model._

  val AnalyzeDependencies = "githubAnalyzeDependencies"
  private val AnalyzeDependenciesUsage = s"""$AnalyzeDependencies [${AnalysisAction.values.map(_.name).mkString("|")}] [pattern]"""
  private val AnalyzeDependenciesDetail = s"""Analyze the dependencies based on a search pattern:
  ${AnalysisAction.values.map(a => s"${a.name}: ${a.help}").mkString("\n  ")}
  """

  val commands: Seq[Command] = Seq(
    Command(AnalyzeDependencies, (AnalyzeDependenciesUsage, AnalyzeDependenciesDetail), AnalyzeDependenciesDetail)(extractPattern)(analyzeDependencies)
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  private def extractPattern(state: State): Parser[AnalysisParams] =
    Parsers.any.*.map { raw =>
      raw.mkString.trim.split(" ").toSeq match {
        case Seq(action, arg) => AnalysisParams(AnalysisAction.fromString(action).get, Some(arg))
        case Seq(action)      => AnalysisParams(AnalysisAction.fromString(action).get, None)
      }
    }.failOnException

  private def highlight(string: String, pattern: String): String = 
    string.replaceAll(pattern, s"\u001b[32m${pattern}\u001b[0m")

  private def analyzeDependenciesInternal(state: State, action: AnalysisAction, pattern: String, originalPattern: String): Unit = {
    def getDeps(dependencies: Seq[String], pattern: String): Seq[String] = 
      dependencies.filter(_.contains(pattern)).map(highlight(_, originalPattern))

    def resolvedDeps(tabs: String, acc: Seq[String], resolvedByName: Map[String, DependencyNode], pattern: String, originalPattern: String): Seq[String] = {
      acc ++ resolvedByName.toSeq.flatMap { case (name, resolved) =>
        val matchingDependencies = getDeps(resolved.dependencies, pattern)
        if (matchingDependencies.isEmpty) {
          if (name.contains(pattern)) Seq(tabs + highlight(name, originalPattern)) else Nil
        } else {
          matchingDependencies.flatMap { matchingDependency =>
            resolvedDeps("  " + tabs, acc :+ (tabs + matchingDependency), resolvedByName, name, originalPattern)
          }
        }
      }
    }

    val matches = state.get(githubManifestsKey).toSeq.flatMap { manifests =>
      manifests.map { case (name, manifest) =>
        manifest -> resolvedDeps("", Nil, manifest.resolved, pattern, originalPattern = pattern)
      }
    }.toMap

    action match {
      case AnalysisAction.Get =>
        matches.foreach { case (manifest, deps) =>
          println(s"📁 ${blue(manifest.name)}")
          println(deps.map(dep => s"  $dep").mkString("\n"))
        }
      case AnalysisAction.List =>
        println(matches.values.flatten.filter(_.contains(pattern)).toSet.mkString("\n"))
      case _ =>
    }
  }

  private def getGithubToken(ghConfigFile: File): Option[String] = {
    println(s"Extract token from ${ghConfigFile.getPath}")
    if (ghConfigFile.exists()) {
      IO.readLines(ghConfigFile).find(_.contains("oauth_token")).map(_.split(":").last.trim)
    } else None
  }

  private def getGithubTokenFromGhConfigDir(): String = {
    val ghConfigDir = Properties.envOrElse("GH_CONFIG_DIR", Paths.get(System.getProperty("user.home"), ".config", "gh").toString)
    val ghConfigFile = Paths.get(ghConfigDir).resolve("hosts.yml").toFile
    getGithubToken(ghConfigFile).getOrElse {
      val ghConfigPath = Properties.envOrElse("HUB_CONFIG", Paths.get(System.getProperty("user.home"), ".config", "hub").toString)
      val hubConfigFile = Paths.get(ghConfigPath).toFile
      getGithubToken(hubConfigFile).getOrElse(githubToken())
    }
  }

  private def downloadAlerts(state: State, repo: String): Try[State] = {
    val snapshotUrl = s"https://api.github.com/repos/$repo/dependabot/alerts"
    val request = Gigahorse.url(snapshotUrl).get.addHeaders("Authorization" -> s"token ${getGithubTokenFromGhConfigDir()}")
    state.log.info(s"Downloading alerts from $snapshotUrl")
    for {
      httpResp <- Try(Await.result(http.processFull(request), Duration.Inf))
      vulnerabilities <- getVulnerabilities(httpResp)
    } yield {
      vulnerabilities.foreach(v => println(v.toString))
      state.put(githubAlertsKey, vulnerabilities)
    }
  }

  private def getAllArtifacts(state: State): Seq[String] = {
    state.get(githubManifestsKey).toSeq.flatMap { manifests =>
      manifests.flatMap { case (_, manifest) =>
        manifest.resolved.values.toSeq.map(_.package_url)
      }
    }.distinct
  }

  private def translateToSemVer(string: String): String =
    string.replaceAll("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)", "$1.$2.$3-$4")

  private def versionMatchesRange(versionStr: String, rangeStr: String): Boolean = {
    val range = rangeStr.replaceAll(" ", "").replace(",", " ")
    VersionNumber(translateToSemVer(versionStr)).matchesSemVer(SemanticSelector(translateToSemVer(range)))
  }

  private def vulnerabilityMatchesArtifact(alert: Vulnerability, artifact: String): Vulnerable = {
    val alertMavenPath = s"pkg:maven/${alert.packageId.replace(":", "/")}@"
    if (artifact.startsWith(alertMavenPath)) {
      val version = artifact.replaceAll(".*@", "")
      if (versionMatchesRange(version, alert.vulnerableVersionRange)) Bad else Good
    } else No
  }

  private def vulnerabilityMatchesArtifacts(alert: Vulnerability, artifacts: Seq[String]): Map[Vulnerable, Seq[String]] = {
    artifacts.foldLeft(Map(Good -> Seq.empty[String], Bad -> Seq.empty[String])) { (acc, artifact) =>
      val res = vulnerabilityMatchesArtifact(alert, artifact)
      if (res != No) acc.updated(res, acc(res) :+ artifact) else acc
    }
  }

  private def analyzeCves(state: State): State = {
    val vulnerabilities = state.get(githubAlertsKey).getOrElse(Seq.empty)
    val artifacts = getAllArtifacts(state)
    vulnerabilities.foreach { v =>
      val matches = vulnerabilityMatchesArtifacts(v, artifacts)
      println(v.toString)
      if (matches(Good).nonEmpty || matches(Bad).nonEmpty) {
        matches(Good).foreach(m => println(s"    🟢 ${m.replaceAll(".*@", "")}"))
        matches(Bad).foreach(m => println(s"    🔴 ${m.replaceAll(".*@", "")}"))
      } else {
        println("    🎉 no match (dependency was probably removed)")
      }
    }
    state
  }

  def getGitHubRepo: Option[String] = {
    val remoteUrl = "git config --get remote.origin.url".!!.trim
    val repoPattern = """(?:https://|git@)github\.com[:/](.+/.+)\.git""".r
    remoteUrl match {
      case repoPattern(repo) => Some(repo)
      case _ => None
    }
  }

  private def analyzeDependencies(state: State, params: AnalysisParams): State = {
    params.action match {
      case AnalysisAction.Get | AnalysisAction.List =>
        params.arg.foreach(pattern => analyzeDependenciesInternal(state, params.action, pattern, pattern))
        state
      case AnalysisAction.Alerts =>
        params.arg.orElse(getGitHubRepo).map(repo => downloadAlerts(state, repo).get).getOrElse(state)
      case AnalysisAction.Cves =>
        analyzeCves(state)
      case _ =>
        state
    }
  }

  private def getVulnerabilities(httpResp: FullResponse): Try[Seq[Vulnerability]] = Try {
    httpResp.status match {
      case status if status / 100 == 2 =>
        val json: JArray = JsonParser.parseFromByteBuffer(httpResp.bodyAsByteBuffer).get.asInstanceOf[JArray]
        json.value.collect {
          case obj: JObject if (obj.value.collectFirst { case JField("state", JString("open")) => true }.isDefined) =>
            val securityVulnerability = obj.value.collectFirst { case JField("security_vulnerability", secVuln: JObject) => secVuln }.get.value
            val packageObj = securityVulnerability.collectFirst { case JField("package", pkg: JObject) => pkg }.get.value
            val firstPatchedVersion = securityVulnerability.collectFirst {
              case JField("first_patched_version", firstPatched: JObject) => firstPatched
            }.map(_.value.collectFirst { case JField("identifier", JString(ident)) => ident }.getOrElse("")).getOrElse("")
            Vulnerability(
              packageObj.collectFirst { case JField("name", JString(name)) => name }.get,
              securityVulnerability.collectFirst { case JField("vulnerable_version_range", JString(range)) => range }.get,
              firstPatchedVersion,
              securityVulnerability.collectFirst { case JField("severity", JString(sev)) => sev }.get
            )
        }
      case _ =>
        val message = s"Unexpected status ${httpResp.status} ${httpResp.statusText} with body:\n${httpResp.bodyAsString}"
        throw new MessageOnlyException(message)
    }
  }

  private def githubToken(): String = Properties.envOrElse("GITHUB_TOKEN", "") 
}
