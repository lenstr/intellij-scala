package org.jetbrains.sbt.task

import java.io.{OutputStreamWriter, PrintWriter}
import java.util

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.{ProcessAdapter, ProcessEvent}
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.task._
import org.jetbrains.annotations.Nullable
import org.jetbrains.sbt.shell.SbtProcessManager

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

import scala.concurrent.ExecutionContext.Implicits.global

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

    val callbackOpt = Option(callback)
    val handler = SbtProcessManager.forProject(project).acquireShellProcessHandler
    val listener = new SbtProcessListener
    handler.addProcessListener(listener)
    listener.future.andThen {
      case _ => handler.removeProcessListener(listener)
    }.recover {
      case _ =>
        // TODO some kind of feedback / rethrow
        new ProjectTaskResult(true, 1, 0)
    }.onComplete {
      case Success(taskResult) =>
        callbackOpt.foreach(_.finished(taskResult))
      case Failure(x) =>
        // TODO some kind of feedback / rethrow
    }

    // TODO more robust way to get the writer? cf createOutputStreamWriter.createOutputStreamWriter
    val shell = new PrintWriter(new OutputStreamWriter(handler.getProcessInput))

    // TODO queue the task instead of direct execution.
    // we want to avoid registering multiple callbacks to the same output and having whatever side effects
    // TODO build selected module?
    shell.println("products")
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

  class SbtProcessListener extends ProcessAdapter {

    private var success = false
    private var errors = 0
    private var warnings = 0

    private val promise = Promise[ProjectTaskResult]()

    def future: Future[ProjectTaskResult] = promise.future

    override def processTerminated(event: ProcessEvent): Unit = {
      val res = new ProjectTaskResult(true, errors, warnings)
      promise.complete(Success(res))
    }

    override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit = {
      val text = event.getText
      if (text startsWith "[error]") {
        success = false
        errors += 1
      } else if (text startsWith "[warning]") {
        warnings += 1
      }
      else if (text contains "[success]")
        success = true

      if (taskCompleted(text)) {
        val res = new ProjectTaskResult(false, errors, warnings)
        promise.complete(Success(res))
      }
    }

    private def taskCompleted(line: String) =
      // TODO smarter conditions? see idea-sbt-plugin: SbtRunner.execute
      line.startsWith("> ")
  }
}
