package org.jetbrains.plugins.scala.failed.typeInference

import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.lang.typeInference.TypeInferenceTestBase
import org.junit.experimental.categories.Category

/**
  * Created by Anton Yalyshev on 15.06.16.
  */
@Category(Array(classOf[PerfCycleTests]))
class CollectionsTest extends TypeInferenceTestBase {

  def testSCL10414(): Unit = {
    val text =
      s"""
          |val s1 : Set[Class[_]] = Set()
          |val s2 : Set[java.lang.Class[_]] = Set()
          |
          |if(!s1.union(${START}s2$END).isEmpty) println(5)
          |//GenSet[Class[_]]""".stripMargin
    doTest(text)
  }

  def testSCL11052(): Unit = {
    val text =
      s"""
         |def second[T]: Seq[T] => Option[T] = _.drop(1).headOption
         |second(${START}Seq("one", "two")$END)
         |//Seq[T]""".stripMargin
    doTest(text)
  }
}
