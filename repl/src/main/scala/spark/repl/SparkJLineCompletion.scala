/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author Paul Phillips
 */

package spark.repl

import scala.tools.nsc._
import scala.tools.nsc.interpreter._

import scala.tools.jline._
import scala.tools.jline.console.completer._
import Completion._
import collection.mutable.ListBuffer

// REPL completor - queries supplied interpreter for valid
// completions based on current contents of buffer.
class SparkJLineCompletion(val intp: SparkIMain) extends Completion with CompletionOutput {
  val global: intp.global.type = intp.global
  import global._
  import definitions.{ PredefModule, RootClass, AnyClass, AnyRefClass, ScalaPackage, JavaLangPackage }
  type ExecResult = Any
  import intp.{ DBG, debugging, afterTyper }
  
  // verbosity goes up with consecutive tabs
  private var verbosity: Int = 0
  def resetVerbosity() = verbosity = 0
    
  def getType(name: String, isModule: Boolean) = {
    val f = if (isModule) definitions.getModule(_: Name) else definitions.getClass(_: Name)
    try Some(f(name).tpe)
    catch { case _: MissingRequirementError => None }
  }
  
  def typeOf(name: String) = getType(name, false)
  def moduleOf(name: String) = getType(name, true)
    
  trait CompilerCompletion {
    def tp: Type
    def effectiveTp = tp match {
      case MethodType(Nil, resType)   => resType
      case NullaryMethodType(resType) => resType
      case _                          => tp
    }

    // for some reason any's members don't show up in subclasses, which
    // we need so 5.<tab> offers asInstanceOf etc.
    private def anyMembers = AnyClass.tpe.nonPrivateMembers
    def anyRefMethodsToShow = Set("isInstanceOf", "asInstanceOf", "toString")

    def tos(sym: Symbol) = sym.name.decode.toString
    def memberNamed(s: String) = members find (x => tos(x) == s)
    def hasMethod(s: String) = methods exists (x => tos(x) == s)

    // XXX we'd like to say "filterNot (_.isDeprecated)" but this causes the
    // compiler to crash for reasons not yet known.
    def members     = afterTyper((effectiveTp.nonPrivateMembers ++ anyMembers) filter (_.isPublic))
    def methods     = members filter (_.isMethod)
    def packages    = members filter (_.isPackage)
    def aliases     = members filter (_.isAliasType)

    def memberNames   = members map tos
    def methodNames   = methods map tos
    def packageNames  = packages map tos
    def aliasNames    = aliases map tos
  }

  object TypeMemberCompletion {
    def apply(tp: Type, runtimeType: Type, param: NamedParam): TypeMemberCompletion = {
      new TypeMemberCompletion(tp) {
        var upgraded = false
        lazy val upgrade = {
          intp rebind param          
          intp.reporter.printMessage("\nRebinding stable value %s from %s to %s".format(param.name, tp, param.tpe))
          upgraded = true
          new TypeMemberCompletion(runtimeType)
        }
        override def completions(verbosity: Int) = {
          super.completions(verbosity) ++ (
            if (verbosity == 0) Nil
            else upgrade.completions(verbosity)
          )
        }
        override def follow(s: String) = super.follow(s) orElse {
          if (upgraded) upgrade.follow(s)
          else None
        }
        override def alternativesFor(id: String) = super.alternativesFor(id) ++ (
          if (upgraded) upgrade.alternativesFor(id)
          else Nil
        ) distinct
      }
    }
    def apply(tp: Type): TypeMemberCompletion = {
      if (tp.typeSymbol.isPackageClass) new PackageCompletion(tp)
      else new TypeMemberCompletion(tp)
    }
    def imported(tp: Type) = new ImportCompletion(tp)
  }

  class TypeMemberCompletion(val tp: Type) extends CompletionAware
                                              with CompilerCompletion {
    def excludeEndsWith: List[String] = Nil
    def excludeStartsWith: List[String] = List("<") // <byname>, <repeated>, etc.
    def excludeNames: List[String] = (anyref.methodNames filterNot anyRefMethodsToShow) :+ "_root_"
    
    def methodSignatureString(sym: Symbol) = {
      SparkIMain stripString afterTyper(new MethodSymbolOutput(sym).methodString())
    }

    def exclude(name: String): Boolean = (
      (name contains "$") ||
      (excludeNames contains name) ||
      (excludeEndsWith exists (name endsWith _)) ||
      (excludeStartsWith exists (name startsWith _))
    )
    def filtered(xs: List[String]) = xs filterNot exclude distinct

    def completions(verbosity: Int) =
      debugging(tp + " completions ==> ")(filtered(memberNames))
    
    override def follow(s: String): Option[CompletionAware] =
      debugging(tp + " -> '" + s + "' ==> ")(memberNamed(s) map (x => TypeMemberCompletion(x.tpe)))      
    
    override def alternativesFor(id: String): List[String] =
      debugging(id + " alternatives ==> ") {
        val alts = members filter (x => x.isMethod && tos(x) == id) map methodSignatureString

        if (alts.nonEmpty) "" :: alts else Nil
      }

    override def toString = "%s (%d members)".format(tp, members.size)
  }
  
