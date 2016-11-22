package org.jetbrains.jps.incremental.scala

import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.DirtyFilesHolder
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor
import org.jetbrains.jps.incremental.ModuleLevelBuilder.{ExitCode, OutputConsumer}
import org.jetbrains.jps.incremental.{BuilderCategory, CompileContext, ModuleBuildTarget, ModuleLevelBuilder}

/**
  * Created by jast on 2016-11-21.
  */
class SbtServerBuilder extends ModuleLevelBuilder(BuilderCategory.TRANSLATOR) {
  override def build(context: CompileContext,
                     chunk: ModuleChunk,
                     dirtyFilesHolder: DirtyFilesHolder[JavaSourceRootDescriptor, ModuleBuildTarget],
                     outputConsumer: OutputConsumer): ExitCode = {

    ???
  }

  override def getPresentableName: String = "SBT server builder"
}
