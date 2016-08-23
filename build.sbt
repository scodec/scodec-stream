scodecModule := "scodec-stream"

scodecPrimaryModule
scodecPrimaryModuleJvm

crossScalaVersions := Seq(scalaVersion.value, "2.12.0-M5") // fs2 does not support 2.10

contributors ++= Seq(Contributor("mpilquist", "Michael Pilquist"), Contributor("pchiusano", "Paul Chiusano"))

rootPackage := "scodec.stream"

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-core" % "1.10.2",
  "co.fs2" %% "fs2-core" % "0.9.0-RC2",
  "org.scalacheck" %% "scalacheck" % "1.13.1" % "test"
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

