// SPDX-License-Identifier: Apache-2.0

import mill._
import scalalib._
import publish._
import Assembly._
import mill.util.Jvm
import mill.scalalib.PublishModule.checkSonatypeCreds
import mill.api.{Result}
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import de.tobiasroeser.mill.vcs.version.VcsVersion

case class Platform(os: String, arch: String) {
  override def toString: String = s"$os-$arch"
}
object Platform {
  // Needed to use Platform's in T[_] results
  import upickle.default.{ReadWriter, macroRW}
  implicit val rw: ReadWriter[Platform] = macroRW
}

trait ChipsAlliancePublishModule extends PublishModule {

  def isSnapshot: T[Boolean]

  override def sonatypeUri: String = "https://s01.oss.sonatype.org/service/local"
  override def sonatypeSnapshotUri: String = "https://s01.oss.sonatype.org/content/repositories/snapshots"
  protected def getEnvVariable(name: String): String =
    sys.env.get(name).getOrElse(throw new Exception(s"Environment variable $name must be defined!"))

  def gpgArgs: Seq[String] = Seq(
    "--detach-sign",
    "--batch=true",
    "--yes",
    "--passphrase",
    getEnvVariable("PGP_PASSPHRASE"),
    "--armor",
    "--use-agent"
  )

  // We cannot call publish from a Command so we inline it, derived from:
  // https://github.com/com-lihaoyi/mill/blob/e4bcc274cff76534d9b7bdfe6238bd65fc1eaf45/scalalib/src/mill/scalalib/PublishModule.scala#L279
  // Mill uses the MIT license
  private def loadSonatypeCreds(): Task[String] = T.task {
    (for {
      username <- T.env.get("SONATYPE_USERNAME")
      password <- T.env.get("SONATYPE_PASSWORD")
    } yield {
      Result.Success(s"$username:$password")
    }).getOrElse(
      Result.Failure(
        "Consider using SONATYPE_USERNAME/SONATYPE_PASSWORD environment variables or passing `sonatypeCreds` argument"
      )
    )
  }

  // Helper for publishing, sets values so we don't have to set them on the command-line
  def publishSigned() = T.command {
    val signed = true
    val readTimeout: Int = 2 * 60 * 1000
    val connectTimeout: Int = 10 * 1000
    val awaitTimeout: Int = 10 * 60 * 1000
    val stagingRelease: Boolean = true
    val release = !isSnapshot()
    // We cannot call publish from a Command so we inline it, derived from:
    // https://github.com/com-lihaoyi/mill/blob/e4bcc274cff76534d9b7bdfe6238bd65fc1eaf45/scalalib/src/mill/scalalib/PublishModule.scala#L177
    // Mill uses the MIT license
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      loadSonatypeCreds()(),
      signed,
      gpgArgs,
      readTimeout,
      connectTimeout,
      T.log,
      T.workspace,
      T.env,
      awaitTimeout,
      stagingRelease
    ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo, release)
  }
}

// Must run mill with -i because it uses environment variables:
// LLVM_FIRTOOL_VERSION - (eg. 1.58.0)
// LLVM_FIRTOOL_PRERELEASE - 0 means real release (non-SNAPSHOT), otherwise is -SNAPSHOT
// Maven appears to have an API for resolving classifiers at fetch time,
// but it's not supported by Coursier: https://github.com/coursier/coursier/issues/2016
object `llvm-firtool` extends JavaModule with ChipsAlliancePublishModule {

  def firtoolVersion = getEnvVariable("LLVM_FIRTOOL_VERSION")
  // FNDDS requires that the publish version start with firtool version with optional -<suffix>
  def isSnapshot = T { getEnvVariable("LLVM_FIRTOOL_PRERELEASE") != "0" }

  def publishSuffix = T { if (isSnapshot()) "-SNAPSHOT" else "" }
  def publishVersion = T { firtoolVersion + publishSuffix() }

