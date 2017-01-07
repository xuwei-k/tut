package tut

import scala.util.matching.Regex

import sbt._
import sbt.Keys._
import sbt.Defaults.runnerInit
import sbt.Attributed.data
import sbt.complete.Parser
import sbt.complete.DefaultParsers._

object Plugin extends sbt.Plugin {

  type Dir = File

  lazy val tut                = TaskKey[Seq[(File,String)]]("tut", "create tut documentation")
  lazy val tutSourceDirectory = SettingKey[File]("tutSourceDirectory", "where to look for tut sources")
  lazy val tutScalacOptions   = TaskKey[Seq[String]]("tutScalacOptions", "scalac options")
  lazy val tutPluginJars      = TaskKey[Seq[File]]("tutPluginJars", "Plugin jars to be used by tut REPL.")
  lazy val tutOnly            = inputKey[Unit]("Run tut on a single file.")
  lazy val tutTargetDirectory = SettingKey[File]("tutTargetDirectory", "Where tut output goes")
  lazy val tutNameFilter      = SettingKey[Regex]("tutNameFilter", "tut skips files whose names don't match")
  lazy val tutFiles           = SettingKey[State => Parser[File]]("tutFiles", "parser identifying files visible to tut")
  lazy val tutQuick           = TaskKey[Set[File]]("tutQuick", "Run tut incrementally on recently changed files")
  lazy val tutQuickCache      = SettingKey[File]("tutQuickCache", "Cache directory for tutQuick metadata")

  def safeListFiles(dir: File, recurse: Boolean): List[File] =
    Option(dir.listFiles).fold(List.empty[File]){ files =>
      val l = files.toList
      if (recurse) l.flatMap(flatten) else l
    }

  def safeRelativize(in: File, out: File, recurse: Boolean): List[(File, String)] = {
    val inPath = in.toPath
    val outPath = out.toPath
    val read = safeListFiles(in, recurse = true).map(f => inPath.relativize(f.toPath)).toSet
    safeListFiles(out, recurse = true).flatMap { f =>
      val rel = outPath.relativize(f.toPath)
      if (read(rel)) (f -> rel.toString) :: Nil else Nil
    }
  }

  def flatten(f: File): List[File] =
    f :: (if (f.isDirectory) f.listFiles.toList.flatMap(flatten) else Nil)

  def tutFilesParser(state: State): Parser[File] = {
    val extracted = Project.extract(state)
    val dir     = extracted.getOpt(tutSourceDirectory)
    val files   = dir.fold(List.empty[File])(d => safeListFiles(d, recurse = true))
    val parsers = dir.fold(List.empty[Parser[File]])(dir => files.map(f => literal(dir.toURI.relativize(f.toURI).getPath).map(_ => f)))
    val folded  = parsers.foldRight[Parser[File]](failure("<no input files>"))(_ | _)
    token(folded)
  }

  /** Run the Tut CLI for a single input file or directory */
  def tutOne(streams: TaskStreams, r: ScalaRun, in: File, out: File, cp: Classpath, opts: Seq[String], pOpts: Seq[String], re: String): List[(File, String)] = {
    toError(r.run("tut.TutMain",
      data(cp),
      Seq(in.getAbsolutePath, out.getAbsolutePath, re) ++ opts ++ pOpts,
      streams.log))
    // We can't return a value from the runner, but we know what TutMain is looking at so we'll
    // fake it here. Returning all files potentially touched.
    safeRelativize(in, out, recurse = true)
  }

  /** Run the Tut CLI repeatedly for a list of input files or directories */
  def tutAll(streams: TaskStreams, r: ScalaRun, in: List[File], out: List[File], cp: Classpath, opts: Seq[String], pOpts: Seq[String], re: String): List[(File, String)] =
    (in zip out).flatMap { case (in, out) => tutOne(streams, r, in, out, cp, opts, pOpts, re ) }

  lazy val tutSettings =
    Seq(
      resolvers += "tpolecat" at "http://dl.bintray.com/tpolecat/maven",
      libraryDependencies += "org.tpolecat" %% "tut-core" % BuildInfo.version % "test",
      tutSourceDirectory := sourceDirectory.value / "main" / "tut",
      tutTargetDirectory := crossTarget.value / "tut",
      watchSources <++= tutSourceDirectory map { path => (path ** "*.md").get },
      tutScalacOptions := {
        val testOptions = scalacOptions.in(Test).value
        val unwantedOptions = Set("-Ywarn-unused-import")
        testOptions.filterNot(unwantedOptions)
      },
      tutNameFilter := """.*\.(md|markdown|txt|htm|html)""".r,
      tutFiles := tutFilesParser,
      tutPluginJars := {
        // no idea if this is the right way to do this
        val deps = (libraryDependencies in Test).value.filter(_.configurations.fold(false)(_.startsWith("plugin->")))
        update.value.configuration("plugin").map(_.modules).getOrElse(Nil).filter { m =>
          deps.exists { d =>
            d.organization == m.module.organization &&
            d.name         == m.module.name &&
            d.revision     == m.module.revision
          }
        }.flatMap(_.artifacts.map(_._2))
      },
      tut := {
        val r     = (runner in Test).value
        val in    = tutSourceDirectory.value
        val out   = tutTargetDirectory.value
        val cp    = (fullClasspath in Test).value
        val opts  = tutScalacOptions.value
        val pOpts = tutPluginJars.value.map(f => "–Xplugin:" + f.getAbsolutePath)
        val re    = tutNameFilter.value.pattern.toString
        tutOne(streams.value, r, in, out, cp, opts, pOpts, re)
      },
      tutOnly := InputTask.createDyn(Def.setting((state: State) => Space ~> tutFilesParser(state))) {
        Def.task{ in =>
          Def.task{
            val r     = (runner in Test).value
            val inR   = tutSourceDirectory.value // input root
            val inDir = if (in.isDirectory) in
                        else in.getParentFile    // input dir
            val outR  = tutTargetDirectory.value // output root
            val out   = new File(outR, inR.toURI.relativize(inDir.toURI).getPath) // output dir
            val cp    = (fullClasspath in Test).value
            val opts  = tutScalacOptions.value
            val pOpts = tutPluginJars.value.map(f => "–Xplugin:" + f.getAbsolutePath)
            val re    = tutNameFilter.value.pattern.toString
            tutOne(streams.value, r, in, out, cp, opts, pOpts, re)
          }
        }
      }.evaluated,
      tutQuickCache := cacheDirectory.value / "tut",
      tutQuick := {
        val r     = (runner in Test).value
        val inR   = tutSourceDirectory.value
        val outR  = tutTargetDirectory.value
        val cp    = (fullClasspath in Test).value
        val opts  = tutScalacOptions.value
        val pOpts = tutPluginJars.value.map(f => "–Xplugin:" + f.getAbsolutePath)
        val re    = tutNameFilter.value.pattern.toString
        val cache = tutQuickCache.value

        def handleUpdate(inReport: ChangeReport[File], outReport: ChangeReport[File]) = {
          val in    = (inReport.modified -- inReport.removed).toList
          val out   = in.map { in =>
            val inDir = if (in.isDirectory) in else in.getParentFile    // input dir
            new File(outR, inR.toURI.relativize(inDir.toURI).getPath) // output dir
          }

          tutAll(streams.value, r, in, out, cp, opts, pOpts, re).map(_._1).toSet
        }

        val files = safeListFiles(inR, recurse = true).toSet

        FileFunction.cached(cache)(FilesInfo.hash, FilesInfo.exists)(handleUpdate)(files)
      }
    )

}

