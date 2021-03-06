package knowledgeMiner.mapping.cycToWiki;

import io.ontology.OntologySocket;
import io.resources.WikipediaSocket;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;

import knowledgeMiner.mapping.MappingPostProcessor;
import util.UtilityMethods;
import util.collection.WeightedSet;

public class CycToWikiRedirectPostProcessor extends
		MappingPostProcessor<Integer> {

	/**
	 * Constructor for a new WikiToCycPostProcessor
	 */
	public CycToWikiRedirectPostProcessor() {
		super();
	}

	@Override
	public WeightedSet<Integer> process(WeightedSet<Integer> collection,
			WikipediaSocket wmi, OntologySocket cyc) {
		if (collection.isEmpty())
			return collection;
		UtilityMethods.removeNegOnes(collection);

		// Follow redirects and remove disambiguations
		WeightedSet<Integer> newSet = new WeightedSet<>();
		try {
			Integer[] pages = collection
					.toArray(new Integer[collection.size()]);
			int index = 0;
			for (String pageType : wmi.getPageType(pages)) {
				if (pageType == null)
					continue;
				int artID = pages[index];
				double weight = collection.getWeight(artID);
				if (pageType.equals(WikipediaSocket.TYPE_REDIRECT)) {
					// Follow redirects
					int redirect = followRedirect(artID, wmi);
					if (redirect != -1)
						newSet.add(redirect, weight);
				} else
					newSet.add(artID, weight);
				index++;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return newSet;
	}

	/**
	 * Continue to follow redirects until either an article is found or no
	 * article is found.
	 * 
	 * @param startPoint
	 *            The starting point for the redirects.
	 * @param wmi
	 *            WMI Access.
	 * @return The end point or -1.
	 * @throws IOException
	 *             Should something go awry...
	 */
	private int followRedirect(Integer startPoint, WikipediaSocket wmi)
			throws IOException {
		int currId = startPoint;
		Collection<Integer> redirectsFollowed = new HashSet<>();

		while (!redirectsFollowed.contains(currId)) {
			redirectsFollowed.add(currId);

			int targetId = wmi.getRedirect(currId);
			if (targetId == -1)
				return -1;

			String type = wmi.getPageType(targetId);
			if (type == null)
				return -1;
			if (type.equals("redirect"))
				currId = targetId;
			else
				return targetId;
		}

		return -1;
	}
}
