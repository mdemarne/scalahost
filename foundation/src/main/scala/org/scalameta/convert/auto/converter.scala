package org.scalameta.convert
package auto

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.reflect.macros.blackbox
import scala.reflect.macros.whitebox
import scala.collection.mutable
import org.scalameta.unreachable
import org.scalameta.invariants._
import org.scalameta.reflection._

// NOTE: a macro annotation that converts naive patmat-based converters
// like `@converter def toMtree(in: Any, pt: Pt): Any = in match { ... }`
// into a typeclass, a number of instances (created from patmat clauses) and some glue
// see the resulting quasiquote of ConverterMacros.converter to get a better idea what's going on
// has a number of quirks to accommodate ambiguous conversions and other fun stuff, which I should document later (TODO)
class converter extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro ConverterMacros.converter
}

class ConverterMacros(val c: whitebox.Context) extends MacroToolkit {
  import c.universe._
  import internal._
  lazy val global: c.universe.type = c.universe
  val DeriveInternal = q"_root_.org.scalameta.convert.auto.internal"
  def converter(annottees: Tree*): Tree = {
    def transform(ddef: DefDef): ModuleDef = {
      val q"$mods def $name[..$tparams](...$paramss): $tpt = $body" = ddef
      val (rawprelude, rawclauses) = body match {
        case q"{ ..$rawprelude; in match { case ..$rawclauses } }" => (rawprelude, rawclauses)
        case _ => c.abort(c.enclosingPosition, "@converter methods must end in a pattern match")
      }
      if (tparams.nonEmpty) c.abort(c.enclosingPosition, "@converter methods must not define type parameters")
      if (paramss.length != 1 || paramss(0).length != 2) c.abort(c.enclosingPosition, "@converter methods must define just two parameters: (in: Any, pt: ru.Type)")
      val returnTypeIsAny = tpt match { case tq"Any" => true; case _ => false }
      if (!returnTypeIsAny) c.abort(c.enclosingPosition, "@converter methods must define their return type as Any")
      rawclauses foreach {
        case cq"$_ => $_" => // ok
        case cq"$_ if pt <:< typeOf[$_] => $_" => // ok
        case cq"$_ if $guard => $_" => c.abort(guard.pos, "@converter matches must not use guards except for `pt <:< typeOf[...]`")
      }

      val wrapper = name
      val dummy = c.freshName(TermName("dummy"))
      val typeclass = TypeName(name.toString.capitalize + "Cvt")
      val exception = tq"_root_.scala.meta.ConvertException"
      val companion = typeclass.toTermName
      val helperClass = c.freshName(TypeName(name.toString.capitalize + "Helper"))
      val helperInstance = c.freshName(TermName(name.toString.capitalize + "Helper"))
      object connector extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case DefDef(mods, name, tparams, vparamss, tpt, body) if name != termNames.CONSTRUCTOR =>
            val body1 = atPos(body.pos)(q"$DeriveInternal.connectConverters($body)")
            DefDef(mods, name, tparams, vparamss, tpt, body1)
          case _ =>
            super.transform(tree)
        }
      }
      val prelude = rawprelude.map(connector.transform)
      def normalize(clause: CaseDef): List[CaseDef] = clause match {
        case CaseDef(Alternative(alts), guard, body) => alts.map(alt => atPos(clause.pos)(CaseDef(alt, guard, body)))
        case _ => List(clause)
      }
      val clauses = rawclauses.flatMap(normalize)
      def typeref(termref: Tree): Tree = termref match {
        case Ident(name @ TermName(_)) => Ident(name.toTypeName)
        case Select(qual, name @ TermName(_)) => Select(qual, name.toTypeName)
      }
      def intpe(pat: Tree): Tree = pat match {
        case Ident(_) | Select(_, _) => pat
        case Bind(_, body) => intpe(body)
        case Apply(Select(Apply(Ident(TermName("StringContext")), _), _), _) => c.abort(pat.pos, "@converter matches must use manual tree deconstruction")
        case Apply(fn, _) => typeref(fn)
        case Typed(_, tpe) => tpe
      }
      def outpe(guard: Tree): Tree = guard match {
        case q"pt <:< typeOf[$tpe]" => tpe
        case _ => EmptyTree
      }
      case class Instance(in: Tree, out: Tree, clauses: List[Tree], notImplemented: Boolean, notBounded: Boolean) {
        private val prefix = in.toString.replace(".", "") + "2" + (if (notBounded) "Wildcard" else out.toString.replace(".", ""))
        lazy val decl = c.freshName(TermName("Decl$" + prefix))
        lazy val impl = c.freshName(TermName("Impl$" + prefix))
        lazy val sig = c.freshName(TermName("Sig$" + prefix))
        def pos = clauses.head.pos
      }
      val instances = mutable.ListBuffer[Instance]()
      clauses.foreach(clause => clause match {
        case cq"$pat if $guard => $body" =>
          val in = intpe(pat)
          val out = outpe(guard).orElse(tq"m.Tree")
          val guardlessClause = atPos(clause.pos)(cq"$pat => $body")
          val notImplemented = body match { case q"unreachable" => true; case q"???" => true; case _ => false }
          val notBounded = outpe(guard).isEmpty
          val i = instances.indexWhere{ case Instance(iin, iout, _, _, _) => in.toString == iin.toString && out.toString == iout.toString }
          if (i == -1) instances += Instance(in, out, List(guardlessClause), notImplemented, notBounded)
          else instances(i) = Instance(in, out, instances(i).clauses :+ guardlessClause, notImplemented, notBounded)
      })
      val computeParts = instances.map({
        case Instance(_, _, Nil, _, _) => unreachable
        case Instance(_, out, List(clause), _, _) => clause.appendMetadata("pt" -> out)
        case Instance(in, out, clauses, _, _) => atPos(clauses.head.pos)(cq"in: $in => in match { case ..$clauses }".appendMetadata("pt" -> out))
      })
      // NOTE: having this as an rhs of a dummy val rather than as a statement in the template is important
      // because template statements get typechecked after val synthesis take place
      // and we can't afford this, because we need to get these converters computed before anything else takes place
      val computeConverters = atPos(ddef.pos)(q"""
        protected[meta] val computeConverters = $DeriveInternal.computeConverters($wrapper.$companion){
          @$DeriveInternal.declNames(..${instances.filter(!_.notImplemented).map(_.decl.toString)})
          def dummy(in: Any): Any = {
            ..$rawprelude
            in match { case ..$computeParts }
          }
          ()
        }
      """)
      val instanceSigs = instances.filter(!_.notImplemented).map(instance => atPos(instance.pos)(
        q"""
          lazy val ${instance.sig} = $DeriveInternal.lubConverters[${instance.in}, ${instance.out}]
        """
      ))
      val instanceDecls = instances.filter(!_.notImplemented).map(instance => atPos(instance.pos)(
        q"""
          implicit val ${instance.decl}: $typeclass[${instance.sig}.In, ${instance.sig}.Out] = ${companion.toTypeName}.this.apply((in: ${instance.in}) => {
            ${wrapper.toTypeName}.this.${instance.impl}(in)
          })
        """
      ))
      val instanceImpls = instances.filter(!_.notImplemented).map(instance => atPos(instance.pos)(
        q"""
          private def ${instance.impl}(in0: ${instance.in}): $companion.${instance.sig}.Out = {
            // TODO: fix the duplication wrt Ensugar
            val in = $DeriveInternal.customPrologue(in0)
            def logFailure() = {
              def summary(x: Any) = x match { case x: Product => x.productPrefix; case null => "null"; case _ => x.getClass }
              var details = in.toString.replace("\n", "")
              if (details.length > 60) details = details.take(60) + "..."
              val actualType = summary(in)
              val expectedType = ${instance.in.toString}.substring(2)
              val prefix = if (actualType == expectedType) expectedType else (actualType + " <: " + expectedType)
              Console.err.println("(" + prefix + ") " + details)
            }
            try {
              val out = $DeriveInternal.connectConverters {
                val $helperInstance = new $helperClass(in)
                import $helperInstance._
                ..${prelude.collect { case imp: Import => imp }}
                in match { case ..${instance.clauses} }
              }
              $DeriveInternal.customEpilogue(out)
            } catch {
              case ex: _root_.scala.meta.ConvertException => logFailure(); throw ex
              case err: _root_.java.lang.AssertionError => logFailure(); throw new $exception(in, err.getMessage, _root_.scala.Some(err))
              case err: _root_.org.scalameta.UnreachableError => logFailure(); throw new $exception(in, err.getMessage, _root_.scala.Some(err))
              case ex: _root_.scala.Exception => logFailure(); throw new $exception(in, ex.getMessage, _root_.scala.Some(ex))
            }
          }
        """
      ))