  class PackageCompletion(tp: Type) extends TypeMemberCompletion(tp) {
    override def excludeNames = anyref.methodNames
  }

  class LiteralCompletion(lit: Literal) extends TypeMemberCompletion(lit.value.tpe) {
    override def completions(verbosity: Int) = verbosity match {
      case 0    => filtered(memberNames)
      case _    => memberNames
    }
  }
  
  class ImportCompletion(tp: Type) extends TypeMemberCompletion(tp) {
    override def completions(verbosity: Int) = verbosity match {
      case 0    => filtered(members filterNot (_.isSetter) map tos)
      case _    => super.completions(verbosity)
    }
  }
  
  // not for completion but for excluding
  object anyref extends TypeMemberCompletion(AnyRefClass.tpe) { }
  
  // the unqualified vals/defs/etc visible in the repl
  object ids extends CompletionAware {
    override def completions(verbosity: Int) = intp.unqualifiedIds ++ List("classOf") //, "_root_")
    // now we use the compiler for everything.
    override def follow(id: String) = {
      if (completions(0) contains id) {
        intp typeOfExpression id map { tpe =>
          def default = TypeMemberCompletion(tpe)

          // only rebinding vals in power mode for now.
          if (!isReplPower) default
          else intp runtimeClassAndTypeOfTerm id match {
            case Some((clazz, runtimeType)) =>
              val sym = intp.symbolOfTerm(id)
              if (sym.isStable) {
                val param = new NamedParam.Untyped(id, intp valueOfTerm id getOrElse null)
                TypeMemberCompletion(tpe, runtimeType, param)
              }
              else default
            case _        =>
              default
          }
        }
      }
      else
        None
    }
    override def toString = "<repl ids> (%s)".format(completions(0).size)
  }

  // user-issued wildcard imports like "import global._" or "import String._"
  private def imported = intp.sessionWildcards map TypeMemberCompletion.imported

  // literal Ints, Strings, etc.
  object literals extends CompletionAware {    
    def simpleParse(code: String): Tree = {
      val unit    = new CompilationUnit(new util.BatchSourceFile("<console>", code))
      val scanner = new syntaxAnalyzer.UnitParser(unit)
      val tss     = scanner.templateStatSeq(false)._2

      if (tss.size == 1) tss.head else EmptyTree
    }
  
    def completions(verbosity: Int) = Nil
    
    override def follow(id: String) = simpleParse(id) match {
      case x: Literal   => Some(new LiteralCompletion(x))
      case _            => None
    }
  }

  // top level packages
  object rootClass extends TypeMemberCompletion(RootClass.tpe) {
    override def completions(verbosity: Int) = super.completions(verbosity) :+ "_root_"
    override def follow(id: String) = id match {
      case "_root_" => Some(this)
      case _        => super.follow(id)
    }    
  }
  // members of Predef
  object predef extends TypeMemberCompletion(PredefModule.tpe) {
    override def excludeEndsWith    = super.excludeEndsWith ++ List("Wrapper", "ArrayOps")
    override def excludeStartsWith  = super.excludeStartsWith ++ List("wrap")
    override def excludeNames       = anyref.methodNames
    
    override def exclude(name: String) = super.exclude(name) || (
      (name contains "2")
    )
    
    override def completions(verbosity: Int) = verbosity match {
      case 0    => Nil
      case _    => super.completions(verbosity)
    }
  }
  // members of scala.*
  object scalalang extends PackageCompletion(ScalaPackage.tpe) {
    def arityClasses = List("Product", "Tuple", "Function")
    def skipArity(name: String) = arityClasses exists (x => name != x && (name startsWith x))
    override def exclude(name: String) = super.exclude(name) || (
      skipArity(name)
    )
    
    override def completions(verbosity: Int) = verbosity match {
      case 0    => filtered(packageNames ++ aliasNames)
      case _    => super.completions(verbosity)
    }
  }
  // members of java.lang.*
  object javalang extends PackageCompletion(JavaLangPackage.tpe) {
    override lazy val excludeEndsWith   = super.excludeEndsWith ++ List("Exception", "Error")
    override lazy val excludeStartsWith = super.excludeStartsWith ++ List("CharacterData")
    
    override def completions(verbosity: Int) = verbosity match {
      case 0    => filtered(packageNames)
      case _    => super.completions(verbosity)
    }
  }

  // the list of completion aware objects which should be consulted
  // for top level unqualified, it's too noisy to let much in.
  lazy val topLevelBase: List[CompletionAware] = List(ids, rootClass, predef, scalalang, javalang, literals)
  def topLevel = topLevelBase ++ imported
  def topLevelThreshold = 50
  
