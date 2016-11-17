package org.jetbrains.plugins.scala.lang.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion._
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.openapi.actionSystem.{ActionManager, IdeActions}
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi._
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.ProcessingContext
import gnu.trove.THashSet
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.completion.lookups.{LookupElementManager, ScalaLookupItem}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValue, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.implicits.ScImplicitlyConvertible
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.jetbrains.plugins.scala.lang.psi.types.ScType
import org.jetbrains.plugins.scala.lang.psi.types.api.TypeSystem
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.ScThisType
import org.jetbrains.plugins.scala.lang.psi.types.result.TypingContext
import org.jetbrains.plugins.scala.lang.resolve.processor.CompletionProcessor
import org.jetbrains.plugins.scala.lang.resolve.{ResolveUtils, ScalaResolveResult, StdKinds}

import scala.collection.mutable

/**
  * @author Alexander Podkhalyuzin
  */
class ScalaGlobalMembersCompletionContributor extends ScalaCompletionContributor {
  extend(CompletionType.BASIC, psiElement, new CompletionProvider[CompletionParameters]() {
    def addCompletions(parameters: CompletionParameters, context: ProcessingContext,
                       result: CompletionResultSet) {
      if (parameters.getInvocationCount < 2) return
      val position: PsiElement = positionFromParameters(parameters)
      if (!ScalaPsiUtil.fileContext(position).isInstanceOf[ScalaFile]) return

      position.getContext match {
        case reference: ScReferenceExpression =>
          val maybeQualifier = reference.qualifier.orElse {
            reference.getContext match {
              case expr: ScSugarCallExpr if expr.operation == reference =>
                Some(expr.getBaseExpr)
              case _ => None
            }
          }

          val file = parameters.getOriginalFile
          maybeQualifier match {
            case Some(qualifier) =>
              qualifier.getTypeWithoutImplicits().foreach {
                completeImplicits(reference, result, file, _)
              }
            case _ =>
              if (result.getPrefixMatcher.getPrefix != "") {
                complete(reference, result, file, parameters.getInvocationCount)
              }
          }
        case _ =>
      }
    }
  })

  private def isStatic(member: PsiNamedElement): Boolean = {
    ScalaPsiUtil.nameContext(member) match {
      case memb: PsiMember => isStatic(member, memb.containingClass)
    }
  }

  private def isStatic(member: PsiNamedElement, containingClass: PsiClass): Boolean = {
    ScalaPsiUtil.nameContext(member) match {
      case memb: PsiMember =>
        if (containingClass == null) return false
        val qualifiedName = containingClass.qualifiedName + "." + member.name
        for (excluded <- CodeInsightSettings.getInstance.EXCLUDED_PACKAGES) {
          if (qualifiedName == excluded || qualifiedName.startsWith(excluded + ".")) {
            return false
          }
        }
        containingClass match {
          case o: ScObject if o.isStatic =>
            // filter out type class instances, such as scala.math.Numeric.String, to avoid too many results.
            !o.hasModifierProperty("implicit")
          case _: ScTypeDefinition => false
          case _ => memb.hasModifierProperty("static")
        }
    }
  }

