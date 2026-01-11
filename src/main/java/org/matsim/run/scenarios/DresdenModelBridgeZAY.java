package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;

import java.util.Set;

public final class DresdenModelBridgeZAY extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25
	private static final Logger log = LogManager.getLogger(DresdenModelBridgeZAY.class);

	// change speed to 0.6
	private static final double RELATIVE_SPEED_CHANGE = 0.6;

	// only those linkIds are changed
	private static final Set<Id<Link>> BRIDGE_LINKS = Set.of(
		Id.createLinkId(-488766980),
		Id.createLinkId(761288685),
		Id.createLinkId("-264360396#1"),
		Id.createLinkId("505502627#0"),
		Id.createLinkId( 132572494 ), // providing a number instead of a string is also ok. kai, dec'25
		Id.createLinkId( 277710971 ),
		Id.createLinkId( 4214231 ),
		Id.createLinkId( 901959078 ),
		Id.createLinkId( 1031454500 ),
		Id.createLinkId( -264360404 ),
		Id.createLinkId(30129851),
		Id.createLinkId(-30129851),
		Id.createLinkId(425728245),
		Id.createLinkId(14448952)
	);

//	public static final String VERSION = "v1.0";

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			args = new String[]{
				"--1pct",
				"--iterations", "10",
				"--output", "./output/bridge_speed_reduction/",
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",// Refresh the output
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"};
			// Compare the test output with the expected output
			// yy If we collaborate on code, could you please do comments in english?  Thanks.  kai, dec'25
		}

		MATSimApplication.execute(DresdenModelBridgeZAY.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig( config );
		// add own config modifications here:

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario( scenario );

		// make sure the speed chance is legal or not
		if (RELATIVE_SPEED_CHANGE >= 0.0 && RELATIVE_SPEED_CHANGE < 1.0) {
			log.info(
				"Policy: reducing freespeed on {} specified bridge links (car only) by factor: {}",
				BRIDGE_LINKS.size(),
				RELATIVE_SPEED_CHANGE
			); // how many bridge are closed

			int changed = 0; // count the real number of closed bridges

			for (Id<Link> linkId : BRIDGE_LINKS) {
				Link link = scenario.getNetwork().getLinks().get(linkId);

				if (link == null) {
					log.warn("Bridge link {} not found in network – skipping.", linkId);
					continue;
				}

				if (link.getAllowedModes().contains(TransportMode.car)) {
					link.setFreespeed(link.getFreespeed() * RELATIVE_SPEED_CHANGE);
					changed++;
				} // only change speed to 0.6 of the linkIds for car
			}

			log.info("Speed reduction applied to {} / {} bridge links (car only).", changed, BRIDGE_LINKS.size());
		} else {
			log.fatal(
				"Speed reduction factor {} is invalid. Please put a 0.0 <= value < 1.0",
				RELATIVE_SPEED_CHANGE
			);
			throw new IllegalArgumentException("Invalid RELATIVE_SPEED_CHANGE: " + RELATIVE_SPEED_CHANGE);
		} // check if is it get some errors, when get then stop
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
