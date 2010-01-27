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
package joshua.corpus.suffix_array;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import joshua.corpus.ContiguousPhrase;
import joshua.corpus.Corpus;
import joshua.corpus.Phrase;
import joshua.corpus.mm.MemoryMappedCorpusArray;
import joshua.corpus.suffix_array.mm.MemoryMappedSuffixArray;
import joshua.corpus.vocab.SymbolTable;
import joshua.corpus.vocab.Vocabulary;
import joshua.util.Cache;
import joshua.util.Counted;
import joshua.util.io.BinaryIn;

/**
 * Represents the most frequent phrases in a corpus.
 * 
 * @author Chris Callison-Burch
 * @author Lane Schwartz
 */
public class FrequentPhrases {

	/** Logger for this class. */
	private static final Logger logger = 
		Logger.getLogger(FrequentPhrases.class.getName());
	
	/** Suffix array in which frequent phrases are located. */
	final Suffixes suffixes;
	
	/** 
	 * Stores the number of times a phrase occurred in the
	 * corpus.
	 * <p>
	 * The iteration order of this map should start with the
	 * most frequent phrase and end with the least frequent
	 * phrase stored in the map.
	 * <p>
	 * The key set for this map should be identical to the key
	 * set in the <code>ranks</code> map.
	 */
	LinkedHashMap<Phrase,Integer> frequentPhrases;
	
	/** Maximum number of phrases of which this object is aware. */
	short maxPhrases;
	
	/** Maximum phrase length to consider. */
	int maxPhraseLength;
	
	/** Stores sorted lists of corpus locations for most frequent phrases. */
	Map<Phrase,InvertedIndex> invertedIndices;
	
	/**
	 * Constructs data regarding the frequencies of the <em>n</em>
	 * most frequent phrases found in the corpus backed by the
	 * provided suffix array.
	 * 
	 * @param suffixes   Suffix array corresponding to a corpus.
	 * @param minFrequency The minimum frequency required to
	 *                   for a phrase to be considered frequent.
	 * @param maxPhrases The number of phrases to consider.
	 * @param maxPhraseLength Maximum phrase length to consider.
	 */
	public FrequentPhrases(
			Suffixes suffixes,
			int minFrequency,
			short maxPhrases,
			int maxPhraseLength) {
		
		this.maxPhrases = maxPhrases;
		this.maxPhraseLength = maxPhraseLength;
		
		this.suffixes = suffixes;
		this.frequentPhrases = getMostFrequentPhrases(suffixes, minFrequency, maxPhrases, maxPhraseLength);
		this.invertedIndices = calculateInvertedIndices();
	}
	
	public FrequentPhrases(Suffixes suffixes, String binaryFilename) {
		this.suffixes = suffixes;
		
	}

	public short getMaxPhrases() {
		return this.maxPhrases;
	}
	
	public Suffixes getSuffixes() {
		return this.suffixes;
	}
	
	/**
	 * This method performs a one-pass computation of the
	 * collocation of two frequent subphrases. It is used for
	 * the precalculation of the translations of hierarchical
	 * phrases which are problematic to calculate on the fly.
	 * This procedure is described in "Hierarchical Phrase-Based
	 * Translation with Suffix Arrays" by Adam Lopez.
	 *
	 * @param maxPhraseLength the maximum length of any phrase
	 *                   in the phrases
	 * @param windowSize the maximum allowable space between
	 *                   phrases for them to still be considered
	 *                   collocated
	 * @param minNonterminalSpan Minimum span allowed for a nonterminal 
	 */
	public FrequentMatches getCollocations(
			int maxPhraseLength,
			int windowSize,
			short minNonterminalSpan
	) {
	
		FrequentMatches collocations = new FrequentMatches(this, maxPhraseLength, windowSize, minNonterminalSpan);
		
		countCollocations(maxPhraseLength, windowSize, minNonterminalSpan, collocations);
		
		collocations.histogramSort();
		
		return collocations;
		
	}


	/**
	 * Gets the number of times any frequent phrase co-occurred 
	 * with any frequent phrase within the given window.
	 * <p>        
	 * This method performs a one-pass computation of the
	 * collocation of two frequent sub-phrases. It is used for
	 * the precalculation of the translations of hierarchical
	 * phrases which are problematic to calculate on the fly.
	 * 
	 * This procedure is described in "Hierarchical Phrase-Based
	 * Translation with Suffix Arrays" by Adam Lopez.
	 *
	 * @param maxPhraseLength the maximum length of any phrase
	 *                   in the phrases
	 * @param windowSize the maximum allowable space between
	 *                   phrases for them to still be considered
	 *                   collocated
	 *                   
	 * @return The number of times any frequent phrase co-occurred 
	 *         with any frequent phrase within the given window.
	 */
	int countCollocations(int maxPhraseLength, int windowSize, short minNonterminalSpan) {
		return countCollocations(maxPhraseLength, windowSize, minNonterminalSpan, null);
	}
	
