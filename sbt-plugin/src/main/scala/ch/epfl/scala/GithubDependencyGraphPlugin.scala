package ch.epfl.scala

import scala.collection.mutable
import scala.util.Properties

import ch.epfl.scala.githubapi._
import sbt.Scoped.richTaskSeq
import sbt._
import sbt.internal.util.complete.Parser
import sbt.internal.util.complete.Parsers
import sbt.plugins.JvmPlugin
import sjsonnew.shaded.scalajson.ast.unsafe.JString

object GithubDependencyGraphPlugin extends AutoPlugin {
  private val runtimeConfigs =
    Set(
      Compile,
      Configurations.CompileInternal,
      Runtime,
      Configurations.RuntimeInternal,
      Provided,
      Optional,
      Configurations.System
    )
      .map(_.toConfigRef)

  object autoImport {
    val githubSubmitInputKey: AttributeKey[SubmitInput] = AttributeKey("githubSubmitInput")
    val githubManifestsKey: AttributeKey[Map[String, githubapi.Manifest]] = AttributeKey("githubDependencyManifests")
    val githubProjectsKey: AttributeKey[Seq[ProjectRef]] = AttributeKey("githubProjectRefs")
    val githubDependencyManifest: TaskKey[githubapi.Manifest] = taskKey("The dependency manifest of the project")
    val githubStoreDependencyManifests: InputKey[StateTransform] =
      inputKey("Store the dependency manifests of all projects of a Scala version in the attribute map.")
        .withRank(KeyRanks.DTask)
  }

  import autoImport._

  override def trigger = allRequirements
  override def requires: Plugins = JvmPlugin

  override def globalSettings: Seq[Setting[_]] = Def.settings(
    githubStoreDependencyManifests := storeManifestsTask.evaluated,
    Keys.commands ++= SubmitDependencyGraph.commands
  )

  override def projectSettings: Seq[Setting[_]] = Def.settings(
    githubDependencyManifest := manifestTask.value,
    githubDependencyManifest / Keys.aggregate := false
  )

  private val scalaVersionParser = {
    import Parsers._
    import Parser._
    val validOpChars = Set('.', '-', '+')
    identifier(
      charClass(alphanum, "alphanum"),
      charClass(c => alphanum(c) || validOpChars.contains(c), "version character")
    )
  }

  private def storeManifestsTask: Def.Initialize[InputTask[StateTransform]] = Def.inputTaskDyn {
    val scalaVersionInput = (Parsers.Space ~> scalaVersionParser).parsed
    val state = Keys.state.value

    val projectRefs = state
      .attributes(githubProjectsKey)
      .filter(ref => state.setting(ref / Keys.scalaVersion) == scalaVersionInput)
      .filter(ref => includeProject(ref, state))

    Def.task {
      val manifests: Map[String, Manifest] = projectRefs
        .map(ref => (ref / githubDependencyManifest).?)
        .join
        .value
        .collect { case Some(manifest) => (manifest.name, manifest) }
        .toMap
      StateTransform { state =>
        val oldManifests = state.attributes(githubManifestsKey)
        state.put(githubManifestsKey, oldManifests ++ manifests)
      }
    }
  }

  private def includeProject(projectRef: ProjectRef, state: State): Boolean = {
    val ignoredModules = state.attributes(githubSubmitInputKey).ignoredModules
    val scalaVersion = state.setting(projectRef / Keys.artifactName / Keys.scalaVersion)
    val scalaBinaryVersion = state.setting(projectRef / Keys.artifactName / Keys.scalaBinaryVersion)
    val projectID = state.setting(projectRef / Keys.projectID)
    val moduleName = CrossVersion(scalaVersion, scalaBinaryVersion).apply(projectID).name
    !ignoredModules.contains(moduleName)
  }

  private def manifestTask: Def.Initialize[Task[Manifest]] = Def.task {
    // updateFull is needed to have information about callers and reconstruct dependency tree
    val report = Keys.updateFull.value
    val projectID = Keys.projectID.value
    val scalaVersion = (Keys.artifactName / Keys.scalaVersion).value
    val scalaBinaryVersion = (Keys.artifactName / Keys.scalaBinaryVersion).value
    val crossVersion = CrossVersion.apply(scalaVersion, scalaBinaryVersion)
    val allDirectDependencies = Keys.allDependencies.value
    val baseDirectory = Keys.baseDirectory.value

    def getReference(module: ModuleID): String =
      crossVersion(module)
        .withConfigurations(None)
        .withExtraAttributes(Map.empty)
        .toString

    val alreadySeen = mutable.Set[String]()
    val moduleReports = mutable.Buffer[(ModuleReport, ConfigRef)]()
    val allDependencies = mutable.Buffer[(String, String)]()

    for {
      configReport <- report.configurations
      moduleReport <- configReport.modules
      moduleRef = getReference(moduleReport.module)
      if !moduleReport.evicted && !alreadySeen.contains(moduleRef)
    } {
      alreadySeen += moduleRef
      moduleReports += (moduleReport -> configReport.configuration)
      for (caller <- moduleReport.callers)
        allDependencies += (getReference(caller.caller) -> moduleRef)
    }

    val allDependenciesMap: Map[String, Vector[String]] = allDependencies.view
      .groupBy(_._1)
      .mapValues {
        _.map { case (_, dep) => dep }.toVector
      }
    val allDirectDependenciesRefs: Set[String] = allDirectDependencies.map(getReference).toSet

    val resolved =
      for ((moduleReport, configRef) <- moduleReports)
        yield {
          val moduleRef = getReference(moduleReport.module)
          val packageUrl = formatPackageUrl(moduleReport)
          val dependencies = allDependenciesMap.getOrElse(moduleRef, Vector.empty)
          val relationship =
            if (allDirectDependenciesRefs.contains(moduleRef)) DependencyRelationship.direct
            else DependencyRelationship.indirect
          val scope =
            if (isRuntime(configRef)) DependencyScope.runtime
            else DependencyScope.development
          val metadata = Map("config" -> JString(configRef.name))
          val node = DependencyNode(packageUrl, metadata, Some(relationship), Some(scope), dependencies)
          (moduleRef -> node)
        }

    val projectModuleRef = getReference(projectID)
    // TODO: find exact build file for this project
    val file = githubapi.FileInfo("build.sbt")
    val metadata = Map("baseDirectory" -> JString(baseDirectory.toString))
    githubapi.Manifest(projectModuleRef, file, metadata, resolved.toMap)
  }

  private def formatPackageUrl(moduleReport: ModuleReport): String = {
    val module = moduleReport.module
    val artifacts = moduleReport.artifacts.map { case (a, _) => a }
    val classifiers = artifacts.flatMap(_.classifier).filter(_ != "default")
    val packaging = if (classifiers.nonEmpty) "?" + classifiers.map(c => s"packaging=$c").mkString("&") else ""
    s"pkg:maven/${module.organization}/${module.name}@${module.revision}$packaging"
  }

  private def isRuntime(config: ConfigRef): Boolean = runtimeConfigs.contains(config)

  private def githubCIEnv(name: String): String =
    Properties.envOrNone(name).getOrElse {
      throw new MessageOnlyException(s"Missing environment variable $name. This task must run in a Github Action.")
    }
}
