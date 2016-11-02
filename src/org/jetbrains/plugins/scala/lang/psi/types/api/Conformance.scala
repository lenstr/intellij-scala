package org.jetbrains.plugins.scala.lang.psi.types.api

import java.util.concurrent.ConcurrentMap

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Computable, RecursionManager}
import com.intellij.psi.PsiClass
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.plugins.scala.lang.psi.types._

import scala.collection.immutable.HashSet

/**
  * @author adkozlov
  */
trait Conformance extends TypeSystemOwner {
  private val guard = RecursionManager.createGuard(s"${typeSystem.name}.conformance.guard")

  private val cache: ConcurrentMap[(ScType, ScType, Boolean), (Boolean, ScUndefinedSubstitutor)] =
    ContainerUtil.createConcurrentWeakMap[(ScType, ScType, Boolean), (Boolean, ScUndefinedSubstitutor)]()

  /**
    * Checks, whether the following assignment is correct:
    * val x: l = (y: r)
    */
  final def conformsInner(left: ScType, right: ScType,
                          visited: Set[PsiClass] = HashSet.empty,
                          substitutor: ScUndefinedSubstitutor = ScUndefinedSubstitutor(),
                          checkWeak: Boolean = false): (Boolean, ScUndefinedSubstitutor) = {
    ProgressManager.checkCanceled()

    if (left.equiv(Any) || right.equiv(Nothing)) return (true, substitutor)

    val key = (left, right, checkWeak)

    val tuple = cache.get(key)
    if (tuple != null) {
      if (substitutor.isEmpty) return tuple
      return tuple.copy(_2 = substitutor + tuple._2)
    }
    if (guard.currentStack().contains(key)) {
      return (false, ScUndefinedSubstitutor())
    }

    val res = guard.doPreventingRecursion(key, false, computable(left, right, visited, checkWeak))
    if (res == null) return (false, ScUndefinedSubstitutor())
    cache.put(key, res)
    if (substitutor.isEmpty) return res
    res.copy(_2 = substitutor + res._2)
  }

  final def clearCache(): Unit = cache.clear()

  protected def computable(left: ScType, right: ScType,
                           visited: Set[PsiClass],
                           checkWeak: Boolean): Computable[(Boolean, ScUndefinedSubstitutor)]
}
