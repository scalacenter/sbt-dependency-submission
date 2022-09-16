package ch.epfl.scala

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Properties
import scala.util.Try

import ch.epfl.scala.GithubDependencyGraphPlugin.autoImport._
import ch.epfl.scala.JsonProtocol._
import ch.epfl.scala.githubapi.JsonProtocol._
import ch.epfl.scala.githubapi._
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

    val initState = state
      .put(githubSubmitInputKey, input)
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
    val url = new URL(s"${githubApiUrl()}/repos/${githubRepository()}/dependency-graph/snapshots")

    val snapshotJson = CompactPrinter(Converter.toJsonUnsafe(snapshot))
    val request = Gigahorse
      .url(url.toString)
      .post(snapshotJson, StandardCharsets.UTF_8)
      .addHeaders(
        "Content-Type" -> "application/json",
        "Authorization" -> s"token ${githubToken()}"
      )

    state.log.info(s"Submiting dependency snapshot to $url")
    val result = for {
      httpResp <- Try(Await.result(http.run(request), Duration.Inf))
      jsonResp <- JsonParser.parseFromByteBuffer(httpResp.bodyAsByteBuffer)
      response <- Converter.fromJson[SnapshotResponse](jsonResp)
    } yield new URL(url, response.id.toString)

    result match {
      case scala.util.Success(result) =>
        state.log.info(s"Submitted successfully as $result")
        state
      case scala.util.Failure(cause) =>
        throw new MessageOnlyException(
          s"Failed to submit the dependency snapshot because of ${cause.getClass.getName}: ${cause.getMessage}"
        )
    }
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
    val correlator = s"${githubJobName()}_${githubWorkflow()}"
    val id = githubRunId
    val html_url =
      for {
        serverUrl <- Properties.envOrNone("$GITHUB_SERVER_URL")
        repository <- Properties.envOrNone("GITHUB_REPOSITORY")
      } yield s"$serverUrl/$repository/actions/runs/$id"
    Job(correlator, id, html_url)
  }

  private def checkGithubEnv(): Unit = {
    githubWorkflow()
    githubJobName()
    githubRunId()
    githubSha()
    githubRef()
    githubApiUrl()
    githubRepository()
    githubToken()
  }

  private def githubWorkflow(): String = githubCIEnv("GITHUB_WORKFLOW")
  private def githubJobName(): String = githubCIEnv("GITHUB_JOB")
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
