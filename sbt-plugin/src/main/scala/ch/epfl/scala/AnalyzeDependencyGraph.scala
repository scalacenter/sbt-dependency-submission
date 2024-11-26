package ch.epfl.scala

import java.nio.file.Paths

import scala.Console
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.sys.process._
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try

import ch.epfl.scala.GithubDependencyGraphPlugin.autoImport._
import ch.epfl.scala.githubapi._
import gigahorse.FullResponse
import gigahorse.HttpClient
import gigahorse.support.asynchttpclient.Gigahorse
import sbt._
import sbt.internal.util.complete._
import sjsonnew.shaded.scalajson.ast.unsafe.JArray
import sjsonnew.shaded.scalajson.ast.unsafe.JField
import sjsonnew.shaded.scalajson.ast.unsafe.JObject
import sjsonnew.shaded.scalajson.ast.unsafe.JString
import sjsonnew.support.scalajson.unsafe.{Parser => JsonParser}

object AnalyzeDependencyGraph {

  val help =
    "download and display CVEs alerts from Github, and analyze them against dependencies (use hub or gh local config or GIT_TOKEN env var to authenticate, requires githubGenerateSnapshot)"

  case class AnalysisParams(repository: Option[String])

  val AnalyzeDependencies = "githubAnalyzeDependencies"
  private val AnalyzeDependenciesUsage =
    s"""$AnalyzeDependencies [pattern]"""
  private val AnalyzeDependenciesDetail = s"""Analyze the dependencies based on a search pattern:
  $help
  """

  val commands: Seq[Command] = Seq(
    Command(AnalyzeDependencies, (AnalyzeDependenciesUsage, AnalyzeDependenciesDetail), AnalyzeDependenciesDetail)(
      parser
    )(analyzeDependencies)
  )

  private def parser(state: State): Parser[AnalysisParams] =
    Parsers.any.*.map { raw =>
      raw.mkString.trim.split(" ").toSeq match {
        case Seq("") | Nil => AnalysisParams(None)
        case Seq(arg)      => AnalysisParams(Some(arg))
      }
    }.failOnException

  private def analyzeDependencies(state: State, params: AnalysisParams): State = {
    for {
      repo <- params.repository.orElse(getGitHubRepo)
      vulnerabilities <- downloadAlerts(state, repo) match {
        case Success(v) => Some(v)
        case Failure(e) =>
          state.log.error(s"Failed to download alerts: ${e.getMessage}")
          None
      }
    } yield analyzeCves(state, vulnerabilities)
    state
  }

  private def analyzeCves(state: State, vulnerabilities: Seq[Vulnerability]): Unit = {
    val artifacts = getAllArtifacts(state)
    vulnerabilities.foreach { v =>
      val (goodMatches, badMatches) = vulnerabilityMatchesArtifacts(v, artifacts)
      println(v.toString)
      if (goodMatches.nonEmpty || badMatches.nonEmpty) {
        goodMatches.foreach(m => println(s"    🟢 ${m.replaceAll(".*@", "")}"))
        badMatches.foreach(m => println(s"    🔴 ${m.replaceAll(".*@", "")}"))
      } else {
        println("    🎉 no match (dependency was probably removed)")
      }
    }
  }

  private def getStateOrWarn[T](state: State, key: AttributeKey[T], what: String, command: String): Option[T] =
    state.get(key).orElse {
      println(s"🟠 No $what found, please run '$command' first")
      None
    }

  private def downloadAlerts(state: State, repo: String): Try[Seq[Vulnerability]] = {
    val snapshotUrl = s"https://api.github.com/repos/$repo/dependabot/alerts"
    val request =
      Gigahorse.url(snapshotUrl).get.addHeaders("Authorization" -> s"token ${getGithubToken()}")
    state.log.info(s"Downloading alerts from $snapshotUrl")
    for {
      httpResp <- Try(Await.result(http.processFull(request), Duration.Inf))
      vulnerabilities <- getVulnerabilities(httpResp)
    } yield {
      state.log.info(s"Downloaded ${vulnerabilities.size} alerts")
      vulnerabilities
    }
  }

  case class Vulnerability(
      packageId: String,
      vulnerableVersionRange: String,
      firstPatchedVersion: String,
      severity: String
  ) {
    def severityColor: String = severity match {
      case "critical" => Console.RED
      case "high"     => Console.RED
      case "medium"   => Console.YELLOW
      case "low"      => Console.GREEN
      case _          => Console.RESET
    }

    def coloredSeverity: String = s"${severityColor}${severity}${Console.RESET}"

    def coloredPackageId: String = s"${Console.BLUE}$packageId${Console.RESET}"

    override def toString: String =
      s"${coloredPackageId} [ $vulnerableVersionRange ] fixed: $firstPatchedVersion $coloredSeverity"
  }

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  def getGithubManifest(state: State): Seq[Map[String, Manifest]] =
    getStateOrWarn(state, githubManifestsKey, "dependencies", SubmitDependencyGraph.Generate).toSeq

