package ch.epfl.scala

import java.nio.file.Paths
import java.time.Instant

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Properties
import scala.util.Try

import ch.epfl.scala.GithubDependencyGraphPlugin.autoImport._
import ch.epfl.scala.JsonProtocol._
import ch.epfl.scala.githubapi.JsonProtocol._
import ch.epfl.scala.githubapi._
import gigahorse.FullResponse
import gigahorse.HttpClient
import gigahorse.support.asynchttpclient.Gigahorse
import sbt._
import sbt.internal.util.complete._
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.{Parser => JsonParser, _}

object SubmitDependencyGraph {
  val Generate = "githubGenerateSnapshot"
  private val GenerateUsage = s"""$Generate {"ignoredModules":[], "ignoredConfig":[]}"""
  private val GenerateDetail = "Generate the dependency graph of a set of projects and scala versions"
  
  val AnalyzeDependecies = "githubAnalyzeDependencies"
  private val AnalyzeDependenciesUsage = s"""$AnalyzeDependecies [get|list] pattern"""
  private val AnalyzeDependenciesDetail = "Analyze the dependencies base on a search pattern"

  private val GenerateInternal = s"${Generate}Internal"
  private val InternalOnly = "internal usage only"

  val Submit = "githubSubmitSnapshot"
  private val SubmitDetail = "Submit the dependency graph to Github Dependency API."

  val commands: Seq[Command] = Seq(
    Command(Generate, (GenerateUsage, GenerateDetail), GenerateDetail)(inputParser)(generate),
    Command(AnalyzeDependecies, (AnalyzeDependenciesUsage, AnalyzeDependenciesDetail), AnalyzeDependenciesDetail)(extractPattern)(analyzeDependencies),
    Command.command(GenerateInternal, InternalOnly, InternalOnly)(generateInternal),
    Command.command(Submit, SubmitDetail, SubmitDetail)(submit)
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  private def inputParser(state: State): Parser[DependencySnapshotInput] =
    Parsers.any.*.map { raw =>
      val rawString = raw.mkString
      if (rawString.isEmpty) DependencySnapshotInput(None, Vector.empty, Vector.empty)
      else
        JsonParser
          .parseFromString(rawString)
          .flatMap(Converter.fromJson[DependencySnapshotInput])
          .get
    }.failOnException

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
    val values: Seq[AnalysisAction] = Seq(Get, List, Alerts)
    def fromString(str: String): Option[AnalysisAction] = values.find(_.name == str)
  }


  case class AnalysisParams(action: AnalysisAction, arg: Option[String])

  private def extractPattern(state: State): Parser[AnalysisParams] =
    Parsers.any.*.map { raw =>
      raw.mkString.trim.split(" ").toSeq match {
      case Seq(action, arg) =>
        AnalysisParams(AnalysisAction.fromString(action).get, Some(arg))
      }
    }.failOnException


  private def generate(state: State, input: DependencySnapshotInput): State = {
    val loadedBuild = state.setting(Keys.loadedBuild)
    // all project refs that have a Scala version
    val projectRefs = loadedBuild.allProjectRefs
      .map(_._1)
      .filter(ref => state.getSetting(ref / Keys.scalaVersion).isDefined)
    // all cross scala versions of those projects
    val scalaVersions = projectRefs
      .flatMap(projectRef => state.setting(projectRef / Keys.crossScalaVersions))
      .distinct

    val root = Paths.get(loadedBuild.root).toAbsolutePath
    val workspace = Paths.get(githubWorkspace()).toAbsolutePath
    val buildFile =
      if (root.startsWith(workspace)) workspace.relativize(root).resolve("build.sbt")
      else root.resolve("build.sbt")
    state.log.info(s"Resolving snapshot of $buildFile")

    val initState = state
      .put(githubSnapshotInputKey, input)
      .put(githubBuildFile, githubapi.FileInfo(buildFile.toString))
      .put(githubManifestsKey, Map.empty[String, Manifest])
      .put(githubProjectsKey, projectRefs)

    val storeAllManifests = scalaVersions.flatMap { scalaVersion =>
      Seq(s"++$scalaVersion", s"Global/${githubStoreDependencyManifests.key} $scalaVersion")
    }
    val commands = storeAllManifests :+ GenerateInternal
    commands.toList ::: initState
  }

