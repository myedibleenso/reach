package edu.arizona.sista.bionlp

import edu.arizona.sista.processors.Document
import edu.arizona.sista.struct.Interval
import edu.arizona.sista.odin._
import edu.arizona.sista.bionlp.mentions._

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
    */
  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention =>
      val arguments = m.arguments
      val theme1s = for {
        name <- arguments.keys.toSeq
        if name == "theme1"
        theme <- arguments(name)
      } yield theme

      val theme2s = for {
        name <- arguments.keys.toSeq
        if name == "theme2"
        theme <- arguments(name)
      } yield theme

      (theme1s, theme2s) match {
        case (t1s, t2s) if (t1s ++ t2s).size < 2 => Nil
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
      val bioMention = m.arguments("entity").head.toBioMention
      //println(s"""\t\t(matched by ${m.foundBy} for "${m.text}")""")
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
    mention <- mentions
    biomention = mention.toBioMention
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
          val newArgs = controller.arguments.updated("controller", Seq(newController.get))
          new BioEventMention(
            biomention.labels,
            trigger,
            newArgs,
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
      complex.displayLabel = "[" + event.arguments("theme").map(_.text).mkString(":") + "]"
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
            (ix, label) <- outgoing(tok)
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
          // Check for the prescence of some negative verbs
          // in all the sentence except the tokens

          // First, extract the triggre's range from the mention
          val interval = event.trigger.tokenInterval

          //val pairs = for (lemma <- event.lemmas) yield (1, lemma)
          val pairs = event.tokenInterval.toSeq zip event.lemmas.get

          val pairsL = pairs takeWhile (_._1 < interval.start)
          val pairsR = pairs dropWhile (_._1 <= interval.end)

          // Check for single-token negative verbs
          for{
            (ix, lemma) <- (pairsL ++ pairsR)
            if Seq("fail") contains lemma
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
              if verbs contains bigram
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
    }

    mentions
  }
}
