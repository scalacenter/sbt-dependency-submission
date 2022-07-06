val pluginVersion = sys.props("plugin.version")

addSbtPlugin("ch.epfl.scala" % "sbt-github-dependency-graph" % pluginVersion)
