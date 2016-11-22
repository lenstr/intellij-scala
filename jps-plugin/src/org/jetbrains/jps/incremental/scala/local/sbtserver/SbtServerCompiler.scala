package org.jetbrains.jps.incremental.scala.local.sbtserver

import org.jetbrains.jps.incremental.scala.Client
import org.jetbrains.jps.incremental.scala.data.CompilationData
import org.jetbrains.jps.incremental.scala.local.AbstractCompiler

/**
  * Created by jast on 2016-11-21.
  */
class SbtServerCompiler() extends AbstractCompiler {
  override def compile(compilationData: CompilationData, client: Client): Unit = {
    ???
  }
}
