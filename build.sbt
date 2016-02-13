scodecModule := "scodec-stream"

scodecPrimaryModule
scodecPrimaryModuleJvm

crossScalaVersions := crossScalaVersions.value.filterNot { _.startsWith("2.12.") }

contributors ++= Seq(Contributor("mpilquist", "Michael Pilquist"), Contributor("pchiusano", "Paul Chiusano"))

rootPackage := "scodec.stream"

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-core" % "1.9.0",
  "org.scalaz.stream" %% "scalaz-stream" % "0.8",
  "org.scalacheck" %% "scalacheck" % "1.12.5" % "test"
)

libraryDependencies ++= {
  if (scalaBinaryVersion.value startsWith "2.10") Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full)) else Nil
}

OsgiKeys.exportPackage := Seq("scodec.stream.*;version=${Bundle-Version}")

OsgiKeys.importPackage := Seq(
  """scala.*;version="$<range;[==,=+);$<@>>"""",
  """scalaz.*;version="$<range;[==,=+);$<@>>"""",
  """scodec.*;version="$<range;[==,=+);$<@>>"""",
  "*"
)
