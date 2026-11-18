package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Logs schedule line IDs, route modes, route counts, departure counts and line attributes
 * during scenario preparation. Use these IDs to identify lines in the loaded input schedule.
 */
public final class DresdenModelPtInspection extends DresdenModel {

	private static final Logger log =
		LogManager.getLogger(DresdenModelPtInspection.class);

	public static void main(String[] args) {
		MATSimApplication.execute(DresdenModelPtInspection.class, args);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		log.info("Number of transit lines: {}",
			scenario.getTransitSchedule().getTransitLines().size());

		for (TransitLine line :
			scenario.getTransitSchedule().getTransitLines().values()) {

			Set<String> modes = line.getRoutes().values().stream()
				.map(TransitRoute::getTransportMode)
				.collect(Collectors.toSet());

			int departures = line.getRoutes().values().stream()
				.mapToInt(route -> route.getDepartures().size())
				.sum();

			log.info(
				"PT_LINE | id={} | modes={} | routes={} | departures={} | attributes={}",
				line.getId(),
				modes,
				line.getRoutes().size(),
				departures,
				line.getAttributes()
			);
		}
	}
}
