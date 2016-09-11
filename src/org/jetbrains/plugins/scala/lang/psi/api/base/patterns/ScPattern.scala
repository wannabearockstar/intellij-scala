package org.jetbrains.plugins.scala
package lang
package psi
package api
package base
package patterns

import com.intellij.psi._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.FakeCompanionClassOrCompanionClass
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeVariableTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.expr.xml.ScXmlPattern
import org.jetbrains.plugins.scala.lang.psi.api.statements.params._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValue, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScTemplateDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScInterpolationPatternImpl
import org.jetbrains.plugins.scala.lang.psi.impl.base.{ScPatternListImpl, ScStableCodeReferenceElementImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.{ScalaPsiElementFactory, ScalaPsiManager}
import org.jetbrains.plugins.scala.lang.psi.types.api._
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.{ScDesignatorType, ScThisType}
import org.jetbrains.plugins.scala.lang.psi.types.result._
import org.jetbrains.plugins.scala.lang.psi.types.{api, _}
import org.jetbrains.plugins.scala.lang.resolve._
import org.jetbrains.plugins.scala.lang.resolve.processor.{CompletionProcessor, ExpandedExtractorResolveProcessor}
import org.jetbrains.plugins.scala.macroAnnotations.{CachedInsidePsiElement, ModCount}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel.Scala_2_11
import org.jetbrains.plugins.scala.project._

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.meta.intellij.QuasiquoteInferUtil

/**
 * @author Alexander Podkhalyuzin
 */

trait ScPattern extends ScalaPsiElement with Typeable {
  def isIrrefutableFor(t: Option[ScType]): Boolean = false

  override def getType(ctx: TypingContext): TypeResult[ScType] = Failure("Cannot type pattern", Some(this))

  def bindings: Seq[ScBindingPattern] = {
    val b = new ArrayBuffer[ScBindingPattern]

    def inner(p: ScPattern) {
      p match {
        case binding: ScBindingPattern => b += binding
        case _ =>
      }

      for (sub <- p.subpatterns) {
        inner(sub)
      }
    }

    inner(this)
    b
  }

  def typeVariables: Seq[ScTypeVariableTypeElement] = {
    val b = new ArrayBuffer[ScTypeVariableTypeElement]

    def inner(p: ScPattern) {
      p match {
        case ScTypedPattern(te) =>
          te.accept(new ScalaRecursiveElementVisitor {
            override def visitTypeVariableTypeElement(tvar: ScTypeVariableTypeElement): Unit = {
              b += tvar
            }
          })
        case _ =>
      }

      for (sub <- p.subpatterns) {
        inner(sub)
      }
    }

    inner(this)
    b
  }

  override def accept(visitor: ScalaElementVisitor) {
    visitor.visitPattern(this)
  }

  def subpatterns: Seq[ScPattern] = this match {
    case _: ScReferencePattern => Seq.empty
    case _ => findChildrenByClassScala[ScPattern](classOf[ScPattern])
  }

  private def expectedTypeForExtractorArg(ref: ScStableCodeReferenceElement,
                                          argIndex: Int,
                                          expected: Option[ScType],
                                          totalNumberOfPatterns: Int): Option[ScType] = {
    val bind: Option[ScalaResolveResult] = ref.bind() match {
      case Some(ScalaResolveResult(_: ScBindingPattern | _: ScParameter, _)) =>
        val resolve = ref match {
          case refImpl: ScStableCodeReferenceElementImpl =>
            refImpl.doResolve(refImpl, new ExpandedExtractorResolveProcessor(ref, ref.refName, ref.getKinds(incomplete = false), ref.getContext match {
              case inf: ScInfixPattern => inf.expectedType
              case constr: ScConstructorPattern => constr.expectedType
              case _ => None
            }))
        }
        if (resolve.length != 1) None
        else {
          resolve(0) match {
            case s: ScalaResolveResult => Some(s)
            case _ => None
          }
        }
      case m => m
    }

    def calculateSubstitutor(_tp: ScType, funType: ScType, substitutor: ScSubstitutor): ScSubstitutor = {
      val tp = _tp match {
        case ex: ScExistentialType => ex.quantified
        case _                     => _tp
      }

      def rightWay: ScSubstitutor = {
        val conformance = substitutor.subst(funType).conforms(tp, new ScUndefinedSubstitutor())
        if (conformance._1) {
          val undefSubst = conformance._2
          undefSubst.getSubstitutor match {
            case Some(newSubst) => newSubst.followed(substitutor)
            case _              => substitutor
          }
        } else substitutor
      }

      //todo: looks quite hacky to try another direction first, do you know better? see SCL-6543
      val conformance = tp.conforms(substitutor.subst(funType), new ScUndefinedSubstitutor())
      if (conformance._1) {
        val undefSubst = conformance._2
        undefSubst.getSubstitutor match {
          case Some(newSubst) => newSubst.followed(substitutor)
          case _              => rightWay
        }
      } else rightWay
    }

    bind match {
      case Some(ScalaResolveResult(fun: ScFunction, _)) if fun.name == "unapply" && ScPattern.isQuasiquote(fun) =>
        val tpe = getContext.getContext match {
          case _: ScInterpolationPattern =>
            val parts = getParent.asInstanceOf[ScalaPsiElement]
              .findChildrenByType(ScalaTokenTypes.tINTERPOLATED_STRING)
              .map(_.getText)
            if (argIndex < parts.length && parts(argIndex).endsWith("..."))
              ScalaPsiElementFactory.createTypeElementFromText("Seq[Seq[scala.reflect.api.Trees#Tree]]", PsiManager.getInstance(getProject))
            if (argIndex < parts.length && parts(argIndex).endsWith(".."))
              ScalaPsiElementFactory.createTypeElementFromText("Seq[scala.reflect.api.Trees#Tree]", PsiManager.getInstance(getProject))
            else
              ScalaPsiElementFactory.createTypeElementFromText("scala.reflect.api.Trees#Tree", PsiManager.getInstance(getProject))
        }
        tpe.getType().toOption
      case Some(ScalaResolveResult(fun: ScFunction, _)) if fun.name == "unapply" && ScPattern.isMetaQQ(fun) =>
        val patterns = QuasiquoteInferUtil.getMetaQQPatternTypes(getParent.getParent.asInstanceOf[ScInterpolationPatternImpl])
        val clazz = patterns(argIndex)
        val tpe = ScalaPsiElementFactory.createTypeElementFromText(clazz, PsiManager.getInstance(getProject))
        tpe.getType().toOption
      case Some(ScalaResolveResult(fun: ScFunction, substitutor: ScSubstitutor)) if fun.name == "unapply" &&
              fun.parameters.count(!_.isImplicitParameter) == 1 =>
        val subst = if (fun.typeParameters.isEmpty) substitutor else {
          var undefSubst = fun.typeParameters.foldLeft(ScSubstitutor.empty) { (s, p) =>
            s.bindT(p.nameAndId, UndefinedType(TypeParameterType(p, Some(substitutor))))
          }
          val clazz = ScalaPsiUtil.getContextOfType(this, true, classOf[ScTemplateDefinition])
          clazz match {
            case clazz: ScTemplateDefinition =>
              undefSubst = undefSubst.followed(new ScSubstitutor(ScThisType(clazz)))
            case _ =>
          }
          val firstParameterType = fun.parameters.head.getType(TypingContext.empty) match {
            case Success(tp, _) => tp
            case _ => return None
          }
          val funType = undefSubst.subst(firstParameterType)
          expected match {
            case Some(tp) => calculateSubstitutor(tp, funType, substitutor)
            case _ => substitutor
          }
        }
        fun.returnType match {
          case Success(rt, _) =>
            def updateRes(tp: ScType): ScType = {
              val parameters: Seq[ScTypeParam] = fun.typeParameters
              tp.recursiveVarianceUpdate {
                case (tp: TypeParameterType, variance) if parameters.contains(tp.psiTypeParameter) =>
                  (true, if (variance == -1) substitutor.subst(tp.lowerType.v)
                  else substitutor.subst(tp.upperType.v))
                case (typez, _) => (false, typez)
              }
            }
            val subbedRetTp: ScType = subst.subst(rt)
            if (subbedRetTp.equiv(api.Boolean)) None
            else {
              val args = ScPattern.extractorParameters(subbedRetTp, this, ScPattern.isOneArgCaseClassMethod(fun))
              if (totalNumberOfPatterns == 1 && args.length > 1) Some(TupleType(args)(getProject, getResolveScope))
              else if (argIndex < args.length) Some(updateRes(subst.subst(args(argIndex)).unpackedType))
              else None
            }
          case _ => None
        }
      case Some(ScalaResolveResult(fun: ScFunction, substitutor: ScSubstitutor)) if fun.name == "unapplySeq" &&
            fun.parameters.count(!_.isImplicitParameter) == 1 =>
        val subst = if (fun.typeParameters.isEmpty) substitutor else {
          val undefSubst = substitutor followed fun.typeParameters.foldLeft(ScSubstitutor.empty) { (s, p) =>
            s.bindT(p.nameAndId, UndefinedType(TypeParameterType(p, Some(substitutor))))
          }
          val firstParameterRetTp = fun.parameters.head.getType(TypingContext.empty) match {
            case Success(tp, _) => tp
            case _ => return None
          }
          val funType = undefSubst.subst(firstParameterRetTp)
          expected match {
           case Some(tp) => calculateSubstitutor(tp, funType, substitutor)
           case _ => substitutor
          }
        }
        fun.returnType match {
          case Success(rt, _) =>
            val args = ScPattern.extractorParameters(subst.subst(rt), this, ScPattern.isOneArgCaseClassMethod(fun))
            if (args.isEmpty) return None
            if (argIndex < args.length - 1) return Some(subst.subst(args(argIndex)))
            val lastArg = args.last
            (lastArg +: BaseTypes.get(lastArg)).find {
              case ParameterizedType(des, seqArgs) => seqArgs.length == 1 && des.extractClass().exists {
                _.qualifiedName == "scala.collection.Seq"
              }
              case _ => false
            } match {
              case Some(seq@ParameterizedType(_, seqArgs)) =>
                this match {
                  case n: ScNamingPattern if n.getLastChild.isInstanceOf[ScSeqWildcard] => Some(subst.subst(seq))
                  case _ => Some(subst.subst(seqArgs.head))
                }
              case _ => None
            }
          case _ => None
        }
      case Some(ScalaResolveResult(FakeCompanionClassOrCompanionClass(cl: ScClass), subst: ScSubstitutor))
        if cl.isCase && cl.tooBigForUnapply =>
        val undefSubst = subst.followed(new ScSubstitutor(ScThisType(cl)))
        val params: Seq[ScParameter] = cl.parameters
        val types = params.map(_.getType(TypingContext.empty).getOrAny).map(undefSubst.subst)
        val args = if (types.nonEmpty && params.last.isVarArgs) {
          val lastType = types.last
          val tp = ScalaPsiElementFactory.createTypeFromText(s"scala.collection.Seq[${lastType.canonicalText}]", cl, cl)
          types.dropRight(1) :+ tp
        } else types
        if (argIndex < args.length) Some(args(argIndex))
        else None
      case _ => None
    }
  }

  @CachedInsidePsiElement(this, ModCount.getBlockModificationCount)
  def expectedType: Option[ScType] = getContext match {
    case list : ScPatternList => list.getContext match {
      case _var : ScVariable => _var.getType(TypingContext.empty).toOption
      case _val : ScValue => _val.getType(TypingContext.empty).toOption
    }
    case argList : ScPatternArgumentList =>
      argList.getContext match {
        case constr : ScConstructorPattern =>
          val thisIndex: Int = constr.args.patterns.indexWhere(_ == this)
          expectedTypeForExtractorArg(constr.ref, thisIndex, constr.expectedType, argList.patterns.length)
        case _ => None
      }
    case composite: ScCompositePattern => composite.expectedType
    case infix: ScInfixPattern =>
      val i =
        if (infix.leftPattern == this) 0
        else if (this.isInstanceOf[ScTuplePattern]) return None //this is handled elsewhere in this function
        else 1
      expectedTypeForExtractorArg(infix.reference, i, infix.expectedType, 2)
    case par: ScParenthesisedPattern => par.expectedType
    case patternList : ScPatterns => patternList.getContext match {
      case tuple : ScTuplePattern =>
        tuple.getContext match {
          case infix: ScInfixPattern =>
            if (infix.leftPattern != tuple) {
              //so it's right pattern
              val i = tuple.patternList match {
                case Some(patterns: ScPatterns) => patterns.patterns.indexWhere(_ == this)
                case _ => return None
              }
              val patternLength: Int = tuple.patternList match {
                case Some(pat) => pat.patterns.length
                case _ => -1 //is it possible to get here?
              }
              return expectedTypeForExtractorArg(infix.reference, i + 1, infix.expectedType, patternLength)
            }
          case _ =>
        }

        tuple.expectedType.flatMap {
          case TupleType(comps) =>
            for ((t, p) <- comps.iterator.zip(patternList.patterns.iterator)) {
              if (p == this) return Some(t)
            }
            None
          case et0 if et0 == api.AnyRef || et0 == Any => Some(api.Any)
          case _                                              => None
        }
      case _: ScXmlPattern =>
        val nodeClass: Option[PsiClass] = ScalaPsiManager.instance(getProject).getCachedClass(getResolveScope, "scala.xml.Node")
        nodeClass.flatMap { nodeClass =>
          this match {
            case n: ScNamingPattern if n.getLastChild.isInstanceOf[ScSeqWildcard] =>
              val seqClass: Option[PsiClass] =
                ScalaPsiManager.instance(getProject).getCachedClass(getResolveScope, "scala.collection.Seq")
              seqClass.map { seqClass =>
                ScParameterizedType(ScDesignatorType(seqClass), Seq(ScDesignatorType(nodeClass)))
              }
            case _ => Some(ScDesignatorType(nodeClass))
          }
        }
      case _ => None
    }
    case clause: ScCaseClause => clause.getContext/*clauses*/.getContext match {
      case matchStat : ScMatchStmt => matchStat.expr match {
        case Some(e) => Some(e.getType(TypingContext.empty).getOrAny)
        case _ => None
      }
      case b: ScBlockExpr if b.getContext.isInstanceOf[ScCatchBlock] =>
        val thr = ScalaPsiManager.instance(getProject).getCachedClass(getResolveScope, "java.lang.Throwable")
        thr.map(ScalaType.designator(_))
      case b : ScBlockExpr =>
        b.expectedType(fromUnderscore = false) match {
          case Some(et) =>
            et.removeAbstracts match {
              case FunctionType(_, Seq()) => Some(api.Unit)
              case FunctionType(_, Seq(p0)) => Some(p0)
              case FunctionType(_, params) =>
                val tt = TupleType(params)(getProject, getResolveScope)
                Some(tt)
              case PartialFunctionType(_, param) => Some(param)
              case _ => None
            }
          case None => None
        }
      case _ => None
    }
    case named: ScNamingPattern => named.expectedType
    case _: ScGenerator =>
      val analog = getAnalog
      if (analog != this) analog.expectedType
      else None
    case enum: ScEnumerator =>
      Option(enum.rvalue).flatMap { rvalue =>
        rvalue.getType(TypingContext.empty).toOption
      }
    case _ => None
  }

  def getAnalog: ScPattern = {
    getContext match {
      case gen: ScGenerator =>
        val f: ScForStatement = gen.getContext.getContext match {
          case fr: ScForStatement => fr
          case _ => return this
        }
        f.getDesugarizedExpr match {
          case Some(_) =>
            if (analog != null) return analog
          case _ =>
        }
        this
      case _ => this
    }
  }

  var desugarizedPatternIndex = -1
  var analog: ScPattern = null
}

object ScPattern {
  def isOneArgCaseClassMethod(fun: ScFunction): Boolean = {
    fun.syntheticCaseClass match {
      case Some(c: ScClass) => c.constructor.exists(_.effectiveFirstParameterSection.length == 1)
      case _ => false
    }
  }

  private def findMember(name: String, tp: ScType, place: PsiElement)
                        (implicit typeSystem: TypeSystem): Option[ScType] = {
    val cp = new CompletionProcessor(StdKinds.methodRef, place, forName = Some(name))
    cp.processType(tp, place)
    cp.candidatesS.flatMap {
      case ScalaResolveResult(fun: ScFunction, subst) if fun.parameters.isEmpty && fun.name == name =>
        Seq(subst.subst(fun.returnType.getOrAny))
      case ScalaResolveResult(b: ScBindingPattern, subst) if b.name == name =>
        Seq(subst.subst(b.getType(TypingContext.empty).getOrAny))
      case ScalaResolveResult(param: ScClassParameter, subst) if param.name == name =>
        Seq(subst.subst(param.getType(TypingContext.empty).getOrAny))
      case _ => Seq.empty
    }.headOption
  }

  private def extractPossibleProductParts(receiverType: ScType, place: PsiElement, isOneArgCaseClass: Boolean)
                                         (implicit typeSystem: TypeSystem): Seq[ScType] = {
    val res: ArrayBuffer[ScType] = new ArrayBuffer[ScType]()
    @tailrec
    def collect(i: Int) {
      findMember(s"_$i", receiverType, place) match {
        case Some(tp) if !isOneArgCaseClass =>
          res += tp
          collect(i + 1)
        case _ =>
          if (i == 1) res += receiverType
      }
    }
    collect(1)
    res.toSeq
  }

  def extractProductParts(tp: ScType, place: PsiElement)
                         (implicit typeSystem: TypeSystem): Seq[ScType] = {
    extractPossibleProductParts(tp, place, isOneArgCaseClass = false)
  }

  def expectedNumberOfExtractorArguments(returnType: ScType, place: PsiElement, isOneArgCaseClass: Boolean)
                                        (implicit typeSystem: TypeSystem): Int =
    extractorParameters(returnType, place, isOneArgCaseClass).size

  def extractorParameters(returnType: ScType, place: PsiElement, isOneArgCaseClass: Boolean)
                         (implicit typeSystem: TypeSystem): Seq[ScType] = {
    def collectFor2_11: Seq[ScType] = {
      findMember("isEmpty", returnType, place) match {
        case Some(tp) if api.Boolean.equiv(tp) =>
        case _ => return Seq.empty
      }

      val receiverType = findMember("get", returnType, place).getOrElse(return Seq.empty)
      extractPossibleProductParts(receiverType, place, isOneArgCaseClass)
    }

    val level = place.scalaLanguageLevelOrDefault
    if (level >= Scala_2_11) collectFor2_11
    else {
      returnType match {
        case ParameterizedType(des, args) =>
          des.extractClass() match {
            case Some(clazz) if clazz.qualifiedName == "scala.Option" ||
                    clazz.qualifiedName == "scala.Some" =>
              if (args.length == 1) {
                def checkProduct(tp: ScType): Seq[ScType] = {
                  val productChance = collectFor2_11
                  if (productChance.length <= 1) Seq(tp)
                  else {
                    val productFqn = "scala.Product" + productChance.length
                    val project = place.getProject
                    (for {
                      productClass <- ScalaPsiManager.instance(project).getCachedClass(place.getResolveScope, productFqn)
                      clazz <- tp.extractClass(project)
                    } yield clazz == productClass || clazz.isInheritor(productClass, true)).
                      filter(identity).fold(Seq(tp))(_ => productChance)
                  }
                }
                args.head match {
                  case tp if isOneArgCaseClass => Seq(tp)
                  case TupleType(comps) => comps
                  case tp => checkProduct(tp)
                }
              } else Seq.empty
            case _ => Seq.empty
          }
        case _ => Seq.empty
      }
    }
  }

  def isQuasiquote(fun: ScFunction): Boolean = {
    val fqnO  = Option(fun.containingClass).map(_.qualifiedName)
    fqnO.exists(fqn => fqn.contains('.') && fqn.substring(0, fqn.lastIndexOf('.')) == "scala.reflect.api.Quasiquotes.Quasiquote")
  }

  def isMetaQQ(fun: ScFunction): Boolean = {
    val fqnO  = Option(fun.containingClass).map(_.qualifiedName)
    fqnO.exists(fqn => fqn == "scala.meta.quasiquotes.Api.XtensionQuasiquoteTerm.q")
  }

}
