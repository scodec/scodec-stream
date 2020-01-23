import com.typesafe.tools.mima.core._
import com.typesafe.sbt.SbtGit.GitKeys.{gitCurrentBranch, gitHeadCommit}

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; scalafmtSbt")
addCommandAlias("fmtCheck", "; compile:scalafmtCheck; test:scalafmtCheck; scalafmtSbtCheck")

lazy val contributors = Seq(
  "mpilquist" -> "Michael Pilquist",
  "pchiusano" -> "Paul Chiusano"
)

lazy val sharedScalaOptions = Seq(
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
)

lazy val scala212Options = Seq(
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

lazy val scala213Options = Seq(
  "-Xsource:2.13"
)

lazy val versionOf = new {
  val fs2           = "2.2.1"
  val scalacheck    = "1.14.1"
  val `scodec-core` = "1.11.4"
}

lazy val dependencies = Seq(
  "co.fs2"         %% "fs2-core"    % versionOf.fs2,
  "org.scodec"     %% "scodec-core" % versionOf.`scodec-core`,
  "co.fs2"         %% "fs2-io"      % versionOf.fs2        % Test,
  "org.scalacheck" %% "scalacheck"  % versionOf.scalacheck % Test
)

lazy val moduleSettings = Seq(
  name := "scodec-stream",
  organization := "org.scodec",
  organizationHomepage := Some(new URL("http://scodec.org")),
  licenses += ("Three-clause BSD-style", url(
    "https://github.com/scodec/scodec-stream/blob/master/LICENSE"
  )),
  git.remoteRepo := "git@github.com:scodec/scodec-stream.git",
  scmInfo := Some(
    ScmInfo(url("https://github.com/scodec/scodec-stream"), "git@github.com:scodec/scodec-stream.git")
  ),
  scalacOptions ++= sharedScalaOptions ++
    (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12))  => scala212Options
      case Some((2, 13))  => scala213Options
      case Some((ma, mi)) => sys.error(s"Unsupported scala version: $ma.$mi")
      case None           => sys.error(s"Not found any scala version set")
    }),
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  releaseCrossBuild := true
)

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
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <url>http://github.com/scodec/scodec-stream</url>
    <developers>
      {for ((username, name) <- contributors) yield <developer>
      <id>{username}</id>
      <name>{name}</name>
      <url>http://github.com/{username}</url>
    </developer>}
    </developers>,
  pomPostProcess := { node =>
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

lazy val osgiSettings = Seq(
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

lazy val buildInfoSettings = Seq(
  buildInfoPackage := "scodec.stream",
  buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit)
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    List().map { pv =>
      organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
    }.toSet
  }
)

val scodecStream = project.in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(moduleSettings)
  .settings(mimaSettings)
  .settings(osgiSettings)
  .settings(buildInfoSettings)
  .settings(publishingSettings)
  .settings(
    libraryDependencies ++= dependencies,
    autoAPIMappings := true,
    scalacOptions in (Compile, doc) := {
      val tagOrBranch = {
        if (version.value.endsWith("SNAPSHOT")) gitCurrentBranch.value
        else "v" + version.value
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
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value
  )
