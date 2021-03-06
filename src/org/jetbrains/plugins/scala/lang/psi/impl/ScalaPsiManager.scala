package org.jetbrains.plugins.scala
package lang
package psi
package impl

import java.util
import java.util.Collections
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong

import com.intellij.ProjectTopics
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.project.{DumbService, Project, ProjectUtil}
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener}
import com.intellij.openapi.util.{Key, LowMemoryWatcher, ModificationTracker}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi._
import com.intellij.psi.impl.{JavaPsiFacadeImpl, PsiTreeChangeEventImpl}
import com.intellij.psi.search.{DelegatingGlobalSearchScope, GlobalSearchScope, PsiShortNamesCache}
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ArrayUtil
import com.intellij.util.containers.{ContainerUtil, WeakValueHashMap}
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.scala.caches.{CachesUtil, ScalaShortNamesCacheManager}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.finder.ScalaSourceFilterScope
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTemplateDefinition, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.{ScSyntheticPackage, SyntheticPackageCreator}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.TypeDefinitionMembers.ParameterlessNodes.{Map => PMap}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.TypeDefinitionMembers.SignatureNodes.{Map => SMap}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.TypeDefinitionMembers.TypeNodes.{Map => TMap}
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.TypeDefinitionMembers._
import org.jetbrains.plugins.scala.lang.psi.implicits.ImplicitCollectorCache
import org.jetbrains.plugins.scala.lang.psi.light.PsiClassWrapper
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.ScProjectionType
import org.jetbrains.plugins.scala.lang.psi.types.api.{Any, Null, ParameterizedType, TypeParameterType, UndefinedType}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.lang.resolve.SyntheticClassProducer
import org.jetbrains.plugins.scala.macroAnnotations.{CachedWithoutModificationCount, ValueWrapper}
import org.jetbrains.plugins.scala.project.ProjectExt
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

import scala.collection.{Seq, mutable}

