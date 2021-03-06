package tools;

import graph.core.CommonConcepts;
import graph.inference.CommonQuery;
import graph.module.ARFFData;
import io.ResourceAccess;
import io.ontology.DAGSocket;
import io.ontology.OntologySocket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import knowledgeMiner.ConceptMiningTask;
import knowledgeMiner.mining.HeuristicProvenance;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import util.Pair;
import util.UtilityMethods;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import cyc.OntologyConcept;

/**
 * A class solely for asserting the disjoint data to WEKA through various
 * avenues.
 *
 * @author Sam Sarjant
 */
public class WEKAAsserter {
	private final static Logger logger_ = LoggerFactory
			.getLogger(ConceptMiningTask.class);
	public static final File ARFF_LOCATION = new File("lib/disjointARFF.arff");
	private Instances arffData_;
	private Classifier classifier_;
	private ThreadLocal<Classifier> threadClassifier_;
	private Collection<String> assertedEdges_;
	public boolean generalising_ = true;
	private static final int TRUTH_CONJOINT = -1;
	private static final int TRUTH_DISJOINT = 1;
	private int tp_ = 0; // Predicted disjoint for disjoint
	private int fp_ = 0; // Predicted disjoint for conjoint
	private int tn_ = 0; // Predicted conjoint for conjoint
	private int fn_ = 0; // Predicted conjoint for disjoint
	private BufferedReader in_;
	private int count_;

	public WEKAAsserter(File classifierFile, DAGSocket ontology) {
		try {
			ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
					classifierFile));

			arffData_ = new Instances(new FileReader(ARFF_LOCATION));
			arffData_.setClassIndex(arffData_.numAttributes() - 1);
			classifier_ = (Classifier) ois.readObject();
			ResourceAccess.newInstance(-1);
			ois.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		assertedEdges_ = new ArrayList<>();

		threadClassifier_ = new ThreadLocal<Classifier>() {
			@Override
			protected Classifier initialValue() {
				try {
					return AbstractClassifier.makeCopy(classifier_);
				} catch (Exception e) {
					e.printStackTrace();
				}
				return null;
			}
		};

