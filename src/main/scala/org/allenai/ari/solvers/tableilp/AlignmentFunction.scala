package org.allenai.ari.solvers.tableilp

import org.allenai.ari.solvers.common.{ EntailmentService, KeywordTokenizer }
import org.allenai.common.Logging

import com.medallia.word2vec.Word2VecModel
import com.redis.RedisClient

import java.io.File

/** Various options for computing similarity */
sealed trait SimilarityType {
  def scoreTitleTitle(titleStr1: String, titleStr2: String): Double // should be symmetric
  def scoreCellCell(cellStr1: String, cellStr2: String): Double // should be symmetric
  def scoreCellQCons(cellStr: String, qConsStr: String): Double // directional: qCons to cell
  def scoreTitleQCons(titleStr: String, qConsStr: String): Double // directional: qCons to title
  def scoreCellQChoice(cellStr: String, qChoiceStr: String): Double // directional: cell to qChoice
  def scoreTitleQChoice(titleStr: String, qChoiceStr: String): Double // dir.: title to qChoice
  def scoreStrToWhTerms(str: String, whTerms: Seq[String]): Double // dir.: str to (max of) whTerms

  // turn a one-sided score into a symmetric one
  protected def getSymmetricScore(text1: String, text2: String,
    scoringFunction: (String, String) => Double): Double = {
    (scoringFunction(text1, text2) + scoringFunction(text2, text1)) / 2d
  }

  // take the max of scores across various hypothesis strings
  protected def getMaxScore(text1: String, text2Seq: Seq[String],
    scoringFunction: (String, String) => Double): Double = {
    text2Seq.map(scoringFunction(text1, _)).max
  }
}

/** A function to compute alignment scores between paris of cells, title, question constituent, etc.
  *
  * @param alignmentType Must be one of Entailment, WordOverlap, or Word2Vec
  * @param entailmentServiceOpt Entailment service to use
  * @param entailmentScoreOffset The value to subtract from raw entailment score to get the score
  * @param tokenizer A keyword tokenizer
  */
class AlignmentFunction(
    alignmentType: String,
    entailmentServiceOpt: Option[EntailmentService],
    entailmentScoreOffset: Double,
    tokenizer: KeywordTokenizer,
    useRedisCache: Boolean
) extends Logging {
  private val similarityFunction: SimilarityType = alignmentType match {
    case "Entailment" => {
      logger.info("Using entailment for alignment score computation")
      if (useRedisCache) logger.info("  Using Redis cache for entailment scores")
      val teService = entailmentServiceOpt match {
        case Some(entailmentService) => entailmentService
        case None => throw new IllegalStateException("No entailment service available")
      }
      new EntailmentSimilarity(teService, entailmentScoreOffset, tokenizer, useRedisCache)
    }
    case "Word2Vec" => {
      logger.info("Using word2vec for alignment score computation")
      new Word2VecSimilarity
    }
    case "WordOverlap" => {
      logger.info("Using word overlap for alignment score computation")
      new WordOverlapSimilarity(tokenizer)
    }
    case _: String => {
      throw new IllegalArgumentException(s"Alignment type $alignmentType not recognized")
    }
  }

  /** Alignment score between two titles of tables */
  def scoreTitleTitle(titleStr1: String, titleStr2: String): Double = {
    similarityFunction.scoreTitleTitle(titleStr1, titleStr2)
  }

  /** Alignment score between cells of two tables */
  def scoreCellCell(cellStr1: String, cellStr2: String): Double = {
    similarityFunction.scoreCellCell(cellStr1, cellStr2)
  }

  /** Alignment score between a title of a table, and a question constituent */
  def scoreTitleQCons(titleStr: String, qConsStr: String): Double = {
    similarityFunction.scoreTitleQCons(titleStr, qConsStr)
  }

  /** Alignment score between a cell of a table, and a question constituent */
  def scoreCellQCons(cellStr: String, qConsStr: String): Double = {
    similarityFunction.scoreCellQCons(cellStr, qConsStr)
  }

  /** Alignment score between a title of a table, and a question option */
  def scoreTitleQChoice(titleStr: String, qChoiceStr: String): Double = {
    similarityFunction.scoreTitleQChoice(titleStr, qChoiceStr)
  }

  /** Alignment score between a cell of a table, and a question option */
  def scoreCellQChoice(cellStr: String, qChoiceStr: String): Double = {
    similarityFunction.scoreCellQChoice(cellStr, qChoiceStr)
  }

  /** Alignment score between a string and a which term */
  private val spaceSep = " ".r
  def scoreStrToWhTerms(str: String, whTerms: Seq[String]): Double = {
    // very strict: returns 0 if str has more than two words
    if (whTerms.isEmpty || spaceSep.split(str).length > 2) {
      0d
    } else {
      similarityFunction.scoreStrToWhTerms(str, whTerms)
    }
  }
}

