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
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: scalacenter/sbt-dependency-submission@v2
```

### Inputs

#### - `working-directory` (optional)

The  relative path of the working directory of your sbt build.
Default value is `.`

#### - `modules-ignore` (optional)

A list of space-separated names of modules to ignore. The action will not resolve nor submit the dependencies of these modules.
The name of a module contains the name of the project and its binary version.

Example: `foo_2.13 bar_2.13`

#### - `configs-ignore` (optional)

A list of space-separated names of configurations to ignore. The action will not submit the dependencies of these configurations.

Example of configurations are `compile`, `test`, `scala-tool`, `scala-doc-tool`.

#### - `token` (optional)

GitHub Personal Access Token (PAT). Defaults to PAT provided by Action runner.

Example: `${{ secrets.USER_TOKEN }}`

#### Example

##### Excluding some projects or some Scala versions from the dependency submission.

In this example the snapshot will not contain the graphs of `foo_2.13` and `bar_3`.

```yaml

## in .github/workflows/dependency-graph.md
...
steps:
  - uses: actions/checkout@v3
  - uses: scalacenter/sbt-dependency-submission@v2
    with:
      base-dir: ./my-scala-project
      modules-ignore: foo_2.13 bar_3
```

#### Excluding the Scaladoc dependencies.

In this example the snapshot will not contain the dependencies of the scala-doc-tool configuration.

```yaml

## in .github/workflows/dependency-graph.md
...
steps:
  - uses: actions/checkout@v3
  - uses: scalacenter/sbt-dependency-submission@v2
    with:
      base-dir: ./my-scala-project
      configs-ignore: scala-doc-tool
```

## Troubleshooting

### Unexpected Status: 404

This error happens when the `Dependency Graph` feature is disabled.
You can enable it in `Settings` > `Code Security and Analysis`.

![image](https://user-images.githubusercontent.com/13123162/177736071-5bd63d3c-d338-4e51-a3c9-ad8d11e35508.png)

### Unexpected Status: 403

This error happens when the workflow does not have the right permission on the repository.

First you should check that the workflow is not triggered on PR from forked repositories.
It should be triggered by push to the default branch.

```yaml
## in .github/workflows/dependency-graph.md
on:
  push:
    branches:
      - main # default branch of the project
...
```

Then check that you enabled the read and write permissions for all workflows, at the bottom of the `Settings > Actions > General` page.

![image](https://user-images.githubusercontent.com/13123162/179472237-bffea114-9e99-4736-83ef-00dc7f41149b.png)

If you do not want to enable this you can add the write permission on the `dependency-graph` workflow only:

```yaml
## in .github/workflows/dependency-graph.md
...
permissions:
      contents: write # this permission is needed to submit the dependency graph
...
```

### sbt.librarymanagement.ResolveException: Error downloading

This error may happen when you try to access artifacts from private GitHub packages with the default GitHub token. You need to pass personal access token which is allowed to access private packages in the `token` input.




