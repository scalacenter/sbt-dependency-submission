import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as io from '@actions/io'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as path from 'path'

// Version of the sbt-github-dependency-submission plugin
const pluginVersion = '2.0.1'

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
    // check that sbt is installed
    await io.which('sbt', true)

    const ignoredModules = core
      .getInput('modules-ignore')
      .split(' ')
      .filter(value => value.length > 0)

    const ignoredDependencies = core
      .getInput('dependencies-ignore')
      .split(' ')
      .filter(value => value.length > 0)
      .map(value => {
        const parts = value.split(':')
        if (parts.length === 1) {
          return { organization: parts[0] }
        } else if (parts.length === 2) {
          return { organization: parts[0], name: parts[1] }
        } else if (parts.length === 3) {
          return { organization: parts[0], name: parts[1], version: parts[2] }
        } else {
          core.setFailed(
            `dependencies-ignore. Should be a space-separated list of dependency declarations, 
            with organization, name (optional) and version (optional) separated by single colons: 
            'org1(:name1)(:version1) org2(:name2)(:version2)'.`,
          )
          return {}
        }
      })

    const onResolveFailure = core.getInput('on-resolve-failure')
    if (!['error', 'warning'].includes(onResolveFailure)) {
      core.setFailed(
        `Invalid on-resolve-failure input. Should be 'error' or 'warning', found ${onResolveFailure}.`,
      )
      return
    }

    const input = { ignoredModules, ignoredDependencies, onResolveFailure }

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
