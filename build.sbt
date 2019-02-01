scodecModule := "scodec-stream"

enablePlugins(ScodecPrimaryModuleSettings)
enablePlugins(ScodecPrimaryModuleJVMSettings)

crossScalaVersions := crossScalaVersions.value.filterNot(_.startsWith("2.10.")).filterNot(_.startsWith("2.13."))
releaseCrossBuild := true

contributors ++= Seq(Contributor("mpilquist", "Michael Pilquist"), Contributor("pchiusano", "Paul Chiusano"))

rootPackage := "scodec.stream"
scmInfo := Some(ScmInfo(url("https://github.com/scodec/scodec-stream"), "git@github.com:scodec/scodec-stream.git"))

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-core" % "1.11.2",
  "co.fs2" %% "fs2-core" % "1.0.3",
  "org.scalacheck" %% "scalacheck" % "1.13.5" % "test"
)

libraryDependencies ++= {
  if (scalaBinaryVersion.value startsWith "2.10") Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)) else Nil
}

OsgiKeys.exportPackage := Seq("scodec.stream.*;version=${Bundle-Version}")

OsgiKeys.importPackage := Seq(
  """scala.*;version="$<range;[==,=+);$<@>>"""",
  """fs2.*;version="$<range;[==,=+);$<@>>"""",
  """scodec.*;version="$<range;[==,=+);$<@>>"""",
  "*"
)
