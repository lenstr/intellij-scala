package org.jetbrains.plugins.scala.failed.annotator

import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.annotator.OverridingAnnotatorTestBase
import org.junit.experimental.categories.Category

/**
  * Created by mucianm on 22.03.16.
  */
@Category(Array(classOf[PerfCycleTests]))
class OverridingAnnotatorTest extends OverridingAnnotatorTestBase {

  //#SCL-8577 overriding of inaccessible members with qualified private must show an error
  def testInaccessiblePrivateMembers(): Unit = {
    assert(
      messages(
        """
          |object FOO {
          |class A {
          |  private[A] def foo = 1
          |}
          |
          |class B extends A {
          |  override def foo: Int = 2
          |}}
        """.stripMargin
      ).nonEmpty)
  }

  def testSCL8228(): Unit = {
    assertNothing(
      messages(
        """
          |  trait TraitWithGeneric [T]{
          |    // If you click on the left on green down arrow, it does not list implementation from SelfTypeWildcard
          |    def method: String
          |  }
          |
          |  trait SelfType { self: TraitWithGeneric[Unit] =>
          |    override def method = "no problem here"
          |  }
          |
          |  trait SelfTypeWildcard { self: TraitWithGeneric[_] =>
          |    // BUG: Triggers "Overrides nothing" inspection
          |    override def method = "inspection problem here for selftype"
          |  }
          |  object ItActuallyCompilesAndWorks extends TraitWithGeneric[Unit] with SelfTypeWildcard
          |  ItActuallyCompilesAndWorks.method // returns "inspection problem here for selftype"
        """.stripMargin)
    )
  }

  def testSCL7987(): Unit = {
    assertNothing(
      messages(
        """
          |trait Foo {
          |  protected type T
          |  def foo(t: T)
          |}
          |
          |new Foo {
          |  override protected type T = String // will pass if protected modifier is removed
          |  override def foo(t: T) = ()
          |}
        """.stripMargin
      )
    )
  }

  def testScl9767(): Unit = {
    assertMatches(
      messages(
        """case class Q[B](b: B)
          |
          |trait Foo[A] {
          |  def method(value: A): Unit
          |
          |  def concat[T](that: Foo[T]): Foo[Q[A]] = new Foo[Q[A]] {
          |    override def method(value: Q[A]): Unit = ()
          |  }
          |}
        """.stripMargin)) {
      case Nil =>
    }
  }

  def testScl6809(): Unit = {
    assertMatches(
      messages(
        """import java.{util => ju}
          |
          |abstract class CollectionToArrayBug[E <: AnyRef](collection: ju.Collection[E])
          |  extends ju.Collection[E]
          |{
          |  def toArray[T](a: Array[T]): Array[T] = ???
          |  override def toArray[T](a: Array[T with AnyRef]): Array[T with AnyRef] = ???
          |}
        """.stripMargin)) {
      case Nil =>
    }
  }

  def testScl10762(): Unit = {
    assertNothing(
      messages(
        """
          |trait HasPathDependentType {
          |  type DT
          |}
          |
          |trait T {
          |  def foo(hpdt: HasPathDependentType)(arg: hpdt.DT): hpdt.DT
          |}
          |
          |class TImpl extends T {
          |  override def foo(hpdt: HasPathDependentType)(arg: hpdt.DT): hpdt.DT = arg
          |}
        """.stripMargin)
    )
  }
}