  private def completeImplicits(ref: ScReferenceExpression, result: CompletionResultSet, originalFile: PsiFile,
                                originalType: ScType)
                               (implicit typeSystem: TypeSystem = originalFile.typeSystem) {
    FeatureUsageTracker.getInstance.triggerFeatureUsed(JavaCompletionFeatures.GLOBAL_MEMBER_NAME)
    val scope: GlobalSearchScope = ref.getResolveScope
    val file = ref.getContainingFile

    val elemsSet = new mutable.HashSet[PsiNamedElement]

    def addElemToSet(elem: PsiNamedElement) {
      elemsSet += elem
    }

    def elemsSetContains(elem: PsiNamedElement): Boolean = {
      if (elem.getContainingFile == originalFile) {
        //complex logic to detect static methods in same file, which we shouldn't import
        val name = elem.name
        val containingClass = ScalaPsiUtil.nameContext(elem) match {
          case member: PsiMember => member.containingClass
          case _ => null
        }
        if (containingClass == null) return false
        val qualName = containingClass.qualifiedName
        if (qualName == null) return false
        for {
          element <- elemsSet
          if element.name == name
          if element.getContainingFile == file
          cClass = ScalaPsiUtil.nameContext(element) match {
            case member: PsiMember => member.containingClass
            case _ => null
          }
          if cClass != null
          if cClass.qualifiedName != null
          if cClass.qualifiedName == qualName
        } {
          return true
        }
        false
      } else elemsSet.contains(elem)
    }

    val collection = StubIndex.getElements(ScalaIndexKeys.IMPLICITS_KEY, "implicit", file.getProject, scope, classOf[ScMember])

    import scala.collection.JavaConversions._

    val convertible = new ScImplicitlyConvertible(ref)
    val proc = new convertible.CollectImplicitsProcessor(true)
    val processedObjects: mutable.HashSet[String] = mutable.HashSet.empty

    def processObject(o: ScObject): Unit = {
      val qualifiedName = o.qualifiedName
      if (!processedObjects(qualifiedName)) {
        processedObjects.add(qualifiedName)
        if (o.isStatic) o.getType(TypingContext.empty).foreach(proc.processType(_, ref))
      }
    }

    val names =
      Set("scala.collection.convert.DecorateAsJava",
        "scala.collection.convert.DecorateAsScala",
        "scala.collection.convert.WrapAsScala",
        "scala.collection.convert.WrapAsJava"
      )

    def checkClass(clazz: PsiClass): Unit = {
      if (clazz == null) return
      if (!names(clazz.qualifiedName)) return
      ClassInheritorsSearch.search(clazz, false).find(_.isInstanceOf[ScObject]).collect {
        case o: ScObject => processObject(o)
      }
    }

    for (element <- collection) {
      element match {
        case v: ScValue =>
          v.containingClass match {
            case o: ScObject =>
              for (d <- v.declaredElements if isStatic(d)) {
                proc.execute(d, ResolveState.initial())
              }
            case clazz => checkClass(clazz)
          }
        case f: ScFunction =>
          f.containingClass match {
            case o: ScObject =>
              if (isStatic(f)) proc.execute(element, ResolveState.initial())
            case clazz => checkClass(clazz)
          }
        case c: ScClass =>
          c.getSyntheticImplicitMethod match {
            case Some(f: ScFunction) =>
              c.containingClass match {
                case o: ScObject =>
                  if (isStatic(c)) proc.execute(f, ResolveState.initial())
                case clazz => checkClass(clazz)
              }
            case _ =>
          }
        case _ =>
      }
    }
    val candidates = proc.candidates.map(convertible.forMap(_, originalType))

    ref.getVariants(implicits = false, filterNotNamedVariants = false).foreach {
      case ScalaLookupItem(elem: PsiNamedElement) => addElemToSet(elem)
      case elem: PsiNamedElement => addElemToSet(elem)
    }

    val iterator = candidates.iterator
    while (iterator.hasNext) {
      val next = iterator.next()
      if (next.condition) {
        val retTp = next.rt
        val c = new CompletionProcessor(StdKinds.methodRef, ref)
        c.processType(retTp, ref)
        for (elem <- c.candidates) {
          val shouldImport = !elemsSetContains(elem.getElement)
          //todo: overloads?
          val lookup = LookupElementManager.getLookupElement(elem, isClassName = true, shouldImport = shouldImport).head
          lookup.usedImportStaticQuickfix = true
          val element = next.resolveResult.getElement
          lookup.elementToImport = element
          ScalaPsiUtil.nameContext(element) match {
            case memb: ScMember =>
              val clazz = memb.containingClass
              clazz match {
                case _: ScClass | _: ScTrait =>
                  next.resolveResult.substitutor.subst(ScThisType(clazz)).extractClass(element.getProject) match {
                    case Some(obj: ScObject) => lookup.objectOfElementToImport = Some(obj)
                    case _ =>
                  }
                case _ =>
              }
            case _ =>
          }
          result.addElement(lookup)
        }
      }
    }
  }

  private def complete(ref: ScReferenceExpression, result: CompletionResultSet, originalFile: PsiFile, invocationCount: Int) {
    FeatureUsageTracker.getInstance.triggerFeatureUsed(JavaCompletionFeatures.GLOBAL_MEMBER_NAME)
    val matcher: PrefixMatcher = result.getPrefixMatcher
    val scope: GlobalSearchScope = ref.getResolveScope
    val file = ref.getContainingFile

    var hintShown: Boolean = false

    def showHint(shouldImport: Boolean) {
      if (!hintShown && !shouldImport && CompletionService.getCompletionService.getAdvertisementText == null) {
        val actionId = IdeActions.ACTION_SHOW_INTENTION_ACTIONS
        val shortcut: String =
          KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance.getAction(actionId))
        if (shortcut != null) {
          result.addLookupAdvertisement(s"To import a method statically, press $shortcut")
        }
        hintShown = true
      }
    }

    val elemsSet = new mutable.HashSet[PsiNamedElement]

    def addElemToSet(elem: PsiNamedElement) {
      elemsSet += elem
    }

    def elemsSetContains(elem: PsiNamedElement): Boolean = {
      if (elem.getContainingFile == originalFile) {
        //complex logic to detect static methods in same file, which we shouldn't import
        val name = elem.name
        val containingClass = ScalaPsiUtil.nameContext(elem) match {
          case member: PsiMember => member.containingClass
          case _ => null
        }
        if (containingClass == null) return false
        val qualName = containingClass.qualifiedName
        if (qualName == null) return false
        for {
          element <- elemsSet
          if element.name == name
          if element.getContainingFile == file
          cClass = ScalaPsiUtil.nameContext(element) match {
            case member: PsiMember => member.containingClass
            case _ => null
          }
          if cClass != null
          if cClass.qualifiedName != null
          if cClass.qualifiedName == qualName
        } {
          return true
        }
        false
      } else elemsSet.contains(elem)
    }

