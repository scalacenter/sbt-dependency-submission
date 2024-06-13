package ch.epfl.scala

import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Properties
import scala.util.Try

import ch.epfl.scala.GithubDependencyGraphPlugin.autoImport._
import ch.epfl.scala.githubapi._
import gigahorse.support.asynchttpclient.Gigahorse
import sbt._
import sbt.internal.util.complete._
import sjsonnew.shaded.scalajson.ast.unsafe.{ JValue, JArray, JObject, JField, JString }
import gigahorse.FullResponse
import gigahorse.HttpClient
import gigahorse.support.asynchttpclient.Gigahorse
import sjsonnew.support.scalajson.unsafe.{Parser => JsonParser}

import scala.sys.process._

import sbt._

object AnalyzeDependencyGraph {

  val AnalyzeDependecies = "githubAnalyzeDependencies"
  private val AnalyzeDependenciesUsage = s"""$AnalyzeDependecies [${AnalysisAction.values.map(_.name).mkString("|")}] [pattern]"""
  private val AnalyzeDependenciesDetail = "Analyze the dependencies base on a search pattern"

  val commands: Seq[Command] = Seq(
    Command(AnalyzeDependecies, (AnalyzeDependenciesUsage, AnalyzeDependenciesDetail), AnalyzeDependenciesDetail)(extractPattern)(analyzeDependencies),
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  sealed trait AnalysisAction {
    def name: String
  }
  object AnalysisAction {
    case object Get extends AnalysisAction {
      val name = "get"
    }
    case object List extends AnalysisAction {
      val name = "list"
    }
    case object Alerts extends AnalysisAction {
      val name = "alerts"
    }
    case object Cves extends AnalysisAction {
      val name = "cves"
    }
    val values: Seq[AnalysisAction] = Seq(Get, List, Alerts, Cves)
    def fromString(str: String): Option[AnalysisAction] = values.find(_.name == str)
  }


  case class AnalysisParams(action: AnalysisAction, arg: Option[String])

  private def extractPattern(state: State): Parser[AnalysisParams] =
    Parsers.any.*.map { raw =>
      raw.mkString.trim.split(" ").toSeq match {
      case Seq(action, arg) =>
        AnalysisParams(AnalysisAction.fromString(action).get, Some(arg))
      case Seq(action) =>
        AnalysisParams(AnalysisAction.fromString(action).get, None)
      }
    }.failOnException

  private def analyzeDependenciesInternal(state: State, action: AnalysisAction, pattern: String) = {
      def getDeps(dependencies: Seq[String], pattern: String): Seq[String] = {
        for {
          dep <- dependencies.filter(_.contains(pattern))
        } yield dep
      }

      def resolvedDeps(tabs: String, acc: Seq[String], resolvedByName: Map[String, DependencyNode], pattern: String): Seq[String] = {

        acc ++ (for {
          (name, resolved) <- resolvedByName.toSeq
          matchingDependencies = getDeps(resolved.dependencies, pattern)
          resultDeps <- if (matchingDependencies.isEmpty) {
            if (name.contains(pattern)) {
              Seq(Seq(tabs + name))
            } else {
              Nil
            }
          } else {
            for {
              matchingDependency <- matchingDependencies
            } yield resolvedDeps("  " + tabs, acc ++ Seq(tabs + matchingDependency), resolvedByName, name)
          }
          resultDep <- resultDeps
        } yield {
          resultDep
        })
      }

      val matches = (for {
        manifests <- state.get(githubManifestsKey).toSeq
        (name, manifest) <- manifests
      } yield (manifest, resolvedDeps("", Nil, manifest.resolved, pattern))).toMap

      if (action == AnalysisAction.Get) {
        matches.foreach { case (manifest, deps) =>
          println(s"Manifest: ${manifest.name}")
          println(deps.map{ dep : String => s"  ${dep}" }.mkString("\n"))
        }
      }
      else if (action == AnalysisAction.List) {
        println(
        matches.flatMap { case (manifest, deps) => deps }
          .filter(_.contains(pattern)).toSet.mkString("\n"))
      }
  }

  private def getGithubToken(ghConfigFile: File): Option[String] = {
    println(s"extract token from ${ghConfigFile.getPath()}")
    if (ghConfigFile.exists()) {
      val lines = IO.readLines(ghConfigFile)
      val tokenLine = lines.find(_.contains("oauth_token"))
      tokenLine.map { line => line.split(":").last.trim }
      } else {
        None
      }
  }

  private def getGithubTokenFromGhConfigDir(): String = {
    // use GH_CONFIG_DIR variable if it exists
    val ghConfigDir = Properties.envOrElse("GH_CONFIG_DIR", Paths.get(System.getProperty("user.home"), ".config", "gh").toString)
    val ghConfigFile = Paths.get(ghConfigDir).resolve("hosts.yml").toFile
    getGithubToken(ghConfigFile).getOrElse {
      val ghConfigPath = Properties.envOrElse("HUB_CONFIG", Paths.get(System.getProperty("user.home"), ".config", "hub").toString)
      val hubConfigFile = Paths.get(ghConfigPath).toFile
      getGithubToken(hubConfigFile).getOrElse {
        githubToken()
      }
    }
  }

  private def downloadAlerts(state: State, repo: String) : Try[State] = {
      val snapshotUrl = s"https://api.github.com/repos/$repo/dependabot/alerts"
      val request = Gigahorse
        .url(snapshotUrl)
        .get
        .addHeaders(
          "Authorization" -> s"token ${getGithubTokenFromGhConfigDir()}"
        )
      state.log.info(s"Downloading alerts from $snapshotUrl")
      for {
        httpResp <- Try(Await.result(http.processFull(request), Duration.Inf))
        vulnerabilities <- getVulnerabilities(httpResp)
      } yield {
        vulnerabilities.foreach { v =>
          println(s"${v.packageId} ${v.vulnerableVersionRange} ${v.firstPatchedVersion} ${v.severity}")
        }
        state.put(githubAlertsKey, vulnerabilities)
      }
  }

  private def getAllArtifacts(state: State): Seq[String] = {
  {
    for {
      manifests <- state.get(githubManifestsKey).toSeq
      (_, manifest) <- manifests
      artifact <- manifest.resolved.values.toSeq
    } yield artifact.package_url
  }.toSet.toSeq
  }

 private def translateToSemVer(string: String): String = {
   // if a version in the string has more than 3 digits, we assume it's a pre-release version
   // ">= 1.0 <32.0.0.4" => ">= 1.0 < 32.0.0-4"
   string.replaceAll("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)", "$1.$2.$3-$4")
 }

 // versionMatchesRange("31.1-jre", ">= 1.0, < 32.0.0-android") => true
 // versionMatchesRange("2.8.5", "< 2.9.0") => true
 // versionMatchesRange("2.9.0", "< 2.9.0") => false
 private def versionMatchesRange(versionStr: String, rangeStr: String): Boolean = {
   val range = rangeStr.replaceAll(" ", "").replace(",", " ")
   val result = VersionNumber(translateToSemVer(versionStr)).matchesSemVer(SemanticSelector(translateToSemVer(range)))
   result
 }

 // create an enum with good, bad, no
 
 sealed trait Vulnerable

 object Good extends Vulnerable
 object Bad extends Vulnerable
 object No extends Vulnerable

 private def vulnerabilityMatchesArtifact(alert: Vulnerability, artifact: String): Vulnerable = {
   val alertMavenPath = s"pkg:maven/${alert.packageId.replace(":", "/")}@"
   if (artifact.startsWith(alertMavenPath)) {
     val version = artifact.split("@").last
     // vulnerableVersionRange can be ">= 1.0, < 32.0.0-android" or "< 2.9.0"
     val bad = versionMatchesRange(version, alert.vulnerableVersionRange)
     if (bad) {
       Bad
     } else {
       Good
     }
     } else {
       No
     }
 }

 private def vulnerabilityMatchesArtifacts(alert: Vulnerability, artifacts: Seq[String]): Map[Vulnerable, Seq[String]] = {
   artifacts.foldLeft(Map(Good -> Seq.empty[String], Bad -> Seq.empty[String])) { (acc, artifact) =>
     val res = vulnerabilityMatchesArtifact(alert, artifact)
     if (res != No) {
       acc.updated(res, acc(res) :+ artifact)
     } else {
       acc
     }
   }
 }

  private def analyzeCves(state: State): State = {
    val vulnerabilities = state.get(githubAlertsKey).get
    val cves = vulnerabilities
    val artifacts = getAllArtifacts(state)
    cves.foreach { v =>
      val matches = vulnerabilityMatchesArtifacts(v, artifacts)
      println(s"${v.packageId} ${v.vulnerableVersionRange} ${v.firstPatchedVersion} ${v.severity}")
      if (matches(Good).length + matches(Bad).length > 0) {
        matches(Good).foreach { m =>
          println(s"  🟢 ${m}")
        }
        matches(Bad).foreach { m =>
          println(s"  🔴 ${m}")
        }
      } else {
        println("  🎉 no match (dependency was probably removed)")
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
    val action = params.action
    if (Seq(AnalysisAction.Get, AnalysisAction.List).contains(action)) {
      params.arg.foreach { pattern => analyzeDependenciesInternal(state, action, pattern) }
      state
    } else if (action == AnalysisAction.Alerts) {
      params.arg.orElse(getGitHubRepo).map { repo =>
        downloadAlerts(state, repo).get
      }.get
    } else if (action == AnalysisAction.Cves) {
      analyzeCves(state)
    } else {
      state
    }
  }

  case class Vulnerability(
      packageId: String,
      vulnerableVersionRange: String,
      firstPatchedVersion: String,
      severity: String,
  )

  private def getVulnerabilities(httpResp: FullResponse): Try[Seq[Vulnerability]] =
    httpResp.status match {
      case status if status / 100 == 2 => Try {
        val json : JValue = JsonParser.parseFromByteBuffer(httpResp.bodyAsByteBuffer).get

        json.asInstanceOf[JArray].value.map { value =>
          val obj = value.asInstanceOf[JObject].value

          val map = obj.map { case JField(k, v) => (k, v) }.toMap

          val securityVulnerability = map("security_vulnerability").asInstanceOf[JObject].value.map { case JField(k, v) => (k, v) }.toMap

          val packageObj = securityVulnerability("package").asInstanceOf[JObject].value.map { case JField(k, v) => (k, v) }.toMap

          val firstPatchedVersion = Try(securityVulnerability("first_patched_version").asInstanceOf[JObject].value.map { case JField(k, v) => (k, v) }.toMap).getOrElse(Map.empty)

          (
            map("state") == JString("open"),
            Vulnerability(
            packageObj("name").asInstanceOf[JString].value,
            securityVulnerability("vulnerable_version_range").asInstanceOf[JString].value,
            firstPatchedVersion.get("identifier").map { x => x.asInstanceOf[JString].value }.getOrElse(""),
            securityVulnerability("severity").asInstanceOf[JString].value
          )
        )
        }.filter(_._1).map(_._2)
        }
      case status =>
        val message =
          s"Unexpected status $status ${httpResp.statusText} with body:\n${httpResp.bodyAsString}"
        throw new MessageOnlyException(message)
    }


  private def githubToken(): String = Properties.envOrElse("GITHUB_TOKEN", "")
}
