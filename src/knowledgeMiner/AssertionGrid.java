/*******************************************************************************
 * Copyright (C) 2013 University of Waikato, Hamilton, New Zealand.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *    Sam Sarjant - initial API and implementation
 ******************************************************************************/
package knowledgeMiner;

import graph.core.CommonConcepts;
import graph.inference.CommonQuery;
import io.ontology.OntologySocket;
import io.resources.WikipediaSocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import knowledgeMiner.mapping.CycMapper;
import knowledgeMiner.mining.AssertionQueue;
import knowledgeMiner.mining.DefiniteAssertion;
import knowledgeMiner.mining.MinedAssertion;
import knowledgeMiner.mining.PartialAssertion;
import knowledgeMiner.mining.WeightedStanding;
import util.Pair;
import cyc.CycConstants;
import cyc.MappableConcept;
import cyc.OntologyConcept;

/**
 * An inner class for encapsulating the assertion grid and the various metrics
 * associated with the grid.
 * 
 * @author Sam Sarjant
 */
public class AssertionGrid {

	/** The assertions in a grid format for quick access. */
	private MinedAssertion[][] assertionGrid_;

	/** The current queue of disjoint cases. */
	private PriorityQueue<DisjointCase> cases_;

	/** The current column to seed. */
	private int column_;

	private OntologyConcept concept_;

	private Collection<OntologyConcept> conceptGenlTruths_;

	/** Temporary members for non-assertion-removal code. */
	private Collection<OntologyConcept> conceptIsaTruths_;

	/** The concept for which this assertion grid is built around. */
	private MappableConcept coreConcept_;

	private DisjointCase[] disjointCases_;

	private Map<Pair<String, String>, Boolean> disjointQueries_;

	/** The proportions of every assertion queue, based on hierarchy. */
	// private Float[] proportionVector_;
	private float weightSum_;

	/** The current row to seed. */
	private int row_;

	/** A stack of starting seeds, from highest weighted to least. */
	private ArrayList<Pair<Integer, Integer>> seedStack_;

	/** The standing of the concept. */
	private WeightedStanding standing_;

	/** The starting seed assertions that have been used. */
	private boolean[][] usedSeeds_;

	/** The weights of the assertions in a grid format for quick access. */
	private float[][] weightGrid_;

	/**
	 * Constructor for a new AssertionGrid extending an existing one.
	 * 
	 * @param existingGrid
	 *            The existing grid to add to.
	 * @param concept
	 *            The concept being disambiguated.
	 * @param standing
	 *            The learned or known standing of the concept.
	 * @param existingAssertions
	 *            The assertions to add to the grid.
	 * @param assertionRemoval
	 *            If assertions can be removed from the concept during this
	 *            process.
	 */
	public AssertionGrid(AssertionGrid existingGrid, OntologyConcept concept,
			WeightedStanding standing,
			Collection<DefiniteAssertion> existingAssertions,
			boolean assertionRemoval) {
		// Common stuff first
		concept_ = concept;
		coreConcept_ = existingGrid.coreConcept_;
		standing_ = standing;
		weightSum_ = existingGrid.weightSum_;

		if (existingGrid.assertionGrid_ == null)
			return;

		int oldLength = existingGrid.assertionGrid_.length;
		if (assertionRemoval && !existingAssertions.isEmpty()) {
			seedStack_ = new ArrayList<>(existingGrid.seedStack_);
			int newLength = oldLength + existingAssertions.size();
			instantiateAssertionGrid(existingGrid, newLength, coreConcept_,
					concept_);

			Iterator<DefiniteAssertion> iter = existingAssertions.iterator();
			for (int i = oldLength; i < newLength; i++) {
				assertionGrid_[i] = new DefiniteAssertion[] { iter.next() };
				usedSeeds_[i] = new boolean[] { false };
				weightGrid_[i] = new float[] { 1 };
				weightSum_ += 1;
				seedStack_.add(new Pair<Integer, Integer>(i, 0));
			}
			Collections.sort(seedStack_, new SeedComparator());
			conceptIsaTruths_ = null;
			conceptGenlTruths_ = null;
		} else {
			seedStack_ = existingGrid.seedStack_;
			// Non-assertion-removal
			instantiateAssertionGrid(existingGrid, oldLength, coreConcept_,
					concept_);

			conceptIsaTruths_ = new ArrayList<>();
			conceptGenlTruths_ = new ArrayList<>();
			for (DefiniteAssertion ma : existingAssertions) {
				if (ma.getRelation().equals(CycConstants.ISA.getConcept()))
					conceptIsaTruths_.add(ma.getArgs()[1]);
				else if (ma.getRelation().equals(
						CycConstants.GENLS.getConcept()))
					conceptGenlTruths_.add(ma.getArgs()[1]);
			}
		}
		resetMetrics();
	}

