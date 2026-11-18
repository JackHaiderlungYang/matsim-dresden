package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares selected plans by person ID and trip position across baseline and policy runs.
 * The detailed CSV records the paired main modes and baseline purpose and beeline distance.
 * Summary rows report matched trip counts and unique people by area or demographic group.
 */
public final class PopulationReactionDetailedAnalysis {

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
		WGS84_TO_NETWORK.transform(new Coord(MIN_LON, MIN_LAT));

	private static final Coord NORTH_EAST =
		WGS84_TO_NETWORK.transform(new Coord(MAX_LON, MAX_LAT));

	private static final double MIN_X =
		Math.min(SOUTH_WEST.getX(), NORTH_EAST.getX());

	private static final double MAX_X =
		Math.max(SOUTH_WEST.getX(), NORTH_EAST.getX());

	private static final double MIN_Y =
		Math.min(SOUTH_WEST.getY(), NORTH_EAST.getY());

	private static final double MAX_Y =
		Math.max(SOUTH_WEST.getY(), NORTH_EAST.getY());

	private static final Path BASELINE_PLANS = Path.of(
		"output", "baseline-500",
		"dresden-v1.1-1pct.output_plans.xml.zst"
	);

	private static final Map<String, Path> POLICY_PLANS =
		new LinkedHashMap<>();

	static {
		POLICY_PLANS.put(
			"speed30",
			Path.of(
				"output", "speed21-zone-500",
				"dresden-v1.1-1pct.output_plans.xml.zst"
			)
		);

		POLICY_PLANS.put(
			"roadpricing",
			Path.of(
				"output", "roadpricing-500",
				"dresden-v1.1-1pct.output_plans.xml.zst"
			)
		);

		POLICY_PLANS.put(
			"pt_frequency",
			Path.of(
				"output", "pt-frequency-500",
				"dresden-v1.1-1pct.output_plans.xml.zst"
			)
		);

		POLICY_PLANS.put(
			"pt_frequency_3_and_62",
			Path.of(
				"output", "pt-frequency-3-and-62-500",
				"dresden-v1.1-1pct.output_plans.xml.zst"
			)
		);
	}

	private static final Path OUTPUT_CSV = Path.of(
		"output", "analysis",
		"population-reaction-detailed.csv"
	);

	private PopulationReactionDetailedAnalysis() {
	}

	public static void main(String[] args) throws IOException {

		Path detailedOutput = args.length == 0 ? OUTPUT_CSV : Path.of(args[0]).resolve(OUTPUT_CSV.getFileName());
		Scenario baselineScenario = readPopulation(BASELINE_PLANS);

		Files.createDirectories(detailedOutput.toAbsolutePath().getParent());

		try (BufferedWriter writer =
				 Files.newBufferedWriter(detailedOutput)) {

			writer.write(
				"person_id,age,sex,income_eur,group,policy,trip_index,purpose,"
					+ "baseline_mode,policy_mode,mode_changed,"
					+ "beeline_distance_km"
			);
			writer.newLine();

			for (Map.Entry<String, Path> policy :
				POLICY_PLANS.entrySet()) {

				Scenario policyScenario =
					readPopulation(policy.getValue());

				comparePolicy(
					writer,
					policy.getKey(),
					baselineScenario,
					policyScenario
				);
			}
		}

		writeSummaries(detailedOutput);

		System.out.println(
			"Detailed population-reaction analysis written to: "
				+ detailedOutput.toAbsolutePath()
		);
	}


	private static void writeSummaries(Path detailedOutput) throws IOException {
		Map<DemographicGroup, DemographicStats> groups = new LinkedHashMap<>();
		var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
		try (var reader = Files.newBufferedReader(detailedOutput);
			 var records = format.parse(reader)) {
			for (var row : records) {
				String policy = row.get("policy");
				String person = row.get("person_id");
				String before = row.get("baseline_mode");
				String after = row.get("policy_mode");
				String age = demographicBand(row.get("age"), new int[]{18, 30, 45, 65},
					"under_18", "18_29", "30_44", "45_64", "65_plus");
				String income = demographicBand(row.get("income_eur"), new int[]{1500, 2500, 3500},
					"under_1500", "1500_2499", "2500_3499", "3500_plus");
				String sex = switch (row.get("sex")) {
					case "f" -> "female";
					case "m" -> "male";
					default -> "other_or_unknown";
				};
				for (var group : List.of(new DemographicGroup(policy, "age", age),
					new DemographicGroup(policy, "income", income),
					new DemographicGroup(policy, "sex", sex),
					new DemographicGroup(policy, "area", row.get("group")),
					new DemographicGroup(policy, "area", "all"))) {
					groups.computeIfAbsent(group, key -> new DemographicStats()).add(person, before, after);
				}
			}
		}

		writeSummary(detailedOutput.resolveSibling("population-reaction-sociodemographic-summary.csv"), groups, false);
		writeSummary(detailedOutput.resolveSibling("population-reaction-person-summary.csv"), groups, true);
	}

