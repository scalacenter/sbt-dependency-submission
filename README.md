# Sbt Dependency Submission

A Github action to submit the dependency graph of an [sbt](https://www.scala-sbt.org/) build to the Github [Dependency submission API](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/using-the-dependency-submission-api).

Before running the workflow, make sure that the `Dependency Graph` feature is enabled in the settings of your repository (`Settings` > `Code Security and Analysis`). 
The graph of your sbt build will be visible in the [Dependency Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/exploring-the-dependencies-of-a-repository) page of the `Insights` tab.

Enable [Dependabot](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-supply-chain-security#what-is-dependabot) in your project settings to receive alerts for vulnerabilities that affect your sbt project.

## Support

Any sbt project whose sbt version is equal to or greater than 1.3.

## Usage

Create a Github Action file under `.github/workflows` containing the following definition.

```yml
# .github/workflows/dependency-graph.yml
name: Update Dependency Graph
on:
  push:
    branches:
      - main # default branch of the project
jobs:
  dependency-graph:
    name: Update Dependency Graph
    runs-on: ubuntu-latest # or windows-latest, or macOS-latest
    permissions:
      contents: write # this permission is needed to submit the dependency graph
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/sbt-dependency-submission@v1
```

### Inputs

#### - `working-directory` (optional)

The  relative path of the working directory of your sbt build.
Default value is `.`

#### - `modules-ignore` (optional)

A list of space-separated names of modules to ignore. The action will not resolve nor submit the dependencies of these modules.
The name of a module contains the name of the project and its binary version.

Example: `foo_2.13 bar_2.13`

#### Example

In this example the snapshot will not contain the graphs of `foo_2.13` and `bar_3`.

```yaml
steps:
  - uses: actions/checkout@v3
  - uses: scalacenter/sbt-dependency-submission@v1
    with:
      base-dir: ./my-scala-project
      modules-ignore: foo_2.13 bar_3
```

## Troubleshooting

### Unexpected Status: 404

This error happens when the `Dependency Graph` feature is disabled.
You can enable it in `Settings` > `Code Security and Analysis`.

![image](https://user-images.githubusercontent.com/13123162/177736071-5bd63d3c-d338-4e51-a3c9-ad8d11e35508.png)