	/**
	 * Constructor for a new AssertionGrid that builds itself from partial
	 * assertions.
	 * 
	 * @param assertions
	 *            The assertions with which to build the grid.
	 * @param coreConcept
	 *            The core concept contained in every assertion.
	 * @param ontology
	 *            The ontology access.
	 * @param wmi
	 *            The WMI access.
	 */
	public AssertionGrid(Collection<? extends MinedAssertion> assertions,
			MappableConcept coreConcept, OntologySocket ontology, WikipediaSocket wmi) {
		coreConcept_ = coreConcept;
		concept_ = null;
		buildAssertionGrid(assertions, ontology, wmi);
		resetMetrics();
	}

	/**
	 * Constructor for a new AssertionGrid with a pre-provided collection of
	 * assertions to disambiguate.
	 *
	 * @param assertions
	 *            The assertions of the grid (vector).
	 * @param concept
	 *            The concept central to the assertions.
	 * @param standing
	 *            The learned or known standing of the concept.
	 * @param existingAssertions
	 *            Any existing assertions of the concept to verify the truth
	 *            against.
	 * @param assertionRemoval
	 *            If assertions can be removed.
	 */
	public AssertionGrid(Collection<DefiniteAssertion> assertions,
			OntologyConcept concept, WeightedStanding standing,
			Collection<DefiniteAssertion> existingAssertions,
			boolean assertionRemoval, OntologySocket ontology, WikipediaSocket wmi) {
		this(new AssertionGrid(assertions, null, ontology, wmi), concept,
				standing, existingAssertions, assertionRemoval);
	}

	/**
	 * Builds a 2d array of assertions (and an identical array of weights), so
	 * they can be approached in a easy-to-access and principled manner.
	 * 
	 * @param assertions
	 *            The assertions to build into a 2D array.
	 * @param ontology
	 *            The ontology access.
	 * @param wmi
	 *            The WMI access.
	 */
	private void buildAssertionGrid(
			Collection<? extends MinedAssertion> assertions,
			OntologySocket ontology, WikipediaSocket wmi) {
		ArrayList<MinedAssertion[]> assertionGrid = new ArrayList<>();
		ArrayList<float[]> weightGrid = new ArrayList<>();
		seedStack_ = new ArrayList<>();

		// Build the Assertion Queues
		Collection<AssertionQueue> aqs = new ArrayList<>();
		// Sum the weight of all aqs, using fractions for lower weights. Is <=
		// aqs.size
		for (MinedAssertion assertion : assertions) {
			AssertionQueue aq = null;
			if (assertion instanceof PartialAssertion)
				aq = expandPartial((PartialAssertion) assertion, ontology, wmi);
			else if (assertion instanceof DefiniteAssertion) {
				aq = new AssertionQueue();
				aq.add(assertion, 1);
			}

			if (aq != null && !aq.isEmpty()) {
				aq = (AssertionQueue) aq.cleanEmptyParents();
				aqs.add(aq);
			}
		}

		if (aqs.isEmpty())
			return;

		// Iterate through
		weightSum_ = 1;
		for (AssertionQueue aq : aqs)
			recurseBuild(aq, 0, assertionGrid, weightGrid, 1, seedStack_);

		assertionGrid_ = assertionGrid.toArray(new MinedAssertion[assertionGrid
				.size()][]);
		weightGrid_ = weightGrid.toArray(new float[weightGrid.size()][]);
		usedSeeds_ = new boolean[weightGrid_.length][];
		for (int x = 0; x < weightGrid_.length; x++) {
			usedSeeds_[x] = new boolean[weightGrid_[x].length];
		}
		Collections.sort(seedStack_, new SeedComparator());
	}