  private def analyzeDependenciesInternal(state: State, action: AnalysisAction, pattern: String) = {
      def getDeps(dependencies: Seq[String], pattern: String): Seq[String] = {
        for {
          dep <- dependencies.filter(_.contains(pattern))
        } yield dep
      }

      def resolvedDeps(tabs: String, acc: Seq[String], resolvedByName: Map[String, DependencyNode], pattern: String): Seq[String] = {
        acc ++ (for {
          (name, resolved) <- resolvedByName.toSeq
          matchingDependency <- getDeps(resolved.dependencies, pattern)
          resolvedDep <- resolvedDeps("  " + tabs, acc ++ Seq(tabs + matchingDependency), resolvedByName, name)
        } yield resolvedDep)
      }

      val matches = (for {
        manifests <- state.get(githubManifestsKey).toSeq
        (_, manifest) <- manifests
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

  private def getGithubTokenFromGhConfigDir(): String = {
    // use GH_CONFIG_DIR variable if it exists
    val ghConfigDir = Properties.envOrElse("GH_CONFIG_DIR", Paths.get(System.getProperty("user.home"), ".config", "gh").toString)
    val ghConfigFile = Paths.get(ghConfigDir).resolve("hosts.yml").toFile
    if (ghConfigFile.exists()) {
      val lines = IO.readLines(ghConfigFile)
      val tokenLine = lines.find(_.contains("oauth_token"))
      tokenLine match {
        case Some(line) => line.split(":").last.trim
        case None => throw new MessageOnlyException("No token found in gh config file")
      }
    } else {
      throw new MessageOnlyException("No gh config file found")
    }
  }

  private def downloadAlerts(state: State, repo: String) = {
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
        alerts <- getAlerts(httpResp)
      } yield {
        println(httpResp.bodyAsString)
      }
  }

  private def analyzeDependencies(state: State, params: AnalysisParams): State = {
    val action = params.action
    if (Seq(AnalysisAction.Get, AnalysisAction.List).contains(action)) {
      params.arg.foreach { pattern => analyzeDependenciesInternal(state, action, pattern) }
    } else if (action == AnalysisAction.Alerts) {
      params.arg.foreach { repo =>
        downloadAlerts(state, repo)
      }
    }
    state
  }

  private def generateInternal(state: State): State = {
    val snapshot = githubDependencySnapshot(state)
    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))
    val snapshotJsonFile = IO.withTemporaryFile("dependency-snapshot-", ".json", keepFile = true) { file =>
      IO.write(file, snapshotJson)
      state.log.info(s"Dependency snapshot written to ${file.getAbsolutePath}")
      file
    }
    setGithubOutputs("snapshot-json-path" -> snapshotJsonFile.getAbsolutePath)
    state.put(githubSnapshotFileKey, snapshotJsonFile)
  }

  def submit(state: State): State = {
    checkGithubEnv() // fail if the Github CI environment
    val snapshotJsonFile = state
      .get(githubSnapshotFileKey)
      .getOrElse(
        throw new MessageOnlyException(
          "Missing snapshot file. This command must execute after the githubGenerateSnapshot command"
        )
      )
    val snapshotUrl = s"${githubApiUrl()}/repos/${githubRepository()}/dependency-graph/snapshots"
    val job = githubJob()
    val request = Gigahorse
      .url(snapshotUrl)
      .post(snapshotJsonFile)
      .addHeaders(
        "Content-Type" -> "application/json",
        "Authorization" -> s"token ${githubToken()}"
      )

    state.log.info(s"Submitting dependency snapshot of job $job to $snapshotUrl")
    val result = for {
      httpResp <- Try(Await.result(http.processFull(request), Duration.Inf))
      snapshot <- getSnapshot(httpResp)
    } yield {
      state.log.info(s"Submitted successfully as $snapshotUrl/${snapshot.id}")
      setGithubOutputs(
        "submission-id" -> s"${snapshot.id}",
        "submission-api-url" -> s"${snapshotUrl}/${snapshot.id}"
      )
      state
    }

    result.get
  }

  // https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#setting-an-output-parameter
  private def setGithubOutputs(outputs: (String, String)*): Unit =
    for (output <- githubOutput())
      IO.writeLines(output, outputs.map { case (name, value) => s"${name}=${value}" }, append = true)

  case class Vulnerability(
      packageId: String,
      vulnerableVersionRange: String,
      firstPatchedVersion: String,
      severity: String,
  )

