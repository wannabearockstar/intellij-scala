package org.jetbrains.plugins.scala.lang.macros.expansion

import java.awt.event.MouseEvent
import java.io.File
import java.util
import javax.swing.Icon

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon._
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.compiler.{CompileContext, CompileStatusNotification, CompilerManager}
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.{PsiElement, PsiManager}
import com.intellij.util.Function
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScAnnotation
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScAnnotationsHolder
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory

import scala.collection.JavaConversions._

class MacroExpansionLineMarkerProvider extends RelatedItemLineMarkerProvider {

  private type Marker = RelatedItemLineMarkerInfo[_ <: PsiElement]
  private type Markers = util.Collection[_ >: Marker]

  override def collectNavigationMarkers(element: PsiElement, result: Markers): Unit = {
    processElement(element).foreach(result.add)
  }

  private def processElement(element: PsiElement): Option[Marker] = {
    element match {
      case holder: ScAnnotationsHolder =>
        val metaAnnot: Option[ScAnnotation] = holder.annotations.find(_.isMetaAnnotation)
        metaAnnot.map { annot =>
          MacroExpandAction.getCompiledMetaAnnotClass(annot) match {
            case Some(clazz) if isUpToDate(annot, clazz) => createExpandLineMarker(holder, annot)
            case _                                       => createNotCompiledLineMarker(holder, annot)
          }
        }
      case _ => None
    }
  }

  private def isUpToDate(annot: ScAnnotation, clazz: Class[_]): Boolean = {
    val classFile = new File(clazz.getProtectionDomain.getCodeSource.getLocation.getPath)
    val sourceFile = new File(annot.getContainingFile.getVirtualFile.getPath)
    classFile.lastModified() >= sourceFile.lastModified()
  }

  private def createExpandLineMarker(holder: ScAnnotationsHolder, annot: ScAnnotation): Marker = {
    newMarker(holder, AllIcons.General.ExpandAllHover, "Expand scala.meta macro") { _=>
      MacroExpandAction.expandMetaAnnotation(annot)
    }
  }

  private def createNotCompiledLineMarker(holder: ScAnnotationsHolder, annot: ScAnnotation): Marker = {
    import org.jetbrains.plugins.scala.project._
    newMarker(holder, AllIcons.General.Help, "Metaprogram is out of date. Click here to compile.") { elt =>
      CompilerManager.getInstance(elt.getProject).compile(annot.constructor.reference.get.resolve().module.get,
        new CompileStatusNotification {
          override def finished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) = {
            DaemonCodeAnalyzer.getInstance(elt.getProject).restart(elt.getContainingFile)
          }
        }
      )
    }
  }

  private def collectReflectExpansions(elements: util.List[PsiElement], result: Markers): Unit = {
    val expansions = elements.map(p => (p, p.getCopyableUserData(MacroExpandAction.EXPANDED_KEY))).filter(_._2 != null)
    val res = expansions.map { case (current, saved) =>
      newMarker(current, AllIcons.General.ExpandAllHover, "Undo Macro Expansion") { _ =>
        inWriteAction {
          val newPsi = ScalaPsiElementFactory.createBlockExpressionWithoutBracesFromText(saved, PsiManager.getInstance(current.getProject))
          current.replace(newPsi)
          saved
        }
      }
    }
    result.addAll(res)
  }

  private def newMarker[T](elem: PsiElement, icon: Icon, caption: String)(fun: PsiElement => T): Marker = {
    new RelatedItemLineMarkerInfo[PsiElement](elem, elem.getTextRange, icon, Pass.LINE_MARKERS,
      new Function[PsiElement, String] {
        def fun(param: PsiElement): String = caption
      },
      new GutterIconNavigationHandler[PsiElement] {
        def navigate(mouseEvent: MouseEvent, elt: PsiElement) = fun(elt)
      },
      GutterIconRenderer.Alignment.RIGHT, util.Arrays.asList[GotoRelatedItem]())
  }
}
