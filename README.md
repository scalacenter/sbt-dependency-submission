# Scala Dependency Graph Action

A Github action to submit the dependency graph of your build to the Github
[Dependency submission
API](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/using-the-dependency-submission-api).

## Requirements
  - Before running the workflow, make sure that the `Dependency Graph` feature
      is enabled in the settings of your repository (`Settings` > `Code Security
      and Analysis`). 
  - Enable
      [Dependabot](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-supply-chain-security#what-is-dependabot)
      in your project settings to receive alerts for vulnerabilities that affect
      your Scala project.

The graph of your projects dependencies will be visible in the [Dependency
Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository)
page of the `Insights` tab.

## Supported Build Tools

  - [sbt](https://www.scala-sbt.org/) projects whose version is >= than 1.3. The
      plugin for this is located in this same directory under `sbt-plugin`.
  - [Mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html) projects
      whose version is >= 0.10.3. Mill support comes from
      [`ckipp01/mill-github-dependency-graph`](https://github.com/ckipp01/mill-github-dependency-graph).

## Usage

Create a Github Action file under `.github/workflows` containing the following definition.

```yml
# .github/workflows/dependency-graph.yml
name: Submit Dependency Graph
on:
  push:
    branches:
      - main # default branch of the project
jobs:
  submit-graph:
    name: Submit Dependency Graph
    runs-on: ubuntu-latest
    permissions:
      contents: write # this permission is needed to submit the dependency graph
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/scala-dependency-graph-action@v1
```

### Inputs

#### - `base-dir` (optional)

The relative path of the base directory of your build.
Default value is `.`

#### - `projects` (optional)

A list of space-separated names of projects from your build.
The action will publish the graph of these projects only.

Example: `foo bar`

Default is empty string and it means all projects.

#### - `scala-versions` (optional)

A list of space-separated versions of Scala, that are declared in your build.
The action will publish the graph on these Scala versions only.

Example: `2.13.8 3.1.3`

Default is empty string and it means all Scala versions.

#### Example

In this example the snapshot will contain the graphs of `foo_2.13`, `foo_3`,
`bar_2.13` and `bar_3`.

```yaml
steps:
  - uses: actions/checkout@v3
  - uses: scalacenter/scala-dependency-graph-action@v1
    with:
      base-dir: ./my-scala-project
      projects: foo bar
      scala-versions: 2.13.8 3.1.3
```

## Troubleshooting

### Unexpected Status: 404

This error happens when the `Dependency Graph` feature is disabled.
You can enable it in `Settings` > `Code Security and Analysis`.

![image](https://user-images.githubusercontent.com/13123162/177736071-5bd63d3c-d338-4e51-a3c9-ad8d11e35508.png)
