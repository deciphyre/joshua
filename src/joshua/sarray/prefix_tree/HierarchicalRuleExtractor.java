/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.sarray.prefix_tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import joshua.corpus.Corpus;
import joshua.corpus.LabeledSpan;
import joshua.corpus.MatchedHierarchicalPhrases;
import joshua.corpus.RuleExtractor;
import joshua.corpus.Span;
import joshua.corpus.alignment.Alignments;
import joshua.corpus.lexprob.LexicalProbabilities;
import joshua.decoder.ff.tm.BilingualRule;
import joshua.decoder.ff.tm.Rule;
import joshua.sarray.Pattern;
import joshua.sarray.Suffixes;
import joshua.util.Pair;

/**
 * Rule extractor for Hiero-style hierarchical phrase-based translation.
 * 
 * @author Lane Schwartz
 * @version $LastChangedDate$
 */
public class HierarchicalRuleExtractor implements RuleExtractor {

	/** Logger for this class. */
	private static final Logger logger = Logger.getLogger(HierarchicalRuleExtractor.class.getName());

	/** Lexical translation probabilities. */
	protected final LexicalProbabilities lexProbs;
	
	/** Max span in the source corpus of any extracted hierarchical phrase */
	protected final int maxPhraseSpan;
	
	
	/** Maximum number of terminals plus nonterminals allowed in any extracted hierarchical phrase. */
	protected final int maxPhraseLength;
	
	/** Minimum span in the source corpus of any nonterminal in an extracted hierarchical phrase. */
	protected final int minNonterminalSpan;
	
	/** Maximum span in the source corpus of any nonterminal in an extracted hierarchical phrase. */
	protected final int maxNonterminalSpan;
	
	/** Suffix array representing the source language corpus. */
	protected final Suffixes suffixArray;
	
	/** Corpus array representing the target language corpus. */
	protected final Corpus targetCorpus;
	
	/** Represents alignments between words in the source corpus and the target corpus. */
	protected final Alignments alignments;
	
	protected final int sampleSize;
	
	public HierarchicalRuleExtractor(Suffixes suffixArray, Corpus targetCorpus, Alignments alignments, LexicalProbabilities lexProbs, int sampleSize, int maxPhraseSpan, int maxPhraseLength, int minNonterminalSpan, int maxNonterminalSpan) {
		this.lexProbs = lexProbs;
		this.maxPhraseSpan = maxPhraseSpan;
		this.maxPhraseLength = maxPhraseLength;
		this.minNonterminalSpan = minNonterminalSpan;
		this.maxNonterminalSpan = maxNonterminalSpan;
		this.targetCorpus = targetCorpus;
		this.alignments = alignments;
		this.suffixArray = suffixArray;
		this.sampleSize = sampleSize;
	}

