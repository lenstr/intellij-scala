package org.jetbrains.sbt.shell

import java.util

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.{ExternalSystemApiUtil, ExternalSystemUtil}
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.task._
import org.jetbrains.annotations.Nullable
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.module.SbtModuleType

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/**
  * Created by jast on 2016-11-25.
  */
class SbtProjectTaskRunner extends ProjectTaskRunner {

  // FIXME should be based on a config option
  // will override the usual jps build thingies
  override def canRun(projectTask: ProjectTask): Boolean = projectTask match {
    case task: ModuleBuildTask =>
      val module = task.getModule
      ModuleType.get(module) match {
        case _: SbtModuleType =>
          // SbtModuleType actually denotes `-build` modules, which are not part of the regular build
          false
        case _ =>
          ExternalSystemApiUtil.isExternalSystemAwareModule(SbtProjectSystem.Id, module)
      }
    case _: ArtifactBuildTask =>
      // TODO should sbt handle this?
      false
    case _: ExecuteRunConfigurationTask =>
      // TODO this includes tests (and what else?). sbt should handle it and test output should be parsed
      false
    case _ => false
  }

  override def run(project: Project,
                   context: ProjectTaskContext,
                   callback: ProjectTaskNotification,
                   tasks: util.Collection[_ <: ProjectTask]): Unit = {

    val callbackOpt = Option(callback)
    val shell = new SbtShellCommunication(project)

    // TODO run task for selected modules directly instead of from root module
    // may require collecting results individually and aggregating
    // shell communication should do proper queueing
    shell.task("{.}/products")
      .onComplete {
      case Success(taskResult) =>
        callbackOpt.foreach(_.finished(taskResult))
      case Failure(x) =>
        // TODO some kind of feedback / rethrow
    }
  }

  @Nullable
  override def createExecutionEnvironment(project: Project,
                                          task: ExecuteRunConfigurationTask,
                                          executor: Executor): ExecutionEnvironment = {

    val taskSettings = new ExternalSystemTaskExecutionSettings
    val executorId = Option(executor).map(_.getId).getOrElse(DefaultRunExecutor.EXECUTOR_ID)

    ExternalSystemUtil.createExecutionEnvironment(
      project,
      SbtProjectSystem.Id,
      taskSettings, executorId
    )
  }
}
