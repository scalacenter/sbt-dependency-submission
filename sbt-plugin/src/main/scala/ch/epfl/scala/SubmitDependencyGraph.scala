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

  private val GenerateInternal = s"${Generate}Internal"
  private val InternalOnly = "internal usage only"

  val Submit = "githubSubmitSnapshot"
  private val SubmitDetail = "Submit the dependency graph to Github Dependency API."

  val commands: Seq[Command] = Seq(
    Command(Generate, (GenerateUsage, GenerateDetail), GenerateDetail)(inputParser)(generate),
    Command.command(GenerateInternal, InternalOnly, InternalOnly)(generateInternal),
    Command.command(Submit, SubmitDetail, SubmitDetail)(submit)
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  private def inputParser(state: State): Parser[DependencySnapshotInput] =
    Parsers.any.*.map { raw =>
      val rawString = raw.mkString
      if (rawString.isEmpty) DependencySnapshotInput(None, Vector.empty, Vector.empty)
      else JsonParser
        .parseFromString(rawString)
        .flatMap(Converter.fromJson[DependencySnapshotInput])
        .get
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
