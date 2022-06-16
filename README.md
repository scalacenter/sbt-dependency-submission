# sbt-dependency-graph-action

!!! WARNING: THIS IS A WORK IN PROGRESS 

A Github action to submit the dependency graphs of an [sbt](https://www.scala-sbt.org/) build to the Github Dependency Graph API.

After the Github Action workflow has been successfully run, the graph of the sbt build is visible in the [Dependency Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository) page of the Insights tab.

Enable [Dependabot](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-supply-chain-security#what-is-dependabot) in your project settings to receive alerts for vulnerabilities that affec your sbt project.

## Support

Any sbt project whose sbt version is equal to or greater than 1.3.

## Usage

Create a Github Action file under `.github/workflows` containing the following definition.

```yml
# .github/workflows/dependency-graph.yml
name: Submit Dependency Graph
on:
  push:
    branches: main # default branch of the project
jobs:
  submit-graph:
    name: Submit Dependency Graph
    runs-on: ubuntu-latest # or windows-latest, or macOS-latest
    env:
      GITHUB_TOKEN: ${{ github.token }}
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/sbt-dependency-graph-action@v1
```