	/**
	 * Expands a partial assertion into an AssertionQueue for use in building
	 * the assertion grid.
	 * 
	 * @param pa
	 *            The partial assertion to expand.
	 * @param ontology
	 *            The ontology access.
	 * @param wmi
	 *            The WMI access.
	 */
	private AssertionQueue expandPartial(PartialAssertion pa,
			OntologySocket ontology, WikipediaSocket wmi) {
		// Expand the partial assertion
		Collection<MappableConcept> excluded = new HashSet<>();
		if (coreConcept_ != null)
			excluded.add(coreConcept_);
		CycMapper mapper = KnowledgeMiner.getInstance().getMapper();
		return pa.expand(excluded, mapper, ontology, wmi);
	}

	private float findAvailableSeed() {
		if (usedSeeds_ == null)
			return -1;
		int i = 0;
		while (usedSeeds_[seedStack_.get(i).objA_][seedStack_.get(i).objB_]) {
			i++;
			if (i >= seedStack_.size())
				return -1;
		}
		row_ = seedStack_.get(i).objB_;
		column_ = seedStack_.get(i).objA_;
		return weightGrid_[column_][row_];
	}

	private DefiniteAssertion getAssertion(int x, int y) {
		// Check for ArrayIndexException
		if (x >= assertionGrid_.length)
			return null;
		if (y >= assertionGrid_[x].length)
			return null;
		return (DefiniteAssertion) assertionGrid_[x][y];
	}

	private float getWeight(int x, int y) {
		if (x >= weightGrid_.length)
			return 0;
		if (y >= weightGrid_[x].length)
			return 0;
		return weightGrid_[x][y];
	}

	/**
	 * Instantiates all the partial assertions of the grid with definite
	 * assertions.
	 * 
	 * @param existingGrid
	 *            The existing grid to instantiate
	 * @param sizeIncrease
	 *            Any size increases needed.
	 * @param mappedConcept
	 *            The mapped concpet to replace.
	 * @param replacementConcept
	 *            The replacement concept.
	 * @return
	 */
	private void instantiateAssertionGrid(AssertionGrid existingGrid,
			int newSize, MappableConcept mappedConcept,
			OntologyConcept replacementConcept) {
		MinedAssertion[][] coreGrid = existingGrid.assertionGrid_;
		assertionGrid_ = new MinedAssertion[newSize][];
		// Instantiate every assertion.
		for (int x = 0; x < coreGrid.length; x++) {
			assertionGrid_[x] = new MinedAssertion[coreGrid[x].length];
			for (int y = 0; y < coreGrid[x].length; y++) {
				MinedAssertion assertion = coreGrid[x][y];
				if (assertion != null) {
					if (assertion instanceof PartialAssertion) {
						assertionGrid_[x][y] = ((PartialAssertion) assertion)
								.instantiate(mappedConcept, replacementConcept);
					} else if (assertion instanceof DefiniteAssertion)
						assertionGrid_[x][y] = assertion;
				}
			}
		}

		// Other members
		if (newSize == coreGrid.length) {
			usedSeeds_ = new boolean[existingGrid.usedSeeds_.length][];
			weightGrid_ = existingGrid.weightGrid_;
		} else {
			usedSeeds_ = new boolean[newSize][];
			weightGrid_ = Arrays.copyOf(existingGrid.weightGrid_, newSize);
		}
		for (int x = 0; x < existingGrid.usedSeeds_.length; x++)
			usedSeeds_[x] = new boolean[existingGrid.usedSeeds_[x].length];
	}

	private boolean isDisjoint(OntologyConcept testCollection,
			Collection<OntologyConcept> truths, OntologySocket ontology) {
		if (truths.contains(testCollection))
			return false;
		String testID = testCollection.getIdentifier();
		for (OntologyConcept truth : truths) {
			String truthID = truth.getIdentifier();

			// Check cache
			Pair<String, String> disjCase = null;
			if (testID.compareTo(truthID) < 0)
				disjCase = new Pair<String, String>(testID, truthID);
			else
				disjCase = new Pair<String, String>(truthID, testID);
			if (disjointQueries_.containsKey(disjCase)) {
				if (disjointQueries_.get(disjCase))
					return true;
			} else {
				boolean result = ontology.evaluate(null,
						CommonConcepts.DISJOINTWITH.getID(), testID, truthID);
				disjointQueries_.put(disjCase, result);
				if (result)
					return true;
			}
		}
		return false;
	}

