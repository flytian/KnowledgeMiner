/*******************************************************************************
 * Copyright (C) 2013 University of Waikato, Hamilton, New Zealand
 ******************************************************************************/
package knowledgeMiner.preprocessing;

import io.IOManager;
import io.ResourceAccess;
import io.ontology.OntologySocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import util.UtilityMethods;
import cyc.OntologyConcept;
import cyc.CycConstants;

/**
 * The main class for running the preprocessing
 * 
 * @author Sam Sarjant
 */
public class CycPreprocessor {
	private static final File PREPROCESSED = new File("preprocessed.txt");
	private List<Preprocessor> preprocessors_;
	private int count_;
	private long startTime_;
	private int total_;

	/**
	 * Constructor for a new CycPreprocessor
	 * 
	 */
	public CycPreprocessor() {
		ResourceAccess.newInstance();
		preprocessors_ = new ArrayList<>(3);
		preprocessors_.add(new RemoveUseless());
		preprocessors_.add(new UglyString());
	}

	public void preprocess(ThreadPoolExecutor executor) throws Exception {
		System.out.println("Preprocessing KM. Please wait.");

		OntologySocket cyc = ResourceAccess.requestOntologySocket();

		// Check for preprocessed file
		if (PREPROCESSED.exists()) {
			loadPreprocessCommands(cyc);
			return;
		}

		count_ = 0;
		startTime_ = System.currentTimeMillis();

		int count = 0;
		total_ = cyc.getNumConstants();
		int index = 0;
		while (count < total_) {
			// Read in a constant
			String constant = cyc.findConceptByID(index++);
			if (constant == null)
				continue;
			count++;

			executor.execute(new PreprocessingTask(new OntologyConcept(constant,
					index - 1)));
		}

		while (executor.getActiveCount() > 0)
			Thread.sleep(5000);

		System.out.println("Complete! " + count_ + " concepts processed.");
	}

	private void loadPreprocessCommands(OntologySocket cyc) throws Exception {
		System.out
				.println("Loading prior preprocessing assertions. Please wait.");
		BufferedReader reader = new BufferedReader(new FileReader(PREPROCESSED));
		String input;
		while ((input = reader.readLine()) != null) {
			try {
				cyc.querySocket(input);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		reader.close();
		System.out.println("Complete!");
	}

	private class PreprocessingTask implements Runnable {
		private OntologyConcept constant_;

		public PreprocessingTask(OntologyConcept constant) {
			constant_ = constant;
		}

		@Override
		public void run() {
			for (Preprocessor preprocessor : preprocessors_) {
				try {
					preprocessor.processTerm(constant_);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (count_++ % 1000 == 0) {
				System.out.println(count_
						+ " preprocessed ("
						+ (100f * count_ / total_)
						+ "%). Time elapsed: "
						+ UtilityMethods.toTimeFormat(System
								.currentTimeMillis() - startTime_));
			}
		}

	}

	public static void main(String[] args) {
		IOManager.newInstance();
		CycPreprocessor processor = new CycPreprocessor();
		try {
			CycConstants.initialiseAssertions(ResourceAccess
					.requestOntologySocket());
			ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors
					.newFixedThreadPool(Runtime.getRuntime()
							.availableProcessors());
			processor.preprocess(executor);
			IOManager.getInstance().close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		System.out.println("Done!");
		System.exit(0);
	}
}
