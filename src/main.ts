import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as os from 'os'
import * as path from 'path'

// Version of the sbt-github-dependency-submission plugin
const pluginVersion = '1.2.0-SNAPSHOT'

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
    const pluginFile = path.join(projectDir, `github-dependency-submission-${uuid}.sbt`)

    const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-submission" % "${pluginVersion}")`
    await fsPromises.writeFile(pluginFile, pluginDep)
    const sbtExists = await commandExists('sbt')
    if (!sbtExists) {
      core.setFailed('Not found sbt command')
      return
    }

    const ignoredModules = core
      .getInput('modules-ignore')
      .split(' ')
      .filter(value => value.length > 0)

    const onResolveFailure = core.getInput('on-resolve-failure')
    if (!['error', 'warning'].includes(onResolveFailure)) {
      core.setFailed(
        `Invalid on-resolve-failure input. Should be 'error' or 'warning', found ${onResolveFailure}.`,
      )
      return
    }

    const input = { ignoredModules, onResolveFailure }

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