	/**
	 * Recursively build the grid
	 * 
	 * @param aq
	 *            The partial assertion to instantiate, build, and recurse
	 *            through.
	 * @param offset
	 *            The offset (for lower queues)
	 * @param assertionGrid
	 *            The assertion grid to add to.
	 * @param weightGrid
	 *            The weight grid to add to.
	 * @param fraction
	 *            The value to offset weight lookups.
	 * @param coords
	 *            The coord locations of elements in the grid.
	 */
	private void recurseBuild(AssertionQueue aq, int offset,
			ArrayList<MinedAssertion[]> assertionGrid,
			ArrayList<float[]> weightGrid, float fraction,
			ArrayList<Pair<Integer, Integer>> coords) {
		int size = aq.size();
		// Add the current AQ
		int x = assertionGrid.size();
		if (size > 0) {
			MinedAssertion[] assertions = new MinedAssertion[size + offset];
			float[] weights = new float[size + offset];
			int i = offset;
			for (MinedAssertion ma : aq) {
				assertions[i] = ma;
				weights[i] = (float) aq.getWeight(ma) * fraction;
				coords.add(new Pair<Integer, Integer>(x, i));
				i++;
			}
			assertionGrid.add(assertions);
			weightGrid.add(weights);
			if (offset == 0)
				weightSum_ += weights[0];
		}

		// Recurse to lower AQs, offsetting
		Set<AssertionQueue> subAssertionQueues = aq.getSubAssertionQueues();
		for (AssertionQueue subAQ : subAssertionQueues) {
			recurseBuild(subAQ, size + offset, assertionGrid, weightGrid,
					fraction / subAssertionQueues.size(), coords);
		}
	}

	private DisjointCase requestDisjointCase(OntologySocket ontology) {
		float bestStanding = Math.max(
				standing_.getNormalisedWeight(TermStanding.COLLECTION),
				standing_.getNormalisedWeight(TermStanding.INDIVIDUAL));
		do {
			// Seed a new case
			float seedWeight = findAvailableSeed();
			if (seedWeight < 0 && cases_.isEmpty())
				return null;

			// Add a case for both individual and collection (if there is
			// ambiguity), with standing weights
			if (cases_.isEmpty()
					|| seedWeight > cases_.peek().getPotentialWeight()) {
				// Always slight bias against Collection.
				float collWeight = Math.min(
						standing_.getNormalisedWeight(TermStanding.COLLECTION)
								/ bestStanding, 1) - 0.0001f;
				float indvWeight = Math.min(
						standing_.getNormalisedWeight(TermStanding.INDIVIDUAL)
								/ bestStanding, 1);
				// System.out.println(concept_ + ":" + collWeight + " " +
				// indvWeight);
				DisjointCase dc = new DisjointCase(row_, column_, true,
						collWeight, ontology);
				if (dc.getPotentialWeight() > 0)
					cases_.add(dc);

				dc = new DisjointCase(row_, column_, false, indvWeight,
						ontology);
				if (dc.getPotentialWeight() > 0)
					cases_.add(dc);
				usedSeeds_[column_][row_] = true;
			}
		} while (cases_.isEmpty());
		return cases_.remove();
	}

	private void resetMetrics() {
		row_ = 0;
		column_ = 0;
		cases_ = new PriorityQueue<>();
		disjointCases_ = null;
		disjointQueries_ = new HashMap<>();
	}

	@SuppressWarnings("unchecked")
	public Collection<DefiniteAssertion> findMaximalConjoint(
			OntologySocket ontology) {
		findNConjoint(1, ontology);
		if (disjointCases_[0] != null) {
			DisjointnessDisambiguator.logger_.debug(
					"Disambiguated {} with weight {}",
					concept_.getConceptName(),
					Math.min(1.0, disjointCases_[0].getPotentialWeight()));
			return disjointCases_[0].getAssertions();
		}
		return Collections.EMPTY_LIST;
	}