	/**
	 * Gets the number of times any frequent phrase co-occurred 
	 * with any frequent phrase within the given window.
	 * <p>        
	 * This method performs a one-pass computation of the
	 * collocation of two frequent sub-phrases. It is used for
	 * the precalculation of the translations of hierarchical
	 * phrases which are problematic to calculate on the fly.
	 * 
	 * This procedure is described in "Hierarchical Phrase-Based
	 * Translation with Suffix Arrays" by Adam Lopez.
	 * <p>
	 * 
	 * <em>Note</em>: In the course of constructing FrequentMatches, 
	 * this method should be called twice. 
	 * 
	 * In the first call, the frequentMatches should be null. 
	 * When frequentMatches is null, this method simply counts the
	 * total number of collocations of frequent phrases.
	 * 
	 * In the second call, the frequentMatches parameter should be non-null.
	 * When frequentMatches is not null, this method initializes 
	 * the provided frequentMatches object with collocation data,
	 * in addition to returning the total number of collocations
	 * of frequent phrases.
	 * 
	 * @param maxPhraseLength the maximum length of any phrase
	 *                   in the phrases
	 * @param windowSize the maximum allowable space between
	 *                   phrases for them to still be considered
	 *                   collocated
	 * @param frequentMatches Object for storing collocation data.
	 *                        If non-null, this object will be initialized
	 *                        with collocation data.
	 *                   
	 * @return The number of times any frequent phrase co-occurred 
	 *         with any frequent phrase within the given window.
	 */
	private int countCollocations(int maxPhraseLength, int windowSize, short minNonterminalSpan, FrequentMatches frequentMatches) {
		
		int count = 0;

		LinkedList<Phrase> phrasesInWindow = new LinkedList<Phrase>();
		LinkedList<Integer> positions = new LinkedList<Integer>();
		int sentenceNumber = 1;
		int endOfSentence = suffixes.getSentencePosition(sentenceNumber);

		if (logger.isLoggable(Level.FINEST)) logger.finest("END OF SENT: " + endOfSentence);

		Corpus corpus = suffixes.getCorpus();
		int endOfCorpus = corpus.size();
		
		// Start at the beginning of the corpus...
		for (int currentPosition : corpus.corpusPositions()) {
					
			// Start with a phrase length of 1, at the current position...
			for (int i = 1, endOfPhrase = currentPosition + i; 
					// ...ensure the phrase length isn't too long...
					i <= maxPhraseLength  &&  
					// ...and that the phrase doesn't extend past the end of the sentence...
					endOfPhrase <= endOfSentence  &&  
					// ...or past the end of the corpus
					endOfPhrase <= endOfCorpus; 
					// ...then increment the phrase length and end of phrase marker.
					i++, endOfPhrase = currentPosition + i) {

				
				// Get the current phrase
				Phrase phrase = new ContiguousPhrase(currentPosition, endOfPhrase, corpus);

				if (logger.isLoggable(Level.FINEST)) logger.finest("Found phrase (" +currentPosition + ","+endOfPhrase+") "  + phrase);

				// If the phrase is one we care about...
				if (frequentPhrases.containsKey(phrase)) {

					if (logger.isLoggable(Level.FINER)) logger.finer("\"" + phrase + "\" found at currentPosition " + currentPosition);

					// Remember the phrase...
					phrasesInWindow.add(phrase);

					// ...and its starting position
					positions.add(currentPosition);
				}

			} // end iterating over various phrase lengths


			// check whether we're at the end of the sentence and dequeue...
			if (currentPosition == endOfSentence) {

				if (logger.isLoggable(Level.FINEST)) {
					logger.finest("REACHED END OF SENT: " + currentPosition);
					logger.finest("PHRASES:   " + phrasesInWindow);
					logger.finest("POSITIONS: " + positions);
				}

				// empty the whole queue...
				for (int i = 0, n=phrasesInWindow.size(); i < n; i++) {

					Phrase phrase1 = phrasesInWindow.removeFirst();
					int position1 = positions.removeFirst();

					Iterator<Phrase> phraseIterator = phrasesInWindow.iterator();
					Iterator<Integer> positionIterator = positions.iterator();

					for (int j = i+1; j < n; j++) {

						Phrase phrase2 = phraseIterator.next();
						int position2 = positionIterator.next();

						if (logger.isLoggable(Level.FINEST)) logger.finest("CASE1: " + phrase1 + "\t" + phrase2 + "\t" + position1 + "\t" + position2);
						count++;
						if (frequentMatches != null) {
							frequentMatches.add(phrase1, phrase2, position1, position2);
						}

					}

				}
				// clear the queues
				phrasesInWindow.clear();
				positions.clear();

				// update the end of sentence marker
				sentenceNumber++;
				endOfSentence = suffixes.getSentencePosition(sentenceNumber)-1;

				if (logger.isLoggable(Level.FINER)) logger.finer("END OF SENT: " + sentenceNumber + " at position " + endOfSentence);

			} // Done processing end of sentence.


			// check whether the initial elements are
			// outside the window size...
			if (phrasesInWindow.size() > 0) {
				int position1 = positions.get(0);
				// dequeue the first element and
				// calculate its collocations...
				while (((currentPosition+1==endOfCorpus) || (windowSize <= currentPosition-position1))
						&& phrasesInWindow.size() > 0) {

					if (logger.isLoggable(Level.FINEST)) logger.finest("OUTSIDE OF WINDOW: " + position1 + " " +  currentPosition + " " + windowSize);
					
					Phrase phrase1 = phrasesInWindow.removeFirst();
					positions.removeFirst();
					
					Iterator<Phrase> phraseIterator = phrasesInWindow.iterator();
					Iterator<Integer> positionIterator = positions.iterator();

					for (int j = 0, n=phrasesInWindow.size(); j < n; j++) {

						Phrase phrase2 = phraseIterator.next();
						int position2 = positionIterator.next();

						count++;
						if (frequentMatches != null) {
							frequentMatches.add(phrase1, phrase2, position1, position2);
						}

						if (logger.isLoggable(Level.FINEST)) logger.finest("CASE2: " + phrase1 + "\t" + phrase2 + "\t" + position1 + "\t" + position2);
					}
					if (phrasesInWindow.size() > 0) {
						position1 = positions.getFirst();
					} else {
						position1 = currentPosition;
					}
				}
			}

		} // end iterating over positions in the corpus

		return count;
	}


//	/**
//	 * Returns an integer identifier for the collocation of
//	 * <code>phrase1</code> with <code>phrase2</code>.
//	 * <p>
//	 * If <code>rank1</code> is the rank of <code>phrase1</code>
//	 * and <code>rank2</code> is the rank of <code>phrase2</code>,
//	 * the identifier returned by this method is defined to be
//	 * <code>rank1*maxPhrases + rank2</code>.
//	 * <p>
//	 * As such, the range of possible values returned by this
//	 * method will be </code>0</code> through
//	 * <code>maxPhrases*maxPhrases-1</code>.
//	 *
//	 * @param phrase1 First phrase in a collocation.
//	 * @param phrase2 Second phrase in a collocation.
//	 * @return a unique integer identifier for the collocation.
//	 */
//	private int getKey(LinkedHashMap<Phrase,Short> ranks, Phrase phrase1, Phrase phrase2) {
//
//		short rank1 = ranks.get(phrase1);
//		short rank2 = ranks.get(phrase2);
//
//		int rank = rank1*maxPhrases + rank2;
//
//		return rank;
//	}
	

