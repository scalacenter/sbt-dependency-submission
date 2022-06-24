import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as os from 'os'
import * as path from 'path'

// Version of the sbt-github-dependency-graph-plugin
const pluginVersion = '0.1.0-M6'

async function commandExists(cmd: string): Promise<boolean> {
  const isWin = os.platform() === 'win32'
  const where = isWin ? 'where.exe' : 'which'
  const code = await cli.exec(where, [cmd], { silent: true })
  return code === 0
}

async function run(): Promise<void> {
  try {
    const baseDirInput = core.getInput('base-dir')
    const baseDir = baseDirInput.length === 0 ? '.' : baseDirInput
    const projectDir = path.join(baseDir, 'project')
    const uuid = crypto.randomUUID()
    const pluginFile = path.join(projectDir, `github-dependency-graph-${uuid}.sbt`)
    const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-graph" % "${pluginVersion}")`
    if (!fs.existsSync(projectDir)) {
      core.setFailed(`${baseDir} is not a valid sbt project: missing folder '${projectDir}'.`)
      return
    }
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

    await cli.exec('sbt', [`githubSubmitDependencyGraph ${JSON.stringify(input)}`])
  } catch (error) {
    if (error instanceof Error) {
      core.setFailed(error)
    } else {
      core.setFailed(`unknown error: ${error}`)
    }
  }
}

run()
