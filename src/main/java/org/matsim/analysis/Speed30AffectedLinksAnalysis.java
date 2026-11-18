package org.matsim.analysis;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Compares baseline and 21 km/h output networks by directed link ID and exports links
 * whose free-flow speed decreased. Length is summed by direction and speeds are reported
 * in km/h. The speed30 CSV name and column name are retained for existing consumers.
 */
public final class Speed30AffectedLinksAnalysis {
	private static final String NETWORK_FILE = "dresden-v1.1-1pct.output_network.xml.zst";

	public static void main(String[] args) throws IOException, XMLStreamException {
		Path input = args.length > 0 ? Path.of(args[0]) : Path.of("output");
		Path target = args.length > 1 ? Path.of(args[1])
			: input.resolve("analysis/speed30-affected-links.csv");

		Map<String, Link> baseline = readNetwork(input.resolve("baseline-500").resolve(NETWORK_FILE));
		Map<String, Link> policy = readNetwork(input.resolve("speed21-zone-500").resolve(NETWORK_FILE));
		long count = 0;
		double length = 0;
		Files.createDirectories(target.toAbsolutePath().getParent());
		try (var writer = Files.newBufferedWriter(target);
			 var csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			csv.printRecord("link_id", "street", "road_type", "from_node", "to_node", "length_m",
				"baseline_freespeed_kmh", "speed30_freespeed_kmh", "change_kmh");
			for (String id : policy.keySet().stream().sorted().toList()) {
				Link before = baseline.get(id);
				Link after = policy.get(id);
				if (before == null || after.speed() >= before.speed() - 1e-6) {
					continue;
				}
				double oldSpeed = before.speed() * 3.6;
				double newSpeed = after.speed() * 3.6;
				csv.printRecord(id, after.street(), after.type(), after.from(), after.to(),
					after.length(), rounded(oldSpeed), rounded(newSpeed), rounded(newSpeed - oldSpeed));
				count++;
				length += after.length();
			}
		}
		System.out.printf("%d directed links, %.3f km total%n%s%n", count, length / 1000,
			target.toAbsolutePath());
	}

	private static Map<String, Link> readNetwork(Path path) throws IOException, XMLStreamException {
		var factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		var links = new HashMap<String, Link>();
		try (var input = new ZstdInputStream(Files.newInputStream(path))) {
			var reader = factory.createXMLStreamReader(input);
			try {
				while (reader.hasNext()) {
					if (reader.next() != XMLStreamConstants.START_ELEMENT ||
						!"link".equals(reader.getLocalName())) {
						continue;
					}
					String id = attribute(reader, "id");
					String from = attribute(reader, "from");
					String to = attribute(reader, "to");
					double length = Double.parseDouble(attribute(reader, "length"));
					double speed = Double.parseDouble(attribute(reader, "freespeed"));
					String street = "";
					String type = "";
					while (reader.hasNext()) {
						int event = reader.next();
						if (event == XMLStreamConstants.END_ELEMENT && "link".equals(reader.getLocalName())) {
							break;
						}
						if (event == XMLStreamConstants.START_ELEMENT && "attribute".equals(reader.getLocalName())) {
							String name = attribute(reader, "name");
							if ("name".equals(name)) {
								street = reader.getElementText();
							} else if ("type".equals(name)) {
								type = reader.getElementText();
							}
						}
					}
					links.put(id, new Link(from, to, length, speed, street, type));
				}
			} finally {
				reader.close();
			}
		}
		return links;
	}

	private static String attribute(XMLStreamReader reader, String name) {
		return reader.getAttributeValue(null, name);
	}

	private static double rounded(double value) {
		return Math.round(value * 1000) / 1000.0;
	}

	private record Link(String from, String to, double length, double speed, String street, String type) {}
}
