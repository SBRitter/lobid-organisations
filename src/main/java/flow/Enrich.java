package flow;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import org.culturegraph.mf.morph.Metamorph;
import org.culturegraph.mf.stream.converter.JsonEncoder;
import org.culturegraph.mf.stream.converter.JsonToElasticsearchBulk;
import org.culturegraph.mf.stream.converter.StreamToTriples;
import org.culturegraph.mf.stream.pipe.CloseSupressor;
import org.culturegraph.mf.stream.pipe.TripleFilter;
import org.culturegraph.mf.stream.pipe.sort.AbstractTripleSort.Compare;
import org.culturegraph.mf.stream.pipe.sort.TripleCollect;
import org.culturegraph.mf.stream.pipe.sort.TripleSort;
import org.culturegraph.mf.stream.sink.ObjectWriter;
import org.culturegraph.mf.stream.source.FileOpener;
import org.culturegraph.mf.stream.source.OaiPmhOpener;
import org.culturegraph.mf.types.Triple;

/**
 * Simple enrichment of DBS records with Sigel data based on the DBS ID.
 * 
 * After enrichment, the result is transformed to JSON for ES indexing.
 * 
 * @author Fabian Steeg (fsteeg)
 *
 */
public class Enrich {

	private static String sigelDumpLocation =
			"src/main/resources/input/sigel.xml";
	private static String sigelDnbRepo = "http://services.d-nb.de/oai/repository";

	/**
	 * @param args Not used
	 */
	public static void main(String... args) {
		/* Run both preparatory pipelines standalone for debugging, doc etc. */
		// Dbs.main();
		// Sigel.main();
		/* Run the actual enrichment pipeline, which includes the previous: */
		process();
	}

	static void process() {
		int updateIntervals = calculateIntervals("2013-06-01", Sigel.getToday());
		CloseSupressor<Triple> wait = new CloseSupressor<>(updateIntervals + 2);

		FileOpener openSigelDump = new FileOpener();
		StreamToTriples streamToTriplesDump = new StreamToTriples();
		streamToTriplesDump.setRedirect(true);
		StreamToTriples flowSigelDump = //
				Sigel.morphSigel(openSigelDump).setReceiver(streamToTriplesDump);
		continueWith(flowSigelDump, wait);

		String start = "2013-06-01";
		String end = addDays(start);
		ArrayList<OaiPmhOpener> updateOpenerList = new ArrayList<>();
		for (int i = 0; i < updateIntervals; i++) {
			System.out.println("***" + start + "; " + end);
			OaiPmhOpener openSigelUpdates = Sigel.createOaiPmhOpener(start, end);
			StreamToTriples streamToTriplesUpdates = new StreamToTriples();
			streamToTriplesUpdates.setRedirect(true);
			StreamToTriples flowUpdates = //
					Sigel.morphSigel(openSigelUpdates)
							.setReceiver(streamToTriplesUpdates);
			continueWith(flowUpdates, wait);
			updateOpenerList.add(openSigelUpdates);
			start = addDays(start);
			if (i == updateIntervals - 2)
				end = Sigel.getToday();
			else
				end = addDays(end);
		}

		FileOpener openDbs = new FileOpener();
		StreamToTriples streamToTriplesDbs = new StreamToTriples();
		streamToTriplesDbs.setRedirect(true);
		StreamToTriples flowDbs = //
				Dbs.morphDbs(openDbs).setReceiver(streamToTriplesDbs);
		continueWith(flowDbs, wait);

		Sigel.processSigel(openSigelDump, sigelDumpLocation);
		for (OaiPmhOpener updateOpener : updateOpenerList)
			Sigel.processSigel(updateOpener, sigelDnbRepo);
		Dbs.processDbs(openDbs);
	}

	private static String addDays(String start) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		String result = null;
		try {
			Date startDate = dateFormat.parse(start);
			Calendar calender = Calendar.getInstance();
			calender.setTime(startDate);
			calender.add(Calendar.DATE, 50);
			result = dateFormat.format(calender.getTime());
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return result;
	}

	private static int calculateIntervals(String start, String end) {
		LocalDate startDate = LocalDate.parse(start);
		LocalDate endDate = LocalDate.parse(end);
		long timeSpan = startDate.until(endDate, ChronoUnit.DAYS);
		return (int) timeSpan / 50;
	}

	private static void continueWith(StreamToTriples flow,
			CloseSupressor<Triple> wait) {
		TripleFilter tripleFilter = new TripleFilter();
		tripleFilter.setSubjectPattern(".+"); // Remove entries without id
		Metamorph morph = new Metamorph("src/main/resources/morph-enriched.xml");
		TripleSort sortTriples = new TripleSort();
		sortTriples.setBy(Compare.SUBJECT);
		JsonEncoder encodeJson = new JsonEncoder();
		encodeJson.setPrettyPrinting(true);
		ObjectWriter<String> writer =
				new ObjectWriter<>("src/main/resources/output/enriched.out.json");
		JsonToElasticsearchBulk esBulk =
				new JsonToElasticsearchBulk("@id", "organisation", "organisations");
		flow.setReceiver(wait)//
				.setReceiver(tripleFilter)//
				.setReceiver(sortTriples)//
				.setReceiver(new TripleCollect())//
				.setReceiver(morph)//
				.setReceiver(encodeJson)//
				.setReceiver(esBulk)//
				.setReceiver(writer);
	}

	/* For tests */
	static void processSample() {
		FileOpener openSigelDump = new FileOpener();
		StreamToTriples streamToTriples1 = new StreamToTriples();
		streamToTriples1.setRedirect(true);
		StreamToTriples flow1 = //
				Sigel.morphSigel(openSigelDump).setReceiver(streamToTriples1);

		FileOpener openDbs = new FileOpener();
		StreamToTriples streamToTriples2 = new StreamToTriples();
		streamToTriples2.setRedirect(true);
		StreamToTriples flow2 = //
				Dbs.morphDbs(openDbs).setReceiver(streamToTriples2);

		CloseSupressor<Triple> wait = new CloseSupressor<>(2);
		continueWith(flow1, wait);
		continueWith(flow2, wait);

		Sigel.processSigel(openSigelDump, sigelDumpLocation);
		Dbs.processDbs(openDbs);
	}
}