	//	/**
	//	 * Builds a HashMap of all the occurrences of the phrase,
	//	 * keying them based on the index of the sentence that they
	//	 * occur in. Since we iterate over all occurrences of the
	//	 * phrase, this method is linear with respect to the number
	//	 * of occurrences, and should not be used for very frequent
	//	 * phrases. This is part of the baseline method described
	//	 * in Section 4.1 of Adam Lopez's EMNLP paper.
	//	 */
	//	public HashMap<Integer,HashSet<Integer>> keyPositionsWithSentenceNumber(Phrase phrase) {
	//		// keys are the sentence numbers of partial matches
	//		HashMap<Integer,HashSet<Integer>> positionsKeyedWithSentenceNumber = new HashMap<Integer,HashSet<Integer>>(suffixes.size());
	//		int[] bounds = suffixes.findPhrase(phrase);
	//		if (bounds == null) return positionsKeyedWithSentenceNumber;
	//		
	//		int[] positions = suffixes.getAllPositions(bounds);
	//		for (int i = 0; i < positions.length; i++) {
	//			int sentenceNumber = suffixes.getSentenceIndex(positions[i]);
	//			HashSet<Integer> positionsInSentence = positionsKeyedWithSentenceNumber.get(sentenceNumber);
	//			if (positionsInSentence == null) {
	//				positionsInSentence = new HashSet<Integer>();
	//			}
	//			positionsInSentence.add(positions[i]);
	//			positionsKeyedWithSentenceNumber.put(sentenceNumber, positionsInSentence);
	//		}
	//		return positionsKeyedWithSentenceNumber;
	//	}

	//===============================================================
	// Protected 
	//===============================================================

	//===============================================================
	// Methods
	//===============================================================

	/**
	 * Calculates the frequency ranks of the provided phrases.
	 * <p>
	 * The iteration order of the <code>frequentPhrases</code>
	 * parameter is used by this method to determine the
	 * rank of each phrase. Specifically, the first phrase
	 * returned by the map's iterator is taken to be the most
	 * frequent phrase; the last phrase returned by the map's
	 * iterator is taken to be the least frequent phrase.
	 * 
	 * @param frequentPhrases Map from phrase to frequency of
	 *                        that phrase in a corpus.
	 * @return the frequency ranks of the provided phrases
	 */
	protected LinkedHashMap<Phrase,Short> getRanks() {
		
		logger.fine("Calculating ranks of frequent phrases");
		
		LinkedHashMap<Phrase,Short> ranks = new LinkedHashMap<Phrase,Short>(frequentPhrases.size());

		short i=0;
		for (Phrase phrase : frequentPhrases.keySet()) {
			ranks.put(phrase, i++);
		}
		
		logger.fine("Done calculating ranks");
		
		return ranks;
	}
	

