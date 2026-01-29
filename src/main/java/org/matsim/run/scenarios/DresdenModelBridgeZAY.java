package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;
import org.matsim.facilities.FacilitiesUtils;

import java.util.Set;

public final class DresdenModelBridgeZAY extends DresdenModel {

	public static void main(String[] args) {
		if (args == null || args.length == 0) {
			args = new String[]{
				"--1pct",
				"--iterations", "10",
				"--output", "./output/bridge_link_removal/",
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"};
		}
		MATSimApplication.execute(DresdenModelBridgeZAY.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
	//	this.network = scenario.getNetwork();
	//	this.facilities = scenario.getActivityFacilities();

		Set<Id<Link>> closedLinks = Set.of(
			Id.createLinkId(-488766980),
			Id.createLinkId(761288685),
			Id.createLinkId("-264360396#1"),
			Id.createLinkId("505502627#0"),
			Id.createLinkId(132572494),
			Id.createLinkId(277710971),
			Id.createLinkId(4214231),
			Id.createLinkId(901959078),
			Id.createLinkId(1031454500),
			Id.createLinkId(-264360404),
			Id.createLinkId(30129851),
			Id.createLinkId(-30129851),
			Id.createLinkId(425728245),
			Id.createLinkId(14448952)
		);

		for (Id<Link> closedLinkId : closedLinks) {
			scenario.getNetwork().removeLink(closedLinkId);
		}

		NetworkUtils.cleanNetwork( scenario );
		PopulationUtils.cleanPopulation( scenario );
		FacilitiesUtils.cleanFacilities( scenario );

		Set<String> set = CollectionUtils.stringArrayToSet(new String[]{TransportMode.car, "truck8t", "truck18t", "truck40t", "ride", "bike"});
		NetworkUtils.cleanNetwork(scenario.getNetwork(), set);

		Population population = scenario.getPopulation();
		Network network = scenario.getNetwork();
		PopulationUtils.checkRouteModeAndReset(population, network);

		for (Person person : scenario.getPopulation().getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				for (PlanElement planElement : plan.getPlanElements()) {
					if (planElement instanceof Leg) {
						((Leg) planElement).setRoute(null);
					}
				}
			}
		}

		ActivityFacilities facilities = scenario.getActivityFacilities();
		for (ActivityFacility facility : facilities.getFacilities().values()) {
			Id<Link> facilityLinkId = facility.getLinkId();
			if (facilityLinkId != null) {
				if (network.getLinks().get(facilityLinkId) == null) {
					((ActivityFacilityImpl) facility).setLinkId(null);
					// yyyy would be better to rerun XY2Links
				}
			}
		}
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
	}
}
