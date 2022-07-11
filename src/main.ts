import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as os from 'os'
import * as path from 'path'

// Version of the sbt-github-dependency-graph-plugin
const defaultSbtPluginVersion = '1.1.0'
// Ensure the version of Mill here matches that against what plugin is
// published against. Right now we only support 0.10, but when 0.11 drops
// we'll need to decide on support and then adjust accordingly here.
const defaultMillPluginVersion = '0.0.11'
const defaultMillVersion = '0.10.5'

const Mill = 'mill'
const Sbt = 'sbt'
const NoChoice = ''

const supportedBuildToolChoices = [NoChoice, Sbt, Mill]

async function commandExists(cmd: string): Promise<boolean> {
  const isWin = os.platform() === 'win32'
  const where = isWin ? 'where.exe' : 'which'
  const code = await cli.exec(where, [cmd], { silent: true })
  return code === 0
}

async function runSbt(baseDir: string, pluginVersionInput: string): Promise<void> {
  const projectDir = path.join(baseDir, 'project')

  if (!fs.existsSync(projectDir)) {
    core.setFailed(`${baseDir} is not a valid sbt project: missing folder '${projectDir}'.`)
    return
  }

  const uuid = crypto.randomUUID()
  const pluginFile = path.join(projectDir, `github-dependency-graph-${uuid}.sbt`)

  const pluginVersion =
    pluginVersionInput.length === 0 ? defaultSbtPluginVersion : pluginVersionInput

  const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-graph" % "${pluginVersion}")`
  await fsPromises.writeFile(pluginFile, pluginDep)

  const sbtExists = await commandExists('sbt')
  if (!sbtExists) {
    core.setFailed('Not found sbt command')
    return
  }

  const input = {
    projects: core
      .getInput('projects')
      .split(' ')
      .filter(value => value.length > 0),
    scalaVersions: core
      .getInput('scala-versions')
      .split(' ')
      .filter(value => value.length > 0),
  }

  await cli.exec('sbt', [`githubSubmitDependencyGraph ${JSON.stringify(input)}`], {
    cwd: baseDir,
  })
}

function getMillPath(baseDir: string): string {
  const millPath = path.join(baseDir, 'mill')
  const millWPath = path.join(baseDir, 'millw')
  if (fs.existsSync(millPath)) {
    return './mill'
  } else if (fs.existsSync(millWPath)) {
    return './millw'
  } else {
    core.info('Installing mill...')
    cli.exec('curl', [
      '-sLo',
      millPath,
      `https://github.com/com-lihaoyi/mill/releases/tag/${defaultMillVersion}/${defaultMillVersion}`,
    ])
    cli.exec('chmod', ['+x', millPath])
    return './mill'
  }
}

async function runMill(baseDir: string, pluginVersionInput: string): Promise<void> {
  const millCommand = getMillPath(baseDir)

  const pluginVersion =
    pluginVersionInput.length === 0 ? defaultMillPluginVersion : pluginVersionInput

  await cli.exec(
    millCommand,
    [
      '--import',
      `ivy:io.chris-kipp::mill-github-dependency-graph::${pluginVersion}`,
      'io.kipp.mill.github.dependency.graph.Graph/submit',
    ],
    {
      cwd: baseDir,
    },
  )
}

function isValidMillWorkspace(baseDir: string, buildToolChoice: string): boolean {
  return (
    (buildToolChoice === Mill || buildToolChoice === NoChoice) &&
    fs.existsSync(path.join(baseDir, 'build.sc'))
  )
}

function isValidSbtWorkspace(baseDir: string, buildToolChoice: string): boolean {
  return (
    (buildToolChoice === Sbt || buildToolChoice === NoChoice) &&
    fs.existsSync(path.join(baseDir, 'build.sbt'))
  )
}

async function run(): Promise<void> {
  try {
    const token = core.getInput('token')
    core.setSecret(token)
    process.env['GITHUB_TOKEN'] = token

    const baseDirInput = core.getInput('base-dir')
    const buildToolChoice = core.getInput('build-tool').toLowerCase()

    if (supportedBuildToolChoices.includes(buildToolChoice)) {
      core.setFailed(`The "build-tool" setting must be a either "${Mill}" or ${Sbt}`)
      return
    }

    const baseDir = baseDirInput.length === 0 ? '.' : baseDirInput

    const pluginVersionInput = core.getInput('plugin-version')

    if (isValidMillWorkspace(baseDir, buildToolChoice)) {
      runMill(baseDir, pluginVersionInput)
    } else if (isValidSbtWorkspace(baseDir, buildToolChoice)) {
      runSbt(baseDir, pluginVersionInput)
    } else {
      core.setFailed('Unable to find a build file for any of the supported build tools.')
    }
  } catch (error) {
    if (error instanceof Error) {
      core.setFailed(error)
    } else {
      core.setFailed(`unknown error: ${error}`)
    }
  }
}

run()
