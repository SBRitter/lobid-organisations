package controllers;

import java.io.IOException;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.FilteredQueryBuilder;
import org.elasticsearch.index.query.GeoDistanceFilterBuilder;
import org.elasticsearch.index.query.GeoPolygonFilterBuilder;
import org.elasticsearch.index.query.MatchAllFilterBuilder;
import org.elasticsearch.index.query.QueryBuilders;

import play.Play;
import play.libs.F.Promise;
import play.libs.ws.WS;
import play.mvc.Controller;
import play.mvc.Result;
import views.html.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Application extends Controller {

	private static final String ES_SERVER = "http://weywot2.hbz-nrw.de:9200";
	private static final String ES_INDEX = "organisations";
	private static final String ES_TYPE = "dbs";

	private static Settings clientSettings = ImmutableSettings
			.settingsBuilder().put("cluster.name", "organisation-cluster")
			.put("client.transport.sniff", true).build();
	private static TransportClient transportClient = new TransportClient(
			clientSettings);
	private static Client client = transportClient
			.addTransportAddress(new InetSocketTransportAddress(
					"weywot2.hbz-nrw.de", 9300));

	public static Result index() {
		return ok(index.render("lobid-organisations"));
	}

	public static Result context() {
		response().setContentType("application/ld+json");
		response().setHeader("Access-Control-Allow-Origin", "*");
		return ok(Play.application().resourceAsStream("context.jsonld"));
	}

	public static Result search(String q, String location, int from, int size)
			throws JsonProcessingException, IOException {
		String[] queryParts = q.split(":");
		String field = queryParts[0];
		String term = queryParts[1];
		Status result = null;
		if (location == null) {
			result = buildSimpleQuery(field, term, from, size);
		} else {
			result = prepareLocationQuery(location, field, term, from, size);
		}
		return result;
	}

	private static Status prepareLocationQuery(String location, String field,
			String term, int from, int size) throws JsonProcessingException,
			IOException {
		String[] coordPairsAsString = location.split(" ");
		Status result;
		if (coordPairsAsString[0].split(",").length > 2) {
			result = prepareDistanceQuery(coordPairsAsString, field, term,
					from, size);
		} else {
			result = preparePolygonQuery(coordPairsAsString, field, term, from,
					size);
		}
		return result;
	}

	private static Status preparePolygonQuery(String[] coordPairsAsString,
			String field, String term, int from, int size)
			throws JsonProcessingException, IOException {
		double[] latCoordinates = new double[coordPairsAsString.length];
		double[] lonCoordinates = new double[coordPairsAsString.length];
		Status result;
		for (int i = 0; i < coordPairsAsString.length; i++) {
			String[] coordinatePair = coordPairsAsString[i].split(",");
			latCoordinates[i] = Double.parseDouble(coordinatePair[0]);
			lonCoordinates[i] = Double.parseDouble(coordinatePair[1]);
		}
		if (coordPairsAsString.length < 3) {
			return badRequest("Not enough points. Polygon requires more than two points.");
		} else {
			result = buildPolygonQuery(field, term, latCoordinates, lonCoordinates,
					from, size);
		}
		return result;
	}

	private static Status prepareDistanceQuery(String[] coordPairsAsString,
			String field, String term, int from, int size)
			throws JsonProcessingException, IOException {
		String[] coordinatePair = coordPairsAsString[0].split(",");
		double lat = Double.parseDouble(coordinatePair[0]);
		double lon = Double.parseDouble(coordinatePair[1]);
		double distance = Double.parseDouble(coordinatePair[2]);
		Status result;
		if (distance < 0) {
			return badRequest("Distance must not be smaller than 0.");
		} else {
			result = buildDistanceQuery(field, term, from, size, lat, lon, distance);
		}
		return result;
	}

	private static Status buildSimpleQuery(String field, String term, int from,
			int size) throws JsonProcessingException, IOException {
		MatchAllFilterBuilder matchAllFilter = FilterBuilders.matchAllFilter();
		FilteredQueryBuilder simpleQuery = QueryBuilders.filteredQuery(
				QueryBuilders.matchQuery(field, term), matchAllFilter);
		SearchResponse queryResponse = executeQuery(from, size, simpleQuery);
		return returnAsJson(queryResponse);
	}

	private static Status buildPolygonQuery(String field, String term,
			double[] latCoordinates, double[] lonCoordinates, int from, int size)
			throws JsonProcessingException, IOException {
		GeoPolygonFilterBuilder polygonFilter = FilterBuilders
				.geoPolygonFilter("location");
		for (int i = 0; i < latCoordinates.length; i++) {
			polygonFilter.addPoint(latCoordinates[i], lonCoordinates[i]);
		}
		FilteredQueryBuilder polygonQuery = QueryBuilders.filteredQuery(
				QueryBuilders.matchQuery(field, term), polygonFilter);
		SearchResponse queryResponse = executeQuery(from, size, polygonQuery);
		return returnAsJson(queryResponse);
	}

	private static Status buildDistanceQuery(String field, String term, int from,
			int size, double lat, double lon, double distance)
			throws JsonProcessingException, IOException {
		GeoDistanceFilterBuilder distanceFilter = FilterBuilders
				.geoDistanceFilter("location")
				.distance(distance, DistanceUnit.KILOMETERS).point(lat, lon);
		FilteredQueryBuilder distanceQuery = QueryBuilders.filteredQuery(
				QueryBuilders.matchQuery(field, term), distanceFilter);
		SearchResponse queryResponse = executeQuery(from, size, distanceQuery);
		return returnAsJson(queryResponse);
	}

	private static SearchResponse executeQuery(int from, int size,
			FilteredQueryBuilder filteredQuery) {
		SearchResponse responseOfSearch = client.prepareSearch("organisations")
				.setTypes("dbs").setSearchType(SearchType.QUERY_THEN_FETCH)
				.setQuery(filteredQuery).setFrom(from).setSize(size).execute()
				.actionGet();
		return responseOfSearch;
	}

	private static Status returnAsJson(SearchResponse queryResponse)
			throws IOException, JsonProcessingException {
		JsonNode responseAsJson = new ObjectMapper().readTree(queryResponse
				.toString());
		return ok(responseAsJson);
	}

	public static Promise<Result> get(String id) {
		String url = String.format("%s/%s/%s/%s/_source", ES_SERVER, ES_INDEX,
				ES_TYPE, id);
		return WS.url(url).execute().map(x -> ok(x.asJson()));
	}
}