  private def FNDDSSpecVersion = "1.0.0"
  private def groupId = "org.chipsalliance"
  // artifactId is the the name of this object
  private def artId = "llvm-firtool"
  private def binName = "firtool"
  private def releaseUrl = s"https://github.com/llvm/circt/releases/download/firtool-${firtoolVersion}"

  val platforms = Seq[Platform](
    Platform("macos", "x64"),
    Platform("linux", "x64"),
    Platform("windows", "x64")
  )

  def pomSettings = PomSettings(
    description = "Package of native firtool binary",
    organization = "org.chipsalliance",
    url = "https://chipsalliance.org",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "llvm-firtool"),
    developers = Seq(
      Developer("jackkoenig", "Jack Koenig", "https://github.com/jackkoenig")
    )
  )

  private def getBaseDir(dir: os.Path): os.Path = dir / groupId / artId

  // Downloaded tarball for each platform
  def tarballs = T {
    platforms.map { platform =>
      val tarballName = if (platform.os == "windows") {
        s"firrtl-bin-$platform.zip"
      } else {
        s"firrtl-bin-$platform.tar.gz"
      }
      val archiveUrl = s"$releaseUrl/$tarballName"
      val file = T.dest / tarballName
      os.write(file, requests.get.stream(archiveUrl))
      platform -> PathRef(file)
    }
  }

  def extractedDirs = T {
    val tarballLookup = tarballs().toMap
    platforms.map { platform =>
      val tarball = tarballLookup(platform)
      val dir = T.dest / platform.toString
      os.makeDir.all(dir)
      // Windows uses .zip
      if (platform.os == "windows") {
        os.proc("unzip", tarball.path)
          .call(cwd = dir)
      } else {
        os.proc("tar", "zxf", tarball.path)
          .call(cwd = dir)
      }
      val downloadedDir = os.list(dir).head
      val baseDir = getBaseDir(dir)
      os.makeDir.all(baseDir)

      // Rename the directory to the FNNDS specificed path
      val artDir = baseDir / platform.toString
      os.move(downloadedDir, artDir)

      // If on windows, rename firtool.exe to firtool
      if (platform.os == "windows") {
        os.walk(artDir).foreach { path =>
          if (path.baseName == "firtool" && path.ext == "exe") {
            // OS lib doesn't seem to have a way to get the directory
            val parent = os.Path(path.toIO.getParentFile)
            os.move(path, parent / path.baseName)
          }
        }
      }

      platform -> PathRef(dir)
    }
  }

  // Directories that will be turned into platform-specific classifier jars
  def classifierDirs = T {
    extractedDirs().map { case (platform, dir) =>
      os.copy.into(dir.path, T.dest)
      // We added a platform directory above in extractedDirs, remove it to get actual root
      val rootDir = T.dest / platform.toString

      platform -> PathRef(rootDir)
    }
  }

  // Classifier jars will be included as extras so that platform-specific jars can be fetched
  def classifierJars = T {
    classifierDirs().map { case (platform, dir) =>
      val jarPath = T.dest / s"$platform.jar"
      Jvm.createJar(
        jarPath,
        Agg(dir.path, fnddsMetadata().path),
        mill.api.JarManifest.MillDefault,
        (_, _) => true
      )
      platform -> PathRef(jarPath)
    }
  }

  def extraPublish = T {
    classifierJars().map { case (platform, jar) =>
      PublishInfo(
        jar,
        classifier = Some(platform.toString),
        ext = "jar",
        ivyConfig = "compile" // TODO is this right?
      )
    }
  }

  def fnddsMetadata = T {
    // Then get the baseDir from there
    val baseDir = getBaseDir(T.dest)
    os.makeDir.all(baseDir)
    os.write(baseDir / "FNDDS.version", FNDDSSpecVersion)
    os.write(baseDir / "project.version", publishVersion())
    PathRef(T.dest)
  }

  def localClasspath = T {
    super.localClasspath() ++ extractedDirs().map { case (_, dir) => dir } ++ Seq(fnddsMetadata())
  }
}

