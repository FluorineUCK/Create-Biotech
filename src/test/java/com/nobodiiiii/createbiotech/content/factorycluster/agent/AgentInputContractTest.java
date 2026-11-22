package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class AgentInputContractTest {
	private static final UUID AGENT = id(1);
	private static final UUID RUN = id(2);
	private static final UUID OUTPUT_EPOCH = id(3);
	private static final CreateRouteKey ROUTE_A = new CreateRouteKey(id(4), "input-a");
	private static final CreateRouteKey ROUTE_B = new CreateRouteKey(id(4), "input-b");
	private static StackKey STONE;
	private static StackKey DIRT;

	@BeforeAll static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		STONE = key(Items.STONE);
		DIRT = key(Items.DIRT);
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class,
				Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test void followsTheOnlyLegalInputPathAndPersistsIntentBeforeExposingCommand() {
		RecordingStore store = new RecordingStore();
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 4), 5, store);

		assertEquals(AgentInputPhase.QUEUED, contract.phase());
		assertTrue(contract.markCurrentStageReady(17, manifest(STONE, 4), Set.of(ROUTE_A), 31));
		assertEquals(AgentInputPhase.READY, contract.phase());
		CreateDispatchCommand command = contract.beginDispatch(id(10), 17).orElseThrow();
		assertEquals(AgentInputPhase.DISPATCH_INTENT, store.states.getLast().phase(),
			"the irreversible intent must be durable before the command is returned");
		assertEquals(17, command.fence());
		assertEquals(5, command.agentResetGeneration());
		assertEquals(OUTPUT_EPOCH, command.outputObservationEpoch());

		CreateLogisticsGateway.CommitReceipt committed = committed(command,
			new NativeOrderLedger.NativeOrderIdentity(101, Set.of(0)));
		assertTrue(contract.confirmDispatch(committed));
		assertEquals(AgentInputPhase.DISPATCH_CONFIRMED, contract.phase());
		assertTrue(contract.closeInput(17));
		assertEquals(AgentInputPhase.INPUT_CLOSED, contract.phase());
		assertEquals(AgentInputContract.ObservationResult.ACCEPTED,
			contract.observe(packageAt(ROUTE_A, 101, 0, 0, true, true,
				manifest(STONE, 4), manifest(STONE, 4))));
		assertTrue(contract.endInput(17));
		assertEquals(AgentInputPhase.INPUT_ENDED, contract.phase());
	}

	@Test void rejectsEndBeforeCloseDuplicateIntentAndMismatchedFence() {
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 2), 5,
			state -> {});
		assertFalse(contract.markCurrentStageReady(18, manifest(STONE, 2), Set.of(ROUTE_A), 9));
		assertTrue(contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 9));
		CreateDispatchCommand command = contract.beginDispatch(id(20), 17).orElseThrow();
		assertTrue(contract.beginDispatch(id(21), 17).isEmpty());
		assertFalse(contract.endInput(17));
		assertFalse(contract.confirmDispatch(committed(command.withFence(18),
			new NativeOrderLedger.NativeOrderIdentity(102, Set.of(0)))));
		assertEquals(AgentInputPhase.DISPATCH_INTENT, contract.phase());
	}

	@Test void knownShortageAndMissingLoadedInputNeverEnterReady() {
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 2), 5,
			state -> {});
		assertFalse(contract.markCurrentStageReady(17, manifest(STONE, 1), Set.of(ROUTE_A), 1));
		assertFalse(contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(), 1));
		assertEquals(AgentInputPhase.QUEUED, contract.phase());
	}

	@Test void constructorRejectsIncompleteOrMisaddressedFrozenManifests() {
		ProductionRun run = run(InputMode.BULK, List.of(ROUTE_A), manifest(STONE, 4));
		assertThrows(IllegalArgumentException.class, () -> new AgentInputContract(AGENT, 5,
			OUTPUT_EPOCH, run, AgentInputContract.ExecutionShape.STANDARD,
			List.of(new AgentInputContract.StageInput(0, ROUTE_A, manifest(STONE, 3))), 20,
			state -> {}));
		assertThrows(IllegalArgumentException.class, () -> new AgentInputContract(AGENT, 5,
			OUTPUT_EPOCH, run, AgentInputContract.ExecutionShape.STANDARD,
			List.of(new AgentInputContract.StageInput(0, ROUTE_B, manifest(STONE, 4))), 20,
			state -> {}));
	}

	@Test void inputCannotEndWithoutEveryFragmentAndNativeFinalMarker() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.BULK, manifest(STONE, 4), 201,
			Set.of(0));
		contract.observe(packageAt(ROUTE_A, 201, 0, 0, true, false,
			manifest(STONE, 2), manifest(STONE, 4)));
		assertFalse(contract.endInput(17), "a final fragment marker is mandatory");
		contract.observe(packageAt(ROUTE_A, 201, 0, 2, true, true,
			manifest(STONE, 2), Map.of()));
		assertFalse(contract.endInput(17), "fragment indexes must be contiguous");
	}

	@Test void nativeSignedOrderIdsRemainValidWhenCreateRandomlyChoosesANegativeInt() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.BULK, manifest(STONE, 2), -7,
			Set.of(0));
		assertEquals(AgentInputContract.ObservationResult.ACCEPTED,
			contract.observe(packageAt(ROUTE_A, -7, 0, 0, true, true,
				manifest(STONE, 2), manifest(STONE, 2))));
		assertTrue(contract.endInput(17));
	}

	@Test void packagesForAnotherAddressAreIgnoredAndDoNotCompleteManifest() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.BULK, manifest(STONE, 2), 301,
			Set.of(0));
		assertEquals(AgentInputContract.ObservationResult.IGNORED_OTHER_ADDRESS,
			contract.observe(packageAt(ROUTE_B, 301, 0, 0, true, true,
				manifest(STONE, 2), manifest(STONE, 2))));
		assertFalse(contract.endInput(17));
		assertEquals(0, contract.interferenceCount());
	}

	@Test void duplicateNativeObservationIsIdempotentRatherThanInterference() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.EXACT, manifest(STONE, 2), 302,
			Set.of(0));
		NativePackageObservation observation = packageAt(ROUTE_A, 302, 0, 0, true, true,
			manifest(STONE, 2), manifest(STONE, 2));
		assertEquals(AgentInputContract.ObservationResult.ACCEPTED, contract.observe(observation));
		assertEquals(AgentInputContract.ObservationResult.DUPLICATE, contract.observe(observation));
		assertEquals(0, contract.interferenceCount());
		assertTrue(contract.endInput(17));
	}

	@Test void unloadPausesStallTimeWhileLoadedNoProgressStallsAtConfiguredInterval() {
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 2), 5,
			state -> {}, 5);
		contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 1);
		contract.beginDispatch(id(40), 17);
		contract.advanceStallClock(4, true, false);
		contract.advanceStallClock(100, false, false);
		assertEquals(4, contract.loadedNoProgressTicks());
		assertTrue(contract.waitingForAgentLoad());
		assertEquals(AgentInputPhase.DISPATCH_INTENT, contract.phase());
		contract.advanceStallClock(1, true, false);
		assertEquals(AgentInputPhase.INPUT_STALLED, contract.phase());
		assertTrue(contract.leaseRetained());
	}

	@Test void progressResetsTheLoadedStallClock() {
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 2), 5,
			state -> {}, 5);
		contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 1);
		contract.beginDispatch(id(41), 17);
		contract.advanceStallClock(4, true, false);
		contract.advanceStallClock(1, true, true);
		assertEquals(0, contract.loadedNoProgressTicks());
		assertEquals(AgentInputPhase.DISPATCH_INTENT, contract.phase());
	}

	@Test void bulkInterferenceIsDiagnosticOnly() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.BULK, manifest(STONE, 2), 401,
			Set.of(0));
		assertEquals(AgentInputContract.ObservationResult.INTERFERENCE_DIAGNOSTIC,
			contract.observe(packageAt(ROUTE_A, 999, 0, 0, true, true,
				manifest(DIRT, 1), manifest(DIRT, 1))));
		assertEquals(AgentInputPhase.INPUT_CLOSED, contract.phase());
		assertEquals(1, contract.interferenceCount());
	}

	@Test void exactInterferenceRetainsLeaseAndRequiresReset() {
		AgentInputContract contract = dispatchedAndClosed(InputMode.EXACT, manifest(STONE, 2), 501,
			Set.of(0));
		contract.advanceStallClock(4, true, false);
		contract.advanceStallClock(0, false, false);
		assertEquals(AgentInputContract.ObservationResult.RESET_REQUIRED,
			contract.observe(packageAt(ROUTE_A, 999, 0, 0, true, true,
				manifest(DIRT, 1), manifest(DIRT, 1))));
		assertEquals(AgentInputPhase.RESET_REQUIRED, contract.phase());
		assertTrue(contract.leaseRetained());
		assertEquals(0, contract.loadedNoProgressTicks());
		assertFalse(contract.waitingForAgentLoad());
		assertEquals(AgentInputPhase.RESET_REQUIRED,
			AgentInputContract.restore(contract.snapshot(), state -> {}).phase());
	}

	@Test void sequencedStagesReuseFencesAndCannotDispatchUntilPreviousStageEnded() {
		ProductionRun run = run(InputMode.BULK, List.of(ROUTE_A, ROUTE_B),
			manifest(STONE, 2, DIRT, 3));
		AgentInputContract contract = new AgentInputContract(AGENT, 5, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.SEQUENCED, List.of(
				new AgentInputContract.StageInput(0, ROUTE_A, manifest(STONE, 2)),
				new AgentInputContract.StageInput(1, ROUTE_B, manifest(DIRT, 3))), 20, state -> {});
		assertTrue(contract.markCurrentStageReady(17, manifest(STONE, 2),
			Set.of(ROUTE_A, ROUTE_B), 8));
		CreateDispatchCommand first = contract.beginDispatch(id(60), 17).orElseThrow();
		assertEquals(0, first.stageIndex());
		assertTrue(contract.advanceStage().isEmpty());
		contract.confirmDispatch(committed(first,
			new NativeOrderLedger.NativeOrderIdentity(601, Set.of(0))));
		contract.closeInput(17);
		contract.observe(packageAt(ROUTE_A, 601, 0, 0, true, true,
			manifest(STONE, 2), manifest(STONE, 2)));
		assertTrue(contract.endInput(17));

		AgentInputContract.StageInput next = contract.advanceStage().orElseThrow();
		assertEquals(ROUTE_B, next.route());
		assertTrue(contract.markCurrentStageReady(17, manifest(DIRT, 3), Set.of(ROUTE_B), 9));
		CreateDispatchCommand second = contract.beginDispatch(id(61), 17).orElseThrow();
		assertEquals(1, second.stageIndex());
		assertEquals(first.agentId(), second.agentId());
		assertEquals(first.agentResetGeneration(), second.agentResetGeneration());
		assertEquals(first.fence(), second.fence());
		assertEquals(first.outputObservationEpoch(), second.outputObservationEpoch());
	}

	@Test void sequencedStageManifestsAreIndependentFromTheRunsTopLevelMaterialPrecheck() {
		ProductionRun run = run(InputMode.BULK, List.of(ROUTE_A, ROUTE_B),
			manifest(key(Items.PAPER), 1));
		AgentInputContract contract = new AgentInputContract(AGENT, 5, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.SEQUENCED, List.of(
				new AgentInputContract.StageInput(0, ROUTE_A, manifest(STONE, 2)),
				new AgentInputContract.StageInput(1, ROUTE_B, manifest(DIRT, 3))), 20, state -> {});
		assertTrue(contract.markCurrentStageReady(17, manifest(STONE, 2),
			Set.of(ROUTE_A, ROUTE_B), 9));
		assertEquals(manifest(STONE, 2),
			contract.beginDispatch(id(62), 17).orElseThrow().manifest());
	}

	@Test void cancellationOrSequencedInterferenceStopsAllLaterStages() {
		AgentInputContract cancelled = sequencedAtFirstIntent(70);
		cancelled.stopAfterIrreversibleBoundary(17);
		assertEquals(AgentInputPhase.RESET_REQUIRED, cancelled.phase());
		assertTrue(cancelled.advanceStage().isEmpty());

		AgentInputContract interfered = sequencedAtFirstIntent(71);
		assertEquals(AgentInputContract.ObservationResult.RESET_REQUIRED,
			interfered.observe(packageAt(ROUTE_A, 999, 0, 0, true, true,
				manifest(DIRT, 1), manifest(DIRT, 1))));
		assertTrue(interfered.advanceStage().isEmpty());
	}

	@Test void cancellationBetweenSequencedStagesCannotRaceTheNextDispatch() {
		AgentInputContract contract = sequencedAtFirstIntent(72);
		CreateDispatchCommand first = contract.snapshot().currentCommand().orElseThrow();
		contract.confirmDispatch(committed(first,
			new NativeOrderLedger.NativeOrderIdentity(701, Set.of(0))));
		contract.closeInput(17);
		contract.observe(packageAt(ROUTE_A, 701, 0, 0, true, true,
			manifest(STONE, 2), manifest(STONE, 2)));
		assertTrue(contract.endInput(17));
		assertTrue(contract.stopAfterIrreversibleBoundary(17));
		assertEquals(AgentInputPhase.RESET_REQUIRED, contract.phase());
		assertTrue(contract.advanceStage().isEmpty());
	}

	@Test void restoredDispatchIntentCannotExposeASecondCreateCommand() {
		RecordingStore originalStore = new RecordingStore();
		AgentInputContract original = standard(InputMode.BULK, manifest(STONE, 2), 5,
			originalStore);
		assertTrue(original.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 31));
		CreateDispatchCommand command = original.beginDispatch(id(73), 17).orElseThrow();

		RecordingStore restoredStore = new RecordingStore();
		AgentInputContract restored = AgentInputContract.restore(original.snapshot(), restoredStore);
		assertEquals(AgentInputPhase.DISPATCH_INTENT, restored.phase());
		assertEquals(command, restored.snapshot().currentCommand().orElseThrow());
		assertTrue(restored.beginDispatch(id(74), 17).isEmpty());
		assertTrue(restoredStore.states.isEmpty(), "rehydration must not synthesize a transition");
	}

	@Test void restoredNativeLedgerKeepsFragmentEvidenceIdempotentAndCanComplete() {
		AgentInputContract original = dispatchedAndClosed(InputMode.EXACT, manifest(STONE, 4),
			801, Set.of(0));
		NativePackageObservation first = packageAt(ROUTE_A, 801, 0, 0, true, false,
			manifest(STONE, 2), manifest(STONE, 4));
		assertEquals(AgentInputContract.ObservationResult.ACCEPTED, original.observe(first));

		AgentInputContract restored = AgentInputContract.restore(original.snapshot(), state -> {});
		assertEquals(AgentInputContract.ObservationResult.DUPLICATE, restored.observe(first));
		assertEquals(AgentInputContract.ObservationResult.ACCEPTED,
			restored.observe(packageAt(ROUTE_A, 801, 0, 1, true, true,
				manifest(STONE, 2), Map.of())));
		assertTrue(restored.endInput(17));
	}

	@Test void restoredLedgerMustBelongToTheCurrentStageRouteAndManifest() {
		AgentInputContract original = dispatchedAndClosed(InputMode.EXACT, manifest(STONE, 2),
			811, Set.of(0));
		NativePackageObservation wrongRoute = packageAt(ROUTE_B, 811, 0, 0, true, true,
			manifest(STONE, 2), manifest(STONE, 2));
		AgentInputContract.State snapshot = original.snapshot();
		NativeOrderLedger.State originalLedger = snapshot.nativeOrders().orElseThrow();
		NativeOrderLedger.State pollutedRoute = new NativeOrderLedger.State(
			originalLedger.expectedManifest(), originalLedger.expectedOrders(), List.of(wrongRoute),
			manifest(STONE, 2));
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withLedger(snapshot, pollutedRoute), state -> {}));

		NativeOrderLedger.State wrongManifest = new NativeOrderLedger.State(manifest(DIRT, 2),
			originalLedger.expectedOrders(), List.of(), Map.of());
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withLedger(snapshot, wrongManifest), state -> {}));
	}

	@Test void restoreRejectsBrokenLeaseAndStallInvariants() {
		AgentInputContract original = standard(InputMode.BULK, manifest(STONE, 2), 5, state -> {});
		original.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 31);
		original.beginDispatch(id(812), 17);
		AgentInputContract.State snapshot = original.snapshot();
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withProtocol(snapshot, AgentInputPhase.DISPATCH_INTENT, 0, false), state -> {}));
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withProtocol(snapshot, AgentInputPhase.INPUT_STALLED, 0, true), state -> {}));
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withProtocol(snapshot, AgentInputPhase.RESET_REQUIRED, 1, true), state -> {}));

		AgentInputContract ready = standard(InputMode.BULK, manifest(STONE, 2), 5, state -> {});
		ready.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 31);
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withReadySource(ready.snapshot(), java.util.OptionalLong.of(-1)), state -> {}));
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withWaiting(ready.snapshot(), true), state -> {}));

		AgentInputContract queued = standard(InputMode.BULK, manifest(STONE, 2), 5, state -> {});
		assertThrows(IllegalArgumentException.class, () -> AgentInputContract.restore(
			withProtocol(queued.snapshot(), AgentInputPhase.QUEUED, 1, false), state -> {}));
	}

	@Test void resetAfterAStallProducesARestorableFailClosedState() {
		AgentInputContract contract = standard(InputMode.BULK, manifest(STONE, 2), 5,
			state -> {}, 5);
		contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 31);
		contract.beginDispatch(id(816), 17);
		contract.advanceStallClock(5, true, false);
		assertEquals(AgentInputPhase.INPUT_STALLED, contract.phase());
		assertTrue(contract.stopAfterIrreversibleBoundary(17));
		assertEquals(AgentInputPhase.RESET_REQUIRED,
			AgentInputContract.restore(contract.snapshot(), state -> {}).phase());
	}

	@Test void preparedForceResetFenceDoesNotMutateOrPersistBeforeBatchCommit() {
		RecordingStore store = new RecordingStore();
		AgentInputContract contract = standard(InputMode.EXACT, manifest(STONE, 2), 5, store);
		assertTrue(contract.markCurrentStageReady(17, manifest(STONE, 2), Set.of(ROUTE_A), 1));
		CreateDispatchCommand command = contract.beginDispatch(id(818), 17).orElseThrow();
		assertTrue(contract.confirmDispatch(committed(command,
			new NativeOrderLedger.NativeOrderIdentity(-818, Set.of(0)))));
		assertTrue(contract.closeInput(17));
		AgentInputContract.State before = contract.snapshot();
		int writesBefore = store.states.size();

		AgentInputContract.ForceResetPreparation prepared = contract
			.prepareForceResetFence(17).orElseThrow();
		assertEquals(before, contract.snapshot(), "preflight must not expose RESET_REQUIRED");
		assertEquals(writesBefore, store.states.size(), "preflight must not write its private store");
		store.persist(prepared.nextState());
		contract.installCommittedForceResetFence(prepared);

		assertEquals(AgentInputPhase.RESET_REQUIRED, contract.phase());
		assertEquals(prepared.nextState(), contract.snapshot());
	}

	@Test void nextSequencedStageMustRevalidateItsOwnManifestRouteAndSourceSnapshot() {
		AgentInputContract contract = sequencedAtFirstIntent(813);
		CreateDispatchCommand first = contract.snapshot().currentCommand().orElseThrow();
		contract.confirmDispatch(committed(first,
			new NativeOrderLedger.NativeOrderIdentity(813, Set.of(0))));
		contract.closeInput(17);
		contract.observe(packageAt(ROUTE_A, 813, 0, 0, true, true,
			manifest(STONE, 2), manifest(STONE, 2)));
		assertTrue(contract.endInput(17));
		assertEquals(ROUTE_B, contract.advanceStage().orElseThrow().route());
		assertEquals(AgentInputPhase.QUEUED, contract.phase());
		assertTrue(contract.beginDispatch(id(814), 17).isEmpty());
		assertFalse(contract.markCurrentStageReady(17, manifest(DIRT, 2), Set.of(ROUTE_B), 44));
		assertFalse(contract.markCurrentStageReady(17, manifest(DIRT, 3), Set.of(ROUTE_A), 44));
		assertTrue(contract.markCurrentStageReady(17, manifest(DIRT, 3), Set.of(ROUTE_B), 44));
		assertEquals(44, contract.beginDispatch(id(814), 17).orElseThrow()
			.sourceInventorySnapshotGeneration());
	}

	@Test void packageForAnotherManagedSequencedStageIsInterferenceNotUnrelatedTraffic() {
		AgentInputContract contract = sequencedAtFirstIntent(815);
		assertEquals(AgentInputContract.ObservationResult.RESET_REQUIRED,
			contract.observe(packageAt(ROUTE_B, 999, 0, 0, true, true,
				manifest(DIRT, 3), manifest(DIRT, 3))));
		assertEquals(AgentInputPhase.RESET_REQUIRED, contract.phase());
		assertTrue(contract.leaseRetained());
		assertTrue(contract.advanceStage().isEmpty());
	}

	@Test void preDispatchPackageForAnotherManagedStagePersistsAFailClosedReset() {
		ProductionRun run = run(InputMode.BULK, List.of(ROUTE_A, ROUTE_B),
			manifest(STONE, 2, DIRT, 3));
		AgentInputContract contract = new AgentInputContract(AGENT, 5, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.SEQUENCED, List.of(
				new AgentInputContract.StageInput(0, ROUTE_A, manifest(STONE, 2)),
				new AgentInputContract.StageInput(1, ROUTE_B, manifest(DIRT, 3))), 20, state -> {});
		assertEquals(AgentInputContract.ObservationResult.RESET_REQUIRED,
			contract.observe(packageAt(ROUTE_B, 999, 0, 0, true, true,
				manifest(DIRT, 3), manifest(DIRT, 3))));
		assertEquals(AgentInputPhase.RESET_REQUIRED,
			AgentInputContract.restore(contract.snapshot(), state -> {}).phase());
	}

	@Test void dispatchConfirmationRequiresAnOpaqueGatewayReceipt() throws Exception {
		var confirmation = AgentInputContract.class.getMethod("confirmDispatch",
			CreateLogisticsGateway.CommitReceipt.class);
		assertEquals(CreateLogisticsGateway.CommitReceipt.class,
			confirmation.getParameterTypes()[0]);
		for (var constructor : CreateLogisticsGateway.CommitReceipt.class
			.getDeclaredConstructors())
			assertFalse(Modifier.isPublic(constructor.getModifiers()));
	}

	private static AgentInputContract.State withLedger(AgentInputContract.State state,
		NativeOrderLedger.State ledger) {
		return new AgentInputContract.State(state.agentId(), state.agentResetGeneration(),
			state.outputObservationEpoch(), state.run(), state.executionShape(), state.stages(),
			state.inputStallTicks(), state.phase(), state.stageIndex(),
			state.readySourceInventorySnapshotGeneration(), state.currentCommand(),
			java.util.Optional.of(ledger), state.loadedNoProgressTicks(), state.waitingForAgentLoad(),
			state.interferenceCount(), state.leaseRetained(), state.cancellationDrain(),
			state.cancellationDrainRevision());
	}

	private static AgentInputContract.State withProtocol(AgentInputContract.State state,
		AgentInputPhase phase, long loadedNoProgressTicks, boolean leaseRetained) {
		return new AgentInputContract.State(state.agentId(), state.agentResetGeneration(),
			state.outputObservationEpoch(), state.run(), state.executionShape(), state.stages(),
			state.inputStallTicks(), phase, state.stageIndex(),
			state.readySourceInventorySnapshotGeneration(), state.currentCommand(),
			state.nativeOrders(), loadedNoProgressTicks, state.waitingForAgentLoad(),
			state.interferenceCount(), leaseRetained, state.cancellationDrain(),
			state.cancellationDrainRevision());
	}

	private static AgentInputContract.State withReadySource(AgentInputContract.State state,
		java.util.OptionalLong source) {
		return new AgentInputContract.State(state.agentId(), state.agentResetGeneration(),
			state.outputObservationEpoch(), state.run(), state.executionShape(), state.stages(),
			state.inputStallTicks(), state.phase(), state.stageIndex(), source,
			state.currentCommand(), state.nativeOrders(), state.loadedNoProgressTicks(),
			state.waitingForAgentLoad(), state.interferenceCount(), state.leaseRetained(),
			state.cancellationDrain(), state.cancellationDrainRevision());
	}

	private static AgentInputContract.State withWaiting(AgentInputContract.State state,
		boolean waiting) {
		return new AgentInputContract.State(state.agentId(), state.agentResetGeneration(),
			state.outputObservationEpoch(), state.run(), state.executionShape(), state.stages(),
			state.inputStallTicks(), state.phase(), state.stageIndex(),
			state.readySourceInventorySnapshotGeneration(), state.currentCommand(),
			state.nativeOrders(), state.loadedNoProgressTicks(), waiting,
			state.interferenceCount(), state.leaseRetained(), state.cancellationDrain(),
			state.cancellationDrainRevision());
	}

	private static AgentInputContract sequencedAtFirstIntent(long identity) {
		ProductionRun run = run(InputMode.BULK, List.of(ROUTE_A, ROUTE_B),
			manifest(STONE, 2, DIRT, 3));
		AgentInputContract contract = new AgentInputContract(AGENT, 5, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.SEQUENCED, List.of(
				new AgentInputContract.StageInput(0, ROUTE_A, manifest(STONE, 2)),
				new AgentInputContract.StageInput(1, ROUTE_B, manifest(DIRT, 3))), 20, state -> {});
		contract.markCurrentStageReady(17, manifest(STONE, 2),
			Set.of(ROUTE_A, ROUTE_B), 1);
		contract.beginDispatch(id(identity), 17);
		return contract;
	}

	private static AgentInputContract dispatchedAndClosed(InputMode mode,
		Map<StackKey, Long> manifest, int orderId, Set<Integer> links) {
		AgentInputContract contract = standard(mode, manifest, 5, state -> {});
		contract.markCurrentStageReady(17, manifest, Set.of(ROUTE_A), 1);
		CreateDispatchCommand command = contract.beginDispatch(id(orderId), 17).orElseThrow();
		assertTrue(contract.confirmDispatch(committed(command,
			new NativeOrderLedger.NativeOrderIdentity(orderId, links))));
		assertTrue(contract.closeInput(17));
		return contract;
	}

	private static AgentInputContract standard(InputMode mode, Map<StackKey, Long> manifest,
		long resetGeneration, AgentInputContract.StateStore store) {
		return standard(mode, manifest, resetGeneration, store, 20);
	}

	private static AgentInputContract standard(InputMode mode, Map<StackKey, Long> manifest,
		long resetGeneration, AgentInputContract.StateStore store, int stallTicks) {
		ProductionRun run = run(mode, List.of(ROUTE_A), manifest);
		return new AgentInputContract(AGENT, resetGeneration, OUTPUT_EPOCH, run,
			AgentInputContract.ExecutionShape.STANDARD,
			List.of(new AgentInputContract.StageInput(0, ROUTE_A, manifest)), stallTicks, store);
	}

	private static ProductionRun run(InputMode mode, List<CreateRouteKey> routes,
		Map<StackKey, Long> manifest) {
		return new ProductionRun(RUN, id(1000), id(1001), 17, 1, mode, routes,
			new CreateRouteKey(id(1002), "warehouse"), 6, manifest);
	}

	private static CreateLogisticsGateway.CommitReceipt committed(CreateDispatchCommand command,
		NativeOrderLedger.NativeOrderIdentity... orders) {
		class Journal implements CreateLogisticsGateway.CommitStore {
			private CreateLogisticsGateway.PersistedCommit value;
			@Override public java.util.Optional<CreateLogisticsGateway.PersistedCommit> load(
				UUID intentId) {
				return value == null || !value.command().intentId().equals(intentId)
					? java.util.Optional.empty() : java.util.Optional.of(value);
			}
			@Override public void persist(CreateLogisticsGateway.PersistedCommit commit) {
				value = commit;
			}
		}
		Journal journal = new Journal();
		CreateLogisticsGateway gateway = new CreateLogisticsGateway(
			ignored -> new CreateLogisticsGateway.CommitContext(command.agentId(),
				command.agentResetGeneration(), command.fence(), command.route(), true,
				command.sourceInventorySnapshotGeneration(), AgentInputPhase.DISPATCH_INTENT,
				command),
			ignored -> new CreateLogisticsGateway.NativeDispatchFact(true, List.of(orders)),
			() -> true, journal);
		return gateway.commit(command).confirmationReceipt().orElseThrow();
	}

	private static NativePackageObservation packageAt(CreateRouteKey route, int orderId,
		int link, int fragment, boolean finalLink, boolean last,
		Map<StackKey, Long> accepted, Map<StackKey, Long> context) {
		return new NativePackageObservation(route, true, orderId, link, fragment, finalLink, last,
			context.isEmpty() ? null : new NativeOrderLedger.OrderContext(context), accepted);
	}

	private static Map<StackKey, Long> manifest(StackKey first, long firstCount) {
		return Map.of(first, firstCount);
	}

	private static Map<StackKey, Long> manifest(StackKey first, long firstCount,
		StackKey second, long secondCount) {
		LinkedHashMap<StackKey, Long> manifest = new LinkedHashMap<>();
		manifest.put(first, firstCount);
		manifest.put(second, secondCount);
		return Map.copyOf(manifest);
	}

	private static StackKey key(Item item) { return new StackKey(new ItemStack(item)); }
	private static UUID id(long value) { return new UUID(0, value); }

	private static final class RecordingStore implements AgentInputContract.StateStore {
		private final List<AgentInputContract.State> states = new ArrayList<>();
		@Override public void persist(AgentInputContract.State state) {
			states.add(state);
			assertNotNull(state);
		}
	}
}