      q"""
        $mods object $wrapper {
          $computeConverters
          private class $helperClass(in: Any) { ..$prelude }
          trait $typeclass[In, Out] extends _root_.org.scalameta.convert.Convert[In, Out]
          object $companion {
            def apply[In, Out](f: In => Out): $typeclass[In, Out] = new $typeclass[In, Out] { def apply(in: In): Out = f(in) }
            import _root_.scala.language.experimental.macros
            ..$instanceSigs
            ..$instanceDecls
          }
          ..$instanceImpls
          import _root_.scala.language.experimental.macros
          def apply[In](x: In): Any = macro $DeriveInternal.WhiteboxMacros.lookupConvertersWithoutPt[In]
          def apply[In, Pt](x: In, pt: _root_.java.lang.Class[Pt]): Any = macro $DeriveInternal.WhiteboxMacros.lookupConvertersWithPt[In, Pt]
        }
      """
    }
    val expanded = annottees match {
      case (ddef: DefDef) :: rest => transform(ddef) :: rest
      case annottee :: rest => c.abort(annottee.pos, "only methods can be @converter")
    }
    q"{ ..$expanded; () }"
  }
}

package object internal {
  case class Converter(in: Any, pt: Any, out: Any, module: Any, method: String, methodRef: Any, derived: Boolean)
  class computedConvertersAnnotation(converters: List[Converter]) extends scala.annotation.StaticAnnotation
  class WildcardDummy

  class declNames(xs: String*) extends scala.annotation.StaticAnnotation
  def computeConverters[T](typeclassCompanion: Any)(x: T): Unit = macro WhiteboxMacros.computeConverters
  def lubConverters[T, U]: Any = macro WhiteboxMacros.lubConverters[T, U]
  def connectConverters[T](x: T): Any = macro WhiteboxMacros.connectConverters
  def customPrologue[T](x: T): T = macro WhiteboxMacros.customPrologue[T]
  def customEpilogue[T](x: T): T = macro WhiteboxMacros.customEpilogue[T]

  class WhiteboxMacros(val c: whitebox.Context) extends MacroToolkit {
    import c.universe._
    import definitions._
    import c.internal._
    import decorators._
    import Flag._
    lazy val global: c.universe.type = c.universe
    val Predef_??? = typeOf[Predef.type].member(TermName("$qmark$qmark$qmark")).asMethod
    val List_apply = typeOf[List.type].member(TermName("apply")).asMethod
    val Some_apply = typeOf[Some.type].member(TermName("apply")).asMethod
    val UnreachableError_raise = typeOf[org.scalameta.UnreachableError.type].member(TermName("raise")).asMethod
    val Auto_derive = typeOf[org.scalameta.convert.auto.`package`.type].member(TermName("derive")).asMethod
    val SeqClass = symbolOf[scala.collection.immutable.Seq[_]]
    val Ops_cvt = typeOf[org.scalameta.convert.auto.`package`.Ops].member(TermName("cvt")).asMethod
    val Ops_cvtbang = typeOf[org.scalameta.convert.auto.`package`.Ops].member(TermName("cvt_$bang")).asMethod
    val Any_asInstanceOf = AnyTpe.member(TermName("asInstanceOf")).asMethod
    val AstClassAnnotation = symbolOf[org.scalameta.ast.internal.astClass]
    val ComputedConvertersAnnotation = symbolOf[org.scalameta.convert.auto.internal.computedConvertersAnnotation]
    val ComputedConvertersDatabearer = symbolOf[org.scalameta.convert.auto.internal.Converter]
    val PersistedWildcardType = typeOf[WildcardDummy]
    val DeriveInternal = q"_root_.org.scalameta.convert.auto.internal"
    val ToolkitTrait = tq"_root_.org.scalameta.reflection.GlobalToolkit"
    val TreeTpe = c.mirror.staticClass("scala.meta.Tree").toType
    implicit class RichUnimplementedTree(tree: Tree) {
      def isUnreachableError: Boolean = tree match {
        case Typed(typee, _) if typee.symbol == UnreachableError_raise => true
        case _ => false
      }
      def isPredef_??? : Boolean = tree.symbol == Predef_???
      def isUnimplemented: Boolean = tree.isUnreachableError || tree.isPredef_???
    }
    object Cvt {
      def unapply(x: Tree): Option[(Tree, Type, Boolean)] = {
        object RawCvt {
          def unapply(x: Tree): Option[(Tree, Boolean)] = x match {
            case q"$_($convertee).$_" if x.symbol == Ops_cvt => Some((convertee, false))
            case q"$_($convertee).$_" if x.symbol == Ops_cvtbang => Some((convertee, true))
            case _ => None
          }
        }
        object Ascribe {
          def unapply(x: Tree): Option[(Tree, Type)] = x match {
            case Typed(x, tpt) =>
              tpt.tpe match {
                case AnnotatedType(List(unchecked), nothing) if unchecked.tree.tpe =:= typeOf[unchecked] && nothing =:= NothingTpe =>
                  Some((x, WildcardType))
                case pt =>
                  Some((x, pt))
              }
            case _ => None
          }
        }
        object Cast {
          def unapply(x: Tree): Option[(Tree, Type)] = x match {
            case TypeApply(sel @ Select(x, _), List(pt)) if sel.symbol == Any_asInstanceOf => Some((x, pt.tpe))
            case _ => None
          }
        }
        x match {
          // TODO: this is a soundness hole
          // if we allow WildcardType as pt, this means that we are fine with Nothings from `convertee.cvt`
          // being propagated into inferred types
          // that can lead to disasters like
          //
          //   val mthis = if (qual != g.tpnme.EMPTY) Some(qual.cvt) else None
          //   m.Qual.Super(mthis, msuper)
          //
          // here, regardless of the actual type of `qual.cvt`, the typecheck will succeed
          // because `Some(qual.cvt)` has type `Some[Nothing]`, pthis has type `Option[Nothing]`,
          // and, consequently, mthis will fit the `Option[m.Qual.Name]` required by `m.Qual.Super.qual`
          // if the actual type is incorrect, that'll be a ClassCastException
          case RawCvt(convertee, force) => Some((convertee, WildcardType, force))
          case Ascribe(RawCvt(convertee, force), pt) => Some((convertee, pt, force))
          case Cast(RawCvt(convertee, _), pt) => Some((convertee, pt, true))
          case _ => None
        }
      }
    }
    case class Converter(in: Type, pt: Type, out: Type, module: Tree, method: String, methodRef: Tree, derived: Boolean)
    type SharedConverter = org.scalameta.convert.auto.internal.Converter
    val SharedConverter = org.scalameta.convert.auto.internal.Converter
    def computeConverters(typeclassCompanion: Tree)(x: Tree): Tree = {
      import c.internal._, decorators._
      val q"{ ${dummy @ q"def $_(in: $_): $_ = { ..$prelude; in match { case ..$clauses } }"}; () }" = x
      def toMtreeConverters: List[SharedConverter] = {
        def precisetpe(tree: Tree): Type = tree match {
          case If(_, thenp, elsep) => preciseLub(List(precisetpe(thenp), precisetpe(elsep)))
          case Match(_, cases) => preciseLub(cases.map(tree => precisetpe(tree.body)))
          case Block(_, expr) => precisetpe(expr)
          case tree => tree.tpe
        }
        def validateAllowedInputs(): Boolean = {
          var isValid = true
          clauses.foreach(clause => {
            val in = clause.pat.tpe.typeSymbol.asClass
            if (!in.baseClasses.exists(sym => sym.fullName == "scala.reflect.internal.Trees.Tree")) {
              c.error(clause.pos, s"must only convert from Scala trees, found $in")
              isValid = false
            }
          })
          isValid
        }
        def validateAllowedOutputs(): Boolean = {
          var isValid = true
          val validatableClauses = clauses.filter(clause => !clause.body.isUnimplemented && clause.body.symbol != Auto_derive)
          validatableClauses.foreach(clause => {
            val tpe = precisetpe(clause.body)
            if (tpe =:= NothingTpe) { isValid = false; c.error(clause.pos, "must not convert to Nothing") }
            if (!(tpe <:< typeOf[scala.meta.Tree])) { isValid = false; c.error(clause.pos, s"must only convert to trees or intersections thereof, found $tpe") }
          })
          isValid
        }
        def validateExhaustiveInputs(): Boolean = {
          val root = typeOf[scala.tools.nsc.Global]
          def ref(sym: Symbol): Type = sym match {
            case csym: ClassSymbol => csym.toType
            case msym: ModuleSymbol => msym.info
            case _ => NoType
          }
          def sortOfAllSubclassesOf(tpe: Type): List[Symbol] = root.members.toList.flatMap(sym => if ((ref(sym) <:< tpe) && !sym.isAbstract) Some(sym) else None)
          val expected = sortOfAllSubclassesOf(typeOf[scala.reflect.internal.Trees#Tree])
          val inputs = clauses.map(_.pat.tpe).map(tpe => tpe.termSymbol.orElse(tpe.typeSymbol)).map(sym => root.member(sym.name))
          val unmatched = expected.filter(exp => !inputs.exists(pat => ref(exp) <:< ref(pat)))
          if (unmatched.nonEmpty) c.error(c.enclosingPosition, "@converter is not exhaustive in its inputs; missing: " + unmatched)
          unmatched.isEmpty
        }
        def validateExhaustiveOutputs(): Boolean = {
          val leafs = symbolOf[scala.meta.Tree].asRoot.allLeafs
          val companions = leafs.map(leaf => {
            // somehow calling companionSymbol on results of knownDirectSubclasses is flaky
            // therefore, I'm doing exactly what companionSymbol should do, but manually
            val companion = leaf.sym.owner.info.member(leaf.sym.name.toTermName)
            companion.orElse(c.abort(c.enclosingPosition, "companionless leaf in @root hierarchy"))
          })
          val expected = mutable.Set(companions: _*)
          (prelude ++ clauses).foreach(_.foreach(sub => if (sub.symbol != null) expected -= sub.symbol))
          val unmatched = expected.filter(sym => {
            sym.fullName != "scala.meta.internal.ast.Pat.Interpolate" && // not implemented yet
            sym.fullName != "scala.meta.internal.ast.Ctor.Ref.Name" && // Ctor.Name is an alias to Ctor.Ref.Name, and it is very well used in the converter
            sym.fullName != "scala.meta.internal.ast.Ctor.Name" && // handled in a helper outside the @converter
            sym.fullName != "scala.meta.internal.ast.Ctor.Ref.Select" && // handled in a helper outside the @converter
            sym.fullName != "scala.meta.internal.ast.Ctor.Ref.Project" && // handled in a helper outside the @converter
            sym.fullName != "scala.meta.internal.ast.Ctor.Ref.Function" && // handled in a helper outside the @converter
            sym.fullName != "scala.meta.internal.ast.Type.Name" && // handled in a helper outside the @converter
            !sym.fullName.startsWith("scala.meta.internal.ast.Lit") && // handled in a helper outside the @converter
            !sym.fullName.endsWith(".Quasi") && // never produced by the converter
            sym.fullName != "scala.meta.internal.ast.Mod.Ffi" // never produced by the converter
          })
          val s_unmatched = unmatched.map(sym => sym.fullName.replace("scala.meta.internal.ast.", "")).mkString(", ")
          if (unmatched.nonEmpty) c.error(c.enclosingPosition, "@converter is not exhaustive in its outputs; missing: " + s_unmatched)
          unmatched.isEmpty
        }
        // val tups = clauses.map{ case CaseDef(pat, _, body) => (pat.tpe.toString.replace("HostContext.this.", ""), cleanLub(List(precisetpe(body))).toString.replace("scala.meta.", "p."), precisetpe(body).toString.replace("scala.meta.", "p.")) }
        // val max1 = tups.map(_._1.length).max
        // val max2 = tups.map(_._2.length).max
        // tups.foreach{ case (f1, f2, f3) => println(f1 + (" " * (max1 - f1.length + 5)) + f2 + (" " * (max2 - f2.length + 5)) + f3) }
        // ???
        val isValid = List(validateAllowedInputs(), validateAllowedOutputs(), validateExhaustiveInputs(), validateExhaustiveOutputs()).forall(Predef.identity)
        if (isValid) {
          val nontrivialClauses = clauses.filter(!_.body.isUnimplemented)
          val ins = nontrivialClauses.map(_.pat.tpe)
          val pts = nontrivialClauses.map(clause => c.typecheck(clause.metadata("pt").require[Tree], mode = c.TYPEmode).tpe)
          val underivedOuts = nontrivialClauses.map(_.body).map(body => if (body.symbol != Auto_derive) precisetpe(body) else NoType)
          val outs = nontrivialClauses.map({ case CaseDef(pat, _, body) =>
            if (body.symbol != Auto_derive) precisetpe(body)
            else lub(ins.zip(underivedOuts).collect{ case (in, out) if (in <:< pat.tpe) && (out != NoType) => out })
          })
          val methods = dummy.symbol.annotations.head.tree.children.tail.map({
            case Literal(Constant(s: String)) =>
              val qual = typeclassCompanion.duplicate
              val methodSym = typeclassCompanion.symbol.info.member(TermName(s)).orElse(c.abort(c.enclosingPosition, s"something went wrong: can't resolve $s in $typeclassCompanion"))
              q"$qual.$methodSym"
          })
          val deriveds = nontrivialClauses.map(_.body.symbol == Auto_derive)
          if (ins.length != pts.length || pts.length != outs.length || outs.length != methods.length || methods.length != deriveds.length) c.abort(c.enclosingPosition, s"something went wrong: can't create converters from ${ins.length}, ${pts.length}, ${outs.length}, ${methods.length} and ${deriveds.length}")
          ins.zip(pts).zip(outs).zip(methods).zip(deriveds).map{ case ((((in, pt), out), method), derived) => SharedConverter(in, pt, out, typeclassCompanion.duplicate, method.symbol.name.toString, method, derived) }
        } else {
          Nil
        }
      }
      val target = typeclassCompanion.symbol.owner
      if (!target.isModuleClass) c.abort(c.enclosingPosition, s"something went wrong: unexpected typeclass companion $typeclassCompanion")
      val converters = target.name.toString match {
        case "toMtree" => toMtreeConverters
        case _ => c.abort(c.enclosingPosition, "unknown target: " + target.name)
      }
      // val tups = converters.map{ case SharedConverter(in: Type, out: Type, method: Tree, derived) => ((if (derived) "*" else "") + in.toString.replace("Host.this.", ""), cleanLub(List(out)).toString.replace("scala.meta.", "p."), out.toString.replace("scala.meta.", "p."), method) }
      // val max1 = tups.map(_._1.length).max
      // val max2 = tups.map(_._2.length).max
      // val max3 = tups.map(_._3.length).max
      // tups.foreach{ case (f1, f2, f3, f4) => println(f1 + (" " * (max1 - f1.length + 5)) + f2 + (" " * (max2 - f2.length + 5)) + f3 + (" " * (max3 - f3.length + 5)) + f4) }
      // ???
      target.setAnnotations(target.annotations ++ List(Annotation({
        def smuggleType(tpe: Type) = {
          val persistedTpe = if (tpe eq WildcardType) PersistedWildcardType else tpe
          Literal(Constant(persistedTpe)).setType(constantType(Constant(persistedTpe)))
        }
        def pickleConverter(converter: SharedConverter): Tree = q"""
          new $ComputedConvertersDatabearer(
            ${smuggleType(converter.in.require[Type])},
            ${smuggleType(converter.pt.require[Type])},
            ${smuggleType(converter.out.require[Type])},
            ${converter.module.require[Tree]},
            ${converter.method},
            ${Literal(Constant(null))},
            ${converter.derived}
          )
        """
        val untypedArg = q"$ListModule(${converters.map(pickleConverter)})"
        val typedArg = c.typecheck(untypedArg)
        q"new ${ComputedConvertersAnnotation.toType}($typedArg)"
      })): _*)
      q"()"
    }
    def loadConverters(pre0: Type, sym: Symbol): List[Converter] = {
      def loop(sym: Symbol): List[Converter] = {
        if (sym == NoSymbol) Nil
        else {
          object UnsmuggleType {
            def unapply(tree: Tree): Option[Type] = tree match {
              case Literal(Constant(persistedTpe: Type)) =>
                val tpe = if (persistedTpe =:= PersistedWildcardType) WildcardType else persistedTpe
                Some(tpe)
              case _ =>
                None
            }
          }
          val ann = sym.annotations.find(_.tree.tpe.typeSymbol == ComputedConvertersAnnotation)
          val args = ann.map(_.tree.children.last match {
            case q"$_.$_[..$_]($_.$_[..$_](..$args))" => args
            case _ => c.abort(c.enclosingPosition, "something went really wrong: can't load converters")
          })
          val result = args.map(_.map(_ match { case q"""new $_(
            ${UnsmuggleType(in)},
            ${UnsmuggleType(pt)},
            ${UnsmuggleType(out)},
            $module,
            ${method: String},
            $_,
            ${derived: Boolean}
          )""" =>
            val pre = pre0.orElse(sym.asClass.thisPrefix)
            def computeMethodRef: Tree = {
              val methodSym = module.symbol.info.member(TermName(method)).orElse(c.abort(c.enclosingPosition, s"something went wrong: can't resolve $method in $module"))
              if (pre0 == NoType) q"$module.$methodSym"
              else gen.mkAttributedRef(singleType(pre, module.symbol), methodSym)
            }
            Converter(in.asSeenFrom(pre, sym), pt.asSeenFrom(pre, sym), out.asSeenFrom(pre, sym), module, method, computeMethodRef, derived)
          }))
          val next = List(sym.owner) ++ (if (sym.isModule) List(sym.asModule.moduleClass) else Nil)
          result.getOrElse(next.flatMap(loop))
        }
      }
      val converters = loop(sym)
      if (converters.isEmpty && !c.hasErrors) {
        c.abort(c.enclosingPosition, "something went wrong: can't load converters")
      } else {
        converters
      }
    }
    def convert(x: Tree, in: Type, out: Type, allowDerived: Boolean, allowInputDowncasts: Boolean, allowOutputDowncasts: Boolean, pre: Type, sym: Symbol): Tree = {
      def fail(reason: String) = { c.error(x.pos, s"can't derive a converter from $in to $out because $reason"); gen.mkAttributedRef(Predef_???).setType(NothingTpe) }
      if (in.baseClasses.contains(SeqClass)) {
        if (!out.baseClasses.contains(SeqClass)) fail(s"of a collection rank mismatch")
        else {
          val in1 = in.baseType(SeqClass).typeArgs.head
          val out1 = out.baseType(SeqClass).typeArgs.head
          val param = c.freshName(TermName("x"))
          val result1 = convert(atPos(x.pos)(q"$param"), in1, out1, allowDerived, allowInputDowncasts, allowOutputDowncasts, pre, sym)
          q"$x.map(($param: ${tq""}) => $result1)"
        }
      } else {
        val converters = loadConverters(pre, sym)
        def matchesIn(c: Converter) = (in <:< c.in) || (allowInputDowncasts && (c.in <:< in))
        def matchesOut(c: Converter) = {
          def matchesOutTpe(cout: Type) = (cout <:< out) || (allowOutputDowncasts && (out <:< cout))
          extractIntersections(c.out).exists(matchesOutTpe)
        }
        var matching = converters.filter(c => !c.derived || allowDerived).filter(c => matchesIn(c) && matchesOut(c))
        matching = matching.filter(c1 => !matching.exists(c2 => c1 != c2 && !(c1.in =:= c2.in) && (c1.in <:< c2.in)))
        var result = matching match {
          case Nil => fail(s"no suitable patterns were found");
          case List(Converter(cin, _, _, _, _, methodRef, _)) if in <:< cin => q"$methodRef($x)"
          case matching =>
            if (matching.map(_.in).length > matching.map(_.in).distinct.length) {
              val (ambin, ambout) = matching.groupBy(_.in).toList.sortBy(_._1.toString).filter(_._2.length > 1).head
              fail(s"$ambin <:< $in is ambiguous between ${ambout.map(_.out).map(tpe => cleanLub(List(tpe)))}")
            } else {
              val cases = matching.map(c => cq"in: ${c.in} => ${c.methodRef}(in)")
              q"""
                $x match {
                  case ..$cases
                  case in => throw new _root_.scala.meta.ConvertException(
                    culprit = in,
                    message = "error converting from " + ${in.toString} + " to " + ${out.toString} + ": " +
                    "expected input of type " + ${matching.map(_.in).toString} + ", got input of " + in.getClass.toString + ": " + in)
                }
              """
            }
        }
        val downcastees = matching.filter(c => !(c.out <:< out))
        if (downcastees.nonEmpty && !allowOutputDowncasts) {
          val problems = downcastees.flatMap(c => extractIntersections(c.out).filter(cout => !(cout <:< out)).map(cout => (c.in, cout)))
          if (problems.isEmpty) fail("an unknown problem")
          else {
            def printProblem(p: (Type, Type)) = s"${p._1} => ${p._2}"
            val s_problems = problems.init.map(printProblem).mkString(", ") + (if (problems.length > 1) " and " else "") + printProblem(problems.last)
            val s_verb = if (problems.length == 1) "doesn't" else "don't"
            fail(s"$s_problems $s_verb fit the output type")
          }
        } else {
          if (downcastees.nonEmpty) result = q"""
            $result match {
              case out: $out => out
              case out => throw new _root_.scala.meta.ConvertException(
                culprit = $x,
                message = "error converting from " + ${in.toString} + " to " + ${out.toString} + ": " +
                "expected output of type " + ${out.toString} + ", got output of " + out.getClass.toString + ": " + out)
            }
          """
          atPos(x.pos)(result)
        }
      }
    }
    def lookupConvertersWithoutPt[In: c.WeakTypeTag](x: c.Tree): c.Tree = {
      lookupConvertersWithPt(x, EmptyTree)(c.weakTypeTag[In], c.WeakTypeTag(WildcardType))
    }
    def lookupConvertersWithPt[In: c.WeakTypeTag, Pt: c.WeakTypeTag](x: c.Tree, pt: c.Tree): c.Tree = {
      val target = c.macroApplication.symbol.owner
      target.name.toString match {
        case "toMtree" =>
          val pre @ q"$h.toMtree" = c.prefix.tree
          val sym = c.macroApplication.symbol
          val ensugared = q"""
            val ensugared = (new { val global: $h.g.type = $h.g } with $ToolkitTrait).ensugar($x)
            if (System.getProperty("ensugar.debug") != null) { _root_.scala.Console.err.println(ensugared); _root_.scala.Console.err.println($h.g.showRaw(ensugared, printIds = true, printTypes = true)) }
            ensugared
          """
          val result = convert(ensugared, c.typecheck(ensugared).tpe, c.weakTypeOf[Pt], allowDerived = true, allowInputDowncasts = true, allowOutputDowncasts = true, pre = pre.tpe, sym = sym)
          q"$h.cacheAllMembers($result)"
        case _ =>
          c.abort(c.enclosingPosition, "unknown target: " + target.name)
      }
    }
    def lubConverters[T: WeakTypeTag, U: WeakTypeTag]: Tree = {
      val converters = loadConverters(NoType, enclosingOwner)
      val in = weakTypeOf[T]
      val pt = weakTypeOf[U]
      val out = {
        if (converters.isEmpty) pt
        else {
          val matching = converters.filter(c => (c.in =:= in) && (c.out <:< pt))
          matching match {
            case List(success) => cleanLub(List(success.out))
            case _ => c.abort(c.enclosingPosition, s"something went wrong: can't load converter signature for $in => $pt")
          }
        }
      }
      q"new { type In = $in; type Out = $out }"
    }
    def connectConverters(x: Tree): Tree = {
      val converters = loadConverters(NoType, enclosingOwner)
      if (converters.isEmpty) {
        x
      } else if (x.exists(_.symbol == Auto_derive)) {
        val q"{ ..$_; in match { case ..$clauses } }" = x
        if (clauses.length != 1) c.abort(c.enclosingPosition, "can't derive a converter encompassing multiple clauses")
        val in = atPos(x.pos)(c.typecheck(Ident(TermName("in"))))
        convert(in, in.tpe, WildcardType, allowDerived = false, allowInputDowncasts = true, allowOutputDowncasts = true, pre = NoType, sym = enclosingOwner)
      } else {
        object transformer {
          var pt: Type = WildcardType
          def refinePt(upperbound: Type): Unit = {
            if (pt != WildcardType) pt = cleanLub(List(pt, upperbound))
            else if (upperbound != NothingTpe) pt = upperbound
            else pt = WildcardType
          }
          def transform(tree: Tree): Tree = typingTransform(tree)((tree, api) => {
            def connect(convertee: Tree, force: Boolean): Tree = {
              // println(convertee.tpe.widen + " -> " + pt + (if (force) " !!!" else ""))
              // println(convert(convertee, convertee.tpe.widen, pt, allowDerived = true, allowInputDowncasts = force, allowOutputDowncasts = force, pre = NoType, sym = enclosingOwner))
              // gen.mkAttributedRef(Predef_???).setType(NothingTpe)
              api.typecheck(convert(convertee, convertee.tpe.widen, pt, allowDerived = true, allowInputDowncasts = force, allowOutputDowncasts = force, pre = NoType, sym = enclosingOwner))
            }
            def transformApplyRememberingPts(app: Apply): Apply = {
              treeCopy.Apply(app, api.recur(app.fun), app.args.zipWithIndex.map({ case (arg, i) =>
                val params = app.fun.tpe.paramLists.head
                def isVararg = i >= params.length - 1 && params.last.info.typeSymbol == RepeatedParamClass
                def unVararg(tpe: Type) = tpe.typeArgs.head
                pt = if (isVararg) unVararg(params.last.info) else params(i).info
                val result = api.recur(arg)
                pt = WildcardType
                result
              }))
            }
            // TODO: think whether we can rebind polymorphic usages of cvt in a generic fashion
            // without hardcoding to every type constructor we're going to use
            tree match {
              case q"$_.List.apply[${wrappingPt: Type}](${Cvt(convertee, cvtPt, force)})" =>
                pt = if (pt != WildcardType) pt.typeArgs.head else pt
                refinePt(wrappingPt)
                refinePt(cvtPt)
                mkAttributedApply(List_apply, connect(convertee, force))
              case q"$_.Some.apply[${wrappingPt: Type}](${Cvt(convertee, cvtPt, force)})" =>
                pt = if (pt != WildcardType) pt.typeArgs.head else pt
                refinePt(wrappingPt)
                refinePt(cvtPt)
                mkAttributedApply(Some_apply, connect(convertee, force))
              case Cvt(convertee, cvtPt, force) =>
                refinePt(cvtPt)
                connect(convertee, force)
              case app: Apply =>
                transformApplyRememberingPts(app)
              case _ =>
                pt = WildcardType
                api.default(tree)
            }
          })
        }
        def shouldCleanRet(x: Tree): Boolean = x match {
          case Block(_, expr) => shouldCleanRet(expr)
          case _ => x.symbol != Any_asInstanceOf
        }
        val ret = if (shouldCleanRet(x)) cleanLub(List(x.tpe)) else x.tpe
        transformer.transform(x).setType(ret)
      }
    }
    object Scope {
      def unapplySeq(scope: Scope): Some[Seq[Symbol]] = Some(scope.toList)
    }
    object ThisType {
      def unapply(sym: Symbol): Option[(Symbol, Type, Type)] = {
        if (sym.name == TypeName("ThisType")) {
          sym.info match {
            case TypeBounds(lo, hi) => Some((sym, lo, hi))
            case _ => None
          }
        } else {
          None
        }
      }
    }
    // NOTE: Previously, when @ast classes were really classes, we were using the standard `lub` function.
    //
    // What was useful about it is that it allowed us to emulate union types.
    // For example, for List(Defn.Class, Defn.Trait) it would return:
    // Member.Type with Defn{type ThisType >: Defn.Trait with Defn.Class <: Member.Type with Defn}.
    // Now, when @ast classes are publicly represented as traits, lub is less useful:
    // Member.Type with Defn{type ThisType <: Member.Type with Defn}.
    //
    // Therefore, what we will do here is produce the lub as it could be seen before,
    // restoring the balance in the universe.
    //
    // Annoyingly enough, in the new scheme of things, sometimes we don't even get refined types
    // e.g. for List(Decl.Var, Decl.Var, Defn.Val, Defn.Var), we simply get Stat, which is totally unacceptable.
    // Allright, time to build the refined type manually.
    def preciseLub(tpes0: List[Type]): Type = {
      val tpes = {
        val tpes1 = tpes0.filter(tpe => !(tpe =:= NothingTpe))
        tpes1.flatMap({
          case RefinedType(_, Scope(ThisType(_, RefinedType(tpes1, _), _))) => tpes1
          case tpe1 => List(tpe1)
        })
      }
      val crudeLub = lub(tpes)
      if (tpes.length > 1 && tpes.forall(_ <:< TreeTpe)) {
        val parents = crudeLub match { case RefinedType(parents, _) => parents; case tpe => List(tpe) }
        val thisOwner = {
          val poweru = u.asInstanceOf[scala.reflect.internal.SymbolTable]
          val result = c.internal.enclosingOwner.asInstanceOf[poweru.Symbol].newRefinementClass(poweru.NoPosition)
          result.asInstanceOf[u.Symbol]
        }
        val thisScope = {
          val thisType = thisOwner.newTypeSymbol(TypeName("ThisType"), flags = DEFERRED)
          thisType.setInfo(typeBounds(intersectionType(tpes), intersectionType(parents)))
          newScopeWith(thisType)
        }
        val preciseLub = refinedType(parents, thisScope)
        thisOwner.setInfo(preciseLub)
        preciseLub
      } else {
        crudeLub
      }
    }
    // NOTE: postprocessing is not mandatory, but it's sort of necessary to simplify types
    // due to the fact that we have `type ThisType` in every root, branch and leaf node
    // lubs of node types can have really baroque ThisType refinements :)
    def cleanLub(tpes: List[Type]): Type = {
      lub(tpes).dealias match {
        case RefinedType(List(underlying), Scope(ThisType(_, _, _))) => underlying
        case RefinedType(parents, Scope(ThisType(_, _, _))) => cleanLub(parents)
        case lub => lub
      }
    }
    def extractIntersections(tpe: Type): List[Type] = {
      tpe.dealias match {
        case RefinedType(_, Scope(ThisType(sym, NothingTpe, _))) => unreachable
        case RefinedType(_, Scope(ThisType(sym, RefinedType(parents, Scope()), _))) => parents
        case RefinedType(_, Scope(ThisType(sym, parent, _))) => List(parent)
        case tpe => List(tpe)
      }
    }
    def mkAttributedApply(m: MethodSymbol, arg: Tree): Apply = {
      val owner = if (m.owner.isModuleClass) m.owner.asClass.module else m.owner
      val pre = gen.mkAttributedRef(owner)
      val polysel = gen.mkAttributedSelect(pre, m)
      val sel = TypeApply(polysel, List(TypeTree(arg.tpe))).setType(appliedType(polysel.tpe, arg.tpe))
      Apply(sel, List(arg)).setType(sel.tpe.finalResultType)
    }
    def customPrologue[T](x: Tree)(implicit T: c.WeakTypeTag[T]): Tree = {
      // do nothing, there hasn't been need for a custom prologue yet
      x
    }
    def customEpilogue[T](x: Tree)(implicit T: c.WeakTypeTag[T]): Tree = {
      val TermTpe = c.mirror.staticClass("scala.meta.internal.ast.Term").asClass.toType
      val TermParamTpe = c.mirror.staticModule("scala.meta.internal.ast.Term").info.member(TypeName("Param")).asClass.toType
      if (T.tpe <:< TermTpe) q"$x.withTpe(in.tpe)"
      else if (T.tpe <:< TermParamTpe) q"$x.withTpe(in.tpe)"
      else x
    }
  }
}