	/**
	 * Calculates the most frequent phrases in the corpus.
	 * <p>
	 * Allows a threshold to be set for the minimum frequency
	 * to remember, as well as the maximum number of phrases.
	 * <p>
	 * This method implements the 
	 * <code>print_LDIs_stack</code> function defined in 
	 * section 2.5 of Yamamoto and Church.
	 *
	 * @param suffixes     a suffix array for the corpus
	 * @param minFrequency the minimum frequency required to
	 *                     retain phrases
	 * @param maxPhrases   the maximum number of phrases to
	 *                     return
	 * @param maxPhraseLength the maximum phrase length to
	 *                     consider
	 * 
	 * @return A map from phrase to the number of times 
	 *         that phrase occurred in the corpus. 
	 *         The iteration order of the map will start 
	 *         with the most frequent phrase, and 
	 *         end with the least frequent calculated phrase.
	 *         
	 * @see "Yamamoto and Church (2001), section 2.5"
	 */
	@SuppressWarnings("unchecked")
	protected static LinkedHashMap<Phrase,Integer> getMostFrequentPhrases(
			Suffixes suffixes,
			int minFrequency,
			int maxPhrases,
			int maxPhraseLength
	) {
		
		PriorityQueue<Counted<Phrase>> frequentPhrases = new PriorityQueue<Counted<Phrase>>();
		Set<Integer> prunedFrequencies = new HashSet<Integer>();
		
		Corpus corpus = suffixes.getCorpus();
		
		FrequencyClasses frequencyClasses = getFrequencyClasses(suffixes);
		
		for (FrequencyClass frequencyClass : frequencyClasses.withMinimumFrequency(minFrequency)) {
			
			int frequency = frequencyClass.getFrequency();
			
			if (! prunedFrequencies.contains(frequency)) {
				
				int i = frequencyClass.getIntervalStart();
				int startOfPhrase = suffixes.getCorpusIndex(i);
				int sentenceNumber = suffixes.getSentenceIndex(startOfPhrase);
				int endOfSentence = suffixes.getSentencePosition(sentenceNumber+1);
				
				int max = Math.min(maxPhraseLength, endOfSentence-startOfPhrase);
				if (logger.isLoggable(Level.FINER)) logger.finer("Max phrase length is " + max + " for " + frequencyClass.toString());
				
				for (int phraseLength : frequencyClass.validPhraseLengths(max)) {
					
					int endOfPhrase = startOfPhrase + phraseLength;
					
					Phrase phrase = new ContiguousPhrase(
							startOfPhrase, 
							endOfPhrase, 
							corpus);
					
					frequentPhrases.add(new Counted<Phrase>(phrase, frequency));
					if (frequentPhrases.size() > maxPhrases) {
						Counted<Phrase> pruned = frequentPhrases.poll();
						int prunedFrequency = pruned.getCount();
						prunedFrequencies.add(prunedFrequency);
						if (logger.isLoggable(Level.FINER)) logger.info("Pruned " + pruned.getElement() + " with frequency " + prunedFrequency);
						break;
					}
					
				}
			} else if (logger.isLoggable(Level.FINER)) {
				logger.finer("Skipping pruned frequency " + frequency);
			}
		}

		while (! frequentPhrases.isEmpty() && prunedFrequencies.contains(frequentPhrases.peek().getCount())) {
			Counted<Phrase> pruned = frequentPhrases.poll();
			if (logger.isLoggable(Level.FINER)) logger.finer("Pruned " + pruned.getElement() + " " + pruned.getCount());
		}
		
		Counted<Phrase>[] reverse = new Counted[frequentPhrases.size()];
		{
			int i=frequentPhrases.size()-1;
			while (! frequentPhrases.isEmpty()) {
				reverse[i] = frequentPhrases.poll();
				i -= 1;
			}
		}
		
		LinkedHashMap<Phrase,Integer> results = new LinkedHashMap<Phrase,Integer>();
		for (Counted<Phrase> countedPhrase : reverse) {
			Phrase phrase = countedPhrase.getElement();
			Integer count = countedPhrase.getCount();
			results.put(phrase, count);
		}
//		
//		while (! frequentPhrases.isEmpty()) {
//			Counted<Phrase> countedPhrase = frequentPhrases.poll();
//			Phrase phrase = countedPhrase.getElement();
//			Integer count = countedPhrase.getCount();
//			results.put(phrase, count);
//		}
//		
		return results;
		
	}
	
