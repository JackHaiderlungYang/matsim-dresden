package org.matsim.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * Summarises selected-plan trip counts and mode shares separately for each run.
 * Each person is grouped using activities in that run; group membership can differ across runs.
 * The speed30 output filename and scenario key are retained for existing consumers of the 21 km/h results.
 */
public final class SpeedLimitPopulationAnalysis {

	/*
	 * The 21 km/h free-flow speed and road-pricing scenarios use the same
	 * central Dresden policy area.
	 */
	private static final double MIN_LAT = 51.049;
	private static final double MAX_LAT = 51.064;
	private static final double MIN_LON = 13.724;
	private static final double MAX_LON = 13.748;

	private static final CoordinateTransformation WGS84_TO_NETWORK =
		TransformationFactory.getCoordinateTransformation(
			TransformationFactory.WGS84,
			"EPSG:25832"
		);

	private static final Coord SOUTH_WEST =
		WGS84_TO_NETWORK.transform(
			new Coord(MIN_LON, MIN_LAT)
		);

	private static final Coord NORTH_EAST =
		WGS84_TO_NETWORK.transform(
			new Coord(MAX_LON, MAX_LAT)
		);

	private static final double MIN_X =
		Math.min(SOUTH_WEST.getX(), NORTH_EAST.getX());

	private static final double MAX_X =
		Math.max(SOUTH_WEST.getX(), NORTH_EAST.getX());

	private static final double MIN_Y =
		Math.min(SOUTH_WEST.getY(), NORTH_EAST.getY());

	private static final double MAX_Y =
		Math.max(SOUTH_WEST.getY(), NORTH_EAST.getY());

	private static final Path BASELINE_PLANS = Path.of(
		"output",
		"baseline-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Path SPEED21_PLANS = Path.of(
		"output",
		"speed21-zone-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Path ROAD_PRICING_PLANS = Path.of(
		"output",
		"roadpricing-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Path PT_FREQUENCY_PLANS = Path.of(
		"output",
		"pt-frequency-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Path COMBINED_PT_FREQUENCY_PLANS = Path.of(
		"output", "pt-frequency-3-and-62-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Path OUTPUT_CSV = Path.of(
		"output",
		"analysis",
		"speed30-population-reaction.csv"
	);

	private SpeedLimitPopulationAnalysis() {
	}

