package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * Reduces free-flow speed to approximately 21 km/h on car-accessible links with a baseline
 * free-flow speed of 34–36 km/h and a midpoint inside the central Dresden policy area.
 */
public final class DresdenModelSpeedLimit extends DresdenModel {

	private static final Logger log =
		LogManager.getLogger(DresdenModelSpeedLimit.class);

	/*
	 * Scale selected links from approximately 35 to 21 km/h free-flow speed.
	 * The baseline free-flow speed is characteristic of posted 50 km/h roads
	 * in the Dresden network. The scenario updates link freespeed only.
	 */
	private static final double SPEED_REDUCTION_FACTOR = 0.6;

	/*
	 * Free-flow speed reduction area in central Dresden.
	 *
	 * Approximate boundaries:
	 * West:  Könneritzstraße and the railway corridor
	 * East:  Carolaplatz / Albertstraße
	 * South: Ostra-Allee / Wilsdruffer Straße
	 * North: Antonstraße / Albertplatz
	 *
	 * Coordinates are given in WGS84:
	 * latitude  = north/south coordinate
	 * longitude = east/west coordinate
	 */
	private static final double MIN_LAT = 51.049;
	private static final double MAX_LAT = 51.064;
	private static final double MIN_LON = 13.724;
	private static final double MAX_LON = 13.748;

	/*
	 * In this Dresden network, roads with a posted speed limit
	 * of 50 km/h have a MATSim freespeed of approximately 35 km/h.
	 *
	 * A small tolerance is used because the stored value is
	 * approximately 35.0028 km/h instead of exactly 35 km/h.
	 */
	private static final double ORIGINAL_FREESPEED_MIN_KMH = 34.0;
	private static final double ORIGINAL_FREESPEED_MAX_KMH = 36.0;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelSpeedLimit.class, args);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		/*
		 * The Dresden scenario uses EPSG:25832.
		 * Transform the geographical WGS84 zone boundaries
		 * into the coordinate system of the MATSim network.
		 */
		CoordinateTransformation transformation =
			TransformationFactory.getCoordinateTransformation(
				TransformationFactory.WGS84,
				"EPSG:25832"
			);

		Coord southWest = transformation.transform(
			new Coord(MIN_LON, MIN_LAT)
		);

		Coord northEast = transformation.transform(
			new Coord(MAX_LON, MAX_LAT)
		);

		double minX = Math.min(southWest.getX(), northEast.getX());
		double maxX = Math.max(southWest.getX(), northEast.getX());
		double minY = Math.min(southWest.getY(), northEast.getY());
		double maxY = Math.max(southWest.getY(), northEast.getY());

		int modifiedLinks = 0;
		double totalModifiedLengthMeters = 0.0;

		for (Link link : scenario.getNetwork().getLinks().values()) {

			/*
			 * Use the centre point of each MATSim link to determine
			 * whether the link lies inside the policy area.
			 */
			double centreX =
				(link.getFromNode().getCoord().getX()
					+ link.getToNode().getCoord().getX()) / 2.0;

			double centreY =
				(link.getFromNode().getCoord().getY()
					+ link.getToNode().getCoord().getY()) / 2.0;

			boolean insidePolicyArea =
				centreX >= minX
					&& centreX <= maxX
					&& centreY >= minY
					&& centreY <= maxY;

			/*
			 * Only modify links on which cars are allowed.
			 * Bicycle-only, pedestrian and public-transport-only
			 * links are not affected by this policy.
			 */
			boolean carAllowed =
				link.getAllowedModes().contains(TransportMode.car);

			double originalSpeed = link.getFreespeed();
			double originalSpeedKmh = originalSpeed * 3.6;

			/*
			 * The baseline free-flow speed range identifies links
			 * representative of posted 50 km/h roads.
			 * Other links are outside the baseline free-flow speed range.
			 */
			boolean matchesBaselineFreeSpeed =
				originalSpeedKmh >= ORIGINAL_FREESPEED_MIN_KMH
					&& originalSpeedKmh <= ORIGINAL_FREESPEED_MAX_KMH;

			if (!insidePolicyArea
				|| !carAllowed
				|| !matchesBaselineFreeSpeed) {
				continue;
			}

			double policySpeed =
				originalSpeed * SPEED_REDUCTION_FACTOR;

			link.setFreespeed(policySpeed);

			log.info(
				"21 km/h free-flow speed scenario, link {}: {} km/h -> {} km/h; "
					+ "length = {} m",
				link.getId(),
				originalSpeedKmh,
				link.getFreespeed() * 3.6,
				link.getLength()
			);

			modifiedLinks++;
			totalModifiedLengthMeters += link.getLength();
		}

		/*
		 * Stop immediately if the geographical selection did not
		 * identify any links. This prevents an empty policy scenario
		 * from being simulated accidentally.
		 */
		if (modifiedLinks == 0) {
			throw new IllegalStateException(
				"No links were selected for the 21 km/h free-flow speed scenario. "
					+ "Check the coordinates and network CRS."
			);
		}

		log.info(
			"21 km/h free-flow speed scenario completed using factor {}. "
				+ "Modified links: {}. Total link length: {} km.",
			SPEED_REDUCTION_FACTOR,
			modifiedLinks,
			totalModifiedLengthMeters / 1000.0
		);
	}
}
