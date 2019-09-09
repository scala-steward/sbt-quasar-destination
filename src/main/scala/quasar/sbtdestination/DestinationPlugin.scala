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

import scala.Predef.{ArrowAssoc, String}
import scala.collection.Seq
import scala.concurrent.ExecutionContext

import cats.effect.IO
import coursier.interop.cats._
import sbt._, Keys._

object DestinationPlugin extends AutoPlugin {

  private implicit val globalContextShift =
    IO.contextShift(ExecutionContext.Implicits.global)

  object autoImport {
    val destinationName: SettingKey[String] = settingKey[String]("The short name of the destination (e.g. 's3', 'azure').")
    val destinationDependencies: SettingKey[Seq[ModuleID]] = settingKey[Seq[ModuleID]]("Declares the non-quasar managed dependencies.")
    val destinationModuleFqcn: SettingKey[String] = settingKey[String]("The fully qualified class name of the destination module.")
    val destinationQuasarVersion: SettingKey[String] = settingKey[String]("Defines the version of Quasar to depend on.")

    val destinationAssemblePlugin: TaskKey[File] = taskKey[File]("Produces the tarball containing the destination plugin and all non-quasar dependencies.")
  }

  import autoImport._

  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(

    destinationDependencies := Seq.empty,

    libraryDependencies := {
      libraryDependencies.value ++ destinationDependencies.value ++ Seq(
        "com.slamdata" %% "quasar-connector" % destinationQuasarVersion.value,
        "com.slamdata" %% "quasar-connector" % destinationQuasarVersion.value % Test classifier "tests"
      )
    },

    packageOptions in (Compile, packageBin) +=
      Package.ManifestAttributes("Datasource-Module" -> destinationModuleFqcn.value),

    destinationAssemblePlugin := {
      val pluginPath =
        AssemblePlugin[IO, IO.Par](
          destinationName.value,
          version.value,
          destinationDependencies.value,
          (Keys.`package` in Compile).value.toPath,
          destinationQuasarVersion.value,
          (scalaBinaryVersion in Compile).value,
          (crossTarget in Compile).value.toPath)

      pluginPath.map(_.toFile).unsafeRunSync()
    }
  )
}
