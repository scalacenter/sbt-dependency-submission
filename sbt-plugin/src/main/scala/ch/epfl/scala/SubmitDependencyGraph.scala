package ch.epfl.scala

import java.nio.charset.StandardCharsets
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
  val Submit = "githubSubmitDependencyGraph"
  val Generate = "generateDependencyGraph"
  val usage: String = s"""$Submit {"projects":[], "scalaVersions":[]}"""
  val brief = "Submit the dependency graph to Github Dependency API."
  val detail = "Submit the dependency graph of a set of projects and scala versions to Github Dependency API"
  val briefGenerate = "Generate the dependency graph"
  val detailGenerate = "Generate the dependency graph of a set of projects and scala versions"

  val SubmitInternal: String = s"${Submit}Internal"
  val SubmitInternalLocal: String = s"${Submit}InternalLocal"
  val internalOnly = "internal usage only"

  val commands: Seq[Command] = Seq(
    Command(Submit, (usage, brief), detail)(inputParser)(submit(false)),
    Command(Generate, (usage, briefGenerate), detailGenerate)(inputParser)(submit(true)),
    Command.command(SubmitInternal, internalOnly, internalOnly)(submitInternal(false)),
    Command.command(SubmitInternalLocal, internalOnly, internalOnly)(submitInternal(true))
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  private def inputParser(state: State): Parser[SubmitInput] =
    Parsers.any.*.map { raw =>
      JsonParser
        .parseFromString(raw.mkString)
        .flatMap(Converter.fromJson[SubmitInput])
        .get
    }.failOnException

  private def submit(local: Boolean)(state: State, input: SubmitInput): State = {
    checkGithubEnv(local) // fail fast if the Github CI environment is incomplete
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
    val workspace = Paths.get(githubWorkspace(local)).toAbsolutePath
    val buildFile =
      if (root.startsWith(workspace)) workspace.relativize(root).resolve("build.sbt")
      else root.resolve("build.sbt")
    state.log.info(s"Resolving snapshot of $buildFile")

    val initState = state
      .put(githubSubmitInputKey, input)
      .put(githubBuildFile, githubapi.FileInfo(buildFile.toString))
      .put(githubManifestsKey, Map.empty[String, Manifest])
      .put(githubProjectsKey, projectRefs)

    val storeAllManifests = scalaVersions.flatMap { scalaVersion =>
      Seq(s"++$scalaVersion", s"Global/${githubStoreDependencyManifests.key} $scalaVersion")
    }
    val commands = storeAllManifests :+ {
      if (local) { SubmitInternalLocal }
      else { SubmitInternal }
    }
    commands.toList ::: initState
  }

  private def submitInternal(local: Boolean)(state: State): State = {
    val snapshot = githubDependencySnapshot(local)(state)
    val snapshotUrl = s"${githubApiUrl(local)}/repos/${githubRepository(local)}/dependency-graph/snapshots"

    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))

    val snapshotJsonFile = IO.withTemporaryFile("dependency-snapshot-", ".json", keepFile = true) { file =>
      IO.write(file, snapshotJson)
      state.log.info(s"Dependency snapshot written to ${file.getAbsolutePath}")
      file
    }

    if (!local) {

      val request = Gigahorse
        .url(snapshotUrl)
        .post(snapshotJson, StandardCharsets.UTF_8)
        .addHeaders(
          "Content-Type" -> "application/json",
          "Authorization" -> s"token ${githubToken()}"
        )
      state.log.info(s"Submiting dependency snapshot of job ${snapshot.job} to $snapshotUrl")
      val result = for {
        httpResp <- Try(Await.result(http.processFull(request), Duration.Inf))
        snapshot <- getSnapshot(httpResp)
      } yield {
        state.log.info(s"Submitted successfully as $snapshotUrl/${snapshot.id}")
        setGithubOutputs(
          "submission-id" -> s"${snapshot.id}",
          "submission-api-url" -> s"${snapshotUrl}/${snapshot.id}",
          "snapshot-json-path" -> snapshotJsonFile.getAbsolutePath
        )
        state
      }
      result.get
    } else {
      state.log.info(s"Local mode: skipping submission")
      state
    }

  }

  // https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#setting-an-output-parameter
  private def setGithubOutputs(outputs: (String, String)*): Unit = IO.writeLines(
    file(githubOutput(false)),
    outputs.toSeq.map { case (name, value) => s"${name}=${value}" },
    append = true
  )

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

  private def githubDependencySnapshot(local: Boolean)(state: State): DependencySnapshot = {
    val detector = DetectorMetadata(
      SbtGithubDependencySubmission.name,
      SbtGithubDependencySubmission.homepage.map(_.toString).getOrElse(""),
      SbtGithubDependencySubmission.version
    )
    val scanned = Instant.now
    val manifests = state.get(githubManifestsKey).get
    DependencySnapshot(
      0,
      githubJob(local),
      githubSha(local),
      githubRef(local),
      detector,
      Map.empty[String, JValue],
      manifests,
      scanned.toString
    )
  }

  private def githubJob(local: Boolean): Job = {
    val correlator = s"${githubWorkflow(local)}_${githubJobName(local)}_${githubAction(local)}"
    val id = githubRunId(local)
    val html_url =
      for {
        serverUrl <- Properties.envOrNone("GITHUB_SERVER_URL")
        repository <- Properties.envOrNone("GITHUB_REPOSITORY")
      } yield s"$serverUrl/$repository/actions/runs/$id"
    Job(correlator, id, html_url)
  }

  private def checkGithubEnv(local: Boolean = false): Unit = {
    githubWorkspace(local)
    githubWorkflow(local)
    githubJobName(local)
    githubAction(local)
    githubRunId(local)
    githubSha(local)
    githubRef(local)
    githubApiUrl(local)
    githubRepository(local)
    githubToken(local)
  }

  private def githubWorkspace(local: Boolean = false): String = githubCIEnv("GITHUB_WORKSPACE", local)
  private def githubWorkflow(local: Boolean = false): String = githubCIEnv("GITHUB_WORKFLOW", local)
  private def githubJobName(local: Boolean = false): String = githubCIEnv("GITHUB_JOB", local)
  private def githubAction(local: Boolean = false): String = githubCIEnv("GITHUB_ACTION", local)
  private def githubRunId(local: Boolean = false): String = githubCIEnv("GITHUB_RUN_ID", local)
  private def githubSha(local: Boolean = false): String = githubCIEnv("GITHUB_SHA", local)
  private def githubRef(local: Boolean = false): String = githubCIEnv("GITHUB_REF", local)
  private def githubApiUrl(local: Boolean = false): String = githubCIEnv("GITHUB_API_URL", local)
  private def githubRepository(local: Boolean = false): String = githubCIEnv("GITHUB_REPOSITORY", local)
  private def githubToken(local: Boolean = false): String = githubCIEnv("GITHUB_TOKEN", local)
  private def githubOutput(local: Boolean = false): String = githubCIEnv("GITHUB_OUTPUT", local)

  private def githubCIEnv(name: String, local: Boolean = false): String =
    Properties.envOrNone(name).getOrElse {
      if (local) ""
      else throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }
}
