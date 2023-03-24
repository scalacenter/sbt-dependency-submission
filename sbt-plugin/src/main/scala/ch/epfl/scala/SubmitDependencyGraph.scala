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
import gigahorse.support.apachehttp.Gigahorse
import sbt._
import sbt.internal.util.complete._
import sjsonnew.shaded.scalajson.ast.unsafe.JValue
import sjsonnew.support.scalajson.unsafe.{Parser => JsonParser, _}

object SubmitDependencyGraph {
  val Submit = "githubSubmitDependencyGraph"
  val usage: String = s"""$Submit {"projects":[], "scalaVersions":[]}"""
  val brief = "Submit the dependency graph to Github Dependency API."
  val detail = "Submit the dependency graph of a set of projects and scala versions to Github Dependency API"

  val SubmitInternal: String = s"${Submit}Internal"
  val internalOnly = "internal usage only"

  val commands: Seq[Command] = Seq(
    Command(Submit, (usage, brief), detail)(inputParser)(submit),
    Command.command(SubmitInternal, internalOnly, internalOnly)(submitInternal)
  )

  private lazy val http: HttpClient = Gigahorse.http(Gigahorse.config)

  private def inputParser(state: State): Parser[SubmitInput] =
    Parsers.any.*.map { raw =>
      JsonParser
        .parseFromString(raw.mkString)
        .flatMap(Converter.fromJson[SubmitInput])
        .get
    }.failOnException

  private def submit(state: State, input: SubmitInput): State = {
    checkGithubEnv() // fail fast if the Github CI environment is incomplete
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
      .put(githubSubmitInputKey, input)
      .put(githubBuildFile, githubapi.FileInfo(buildFile.toString))
      .put(githubManifestsKey, Map.empty[String, Manifest])
      .put(githubProjectsKey, projectRefs)

    val storeAllManifests = scalaVersions.flatMap { scalaVersion =>
      Seq(s"++$scalaVersion", s"Global/${githubStoreDependencyManifests.key} $scalaVersion")
    }
    val commands = storeAllManifests :+ SubmitInternal
    commands.toList ::: initState
  }

  private def submitInternal(state: State): State = {
    val snapshot = githubDependencySnapshot(state)
    val snapshotUrl = s"${githubApiUrl()}/repos/${githubRepository()}/dependency-graph/snapshots"

    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))
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
      state
    }

    result.get
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
    githubWorkspace()
    githubWorkflow()
    githubJobName()
    githubAction()
    githubRunId()
    githubSha()
    githubRef()
    githubApiUrl()
    githubRepository()
    githubToken()
  }

  private def githubWorkspace(): String = githubCIEnv("GITHUB_WORKSPACE")
  private def githubWorkflow(): String = githubCIEnv("GITHUB_WORKFLOW")
  private def githubJobName(): String = githubCIEnv("GITHUB_JOB")
  private def githubAction(): String = githubCIEnv("GITHUB_ACTION")
  private def githubRunId(): String = githubCIEnv("GITHUB_RUN_ID")
  private def githubSha(): String = githubCIEnv("GITHUB_SHA")
  private def githubRef(): String = githubCIEnv("GITHUB_REF")
  private def githubApiUrl(): String = githubCIEnv("GITHUB_API_URL")
  private def githubRepository(): String = githubCIEnv("GITHUB_REPOSITORY")
  private def githubToken(): String = githubCIEnv("GITHUB_TOKEN")

  private def githubCIEnv(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }
}