// firtool-resolver is a shell project (no source files) that wraps an inner core project
// The core project is assembled (with dependencies like the scala standard lbirary removed)
// firtool-resolver then publishes this "assembly" with dynamic dependencies
//
// ******************** WARNING ********************
// This is extremely manual and changing dependencies IN ANY WAY (including bumping version)
// requires carefully checking the packages to shade and dynamic ivy deps in the outer project
object `firtool-resolver` extends Cross[FirtoolResolver]("2.13", "2.12")
trait FirtoolResolver extends CrossScalaModule with ChipsAlliancePublishModule { root =>

  override def crossScalaVersion = Map(
    "2.13" -> "2.13.11",
    "2.12" -> "2.12.18"
  )(crossValue)

  def publishVersion = VcsVersion.vcsState().format(countSep = "+", untaggedSuffix = "-SNAPSHOT")

  def isSnapshot = T { publishVersion().endsWith("-SNAPSHOT") }

  def pomSettings = PomSettings(
    description = "Fetcher for native firtool binary",
    organization = "org.chipsalliance",
    url = "https://chipsalliance.org",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "llvm-firtool"),
    developers = Seq(
      Developer("jackkoenig", "Jack Koenig", "https://github.com/jackkoenig")
    )
  )

  // The outer project has no sources
  def sources = T { Seq.empty[PathRef] }

  def mainClass = T { Some("firtoolresolver.Main") }

  // We unzip the core assembly into our unmanaged classpath so it can then be
  // rezipped up into our jar (and published).
  def unmanagedClasspath = T {
    val dir = T.dest
    os.remove.all(dir)
    os.makeDir(dir)
    os.proc("unzip", core.assembly().path)
      .call(cwd = dir)
    Agg(PathRef(dir))
  }

  // TODO These are the things stripped from upstreamAssemblyClasspath (in addition to scala-library).
  // Is it possible to derive these automatically?
  def ivyDeps = Agg(
    ivy"org.scala-lang.modules::scala-xml:2.2.0",
    ivy"org.scala-lang.modules::scala-collection-compat:2.11.0"
  )

  object core extends ScalaModule {

    def scalaVersion = root.scalaVersion

    // The inner project has the sources
    def millSourcePath = root.millSourcePath

    // We cannot use os-lib because it cannot be shaded without screwing up
    // getting System property os.name
    def ivyDeps = Agg(
      ivy"dev.dirs:directories:26",
      ivy"io.get-coursier::coursier:2.1.8",
    )

    // Modify the classpath to remove things we want to dynamically link (Scala jars).
    override def upstreamAssemblyClasspath: T[Agg[PathRef]] = T {
      val all = super.upstreamAssemblyClasspath()
      // Remove Scala jars.
      // This removes scala-library, scala-collection-compat, and scala-xml.
      val regex = """.*/scala[^\/]*\.jar""".r
      all.filterNot(path => regex.matches(path.toString))
    }

    // Our assembly is not intended to be an executable.
    // Including the launcher causes unzip to fail.
    override def prependShellScript: T[String] = T { "" }

    // It would be nice if this could be derived from dependencies.
    def packagesToShade = Seq(
      "dev.dirs",
      "coursier",
      "com.github.plokhotnyuk",
      "concurrentrefhashmap",
      "org.codehaus",
      "scala.cli",
      "io.github.alexarchambault",
      "javax.inject",
      "org.apache.commons",
      "org.slf4j",
      "org.iq80.snappy",
      "org.tukaani.xz",
      "com.github.luben.zstd",
      "org.apache.xbean",
      "org.fusesource.jansi",
      "org.fusesource.hawtjni",
    )

    override def assemblyRules =
      Seq(
        Rule.ExcludePattern("META-INF/.*"),
      ) ++ packagesToShade.map(p =>
        Rule.Relocate(s"$p.**", s"firtoolresolver.shaded.$p.@1")
      )
  }
}