class ScalaPsiManager(val project: Project) {
  private val inJavaPsiFacade: ThreadLocal[Boolean] = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }

  def isInJavaPsiFacade: Boolean = inJavaPsiFacade.get

  private val clearCacheOnChange = new mutable.ArrayBuffer[util.Map[_ <: Any, _ <: Any]]()
  private val clearCacheOnLowMemory = new mutable.ArrayBuffer[util.Map[_ <: Any, _ <: Any]]()
  private val clearCacheOnOutOfBlockChange = new mutable.ArrayBuffer[util.Map[_ <: Any, _ <: Any]]()

  val collectImplicitObjectsCache: ConcurrentMap[(ScType, GlobalSearchScope), Seq[ScType]] =
    ContainerUtil.createConcurrentWeakMap[(ScType, GlobalSearchScope), Seq[ScType]]()

  val implicitCollectorCache: ImplicitCollectorCache = new ImplicitCollectorCache(project)

  def getParameterlessSignatures(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): PMap = {
    if (ScalaProjectSettings.getInstance(project).isDontCacheCompoundTypes) ParameterlessNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
    else getParameterlessSignaturesCached(tp, compoundTypeThisType)
  }

  @CachedWithoutModificationCount(synchronized = false, valueWrapper = ValueWrapper.SofterReference, clearCacheOnChange, clearCacheOnLowMemory)
  private def getParameterlessSignaturesCached(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): PMap = {
    ParameterlessNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
  }

  def getTypes(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): TMap = {
    if (ScalaProjectSettings.getInstance(project).isDontCacheCompoundTypes) TypeNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
    else getTypesCached(tp, compoundTypeThisType)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnChange, clearCacheOnLowMemory)
  private def getTypesCached(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): TMap = {
    TypeNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
  }

  def getSignatures(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): SMap = {
    if (ScalaProjectSettings.getInstance(project).isDontCacheCompoundTypes) return SignatureNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
    getSignaturesCached(tp, compoundTypeThisType)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnChange, clearCacheOnLowMemory)
  private def getSignaturesCached(tp: ScCompoundType, compoundTypeThisType: Option[ScType]): SMap = {
    SignatureNodes.build(tp, compoundTypeThisType)(ScalaTypeSystem)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  def cachedDeepIsInheritor(clazz: PsiClass, base: PsiClass): Boolean = clazz.isInheritor(base, true)

  def getPackageImplicitObjects(fqn: String, scope: GlobalSearchScope): Seq[ScObject] = {
    if (DumbService.getInstance(project).isDumb) Seq.empty
    else getPackageImplicitObjectsCached(fqn, scope)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  private def getPackageImplicitObjectsCached(fqn: String, scope: GlobalSearchScope): Seq[ScObject] = {
    ScalaShortNamesCacheManager.getInstance(project).getImplicitObjectsByPackage(fqn, scope)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  def getCachedPackage(inFqn: String): Option[PsiPackage] = {
    //to find java packages with scala keyword name as PsiPackage not ScSyntheticPackage
    val fqn = ScalaNamesUtil.cleanFqn(inFqn)
    Option(JavaPsiFacade.getInstance(project).findPackage(fqn))
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  def getCachedClass(scope: GlobalSearchScope, fqn: String): Option[PsiClass] = {
    def getCachedFacadeClass(scope: GlobalSearchScope, fqn: String): Option[PsiClass] = {
      inJavaPsiFacade.set(true)
      try {
        val clazz = JavaPsiFacade.getInstance(project).findClass(fqn, scope)
        if (clazz == null || clazz.isInstanceOf[ScTemplateDefinition] || clazz.isInstanceOf[PsiClassWrapper]) None
        else Option(clazz)
      } finally {
        inJavaPsiFacade.set(false)
      }
    }

    val res = ScalaShortNamesCacheManager.getInstance(project).getClassByFQName(fqn, scope)
    Option(res).orElse(getCachedFacadeClass(scope, fqn))
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnChange)
  def getProjectionTypeCached(projected: ScType, element: PsiNamedElement,
                              superReference: Boolean /* todo: find a way to remove it*/): ScType = {
    ScProjectionType.create(projected, element, superReference)
  }

  def getStableAliasesByName(name: String, scope: GlobalSearchScope): Seq[ScTypeAlias] = {
    val types: util.Collection[ScTypeAlias] =
      StubIndex.getElements(ScalaIndexKeys.TYPE_ALIAS_NAME_KEY, ScalaNamesUtil.cleanFqn(name), project,
        new ScalaSourceFilterScope(scope, project), classOf[ScTypeAlias])
    import scala.collection.JavaConversions._
    types.toSeq
  }

  def getClassesByName(name: String, scope: GlobalSearchScope): Seq[PsiClass] = {
    val scalaClasses = ScalaShortNamesCacheManager.getInstance(project).getClassesByName(name, scope)
    val buffer: mutable.Buffer[PsiClass] = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope).filterNot(p =>
      p.isInstanceOf[ScTemplateDefinition] || p.isInstanceOf[PsiClassWrapper]
    ).toBuffer
    val classesIterator = scalaClasses.iterator
    while (classesIterator.hasNext) {
      val clazz = classesIterator.next()
      buffer += clazz
    }
    buffer
  }

  def getCachedClass(fqn: String, scope: GlobalSearchScope): Option[PsiClass] =
    getCachedClasses(scope, fqn).find {
      !_.isInstanceOf[ScObject]
    }

  def getCachedObject(fqn: String, scope: GlobalSearchScope): Option[ScObject] =
    getCachedClasses(scope, fqn).collect {
      case o: ScObject => o
    }.headOption

  def getClasses(pack: PsiPackage, scope: GlobalSearchScope): Array[PsiClass] = {
    if (pack.getQualifiedName == "scala") getClassesCached(pack, scope)
    else getClassesImpl(pack, scope)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.None, clearCacheOnLowMemory, clearCacheOnOutOfBlockChange)
  private def getClassesCached(pack: PsiPackage, scope: GlobalSearchScope): Array[PsiClass] = getClassesImpl(pack, scope)

  private[this] def getClassesImpl(pack: PsiPackage, scope: GlobalSearchScope): Array[PsiClass] = {
    val classes = {
      inJavaPsiFacade.set(true)
      try {
        JavaPsiFacade.getInstance(project).asInstanceOf[JavaPsiFacadeImpl].getClasses(pack, scope).filterNot(p =>
          p.isInstanceOf[ScTemplateDefinition] || p.isInstanceOf[PsiClassWrapper]
        )
      } finally {
        inJavaPsiFacade.set(false)
      }
    }
    val scalaClasses = ScalaShortNamesCacheManager.getInstance(project).getClasses(pack, scope)
    classes ++ scalaClasses
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  def getCachedClasses(scope: GlobalSearchScope, fqn: String): Array[PsiClass] = {
    def getCachedFacadeClasses(scope: GlobalSearchScope, fqn: String): Array[PsiClass] = {
      inJavaPsiFacade.set(true)
      try {
        val classes = JavaPsiFacade.getInstance(project).findClasses(fqn, new DelegatingGlobalSearchScope(scope) {
          override def compare(file1: VirtualFile, file2: VirtualFile): Int = 0
        }).filterNot { p =>
          p.isInstanceOf[ScTemplateDefinition] || p.isInstanceOf[PsiClassWrapper]
        }

        ArrayUtil.mergeArrays(classes, SyntheticClassProducer.getAllClasses(fqn, scope))
      } finally {
        inJavaPsiFacade.set(false)
      }
    }
    if (DumbService.getInstance(project).isDumb) return Array.empty

    val classes = getCachedFacadeClasses(scope, ScalaNamesUtil.cleanFqn(fqn))
    val fromScala = ScalaShortNamesCacheManager.getInstance(project).getClassesByFQName(fqn, scope)
    ArrayUtil.mergeArrays(classes, ArrayUtil.mergeArrays(fromScala.toArray, SyntheticClassProducer.getAllClasses(fqn, scope)))
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnOutOfBlockChange)
  def cachedFunction1Type(scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): Option[ScParameterizedType] = {
    implicit val typeSystem = project.typeSystem

    getCachedClass("scala.Function1", scope).collect {
      case t: ScTrait => t
    }.map { t =>
      val parameters = t.typeParameters.map {
        TypeParameterType(_)
      }.map {
        UndefinedType(_, 1)
      }

      ScParameterizedType(ScalaType.designator(t), parameters)
    }.collect {
      case p: ScParameterizedType => p
    }
  }

  import java.util.{Set => JSet}

  def getJavaPackageClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): JSet[String] = {
    if (DumbService.getInstance(project).isDumb) return Collections.emptySet()
    val qualifier: String = psiPackage.getQualifiedName
    getJavaPackageClassNamesCached(qualifier, scope)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.None, clearCacheOnLowMemory, clearCacheOnOutOfBlockChange)
  private def getJavaPackageClassNamesCached(packageFQN: String, scope: GlobalSearchScope): JSet[String] = {
    val classes: util.Collection[PsiClass] =
      StubIndex.getElements(ScalaIndexKeys.JAVA_CLASS_NAME_IN_PACKAGE_KEY, ScalaNamesUtil.cleanFqn(packageFQN), project,
        new ScalaSourceFilterScope(scope, project), classOf[PsiClass])
    val strings: util.HashSet[String] = new util.HashSet[String]
    val classesIterator = classes.iterator()
    while (classesIterator.hasNext) {
      val clazz: PsiClass = classesIterator.next()
      strings add clazz.getName
      clazz match {
        case t: ScTemplateDefinition =>
          for (name <- t.additionalJavaNames) strings add name
        case _ =>
      }
    }
    strings
  }

  def getScalaClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): mutable.HashSet[String] = {
    if (DumbService.getInstance(project).isDumb) return mutable.HashSet.empty
    val qualifier: String = psiPackage.getQualifiedName
    getScalaClassNamesCached(qualifier, scope)
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.None, clearCacheOnLowMemory, clearCacheOnOutOfBlockChange)
  def getScalaClassNamesCached(packageFQN: String, scope: GlobalSearchScope): mutable.HashSet[String] = {
    val cleanName = ScalaNamesUtil.cleanFqn(packageFQN)
    val classes: util.Collection[PsiClass] =
      StubIndex.getElements(ScalaIndexKeys.CLASS_NAME_IN_PACKAGE_KEY, cleanName, project,
        new ScalaSourceFilterScope(scope, project), classOf[PsiClass])
    var strings: mutable.HashSet[String] = new mutable.HashSet[String]
    val classesIterator = classes.iterator()
    while (classesIterator.hasNext) {
      val clazz: PsiClass = classesIterator.next()
      strings += clazz.name
    }
    strings
  }

  private def clearCaches(): Unit = {
    val typeSystem = project.typeSystem
    val equivalence = typeSystem.equivalence
    val conformance = typeSystem.conformance

    conformance.clearCache()
    equivalence.clearCache()
    ParameterizedType.substitutorCache.clear()
    ScParameterizedType.cache.clear()
    collectImplicitObjectsCache.clear()
    implicitCollectorCache.clear()
  }

  private def clearOnChange(): Unit = {
    clearCacheOnChange.foreach(_.clear())
    clearCaches()
  }

  private def clearOnLowMemory(): Unit = {
    clearCacheOnLowMemory.foreach(_.clear())
    clearCaches()
  }

  private def clearOnOutOfCodeBlockChange(): Unit = {
    clearCacheOnOutOfBlockChange.foreach(_.clear())
    syntheticPackages.clear()
  }

  private[impl] def projectOpened(): Unit = {
    import ScalaPsiManager._

    subscribeToRootsChange(project)
    registerLowMemoryWatcher(project)
    PsiManager.getInstance(project).addPsiTreeChangeListener(CacheInvalidator, project)
  }

  private val syntheticPackagesCreator = new SyntheticPackageCreator(project)
  private val syntheticPackages = new WeakValueHashMap[String, Any]

  def syntheticPackage(fqn: String): ScSyntheticPackage = {
    var p = syntheticPackages.get(fqn)
    if (p == null) {
      p = syntheticPackagesCreator.getPackage(fqn)
      if (p == null) p = Null
      synchronized {
        val pp = syntheticPackages.get(fqn)
        if (pp == null) {
          syntheticPackages.put(fqn, p)
        } else {
          p = pp
        }
      }
    }

    p match {
      case synth: ScSyntheticPackage => synth
      case _ => null
    }
  }

  @CachedWithoutModificationCount(synchronized = false, ValueWrapper.SofterReference, clearCacheOnChange)
  def javaPsiTypeParameterUpperType(typeParameter: PsiTypeParameter): ScType = {
    val types = typeParameter.getExtendsListTypes ++ typeParameter.getImplementsListTypes
    if (types.isEmpty) Any
    else andType(types)
  }

  private def andType(psiTypes: Seq[PsiType]): ScType = {
    implicit val typeSystem = project.typeSystem
    typeSystem.andType(psiTypes.map(_.toScType()))
  }

  def getStableTypeAliasesNames: Seq[String] = {
    val keys = StubIndex.getInstance.getAllKeys(ScalaIndexKeys.STABLE_ALIAS_NAME_KEY, project)
    import scala.collection.JavaConversions._
    keys.toSeq
  }

  object CacheInvalidator extends PsiTreeChangeAdapter {
    @volatile
    private var outOfCodeBlockModCount: Long = 0L

    private def fromIdeaInternalFile(event: PsiTreeChangeEvent) = {
      val virtFile = event.getFile match {
        case null => event.getOldValue.asOptionOf[VirtualFile]
        case file =>
          val fileType = file.getFileType
          if (fileType == ScalaFileType.INSTANCE || fileType == JavaFileType.INSTANCE) None
          else Option(file.getVirtualFile)
      }
      virtFile.exists(ProjectUtil.isProjectOrWorkspaceFile)
    }

    private def onPsiChange(event: PsiTreeChangeEvent): Unit = {
      event match {
        case impl: PsiTreeChangeEventImpl if impl.isGenericChange => return
        case _ if fromIdeaInternalFile(event) => return
        case _ =>
      }

      CachesUtil.updateModificationCount(event.getParent)
      clearOnChange()
      val count = PsiModificationTracker.SERVICE.getInstance(project).getOutOfCodeBlockModificationCount
      if (outOfCodeBlockModCount != count) {
        outOfCodeBlockModCount = count
        clearOnOutOfCodeBlockChange()
      }
    }

    override def childRemoved(event: PsiTreeChangeEvent): Unit = onPsiChange(event)

    override def childReplaced(event: PsiTreeChangeEvent): Unit = onPsiChange(event)

    override def childAdded(event: PsiTreeChangeEvent): Unit = onPsiChange(event)

    override def childrenChanged(event: PsiTreeChangeEvent): Unit = onPsiChange(event)

    override def childMoved(event: PsiTreeChangeEvent): Unit = onPsiChange(event)

    override def propertyChanged(event: PsiTreeChangeEvent): Unit = onPsiChange(event)
  }

  val modificationTracker: ScalaPsiModificationTracker = new ScalaPsiModificationTracker(project)

  def getModificationCount: Long = modificationTracker.getModificationCount

  def incModificationCount(): Long = modificationTracker.incModificationCount()

  @TestOnly
  def clearAllCaches(): Unit = {
    clearOnChange()
    clearOnOutOfCodeBlockChange()
  }

  @TestOnly
  def clearCachesOnChange(): Unit = {
    clearOnChange()
  }
}

object ScalaPsiManager {
  val TYPE_VARIABLE_KEY: Key[TypeParameterType] = Key.create("type.variable.key")

  def instance(project: Project): ScalaPsiManager = project.getComponent(classOf[ScalaPsiManagerComponent]).instance

  private def subscribeToRootsChange(project: Project) = {
    project.getMessageBus.connect(project).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener {
      def beforeRootsChange(event: ModuleRootEvent) {}

      def rootsChanged(event: ModuleRootEvent) {
        val manager = ScalaPsiManager.instance(project)
        manager.clearOnChange()
        manager.clearOnOutOfCodeBlockChange()
        project.putUserData(CachesUtil.PROJECT_HAS_DOTTY_KEY, null)
      }
    })
  }

  private def registerLowMemoryWatcher(project: Project) = {
    LowMemoryWatcher.register(new Runnable {
      def run(): Unit = {
        val manager = ScalaPsiManager.instance(project)
        manager.clearOnLowMemory()
      }
    }, project)
  }
}

class ScalaPsiManagerComponent(project: Project) extends AbstractProjectComponent(project) {
  private var manager = new ScalaPsiManager(project)

  def instance: ScalaPsiManager =
    if (manager != null) manager
    else throw new IllegalStateException("ScalaPsiManager cannot be used after disposing.")

  override def projectOpened(): Unit = {
    manager.projectOpened()
  }

  override def projectClosed(): Unit = {
    //todo make separate substitutorCache for each project
    ParameterizedType.substitutorCache.clear()
  }

  override def disposeComponent(): Unit = {
    manager = null
  }
}

class ScalaPsiModificationTracker(project: Project) extends ModificationTracker {

  private val myRawModificationCount = new AtomicLong(0)

  private val mainModificationTracker = PsiManager.getInstance(project).getModificationTracker

  def getModificationCount: Long = {
    myRawModificationCount.get() + mainModificationTracker.getOutOfCodeBlockModificationCount
  }

  def incModificationCount(): Long = myRawModificationCount.incrementAndGet()
}