package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.roadpricing.RoadPricingModule;
import org.matsim.contrib.roadpricing.RoadPricingScheme;
import org.matsim.contrib.roadpricing.RoadPricingSchemeImpl;
import org.matsim.contrib.roadpricing.RoadPricingUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

public final class DresdenModelRoadPricing extends DresdenModel {

	private static final Logger log =
		LogManager.getLogger(DresdenModelRoadPricing.class);

	/*
	 * Preliminary road-pricing policy:
	 * Cars entering the selected central Dresden area pay EUR 5.
	 * Public transport, cycling and walking are not charged.
	 */
	private static final double TOLL_EUR = 5.0;

	/*
	 * Use the same geographical area as the 30 km/h policy
	 * to make both policy scenarios directly comparable.
	 */
	private static final double MIN_LAT = 51.049;
	private static final double MAX_LAT = 51.064;
	private static final double MIN_LON = 13.724;
	private static final double MAX_LON = 13.748;

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelRoadPricing.class, args);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

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

		RoadPricingSchemeImpl scheme =
			RoadPricingUtils.addOrGetMutableRoadPricingScheme(scenario);

		RoadPricingUtils.setType(
			scheme,
			RoadPricingScheme.TOLL_TYPE_AREA
		);

		RoadPricingUtils.setName(
			scheme,
			"Central Dresden road-pricing area"
		);

		RoadPricingUtils.setDescription(
			scheme,
			"EUR 5 area toll for car traffic in central Dresden"
		);

		/*
		 * The toll is active from 00:00 until 30:00.
		 * The extended end time also covers trips ending after midnight.
		 */
		RoadPricingUtils.createAndAddGeneralCost(
			scheme,
			0.0,
			30.0 * 3600.0,
			TOLL_EUR
		);

		int tolledLinks = 0;
		double totalLengthMeters = 0.0;

		for (Link link : scenario.getNetwork().getLinks().values()) {

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

			boolean carAllowed =
				link.getAllowedModes().contains(TransportMode.car);

			if (!insidePolicyArea || !carAllowed) {
				continue;
			}

			RoadPricingUtils.addLink(scheme, link.getId());

			tolledLinks++;
			totalLengthMeters += link.getLength();
		}

		if (tolledLinks == 0) {
			throw new IllegalStateException(
				"No car links were selected for the road-pricing area."
			);
		}

		log.info(
			"Road-pricing area completed: toll = {} EUR, "
				+ "tolled links = {}, total link length = {} km.",
			TOLL_EUR,
			tolledLinks,
			totalLengthMeters / 1000.0
		);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
		controler.addOverridingModule(new RoadPricingModule());
	}
}
