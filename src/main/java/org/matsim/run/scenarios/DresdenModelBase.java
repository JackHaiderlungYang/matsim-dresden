package org.matsim.run.scenarios;

import jakarta.annotation.Nullable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;

import java.util.Set;

public final class DresdenModelBase extends DresdenModel {
	// "final": for the time being, please try to avoid inheritance from inheritance.  kai, dec'25

//	public static final String VERSION = "v1.0";

	public static void main(String[] args) {
		if ( args != null && args.length > 0 ) {
			// use the given args
		} else{
			args = new String[]{
				"--1pct",
				"--iterations", "10",
				"--output", "./output/base_version/",
				"--config:controller.overwriteFiles=deleteDirectoryIfExists",// Refresh the output
				"--config:global.numberOfThreads", "2",
				"--config:qsim.numberOfThreads", "2",
				"--config:simwrapper.defaultDashboards", "disabled",
				"--emissions", "DISABLED"};
			// Compare the test output with the expected output
			// yy If we collaborate on code, could you please do comments in english?  Thanks.  kai, dec'25
		}

		MATSimApplication.execute(DresdenModelBase.class, args);
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
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler( controler );
		// add own Controller configurations here:
	}

}