	/**
	 * Calculates the frequencies for 
	 * all phrase frequency classes in the corpus.
	 * <p>
	 * This method is implements the 
	 * <code>print_LDIs_stack</code> function defined in 
	 * section 2.5 of Yamamoto and Church.
	 *
	 * @param suffixes a suffix array for the corpus
	 * @return A list of term frequency classes
	 *         
	 * @see "Yamamoto and Church (2001), section 2.5"
	 */
	protected static FrequencyClasses getFrequencyClasses(Suffixes suffixes) {
		
		// calculate the longest common prefix delimited intervals...
		int[] longestCommonPrefixes = calculateLongestCommonPrefixes(suffixes);

		// Construct an initially empty object to hold class frequency information
		FrequencyClasses frequencyClasses = new FrequencyClasses(longestCommonPrefixes);
		
		// stack_i <-- an integer array for the stack of left edges, i
		Stack<Integer> startIndices = new Stack<Integer>();
		
		// stack_k <-- an integer array for the stack of representatives, k
		Stack<Integer> shortestInteriorLCPIndices = new Stack<Integer>();
		
		// stack_i[0] <-- 0
		startIndices.push(0);

		// stack_k[0] <-- 0
		shortestInteriorLCPIndices.push(0);
		
		// sp <-- 1 (a stack pointer)
		
		// for j <-- 0,1,2, ..., N-1
		for (int j = 0, size=suffixes.size(); j < size; j++) {	
			
			// Output an lcp-delimited interval <j,j> with tf=1
			//        (trivial interval i==j, frequency=1)
			if (logger.isLoggable(Level.FINEST)) logger.finest("Output trivial interval <"+j+","+j+"> with tf=1");
			frequencyClasses.record(j);
			//frequencyClasses.record(j, j, Integer.MAX_VALUE, 1);

			// While lcp[j+1] < lcp[stack_k[sp-1]] do
			while (longestCommonPrefixes[j+1] < longestCommonPrefixes[shortestInteriorLCPIndices.peek()]) {
							
				int i = startIndices.pop();
				int k = shortestInteriorLCPIndices.pop();
				
				int longestBoundingLCP = Math.max(longestCommonPrefixes[i], longestCommonPrefixes[j+1]);
				int shortestInteriorLCP = longestCommonPrefixes[k];

				// Output an interval <i,j> with tf=j-i+1, if it is lcp-delimited
				//                    (non-trivial interval)
				// sp <-- sp - 1
				if (longestBoundingLCP < shortestInteriorLCP) {
	
					int frequency = j-i+1;
					if (logger.isLoggable(Level.FINEST)) logger.finest("Output interval <"+i+","+j+"> with k="+k+" and tf="+j+"-"+i+"+1="+(j-i+1));
					frequencyClasses.record(i, j, k, frequency);	
				}
				
			}
			
			// stack_i[sp] <-- stack_k[sp-1]
			startIndices.push(shortestInteriorLCPIndices.peek());

			// stack_k[sp] <-- j+1
			shortestInteriorLCPIndices.push(j+1);

			// sp <-- sp + 1

		}
		
		return frequencyClasses;
	}
			


