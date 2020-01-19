scodecModule := "scodec-stream"

enablePlugins(ScodecPrimaryModuleSettings)
enablePlugins(ScodecPrimaryModuleJVMSettings)

crossScalaVersions := crossScalaVersions.value.filterNot(_.startsWith("2.10."))
releaseCrossBuild := true

contributors ++= Seq(Contributor("mpilquist", "Michael Pilquist"), Contributor("pchiusano", "Paul Chiusano"))

rootPackage := "scodec.stream"
scmInfo := Some(ScmInfo(url("https://github.com/scodec/scodec-stream"), "git@github.com:scodec/scodec-stream.git"))

scalacOptions += "-language:higherKinds"
scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v >= 13 =>
      Seq()
    case _ =>
      Seq("-Ypartial-unification")
  }
}

scalacOptions --= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v >= 13 =>
      Seq("-Yno-adapted-args", "-Ywarn-unused-import")
    case _ =>
      Seq()
  }
}

libraryDependencies ++= Def.setting {
  val fs2Version = CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v >= 12 => "2.2.0"
    case _ => "2.1.0"
  }

  Seq(
    "org.scodec" %% "scodec-core" % "1.11.4",
    "co.fs2" %% "fs2-core" % fs2Version,
    "co.fs2" %% "fs2-io" % fs2Version % "test",
    "org.scalacheck" %% "scalacheck" % "1.14.1" % "test"
  )
}.value

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
