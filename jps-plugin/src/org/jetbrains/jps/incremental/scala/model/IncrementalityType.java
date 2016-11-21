package org.jetbrains.jps.incremental.scala.model;

/**
 * @author Pavel Fatin
 */
public enum IncrementalityType {
  IDEA,
  /** Actually the Zinc compiler, originally part of sbt. */
  SBT,
  /** An sbt shell or server process. Compile is managed completely by sbt. */
  SBT_SERVER
}
