package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds one departure five minutes after each eligible scheduled departure on tram line 3.
 * Eligibility is based on the route departure time in the 06:00–09:00 and 15:00–18:00
 * windows. The added departure uses the same route and its own vehicle of the original type.
 */
public final class DresdenModelPtFrequency extends DresdenModel {

	private static final Logger log =
		LogManager.getLogger(DresdenModelPtFrequency.class);

	/*
	 * Dresden DVB tram line 3.
	 * GTFS agency 301 represents the Dresden public transport network.
	 */
	private static final Id<TransitLine> TARGET_LINE_ID =
		Id.create("regio_3---5113", TransitLine.class);

	/*
	 * One additional departure is inserted five minutes after each
	 * existing peak-period departure.
	 */
	private static final double ADDITIONAL_DEPARTURE_OFFSET = 5 * 60;

	private static final double MORNING_PEAK_START = 6 * 3600;
	private static final double MORNING_PEAK_END = 9 * 3600;

	private static final double AFTERNOON_PEAK_START = 15 * 3600;
	private static final double AFTERNOON_PEAK_END = 18 * 3600;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelPtFrequency.class, args);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		TransitLine targetLine = scenario.getTransitSchedule()
			.getTransitLines()
			.get(TARGET_LINE_ID);

		if (targetLine == null) {
			throw new IllegalArgumentException(
				"Target tram line was not found: " + TARGET_LINE_ID
			);
		}

		int originalDepartures = targetLine.getRoutes().values().stream()
			.mapToInt(route -> route.getDepartures().size())
			.sum();

		int addedDepartures = 0;

		for (TransitRoute route : targetLine.getRoutes().values()) {

			/*
			 * A copy is required because new departures are added
			 * while the original departures are being inspected.
			 */
			List<Departure> originalRouteDepartures =
				new ArrayList<>(route.getDepartures().values());

			for (Departure originalDeparture : originalRouteDepartures) {

				double originalTime = originalDeparture.getDepartureTime();

				if (!isEligiblePeakDeparture(originalTime)) {
					continue;
				}

				Vehicle originalVehicle = scenario.getTransitVehicles()
					.getVehicles()
					.get(originalDeparture.getVehicleId());

				if (originalVehicle == null) {
					throw new IllegalArgumentException(
						"No transit vehicle found for departure "
							+ originalDeparture.getId()
					);
				}

				String suffix = "_frequency_" + addedDepartures;

				Id<Vehicle> newVehicleId = Id.createVehicleId(
					originalVehicle.getId() + suffix
				);

				Vehicle newVehicle = scenario.getTransitVehicles()
					.getFactory()
					.createVehicle(
						newVehicleId,
						originalVehicle.getType()
					);

				scenario.getTransitVehicles().addVehicle(newVehicle);

				Departure newDeparture = scenario.getTransitSchedule()
					.getFactory()
					.createDeparture(
						Id.create(
							originalDeparture.getId() + suffix,
							Departure.class
						),
						originalTime + ADDITIONAL_DEPARTURE_OFFSET
					);

				newDeparture.setVehicleId(newVehicleId);
				route.addDeparture(newDeparture);

				addedDepartures++;
			}
		}

		int finalDepartures = originalDepartures + addedDepartures;

		log.info(
			"PT frequency policy completed for line {}: "
				+ "original departures = {}, added departures = {}, "
				+ "final departures = {}.",
			TARGET_LINE_ID,
			originalDepartures,
			addedDepartures,
			finalDepartures
		);
	}

	private static boolean isEligiblePeakDeparture(double departureTime) {

		/*
		 * The final five minutes of each period are excluded so that
		 * the added departure remains inside the defined peak period.
		 */
		boolean morningPeak =
			departureTime >= MORNING_PEAK_START
				&& departureTime <
				MORNING_PEAK_END - ADDITIONAL_DEPARTURE_OFFSET;

		boolean afternoonPeak =
			departureTime >= AFTERNOON_PEAK_START
				&& departureTime <
				AFTERNOON_PEAK_END - ADDITIONAL_DEPARTURE_OFFSET;

		return morningPeak || afternoonPeak;
	}
}
