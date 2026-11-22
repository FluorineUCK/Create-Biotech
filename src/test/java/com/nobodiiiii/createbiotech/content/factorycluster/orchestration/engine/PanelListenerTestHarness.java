package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;

/** Test-only bridge to the package-private fake-store lifecycle seam. */
public final class PanelListenerTestHarness {
	private PanelListenerTestHarness() {}

	public static Registration install(FactoryRuntimeService runtime,
		ExternalListenerService service) {
		return new Registration(Objects.requireNonNull(runtime, "runtime")
			.installPanelListenerTestRegistry(Objects.requireNonNull(service, "service")));
	}

	public static final class Registration {
		private final FactoryRuntimeService.PanelListenerTestRegistry registry;
		private Registration(FactoryRuntimeService.PanelListenerTestRegistry registry) {
			this.registry = registry;
		}
		public void observe(UUID clusterId, UUID epochId,
			ExternalListenerService.ListenerComputer computer) {
			registry.observe(clusterId, epochId, computer);
		}
		public void retire(ExternalListenerService.ListenerOwner owner) {
			registry.retire(owner);
		}
		public void configurationChanged(ExternalListenerService.ListenerOwner owner) {
			registry.configurationChanged(owner);
		}
	}
}
