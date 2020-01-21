import com.typesafe.tools.mima.core._
import com.typesafe.sbt.SbtGit.GitKeys.{gitCurrentBranch, gitHeadCommit}

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; scalafmtSbt")
addCommandAlias("fmtCheck", "; compile:scalafmtCheck; test:scalafmtCheck; scalafmtSbtCheck")

lazy val contributors = Seq(
  "mpilquist" -> "Michael Pilquist",
  "pchiusano" -> "Paul Chiusano"
)

lazy val settings = Seq(
  organization := "org.scodec",
  organizationHomepage := Some(new URL("http://scodec.org")),
  licenses += ("Three-clause BSD-style", url(
    "https://github.com/scodec/scodec-stream/blob/master/LICENSE"
  )),
  git.remoteRepo := "git@github.com:scodec/scodec-stream.git",
  scmInfo := Some(
    ScmInfo(url("https://github.com/scodec/scodec-stream"), "git@github.com:scodec/scodec-stream.git")
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-explaintypes",
    "-Yrangepos",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xlint:_,-type-parameter-shadow",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfatal-warnings"
  ) ++
    (scalaBinaryVersion.value match {
      case v if v.startsWith("2.13") =>
        List("-Xsource:2.13")
      case v if v.startsWith("2.12") =>
        List(
          "-opt:l:inline",
          "-opt-inline-from:<source>",
          "-opt-warnings",
          "-Ywarn-unused:imports",
          "-Ywarn-unused:_,imports",
          "-Xlint:constant",
          "-Ywarn-extra-implicit",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-unit",
          "-Ywarn-nullary-override",
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Xfuture"
        )
      case other => sys.error(s"Unsupported scala version: $other")
    }),
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  releaseCrossBuild := true
) ++ publishingSettings

lazy val publishingSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots".at(nexus + "content/repositories/snapshots"))
    else
      Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
  },
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { x =>
    false
  },
  pomExtra := (
    <url>http://github.com/scodec/scodec-stream</url>
      <developers>
        {for ((username, name) <- contributors) yield <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>}
      </developers>
    ),
  pomPostProcess := { (node) =>
    import scala.xml._
    import scala.xml.transform._
    def stripIf(f: Node => Boolean) = new RewriteRule {
      override def transform(n: Node) =
        if (f(n)) NodeSeq.Empty else n
    }
    val stripTestScope = stripIf { n =>
      n.label == "dependency" && (n \ "scope").text == "test"
    }
    new RuleTransformer(stripTestScope).transform(node)(0)
  }
)

val scodecStream = project.in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(settings: _*)
  .settings(
    name := "scodec-stream",
    libraryDependencies ++= Seq(
      "org.scodec" %% "scodec-core" % "1.11.4",
      "co.fs2" %% "fs2-core" % "2.2.0",
      "co.fs2" %% "fs2-io" % "2.2.0" % "test",
      "org.scalacheck" %% "scalacheck" % "1.14.1" % "test"
    ),
    autoAPIMappings := true,
    buildInfoPackage := "scodec.stream",
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit),
    scalacOptions in (Compile, doc) := {
      val tagOrBranch = {
        if (version.value.endsWith("SNAPSHOT")) gitCurrentBranch.value
        else ("v" + version.value)
      }
      Seq(
        "-groups",
        "-implicits",
        "-implicits-show-all",
        "-sourcepath",
        new File(baseDirectory.value, "../..").getCanonicalPath,
        "-doc-source-url",
        "https://github.com/scodec/scodec-stream/tree/" + tagOrBranch + "€{FILE_PATH}.scala"
      )
    },
    scalacOptions in (Compile, console) ~= {
      _.filterNot { o =>
        o == "-Ywarn-unused" || o == "-Xfatal-warnings"
      }
    },
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
    mimaPreviousArtifacts := {
      List().map { pv =>
        organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
      }.toSet
    },
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
