import com.typesafe.tools.mima.core._
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import com.typesafe.sbt.SbtGit.GitKeys.{gitCurrentBranch, gitHeadCommit}

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; scalafmtSbt")
addCommandAlias("fmtCheck", "; compile:scalafmtCheck; test:scalafmtCheck; scalafmtSbtCheck")

ThisBuild / baseVersion := "2.0"

ThisBuild / organization := "org.scodec"
ThisBuild / organizationName := "Scodec"

ThisBuild / homepage := Some(url("https://github.com/scodec/scodec-stream"))
ThisBuild / startYear := Some(2013)

ThisBuild / crossScalaVersions := Seq("2.12.13", "2.13.5", "3.0.0")

ThisBuild / strictSemVer := false

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.8")

ThisBuild / spiewakCiReleaseSnapshots := true

ThisBuild / spiewakMainBranches := List("main", "develop")

ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/scodec/scodec-stream"), "git@github.com:scodec/scodec-stream.git")
)

ThisBuild / licenses := List(
  ("BSD-3-Clause", url("https://github.com/scodec/scodec-stream/blob/main/LICENSE"))
)

ThisBuild / testFrameworks += new TestFramework("munit.Framework")

ThisBuild / publishGithubUser := "mpilquist"
ThisBuild / publishFullName := "Michael Pilquist"
ThisBuild / developers ++= List(
  "pchiusano" -> "Paul Chiusano"
).map {
  case (username, fullName) =>
    Developer(username, fullName, s"@$username", url(s"https://github.com/$username"))
}

ThisBuild / fatalWarningsInCI := false

ThisBuild / mimaBinaryIssueFilters ++= Seq(
)

val stream = crossProject(JVMPlatform, JSPlatform)
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "scodec-stream",
    buildInfoPackage := "scodec.stream",
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit),
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % "2.5.9",
      "org.scodec" %%% "scodec-core" % (if (isDotty.value) "2.0.0" else "1.11.7"),
      "org.scalacheck" %%% "scalacheck" % "1.15.4" % Test
    ),
    unmanagedResources in Compile ++= {
      val base = baseDirectory.value
      (base / "NOTICE") +: (base / "LICENSE") +: ((base / "licenses") * "LICENSE_*").get
    }
  )

lazy val streamJVM = stream.jvm
  .enablePlugins(SbtOsgi)
  .settings(
    libraryDependencies += "co.fs2" %%% "fs2-io" % "2.5.9" % Test,
    OsgiKeys.privatePackage := Nil,
    OsgiKeys.exportPackage := Seq("scodec.stream.*;version=${Bundle-Version}"),
    OsgiKeys.importPackage := Seq(
      """scala.*;version="$<range;[==,=+);$<@>>"""",
      """fs2.*;version="$<range;[==,=+);$<@>>"""",
      """scodec.*;version="$<range;[==,=+);$<@>>"""",
      "*"
    ),
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  )
lazy val streamJS = stream.js

lazy val root = project
  .in(file("."))
  .aggregate(streamJVM, streamJS)
  .enablePlugins(NoPublishPlugin, SonatypeCiReleasePlugin)
