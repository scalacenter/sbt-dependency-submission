# sbt-github-dependency-graph

An sbt plugin that can extract the dependencies of your project and submit them to the Github Dependency submission API.

It is no recommended and generally not useful to install this plugin manually, as it can only be used in a Github workflow.

The easiest way to use this plugin is to set [scalacenter/sbt-dependency-graph-action](https://github.com/scalacenter/sbt-dependency-graph-action) up in your Github workflow.
