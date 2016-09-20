package scala.meta.intellij

import java.io.File
import java.net.URL

import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{ModuleRootManager, OrderEnumerator}
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScAnnotation
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScAnnotationsHolder
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
import org.jetbrains.plugins.scala.macroAnnotations.{CachedInsidePsiElement, ModCount}

import scala.meta.Tree
import scala.meta.trees.TreeConverter
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader

/**
  * @author Mikhail Mutcianko
  * @since 20.09.16
  */
object ExpansionUtil {

  private val LOG = Logger.getInstance(getClass)

  def getCompiledMetaAnnotClass(annot: ScAnnotation): Option[Class[_]] = {
    import org.jetbrains.plugins.scala.project._

    def toUrl(f: VirtualFile) = new File(f.getPath.replaceAll("!", "")).toURI.toURL
    def outputDirs(module: Module) = (ModuleRootManager.getInstance(module).getDependencies :+ module)
      .map(m => CompilerPaths.getModuleOutputPath(m, false)).toList

    val annotClass = annot.constructor.reference.get.bind().map(_.parentElement.get)
    val metaModule = annotClass.flatMap(_.module)
    val cp: Option[List[URL]] = metaModule.map(OrderEnumerator.orderEntries).map(_.getClassesRoots.toList.map(toUrl))
    val outDirs: Option[List[URL]] = metaModule.map(outputDirs(_).map(str => new File(str).toURI.toURL))
    val classLoader = new URLClassLoader(outDirs.get ++ cp.get, this.getClass.getClassLoader)
    try {
      Some(classLoader.loadClass(annotClass.get.asInstanceOf[ScTemplateDefinition].qualifiedName + "$inline$"))
    } catch {
      case _:  ClassNotFoundException => None
    }
  }


  def runMetaAnnotation(annot: ScAnnotation): Option[Tree] = {

    @CachedInsidePsiElement(annot, ModCount.getModificationCount)
    def runMetaAnnotationsImpl: Option[Tree] = {

      val converter = new TreeConverter {
        override def getCurrentProject: Project = annot.getProject
        override def dumbMode: Boolean = true
      }

      val annotee: ScAnnotationsHolder = ScalaPsiUtil.getParentOfType(annot, classOf[ScAnnotationsHolder])
        .copy()
        .asInstanceOf[ScAnnotationsHolder]

      annotee.annotations.find(_.getQualifiedName == annot.getQualifiedName).foreach(_.delete())
      val converted = converter.ideaToMeta(annotee)
      val clazz = getCompiledMetaAnnotClass(annot)
      clazz.flatMap { outer =>
        val ctor = outer.getDeclaredConstructors.head
        ctor.setAccessible(true)
        val inst = ctor.newInstance()
        val meth = outer.getDeclaredMethods.find(_.getName == "apply").get
        meth.setAccessible(true)
        try {
          val result = meth.invoke(inst, null, converted.asInstanceOf[AnyRef])
          Some(result.asInstanceOf[Tree])
        } catch {
          case e: Exception =>
            LOG.error(e)
            None
        }
      }
    }

    runMetaAnnotationsImpl
  }
}
