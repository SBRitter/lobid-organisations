package flow;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Optional;

import org.culturegraph.mf.morph.Metamorph;
import org.culturegraph.mf.morph.maps.RestMap;
import org.culturegraph.mf.stream.converter.JsonEncoder;
import org.culturegraph.mf.stream.converter.JsonToElasticsearchBulk;
import org.culturegraph.mf.stream.converter.StreamToTriples;
import org.culturegraph.mf.stream.pipe.CloseSupressor;
import org.culturegraph.mf.stream.pipe.TripleFilter;
import org.culturegraph.mf.stream.pipe.sort.AbstractTripleSort.Compare;
import org.culturegraph.mf.stream.pipe.sort.TripleCollect;
import org.culturegraph.mf.stream.pipe.sort.TripleSort;
import org.culturegraph.mf.stream.sink.ObjectWriter;
import org.culturegraph.mf.stream.source.OaiPmhOpener;
import org.culturegraph.mf.types.Triple;

/**
 * @author pvb
 */
public class Helpers {

	/**
	 * @param isRedirected define whether stream shall be redirected or not
	 * @return a new StreamToTriples
	 */
	public static StreamToTriples createTripleStream(boolean isRedirected) {
		StreamToTriples streamToTriples = new StreamToTriples();
		streamToTriples.setRedirect(isRedirected);
		return streamToTriples;
	}

	/**
	 * @param isPrettyPrinting define whether encoder shall be pretty printing or
	 *          not
	 * @return a new JsonEncoder
	 */
	public static JsonEncoder createJsonEncoder(boolean isPrettyPrinting) {
		JsonEncoder encodeJson = new JsonEncoder();
		encodeJson.setPrettyPrinting(isPrettyPrinting);
		return encodeJson;
	}

	/**
	 * @param start the start of updates formatted in yyyy-MM-dd
	 * @param end the end of updates formatted in yyyy-MM-dd
	 * @return a new OaiPmhOpener
	 */
	public static OaiPmhOpener createOaiPmhOpener(String start, String end) {
		OaiPmhOpener opener = new OaiPmhOpener();
		opener.setDateFrom(start);
		opener.setDateUntil(end);
		opener.setMetadataPrefix("PicaPlus-xml");
		opener.setSetSpec("bib");
		return opener;
	}

	/**
	 * @return a String representing today in yyyy-MM-dd format
	 */
	public static String getToday() {
		String dateFormat = "yyyy-MM-dd";
		Calendar calender = Calendar.getInstance();
		SimpleDateFormat simpleDate = new SimpleDateFormat(dateFormat);
		return simpleDate.format(calender.getTime());
	}

	/**
	 * @param flow the processing stream that is to be connected to a JSON writer
	 * @param wait a CloseSupressor, ensuring all pipes are received by the writer
	 * @param aOutputPath the destination of the written file
	 * @param geoLookupServer The geo lookup server, with protocol and port
	 */
	static void setupTripleStreamToWriter(final StreamToTriples flow,
			CloseSupressor<Triple> wait, TripleSort sortTriples,
			final TripleRematch rematchTriples, final String aOutputPath,
			Optional<String> geoLookupServer) {

		final TripleFilter tripleFilter = new TripleFilter();
		tripleFilter.setSubjectPattern(".+"); // Remove entries without id
		final Metamorph morph =
				new Metamorph(Constants.MAIN_RESOURCES_PATH + "morph-enriched.xml");
		setupGeoLookup(morph, geoLookupServer);
		sortTriples.setBy(Compare.SUBJECT);
		final JsonEncoder encodeJson = Helpers.createJsonEncoder(true);
		final ObjectWriter<String> writer = new ObjectWriter<>(aOutputPath);
		final JsonToElasticsearchBulk esBulk = new JsonToElasticsearchBulk("id",
				Constants.ES_TYPE, Constants.ES_INDEX);
		flow.setReceiver(wait)//
				.setReceiver(tripleFilter)//
				.setReceiver(rematchTriples)//
				.setReceiver(sortTriples)//
				.setReceiver(new TripleCollect())//
				.setReceiver(morph)//
				.setReceiver(encodeJson)//
				.setReceiver(esBulk)//
				.setReceiver(writer);

	}

	private static void setupGeoLookup(final Metamorph morph,
			Optional<String> geoLookupServer) {
		if (geoLookupServer.isPresent()) {
			RestMap addDbsLatMap = new RestMap();
			RestMap addDbsLongMap = new RestMap();
			RestMap addDbsPostalCodeMap = new RestMap();
			addDbsLatMap.setUrl(geoLookupServer.get() + "/lat/${key}");
			addDbsLongMap.setUrl(geoLookupServer.get() + "/long/${key}");
			addDbsPostalCodeMap.setUrl(geoLookupServer.get() + "/postcode/${key}");
			morph.putMap("addLatMap", addDbsLatMap);
			morph.putMap("addLongMap", addDbsLongMap);
			morph.putMap("addPostalCodeMap", addDbsPostalCodeMap);
		}
	}

}
