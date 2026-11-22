package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

import net.minecraft.server.MinecraftServer;

/** Test-only immutable view over an installed production Runtime. */
public final class FactoryOrchestrationServerTestAccess implements AutoCloseable {
	private final MinecraftServer server;

	private FactoryOrchestrationServerTestAccess(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
	}

    public static void restartHistoricalWorld(MinecraftServer server) {
        FactoryOrchestrationServer.installHistoricalFixture(server, FactoryRuntimeConfig.fromServerConfig(),
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionRuntimeServices.get(server),
            FactoryOrchestrationSavedData.get(server));
    }

	public static FactoryOrchestrationServerTestAccess install(MinecraftServer server,
		FactoryRuntimeConfig config, FactoryRuntimeService runtime,
		FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, runtime, journal,
			attachedNetworks);
		return new FactoryOrchestrationServerTestAccess(server);
	}

	public static void installWithFailingNativeBinding(MinecraftServer server,
		FactoryRuntimeConfig config, FactoryRuntimeService runtime,
		FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, runtime, journal,
			attachedNetworks, (ignoredServer, ignoredPublisher) -> {
				throw new IllegalStateException("test native binding failure");
			});
	}

	public static FactoryOrchestrationServerTestAccess installWithLogisticsScan(
		MinecraftServer server, FactoryRuntimeConfig config, FactoryRuntimeService runtime,
		FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
		Supplier<Set<UUID>> liveLogisticsNetworks) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, runtime, journal,
			attachedNetworks, liveLogisticsNetworks);
		return new FactoryOrchestrationServerTestAccess(server);
	}

	public static FactoryOrchestrationServerTestAccess installWithReentrantNativeBinding(
		MinecraftServer server, FactoryRuntimeConfig config, FactoryRuntimeService outerRuntime,
		FactoryOrchestrationSavedData outerJournal, FactoryRuntimeService competingRuntime,
		FactoryOrchestrationSavedData competingJournal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks,
		AtomicBoolean reentrantInstallRejected) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, outerRuntime, outerJournal,
			attachedNetworks, (exactServer, publisher) -> {
				publisher.recordRecoveryRequired(NativePackageObservation.RecoveryContext.global(
					null, null), null, new IllegalStateException("unpublished ingress"));
				try {
					FactoryOrchestrationServer.installRuntimeForTest(exactServer, config,
						competingRuntime, competingJournal, attachedNetworks);
				} catch (IllegalStateException expected) {
					reentrantInstallRejected.set(true);
				}
				return NativePackageObservation.install(exactServer, publisher);
			});
		return new FactoryOrchestrationServerTestAccess(server);
	}

	public static void installWithNativeBindingFailureAfterInstall(MinecraftServer server,
		FactoryRuntimeConfig config, FactoryRuntimeService runtime,
		FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks, boolean returnNull,
		AtomicReference<AutoCloseable> installedBinding) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, runtime, journal,
			attachedNetworks, (exactServer, publisher) -> {
				installedBinding.set(NativePackageObservation.install(exactServer, publisher));
				if (returnNull) return null;
				throw new IllegalStateException("test failure after native binding install");
			});
	}

	public static void installWithRuntimeClosedAfterNativeBinding(MinecraftServer server,
		FactoryRuntimeConfig config, FactoryRuntimeService runtime,
		FactoryOrchestrationSavedData journal,
		Function<PackagerBlockEntity, Set<UUID>> attachedNetworks) {
		FactoryOrchestrationServer.installRuntimeForTest(server, config, runtime, journal,
			attachedNetworks, (exactServer, publisher) -> {
				AutoCloseable binding = NativePackageObservation.install(exactServer, publisher);
				runtime.close();
				return binding;
			});
	}

	public static boolean hasNativePublisher(MinecraftServer server) {
		return FactoryOrchestrationServer.hasNativePublisherForTest(server);
	}

	public boolean observeExternalListener(ProductionComputer computer,
		CommunicationFrequency primary, SpaceAddress address) {
		return FactoryOrchestrationServer.observeExternalListenerForTest(server, computer, primary,
			address);
	}

	public com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService.EditResult
		replaceExternalListeners(ProductionComputer computer, long expectedRevision,
			List<CommunicationFrequency> frequencies) {
		return FactoryOrchestrationServer.replaceExternalListenersForTest(server, computer,
			expectedRevision, frequencies);
	}

	public boolean hasBoundary(UUID clusterId) {
		return FactoryOrchestrationServer.hasRadioBoundaryForTest(server, clusterId);
	}

	public TaskControlState taskControlState() {
		FactoryOrchestrationServer.TaskControlState state =
			FactoryOrchestrationServer.taskControlStateForTest(server);
		return new TaskControlState(state.suspendedInputDispatchClusters(),
			state.droppedRecoveryAcknowledgements(), state.recoveryAdmissionCeilings());
	}

	public RuntimeAuthoritySnapshot authoritySnapshot() {
		FactoryOrchestrationServer.RuntimeAuthoritySnapshot state =
			FactoryOrchestrationServer.runtimeAuthoritySnapshotForTest(server);
		return new RuntimeAuthoritySnapshot(state.routerAuthorityRevision(), state.activeRouteCount(),
			state.outbox());
	}

	public long radioTopologySampleTick() {
		return FactoryOrchestrationServer.radioTopologySampleTickForTest(server);
	}

	/** Complete router authority from this installed production composition root. */
	public com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter.AuthorityState
		radioAuthorityState() {
		return FactoryOrchestrationServer.radioAuthorityStateForTest(server);
	}

	public boolean offerRadio(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays) {
		return FactoryOrchestrationServer.offerRadioForTest(server, envelope, relays);
	}

	public List<RadioRouter.Delivery> pumpRadioOutbox(long serverTick) {
		return FactoryOrchestrationServer.pumpRadioOutboxForTest(server, serverTick);
	}

	public boolean markRadioApplied(UUID messageId) {
		return FactoryOrchestrationServer.markRadioAppliedForTest(server, messageId);
	}

	public int retireAppliedRadioRoutes() {
		return FactoryOrchestrationServer.retireAppliedRadioRoutesForTest(server);
	}

	public boolean hasNativePublisher() {
		return FactoryOrchestrationServer.hasNativePublisherForTest(server);
	}


	public record TaskControlState(Set<UUID> suspendedInputDispatchClusters,
		Set<UUID> droppedRecoveryAcknowledgements, java.util.Map<UUID, Integer> recoveryAdmissionCeilings) {
		public TaskControlState {
			suspendedInputDispatchClusters = Set.copyOf(suspendedInputDispatchClusters);
			droppedRecoveryAcknowledgements = Set.copyOf(droppedRecoveryAcknowledgements);
			recoveryAdmissionCeilings = java.util.Map.copyOf(recoveryAdmissionCeilings);
		}
	}

	public record RuntimeAuthoritySnapshot(long routerAuthorityRevision, int activeRouteCount,
		DurableRadioOutbox.State outbox) {}

	@Override public void close() { FactoryOrchestrationServer.stop(server); }
}