// how much does text1 entail text2? (directional); an entailment score below the offset value is
// considered negative correlation.
private class EntailmentSimilarity(
    entailmentService: EntailmentService,
    entailmentScoreOffset: Double,
    tokenizer: KeywordTokenizer,
    useRedisCache: Boolean
) extends SimilarityType with Logging {

  private val redisOpt = if (useRedisCache) Some(new RedisClient("localhost", 6379)) else None

  def scoreTitleTitle(titleStr1: String, titleStr2: String): Double = {
    getSymmetricScore(titleStr1, titleStr2, getEntailmentScore)
  }
  def scoreCellCell(cellStr1: String, cellStr2: String): Double = {
    getSymmetricScore(cellStr1, cellStr2, getEntailmentScore)
  }
  def scoreCellQCons(cellStr: String, qConsStr: String): Double = {
    getEntailmentScore(qConsStr, cellStr)
  }
  def scoreTitleQCons(titleStr: String, qConsStr: String): Double = {
    getEntailmentScore(qConsStr, titleStr)
  }
  def scoreCellQChoice(cellStr: String, qChoiceStr: String): Double = {
    getEntailmentScore(cellStr, qChoiceStr)
  }
  def scoreTitleQChoice(titleStr: String, qChoiceStr: String): Double = {
    getEntailmentScore(titleStr, qChoiceStr)
  }
  def scoreStrToWhTerms(str: String, whTerms: Seq[String]): Double = {
    getMaxScore(str, whTerms, getEntailmentScore)
  }

  private val semicolonSep = ";".r
  private def splitStemKeywordTokenizeFilter(text: String): Seq[Seq[String]] = {
    for {
      str <- semicolonSep.split(text).toSeq
      trimmedStr = str.trim
      if !trimmedStr.startsWith("[") || !trimmedStr.endsWith("]") // ignore strings like "[...]"
    } yield tokenizer.stemmedKeywordTokenize(trimmedStr)
  }
  // set of words that should be ignored for entailment calculation if they are the hypothesis;
  // note that in WordNet, consumer -> person -> causal_agent -> cause !
  // Additional candidates: matter, substance, whole, part, cause, unit, event, relation
  private val ignoreHypothesisSet = Set("object", "measure", "part")
  private def getEntailmentScore(text1: String, text2: String): Double = {
    val key = text1 + "----" + text2
    // If Redis cache is being used and contains 'key', return the stored value; otherwise
    // compute the score and, if Redis is being use, save it as the value for 'key'
    val score = redisOpt.flatMap(_.get(key)) match {
      case Some(value) => value.toDouble
      case None => {
        val text1StemmedTokens = splitStemKeywordTokenizeFilter(text1)
        val text2StemmedTokens = splitStemKeywordTokenizeFilter(text2)
        val scores = for {
          text1Seq <- text1StemmedTokens
          text2Seq <- text2StemmedTokens
          if text1Seq == text2Seq || !ignoreHypothesisSet.contains(text2Seq.mkString(" ")
            .toLowerCase)
        } yield entailmentService.entail(text1Seq, text2Seq).confidence
        val scoreMax = if (scores.nonEmpty) scores.max else 0d
        redisOpt.foreach(_.set(key, scoreMax))
        scoreMax
      }
    }
    score - entailmentScoreOffset
  }
}

// cosine distance between two pieces of text (inherently symmetric)
private class Word2VecSimilarity extends SimilarityType {
  def scoreTitleTitle(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreCellCell(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreCellQCons(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreTitleQCons(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreCellQChoice(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreTitleQChoice(text1: String, text2: String): Double = getWord2VecScore(text1, text2)
  def scoreStrToWhTerms(text1: String, text2Seq: Seq[String]): Double = {
    getMaxScore(text1, text2Seq, getWord2VecScore)
  }

  private val word2vecFile = new File(
    "main/resources/vectors/GoogleNews-vectors-negative300_size=200000.bin"
  )
  private val w2vModel = Word2VecModel.fromBinFile(word2vecFile)
  private val w2vNoMatchStr = "</s>" // string used by word2vec when there is no match
  private def getWord2VecScore(text1: String, text2: String): Double = {
    val text1Modified = if (w2vModel.forSearch().contains(text1)) text1 else w2vNoMatchStr
    val text2Modified = if (w2vModel.forSearch().contains(text2)) text2 else w2vNoMatchStr
    w2vModel.forSearch().cosineDistance(text1Modified, text2Modified)
  }
}
// what fraction of text2 words are "covered" by text1 words? (directional)
private class WordOverlapSimilarity(tokenizer: KeywordTokenizer) extends SimilarityType {
  def scoreTitleTitle(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getWordOverlap)
  }
  def scoreCellCell(text1: String, text2: String): Double = {
    getSymmetricScore(text1, text2, getWordOverlap)
  }
  def scoreCellQCons(text1: String, text2: String): Double = getWordOverlap(text2, text1)
  def scoreTitleQCons(text1: String, text2: String): Double = getWordOverlap(text2, text1)
  def scoreCellQChoice(text1: String, text2: String): Double = getWordOverlap(text1, text2)
  def scoreTitleQChoice(text1: String, text2: String): Double = getWordOverlap(text1, text2)
  def scoreStrToWhTerms(text1: String, text2Seq: Seq[String]): Double = {
    getMaxScore(text1, text2Seq, getWordOverlap)
  }

  private def getWordOverlap(text1: String, text2: String): Double = {
    val text1StemmedTokens = tokenizer.stemmedKeywordTokenize(text1)
    val text2StemmedTokens = tokenizer.stemmedKeywordTokenize(text2)
    val coverage = text2StemmedTokens.intersect(text1StemmedTokens).size
    coverage.toDouble / text2StemmedTokens.size
  }
}