	public List<Rule> extractRules(Pattern sourcePattern, MatchedHierarchicalPhrases sourceHierarchicalPhrases) {

		if (logger.isLoggable(Level.FINE)) logger.fine("Extracting rules for source pattern: " + sourcePattern);
		
		int listSize = sourceHierarchicalPhrases.size();
		int stepSize; {
			if (listSize <= sampleSize) {
				stepSize = 1;
			} else {
				stepSize = listSize / sampleSize;
			}
		}
		
		List<Rule> results = new ArrayList<Rule>(sourceHierarchicalPhrases.size());

		List<Pattern> translations = new ArrayList<Pattern>();// = this.translate();
		List<Pair<Float,Float>> lexProbsList = new ArrayList<Pair<Float,Float>>();

		int totalPossibleTranslations = sourceHierarchicalPhrases.size();

		// For each sample HierarchicalPhrase
		for (int i=0; i<totalPossibleTranslations; i+=stepSize) { 
//			HierarchicalPhrase sourcePhrase = sourceHierarchicalPhrases.get(i, suffixArray.getCorpus());
			//for (HierarchicalPhrase sourcePhrase : samples) {
			// We may want to extract the alignment points at this point, rather than deeper on because we're doing this somewhat redundantly in getTranslation and calculateLexProbs
			Pattern translation = getTranslation(sourceHierarchicalPhrases, i);
			if (translation != null) {
				translations.add(translation);
				// We look up the lexprobs for this particular sourcePhrase (which corresponds to exactly one location in the source corpus)
				// We have to do this because, even if we have multiple instances where a source pattern matches the same target pattern,
				//    the alignment points for each pair may well be different.
				// TODO: store a Grid that shows the alignment between source words and target words.  Defer the calculation of the
				//       lexical probs until we have the unique <source pattern, target pattern, alignment> tuples.  I think that
				//        this will be more efficient, because most of the time the alignments will be identical. 
//				lexProbsList.add(lexProbs.calculateLexProbs(sourcePhrase));
				lexProbsList.add(lexProbs.calculateLexProbs(sourceHierarchicalPhrases, i));
			}
		}

		if (logger.isLoggable(Level.FINER)) logger.finer(translations.size() + " actual translations of " + sourcePattern + " being stored.");


		Map<Pattern,Integer> counts = new HashMap<Pattern,Integer>();

		// Calculate the number of times each pattern was found as a translation
		// This is needed for relative frequency estimation of p_e_given_f
		// Simultaneously, calculate the max (or average) 
		//    lexical translation probabilities for the given translation.

		// Pattern is the translation of the source phrase, Float is its cumulative lexical prob
		Map<Pattern,Float> cumulativeSourceGivenTargetLexProbs = new HashMap<Pattern,Float>();
		Map<Pattern,Float> cumulativeTargetGivenSourceLexProbs = new HashMap<Pattern,Float>();
		Map<Pattern,Integer> counterLexProbs = new HashMap<Pattern,Integer>();

		for (int i=0; i<translations.size(); i++) {	

			Pattern translation = translations.get(i);

			Pair<Float,Float> lexProbsPair = lexProbsList.get(i);

			{	// Perform lexical translation probability averaging
				// This is to deal with the situation that there may be 
				//  multiple alignments for a particular source pattern-translation pair.
				// Koehn uses the max, we follow Chiang (2005) in using an average.
				
				float sourceGivenTargetLexProb = lexProbsPair.first;
				float targetGivenSourceLexProb = lexProbsPair.second;
				
				if (!cumulativeSourceGivenTargetLexProbs.containsKey(translation)) {
					cumulativeSourceGivenTargetLexProbs.put(translation,sourceGivenTargetLexProb);
					cumulativeTargetGivenSourceLexProbs.put(translation,targetGivenSourceLexProb);
					
				} else {
					float runningTotal = cumulativeSourceGivenTargetLexProbs.get(translation) + sourceGivenTargetLexProb;
					cumulativeSourceGivenTargetLexProbs.put(translation,runningTotal);

					runningTotal = cumulativeTargetGivenSourceLexProbs.get(translation) + targetGivenSourceLexProb;
					cumulativeTargetGivenSourceLexProbs.put(translation,runningTotal);
				} 

				if (!counterLexProbs.containsKey(translation)) {
					counterLexProbs.put(translation, 1);
				} else {
					counterLexProbs.put(translation, 
							1 + counterLexProbs.get(translation));
				}
			}


			Integer count = counts.get(translation);

			if (count==null) count = 1;
			else count++;

			counts.put(translation, count);

		}

		double p_e_given_f_denominator = translations.size();

		// We don't want to produce duplicate rules
		HashSet<Pattern> uniqueTranslations = new HashSet<Pattern>(translations);
		
		for (Pattern translation : uniqueTranslations) {
			
			float p_e_given_f = -1.0f * (float) Math.log10(counts.get(translation) / p_e_given_f_denominator);
			if (Float.isInfinite(p_e_given_f)) p_e_given_f = PrefixTree.VERY_UNLIKELY;
			if (logger.isLoggable(Level.FINER)) logger.finer("   prob( "+ translation.toString() + " | " + sourcePattern.toString() + " ) =  -log10(" + counts.get(translation) + " / " + p_e_given_f_denominator + ") = " + p_e_given_f);
			
			float lex_p_e_given_f = (float) (-1.0f * Math.log10((double)cumulativeSourceGivenTargetLexProbs.get(translation) / (double)counterLexProbs.get(translation)));
			if (Float.isInfinite(lex_p_e_given_f)) lex_p_e_given_f = PrefixTree.VERY_UNLIKELY;
			if (logger.isLoggable(Level.FINER)) logger.finer("lexprob( " + translation.toString() + " | " + sourcePattern.toString() + " ) =  -log10(" + cumulativeSourceGivenTargetLexProbs.get(translation) + " / " + counterLexProbs.get(translation) + ") = " + lex_p_e_given_f);
			
			float lex_p_f_given_e = (float) (-1.0f * Math.log10(((double)cumulativeTargetGivenSourceLexProbs.get(translation)) / ((double)counterLexProbs.get(translation))));
			if (Float.isInfinite(lex_p_f_given_e)) lex_p_f_given_e = PrefixTree.VERY_UNLIKELY;
			if (logger.isLoggable(Level.FINER)) logger.finer("lexprob( " + sourcePattern.toString() + " | " + translation.toString() + " ) =  -log10(" + cumulativeTargetGivenSourceLexProbs.get(translation) + " / " + counterLexProbs.get(translation) + ") = " + lex_p_f_given_e);
			
			float[] featureScores = { p_e_given_f, lex_p_e_given_f, lex_p_f_given_e };

			Rule rule = new BilingualRule(PrefixTree.X, sourcePattern.getWordIDs(), translation.getWordIDs(), featureScores, translation.arity());
//			if (logger.isLoggable(Level.FINER)) logger.finer(rule.toString(PrefixTree.ntVocab, suffixArray.corpus.vocab, targetCorpus.vocab));
			results.add(rule);
		}

		return results;

	}