    ref.getVariants(implicits = false, filterNotNamedVariants = false).foreach {
      case ScalaLookupItem(elem) => addElemToSet(elem)
      case elem: PsiNamedElement => addElemToSet(elem)
    }

    val namesCache = ScalaShortNamesCacheManager.getInstance(ref.getProject)

    val methodNamesIterator = namesCache.getAllMethodNames.iterator ++ namesCache.getAllJavaMethodNames.iterator

    def isAccessible(member: PsiMember, containingClass: PsiClass): Boolean = {
      invocationCount >= 3 || (ResolveUtils.isAccessible(member, ref, forCompletion = true) && ResolveUtils.isAccessible(containingClass, ref, forCompletion = true))
    }

    while (methodNamesIterator.hasNext) {
      val methodName = methodNamesIterator.next()
      if (matcher.prefixMatches(methodName)) {
        val classes = new THashSet[PsiClass]
        val methodsIterator = namesCache.getMethodsByName(methodName, scope).iterator
        while (methodsIterator.hasNext) {
          val method = methodsIterator.next()
          val cClass = method.containingClass
          if (cClass != null) {
            val inheritors: Array[PsiClass] = {
              if (method.isInstanceOf[ScFunction])
                ClassInheritorsSearch.search(cClass, scope, true).toArray(PsiClass.EMPTY_ARRAY)
              else Array.empty
            }
            val currentAndInheritors = Iterator(cClass) ++ inheritors.iterator
            for {
              containingClass <- currentAndInheritors
              if isStatic(method, containingClass)
            } {
              assert(containingClass != null)
              if (classes.add(containingClass) && isAccessible(method, containingClass)) {
                val shouldImport = !elemsSetContains(method)
                showHint(shouldImport)

                val overloads = containingClass match {
                  case o: ScObject => o.functionsByName(methodName)
                  case _ => containingClass.getAllMethods.toSeq.filter(m => m.name == methodName)
                }
                if (overloads.size == 1) {
                  result.addElement(createLookupElement(method, containingClass, shouldImport))
                }
                else if (overloads.size > 1) {
                  val lookup = createLookupElement(if (overloads.head.getParameterList.getParametersCount == 0)
                    overloads(1)
                  else overloads.head, containingClass, shouldImport, overloaded = true)
                  result.addElement(lookup)
                }
              }
            }
          }
        }
      }
    }

    val fieldNamesIterator = namesCache.getAllFieldNames.iterator

    while (fieldNamesIterator.hasNext) {
      val fieldName = fieldNamesIterator.next()
      if (matcher.prefixMatches(fieldName)) {
        val fieldsIterator = namesCache.getFieldsByName(fieldName, scope).iterator
        while (fieldsIterator.hasNext) {
          val field = fieldsIterator.next()
          if (isStatic(field)) {
            val containingClass: PsiClass = field.containingClass
            assert(containingClass != null)
            if (isAccessible(field, containingClass)) {
              val shouldImport = !elemsSetContains(field)
              showHint(shouldImport)

              result.addElement(createLookupElement(field, containingClass, shouldImport))
            }
          }
        }
      }
    }

    val scalaFieldsIterator = ScalaShortNamesCacheManager.getInstance(ref.getProject).getAllScalaFieldNames.iterator

    while (scalaFieldsIterator.hasNext) {
      val fieldName = scalaFieldsIterator.next()
      if (matcher.prefixMatches(fieldName)) {
        val fieldsIterator = ScalaShortNamesCacheManager.getInstance(ref.getProject).
          getScalaFieldsByName(fieldName, scope).iterator
        while (fieldsIterator.hasNext) {
          val field = fieldsIterator.next()
          val namedElement = field match {
            case v: ScValue => v.declaredElements.find(_.name == fieldName).orNull
            case v: ScVariable => v.declaredElements.find(_.name == fieldName).orNull
          }
          if (field.containingClass != null) {
            val inheritors = ClassInheritorsSearch.search(field.containingClass, scope, true).toArray(PsiClass.EMPTY_ARRAY)
            val currentAndInheritors = Iterator(field.containingClass) ++ inheritors.iterator
            for {
              containingClass <- currentAndInheritors
              if namedElement != null && isStatic(namedElement, containingClass)
            } {
              assert(containingClass != null)
              if (isAccessible(field, containingClass)) {
                val shouldImport = !elemsSetContains(namedElement)
                showHint(shouldImport)

                result.addElement(createLookupElement(namedElement, containingClass, shouldImport))
              }
            }
          }
        }
      }
    }
  }

  private def createLookupElement(member: PsiNamedElement, clazz: PsiClass, shouldImport: Boolean,
                                  overloaded: Boolean = false): LookupElement = {
    LookupElementManager.getLookupElement(new ScalaResolveResult(member), isClassName = true,
      isOverloadedForClassName = overloaded, shouldImport = shouldImport, containingClass = Some(clazz)).head
  }
}