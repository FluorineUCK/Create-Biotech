package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import java.util.Objects;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;

/** Test-only bridge to the package-private topology-owner binding seam. */
public final class PanelTopologyTestHarness {
	private PanelTopologyTestHarness() {}

	public static void install(FactoryRuntimeService runtime,
		RadioTopologyPublicationAuthority authority) {
		Objects.requireNonNull(runtime, "runtime")
			.installPanelCommunicationTopologyAuthorityForTest(
				Objects.requireNonNull(authority, "authority"));
	}
}