	/**
	 * Builds a hierarchical phrase in the target language substituting the terminal sequences
	 *  in the target side with nonterminal symbols corresponding to the source nonterminals.
	 * <p>
	 * This assumes that the source and target spans are consistent.
	 * 
	 * @param sourcePhrase Source language phrase to be translated.
	 * @param sourceSpan Span in the corpus of the source phrase; this is needed because the accurate span will not be in the sourcePhrase if it starts or ends with a nonterminal
	 * @param targetSpan Span in the target corpus of the target phrase.
	 * @param sourceStartsWithNT Indicates whether or not the source phrase starts with a nonterminal.
	 * @param sourceEndsWithNT Indicates whether or not the source phrase ends with a nonterminal.
	 * 
	 * @return null if no translation can be constructed
	 */
//	protected Pattern constructTranslation(HierarchicalPhrase sourcePhrase, Span sourceSpan, Span targetSpan, boolean sourceStartsWithNT, boolean sourceEndsWithNT) {
	protected Pattern constructTranslation(MatchedHierarchicalPhrases sourcePhrases, int sourcePhraseIndex, Span sourceSpan, Span targetSpan, boolean sourceStartsWithNT, boolean sourceEndsWithNT) {		
		if (logger.isLoggable(Level.FINE)) logger.fine("Constructing translation for source span " + sourceSpan + ", target span " + targetSpan);
				
		if (sourceSpan.size() > this.maxPhraseSpan)
			return null;
		
		// Construct a pattern for the trivial case where there are no nonterminals
//		if (sourcePhrases.pattern.arity == 0) {
		if (sourcePhrases.arity() == 0) {

			if (sourceSpan.size() > this.maxPhraseLength) {
				
				return null;
				
			} else {
				
				int[] words = new int[targetSpan.size()];

				for (int i=targetSpan.start; i<targetSpan.end; i++) {
					words[i-targetSpan.start] = targetCorpus.getWordID(i);
				}

				return new Pattern(targetCorpus.getVocabulary(), words);
			}
		}

		
		// Handle the more complex cases...
		List<LabeledSpan> targetNTSpans = new ArrayList<LabeledSpan>();
		int patternSize = targetSpan.size();
		
		int nonterminalID = -1;
		
		// For each non terminal in the source, find their corresponding positions in the target span... 
		
		// If the source phrase starts with a nonterminal, we have to handle that NT as a special case
		if (sourceStartsWithNT) {
			
			int firstTerminalIndex = sourcePhrases.getFirstTerminalIndex(sourcePhraseIndex);
			
//			if (sourcePhrase.terminalSequenceStartIndices[0] - sourceSpan.start < minNonterminalSpan) {
			if (firstTerminalIndex - sourceSpan.start < minNonterminalSpan) {
				
				return null;
				
			} else {
				// If the source phrase starts with NT, then we need to calculate the span of the first NT
//				Span nonterminalSourceSpan = new Span(sourceSpan.start, sourcePhrase.terminalSequenceStartIndices[0]);
				Span nonterminalSourceSpan = new Span(sourceSpan.start, firstTerminalIndex);
				Span nonterminalTargetSpan = alignments.getConsistentTargetSpan(nonterminalSourceSpan);

				if (nonterminalTargetSpan==null || nonterminalTargetSpan.equals(targetSpan)) return null;

				targetNTSpans.add(new LabeledSpan(nonterminalID,nonterminalTargetSpan));
				nonterminalID--;
				// the pattern length will be reduced by the length of the non-terminal, and increased by 1 for the NT itself.
				patternSize = patternSize - nonterminalTargetSpan.size() +1;
			}
		}
		
		// Process all internal nonterminals
//		for (int i=0; i<sourcePhrase.terminalSequenceStartIndices.length-1; i++) {
		for (int i=0, n=sourcePhrases.getNumberOfTerminalSequences()-1; i<n; i++) {
			
			int nextStartIndex = 
				sourcePhrases.getTerminalSequenceStartIndex(sourcePhraseIndex, i+1);
			
			int currentEndIndex =
				sourcePhrases.getTerminalSequenceEndIndex(sourcePhraseIndex, i);
			
//			if (sourcePhrase.terminalSequenceStartIndices[i+1] - sourcePhrase.terminalSequenceEndIndices[i] < minNonterminalSpan) {
			if (nextStartIndex - currentEndIndex < minNonterminalSpan) {
				
				return null;
				
			} else {
				
				Span nonterminalSourceSpan = new Span(currentEndIndex, nextStartIndex);

				Span nonterminalTargetSpan = alignments.getConsistentTargetSpan(nonterminalSourceSpan);

				if (nonterminalTargetSpan==null || nonterminalTargetSpan.equals(targetSpan)) return null;

				targetNTSpans.add(new LabeledSpan(nonterminalID,nonterminalTargetSpan));
				nonterminalID--;
				patternSize = patternSize - nonterminalTargetSpan.size() + 1;
				
			}
		}
			
		// If the source phrase starts with a nonterminal, we have to handle that NT as a special case
		if (sourceEndsWithNT) {
			
			int lastTerminalIndex = sourcePhrases.getLastTerminalIndex(sourcePhraseIndex);
			
//			if (sourceSpan.end - sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1] < minNonterminalSpan) {
			if (sourceSpan.end - lastTerminalIndex < minNonterminalSpan) {
				
				return null;
				
			} else {

				// If the source phrase ends with NT, then we need to calculate the span of the last NT
				Span nonterminalSourceSpan = new Span(lastTerminalIndex, sourceSpan.end);

				Span nonterminalTargetSpan = alignments.getConsistentTargetSpan(nonterminalSourceSpan);
				if (logger.isLoggable(Level.FINEST)) logger.finest("Consistent target span " + nonterminalTargetSpan + " for NT source span " + nonterminalSourceSpan);


				if (nonterminalTargetSpan==null || nonterminalTargetSpan.equals(targetSpan)) return null;

				targetNTSpans.add(new LabeledSpan(nonterminalID,nonterminalTargetSpan));
				nonterminalID--;
				patternSize = patternSize - nonterminalTargetSpan.size() + 1;

			}
		}
		
		boolean foundAlignedTerminal = false;
		
		// Create the pattern...
		int[] words = new int[patternSize];
		int patterCounter = 0;
		
		Collections.sort(targetNTSpans);
		
		if (targetNTSpans.get(0).getSpan().start == targetSpan.start) {
			
			int ntCumulativeSpan = 0;
			
			for (LabeledSpan span : targetNTSpans) {
				ntCumulativeSpan += span.size();
			}
			
			if (ntCumulativeSpan >= targetSpan.size()) {
				return null;
			}
			
		} else {
			// if we don't start with a non-terminal, then write out all the words
			// until we get to the first non-terminal
			for (int i = targetSpan.start; i < targetNTSpans.get(0).getSpan().start; i++) {
				if (!foundAlignedTerminal) {
					foundAlignedTerminal = alignments.hasAlignedTerminal(i, sourcePhrases, sourcePhraseIndex);
				}
				words[patterCounter] = targetCorpus.getWordID(i);
				patterCounter++;
			}
		}

		// add the first non-terminal
		words[patterCounter] = targetNTSpans.get(0).getLabel();
		patterCounter++;
		
		// add everything until the final non-terminal
		for(int i = 1; i < targetNTSpans.size(); i++) {
			LabeledSpan NT1 = targetNTSpans.get(i-1);
			LabeledSpan NT2 = targetNTSpans.get(i);
			
			for(int j = NT1.getSpan().end; j < NT2.getSpan().start; j++) {
				if (!foundAlignedTerminal) {
					foundAlignedTerminal = alignments.hasAlignedTerminal(j, sourcePhrases, sourcePhraseIndex);
				}
				words[patterCounter] = targetCorpus.getWordID(j);
				patterCounter++;
			}
			words[patterCounter] = NT2.getLabel();
			patterCounter++;
		}
		
		// if we don't end with a non-terminal, then write out all remaining words
		if(targetNTSpans.get(targetNTSpans.size()-1).getSpan().end != targetSpan.end) {
			// the target pattern starts with a non-terminal
			for(int i = targetNTSpans.get(targetNTSpans.size()-1).getSpan().end; i < targetSpan.end; i++) {
				if (!foundAlignedTerminal) {
					foundAlignedTerminal = alignments.hasAlignedTerminal(i, sourcePhrases, sourcePhraseIndex);
				}
				words[patterCounter] = targetCorpus.getWordID(i);
				patterCounter++;
			}
		}
		
		if (foundAlignedTerminal) {
			return new Pattern(targetCorpus.getVocabulary(), words);
		} else {
			if (logger.isLoggable(Level.FINEST)) logger.finest("Potential translation contained no aligned terminals");
			return null;
		}
		
	}
	
