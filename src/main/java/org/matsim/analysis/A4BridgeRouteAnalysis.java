package org.matsim.analysis;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Compares completed car routes reconstructed from traffic events for each A4 bridge direction.
 * A centre through-trip detour requires a unique match by driver and exact origin and destination
 * link IDs, with both endpoints outside the centre, prior use of centre links, and new bridge
 * use without visiting the centre. Ambiguous or unmatched policy trips are counted separately.
 */
final class A4BridgeRouteAnalysis {
	record Route(String start, boolean centre, boolean ne, boolean sw) {
		Route visit(String link, Set<String> area) {
			return new Route(start, centre || area.contains(link),
				ne || "31059226".equals(link), sw || "318199257".equals(link));
		}
	}
	record OD(String person, String start, String end) {}
	record Active(String person, Route route) {}
	record Trip(Route route, boolean outside) {}

	static Map<OD, List<Trip>> load(Path file, Set<String> area) throws IOException, XMLStreamException {
		var factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		var active = new HashMap<String, Active>();
		var trips = new HashMap<OD, List<Trip>>();
		try (var input = new ZstdInputStream(Files.newInputStream(file))) {
			var r = factory.createXMLStreamReader(input);
			while (r.hasNext()) {
				if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("event")) continue;
				String type = r.getAttributeValue(null, "type");
				if (!type.equals("vehicle enters traffic") && !type.equals("entered link") &&
					!type.equals("vehicle leaves traffic")) continue;
				String vehicle = r.getAttributeValue(null, "vehicle");
				String link = r.getAttributeValue(null, "link");
				if (type.equals("vehicle enters traffic")) {
					if ("car".equals(r.getAttributeValue(null, "networkMode"))) {
						active.put(vehicle, new Active(r.getAttributeValue(null, "person"),
							new Route(link, false, false, false).visit(link, area)));
					}
				} else {
					Active current = active.get(vehicle);
					if (current == null) continue;
					Route route = current.route().visit(link, area);
					if (type.equals("entered link")) {
						active.put(vehicle, new Active(current.person(), route));
					} else {
						active.remove(vehicle);
						var od = new OD(current.person(), route.start(), link);
						trips.computeIfAbsent(od, key -> new ArrayList<>())
							.add(new Trip(route, !area.contains(route.start()) && !area.contains(link)));
					}
				}
			}
		}
		return trips;
	}

	static boolean bridge(Trip trip, boolean southwest) {
		return southwest ? trip.route().sw() : trip.route().ne();
	}

	private static void compare(CSVPrinter csv, String scenario, Map<OD, List<Trip>> base, Map<OD, List<Trip>> policy, boolean southwest) throws IOException {
		int baseline = 0, current = 0, retained = 0, newMatched = 0, priorCentre = 0,
			avoidedCentre = 0, avoidedThrough = 0, ambiguousOrUnmatched = 0, stoppedMatched = 0;
		for (var entry : base.values()) for (Trip trip : entry) if (bridge(trip, southwest)) baseline++;
		for (var entry : policy.entrySet()) {
			var old = base.get(entry.getKey());
			var now = entry.getValue();
			for (Trip trip : now) {
				if (!bridge(trip, southwest)) continue;
				current++;
				if (old == null || old.size() != 1 || now.size() != 1) {
					ambiguousOrUnmatched++;
					continue;
				}
				Trip previous = old.getFirst();
				if (bridge(previous, southwest)) {
					retained++;
				} else {
					newMatched++;
					if (previous.route().centre()) {
						priorCentre++;
						if (!trip.route().centre()) {
							avoidedCentre++;
							if (previous.outside()) avoidedThrough++;
						}
					}
				}
			}
			if (old != null && old.size() == 1 && now.size() == 1 &&
				bridge(old.getFirst(), southwest) && !bridge(now.getFirst(), southwest)) stoppedMatched++;
		}
		csv.printRecord(scenario, southwest ? "southwest" : "northeast", baseline,
			current, retained, newMatched, priorCentre, avoidedCentre, avoidedThrough,
			ambiguousOrUnmatched, stoppedMatched,
			newMatched == 0 ? "" : String.format(Locale.ROOT, "%.2f", 100.0 * avoidedThrough / newMatched));
	}

	// Use the same baseline set of car-accessible links in the policy rectangle for every comparison.
	private static Set<String> readPolicyLinks(Path file) throws IOException, XMLStreamException {
		var transform = TransformationFactory.getCoordinateTransformation("WGS84", "EPSG:25832");
		Coord southwest = transform.transform(new Coord(13.724, 51.049));
		Coord northeast = transform.transform(new Coord(13.748, 51.064));
		var nodes = new HashMap<String, Coord>();
		var links = new HashSet<String>();
		var factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		try (var input = new ZstdInputStream(Files.newInputStream(file))) {
			var reader = factory.createXMLStreamReader(input);
			try {
				while (reader.hasNext()) {
					if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
					if ("node".equals(reader.getLocalName())) {
						nodes.put(attr(reader, "id"), new Coord(
							Double.parseDouble(attr(reader, "x")), Double.parseDouble(attr(reader, "y"))));
					} else if ("link".equals(reader.getLocalName()) &&
						Arrays.asList(attr(reader, "modes").split(",")).contains("car")) {
						Coord from = nodes.get(attr(reader, "from"));
						Coord to = nodes.get(attr(reader, "to"));
						double x = (from.getX() + to.getX()) / 2;
						double y = (from.getY() + to.getY()) / 2;
						if (x >= southwest.getX() && x <= northeast.getX() &&
							y >= southwest.getY() && y <= northeast.getY()) {
							links.add(attr(reader, "id"));
						}
					}
				}
			} finally {
				reader.close();
			}
		}
		if (links.isEmpty()) throw new IOException("No car links found in the policy area: " + file);
		return links;
	}

	private static String attr(XMLStreamReader reader, String name) {
		return reader.getAttributeValue(null, name);
	}

	static void write(Path input, Path output, List<String> runs) throws IOException, XMLStreamException {
		Path baselineFolder = input.resolve(runs.getFirst());
		Set<String> area = readPolicyLinks(baselineFolder.resolve("dresden-v1.1-1pct.output_network.xml.zst"));
		var baseline = load(events(baselineFolder), area);
		try (var writer = Files.newBufferedWriter(output);
			 var csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			csv.printRecord("scenario", "direction", "baseline_bridge_trips_sample", "policy_bridge_trips_sample",
				"retained_matched", "new_matched", "new_matched_previously_used_centre",
				"new_matched_now_avoids_centre", "confirmed_centre_through_trip_detours",
				"unmatched_or_ambiguous_policy_bridge_trips", "stopped_using_bridge_matched",
				"confirmed_detours_pct_of_new_matched");
			for (String run : runs.subList(1, runs.size())) {
				var policy = load(events(input.resolve(run)), area);
				compare(csv, run, baseline, policy, false);
				compare(csv, run, baseline, policy, true);
			}
		}
		System.out.println("A4 route comparison written to " + output.toAbsolutePath());
	}

	private static Path events(Path folder) {
		return folder.resolve("dresden-v1.1-1pct.output_events.xml.zst");
	}
}