  private def getGithubTokenFromFile(ghConfigFile: File): Option[String] = {
    println(s"Extract token from ${ghConfigFile.getPath}")
    if (ghConfigFile.exists()) {
      IO.readLines(ghConfigFile).find(_.contains("oauth_token")).map(_.split(":").last.trim)
    } else None
  }

  private def getGithubToken(): String = {
    val ghConfigDir =
      Properties.envOrElse("GH_CONFIG_DIR", Paths.get(System.getProperty("user.home"), ".config", "gh").toString)
    val ghConfigFile = Paths.get(ghConfigDir).resolve("hosts.yml").toFile
    getGithubTokenFromFile(ghConfigFile).getOrElse {
      val ghConfigPath =
        Properties.envOrElse("HUB_CONFIG", Paths.get(System.getProperty("user.home"), ".config", "hub").toString)
      val hubConfigFile = Paths.get(ghConfigPath).toFile
      getGithubTokenFromFile(hubConfigFile).getOrElse(githubToken())
    }
  }

  private def getAllArtifacts(state: State): Seq[String] =
    getGithubManifest(state).flatMap { manifests =>
      manifests.flatMap {
        case (_, manifest) =>
          manifest.resolved.values.toSeq.map(_.package_url)
      }
    }.distinct

  private def translateToSemVer(string: String): String =
    string.replaceAll("([a-zA-Z]+)", "0").replaceAll("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)", "$1.$2.$3-$4")

  private def versionMatchesRange(versionStr: String, rangeStr: String): Boolean = {
    val range = rangeStr.replaceAll(" ", "").replace(",", " ")
    VersionNumber(translateToSemVer(versionStr)).matchesSemVer(SemanticSelector(translateToSemVer(range)))
  }

  private def vulnerabilityMatchesArtifacts(
      alert: Vulnerability,
      artifacts: Seq[String]
  ): (Seq[String], Seq[String]) = {
    val alertMavenPath = s"pkg:maven/${alert.packageId.replace(":", "/")}@"
    artifacts
      .filter(_.startsWith(alertMavenPath))
      .partition { artifact =>
        val version = artifact.replaceAll(".*@", "")
        versionMatchesRange(version, alert.vulnerableVersionRange)
      }
  }

  def getGitHubRepo: Option[String] = {
    val remoteUrl = "git config --get remote.origin.url".!!.trim
    val repoPattern = """(?:https://|git@)github\.com[:/](.+/.+)\.git""".r
    remoteUrl match {
      case repoPattern(repo) => Some(repo)
      case _                 => None
    }
  }

  private def getVulnerabilities(httpResp: FullResponse): Try[Seq[Vulnerability]] = Try {
    httpResp.status match {
      case status if status / 100 == 2 =>
        val json: JArray = JsonParser.parseFromByteBuffer(httpResp.bodyAsByteBuffer).get.asInstanceOf[JArray]
        json.value.collect {
          case obj: JObject if obj.value.collectFirst { case JField("state", JString("open")) => true }.isDefined =>
            val securityVulnerability =
              obj.value.collectFirst { case JField("security_vulnerability", secVuln: JObject) => secVuln }.get.value
            val packageObj =
              securityVulnerability.collectFirst { case JField("package", pkg: JObject) => pkg }.get.value
            val firstPatchedVersion = securityVulnerability
              .collectFirst { case JField("first_patched_version", firstPatched: JObject) => firstPatched }
              .map(_.value.collectFirst { case JField("identifier", JString(ident)) => ident }.getOrElse(""))
              .getOrElse("")
            Vulnerability(
              packageObj.collectFirst { case JField("name", JString(name)) => name }.get,
              securityVulnerability.collectFirst {
                case JField("vulnerable_version_range", JString(range)) => range
              }.get,
              firstPatchedVersion,
              securityVulnerability.collectFirst { case JField("severity", JString(sev)) => sev }.get
            )
        }
      case _ =>
        val message =
          s"Unexpected status ${httpResp.status} ${httpResp.statusText} with body:\n${httpResp.bodyAsString}"
        throw new MessageOnlyException(message)
    }
  }

  private def githubToken(): String = Properties.envOrElse("GITHUB_TOKEN", "")
}