	private static void writeSummary(Path summary, Map<DemographicGroup, DemographicStats> groups, boolean areaSummary)
		throws IOException {
		try (var writer = Files.newBufferedWriter(summary);
			 var csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
			csv.printRecord("policy", "dimension", "category", "persons", "trips",
				"mode_changed_trips", "mode_change_rate_pct", "baseline_car_trips",
				"policy_car_trips", "car_trip_change", "car_trip_change_pct", "car_to_pt",
				"car_to_bike", "car_to_walk", "mode_changed_persons", "person_mode_change_rate_pct",
				"car_to_pt_persons", "pt_to_car_trips", "pt_to_car_persons");
			for (var entry : groups.entrySet()) {
				var group = entry.getKey();
				if (areaSummary != "area".equals(group.dimension())) {
					continue;
				}
				var stats = entry.getValue();
				int carChange = stats.policyCar - stats.baselineCar;
				csv.printRecord(group.policy(), group.dimension(), group.category(),
					stats.persons.size(), stats.trips, stats.changed, percentage(stats.changed, stats.trips),
					stats.baselineCar, stats.policyCar, carChange, percentage(carChange, stats.baselineCar),
					stats.carToPt, stats.carToBike, stats.carToWalk,
					stats.changedPersons.size(), percentage(stats.changedPersons.size(), stats.persons.size()),
					stats.carToPtPersons.size(), stats.ptToCar, stats.ptToCarPersons.size());
			}
		}
		System.out.println("Population reaction summary written to: " + summary.toAbsolutePath());
	}

	private static String demographicBand(String value, int[] limits, String... labels) {
		double number;
		try {
			number = Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return "unknown";
		}
		if (!Double.isFinite(number)) {
			return "unknown";
		}
		for (int i = 0; i < limits.length; i++) {
			if (number < limits[i]) {
				return labels[i];
			}
		}
		return labels[limits.length];
	}

	private static String percentage(int count, int total) {
		return total == 0 ? "" : String.format(Locale.ROOT, "%.2f", 100.0 * count / total);
	}

	private record DemographicGroup(String policy, String dimension, String category) {
	}

	private static final class DemographicStats {
		private final Set<String> persons = new HashSet<>();
		private final Set<String> changedPersons = new HashSet<>();
		private final Set<String> carToPtPersons = new HashSet<>();
		private final Set<String> ptToCarPersons = new HashSet<>();
		private int trips;
		private int changed;
		private int baselineCar;
		private int policyCar;
		private int carToPt;
		private int ptToCar;
		private int carToBike;
		private int carToWalk;

		private void add(String person, String before, String after) {
			persons.add(person);
			trips++;
			if (!before.equals(after)) {
				changed++;
				changedPersons.add(person);
			}
			if ("car".equals(after)) {
				policyCar++;
				if ("pt".equals(before)) {
					ptToCar++;
					ptToCarPersons.add(person);
				}
			}
			if ("car".equals(before)) {
				baselineCar++;
				switch (after) {
					case "pt" -> {
						carToPt++;
						carToPtPersons.add(person);
					}
					case "bike" -> carToBike++;
					case "walk" -> carToWalk++;
					default -> { }
				}
			}
		}
	}

	private static Scenario readPopulation(Path plansFile) {

		if (!Files.exists(plansFile)) {
			throw new IllegalArgumentException(
				"Plans file not found: "
					+ plansFile.toAbsolutePath()
			);
		}

		Scenario scenario =
			ScenarioUtils.createScenario(
				ConfigUtils.createConfig()
			);

		new PopulationReader(scenario).readFile(
			plansFile.toString()
		);

		return scenario;
	}