	public static void main(String[] args) throws IOException {

		Map<String, GroupStatistics> baseline =
			analyseScenario(BASELINE_PLANS);

		Map<String, GroupStatistics> speed21 =
			analyseScenario(SPEED21_PLANS);

		Map<String, GroupStatistics> roadPricing =
			analyseScenario(ROAD_PRICING_PLANS);

		Map<String, GroupStatistics> ptFrequency =
			analyseScenario(PT_FREQUENCY_PLANS);

		Map<String, GroupStatistics> combinedPtFrequency =
			analyseScenario(COMBINED_PT_FREQUENCY_PLANS);

		Files.createDirectories(OUTPUT_CSV.getParent());

		try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT_CSV)) {

			writer.write(
				"scenario,group,persons,total_trips,"
					+ "car_trips,pt_trips,ride_trips,bike_trips,walk_trips,"
					+ "car_share,pt_share,ride_share,bike_share,walk_share"
			);
			writer.newLine();

			writeScenario(writer, "baseline", baseline);
			writeScenario(writer, "speed30", speed21);
			writeScenario(writer, "roadpricing", roadPricing);
			writeScenario(writer, "pt_frequency", ptFrequency);
			writeScenario(writer, "pt_frequency_3_and_62", combinedPtFrequency);
		}

		System.out.println(
			"Population-reaction analysis written to: "
				+ OUTPUT_CSV.toAbsolutePath()
		);
	}

	private static Map<String, GroupStatistics> analyseScenario(
		Path plansFile
	) {
		if (!Files.exists(plansFile)) {
			throw new IllegalArgumentException(
				"Plans file not found: " + plansFile.toAbsolutePath()
			);
		}

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);

		new PopulationReader(scenario).readFile(
			plansFile.toString()
		);

		Map<String, GroupStatistics> result = new LinkedHashMap<>();
		result.put("affected", new GroupStatistics());
		result.put("outside", new GroupStatistics());

		for (Person person :
			scenario.getPopulation().getPersons().values()) {

			Plan plan = person.getSelectedPlan();

			if (plan == null) {
				continue;
			}

			String group = hasActivityInsidePolicyArea(plan)
				? "affected"
				: "outside";

			GroupStatistics statistics = result.get(group);
			statistics.persons++;

			TripStructureUtils.getTrips(plan).forEach(trip -> {
				statistics.addTrip(
					identifyMainMode(trip.getLegsOnly())
				);
			});
		}

		return result;
	}

	private static boolean hasActivityInsidePolicyArea(Plan plan) {

		return plan.getPlanElements().stream()
			.filter(Activity.class::isInstance)
			.map(Activity.class::cast)
			.map(Activity::getCoord)
			.filter(coord -> coord != null)
			.anyMatch(SpeedLimitPopulationAnalysis::isInsidePolicyArea);
	}

	private static String identifyMainMode(List<Leg> legs) {

		boolean containsPt = legs.stream()
			.anyMatch(leg ->
				"pt".equals(leg.getMode())
					|| "transit_walk".equals(leg.getMode())
			);

		if (containsPt) {
			return "pt";
		}

		boolean containsCar = legs.stream()
			.anyMatch(leg -> "car".equals(leg.getMode()));

		if (containsCar) {
			return "car";
		}

		boolean containsRide = legs.stream()
			.anyMatch(leg -> "ride".equals(leg.getMode()));

		if (containsRide) {
			return "ride";
		}

		boolean containsBike = legs.stream()
			.anyMatch(leg -> "bike".equals(leg.getMode()));

		if (containsBike) {
			return "bike";
		}

		boolean containsWalk = legs.stream()
			.anyMatch(leg ->
				"walk".equals(leg.getMode())
					|| "non_network_walk".equals(leg.getMode())
			);

		if (containsWalk) {
			return "walk";
		}

		return "other";
	}

	private static boolean isInsidePolicyArea(Coord coord) {
		return coord.getX() >= MIN_X
			&& coord.getX() <= MAX_X
			&& coord.getY() >= MIN_Y
			&& coord.getY() <= MAX_Y;
	}

	private static void writeScenario(
		BufferedWriter writer,
		String scenario,
		Map<String, GroupStatistics> statistics
	) throws IOException {

		for (Map.Entry<String, GroupStatistics> entry :
			statistics.entrySet()) {

			GroupStatistics value = entry.getValue();

			writer.write(String.join(",",
				scenario,
				entry.getKey(),
				Long.toString(value.persons),
				Long.toString(value.totalTrips),
				Long.toString(value.carTrips),
				Long.toString(value.ptTrips),
				Long.toString(value.rideTrips),
				Long.toString(value.bikeTrips),
				Long.toString(value.walkTrips),
				formatShare(value.carTrips, value.totalTrips),
				formatShare(value.ptTrips, value.totalTrips),
				formatShare(value.rideTrips, value.totalTrips),
				formatShare(value.bikeTrips, value.totalTrips),
				formatShare(value.walkTrips, value.totalTrips)
			));

			writer.newLine();
		}
	}

	private static String formatShare(long trips, long totalTrips) {
		if (totalTrips == 0) {
			return "0.0000";
		}

		return String.format(
			java.util.Locale.ROOT,
			"%.4f",
			(double) trips / totalTrips
		);
	}

	private static final class GroupStatistics {

		private long rideTrips;
		private long persons;
		private long totalTrips;
		private long carTrips;
		private long ptTrips;
		private long bikeTrips;
		private long walkTrips;

		private void addTrip(String mode) {
			switch (mode) {
				case "car" -> {
					carTrips++;
					totalTrips++;
				}
				case "pt" -> {
					ptTrips++;
					totalTrips++;
				}
				case "ride" -> {
					rideTrips++;
					totalTrips++;
				}
				case "bike" -> {
					bikeTrips++;
					totalTrips++;
				}
				case "walk" -> {
					walkTrips++;
					totalTrips++;
				}
				default -> {
					// Other modes are currently excluded.
				}
			}
		}
	}
}
