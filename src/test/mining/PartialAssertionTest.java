package test.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import io.ResourceAccess;
import io.ontology.OntologySocket;
import io.resources.DBPediaAccess;
import io.resources.DBPediaNamespace;
import io.resources.WikipediaSocket;

import java.util.ArrayList;
import java.util.Collection;

import knowledgeMiner.KnowledgeMiner;
import knowledgeMiner.mapping.CycMapper;
import knowledgeMiner.mining.AssertionQueue;
import knowledgeMiner.mining.PartialAssertion;
import knowledgeMiner.mining.TextMappedConcept;
import knowledgeMiner.mining.dbpedia.DBMappedConcept;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import com.hp.hpl.jena.rdf.model.RDFNode;

import cyc.CycConstants;
import cyc.MappableConcept;
import cyc.OntologyConcept;

public class PartialAssertionTest {

	private static CycMapper mapper_;
	private static WikipediaSocket wmi_;
	private static OntologySocket ontology_;

	@BeforeClass
	public static void setUp() throws Exception {
		wmi_ = ResourceAccess.requestWikipediaSocket();
		ontology_ = ResourceAccess.requestOntologySocket();
		mapper_ = new CycMapper();
		mapper_.initialise();
		KnowledgeMiner.getInstance();
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testInstantiate() {
		fail("Not yet implemented");
	}

	@Test
	public void testExpand() {
		PartialAssertion pa;
		AssertionQueue aq;
		MappableConcept coreConcept = new TextMappedConcept("CORE", false,
				false);
		Collection<MappableConcept> excluded = new ArrayList<>();
		excluded.add(coreConcept);
		// Single entity resolution
		pa = new PartialAssertion(CycConstants.ISA_GENLS.getConcept(), null,
				coreConcept, new TextMappedConcept("actor", false, false));
		aq = pa.expand(excluded, mapper_, ontology_, wmi_);
		assertEquals(aq.size(), 1);
		assertTrue(aq
				.contains(new PartialAssertion(CycConstants.ISA_GENLS
						.getConcept(), null, coreConcept, new OntologyConcept(
						"Actor"))));

		// Multiple entity resolution
		pa = new PartialAssertion(CycConstants.ISA_GENLS.getConcept(), null,
				coreConcept, new TextMappedConcept("model", false, false));
		aq = pa.expand(excluded, mapper_, ontology_, wmi_);
		assertEquals(aq.size(), 6);
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"ProfessionalModel"))));
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"FashionModel"))));
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"DisplayingSomething"))));
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"Model-Artifact"))));
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"(MakingFn Model-Artifact)"))));
		assertTrue(aq.contains(new PartialAssertion(CycConstants.ISA_GENLS
				.getConcept(), null, coreConcept, new OntologyConcept(
				"ProductTypeByBrand"))));

		// Test birthPlaces
		DBMappedConcept placeBirthPred = new DBMappedConcept(
				DBPediaAccess.selectSingularQuery("?pob",
						DBPediaNamespace.DBPEDIA.format("Uma_Thurman")
								+ " ?pob \"Boston, Massachusetts, U.S.\"@en"),
				true);
		pa = new PartialAssertion(placeBirthPred, null, coreConcept,
				new TextMappedConcept("Boston, Massachusetts", true, true));
		aq = pa.expand(excluded, mapper_, ontology_, wmi_);
	}

}