	/**
	 * Finds N cases of consistent assertions from the assertion grid, where
	 * these N cases represent the highest weighted clusters. If there are less
	 * than N cases, this returns when the case is found.
	 *
	 * @param numDisambiguated
	 *            N, the number of cases to be found
	 * @param ontology
	 *            The ontology access.
	 */
	public void findNConjoint(int numDisambiguated, OntologySocket ontology) {
		// TODO Major problem - simple checks for disjointness and arg
		// constraints isn't enough - there is now negation, collection orders,
		// cycles, etc. These will not be retained when we do finally assert,
		// but this means the case weight is not indicative of the final
		// assertions.

		// Iterate through the assertions in a priority queue until completed
		resetMetrics();
		disjointCases_ = new DisjointCase[numDisambiguated];
		int caseNum = 0;
		do {
			DisjointCase dc = requestDisjointCase(ontology);
			// No assertions left!
			if (dc == null) {
				disjointCases_ = Arrays.copyOf(disjointCases_, caseNum);
				return;
			}
			// DC not yet completed.
			if (!dc.isCompleted()) {
				dc.processRow(ontology);
				if (dc.isCompleted())
					disjointCases_[caseNum++] = dc;
				else
					cases_.add(dc);
			}
		} while (caseNum < numDisambiguated);
	}

	public Collection<DefiniteAssertion> getAssertions(int caseNum) {
		return disjointCases_[caseNum].getAssertions();
	}

	public float getCaseWeight(int caseNum) {
		if (disjointCases_[caseNum] == null)
			return -1;
		return Math.min(disjointCases_[caseNum].getPotentialWeight(), 1);
	}

	public int getNumCases() {
		return disjointCases_.length;
	}

	public DefiniteAssertion getSeedAssertion(int i) {
		return disjointCases_[i].getSeedAssertion();
	}

	public boolean isCollection(int caseNum) {
		return disjointCases_[caseNum].isaCollection_;
	}

	public boolean isEmpty() {
		return assertionGrid_ == null;
	}

	@Override
	public String toString() {
		// Print out the assertion grid
		StringBuilder builder = new StringBuilder();
		for (int x = 0; x < assertionGrid_.length; x++) {
			if (assertionGrid_[x] == null)
				builder.append("null");
			else {
				for (int y = 0; y < assertionGrid_[x].length; y++) {
					if (assertionGrid_[x][y] == null)
						builder.append("[   ]");
					else {
						String assertion = assertionGrid_[x][y].toString();
						if (usedSeeds_[x] != null && usedSeeds_[x][y])
							assertion = assertion.toUpperCase();
						if (weightGrid_[x] == null)
							builder.append("[" + assertion + ":???]");
						else
							builder.append("[" + assertion + ":"
									+ weightGrid_[x][y] + "]");
					}
				}
			}
			builder.append("\n");
		}
		return builder.toString();
	}

	private class DisjointCase implements Comparable<DisjointCase> {
		private ArrayList<DefiniteAssertion> allAssertions_;
		private ArrayList<Float> assertionWeights_;
		private int caseRow_;
		private boolean[] completed_;
		private float completedWeight_;
		private Collection<OntologyConcept> genlsTruth_;
		private boolean isaCollection_;
		private Collection<OntologyConcept> isaTruth_;
		private DefiniteAssertion seedAssertion_;
		private float standingWeight_;

		/**
		 * Constructor for a new DisjointCase which progressively identifies
		 * consistent assertions.
		 * 
		 * @param row
		 *            The row to start with.
		 * @param column
		 *            The column to start with.
		 * @param ontology
		 *            The ontology access.
		 * @param isaTruth
		 *            The isa truths.
		 * @param genlTruth
		 *            The genls truths.
		 */
		public DisjointCase(int row, int column, boolean isaCollection,
				float standingWeight, OntologySocket ontology) {
			if (standingWeight <= 0) {
				completedWeight_ = -1;
				return;
			}
			standingWeight_ = standingWeight;
			completed_ = new boolean[assertionGrid_.length];
			Arrays.fill(completed_, false);
			caseRow_ = 0;
			isaTruth_ = new HashSet<>();
			if (conceptIsaTruths_ != null)
				isaTruth_.addAll(conceptIsaTruths_);
			genlsTruth_ = new HashSet<>();
			if (conceptGenlTruths_ != null)
				genlsTruth_.addAll(conceptGenlTruths_);
			allAssertions_ = new ArrayList<>();
			assertionWeights_ = new ArrayList<>();
			completedWeight_ = 0;
			isaCollection_ = isaCollection;
			if (isaCollection
					&& isDisjoint(CycConstants.COLLECTION.getConcept(),
							isaTruth_, ontology)) {
				completedWeight_ = -1;
				return;
			}
			checkDisjointness(getAssertion(column, row), column, row, ontology);
			// If seed assertion is invalid
			if (completedWeight_ == 0)
				completedWeight_ = -1;
			else
				seedAssertion_ = allAssertions_.get(allAssertions_.size() - 1);
		}

