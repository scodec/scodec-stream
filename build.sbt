scodecModule := "scodec-stream"

scodecPrimaryModule
scodecPrimaryModuleJvm

crossScalaVersions := Seq(scalaVersion.value, "2.12.0-M4") // fs2 does not support 2.10

contributors ++= Seq(Contributor("mpilquist", "Michael Pilquist"), Contributor("pchiusano", "Paul Chiusano"))

rootPackage := "scodec.stream"

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-core" % "1.10.0",
  "co.fs2" %% "fs2-core" % "0.9.0-M2",
  "org.scalacheck" %% "scalacheck" % "1.13.1" % "test"
)

libraryDependencies ++= {
  if (scalaBinaryVersion.value startsWith "2.10") Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full)) else Nil
}

OsgiKeys.exportPackage := Seq("scodec.stream.*;version=${Bundle-Version}")

OsgiKeys.importPackage := Seq(
  """scala.*;version="$<range;[==,=+);$<@>>"""",
  """fs2.*;version="$<range;[==,=+);$<@>>"""",
  """scodec.*;version="$<range;[==,=+);$<@>>"""",
  "*"
)

