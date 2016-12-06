package org.jetbrains.plugins.scala.lang.transformation
package references

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createReferenceExpressionFromText

/**
  * @author Pavel Fatin
  */
class PartiallyQualifySimpleReference extends AbstractTransformer {
  def transformation(implicit project: Project): PartialFunction[PsiElement, Unit] = {
    case e: ScReferenceExpression
      if !e.getParent.isInstanceOf[ScReferenceExpression] && !e.getText.contains(".") =>

      e.bind().foreach { result =>
        val paths = targetFor(result).split("\\.").toVector

        if (paths.length > 1) {
          val reference = createReferenceExpressionFromText(paths.takeRight(2).mkString("."))(e.getManager)
          reference.setContext(e.getParent, e.getParent.getFirstChild)
          if (reference.bind().exists(_.element == result.element)) {
            e.replace(reference)
          }
        }
      }
  }
}
