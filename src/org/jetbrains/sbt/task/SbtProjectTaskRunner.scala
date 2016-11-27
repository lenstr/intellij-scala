package org.jetbrains.sbt.task

import java.io.OutputStreamWriter
import java.util

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.task._
import org.jetbrains.annotations.Nullable
import org.jetbrains.sbt.shell.SbtProcessComponent

/**
  * Created by jast on 2016-11-25.
  */
class SbtProjectTaskRunner extends ProjectTaskRunner {

  // FIXME should be based on a config option
  // will override the usual jps build thingies
  override def canRun(projectTask: ProjectTask): Boolean = true

  override def run(project: Project,
                   context: ProjectTaskContext,
                   callback: ProjectTaskNotification,
                   tasks: util.Collection[_ <: ProjectTask]): Unit = {

    val handler = SbtProcessComponent.forProject(project).acquireShellProcessHandler

    // TODO mroe robust way to get the writer
    val shell = new OutputStreamWriter(handler.getProcessInput)

    // TODO build selected module?
    shell.write("products\n")
    shell.flush()
  }

  @Nullable
  override def createExecutionEnvironment(project: Project,
                                          task: ExecuteRunConfigurationTask,
                                          executor: Executor): ExecutionEnvironment = {

      val taskSettings = new ExternalSystemTaskExecutionSettings
      val executorId = Option(executor).map(_.getId).getOrElse(DefaultRunExecutor.EXECUTOR_ID)

      ExternalSystemUtil.createExecutionEnvironment(
        project,
        new ProjectSystemId("SBT"),
        taskSettings, executorId
      )
  }
}
