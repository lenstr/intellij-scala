package org.jetbrains.jps.incremental.scala.local.sbtserver

import java.io.File

import org.jetbrains.jps.builders.{BuildRootDescriptor, BuildTarget}
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.ModuleLevelBuilder.OutputConsumer
import org.jetbrains.jps.incremental.scala.local.IdeClient

/**
  * Created by jast on 2016-11-21.
  */
class IdeClientSbtServer(compilerName: String,
                         context: CompileContext,
                         modules: Seq[String],
                         consumer: OutputConsumer,
                         sourceToTarget: File => Option[BuildTarget[_ <: BuildRootDescriptor]])
  extends IdeClient(compilerName, context, modules, consumer) {

  override def generated(source: File, module: File, name: String): Unit = ???

  override def processed(source: File): Unit = ???
}
