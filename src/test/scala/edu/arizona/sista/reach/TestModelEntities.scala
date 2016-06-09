package edu.arizona.sista.reach

import edu.arizona.sista.odin._
import edu.arizona.sista.reach.mentions._
import edu.arizona.sista.utils.Serializer

import org.scalatest.{Matchers, FlatSpec}
import scala.util.Try
import TestUtils._

/**
  * Test the labeling of entities from the MITRE RAS model.
  *   Written by: Tom Hicks. 6/9/2016.
  *   Last Modified: Add some more protein tests.
  */
class TestModelEntities extends FlatSpec with Matchers {

  val s1 = "BRAF, EGF, EGFR, and GRB2 are proteins."
  val s2 = "HRAS, KRAS, NRAS, and NF1 are proteins."
  val s2a = "H-RAS, K-RAS, N-RAS, and B-Raf are proteins."
  val s3 = "MAPK1, MAPK3, MEK1, and MEK2 are proteins."
  val s4 = "RASA1, RASA2, RASA3, and SOS1 are proteins."
  val s5 = "p110alpha, p110beta, and p110delta are proteins."
  val s6 = "p55gamma, p85alpha, and p85beta are proteins."
  val s7 = "SAPK is a gene."

  "s1 entities" should "have GPP label" in {
    val mentions = getBioMentions(s1)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (4)
    mentions.count(_ matches "Gene_or_gene_product") should be (4)
  }

  "s2 entities" should "have GPP label" in {
    val mentions = getBioMentions(s2)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (4)
    mentions.count(_ matches "Gene_or_gene_product") should be (4)
  }

  "s2a entities" should "have GPP label" in {
    val mentions = getBioMentions(s2a)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (4)
    mentions.count(_ matches "Gene_or_gene_product") should be (4)
  }

  "s3 entities" should "have GPP label" in {
    val mentions = getBioMentions(s3)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (4)
    mentions.count(_ matches "Gene_or_gene_product") should be (4)
  }

  "s4 entities" should "have GPP label" in {
    val mentions = getBioMentions(s4)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (4)
    mentions.count(_ matches "Gene_or_gene_product") should be (4)
  }

  "s5 entities" should "have GPP label" in {
    val mentions = getBioMentions(s5)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (3)
    mentions.count(_ matches "Gene_or_gene_product") should be (3)
  }

  "s6 entities" should "have GPP label" in {
    val mentions = getBioMentions(s6)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (3)
    mentions.count(_ matches "Gene_or_gene_product") should be (3)
  }

  "s7 entities" should "have GPP label" in {
    val mentions = getBioMentions(s7)
    mentions.isEmpty should be (false)
    // printMentions(Try(mentions), true)      // DEBUGGING
    mentions.size should be (1)
    mentions.count(_ matches "Gene_or_gene_product") should be (1)
  }

}