		in_ = new BufferedReader(new InputStreamReader(System.in));
	}

	/**
	 * Parses an instance from a ARFF-style instance string.
	 *
	 * @param instanceStr
	 *            The string to parse.
	 * @return An instance according to the arffData.
	 */
	private Instance parseInstance(String instanceStr) {
		Collection<Character> quoted = new ArrayList<>();
		quoted.add('\'');
		String[] split = UtilityMethods.splitToArray(instanceStr, ',', quoted);

		// Form the instance
		Instance instance = new DenseInstance(ARFFData.values().length);
		instance.setDataset(arffData_);
		for (int i = 0; i < split.length; i++) {
			if (!split[i].isEmpty()) {
				if (arffData_.attribute(i).isNumeric())
					instance.setValue(i, Double.parseDouble(split[i]));
				else if (arffData_.attribute(i).isNominal()) {
					if (split[i].equals("?"))
						instance.setMissing(i);
					else {
						int relationIndex = arffData_.attribute(i)
								.indexOfValue(split[i]);
						instance.setValue(i, relationIndex);
					}
				} else {
					instance.setValue(i, split[i]);
				}
			}
		}
		return instance;
	}

	/**
	 * Asserts disjoints from a file. Though filters them first to avoid
	 * disjoint/conjoint clashes.
	 *
	 * @param disjointFile
	 *            The file to process.
	 * @param predictionThreshold
	 *            The threshold to assert at.
	 * @param asserting
	 *            If just processing or asserting.
	 * @throws IOException
	 *             Should something go awry...
	 */
	public void assertWEKAClassifiedDisjointFile(File disjointFile,
			float predictionThreshold, boolean asserting) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(disjointFile));
		OntologySocket ontology = ResourceAccess.requestOntologySocket();

		Map<String, String> disjoints = new HashMap<>();
		Collection<String> conjoints = new HashSet<>();

		// Read the disjoint assertions
		String line = reader.readLine();
		// Skip headers
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty())
				break;
			ArrayList<String> split = UtilityMethods.split(line, ',');
			boolean isDisjoint = split.get(2).equals("1:disjoint");
			float predictionWeight = Float.parseFloat(split.get(4));
			String firstArg = split.get(8);
			String secondArg = split.get(9);
			String assertionString = "(disjointWith " + firstArg + " "
					+ secondArg + ")";
			assertionString = assertionString.replaceAll("'(\\(.+?\\))'", "$1");
			assertionString = assertionString.replaceAll("\\\\'", "'");
			String dataString = split.get(5) + "," + split.get(6) + ","
					+ split.get(7);

			// Add it to disjoints if high enough weight and not contradicted by
			// conjoint.
			if (isDisjoint) {
				if (predictionWeight >= predictionThreshold) {
					if (!conjoints.contains(assertionString))
						disjoints.put(assertionString, dataString);
					else
						logger_.info("{} contradicted by conjoint edge",
								assertionString);
				}
			} else {
				conjoints.add(assertionString);
				if (disjoints.containsKey(assertionString)) {
					disjoints.remove(assertionString);
					logger_.info("{} contradicted by conjoint edge",
							assertionString);
				}
			}
		}
		reader.close();

		BufferedWriter writer = new BufferedWriter(new FileWriter(new File(
				disjointFile.getPath() + "out")));
		for (Map.Entry<String, String> entry : disjoints.entrySet()) {
			String provenance = entry.getValue();
			writer.write(entry.getKey() + "," + provenance + "\n");
			if (asserting) {
				int id = ontology.assertToOntology(
						PairwiseDisjointExperimenter.EXPERIMENT_MICROTHEORY,
						entry.getKey());
				if (id != -1) {
					logger_.info("{} asserted", entry.getKey());
					ontology.setProperty(id, false,
							HeuristicProvenance.PROVENANCE, provenance);
				} else {
					logger_.info("{} rejected", entry.getKey());
					System.err.println("Could not assert " + entry.getKey());
				}
			}
		}
		writer.close();
	}

	/**
	 * Classify two concepts with a given classifier. Returns the classifcation
	 * result.
	 *
	 * @param conceptA
	 *            The first concept to check.
	 * @param conceptB
	 *            The second concept to check.
	 * @param relation
	 *            The relation connecting the two concepts.
	 * @param dagSocket
	 *            The DAG socket.
	 * @param instances
	 *            The instances of the data.
	 * @param classifier
	 *            The classifier to use.
	 * @return A string reflecting the result of the classification.
	 * @throws Exception
	 *             Should something go awry...
	 */
	public String classify(String conceptA, String conceptB, String relation,
			DAGSocket ontology) throws Exception {
		String result = ontology.command("asWeka", conceptA + " " + conceptB
				+ " " + relation, false);
		String[] lines = result.split("\\|");
		boolean isDisjoint = true;
		double maxWeight = 0;
		for (int j = 1; j <= Integer.parseInt(lines[0]); j++) {
			Instance instance = parseInstance(lines[j]);

			// Classify
			double classify = classifier_.classifyInstance(instance);
			if (classify < 0.5) {
				// Disjoint
				maxWeight = Math.max(1 - classify, maxWeight);
			} else {
				// Conjoint
				isDisjoint = false;
				break;
			}
		}
		if (isDisjoint) {
			if (maxWeight == 0)
				return "No results found";
			return "disjoint:" + maxWeight;
		} else
			return "conjoint";
	}

	/**
	 * Processes a file of unknown pairs of concepts by using a classifier to
	 * determine if they are disjoint. If so, attempts to lift the disjointness
	 * higher up to assert a more generalised version.
	 *
	 * @param unknownsFile
	 *            The instances to classify as disjoint or not.
	 * @param predictionThreshold
	 *            The threshold at which edges are allowed to be classified as
	 *            disjoint.
	 * @param asserting
	 *            If asserting the edges or storing them.
	 * @param classifier
	 *            The classifier to classify.
	 * @param ontology
	 *            The ontology access.
	 */
	public void processUnknowns(File unknownsFile, float predictionThreshold,
			boolean asserting) throws Exception {
		// For every unknown instance
		BufferedReader in = new BufferedReader(new FileReader(unknownsFile));
		String input = null;
		ExecutorService executor = Executors.newFixedThreadPool(Runtime
				.getRuntime().availableProcessors());
		while ((input = in.readLine()) != null) {
			if (input.startsWith("@") || input.trim().isEmpty())
				continue;
			executor.execute(new GeneraliseTask(input, predictionThreshold));
		}
		in.close();
		executor.shutdown();
		try {
			executor.awaitTermination(24, TimeUnit.HOURS);
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		System.out.println("Generalisation stats:");
		System.out.println("TP: " + tp_ + " FN: " + fn_);
		System.out.println("FP: " + fp_ + " TN: " + tn_);
	}

	public Collection<String> getAssertions() {
		return assertedEdges_;
	}

	private synchronized void addAssertion(String data) {
		assertedEdges_.add(data);
	}

	public class GeneraliseTask implements Runnable {
		private String startInstance_;
		private float predictionThreshold_;
		private DAGSocket ontology_;
		private Classifier thisClassifier_;

		public GeneraliseTask(String input, float predictionThreshold) {
			startInstance_ = input;
			predictionThreshold_ = predictionThreshold;
		}

		@Override
		public void run() {
			ontology_ = (DAGSocket) ResourceAccess.requestOntologySocket();
			thisClassifier_ = threadClassifier_.get();
			try {
				Map<Pair<String, String>, Boolean> examined = new HashMap<>();
				recursiveGeneralise(startInstance_, 0, null, examined);
			} catch (Exception e) {
				e.printStackTrace();
			}
			count_++;
			if ((count_ % 1000) == 0) {
				if ((count_ % 10000) == 0) {
					System.out.print(count_ + " ");
					if (generalising_) {
						System.out.println("\nGeneralisation stats:");
						System.out.println("TP: " + tp_ + " FN: " + fn_);
						System.out.println("FP: " + fp_ + " TN: " + tn_);
					}
				} else
					System.out.print(".");
			}
		}

		/**
		 * Recursively works up the graph, testing whether a disjoint
		 * relationship can be generalised to one or more of it's parent
		 * collections.
		 *
		 * @param instanceStr
		 *            The pair of collections to test, in instance string
		 *            format.
		 * @param depth
		 *            Depth of the recursion
		 * @param provenance
		 *            The original un-generalised term to note in the provenance
		 * @param examined
		 *            The already examined disjoints.
		 * @param instances
		 *            The ARFF structure.
		 * @param ontology
		 *            The ontology access.
		 * @param classifier
		 *            The classifier for classifying the instances.
		 * @return True if this edge is already proved (transitively or
		 *         directly) as disjoint, false otherwise.
		 */
		public boolean recursiveGeneralise(String instanceStr, int depth,
				String provenance, Map<Pair<String, String>, Boolean> examined) {
			logger_.trace("Recursive Generalisation {}", instanceStr);
			String relation = null;
			String textA = null;
			String textB = null;
			String conceptA = null;
			String conceptB = null;
			Pair<String, String> conceptPair = null;
			Instance instance = null;
			try {
				instance = parseInstance(instanceStr);
				relation = instance.stringValue(ARFFData.RELATION.ordinal());
				textA = instance.stringValue(ARFFData.ARG1.ordinal());
				textB = instance.stringValue(ARFFData.ARG2.ordinal());
				conceptA = instance.stringValue(ARFFData.DISAMB1.ordinal());
				conceptB = instance.stringValue(ARFFData.DISAMB2.ordinal());
				if (conceptA.startsWith("'"))
					conceptA = UtilityMethods.shrinkString(conceptA, 1);
				if (conceptB.startsWith("'"))
					conceptB = UtilityMethods.shrinkString(conceptB, 1);

				if (conceptA.compareTo(conceptB) < 0)
					conceptPair = new Pair<String, String>(conceptA, conceptB);
				else
					conceptPair = new Pair<String, String>(conceptB, conceptA);

				if (provenance == null)
					provenance = textA + " " + relation + " " + textB
							+ "=>(disjointWith " + conceptA + " " + conceptB
							+ ")";
			} catch (Exception e) {
				logger_.error("Error classifying {}", instanceStr);
				e.printStackTrace();
				System.exit(1);
			}

			// Check if already examined
			if (examined.containsKey(conceptPair))
				return examined.get(conceptPair);

			String prefix = StringUtils.repeat(' ', depth * 2)
					+ "(disjointWith " + conceptA + " " + conceptB + ")";

			// First check if they're already disjoint
			String result = ontology_.query(true,
					CommonConcepts.DISJOINTWITH.getID(), conceptA, conceptB);
			logger_.trace("Disjoint {}", result);
			int truthState = 0;
			if (result.startsWith("1|T")) {
				truthState = TRUTH_DISJOINT;
				examined.put(conceptPair, true);
				logger_.info(prefix + " - Already disjoint!");
				return true;
			} else if (result.startsWith("0|F")) {
				truthState = TRUTH_CONJOINT;
				examined.put(conceptPair, false);
				logger_.info(prefix + " - Already conjoint!");
				return false;
			}

			// Classify
			try {
				double[] predArray = thisClassifier_
						.distributionForInstance(instance);
				double classification = predArray[0];
				if (classification >= predictionThreshold_) {
					if (truthState != 0) {
						if (truthState == TRUTH_DISJOINT) {
							tp_++;
							return true;
						}
						if (truthState == TRUTH_CONJOINT) {
							fp_++;
							return false;
						}
					}
					logger_.info(prefix + " DISJOINT");
					boolean shouldAssert = true;
					// Generalise A and B until conjoint
					if (generalising_) {
						shouldAssert &= recurseIntoParents(relation, conceptA,
								conceptB, depth, provenance, examined);
						shouldAssert &= recurseIntoParents(relation, conceptB,
								conceptA, depth, provenance, examined);
					}
					// If this is the highest disjoint assertion, make the a
					if (shouldAssert) {
						assertDisjoint(conceptA, conceptB, depth, provenance);
					}
				} else {
					if (truthState != 0) {
						if (truthState == TRUTH_DISJOINT) {
							fn_++;
							return true;
						}
						if (truthState == TRUTH_CONJOINT) {
							tn_++;
							return false;
						}
					}
					logger_.info(prefix + " NOT DISJOINT");
					examined.put(conceptPair, false);
					return false;
				}
			} catch (Exception e) {
				logger_.error("Error classifying {}", instanceStr);
				e.printStackTrace();
				System.exit(1);
			}
			examined.put(conceptPair, true);
			return true;
		}

		private void assertDisjoint(String conceptA, String conceptB,
				int depth, String provenance) {
			// Remove delimiting characters
			conceptA = conceptA.replaceAll("\\\\'", "'");
			conceptB = conceptB.replaceAll("\\\\'", "'");
			
			int id = ontology_.assertToOntology(
					PairwiseDisjointExperimenter.EXPERIMENT_MICROTHEORY,
					CommonConcepts.DISJOINTWITH.getID(), conceptA, conceptB);
			logger_.trace("Asserted {}", id);
			if (id != -1) {
				provenance = provenance + "_STEPS=" + depth;
				ontology_.setProperty(id, false,
						HeuristicProvenance.PROVENANCE, provenance);
				addAssertion("(disjointWith " + conceptA + " " + conceptB
						+ ")\t" + provenance);
				logger_.info("Asserted (disjointWith {} {})", conceptA,
						conceptB);
			} else {
				logger_.info("Error asserting (disjointWith {} {})", conceptA,
						conceptB);
			}
		}

		/**
		 * Check if the parents of a concept are disjoint to the other concept.
		 * If so, there is no need to assert this pair.
		 *
		 * @param relation
		 *            The relation defining the original pair.
		 * @param generalisedConcept
		 *            The concept to be generalised.
		 * @param otherConcept
		 *            The other concept to check against.
		 * @param depth
		 *            The depth of the recursion.
		 * @param provenance
		 *            The original edge that spawned this generalisation.
		 * @param examined
		 *            The map of examined pairs.
		 * @param instances
		 *            The ARFF data.
		 * @param classifier
		 *            The classifier to classify the pairs.
		 * @param ontology
		 *            The ontology access.
		 * @return False if the parents were more general and this edge need not
		 *         be created.
		 * @throws Exception
		 *             Should something go awry...
		 */
		private boolean recurseIntoParents(String relation,
				String generalisedConcept, String otherConcept, int depth,
				String provenance, Map<Pair<String, String>, Boolean> examined)
				throws Exception {
			Collection<OntologyConcept> parents = ontology_.quickQuery(
					CommonQuery.MINGENLS, generalisedConcept);
			logger_.trace("Parents {}", parents.toString());
			// For every min-parent, try to generalise upwards
			boolean shouldAssert = true;
			for (OntologyConcept parent : parents) {
				String parentInstStr = ontology_.command("asWeka",
						parent.getIdentifier() + " " + otherConcept + " "
								+ relation, false);
				logger_.trace("AsWEKA {}", parentInstStr);
				String[] split = parentInstStr.split("\\|");
				// TODO I don't like how this is handled - it should ensure all
				// are disjoint.
				// If any of the parent's edges create generalisations, set
				// should assert to false.
				for (int i = 1; i <= Integer.parseInt(split[0]); i++) {
					if (recursiveGeneralise(split[i], depth + 1, provenance,
							examined)) {
						shouldAssert = false;
						break;
					}
				}
			}
			return shouldAssert;
		}
	}

	public static void main(String[] args) throws Exception {
		DAGSocket ontology = (DAGSocket) ResourceAccess.requestOntologySocket();
		WEKAAsserter wekaAsserter = new WEKAAsserter(new File(args[0]),
				ontology);

		// If there is another (numerical) arg
		if (args.length == 2) {
			wekaAsserter.randomTest(Integer.parseInt(args[1]), ontology);
			System.exit(0);
		}

		String input = null;
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		do {
			System.out.println("Enter '<relation> <conceptA> <conceptB>'");
			input = in.readLine();
			if (!input.isEmpty()) {
				// Receive input
				String[] split = UtilityMethods.splitToArray(input, ' ');
				String relation = split[0];
				String conceptA = split[1];
				String conceptB = split[2];

				System.out.println(wekaAsserter.classify(conceptA, conceptB,
						relation, ontology));
			}
		} while (!input.isEmpty());
	}

	/**
	 * Test N random pairs of concepts against the ontology's ground truth. A
	 * pair only counts if it is already known to be disjoint/conjoint.
	 *
	 * @param numPairs
	 *            The number of pairs to test
	 * @param ontology
	 *            The ontology access.
	 * @throws Exception
	 *             Should something go awry...
	 */
	public void randomTest(int numPairs, DAGSocket ontology) throws Exception {
		int[][] stats = new int[2][2];
		boolean promptUser = true;

		int count = 0;
		Set<String> complete = new HashSet<>();
		while (count < numPairs) {
			// Get two random collections
			String aID = getCollection(ontology);
			String bID = getCollection(ontology);
			while (bID.equals(aID))
				bID = getCollection(ontology);

			// Check if completed
			String ordered = (aID.compareTo(bID) < 0) ? aID + bID : bID + aID;
			if (complete.contains(ordered))
				continue;
			else
				complete.add(ordered);

			// Check disjoint
			String result = ontology.command("query", "T ("
					+ CommonConcepts.DISJOINTWITH.getID() + " " + aID + " "
					+ bID + ")", false);
			// if (result.startsWith("0|F")) {
			boolean actual = false;
			if (result.startsWith("1|T"))
				actual = true;
			else if (result.startsWith("0|F"))
				actual = false;
			else if (promptUser) {
				System.out.println(ontology.dagToText("(disjointWith " + aID
						+ " " + bID + ")", "Q", true)
						+ " (T/F)");
				String input = in_.readLine();
				if (input.equalsIgnoreCase("T"))
					actual = true;
				else if (input.equalsIgnoreCase("F"))
					actual = false;
				else
					continue;
			} else
				continue;

			// Disjoint
			String classification = classify(aID, bID, "IsA", ontology);
			if (classification.equals("No results found"))
				continue;
			boolean classifyB = classification.startsWith("disjoint");
			String aStr = ontology.findConceptByID(Integer.parseInt(aID));
			String bStr = ontology.findConceptByID(Integer.parseInt(bID));
			System.out.println((actual == classifyB) + ": " + classification
					+ " " + aStr + " " + bStr);

			// Note the stat
			int x = classification.startsWith("disjoint") ? 0 : 1;
			int y = actual ? 0 : 1;
			stats[x][y]++;
			count++;
		}

		// Print the stats
		System.out.println("d\tc <- Classified as:");
		System.out.println(stats[0][0] + "\t" + stats[1][0] + " d");
		System.out.println(stats[0][1] + "\t" + stats[1][1] + " c");
	}

	/**
	 * Returns a random collection from the ontology.
	 *
	 * @param ontology
	 *            The ontology access.
	 * @return A random collection
	 * @throws Exception
	 */
	private String getCollection(DAGSocket ontology) throws Exception {
		while (true) {
			String randID = ontology.command("randomnode", "", false);
			if (ontology.evaluate(null, CommonConcepts.ISA.getID(), randID,
					CommonConcepts.COLLECTION.getID()))
				return randID;
		}
	}
}