	private static void comparePolicy(
		BufferedWriter writer,
		String policyName,
		Scenario baselineScenario,
		Scenario policyScenario
	) throws IOException {

		for (Person baselinePerson :
			baselineScenario.getPopulation()
				.getPersons().values()) {

			Person policyPerson =
				policyScenario.getPopulation()
					.getPersons()
					.get(baselinePerson.getId());

			if (policyPerson == null
				|| baselinePerson.getSelectedPlan() == null
				|| policyPerson.getSelectedPlan() == null) {
				continue;
			}

			Plan baselinePlan =
				baselinePerson.getSelectedPlan();

			Plan policyPlan =
				policyPerson.getSelectedPlan();

			/*
			 * The group is defined using the baseline plan.
			 * Therefore, the same people remain in the same group
			 * in every policy comparison.
			 */
			String group =
				hasActivityInsidePolicyArea(baselinePlan)
					? "affected"
					: "outside";

			List<TripStructureUtils.Trip> baselineTrips =
				TripStructureUtils.getTrips(baselinePlan);

			List<TripStructureUtils.Trip> policyTrips =
				TripStructureUtils.getTrips(policyPlan);

			// Pair by trip index; unmatched trailing trips are omitted. A changed activity
			// sequence can pair trips with different destinations.
			int comparableTrips =
				Math.min(
					baselineTrips.size(),
					policyTrips.size()
				);

			for (int index = 0;
				 index < comparableTrips;
				 index++) {

				TripStructureUtils.Trip baselineTrip =
					baselineTrips.get(index);

				TripStructureUtils.Trip policyTrip =
					policyTrips.get(index);

				String baselineMode =
					identifyMainMode(
						baselineTrip.getLegsOnly()
					);

				String policyMode =
					identifyMainMode(
						policyTrip.getLegsOnly()
					);

				String purpose =
					normalizePurpose(
						baselineTrip
							.getDestinationActivity()
							.getType()
					);

				double distanceKm =
					calculateBeelineDistanceKm(
						baselineTrip
					);

				writer.write(String.join(",",
					csv(baselinePerson.getId().toString()),
					attribute(baselinePerson, "SNZ_age", "age"),
					attribute(baselinePerson, "sex", "SNZ_gender", "gender"),
					attribute(baselinePerson, "income", "SNZ_hhIncome", "hhIncome"),
					group,
					policyName,
					Integer.toString(index + 1),
					csv(purpose),
					baselineMode,
					policyMode,
					Boolean.toString(
						!baselineMode.equals(policyMode)
					),
					String.format(
						Locale.ROOT,
						"%.3f",
						distanceKm
					)
				));

				writer.newLine();
			}
		}
	}

	private static String attribute(Person person, String... names) {
		for (String name : names) {
			Object value = person.getAttributes().getAttribute(name);
			if (value != null) {
				return csv(value.toString());
			}
		}
		return "";
	}

	private static double calculateBeelineDistanceKm(
		TripStructureUtils.Trip trip
	) {
		Coord origin =
			trip.getOriginActivity().getCoord();

		Coord destination =
			trip.getDestinationActivity().getCoord();

		if (origin == null || destination == null) {
			return -1.0;
		}

		return CoordUtils.calcEuclideanDistance(
			origin,
			destination
		) / 1000.0;
	}

	private static String normalizePurpose(String activityType) {

		if (activityType == null) {
			return "unknown";
		}

		String type =
			activityType.toLowerCase(Locale.ROOT);

		if (type.startsWith("work")) {
			return "work";
		}

		if (type.startsWith("education")
			|| type.startsWith("school")
			|| type.startsWith("university")) {
			return "education";
		}

		if (type.startsWith("shop")) {
			return "shopping";
		}

		if (type.startsWith("leisure")) {
			return "leisure";
		}

		if (type.startsWith("home")) {
			return "home";
		}

		return "other";
	}

	private static boolean hasActivityInsidePolicyArea(
		Plan plan
	) {
		return plan.getPlanElements().stream()
			.filter(Activity.class::isInstance)
			.map(Activity.class::cast)
			.map(Activity::getCoord)
			.filter(coord -> coord != null)
			.anyMatch(
				PopulationReactionDetailedAnalysis
					::isInsidePolicyArea
			);
	}

	private static boolean isInsidePolicyArea(Coord coord) {
		return coord.getX() >= MIN_X
			&& coord.getX() <= MAX_X
			&& coord.getY() >= MIN_Y
			&& coord.getY() <= MAX_Y;
	}

	private static String identifyMainMode(
		List<Leg> legs
	) {
		if (legs.stream().anyMatch(leg ->
			"pt".equals(leg.getMode())
				|| "transit_walk".equals(leg.getMode()))) {
			return "pt";
		}

		if (legs.stream().anyMatch(leg ->
			"car".equals(leg.getMode()))) {
			return "car";
		}

		if (legs.stream().anyMatch(leg ->
			"ride".equals(leg.getMode()))) {
			return "ride";
		}

		if (legs.stream().anyMatch(leg ->
			"bike".equals(leg.getMode()))) {
			return "bike";
		}

		if (legs.stream().anyMatch(leg ->
			"walk".equals(leg.getMode())
				|| "non_network_walk".equals(
				leg.getMode()
			))) {
			return "walk";
		}

		return "other";
	}

	private static String csv(String value) {
		if (value == null) {
			return "";
		}

		return "\""
			+ value.replace("\"", "\"\"")
			+ "\"";
	}
}
