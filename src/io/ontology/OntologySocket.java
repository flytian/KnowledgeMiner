/*******************************************************************************
 * Copyright (C) 2013 University of Waikato, Hamilton, New Zealand
 ******************************************************************************/
package io.ontology;

import graph.core.CommonConcepts;
import graph.inference.CommonQuery;
import graph.module.NLPToStringModule;
import graph.module.NLPToSyntaxModule;
import io.KMAccess;
import io.KMSocket;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import cyc.CycConstants;
import cyc.OntologyConcept;

public abstract class OntologySocket extends KMSocket {
	public static final int NON_EXISTENT_ID = -56434;
	protected boolean ephemeral_;
	protected boolean forceConstraints_;

	public OntologySocket(KMAccess<? extends KMSocket> access) {
		super(access);
	}

	public OntologySocket(KMAccess<? extends KMSocket> access, int port) {
		super(access, port);
	}

	protected abstract boolean parseProofResult(String result);

	public abstract int assertToOntology(String microtheory,
			Object... arguments);

	public int createAndAssert(String concept, Object type, String comment) {
		int id = createConcept(concept);
		assertToOntology(CycConstants.UNIVERSAL_VOCAB_MT.getConceptName(),
				CommonConcepts.ISA.getID(), concept, type);
		assertToOntology(CycConstants.UNIVERSAL_VOCAB_MT.getConceptName(),
				CommonConcepts.COMMENT.getID(), concept, "\"" + comment + "\"");
		return id;
	}

	public abstract int createConcept(String name);

	public void createMicrotheory(String mtName, String comment,
			String parentMt, LinkedList<String> genlsMt) {
		createAndAssert(mtName, parentMt, comment);
		for (String genlMt : genlsMt) {
			assertToOntology(CycConstants.UNIVERSAL_VOCAB_MT.getConceptName(),
					"genlMt", mtName, genlMt);
		}
	}

	public abstract String dagToText(Object dagObject, String type,
			boolean markup);

	public boolean evaluate(String microtheory, Object... queryArgs) {
		String result = query(false, queryArgs);
		return parseProofResult(result);
	}

	public abstract String findConceptByID(int id);

	public abstract Collection<OntologyConcept> findConceptByName(String name,
			boolean caseSensitive, boolean exactString, boolean allowAliases);

	public abstract String[] findEdgeByID(int id);

	public abstract int findEdgeIDByArgs(Object... edgeArgs);

	public abstract Collection<String[]> findEdges(Object... indexArgs);

	public abstract Collection<OntologyConcept> findFilteredConceptByName(
			String name, boolean caseSensitive, boolean exactString,
			boolean allowAliases, Object... queryArgs);

	public boolean genls(Object instance, Object collection) {
		return evaluate(null, CommonConcepts.GENLS.getID(), instance,
				collection);
	}

	public abstract Collection<String[]> getAllAssertions(Object concept,
			int argPos, Object... exceptPredicates);

	public abstract int getConceptID(String term);

	public boolean getEphemeral() {
		return ephemeral_;
	}

	public abstract int getNextEdge(int id);

	public abstract int getNextNode(int id);

	public abstract int getNumConstants();

	public abstract int getPrevEdge(int id);

	public abstract int getPrevNode(int id);

	public abstract String getProperty(Object nodeEdge, boolean isNode,
			String propKey);

	public abstract Collection<String> getSynonyms(Object term);

	public boolean inOntology(OntologyConcept concept) throws Exception {
		return inOntology(concept.getConceptName());
	}

	public boolean inOntology(String term) throws Exception {
		// Checking for functions
		if (term.startsWith("(") && term.endsWith(")")
				&& inOntology(term.substring(1, term.indexOf(" "))))
			return true;
		return !findConceptByName(term, true, true, false).isEmpty();
	}

	public boolean isa(Object instance, Object collection) {
		return evaluate(null, CommonConcepts.ISA.getID(), instance, collection);
	}

	public boolean isaCollection(OntologyConcept concept) {
		return isa(concept.getIdentifier(), CommonConcepts.COLLECTION.getID());
	}

