/*
 * Copyright 2014–2019 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.sbtdestination

import scala.{Array, List, StringContext}
import scala.Predef.{ArrowAssoc, String, genericWrapArray, println}
import scala.collection.Seq
import scala.sys.process._

import java.io.{File, IOException}
import java.lang.{Exception, SuppressWarnings}
import java.nio.file.{Path, Files, SimpleFileVisitor, FileVisitResult}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW

import cats.Parallel
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.instances.int._
import cats.instances.list._
import cats.instances.string._
import cats.syntax.eq._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._

import coursier._
import coursier.cache._
import coursier.util.{Sync => CSync}

import io.circe.Json

import sbt.{CrossVersion, ModuleID}

/** Assemble a destination plugin tarball, returning the path to the artifact. */
object AssemblePlugin {
  @SuppressWarnings(Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.ToString"))
  def apply[F[_]: CSync: Sync, G[_]](
      dsName: String,
      dsVersion: String,
      dsDependencies: Seq[ModuleID],
      dsJar: Path,
      quasarVersion: String,
      scalaBinaryVersion: String,
      dstDir: Path)(
      implicit P: Parallel[F, G])
      : F[Path] = {

    // We assemble every component of the final tarball in this folder
    val buildDir = dstDir.resolve("plugin-build")

    // Will contain the dependencies and the plugin jar itself
    val pluginDir = buildDir.resolve(dsName)

    // The jar file's own path.
    val jarPath = pluginDir.resolve(dsJar.getFileName)

    // the destination jar's path relative to the build dir
    // included in the generated .plugin file to let quasar
    // know where to load it from.
    val relativeJarPath = buildDir.relativize(jarPath)

    // the path to the generated .plugin file.
    val pluginPath = buildDir.resolve(s"$dsName.plugin")

    // start coursier on resolving all of the destination's
    // dependencies, *except* for quasar. quasar and its
    // dependencies are already present in the user's
    // `plugins` folder.
    val resolution =
      Resolution(dsDependencies.map(moduleIdToDependency(_, scalaBinaryVersion)))

    val cache =
      FileCache[F]()
        .withLocation(buildDir.toFile)
        .withCachePolicies(List(CachePolicy.Update)).fetch

    type OrFileErrors[A] = ValidatedNel[ArtifactError, A]

    for {
      _ <- Sync[F] delay {
        if (Files.exists(buildDir)) {
          Files.walkFileTree(buildDir, RecursiveDeleteVisitor)
        }
      }

      _ <- Sync[F].delay(Files.createDirectories(pluginDir))

      _ <- Sync[F].delay(println("Fetching artifacts with coursier..."))

      copyJar = Sync[F].delay(Files.copy(dsJar, jarPath, REPLACE_EXISTING))

      fetchJarFiles = for {
        metadata <- ResolutionProcess(resolution).run(
          // we don't use ~/.ivy2/local here because
          // I've heard that coursier doesn't copy
          // from local caches into other local caches.
          ResolutionProcess.fetch(
            Seq(
              MavenRepository("https://repo1.maven.org/maven2"),
              MavenRepository("https://dl.bintray.com/slamdata-inc/maven-public")),
            cache))

        // fetch artifacts in parallel into cache
        pluginCache =
          FileCache[F]()
            .withLocation(pluginDir.toFile)
            .withCachePolicies(List(CachePolicy.Update))

        artifactsPar <-
          metadata.artifacts().toList.parTraverse(f =>
            pluginCache.file(f).run)

        // some contortions to make sure *all* errors
        // are reported when any fail to download.
        artifacts <-
          artifactsPar.traverse[OrFileErrors, File](_.toValidatedNel).fold(
            es => Sync[F].raiseError(new Exception(s"Failed to fetch files: ${es.foldMap(e => e.toString + "\n\n")}")),
            Sync[F].pure(_))

        // filter out coursier metadata, we only want the jars
        // for the `classpath` field of the .plugin file
        jarFiles = artifacts.filter(_.getName.endsWith(".jar"))
      } yield jarFiles

      // coursier prefers that we fetch metadata before fetching
      // artifacts. we do that in parallel with copying the destination
      // jar to its new place, because they don't depend on one
      // another.
      fetchedJarFiles <- copyJar &> fetchJarFiles

      _ <- Sync[F].delay(println("Artifacts fetched. Preparing to write .plugin file..."))

      // the .plugin file requires all dependency jar paths
      // to be relative to the plugins folder
      classPath =
        fetchedJarFiles.map(f => buildDir.relativize(f.toPath)) ::: List(relativeJarPath)

      cpJson = Json.arr(classPath.map(p => Json.fromString(p.toString)) : _*)
      mainJar = Json.fromString(relativeJarPath.toString)

      // include the destination jar and classpath into the .plugin file
      outJson = Json.obj("mainJar" -> mainJar, "classPath" -> cpJson).spaces2

      // delete an old .plugin file, write the new one
      _ <- Sync[F] delay {
        Files.deleteIfExists(pluginPath)
        Files.write(pluginPath, outJson.getBytes, CREATE_NEW)
      }

      _ <- Sync[F].delay(println(".plugin file written. Zipping up tarball..."))

      // equivalent to `ls $buildDir`, the files and folders we need to zip up
      // to make a valid `plugins` folder
      files <- Sync[F] delay {
        Files.list(buildDir).map(buildDir.relativize(_)).toArray.mkString(" ")
      }

      // the `plugins` tarball's location
      tarPath = dstDir.resolve(s"$dsName-$dsVersion-q$quasarVersion-explode.tar.gz")

      // TODO: make this portable
      //
      // the command we run to finish up: zip up (-c) all of
      // the files in our plugins folder ($files), with the
      // plugins folder as "root" of the tarball (-C) and
      // put the tarball into the artifacts folder.
      exitCode <- Sync[F].delay(s"tar -czf $tarPath -C $buildDir/ $dsName $dsName.plugin".!)

      _ <- Sync[F] delay {
        if (exitCode === 0)
          println(s"Tarball written to ${tarPath}.")
        else
          println(s"WARNING: Nonzero exit code when writing tarball at ${tarPath}.")
      }
    } yield tarPath
  }

  // SBT needs `ModuleId` to declare dependencies with
  // `libraryDependencies`, and coursier wants `Dependency`.
  // Converting is straightforward but requires knowing the
  // `scalaVersion`; I've hard-coded it to 2.12 here.
  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private def moduleIdToDependency(moduleId: ModuleID, scalaBinaryVersion: String): Dependency = {
    val v =
      if (moduleId.crossVersion == CrossVersion.disabled) ""
      else "_" + scalaBinaryVersion
    Dependency(
      Module(Organization(moduleId.organization), ModuleName(moduleId.name + v)),
      moduleId.revision)
  }

  private object RecursiveDeleteVisitor extends SimpleFileVisitor[Path] {
    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      Files.delete(file)
      FileVisitResult.CONTINUE
    }

    @SuppressWarnings(Array(
      "org.wartremover.warts.Equals",
      "org.wartremover.warts.Throw"))
    override def postVisitDirectory(dir: Path, ioe: IOException): FileVisitResult =
      if (ioe == null) {
        Files.delete(dir)
        FileVisitResult.CONTINUE
      } else {
        throw ioe
      }
  }
}