	/**
	 * Gets the target side translation pattern for a particular source phrase.
	 * <p>
	 * This is a fairly involved method -
	 * the complications arise because we must handle 4 cases:
	 * <ul>
	 * <li>The source phrase neither starts nor ends with a nonterminal</li>
	 * <li>The source phrase starts but doesn't end with a nonterminal</li>
	 * <li>The source phrase ends but doesn't start with a nonterminal</li>
	 * <li>The source phrase both starts and ends with a nonterminal</li>
	 * </ul>
	 * <p>
	 * When a hierarchical phrase begins (or ends) with a nonterminal
	 * its start (or end) point is <em>not</em> explicitly stored. 
	 * This is by design to allow a hierarchical phrase to describe 
	 * a set of possibly matching points in the corpus,
	 * but it complicates this method.
	 * 
	 * @param sourcePhrase
	 * @return the target side translation pattern for a particular source phrase.
	 */
	protected Pattern getTranslation(MatchedHierarchicalPhrases sourcePhrase, int sourcePhraseIndex) {

		//TODO It may be that this method should be moved to the AlignmentArray class.
		//     Doing so would require that the maxPhraseSpan and similar variables be accessible from AlignmentArray.
		//     It would also require storing the SuffixArary as a member variable of AlignmentArray, and
		//     making the constructTranslation method visible to AlignmentArray.
		
		
		
		// Case 1:  If sample !startsWithNT && !endsWithNT
//		if (!sourcePhrase.startsWithNonterminal() && !sourcePhrase.endsWithNonterminal()) {
		if (!sourcePhrase.startsWithNonterminal() && !sourcePhrase.endsWithNonterminal()) {
			
			if (logger.isLoggable(Level.FINER)) logger.finer("Case 1: Source phrase !startsWithNT && !endsWithNT");
			
			// Get target span
			Span sourceSpan = sourcePhrase.getSpan(sourcePhraseIndex);//new Span(sourcePhrase.terminalSequenceStartIndices[0], sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1]);//+sample.length); 

			Span targetSpan = alignments.getConsistentTargetSpan(sourceSpan);
			
			// If target span and source span are consistent
			//if (targetSpan!=null) {
			if (targetSpan!=null && targetSpan.size()>=sourcePhrase.arity()+1 && targetSpan.size()<=maxPhraseSpan) {
				
				// Construct a translation
				Pattern translation = constructTranslation(sourcePhrase, sourcePhraseIndex, sourceSpan, targetSpan, false, false);
				
				
				
				if (translation != null) {
					if (logger.isLoggable(Level.FINEST)) logger.finest("\tCase 1: Adding translation: '" + translation + "' for target span " + targetSpan + " from source span " + sourceSpan);
					//translations.add(translation);
					return translation;
				} else if (logger.isLoggable(Level.FINER)) {
					logger.finer("No valid translation returned from attempt to construct translation for source span " + sourceSpan + ", target span " + targetSpan);
				}
				
			}
			
		}
		
		// Case 2: If sourcePhrase startsWithNT && !endsWithNT
		else if (sourcePhrase.startsWithNonterminal() && !sourcePhrase.endsWithNonterminal()) {
			
			if (logger.isLoggable(Level.FINER)) logger.finer("Case 2: Source phrase startsWithNT && !endsWithNT");
			
			int startOfSentence = suffixArray.getCorpus().getSentencePosition(sourcePhrase.getSentenceNumber(sourcePhraseIndex));
			int startOfTerminalSequence = sourcePhrase.getFirstTerminalIndex(sourcePhraseIndex);//sourcePhrase.terminalSequenceStartIndices[0];
			int endOfTerminalSequence = sourcePhrase.getLastTerminalIndex(sourcePhraseIndex);//sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1];
			
			// Start by assuming the initial source nonterminal starts one word before the first source terminal 
//			Span possibleSourceSpan = new Span(sourcePhrase.terminalSequenceStartIndices[0]-1, sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1]);//+sample.length); 
			Span possibleSourceSpan = new Span(startOfTerminalSequence-1, endOfTerminalSequence);//+sample.length); 
			
			// Loop over all legal source spans 
			//      (this is variable because we don't know the length of the NT span)
			//      looking for a source span with a consistent translation
			while (possibleSourceSpan.start >= startOfSentence && 
					startOfTerminalSequence-possibleSourceSpan.start<=maxNonterminalSpan && 
					endOfTerminalSequence-possibleSourceSpan.start<=maxPhraseSpan) {
				
				// Get target span
				Span targetSpan = alignments.getConsistentTargetSpan(possibleSourceSpan);

				// If target span and source span are consistent
				//if (targetSpan!=null) {
				if (targetSpan!=null && targetSpan.size()>=sourcePhrase.arity()+1 && targetSpan.size()<=maxPhraseSpan) {

					// Construct a translation
					Pattern translation = constructTranslation(sourcePhrase, sourcePhraseIndex, possibleSourceSpan, targetSpan, true, false);

					if (translation != null) {
						if (logger.isLoggable(Level.FINEST)) logger.finest("\tCase 2: Adding translation: '" + translation + "' for target span " + targetSpan + " from source span " + possibleSourceSpan);
						//translations.add(translation);
						//break;
						return translation;
					}

				} 
				
				possibleSourceSpan.start--;
				
			}
			
		}
		
		// Case 3: If sourcePhrase !startsWithNT && endsWithNT
		else if (!sourcePhrase.startsWithNonterminal() && sourcePhrase.endsWithNonterminal()) {
			
			if (logger.isLoggable(Level.FINER)) logger.finer("Case 3: Source phrase !startsWithNT && endsWithNT");
			
			int endOfSentence = suffixArray.getCorpus().getSentenceEndPosition(sourcePhrase.getSentenceNumber(sourcePhraseIndex));
			//int startOfTerminalSequence = sourcePhrase.terminalSequenceStartIndices[0];
			int startOfTerminalSequence = sourcePhrase.getFirstTerminalIndex(sourcePhraseIndex);
			int endOfTerminalSequence = sourcePhrase.getLastTerminalIndex(sourcePhraseIndex);//sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1];
			//int startOfNT = endOfTerminalSequence + 1;
			
			// Start by assuming the initial source nonterminal starts one word after the last source terminal 
			Span possibleSourceSpan = //new Span(sourcePhrase.terminalSequenceStartIndices[0], sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1]+1); 
				new Span(startOfTerminalSequence, endOfTerminalSequence+1);
				
			// Loop over all legal source spans 
			//      (this is variable because we don't know the length of the NT span)
			//      looking for a source span with a consistent translation
			while (possibleSourceSpan.end <= endOfSentence && 
					//startOfTerminalSequence-possibleSourceSpan.start<=maxNonterminalSpan && 
					possibleSourceSpan.end - endOfTerminalSequence <= maxNonterminalSpan &&
					possibleSourceSpan.size()<=maxPhraseSpan) {
					//endOfTerminalSequence-possibleSourceSpan.start<=maxPhraseSpan) {
				
				// Get target span
				Span targetSpan = alignments.getConsistentTargetSpan(possibleSourceSpan);

				// If target span and source span are consistent
				//if (targetSpan!=null) {
				if (targetSpan!=null && targetSpan.size()>=sourcePhrase.arity()+1 && targetSpan.size()<=maxPhraseSpan) {

					// Construct a translation
					Pattern translation = constructTranslation(sourcePhrase, sourcePhraseIndex, possibleSourceSpan, targetSpan, false, true);

					if (translation != null) {
						if (logger.isLoggable(Level.FINEST)) logger.finest("\tCase 3: Adding translation: '" + translation + "' for target span " + targetSpan + " from source span " + possibleSourceSpan);
						//translations.add(translation);
						//break;
						return translation;
					}

				} 
				
				possibleSourceSpan.end++;
				
			}
			
		}
		
		// Case 4: If sourcePhrase startsWithNT && endsWithNT
		else if (sourcePhrase.startsWithNonterminal() && sourcePhrase.endsWithNonterminal()) {
			
			if (logger.isLoggable(Level.FINER)) logger.finer("Case 4: Source phrase startsWithNT && endsWithNT");
			
			int sentenceNumber = sourcePhrase.getSentenceNumber(sourcePhraseIndex);
			int startOfSentence = suffixArray.getCorpus().getSentencePosition(sentenceNumber);
			int endOfSentence = suffixArray.getCorpus().getSentenceEndPosition(sentenceNumber);
			int startOfTerminalSequence = sourcePhrase.getFirstTerminalIndex(sourcePhraseIndex);//sourcePhrase.terminalSequenceStartIndices[0];
			int endOfTerminalSequence = sourcePhrase.getLastTerminalIndex(sourcePhraseIndex);//sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1];
			
			// Start by assuming the initial source nonterminal 
			//   starts one word before the first source terminal and
			//   ends one word after the last source terminal 
			Span possibleSourceSpan = //new Span(sourcePhrase.terminalSequenceStartIndices[0]-1, sourcePhrase.terminalSequenceEndIndices[sourcePhrase.terminalSequenceEndIndices.length-1]+1); 
				new Span(startOfTerminalSequence-1, endOfTerminalSequence+1);
				
			// Loop over all legal source spans 
			//      (this is variable because we don't know the length of the NT span)
			//      looking for a source span with a consistent translation
			while (possibleSourceSpan.start >= startOfSentence && 
					possibleSourceSpan.end <= endOfSentence && 
					startOfTerminalSequence-possibleSourceSpan.start<=maxNonterminalSpan && 
					possibleSourceSpan.end-endOfTerminalSequence<=maxNonterminalSpan &&
					possibleSourceSpan.size()<=maxPhraseSpan) {
		
				// Get target span
				Span targetSpan = alignments.getConsistentTargetSpan(possibleSourceSpan);

				// If target span and source span are consistent
				//if (targetSpan!=null) {
				if (targetSpan!=null && targetSpan.size()>=sourcePhrase.arity()+1 && targetSpan.size()<=maxPhraseSpan) {

					// Construct a translation
					Pattern translation = constructTranslation(sourcePhrase, sourcePhraseIndex, possibleSourceSpan, targetSpan, true, true);

					if (translation != null) {
						if (logger.isLoggable(Level.FINEST)) logger.finest("\tCase 4: Adding translation: '" + translation + "' for target span " + targetSpan + " from source span " + possibleSourceSpan);
						//translations.add(translation);
						//break;
						return translation;
					}

				} 
				
				if (possibleSourceSpan.end < endOfSentence && possibleSourceSpan.end-endOfTerminalSequence+1<=maxNonterminalSpan && possibleSourceSpan.size()+1<=maxPhraseSpan) {
					possibleSourceSpan.end++;
				} else {
					possibleSourceSpan.end = endOfTerminalSequence+1;//1;
					possibleSourceSpan.start--;
				}
										
			}
			
		}
		
		return null;
		//throw new Error("Bug in translation code");
	}


}