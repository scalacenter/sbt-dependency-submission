import * as cli from '@actions/exec'
import * as core from '@actions/core'
import * as io from '@actions/io'
import * as github from '@actions/github'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'
import * as path from 'path'
import type { PullRequestEvent } from '@octokit/webhooks-types'

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

    const pluginVersion = core.getInput('sbt-plugin-version')
    const pluginDep = `addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-submission" % "${pluginVersion}")`
    await fsPromises.writeFile(pluginFile, pluginDep)
    // check that sbt is installed
    await io.which('sbt', true)

    const ignoredModules = core
      .getInput('modules-ignore')
      .split(' ')
      .filter(value => value.length > 0)

    const ignoredConfigs = core
      .getInput('configs-ignore')
      .split(' ')
      .filter(value => value.length > 0)

    const onResolveFailure = core.getInput('on-resolve-failure')
    if (!['error', 'warning'].includes(onResolveFailure)) {
      core.setFailed(
        `Invalid on-resolve-failure input. Should be 'error' or 'warning', found ${onResolveFailure}.`,
      )
      return
    }

    const correlatorInput = core.getInput('correlator')
    const correlator = correlatorInput
      ? correlatorInput
      : `${github.context.workflow}_${github.context.job}_${github.context.action}`

    const refOverride = core.getInput('ref-override')

    const input = {
      ignoredModules,
      ignoredConfigs,
      onResolveFailure,
      correlator,
      refOverride,
    }

    if (github.context.eventName === 'pull_request') {
      core.info('pull request, resetting sha')
      const payload = github.context.payload as PullRequestEvent
      core.info(`setting sha to: ${payload.pull_request.head.sha}`)
      process.env['GITHUB_SHA'] = payload.pull_request.head.sha
    }

    process.env['GITHUB_TOKEN'] = token
    await cli.exec(
      'sbt',
      ['--batch', `githubGenerateSnapshot ${JSON.stringify(input)}; githubSubmitSnapshot`],
      {
        cwd: workingDir,
      },
    )
  } catch (error) {
    if (error instanceof Error) {
      core.setFailed(error)
    } else {
      core.setFailed(`unknown error: ${error}`)
    }
  }
}

run()