		private float calculatePotentialWeight() {
			float weight = 0;
			for (int x = 0; x < completed_.length; x++) {
				if (!completed_[x] && getWeight(x, caseRow_) > 0)
					weight += getWeight(x, caseRow_);
			}
			return weight;
		}

		/**
		 * Checks if arg constraints can be created or if the true information
		 * is disjoint with them.
		 * 
		 * @param assertion
		 *            The assertion to check constraints for.
		 * @param ontology
		 *            The ontology access.
		 * @return True if the constraints can be satisfied.
		 */
		private boolean checkArgConstraints(DefiniteAssertion assertion,
				OntologySocket ontology) {
			// Find index of concept
			int conceptIndex = assertion.getArgIndex(concept_) + 1;
			if (conceptIndex == 0)
				return false;

			// Check arg constraints
			Collection<OntologyConcept> isaConstraints = ontology.quickQuery(
					CommonQuery.MINARGNISA, assertion.getRelation()
							.getIdentifier() + " '" + conceptIndex);
			for (OntologyConcept constraint : isaConstraints)
				if (isDisjoint(constraint, isaTruth_, ontology))
					return false;

			Collection<OntologyConcept> genlsConstraints = ontology.quickQuery(
					CommonQuery.MINARGNGENL, assertion.getRelation()
							.getIdentifier() + " '" + conceptIndex);
			for (OntologyConcept constraint : genlsConstraints)
				if (isDisjoint(constraint, genlsTruth_, ontology))
					return false;

			// Add as truth & assertions
			isaTruth_.addAll(isaConstraints);
			for (OntologyConcept constraint : isaConstraints)
				if (!constraint.getConceptName().equals("Thing")) {
					DefiniteAssertion ma = new DefiniteAssertion(
							CycConstants.ISA.getConcept(), null, concept_,
							constraint);
					if (!allAssertions_.contains(ma))
						allAssertions_.add(ma);
				}

			genlsTruth_.addAll(genlsConstraints);
			for (OntologyConcept constraint : genlsConstraints)
				if (!constraint.getConceptName().equals("Thing")) {
					DefiniteAssertion ma = new DefiniteAssertion(
							CycConstants.GENLS.getConcept(), null, concept_,
							constraint);
					if (!allAssertions_.contains(ma))
						allAssertions_.add(ma);
				}
			return true;
		}

		/**
		 * Checks if an assertion is consistent with existing information about
		 * a concept and adds it if it is.
		 * 
		 * @param assertion
		 *            The assertion to check.
		 * @param x
		 *            The x pos of the assertion in the grid.
		 * @param y
		 *            The y pos of the assertion in the grid.
		 * @param ontology
		 *            The ontology access.
		 */
		private void checkDisjointness(DefiniteAssertion assertion, int x,
				int y, OntologySocket ontology) {
			if (assertion.isHierarchical()) {
				// If a collection, the predicate is/can be genls, and is not
				// disjoint with existing genls, and isn't a useless self-genls
				// edge
				OntologyConcept relation = assertion.getRelation();
				if (isaCollection_
						&& (relation
								.equals(CycConstants.ISA_GENLS.getConcept()) || ontology
								.evaluate(null,
										CommonConcepts.GENLPREDS.getID(),
										relation.getIdentifier(),
										CommonConcepts.GENLS.getID()))
						&& !isDisjoint(assertion.getArgs()[1], genlsTruth_,
								ontology)) {
					assertion = assertion.clone();
					assertion.makeParentageAssertion(TermStanding.COLLECTION);
					isaTruth_.add(CycConstants.FIRST_ORDER_COLLECTION
							.getConcept());
					genlsTruth_.add(assertion.getArgs()[1]);
					recordAssertion(assertion, x, y, ontology);
					return;
				}

				// If predicate is/can be isa, and is not disjoint with existing
				// isa
				// TODO Do not allow isaGenls SecondOrderCollections
				if (((!isaCollection_ && relation.equals(CycConstants.ISA_GENLS
						.getConcept())) || ontology.evaluate(null,
						CommonConcepts.GENLPREDS.getID(),
						relation.getIdentifier(), CommonConcepts.ISA.getID()))
						&& !isDisjoint(assertion.getArgs()[1], isaTruth_,
								ontology)) {
					assertion = assertion.clone();
					assertion.makeParentageAssertion(TermStanding.INDIVIDUAL);
					isaTruth_.add(assertion.getArgs()[1]);
					recordAssertion(assertion, x, y, ontology);
				}
			} else if (checkArgConstraints(assertion, ontology)) {
				recordAssertion(assertion, x, y, ontology);
			}
			return;
		}

