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

public final class DresdenModelSpeedLimit extends DresdenModel {

	private static final Logger log =
		LogManager.getLogger(DresdenModelSpeedLimit.class);

	/*
	 * Policy definition:
	 * Existing posted speed limit: 50 km/h
	 * New posted speed limit:      30 km/h
	 *
	 * The MATSim network represents a posted speed of 50 km/h
	 * with a freespeed of approximately 35 km/h.
	 * Therefore, the original MATSim freespeed is multiplied by:
	 *
	 * 30 / 50 = 0.6
	 */
	private static final double BASE_POSTED_SPEED_KMH = 50.0;
	private static final double POLICY_POSTED_SPEED_KMH = 30.0;
	private static final double SPEED_REDUCTION_FACTOR =
		POLICY_POSTED_SPEED_KMH / BASE_POSTED_SPEED_KMH;

	/*
	 * Candidate 30 km/h zone in central Dresden.
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
			 * Select only links representing roads with an original
			 * posted speed limit of approximately 50 km/h.
			 *
			 * Roads already corresponding to 30 km/h or below remain
			 * unchanged. Motorways and other faster roads are also
			 * excluded.
			 */
			boolean representsPosted50Kmh =
				originalSpeedKmh >= ORIGINAL_FREESPEED_MIN_KMH
					&& originalSpeedKmh <= ORIGINAL_FREESPEED_MAX_KMH;

			if (!insidePolicyArea
				|| !carAllowed
				|| !representsPosted50Kmh) {
				continue;
			}

			/*
			 * Apply the relative change from posted 50 km/h
			 * to posted 30 km/h:
			 *
			 * new MATSim freespeed = original freespeed * 0.6
			 */
			double policySpeed =
				originalSpeed * SPEED_REDUCTION_FACTOR;

			link.setFreespeed(policySpeed);

			log.info(
				"30 km/h zone link {}: {} km/h -> {} km/h; "
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
				"No links were selected for the 30 km/h zone. "
					+ "Check the coordinates and network CRS."
			);
		}

		log.info(
			"30 km/h zone completed: changed posted speed from "
				+ "{} km/h to {} km/h using factor {}. "
				+ "Modified links: {}. Total link length: {} km.",
			BASE_POSTED_SPEED_KMH,
			POLICY_POSTED_SPEED_KMH,
			SPEED_REDUCTION_FACTOR,
			modifiedLinks,
			totalModifiedLengthMeters / 1000.0
		);
	}
}
