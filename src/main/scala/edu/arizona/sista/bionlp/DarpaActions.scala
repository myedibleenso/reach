package edu.arizona.sista.bionlp

import edu.arizona.sista.struct.{Interval, DirectedGraph}
import edu.arizona.sista.odin._
import edu.arizona.sista.bionlp.mentions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class DarpaActions extends Actions {

  def splitSimpleEvents(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m matches "SimpleEvent" =>
      // Do we have a regulation?
      if (m.arguments.keySet contains "cause") {
        // FIXME There could be more than one cause...
        val cause: Seq[Mention] = m.arguments("cause")
        val evArgs = m.arguments - "cause"
        val ev = new BioEventMention(
          m.labels, m.trigger, evArgs, m.sentence, m.document, m.keep, m.foundBy)
        // make sure the regulation is valid
        val controlledArgs:Set[Mention] = evArgs.values.flatten.toSet
        cause match {
          // controller of an event should not be an arg in the controlled
          case reg if cause.forall(c => !controlledArgs.contains(c)) => {
            val regArgs = Map("controlled" -> Seq(ev), "controller" -> cause)
            val reg = new BioRelationMention(
              Seq("Positive_regulation", "ComplexEvent", "Event"),
              regArgs, m.sentence, m.document, m.keep, m.foundBy)
            val (negMods, otherMods) = m.toBioMention.modifications.partition(_.isInstanceOf[Negation])
            reg.modifications = negMods
            ev.modifications = otherMods
            Seq(reg, ev)
          }
          case _ => Nil
        }
      } else Seq(m.toBioMention)
    case m => Seq(m.toBioMention)
  }

  def mkEntities(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case rel: RelationMention => {
      for {(k, v) <- rel.arguments
           m <- v} yield {
        new TextBoundMention(rel.labels, m.tokenInterval, rel.sentence, rel.document, rel.keep, rel.foundBy).toBioMention
      }
    }
      case other => {
        Nil
    }
  }
  // FIXME it would be better to define the action explicitly in the rule
  // instead of replacing the default action
  //override val default: Action = splitSimpleEvents
  override val default: Action = (ms: Seq[Mention], s: State) => ms.map(_.toBioMention)


  /**
  def mkUnresolvedTBMention(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val defaultLoseRels = Seq("NN", "NNS", "NNP", "NNPS")
    val defaultKeepRels = Seq("DT")

    def throwAway(m: Mention, loseRelations: Seq[String] = defaultLoseRels, keepRelations: Seq[String] = defaultKeepRels): Boolean = {
      val dep = m.document.sentences(m.sentence).dependencies
      val edges = if (dep.isDefined) dep.get.getOutgoingEdges(m.tokenInterval.end - 1) else Array()
      edges.exists(edge => (loseRelations.contains(edge._2) &&
        !state.mentionsFor(m.sentence,edge._1).filter(_.isInstanceOf[TextBoundMention]).isEmpty) ||
        state.mentionsFor(m.sentence,edge._1).exists(throwAway(_))) ||
      edges.forall(edge => !keepRelations.contains(edge))
    }

    mentions map {
      case tbm: TextBoundMention =>
        if (!throwAway(tbm)) Seq(tbm) else Seq()
      case m => Seq(m)
    } flatten
  }
    */


  /** This action is meant for Regulation rules written for
    * explicitly negated triggers
    */
  def mkNegatedRegulation(mentions: Seq[Mention], state: State): Seq[Mention] =
    mentions map {
      case event: EventMention =>
        val reg = event.toBioMention
        val neg = new BioTextBoundMention(
          Seq("NegatedTrigger"),
          event.trigger.tokenInterval,
          event.sentence,
          event.document,
          event.keep,
          event.foundBy)
        reg.modifications += Negation(neg)
        reg
      case m => m
    }

  /** This action handles the creation of mentions from labels generated by the NER system.
    * Rules that use this action should run in an iteration following and rules recognizing
    * "custom" entities. This action will only create mentions if no other mentions overlap
    * with a NER label sequence.
    */
  def mkNERMentions(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { m =>
      val candidates = state.mentionsFor(m.sentence, m.tokenInterval.toSeq)
      // do any candidates intersect the mention?
      val overlap = candidates.exists(_.tokenInterval.overlaps(m.tokenInterval))
      if (overlap) None else Some(m.toBioMention)
    }
  }

  /** This action handles the creation of ubiquitination EventMentions.
    * A Ubiquitination event cannot involve arguments (theme/cause) with the text Ubiquitin.
    */
  def mkUbiquitination(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val filteredMentions = mentions.filter { m =>
      // Don't allow Ubiquitin
      !m.arguments.values.flatten.exists(_.text.toLowerCase.startsWith("ubiq"))
    }
    val bioMentions = filteredMentions.map(_.toBioMention)
    // TODO: a temporary hack to convert theme+cause ubiqs => regs
    //splitSimpleEvents(bioMentions, state)
    bioMentions
  }

  /** This action handles the creation of Binding EventMentions. In many cases, sentences about binding
    * will contain two sets of entities. These sets should be combined exhaustively in a pairwise fashion,
    * but no bindings should be created for pairs of entities within each set.
    * Theme1(A,B),Theme2(C,D) => Theme(A,C),Theme(A,D),Theme(B,C),Theme(B,D)
    */
  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m.labels.contains("Binding") =>
      val theme1s = m.arguments.getOrElse("theme1",Seq())
      val theme2s = m.arguments.getOrElse("theme2",Seq())

      (theme1s, theme2s) match {
        case (t1s, t2s) if (t1s ++ t2s).size < 2  && !(t1s ++ t2s).exists(x => x.labels contains "Unresolved") => Nil
        case (t1s, t2s) if (t1s ++ t2s).exists(x => x.labels contains "Unresolved") =>  // wait until coref resolution
          Seq(new BioEventMention(m.labels,m.trigger,m.arguments,m.sentence,m.document,m.keep,m.foundBy))
        case (t1s, t2s) if t1s.size == 0 || t2s.size == 0 =>
          val mergedThemes = t1s ++ t2s

          // if one theme is Ubiquitin, this is a ubiquitination event
          if (mergedThemes.size == 2 && !sameEntityID(mergedThemes) && mergedThemes.exists(_.text.toLowerCase == "ubiquitin")) {
            val args = Map("theme" -> mergedThemes.filter(_.text.toLowerCase != "ubiquitin"))
            Seq(new BioEventMention(
              "Ubiquitination" +: m.labels.filter(_ != "Binding"),m.trigger,args,m.sentence,m.document,m.keep,m.foundBy))
          }
          else {
            // binarize bindings
            // return bindings with pairs of themes
            for (pair <- mergedThemes.combinations(2)
                 //if themes are not the same entity
                 if !sameEntityID(pair)) yield {
              val theme1 = pair.head
              val theme2 = pair.last

              if (theme1.text.toLowerCase == "ubiquitin") {
                val args = Map("theme" -> Seq(theme2))
                new BioEventMention(
                  "Ubiquitination" +: m.labels.filter(_ != "Binding"), m.trigger, args, m.sentence, m.document, m.keep, m.foundBy)
              } else if (theme2.text.toLowerCase == "ubiquitin") {
                val args = Map("theme" -> Seq(theme1))
                new BioEventMention(
                  "Ubiquitination" +: m.labels.filter(_ != "Binding"), m.trigger, args, m.sentence, m.document, m.keep, m.foundBy)
              }
              else {
                val args = Map("theme" -> Seq(theme1, theme2))
                new BioEventMention(m.labels, m.trigger, args, m.sentence, m.document, m.keep, m.foundBy)
              }
            }
          }

        case _ =>
          for {
            theme1 <- theme1s
            theme2 <- theme2s
            if !sameEntityID(Seq(theme1, theme2))
          } yield {
            if (theme1.text.toLowerCase == "ubiquitin"){
              val args = Map("theme" -> Seq(theme2))
              new BioEventMention(
                "Ubiquitination" +: m.labels.filter(_ != "Binding"),m.trigger,args,m.sentence,m.document,m.keep,m.foundBy)
            } else if (theme2.text.toLowerCase == "ubiquitin") {
              val args = Map("theme" -> Seq(theme1))
              new BioEventMention(
                "Ubiquitination" +: m.labels.filter(_ != "Binding"),m.trigger,args,m.sentence,m.document,m.keep,m.foundBy)
            }
            else {
              val args = Map("theme" -> Seq(theme1, theme2))
              new BioEventMention(m.labels, m.trigger, args, m.sentence, m.document, m.keep, m.foundBy)
            }
          }
      }
  }

  /**
   * Do we have exactly 1 unique grounding id for this Sequence of Mentions?
   * @param mentions A Sequence of odin-style mentions
   * @return boolean
   */
  def sameEntityID(mentions:Seq[Mention]): Boolean = {
    val groundings =
      mentions
      .map(_.toBioMention)
      // only look at grounded Mentions
      .filter(_.xref.isDefined)
      .map(_.xref.get)
      .toSet
    // should be 1 if all are the same entity
    groundings.size == 1
  }

  // retrieve the appropriate modification label
  def getModification(text: String): String = text.toLowerCase match {
    case acet if acet contains "acetylat" => "acetylated"
    case farne if farne contains "farnesylat" => "farnesylated"
    case glyco if glyco contains "glycosylat" =>"glycosylated"
    case hydrox if hydrox contains "hydroxylat" =>"hydroxylated"
    case meth if meth contains "methylat" => "methylated"
    case phos if phos contains "phosphorylat" => "phosphorylated"
    case ribo if ribo contains "ribosylat" => "ribosylated"
    case sumo if sumo contains "sumoylat" =>"sumoylated"
    case ubiq if ubiq contains "ubiquitinat" => "ubiquitinated"
    case _ => "UNKNOWN"
  }

  /**
   * This action decomposes RelationMentions with the label Modification to the matched TB entity with the appropriate Modification
   * @return Nil (Modifications are added in-place)
   */
  def mkModification(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap {
      case ptm: RelationMention if ptm.label == "PTM" => {
        //println("found a modification...")
        val trigger = ptm.arguments("mod").head
        // If this creates a new mention, we have a bug because it won't end up in the State
        val bioMention = ptm.arguments("entity").head.toBioMention
        val site = if (ptm.arguments.keySet.contains("site")) Some(ptm.arguments("site").head) else None
        // This is the TextBoundMention for the ModifcationTrigger
        val evidence = ptm.arguments("mod").head
        val label = getModification(evidence.text)
        // If we have a label, add the modification in-place
        if (label != "UNKNOWN") bioMention.modifications += PTM(label, Some(evidence), site)
        Nil // don't return anything; this mention is already in the State
      }
    }
  }

  /**
   * Sometimes it's easiest to find the site associated with a BioChemicalEntity before event detection
   * @return Nil (Modifications are added in-place)
   */
  def storeEventSite(mentions: Seq[Mention], state: State): Seq[Mention] = {
    //println(s"${mentions.size} EventSite mentions found")
    mentions foreach { m =>
      //println(s"\t${m.arguments("entity").size} entities found by ${m.foundBy}: ${m.text}")
      val bioMention = m.arguments("entity").head.toBioMention
      // Check the complete span for any sites
      // FIXME this is due to an odin bug
      state.mentionsFor(m.sentence, m.tokenInterval.toSeq, "Site") foreach { eSite =>
        //println(s"\tEventSite Modification detected: site is ${eSite.text} for ${bioMention.text}")
        bioMention.modifications += EventSite(site = eSite)
      }
    }
    Nil
  }

  /**
   * Propagate any Sites in the Modifications of a a SimpleEvent's theme to the event arguments
   */
  def siteSniffer(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case simple: EventMention if simple.labels contains "SimpleEvent" => {
      val additionalSites: Seq[Mention] = simple.arguments.values.flatten.flatMap { case m:BioMention =>
        // get the sites from any EventSite Modifications
        val eventSites:Seq[EventSite] = m.modifications.toSeq flatMap {
          case es:EventSite => Some(es)
          case _ => None
        }
        // Remove EventSite modifications
        eventSites.foreach(es => m.modifications -= es)

        // Get additional sites
        eventSites.map{case es: EventSite => es.site}
      }.toSeq

      // Gather up our sites
      val allSites = additionalSites ++ simple.arguments.getOrElse("site", Nil)

      // Do we have any sites?
      if (allSites.isEmpty) Seq(simple)
      else {
        val allButSite = simple.arguments - "site"
        // Create a separate EventMention for each Site
        for (site <- allSites.distinct) yield {
          val updatedArgs = allButSite + ("site" -> Seq(site))
          // FIXME the interval might not be correct anymore...
          new EventMention(simple.labels,
            simple.trigger,
            updatedArgs,
            simple.sentence,
            simple.document,
            simple.keep,
            simple.foundBy).toBioMention
        }
      }
    }
    // If it isn't a SimpleEvent, assume there is nothing more to do
    case m => Seq(m)
  }


  def mkRegulation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    mention <- mentions.sortWith { (m1, m2) =>
      // this is an ugly hack
      // it's purpose is finding regulations that have events as controllers
      // before regulations that have entities as controllers
      // so that, if two mentions overlap, we keep the one that comes
      // from the regulation with an event controller
      val c = m1.arguments.get("controller")
      if (c.isDefined && c.get.head.matches("Event")) true
      else false
    }
    biomention = removeDummy(switchLabel(mention.toBioMention))
  } yield {
    val controllerOption = biomention.arguments.get("controller")
    // if no controller then we are done
    if (controllerOption.isEmpty) biomention
    else {
      // assuming one controller only
      val controller = controllerOption.get.head
      // if controller is a physical entity then we are done
      if (controller matches "BioChemicalEntity") biomention
      else if (controller matches "SimpleEvent") {
        // convert controller event into modified physical entity
        val trigger = biomention.asInstanceOf[BioEventMention].trigger
        val newController = convertEventToEntity(controller.toBioMention.asInstanceOf[BioEventMention])
        // if for some reason the event couldn't be converted
        // just return the original mention
        if (newController.isEmpty) biomention
        else {
          // return a new event with the converted controller
          new BioEventMention(
            biomention.labels,
            trigger,
            biomention.arguments.updated("controller", Seq(newController.get)),
            biomention.sentence,
            biomention.document,
            biomention.keep,
            biomention.foundBy)
        }
      }
      // if it is not a biochemical entity or a simple event, what is it??
      // we will just return it
      else biomention
    }
  }

  /**
   * Only allow Activations if no overlapping Regulations exist for the interval
   */
  def mkActivation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    mention <- mentions
    biomention = removeDummy(switchLabel(mention.toBioMention))
    // TODO: Should we add a Regulation label to Pos and Neg Regs?
    regs = state.mentionsFor(biomention.sentence, biomention.tokenInterval.toSeq, "ComplexEvent")
    // Don't report an Activation if an intersecting Regulation has been detected
    if !regs.exists(_.tokenInterval.overlaps(biomention.tokenInterval))
  } yield biomention


  /** Removes the "dummy" argument, if present */
  def removeDummy(m:BioMention):BioMention = {
    m match {
      case em:BioEventMention => // we only need to do this for events
        if(em.arguments.contains("dummy")) {
          val filteredArguments = new mutable.HashMap[String, Seq[Mention]]()
          for(k <- em.arguments.keySet) {
            if(! k.startsWith("dummy")) {
              filteredArguments += k -> em.arguments.get(k).get
            }
          }
          new BioEventMention(
            em.labels,
            em.trigger,
            filteredArguments.toMap,
            em.sentence,
            em.document,
            em.keep,
            em.foundBy
          )
        } else {
          em
        }
      case _ => m
    }
  }


  /** Flips labels from positive to negative and viceversa, if an odd number of semantic negatives are found in the path */
  def switchLabel(m:BioMention):BioMention = {
    if(m.isInstanceOf[BioEventMention]) {
      val em = m.asInstanceOf[BioEventMention]
      val trigger = em.trigger
      // contains all tokens that should be excluded in the search for negatives
      val excluded = new mutable.HashSet[Int]()
      for(arg <- em.arguments.values.flatten) {
        for(i <- arg.tokenInterval) {
          excluded += i
        }
      }
      for(i <- trigger.tokenInterval) {
        excluded += i
      }
      // stores all the negative tokens found
      val negatives = new mutable.HashSet[Int]()
      for(arg <- em.arguments.values.flatten) {
        countSemanticNegatives(trigger, arg, excluded.toSet, negatives)
      }
      if(negatives.size % 2 != 0) {
        // println("Found an odd number of semantic negatives. We should flip " + em.label)
        val flippedLabels = new ListBuffer[String]
        flippedLabels += flipLabel(em.label)
        flippedLabels ++= em.labels.slice(1, em.labels.size)
        return new BioEventMention(
          flippedLabels,
          em.trigger,
          em.arguments,
          em.sentence,
          em.document,
          em.keep,
          em.foundBy)
      }
    }
    m
  }

  def flipLabel(l:String):String = {
    if(l.startsWith("Positive_"))
      "Negative_" + l.substring(9)
    else if(l.startsWith("Negative_"))
      "Positive_" + l.substring(9)
    else throw new RuntimeException("ERROR: Must have a polarized label here!")
  }


  /** Converts a simple event to a physical entity.
    *
    * @param event An event mention
    * @return a mention wrapped in an option
    */
  def convertEventToEntity(event: BioEventMention): Option[BioMention] = {
    if (!event.matches("SimpleEvent"))
      // we only handle simple events
      None
    else if (event matches "Binding") {
      // get the themes of the binding
      // and create a relationMention
      val complex = new BioRelationMention(
        Seq("Complex", "BioChemicalEntity"),
        event.arguments,
        event.sentence,
        event.document,
        event.keep,
        event.foundBy)
      // create a default displayLabel for the complex
      // complex.displayLabel = "[" + event.arguments("theme").map(_.text).mkString(":") + "]"
      complex.displayLabel = "[" +
        (event.arguments.getOrElse("theme",Seq()) ++
        event.arguments.getOrElse("theme1",Seq()) ++
        event.arguments.getOrElse("theme2",Seq()))
          .map(_.text).mkString(":") + "]"
      Some(complex)
    } else {
      // get the theme of the event
      // assume only one theme
      val entity = event.arguments("theme").head
      val site = event.arguments.get("site").map(_.head)
      // create new mention for the entity
      val modifiedEntity = new BioTextBoundMention(
        entity.labels,
        entity.tokenInterval,
        entity.sentence,
        entity.document,
        entity.keep,
        entity.foundBy)
      // add a modification based on the event trigger
      val label = getModification(event.trigger.text)
      modifiedEntity.modifications += PTM(label, evidence = Some(event.trigger), site = site)
      Some(modifiedEntity)
    }
  }

  def detectHypotheses(mentions: Seq[Mention], state:State): Seq[Mention] ={

    val degree = 2 // Degree up to which we should follow the links in the graph

    // These are the words that hint a hypothesis going on
    val hints = Set(
      "argue",
      "argument",
      "believe",
      "belief",
      "conjecture",
      "consider",
      "hint",
      "hypothesis",
      "hypotheses",
      "hypothesize",
      "implication",
      "imply",
      "indicate",
      "predict",
      "prediction",
      "previous",
      "previously",
      "proposal",
      "propose",
      "speculate",
      "suggest",
      "suspect",
      "theorize",
      "theory",
      "think")

    // Recursive function that helps us get the words outside the event
    def getSpannedIndexes(index:Int, degree:Int, dependencies:DirectedGraph[String]):Seq[Int] = {
      degree match {
        case 0 => Seq[Int]() // Base case of the recursion
        case _ =>

          val outgoing = dependencies.outgoingEdges
          val incoming = dependencies.incomingEdges

          // Get incoming and outgoing edges
          val t:Seq[(Int, String)] = incoming.lift(index)  match {
            case Some(x) => x
            case None => Seq()
          }

          val edges = t ++ (outgoing.lift(index) match {
            case Some(x) => x
            case None => Seq()
          })


          // Each edge is a tuple of (endpoint index, edge label), so we map it to the first
          // element of the tuple
          val indexes:Seq[Int] = edges map (_._1)

          // Recursively call this function to get outter degrees
          val higherOrderIndexes:Seq[Int] = indexes flatMap (getSpannedIndexes(_, degree - 1, dependencies))

          indexes ++ higherOrderIndexes
      }
    }

    mentions foreach {
      case event:BioEventMention =>

        // Get the dependencies of the sentence
        val dependencies = event.sentenceObj.dependencies.getOrElse(new DirectedGraph[String](Nil, Set[Int]()))

        val eventInterval:Seq[Int] = event.tokenInterval.toSeq

        // Get the index of the word outside the event up to "degree" degrees
        val spannedIndexes:Seq[Int] = eventInterval flatMap (getSpannedIndexes(_, degree, dependencies))

        // Remove duplicates
        val indexes:Seq[Int] = (eventInterval ++ spannedIndexes).distinct

        // Get the lemmas
        val lemmas = indexes map (event.sentenceObj.lemmas.get(_))

        // Perform assignments
        for {
          // Zip the lemma with its index, this is necessary to build the Modifictaion
          (lemma, ix) <- lemmas zip indexes
          // Only if the lemma is part of one of the hints
          if hints contains lemma
        }{
          event.modifications += Hypothesis(new BioTextBoundMention(
            Seq("Hypothesis_hint"),
            Interval(ix),
            sentence = event.sentence,
            document = event.document,
            keep = event.keep,
            foundBy = event.foundBy
          ))
        }

      case _ => ()
    }
    mentions
  }

  def detectNegations(mentions: Seq[Mention], state:State): Seq[Mention] = {
    // do something very smart to handle negated events
    // and then return the mentions

    // Iterate over the BioEventMentions
    mentions foreach {
        case event:BioEventMention =>

          val dependencies = event.sentenceObj.dependencies

          /////////////////////////////////////////////////
          // Check the outgoing edges from the trigger looking
          // for a neg label
          val outgoing = dependencies match {
            case Some(deps) => deps.outgoingEdges
            case None => Array.empty
          }

          for{
            tok <- event.tokenInterval.toSeq
            out <- outgoing.lift(tok)
            (ix, label) <- out
            if label == "neg"
          }
            event.modifications += Negation(new BioTextBoundMention(
              Seq("Negation_trigger"),
              Interval(ix),
              sentence = event.sentence,
              document = event.document,
              keep = event.keep,
              foundBy = event.foundBy
            ))
          ///////////////////////////////////////////////////

          ///////////////////////////////////////////////////
          // Check for the presence of some negative verbs
          // in all the sentence except the tokens

          // First, extract the trigger's range from the mention
          val interval = event.trigger.tokenInterval

          //val pairs = for (lemma <- event.lemmas) yield (1, lemma)
          val pairs = event.tokenInterval.toSeq zip event.lemmas.get

          val pairsL = pairs takeWhile (_._1 < interval.start)
          val pairsR = pairs dropWhile (_._1 <= interval.end)

          // Get the evidence for the existing negations to avoid duplicates
          val evidence:Set[Int] = event.modifications flatMap {
                  case mod:Negation => mod.evidence.tokenInterval.toSeq
                  case _ => Nil
              }

          // Check for single-token negative verbs
          for{
            (ix, lemma) <- (pairsL ++ pairsR)
            if (Seq("fail") contains lemma) && !(evidence contains ix)
          }{
              event.modifications += Negation(new BioTextBoundMention(
                Seq("Negation_trigger"),
                Interval(ix),
                sentence = event.sentence,
                document = event.document,
                keep = event.keep,
                foundBy = event.foundBy
              ))
            }

          def flattenTuples(left:(Int, String), right:(Int, String)) = {
            (
              (left._1, right._1),
              (left._2, right._2)
            )
          }

          val verbs = Seq(("play", "no"), ("play", "little"), ("is", "not"))
          // Introduce bigrams for two-token verbs in both sides of the trigger
          for(side <- Seq(pairsL, pairsR)){
            val bigrams = (side zip side.slice(1, side.length)) map (x =>
              flattenTuples(x._1, x._2)
            )

            for{
              (interval, bigram) <- bigrams
              if (verbs contains bigram) && !((evidence intersect (interval._1 to interval._2+1).toSet).size > 0)
            }
              {
                event.modifications += Negation(new BioTextBoundMention(
                Seq("Negation_trigger"),
                Interval(interval._1, interval._2 + 1),
                sentence = event.sentence,
                document = event.document,
                keep = event.keep,
                foundBy = event.foundBy
              ))}

          }
          ///////////////////////////////////////////////////
        case _ => ()
    }

    mentions
  }

  def validArguments(mention: Mention, state: State): Boolean = mention match {
    // TextBoundMentions don't have arguments
    case _: BioTextBoundMention => true
    // RelationMentions don't have triggers, so we can't inspect the path
    case _: BioRelationMention => true
    // EventMentions are the only ones we can really check
    case m: BioEventMention =>
      // get simple chemicals in arguments
      val args = m.arguments.values.flatten
      val simpleChemicals = args.filter(_ matches "Simple_chemical")
      // if there are no simple chemicals then we are done
      if (simpleChemicals.isEmpty) true
      else {
        for (chem <- simpleChemicals)
          if (proteinBetween(m.trigger, chem, state))
            return false
        true
      }
  }

  def proteinBetween(trigger: Mention, arg: Mention, state: State): Boolean = {
    // sanity check
    require(trigger.document == arg.document, "mentions not in the same document")
    // it is possible for the trigger and the arg to be in different sentences
    // because of coreference
    if (trigger.sentence != arg.sentence) false
    else trigger.sentenceObj.dependencies match {
      // if for some reason we don't have dependencies
      // then there is nothing we can do
      case None => false
      case Some(deps) => for {
        tok1 <- trigger.tokenInterval
        tok2 <- arg.tokenInterval
        path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
        node <- path
        if state.mentionsFor(trigger.sentence, node, "Gene_or_gene_product").nonEmpty
      } return true
        // if we reach this point then we are good
        false
    }
  }

  /**
   * Counts the number of semantic negatives (e.g., inhibition, suppression) appear between a trigger and an argument
   */
  def countSemanticNegatives(trigger:Mention, arg:Mention, excluded:Set[Int], negatives:mutable.HashSet[Int]):Boolean = {
    // sanity check
    require(trigger.document == arg.document, "mentions not in the same document")
    // it is possible for the trigger and the arg to be in different sentences because of coreference
    if (trigger.sentence != arg.sentence) false
    else if(trigger.sentenceObj.lemmas.isEmpty) false
    else trigger.sentenceObj.dependencies match {
      // if for some reason we don't have dependencies then there is nothing we can do
      case None => false
      case Some(deps) =>
        var shortestPath:Seq[Int] = null
        for (tok1 <- trigger.tokenInterval; tok2 <- arg.tokenInterval) {
          val path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
          if(shortestPath == null || path.length < shortestPath.length)
            shortestPath = path
        }
        // count negatives along the shortest path
        for(i <- shortestPath) {
          if(! excluded.contains(i)) {
            val lemma = trigger.sentenceObj.lemmas.get(i)
            if (RuleReader.SEMANTIC_NEGATIVE_PATTERN.findFirstIn(lemma).isDefined) {
              negatives += i
              //println("Found one semantic negative: " + lemma)
            }
          }
        }
        true
    }
  }

}