	public boolean isInfoless(OntologyConcept concept, boolean ignorePrimitive,
			boolean ignoreString) throws Exception {
		if (!inOntology(concept.getConceptName()))
			return true;

		String ident = concept.getIdentifier();
		if (isa(ident, CommonConcepts.PREDICATE.getID())) {
			// For each of its arguments TODO Assume two
			for (int i = 1; i <= 2; i++) {
				Collection<OntologyConcept> argIsa = quickQuery(
						CommonQuery.MINARGNISA, ident, "'" + i);
				for (OntologyConcept oc : argIsa)
					if (validConstraint(oc, ignorePrimitive, ignoreString))
						return false;

				Collection<OntologyConcept> argGenls = quickQuery(
						CommonQuery.MINARGNGENL, ident, "'" + i);
				for (OntologyConcept oc : argGenls)
					if (validConstraint(oc, false, false))
						return false;
			}
			return true;
		}

		if (getAllAssertions(concept.getIdentifier(), -1).size() > 1)
			return false;

		// Need more than Individual as a parent
		Collection<OntologyConcept> parentCols = quickQuery(CommonQuery.MINISA,
				concept.getIdentifier());
		if (parentCols.size() > 1)
			return false;
		else if (parentCols.size() == 0)
			return true;
		else {
			OntologyConcept parent = parentCols.iterator().next();
			if (parent.getIdentifier().equals(
					CommonConcepts.INDIVIDUAL.getNodeName())
					|| parent.getIdentifier().equals(
							CommonConcepts.COLLECTION.getNodeName())
					|| parent.getIdentifier().equals(
							CommonConcepts.THING.getNodeName())
					|| parent.getIdentifier().equals(
							CommonConcepts.FUNCTION.getNodeName())
					|| parent.getIdentifier().equals(
							CommonConcepts.PREDICATE.getNodeName())
					|| parent.getIdentifier().equals(
							CommonConcepts.BINARY_PREDICATE.getNodeName()))
				return true;
		}
		return false;
	}

	/**
	 * Checks if a constraint is valid or infoless.
	 *
	 * @param constraint
	 *            The constraint to check
	 * @param ignorePrimitive
	 *            If primitive constraints should be ignored.
	 * @param ignoreString
	 *            If string constraints should be ignored
	 * @return True if the constraint is valid.
	 */
	private boolean validConstraint(OntologyConcept constraint,
			boolean ignorePrimitive, boolean ignoreString) {
		if (constraint.getID() == CommonConcepts.THING.getID())
			return false;
		if (ignorePrimitive && genls(constraint, "Number-General"))
			return false;
		if (ignoreString
				&& genls(constraint, CommonConcepts.CHARACTER_STRING.getID()))
			return false;
		return true;
	}

	public boolean isValidArg(Object predicate, Object concept, int argNum) {
		if (predicate.equals(CycConstants.ISA_GENLS.getConcept()
				.getIdentifier())) {
			if (concept.toString().startsWith("\"")
					&& concept.toString().endsWith("\""))
				return false;
			if (argNum == 1 || isa(concept, CommonConcepts.COLLECTION.getID()))
				return true;
			return false;
		}
		if (!isa(predicate, "Relation"))
			return false;

		return true;
	}

	public abstract List<String> justify(Object... assertionArgs);

	public abstract String query(boolean proveFail, Object... queryArgs);

	public abstract Collection<OntologyConcept> quickQuery(CommonQuery cq,
			Object... args);

	public abstract boolean removeConcept(Object name);

	public abstract void setEphemeral(boolean b);

	public abstract void setForceConstraints(boolean b);

	public abstract void setProperty(Object nodeEdge, boolean isNode,
			String propKey, String propValue);

	public abstract void removeProperty(Object nodeEdge, boolean isNode,
			String propKey);

	public String toNormalFormat(String term) {
		return NLPToStringModule.conceptToPlainText(term);
	}

	public String toOntologyFormat(String words) {
		return NLPToSyntaxModule.textToConcept(words);
	}

	public abstract boolean unassert(String microtheory, int assertionID,
			boolean forceRemove);

	public abstract boolean validConstantName(String cycTerm);

	public abstract boolean conceptHasChildren(Object concept);
}