	/**
	 * Constructs an auxiliary array that stores longest common
	 * prefixes. The length of the array is the corpus size+1.
	 * Each elements lcp[i] indicates the length of the common
	 * prefix between two positions s[i-1] and s[i] in the
	 * suffix array.
	 * 
	 * @param suffixes Suffix array
	 * @return Longest common prefix array
	 */
	protected static int[] calculateLongestCommonPrefixes(Suffixes suffixes) {

		int length = suffixes.size();
		Corpus corpus = suffixes.getCorpus();

		int[] longestCommonPrefixes = new int[length +1];
		
		// For each element in the suffix array
		for (int i = 1; i < length; i++) {
			int corpusIndex = suffixes.getCorpusIndex(i);
			int prevCorpusIndex = suffixes.getCorpusIndex(i-1);

			// Start by assuming that the two positions 
			//    don't have anything in common
			int commonPrefixSize = 0;
			
			// While the 1st position is not at the end of the corpus...
			while(corpusIndex+commonPrefixSize < length && 
					// ... and the 2nd position is not at the end of the corpus...
					prevCorpusIndex + commonPrefixSize < length &&
					// ... and the nth word at the 1st position ...
					(corpus.getWordID(corpusIndex  + commonPrefixSize) == 
						// ... is the same as the nth word at the 2nd position ...
						corpus.getWordID(prevCorpusIndex + commonPrefixSize) && 
						// ... and the length to consider isn't too long
						commonPrefixSize <= Suffixes.MAX_COMPARISON_LENGTH)) {
				
				// The two positions match for their respective nth words!
				// Increment commonPrefixSize to reflect this fact
				commonPrefixSize++;
			}
			
			// Record how long the common prefix is between
			//    suffix array element s[i] and s[i-1] 
			longestCommonPrefixes[i] = commonPrefixSize;
		}
		
		// By definition, the 0th element of lcp is 0
		longestCommonPrefixes[0] = 0;
		
		// By definition, the final element of lcp is 0
		longestCommonPrefixes[length] = 0;
		
		return longestCommonPrefixes;

	}
	
//	/**
//	 * This method extracts phrases which reach the specified
//	 * minimum frequency. It uses the equivalency classes for
//	 * substrings in the interval i-j in the suffix array, as
//	 * defined in section 2.3 of the the Yamamoto and Church
//	 * CL article. This is a helper function for the
//	 * getMostFrequentPhrases method.
//	 * 
//	 * @param suffixes Suffix array
//	 * @param longestCommonPrefixes Longest common prefix array
//	 * @param i Index specifying a starting range in the suffix array
//	 * @param j Index specifying an ending range in the suffix array
//	 * @param k Index specifying a representative value of the range,
//	 *          such that i < k <= j, and such that longestCommonPrefixes[k]
//	 *          is the shortest interior longest common prefix of the range 
//	 *          (see section 2.5 of Yamamoto and Church)
//	 * @param phrases
//	 * @param frequencies
//	 * @param minFrequency
//	 * @param maxPhrases
//	 * @param maxPhraseLength
//	 * @param comparator
//	 */
//	protected static void recordPhraseFrequencies(
//			Suffixes            suffixes,
//			int[]               longestCommonPrefixes,
//			int                 i,
//			int                 j,
//			int                 k,
//			List<Phrase>        phrases,
//			List<Integer>       frequencies,
//			int                 minFrequency,
//			int                 maxPhrases,
//			int                 maxPhraseLength,
//			Comparator<Integer> comparator
//	) {
//		
//		if (i==j) {
//			logger.info("Output trivial interval <"+j+","+j+"> with k="+k+" and tf=1");
//		} else {
//
//			int LBL = Math.max(longestCommonPrefixes[i], longestCommonPrefixes[j+1]);
//			int SIL = longestCommonPrefixes[k];
//
//			if (LBL < SIL) {
//				logger.info("Output interval <"+i+","+j+"> with k="+k+" and tf="+j+"-"+i+"+1="+(j-i+1));				
//			} else {
//				logger.info("Interval <"+i+","+j+"> is NOT lcp-delimited, because " + LBL + " not < " +SIL);
//			}
//		}
//	}
	
	
	private Map<Phrase,InvertedIndex> calculateInvertedIndices() {
		Map<Phrase,InvertedIndex> invertedIndices = new HashMap<Phrase,InvertedIndex>(frequentPhrases.keySet().size());
		
		Corpus corpus = suffixes.getCorpus();
		int endOfCorpus = corpus.size();
		logger.fine("Corpus has size " + endOfCorpus);
		
		int sentenceNumber = 1;
		int endOfSentence = suffixes.getSentencePosition(sentenceNumber);
		
		// Start at the beginning of the corpus...
		for (int currentPosition : corpus.corpusPositions()) {
					
			logger.fine("At corpus position " + currentPosition);
			
			// Start with a phrase length of 1, at the current position...
			for (int i = 1, endOfPhrase = currentPosition + i; 
					// ...ensure the phrase length isn't too long...
					i <= maxPhraseLength  &&  
					// ...and that the phrase doesn't extend past the end of the sentence...
					endOfPhrase <= endOfSentence  &&  
					// ...or past the end of the corpus
					endOfPhrase <= endOfCorpus; 
					// ...then increment the phrase length and end of phrase marker.
					i++, endOfPhrase = currentPosition + i) {

				
				// Get the current phrase
				Phrase phrase = new ContiguousPhrase(currentPosition, endOfPhrase, corpus);

				if (logger.isLoggable(Level.FINE)) logger.fine("In sentence " + sentenceNumber + " found phrase (" +currentPosition + ","+endOfPhrase+") "  + phrase);

				// If the phrase is one we care about...
				if (frequentPhrases.containsKey(phrase)) {

					if (logger.isLoggable(Level.FINER)) logger.finer("\"" + phrase + "\" found at currentPosition " + currentPosition);

					if (! invertedIndices.containsKey(phrase)) {
						invertedIndices.put(phrase, new InvertedIndex());
					}
					
					InvertedIndex invertedIndex = invertedIndices.get(phrase);
					
					logger.fine("Recording position " + currentPosition + " in sentence " + sentenceNumber + " for phrase " + phrase);
					invertedIndex.record(currentPosition, sentenceNumber);

				}
				
			} // end iterating over various phrase lengths

			if (currentPosition == endOfSentence) {
				sentenceNumber += 1;
				endOfSentence = suffixes.getSentencePosition(sentenceNumber);
			}
		}
		
		return invertedIndices;
	}
	
	public void cacheInvertedIndices() {

		for (Map.Entry<Phrase, InvertedIndex> entry : invertedIndices.entrySet()) {
			
			Pattern pattern = new Pattern(entry.getKey());
			InvertedIndex list = entry.getValue();
			
			HierarchicalPhrases phraseLocations = new HierarchicalPhrases(pattern,list.corpusLocations, list.sentenceNumbers);
			suffixes.cacheMatchingPhrases(phraseLocations);
			if (logger.isLoggable(Level.FINE)) logger.fine("Cached sorted locations for " + pattern);
			
			if (logger.isLoggable(Level.FINE)) {
				StringBuilder s = new StringBuilder();
				String patternString = pattern.toString();
				for (Integer i : list.corpusLocations) {
					s.append(patternString);
					s.append('\t');
					s.append(i);
					s.append('\n');
				}
				logger.fine(s.toString());
			}
			
		}
		
	}
	
	/* See Javadoc for java.io.Externalizable interface. */
	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		
		SymbolTable vocab = suffixes.getVocabulary();
		
		// Read in the maximum number of phrases of which this object is aware.
		this.maxPhrases = in.readShort();
		
		// Read in the maximum phrase length to consider.
		this.maxPhraseLength = in.readInt();
		
		// Read in the count of frequent phrase types
		int frequentPhrasesSize = in.readInt();
		
		// Read in the frequentPhrases map
		this.frequentPhrases = new LinkedHashMap<Phrase,Integer>();
		for (int i=0; i<frequentPhrasesSize; i++) {
			
			// Write out number of times the phrase is found in the corpus
			int count = in.readInt();
			
			// Read in the number of tokens in the phrase
			int tokenCount = in.readInt();
			
			int[] wordIDs = new int[tokenCount];
			for (int j=0; i<tokenCount; i++) {
				wordIDs[j] = in.readInt();
			}
			
			BasicPhrase phrase = new BasicPhrase(wordIDs, vocab);
			this.frequentPhrases.put(phrase, count);
			
		}
		
