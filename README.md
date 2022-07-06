# Sbt Dependency Graph Action

A Github action to submit the dependency graphs of an [sbt](https://www.scala-sbt.org/) build to the Github [Dependency submission API](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/using-the-dependency-submission-api).

After the workflow has been successfully run, the graph of the sbt build is visible in the [Dependency Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository) page of the Insights tab.

Enable [Dependabot](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-supply-chain-security#what-is-dependabot) in your project settings to receive alerts for vulnerabilities that affect your sbt project.

## Support

Any sbt project whose sbt version is equal to or greater than 1.3.

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
    runs-on: ubuntu-latest # or windows-latest, or macOS-latest
    permissions:
      contents: write # this permission is needed to submit the dependency graph
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/sbt-dependency-graph-action@v1
```

### Inputs

#### - `base-dir` (optional)

The  relative path of the base directory of your sbt build.
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

In this example the snapshot will contain the graphs of `foo_2.13`, `foo_3`, `bar_2.13` and `bar_3`.

```yaml
steps:
  - uses: actions/checkout@v3
  - uses: scalacenter/sbt-dependency-graph-action@v0.1.0-M1
    with:
      base-dir: ./my-scala-project
      projects: foo bar
      scala-versions: 2.13.8 3.1.3
```
