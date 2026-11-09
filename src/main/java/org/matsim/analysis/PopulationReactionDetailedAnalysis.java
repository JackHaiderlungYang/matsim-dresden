package org.matsim.analysis;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
				"output", "speed30-zone-500",
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
	}

	private static final Path OUTPUT_CSV = Path.of(
		"output", "analysis",
		"population-reaction-detailed.csv"
	);

	private PopulationReactionDetailedAnalysis() {
	}

	public static void main(String[] args) throws IOException {

		Scenario baselineScenario = readPopulation(BASELINE_PLANS);

		Files.createDirectories(OUTPUT_CSV.getParent());

		try (BufferedWriter writer =
				 Files.newBufferedWriter(OUTPUT_CSV)) {

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

		System.out.println(
			"Detailed population-reaction analysis written to: "
				+ OUTPUT_CSV.toAbsolutePath()
		);
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