		private void noteCompleted(int x, int y) {
			usedSeeds_[x][y] = true;
			if (!completed_[x]) {
				completed_[x] = true;
				completedWeight_ += getWeight(x, y);

				// Also mark any child collections
				int originalX = x;
				x++;
				while (x < assertionGrid_.length
						&& y < assertionGrid_[x].length
						&& assertionGrid_[x][y] == null) {
					completed_[x] = true;
					// If parent becomes used, so do all children
					Arrays.fill(usedSeeds_[x], true);
					x++;
				}

				// Mark parent collections as completed to disallow potential
				if (y > 0) {
					x = originalX;
					int nullY = y;
					// Find the null point
					while (assertionGrid_[x][nullY] != null) {
						if (nullY == 0)
							return;
						nullY--;
					}

					// Move backwards until all parents found
					while (x > 0 && assertionGrid_[x][0] == null) {
						// If value at nullY is not null (or out of bounds),
						// mark completed.
						x--;
						if (assertionGrid_[x].length <= nullY
								|| assertionGrid_[x][nullY] != null)
							completed_[x] = true;
					}
				}
			}
		}

		/**
		 * Records the assertion and checks for other assertions in the same
		 * queue with the same weight that can also be recorded.
		 * 
		 * @param assertion
		 *            The assertion to check.
		 * @param x
		 *            The x pos of the assertion in the grid.
		 * @param y
		 *            The y pos of the assertion in the grid.
		 * @param ontology
		 *            The ontology access.
		 */
		private void recordAssertion(DefiniteAssertion assertion, int x, int y,
				OntologySocket ontology) {
			noteCompleted(x, y);
			if (!allAssertions_.contains(assertion)) {
				allAssertions_.add(assertion);
				assertionWeights_.add(getWeight(x, y));
			}

			// Recurse down
			if (getWeight(x, y + 1) == getWeight(x, y))
				checkDisjointness(getAssertion(x, y + 1), x, y + 1, ontology);
		}

		@Override
		public int compareTo(DisjointCase arg0) {
			if (arg0 == null)
				return -1;
			if (equals(arg0))
				return 0;

			// Greater potential weight first
			int result = -Float.compare(getPotentialWeight(),
					arg0.getPotentialWeight());
			if (result != 0)
				return result;

			// Then closer to completion
			int numCompleted = 0;
			int numCompletedA = 0;
			for (int x = 0; x < completed_.length; x++) {
				numCompleted += completed_[x] ? 1 : 0;
				numCompletedA += arg0.completed_[x] ? 1 : 0;
			}
			result = -Integer.compare(numCompleted, numCompletedA);
			if (result != 0)
				return result;

			// Then higher row
			result = Integer.compare(caseRow_, arg0.caseRow_);
			if (result != 0)
				return result;

			// Otherwise by hash
			return Integer.compare(hashCode(), arg0.hashCode());
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			DisjointCase other = (DisjointCase) obj;
			if (isaCollection_ != other.isaCollection_)
				return false;
			if (caseRow_ != other.caseRow_)
				return false;
			if (!Arrays.equals(completed_, other.completed_))
				return false;
			return true;
		}

		public ArrayList<DefiniteAssertion> getAssertions() {
			return allAssertions_;
		}

		/**
		 * Returns the most optimistic possible weight for the case.
		 * 
		 * @return The weight of the case.
		 */
		public float getPotentialWeight() {
			if (completedWeight_ == -1)
				return -1;
			float value = completedWeight_ + calculatePotentialWeight();
			// Adjust the concept weight to ignore the existing assertions.
			// if (conceptIsaTruths_ == null && conceptGenlTruths_ == null)
			// value -= 1;
			return value / weightSum_ * standingWeight_;
		}

		public DefiniteAssertion getSeedAssertion() {
			return seedAssertion_;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + caseRow_;
			result = prime * result + Arrays.hashCode(completed_);
			if (isaCollection_)
				result = prime * result + 11;
			return result;
		}

