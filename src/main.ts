import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as os from 'os'
import * as path from 'path'

// Version of the sbt-dependency-graph-plugin
const pluginVersion = '0.1.0-M5'
const baseDir = '.'

async function commandExists(cmd: string): Promise<boolean> {
  const isWin = os.platform() === 'win32'
  const where = isWin ? 'where.exe' : 'which'
  const code = await cli.exec(where, [cmd], { silent: true })
  return code === 0
}

async function run(): Promise<void> {
  try {
    const projectDir = path.join(baseDir, 'project')
    const uuid = crypto.randomUUID()
    const pluginFile = path.join(projectDir, `github-dependency-graph-${uuid}.sbt`)
    const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-graph" % "${pluginVersion}")`
    const isProject = fs.existsSync(projectDir)
    if (!isProject) {
      core.setFailed(`${baseDir} is not a valid sbt project: missing folder '${projectDir}'.`)
      return
    }
    await fsPromises.writeFile(pluginFile, pluginDep)
    const sbtExists = await commandExists('sbt')
    if (!sbtExists) {
      core.setFailed('Not found sbt command')
      return
    }
    await cli.exec('sbt', ['submitGithubDependencyGraph'])
  } catch (error) {
    if (error instanceof Error) {
      core.setFailed(error)
    } else {
      core.setFailed(`unknown error: ${error}`)
    }
  }
}

run()
