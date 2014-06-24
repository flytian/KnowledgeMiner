/*******************************************************************************
 * Copyright (C) 2013 University of Waikato, Hamilton, New Zealand
 ******************************************************************************/
package test;

import io.ResourceAccess;
import io.resources.WMISocket;

import java.io.IOException;

import knowledgeMiner.ConceptMiningTask;
import knowledgeMiner.ConceptModule;
import knowledgeMiner.KnowledgeMiner;
import knowledgeMiner.mining.wikipedia.FirstSentenceMiner;

import org.junit.BeforeClass;
import org.junit.Test;

import cyc.OntologyConcept;

/**
 * 
 * @author Sam Sarjant
 */
public class KnowledgeMinerTest {
	private static KnowledgeMiner km_;
	private static WMISocket wmi_;

	/**
	 * 
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUp() throws Exception {
		km_ = KnowledgeMiner.getInstance();
		wmi_ = ResourceAccess.requestWMISocket();
		FirstSentenceMiner.wikifyText_ = true;
	}

	@Test
	public void testProcessArticle() throws IOException {
		int article = -1;
		ConceptModule cm = null;
		article = wmi_.getArticleByTitle("Alabama");
		cm = new ConceptModule(article);
		km_.processConcept(new ConceptMiningTask(cm));
		
		article = wmi_.getArticleByTitle("Abraham Lincoln");
		cm = new ConceptModule(article);
		km_.processConcept(new ConceptMiningTask(cm));
		
		article = wmi_.getArticleByTitle("Uma Thurman");
		cm = new ConceptModule(article);
		km_.processConcept(new ConceptMiningTask(cm));

		article = wmi_.getArticleByTitle("Pavel Roman");
		cm = new ConceptModule(article);
		km_.processConcept(new ConceptMiningTask(cm));
	}

	/**
	 * Test method for
	 * {@link knowledgeMiner.KnowledgeMiner#processTerm(java.lang.String)}.
	 * 
	 * @throws IOException
	 */
	@Test
	public void testProcessConcept() throws IOException {
		// KnowledgeMiner.miningChildren_ = false;
		ConceptModule cm = null;
		cm = new ConceptModule(new OntologyConcept("Fruit"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("AshTree"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Cyclist"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(wmi_.getArticleByTitle("Christa McAuliffe"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(
				wmi_.getArticleByTitle("Howard Jones (musician)"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Cyclist"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(wmi_.getArticleByTitle("Tooth"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Proctologist"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Urologist"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Android"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Date"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Veterinarian"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Mountain"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("PolishPerson"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("Konin-ProvincePoland"));
		km_.processConcept(new ConceptMiningTask(cm));

		cm = new ConceptModule(new OntologyConcept("PhoenicianLanguage"));
		km_.processConcept(new ConceptMiningTask(cm));
	}
}