		public boolean isCompleted() {
			if (caseRow_ == 0)
				return false;

			for (int x = 0; x < completed_.length; x++) {
				if (!completed_[x] && getAssertion(x, caseRow_ - 1) != null)
					return false;
			}
			return true;
		}

		/**
		 * Process a row of the assertion grid, checking disjointness of the
		 * assertions encountered in the row.
		 * 
		 * @param ontology
		 *            The ontology access.
		 */
		public void processRow(OntologySocket ontology) {
			// Run through the row, pairing with other assertions and checking
			// conjointness
			for (int x = 0; x < completed_.length; x++) {
				if (completed_[x])
					continue;
				DefiniteAssertion assertion = getAssertion(x, caseRow_);
				if (assertion == null)
					continue;
				checkDisjointness(assertion, x, caseRow_, ontology);
			}
			caseRow_++;
		}

		@Override
		public String toString() {
			if (completedWeight_ == -1)
				return "Invalid Disjoint Case.";
			if (isaCollection_)
				return "C:" + allAssertions_.toString();
			else
				return "I:" + allAssertions_.toString();
		}
	}

	private class SeedComparator implements Comparator<Pair<Integer, Integer>> {
		@Override
		public int compare(Pair<Integer, Integer> arg0,
				Pair<Integer, Integer> arg1) {
			// Then weight
			int result = -Float.compare(weightGrid_[arg0.objA_][arg0.objB_],
					weightGrid_[arg1.objA_][arg1.objB_]);
			if (result != 0)
				return result;

			// Then row
			result = Integer.compare(arg0.objB_, arg1.objB_);
			if (result != 0)
				return result;

			// Then column (last first)
			result = Integer.compare(arg0.objA_, arg1.objA_);
			if (result != 0)
				return result;
			return 0;
		}
	}

	/**
	 * Returns all informationless assertions (as though all taxonomic
	 * information was conflicting).
	 *
	 * @param concept
	 *            The concept to ground the informationless assertions to.
	 * @return A collection of definite assertions where all assertions are
	 *         informationless.
	 */
	public Collection<DefiniteAssertion> getInformationlessAssertions(
			OntologyConcept concept, OntologySocket ontology) {
		Collection<DefiniteAssertion> infoless = new HashSet<>();
		for (int x = 0; x < assertionGrid_.length; x++) {
			if (assertionGrid_[x][0] == null)
				continue;
			try {
				double bestWeight = -1;
				for (int y = 0; y < assertionGrid_[x].length; y++) {
					if (weightGrid_[x][y] < bestWeight)
						break;
					OntologyConcept relation = (OntologyConcept) assertionGrid_[x][y]
							.getRelation();
					if (!relation.equals(CycConstants.ISA_GENLS.getConcept())
							&& ontology.isInfoless(relation, true, true)) {
						infoless.add((DefiniteAssertion) assertionGrid_[x][y]);
						bestWeight = weightGrid_[x][y];
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return infoless;
	}

	/**
	 * Returns ALL definite assertions from the assertion grid. Note that
	 * inferred truths are not included.
	 *
	 * @return All assertions in the grid.
	 */
	public Collection<DefiniteAssertion> getAllAssertions() {
		Collection<DefiniteAssertion> allAssertions = new HashSet<>();
		for (int x = 0; x < assertionGrid_.length; x++) {
			for (int y = 0; y < assertionGrid_[0].length; y++) {
				MinedAssertion assertion = getAssertion(x, y);
				if (assertion != null && assertion instanceof DefiniteAssertion) {
					// Expand isaGenls
					if (assertion.getRelation().equals(
							CycConstants.ISA_GENLS.getConcept())) {
						allAssertions.add(new DefiniteAssertion(
								CycConstants.ISA.getConcept(), assertion
										.getMicrotheory(), assertion
										.getProvenance(),
								((DefiniteAssertion) assertion).getArgs()));
						allAssertions.add(new DefiniteAssertion(
								CycConstants.GENLS.getConcept(), assertion
										.getMicrotheory(), assertion
										.getProvenance(),
								((DefiniteAssertion) assertion).getArgs()));
					} else
						allAssertions.add((DefiniteAssertion) assertion);
				}
			}
		}
		return allAssertions;
	}
}
