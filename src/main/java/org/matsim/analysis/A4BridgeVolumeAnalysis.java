package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Reads daily car volume from one A4 bridge link per direction and reports changes against
 * the baseline. The volume table uses the traffic CSV values at their original scale.
 * The second CSV uses event-based trip counts in the simulation sample.
 */
public final class A4BridgeVolumeAnalysis {

	private static final String NORTHEAST_LINK = "31059226";
	private static final String SOUTHWEST_LINK = "318199257";
	private static final List<String> RUNS = List.of(
		"baseline-500",
		"speed21-zone-500",
		"roadpricing-500",
		"pt-frequency-500"
	);

	public static void main(String[] args) throws IOException, javax.xml.stream.XMLStreamException {
		Path input = args.length > 0 ? Path.of(args[0]) : Path.of("output");
		Path result = args.length > 1 ? Path.of(args[1])
			: input.resolve("analysis/a4-bridge-car-volume.csv");

		var volumes = new LinkedHashMap<String, BridgeVolume>();
		for (String run : RUNS) {
			Path file = input.resolve(run).resolve("analysis/traffic/traffic_stats_by_link_daily.csv");
			volumes.put(run, readVolume(file));
		}
		BridgeVolume baseline = volumes.get(RUNS.getFirst());

		Files.createDirectories(result.toAbsolutePath().getParent());
		Path detours = result.resolveSibling("a4-bridge-route-detours.csv");
		if (result.toAbsolutePath().normalize().equals(detours.toAbsolutePath().normalize())) {
			throw new IllegalArgumentException("Volume and route comparison outputs must use distinct paths");
		}
		A4BridgeRouteAnalysis.write(input, detours, RUNS);
		try (var writer = Files.newBufferedWriter(result);
			 var csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			csv.printRecord("scenario", "northeast_car_per_day", "southwest_car_per_day",
				"total_car_per_day", "northeast_change", "southwest_change",
				"total_change", "total_change_pct");
			for (var entry : volumes.entrySet()) {
				BridgeVolume volume = entry.getValue();
				double change = volume.total() - baseline.total();
				csv.printRecord(entry.getKey(), volume.northeast(), volume.southwest(),
					volume.total(), volume.northeast() - baseline.northeast(),
					volume.southwest() - baseline.southwest(), change,
					baseline.total() == 0 ? "" : String.format(Locale.ROOT, "%.2f", 100 * change / baseline.total()));
			}
		}
		System.out.println("A4 bridge comparison written to " + result.toAbsolutePath());
	}

	private static BridgeVolume readVolume(Path file) throws IOException {
		Double northeast = null;
		Double southwest = null;
		var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
		try (var reader = Files.newBufferedReader(file); var records = format.parse(reader)) {
			for (var row : records) {
				String link = row.get("link_id");
				if (NORTHEAST_LINK.equals(link)) {
					northeast = Double.parseDouble(row.get("vol_car"));
				} else if (SOUTHWEST_LINK.equals(link)) {
					southwest = Double.parseDouble(row.get("vol_car"));
				}
			}
		}
		if (northeast == null || southwest == null) {
			throw new IOException("Missing A4 bridge link in " + file);
		}
		return new BridgeVolume(northeast, southwest);
	}

	private record BridgeVolume(double northeast, double southwest) {
		double total() {
			return northeast + southwest;
		}
	}
}