  // the first tier of top level objects (doesn't include file completion)
  def topLevelFor(parsed: Parsed): List[String] = {
    val buf = new ListBuffer[String]
    topLevel foreach { ca =>
      buf ++= (ca completionsFor parsed)

      if (buf.size > topLevelThreshold)
        return buf.toList.sorted
    }
    buf.toList
  }

  // the most recent result
  def lastResult = Forwarder(() => ids follow intp.mostRecentVar)

  def lastResultFor(parsed: Parsed) = {
    /** The logic is a little tortured right now because normally '.' is
     *  ignored as a delimiter, but on .<tab> it needs to be propagated.
     */
    val xs = lastResult completionsFor parsed
    if (parsed.isEmpty) xs map ("." + _) else xs
  }

  // chasing down results which won't parse
  def execute(line: String): Option[ExecResult] = {
    val parsed = Parsed(line)
    def noDotOrSlash = line forall (ch => ch != '.' && ch != '/')
    
    if (noDotOrSlash) None  // we defer all unqualified ids to the repl.
    else {
      (ids executionFor parsed) orElse
      (rootClass executionFor parsed) orElse
      (FileCompletion executionFor line)
    }
  }
  
  // generic interface for querying (e.g. interpreter loop, testing)
  def completions(buf: String): List[String] =
    topLevelFor(Parsed.dotted(buf + ".", buf.length + 1))

  def completer(): ScalaCompleter = new JLineTabCompletion

  /** This gets a little bit hairy.  It's no small feat delegating everything
   *  and also keeping track of exactly where the cursor is and where it's supposed
   *  to end up.  The alternatives mechanism is a little hacky: if there is an empty
   *  string in the list of completions, that means we are expanding a unique
   *  completion, so don't update the "last" buffer because it'll be wrong.
   */
  class JLineTabCompletion extends ScalaCompleter {
    // For recording the buffer on the last tab hit
    private var lastBuf: String = ""
    private var lastCursor: Int = -1

    // Does this represent two consecutive tabs?
    def isConsecutiveTabs(buf: String, cursor: Int) =
      cursor == lastCursor && buf == lastBuf

    // Longest common prefix
    def commonPrefix(xs: List[String]): String = {
      if (xs.isEmpty || xs.contains("")) ""
      else xs.head.head match {
        case ch =>
          if (xs.tail forall (_.head == ch)) "" + ch + commonPrefix(xs map (_.tail))
          else ""
      }
    }

    // This is jline's entry point for completion.
    override def complete(buf: String, cursor: Int): Candidates = {
      verbosity = if (isConsecutiveTabs(buf, cursor)) verbosity + 1 else 0
      DBG("\ncomplete(%s, %d) last = (%s, %d), verbosity: %s".format(buf, cursor, lastBuf, lastCursor, verbosity))

      // we don't try lower priority completions unless higher ones return no results.
      def tryCompletion(p: Parsed, completionFunction: Parsed => List[String]): Option[Candidates] = {
        val winners = completionFunction(p) 
        if (winners.isEmpty)
          return None
        val newCursor =
          if (winners contains "") p.cursor
          else {
            val advance = commonPrefix(winners)
            lastCursor = p.position + advance.length
            lastBuf = (buf take p.position) + advance
            DBG("tryCompletion(%s, _) lastBuf = %s, lastCursor = %s, p.position = %s".format(
              p, lastBuf, lastCursor, p.position))
            p.position
          }
        
        Some(Candidates(newCursor, winners))
      }
      
      def mkDotted      = Parsed.dotted(buf, cursor) withVerbosity verbosity
      def mkUndelimited = Parsed.undelimited(buf, cursor) withVerbosity verbosity

      // a single dot is special cased to completion on the previous result
      def lastResultCompletion =
        if (!looksLikeInvocation(buf)) None            
        else tryCompletion(Parsed.dotted(buf drop 1, cursor), lastResultFor)

      def regularCompletion = tryCompletion(mkDotted, topLevelFor)
      def fileCompletion    = 
        if (!looksLikePath(buf)) None
        else tryCompletion(mkUndelimited, FileCompletion completionsFor _.buffer)
      
      /** This is the kickoff point for all manner of theoretically possible compiler
       *  unhappiness - fault may be here or elsewhere, but we don't want to crash the
       *  repl regardless.  Hopefully catching Exception is enough, but because the
       *  compiler still throws some Errors it may not be.
       */
      try {
        (lastResultCompletion orElse regularCompletion orElse fileCompletion) getOrElse Candidates(cursor, Nil)
      }
      catch {
        case ex: Exception =>
          DBG("Error: complete(%s, %s) provoked %s".format(buf, cursor, ex))
          Candidates(cursor, List(" ", "<completion error: " + ex.getMessage +  ">"))
      }
    }
  }
}
