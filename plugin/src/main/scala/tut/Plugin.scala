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

  def safeListFiles(dir: File): List[File] =
    Option(dir.listFiles).fold(List.empty[File])(_.toList)

  def flatten(f: File): List[File] =
    f :: (if (f.isDirectory) f.listFiles.toList.flatMap(flatten) else Nil)

  lazy val tutSettings =
    Seq(
      resolvers += "tpolecat" at "http://dl.bintray.com/tpolecat/maven",
      libraryDependencies += "org.tpolecat" %% "tut-core" % "0.4.2-SNAPSHOT" % "test",
      tutSourceDirectory := sourceDirectory.value / "main" / "tut",
      tutTargetDirectory := crossTarget.value / "tut",
      watchSources <++= tutSourceDirectory map { path => (path ** "*.md").get },
      tutScalacOptions := (scalacOptions in Test).value,
      tutNameFilter := """.*\.(md|txt|htm|html)""".r,
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
        toError(r.run("tut.TutMain", 
                      data(cp), 
                      Seq(in.getAbsolutePath, out.getAbsolutePath, re) ++ opts ++ pOpts, 
                      streams.value.log))
        // We can't return a value from the runner, but we know what TutMain is looking at so we'll
        // fake it here. Returning all files potentially touched.
        val read = safeListFiles(in).map(_.getName).toSet
        safeListFiles(out).filter(f => read(f.getName)).map(f => f -> f.getName)
      },
      tutOnly <<= InputTask.createDyn{
        Def.setting{ s: State =>
          Project.extract(s).getOpt(tutSourceDirectory) match {
            case Some(dir) =>
              val files = safeListFiles(dir).flatMap(flatten)
              val parsers = files.map(f => literal(dir.toURI.relativize(f.toURI).getPath).map(_ => f))
              val folded = parsers.foldRight[Parser[File]](failure("<no input files>"))(_ | _)
              Space ~> token(folded)
            case None =>
              Space ~> failure("<no input files>")
          }
        }
      } {
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
            toError(r.run("tut.TutMain",
                          data(cp),
                          Seq(in.getAbsolutePath, out.getAbsolutePath, re) ++ opts ++ pOpts,
                          streams.value.log))
          }
        }
      }
    )

}

