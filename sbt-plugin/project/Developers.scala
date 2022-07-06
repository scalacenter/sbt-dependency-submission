import sbt._

object Developers {
  val adpi2: Developer = Developer(
    "adpi2",
    "Adrien Piquerez",
    "adrien.piquerez@gmail.com",
    url("https://github.com/adpi2/")
  )

  val all = List(adpi2)
}