  private def getVulnerabilities(httpResp: FullResponse): Try[Seq[Vulnerability]] =
    httpResp.status match {
      case status if status / 100 == 2 =>
        // here is the jq command:
        // jq -r '.[]|select((.state == "open"))|.security_vulnerability|"\(.package.name);\(.vulnerable_version_range);\(.first_patched_version.identifier);\(.severity)"' | sort
        // do the equivalent in scala and build a seq of Vulnerability, without a converter
        val json = JsonParser.parseFromByteBuffer(httpResp.bodyAsByteBuffer).get


        // fix line below, because "value asArray is not a member of sjsonnew.shaded.scalajson.ast.unsafe.JValue"
        // val vulnerabilities = json.asArray.get.value.map { value =>
        val vulnerabilities = json.asArray.get.value.map { value =>
          val obj = value.asObject.get
          val securityVulnerability = obj("security_vulnerability").get.asObject.get
          Vulnerability(
            securityVulnerability("package").get.asObject.get("name").get.asString.get,
            securityVulnerability("vulnerable_version_range").get.asString.get,
            securityVulnerability("first_patched_version").get.asObject.get("identifier").get.asString.get,
            securityVulnerability("severity").get.asString.get
          )
        }
      case status =>
        val message =
          s"Unexpected status $status ${httpResp.statusText} with body:\n${httpResp.bodyAsString}"
        throw new MessageOnlyException(message)
    }

  private def getSnapshot(httpResp: FullResponse): Try[SnapshotResponse] =
    httpResp.status match {
      case status if status / 100 == 2 =>
        JsonParser
          .parseFromByteBuffer(httpResp.bodyAsByteBuffer)
          .flatMap(Converter.fromJson[SnapshotResponse])
      case status =>
        val message =
          s"Unexpected status $status ${httpResp.statusText} with body:\n${httpResp.bodyAsString}"
        throw new MessageOnlyException(message)
    }

  private def githubDependencySnapshot(state: State): DependencySnapshot = {
    val detector = DetectorMetadata(
      SbtGithubDependencySubmission.name,
      SbtGithubDependencySubmission.homepage.map(_.toString).getOrElse(""),
      SbtGithubDependencySubmission.version
    )
    val scanned = Instant.now
    val manifests = state.get(githubManifestsKey).get
    DependencySnapshot(
      0,
      githubJob(),
      githubSha(),
      githubRef(),
      detector,
      Map.empty[String, JValue],
      manifests,
      scanned.toString
    )
  }

  private def githubJob(): Job = {
    val correlator = s"${githubWorkflow()}_${githubJobName()}_${githubAction()}"
    val id = githubRunId
    val html_url =
      for {
        serverUrl <- Properties.envOrNone("GITHUB_SERVER_URL")
        repository <- Properties.envOrNone("GITHUB_REPOSITORY")
      } yield s"$serverUrl/$repository/actions/runs/$id"
    Job(correlator, id, html_url)
  }

  private def checkGithubEnv(): Unit = {
    def check(name: String): Unit = Properties.envOrNone(name).orElse {
      throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }
    check("GITHUB_WORKSPACE")
    check("GITHUB_WORKFLOW")
    check("GITHUB_JOB")
    check("GITHUB_ACTION")
    check("GITHUB_RUN_ID")
    check("GITHUB_SHA")
    check("GITHUB_REF")
    check("GITHUB_API_URL")
    check("GITHUB_REPOSITORY")
    check("GITHUB_TOKEN")
    check("GITHUB_OUTPUT")
  }

  private def githubWorkspace(): String = Properties.envOrElse("GITHUB_WORKSPACE", "")
  private def githubWorkflow(): String = Properties.envOrElse("GITHUB_WORKFLOW", "")
  private def githubJobName(): String = Properties.envOrElse("GITHUB_JOB", "")
  private def githubAction(): String = Properties.envOrElse("GITHUB_ACTION", "")
  private def githubRunId(): String = Properties.envOrElse("GITHUB_RUN_ID", "")
  private def githubSha(): String = Properties.envOrElse("GITHUB_SHA", "")
  private def githubRef(): String = Properties.envOrElse("GITHUB_REF", "")

  private def githubApiUrl(): String = Properties.envOrElse("GITHUB_API_URL", "")
  private def githubRepository(): String = Properties.envOrElse("GITHUB_REPOSITORY", "")
  private def githubToken(): String = Properties.envOrElse("GITHUB_TOKEN", "")
  private def githubOutput(): Option[File] = Properties.envOrNone("GITHUB_OUTPUT").map(file)
}
