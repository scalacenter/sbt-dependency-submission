import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as os from 'os'
import * as path from 'path'

// Version of the sbt-github-dependency-submission plugin
const defaultPluginVersion = '1.2.0-SNAPSHOT'

async function commandExists(cmd: string): Promise<boolean> {
  const isWin = os.platform() === 'win32'
  const where = isWin ? 'where.exe' : 'which'
  const code = await cli.exec(where, [cmd], { silent: true })
  return code === 0
}

async function run(): Promise<void> {
  try {
    const token = core.getInput('token')
    core.setSecret(token)

    const workingDirInput = core.getInput('working-directory')
    const workingDir = workingDirInput.length === 0 ? '.' : workingDirInput
    const projectDir = path.join(workingDir, 'project')
    if (!fs.existsSync(projectDir)) {
      core.setFailed(`${workingDir} is not a valid sbt project: missing folder '${projectDir}'.`)
      return
    }

    const uuid = crypto.randomUUID()
    const pluginFile = path.join(projectDir, `github-dependency-graph-${uuid}.sbt`)

    const pluginVersionInput = core.getInput('sbt-plugin-version')
    const pluginVersion =
      pluginVersionInput.length === 0 ? defaultPluginVersion : pluginVersionInput
    const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-submission" % "${pluginVersion}")`
    await fsPromises.writeFile(pluginFile, pluginDep)
    const sbtExists = await commandExists('sbt')
    if (!sbtExists) {
      core.setFailed('Not found sbt command')
      return
    }

    const input = {
      ignoredModules: core
        .getInput('modules-ignore')
        .split(' ')
        .filter(value => value.length > 0),
    }

    process.env['GITHUB_TOKEN'] = token
    await cli.exec('sbt', [`githubSubmitDependencyGraph ${JSON.stringify(input)}`], {
      cwd: workingDir,
    })
  } catch (error) {
    if (error instanceof Error) {
      core.setFailed(error)
    } else {
      core.setFailed(`unknown error: ${error}`)
    }
  }
}

run()
