package io.monix.website

import coursier._
import coursier.util.Parse
import java.io.File
import java.net.URLClassLoader
import scala.util.control.NonFatal

case class FrontMatter(tut: Tut)

case class VersionsSet(
  version1x: String,
  version2x: String,
  version3x: String
)

case class ConfigFile(
  code: VersionsSet,
  promoted: VersionsSet
)

case class Tut(
  scala: String,
  binaryScala: String,
  dependencies: Option[List[String]],
  scalacOptions: Option[List[String]],
  plugins: Option[List[String]]) {

  val tutResolution: Resolution = Resolution(Set(
    Dependency(Module("org.tpolecat", s"tut-core_$binaryScala"), BuildInfo.tutVersion)
  ))

  private def mkDependencies(strs: List[String]): Set[Dependency] =
    strs.map { dep =>
      val (mod, v) = Parse.moduleVersion(dep, binaryScala).right.get
      Dependency(mod, v)
    }.toSet

  def parsedDependencies(config: ConfigFile): List[String] =
    dependencies.getOrElse(Nil).map { uri =>
      uri.replaceAll("version1x", config.code.version1x)
         .replaceAll("version2x", config.code.version2x)
         .replaceAll("version3x", config.code.version3x)
    }

  def libResolution(config: ConfigFile): Resolution =
    Resolution(parsedDependencies(config).map { dep =>
      val (mod, v) = Parse.moduleVersion(dep, BuildInfo.scalaVersion).right.get
      Dependency(mod, v)
    }.toSet)

  val pluginExclusions = Set("scala-compiler", "scala-library", "scala-reflect").map(("org.scala-lang", _))
  val pluginDependencies: Set[Dependency] = mkDependencies(plugins.getOrElse(Nil)).map(_.copy(exclusions = pluginExclusions))
  val pluginResolution: Resolution = Resolution(pluginDependencies)

  def invoke(config: ConfigFile, in: File, out: File): Unit = {
    val tutClasspath = resolve(tutResolution).get
    val libClasspath = resolve(libResolution(config)).get
    val pluginClasspath = resolve(pluginResolution).get

    val classLoader = new URLClassLoader(tutClasspath.map(_.toURI.toURL).toArray, null)
    val tutClass = classLoader.loadClass("tut.TutMain")
    val tutMain = tutClass.getDeclaredMethod("main", classOf[Array[String]])

    val commandLine = Array(
      in.getAbsolutePath,
      out.getParentFile.getAbsolutePath,
      ".*",
      "-classpath",
      libClasspath.mkString(File.pathSeparator),
      s"-Xplugin:${pluginClasspath.mkString(File.pathSeparator)}"
    ) ++ scalacOptions.getOrElse(Nil)

    try {
      try tutMain.invoke(null, commandLine)
      finally classLoader.close()
    } catch {
      case NonFatal(e) =>
        out.delete()
        throw e
    }
  }
}
