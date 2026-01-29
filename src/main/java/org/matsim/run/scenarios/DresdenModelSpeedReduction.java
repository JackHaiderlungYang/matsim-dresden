package org.matsim.run.scenarios;

import org.locationtech.jts.geom.prep.PreparedGeometry;
import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.utils.gis.shp2matsim.ShpGeometryUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public final class DresdenModelSpeedReduction extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25
	private static final Logger log = LogManager.getLogger(DresdenModelSpeedReduction.class); // to create a logger, make sure if there is something wrong in it

	@CommandLine.Option(
		names = "--slow-speed-shp",
		description = "Path to shp file for adaption of link speeds.",
		defaultValue = "../v1.0/vvo_tarifzone_10_dresden/v1.0_vvo_tarifzone_10_dresden_utm32n.shp"
	)
	private String slowSpeedAreaShp; // commit a shp document, to slow down the speed in the area

	@CommandLine.Option(
		names = "--slow-speed-relative-change",
		description = "Provide a value 0.0 < value < 1.0. Default 0.6 (50km/h -> 30km/h).",
		defaultValue = "0.6"
	)
	private double relativeSpeedChange; // a speed reduction factor, to slow down the free-flow speed of the selected links

//	public static final String VERSION = "v1.0";

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			args = new String[]{
				"--1pct",
				"--iterations", "10",
				"--output", "./output/speed-reduction/",
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",// Refresh the output
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"};
			// Compare the test output with the expected output
			// yy If we collaborate on code, could you please do comments in english?  Thanks.  kai, dec'25
		}

		MATSimApplication.execute(DresdenModelSpeedReduction.class, args);
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
		List<PreparedGeometry> geometries =
			ShpGeometryUtils.loadPreparedGeometries(
				IOUtils.extendUrl(scenario.getConfig().getContext(), slowSpeedAreaShp)
			); // make a list for slow speed in a area

		Set<? extends Link> carLinksInArea = scenario.getNetwork().getLinks().values().stream() // get all the network links
			.filter(link -> link.getAllowedModes().contains(TransportMode.car)) // only keep the links for car
			.filter(link -> ShpGeometryUtils.isCoordInPreparedGeometries(link.getCoord(), geometries)) // check if the linkId in the place we need
			.filter(link -> {
				Object typeObj = link.getAttributes().getAttribute("type");
				String type = typeObj == null ? "" : typeObj.toString();
				return !type.contains("motorway"); // move out all the type of motorway
			})
			.collect(Collectors.toSet()); // collection a Set

		if (relativeSpeedChange > 0.0 && relativeSpeedChange < 1.0) {
			log.info("Reducing freespeed within shp-area by factor: {}", relativeSpeedChange);
			carLinksInArea.forEach(link -> link.setFreespeed(link.getFreespeed() * relativeSpeedChange));
			log.info("Updated freespeed on {} links.", carLinksInArea.size());
		} else {
			throw new IllegalArgumentException(
				"Invalid --slow-speed-relative-change: " + relativeSpeedChange + " (expected 0.0 < v < 1.0)"
			);
		} // reductive the speed
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
