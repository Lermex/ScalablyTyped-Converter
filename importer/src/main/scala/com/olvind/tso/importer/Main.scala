package com.olvind.tso
package importer

import java.io.FileWriter
import java.time.Instant
import java.util.concurrent._

import ammonite.ops._
import com.olvind.logging
import com.olvind.logging.Logger.Stored
import com.olvind.logging.{LogLevel, LogRegistry}
import com.olvind.tso.importer.PersistedFunction.nameAndMtimeUnder
import com.olvind.tso.importer.build.BloopCompiler
import com.olvind.tso.importer.jsonCodecs._
import com.olvind.tso.phases.{PhaseRunner, RecPhase}
import com.olvind.tso.scalajs._
import com.olvind.tso.ts.TsSource.FromFile
import com.olvind.tso.ts._
import com.olvind.tso.ts.parser.parseFile

import scala.collection.parallel.ForkJoinTaskSupport

object Main extends App {
  val debugMode    = args.nonEmpty
  val cacheFolder  = home / 'tmp / "tso-cache"
  val targetFolder = cacheFolder / Name.OutputPkg.value
  val logsFolder   = cacheFolder / 'logs

  mkdir(targetFolder)
  mkdir(logsFolder)

  if (!exists(targetFolder / ".git")) {
    if (constants.freshRepo) {
      implicit val wd = targetFolder
      % git 'init
      val gitIgnore = wd / ".gitignore"
      files.softWrite(gitIgnore) { pw =>
        pw.println("target/")
        pw.println(".idea/")
      }
      %%('git, 'add, gitIgnore)
      %% git ('remote, 'add, 'origin, constants.ScalablyTypedRepo)
      %% git ('branch, 'add, "--set-upstream-to=origin/master")

    } else {
      implicit val wd = cacheFolder
      % git ('clone, constants.ScalablyTypedRepo)
    }
  } else {
    implicit val wd = targetFolder
    % git ('clean, "-qfdx")
    % git 'pull
  }

  val storingErrorLogger = logging.storing()

  val logger = {
    val logFile = new FileWriter((logsFolder / s"${Instant.now()}.log").toIO)
    val base    = logging.appendable(logFile).zipWith(storingErrorLogger.filter(LogLevel.error))
    if (debugMode) base.zipWith(logging.stdout) else base
  }

  val logRegistry = new LogRegistry[TsSource, TsIdentLibrary, Array[Stored]](
    logger.filter(LogLevel.warn).syncAccess.void,
    _.inLibrary.libName,
    _ => logging.storing()
  )

  val dtFolder: InFolder =
    UpToDateDefinitelyTyped(cacheFolder, constants.DefinitelyTypedRepo)

  val external: NotNeededPackages =
    Json[NotNeededPackages](dtFolder.path / up / "notNeededPackages.json")

  val externalsFolder: InFolder =
    UpToDateExternals(
      logger.void,
      cacheFolder / 'npm,
      external.packages.map(_.typingsPackageName).to[Set] + "typescript" ++ constants.extraExternals,
      constants.ignored
    )

  val stdLibSource: TsSource =
    FromFile(InFile(externalsFolder.path / "typescript" / "lib" / "lib.esnext.full.d.ts"), TsIdentLibrarySimple("std"))

  val sources: Seq[InFolder] = Seq(dtFolder, externalsFolder)

  val persistedParser: InFile => Either[String, TsParsedFile] =
    PersistedFunction(nameAndMtimeUnder(cacheFolder / 'parse / BuildInfo.parserHash.toString), logger.void)(
      parseFile
    )

  val tsSources: Set[TsSource] =
    (TypescriptSources(externalsFolder, dtFolder, constants.ignored), args.to[List]) match {
      case (sources, Nil) => sources
      case (sources, wantedLibsStrings) =>
        val wantedLibNames: Set[TsIdentLibrary] =
          wantedLibsStrings.map(libName => ModuleNameParser(TsLiteralString(libName)).inLibrary).to[Set]

        sources.filter(s => wantedLibNames(s.inLibrary.libName))
    }

  val bloop = BloopCompiler(logger.filter(LogLevel.debug).void)

  val Phase: RecPhase[TsSource, PublishedSbtProject] =
    RecPhase[TsSource]
      .next(new PhaseReadTypescript(sources, constants.ignored, stdLibSource, persistedParser), "typescript")
      .next(PhaseToScalaJs, "scala.js")
      .next(PhaseCompileBloop(bloop, OutFolder(targetFolder), Name.OutputPkg, home / ".ivy2" / "local"), "build")

  val interface = new Interface(debugMode, storingErrorLogger)
  interface.start()

  /* todo: parallel collections suck, but are super easy to use. We'll settle with that for now */
  val par  = tsSources.par
  val pool = new ForkJoinPool(3)

  par.tasksupport = new ForkJoinTaskSupport(pool)
  par.map(source => PhaseRunner.go(Phase, source, Nil, logRegistry.get, interface)).seq
  pool.shutdown()

  logRegistry.logs.foreach {
    case (libName, storeds) =>
      files.softWrite(logsFolder / RelPath(libName.`__value` + ".log")) { w1 =>
        storeds.underlying.foreach { stored =>
          {
            val str = LibraryPattern(stored.message, stored.throwable, stored.metadata, stored.ctx).plainText
            w1.append(str)
            w1.append("\n")
          }
        }
      }
  }

  val summary = interface.finish()

  if (debugMode) {
    System.err.println(s"Not committing because of non-empty args ${args.mkString(", ")}")
  } else {
    CommitRun(summary)(targetFolder)
  }

  System.exit(0)
}