		// Read in number of inverted indices
		int invertedIndicesCount = in.readInt();
		
		// Read in inverted indices
		this.invertedIndices = new HashMap<Phrase,InvertedIndex>(frequentPhrases.keySet().size());
		for (int i=0; i<invertedIndicesCount; i++) {
			
			// Read in the number of tokens in the phrase
			int tokenCount = in.readInt();
			
			int[] wordIDs = new int[tokenCount];
			for (int j=0; i<tokenCount; i++) {
				wordIDs[j] = in.readInt();
			}
			
			// Reconstruct phrase
			BasicPhrase phrase = new BasicPhrase(wordIDs, vocab);
			
			// Read in inverted index
			InvertedIndex invertedIndex = new InvertedIndex();
			invertedIndex.readExternal(in);
			
			this.invertedIndices.put(phrase, invertedIndex);
		}
	}

	public void writeExternal(ObjectOutput out) throws IOException { 
		
		// Write out maximum number of phrases of which this object is aware.
		out.writeShort(maxPhrases);
		
		// Write out maximum phrase length to consider.
		out.writeInt(maxPhraseLength);
		
		// Write out count of frequent phrase types
		out.writeInt(frequentPhrases.size());
		
		// Write out frequentPhrases map
		for (Map.Entry<Phrase, Integer> entry : frequentPhrases.entrySet()) {
			Phrase phrase = entry.getKey();
			int phraseCount = entry.getValue();
			int[] wordIDs = phrase.getWordIDs();
			
			// Write out number of times the phrase is found in the corpus
			out.writeInt(phraseCount);
			
			// Write out the number of tokens in the phrase
			out.writeInt(wordIDs.length);
			
			// Write out each token in the phrase
			for (int wordID : wordIDs) {
				out.writeInt(wordID);
			}
		}
		
		// Write out number of inverted indices
		out.writeInt(invertedIndices.size());
		
		// Write out inverted indices
		for (Map.Entry<Phrase, InvertedIndex> entry : invertedIndices.entrySet()) {
			
			Pattern pattern = new Pattern(entry.getKey());
			int[] wordIDs = pattern.getWordIDs();
			
			// Write out number of tokens in the pattern
			out.writeInt(wordIDs.length);
			
			// Write out each token in the phrase
			for (int wordID : wordIDs) {
				out.writeInt(wordID);
			}
			
			// Write out inverted index for this phrase
			InvertedIndex list = entry.getValue();
			out.writeObject(list);
		}
	}
	

	public String toString() {

		String format = null;

		StringBuilder s = new StringBuilder();

		for (Map.Entry<Phrase, Integer> entry : frequentPhrases.entrySet()) {

			Phrase phrase = entry.getKey();
			Integer frequency = entry.getValue();

			if (format==null) {
				int length = frequency.toString().length();
				format = "%1$" + length + "d";
			}

			s.append(String.format(format, frequency));
			s.append('\t');
			s.append(phrase.toString());
			s.append('\n');

		}

		return s.toString();
	}


	/**
	 * Private helper method for performing fast intersection.
	 * 
	 * @param <E>
	 * @param sortedData
	 * @param sortedQueries
	 * @param result
	 */
	private static <E extends Comparable<E>> void fastIntersect(List<E> sortedData, List<E> sortedQueries, SortedSet<E> result) {

		int medianQueryIndex = sortedQueries.size() / 2;
		E medianQuery = sortedQueries.get(medianQueryIndex);

		int index = Collections.binarySearch(sortedData, medianQuery);

		if (index >= 0) {
			result.add(medianQuery);
		} else {
			index = (-1 * index) + 1;
		}

		if (index-1 >= 0 && medianQueryIndex-1 >=0) {
			fastIntersect(sortedData.subList(0, index), sortedQueries.subList(0, medianQueryIndex), result);
		}

		if (index+1 < sortedData.size()  &&  medianQueryIndex+1 < sortedQueries.size()) {
			fastIntersect(sortedData.subList(index+1, sortedData.size()), sortedQueries.subList(medianQueryIndex+1, sortedQueries.size()), result);
		}
	}	


	//===============================================================
	// Static
	//===============================================================



	//===============================================================
	// Inner classes
	//===============================================================

	

	//===============================================================
	// Main method
	//===============================================================
	
	public static void main(String[] args) throws IOException, ClassNotFoundException {


		Vocabulary symbolTable;
		Corpus corpusArray;
		Suffixes suffixArray;
		FrequentPhrases frequentPhrases;

		if (args.length == 1) {

			String corpusFileName = args[0];

			logger.info("Constructing vocabulary from file " + corpusFileName);
			symbolTable = new Vocabulary();
			int[] lengths = Vocabulary.initializeVocabulary(corpusFileName, symbolTable, true);

			logger.info("Constructing corpus array from file " + corpusFileName);
			corpusArray = SuffixArrayFactory.createCorpusArray(corpusFileName, symbolTable, lengths[0], lengths[1]);

			logger.info("Constructing suffix array from file " + corpusFileName);
			suffixArray = new SuffixArray(corpusArray, Cache.DEFAULT_CAPACITY);

		} else if (args.length == 3) {

			String binarySourceVocabFileName = args[0];
			String binaryCorpusFileName = args[1];
			String binarySuffixArrayFileName = args[2];

			if (logger.isLoggable(Level.INFO)) logger.info("Constructing source language vocabulary from binary file " + binarySourceVocabFileName);
			ObjectInput in = BinaryIn.vocabulary(binarySourceVocabFileName);
			symbolTable = new Vocabulary();
			symbolTable.readExternal(in);

			logger.info("Constructing corpus array from file " + binaryCorpusFileName);
			if (logger.isLoggable(Level.INFO)) logger.info("Constructing memory mapped source language corpus array.");
			corpusArray = new MemoryMappedCorpusArray(symbolTable, binaryCorpusFileName);

			logger.info("Constructing suffix array from file " + binarySuffixArrayFileName);
			suffixArray = new MemoryMappedSuffixArray(binarySuffixArrayFileName, corpusArray, Cache.DEFAULT_CAPACITY);


		} else {

			System.err.println("Usage: java " + SuffixArray.class.getName() + " source.vocab source.corpus source.suffixes");
			System.exit(0);

			symbolTable = null;
			corpusArray = null;
			suffixArray = null;

		}

		int minFrequency = 0;
		short maxPhrases = 100;
		int maxPhraseLength = 10;
		int windowSize = 10;
		short minNonterminalSpan = 2;

		logger.info("Calculating " + maxPhrases + " most frequent phrases");
		frequentPhrases = new FrequentPhrases(suffixArray, minFrequency, maxPhrases, maxPhraseLength);

		logger.info("Frequent phrases: \n" + frequentPhrases.toString());

		logger.info("Caching inverted indices");
		frequentPhrases.cacheInvertedIndices();
		
		logger.info("Calculating collocations for most frequent phrases");
		FrequentMatches matches = frequentPhrases.getCollocations(maxPhraseLength, windowSize, minNonterminalSpan);

		

		
//		logger.info("Printing collocations for most frequent phrases");		
//		logger.info("Total collocations: " + matches.counter);
		
		
		//		for (int i=0, n=collocations.size(); i<n; i+=3) {
		//			
		//			int key = collocations.get(i);
		//			short rank2 = (short) key;
		//			short rank1 = (short) (key >> 8);
		//			Phrase phrase1 = frequentPhrases.phraseList.get(rank1);
		//			Phrase phrase2 = frequentPhrases.phraseList.get(rank2);
		//			
		//			String pattern = phrase1.toString() + " X " + phrase2.toString();
		//			
		//			int position1 = collocations.get(i+1);
		//			int position2 = collocations.get(i+2);
		//			
		//			System.out.println(pattern + " " + position1 + "," + position2);
		//		}



		//		for (Map.Entry<Integer, ArrayList<int[]>> entry : collocations.entrySet()) {
		//			
		//			int key = entry.getKey();
		//			ArrayList<int[]> values = entry.getValue();
		//			
		//			short rank2 = (short) key;
		//			short rank1 = (short) (key >> 8);
		//			
		//			Phrase phrase1 = frequentPhrases.phraseList.get(rank1);
		//			Phrase phrase2 = frequentPhrases.phraseList.get(rank2);
		//			
		//			String pattern = phrase1.toString() + " X " + phrase2.toString();
		//			
		//			for (int[] value : values) {
		//				System.out.println(value + "\t" + pattern);
		//			}
		//		}


	}
	
	private static final class InvertedIndex implements Externalizable {
		private final ArrayList<Integer> corpusLocations;
		private final ArrayList<Integer> sentenceNumbers;
		
		private InvertedIndex() {
			this.corpusLocations = new ArrayList<Integer>();
			this.sentenceNumbers = new ArrayList<Integer>();
		}
		
		private void record(int corpusLocation, int sentenceNumber) {
			corpusLocations.add(corpusLocation);
			sentenceNumbers.add(sentenceNumber);
		}

		public void readExternal(ObjectInput in) throws IOException,
				ClassNotFoundException {

			// Read number of corpus locations
			int corpusSize = in.readInt();
			
			// Read number of sentence numbers
			int sentences = in.readInt();
			
			// Read in all corpus locations
			corpusLocations.ensureCapacity(corpusSize);
			for (int i=0; i<corpusSize; i++) {
				corpusLocations.add(in.readInt());
			}
			
			// Read out all sentence numbers
			sentenceNumbers.ensureCapacity(sentences);
			for (int i=0; i<sentences; i++) {
				sentenceNumbers.add(in.readInt());
			}
		}

		public void writeExternal(ObjectOutput out) throws IOException {
			
			// Write number of corpus locations
			out.writeInt(corpusLocations.size());
			
			// Write number of sentence numbers
			out.writeInt(sentenceNumbers.size());
			
			// Write out all corpus locations
			for (Integer location : corpusLocations) {
				out.writeInt(location);
			}
			
			// Write out all sentence numbers
			for (Integer sentenceNumber : sentenceNumbers) {
				out.writeInt(sentenceNumber);
			}
		}
		
	}
	
}

