# sbt-dependency-graph-action

!!! WARNING: THIS IS A WORK IN PROGRESS 

A Github action to submit the dependency graphs of an sbt build to the Github Dependency Graph API.

After the Github Action workflow has been successfully run, the graph of the sbt build is visible in the [Dependency Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository) page of the Insights tab.

## Support

- It supports any sbt project with sbt version equal to or greater than 1.3.
- It can only run on the default branch of a project.

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
    runs-on: ubuntu-latest
    env:
      GITHUB_TOKEN: ${{ github.token }}
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/sbt-dependency-graph-action@v1
```
