package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.DispatchDecision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Milestone;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.OutputEvent;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputGatewayTestFixture;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.AttachedInventoryBindingTestFixture;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.AttachedInventoryBindingResolver;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

class AgentOutputContractTest {
	private static final UUID NETWORK = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000903");
	private static final UUID RECEIVER_A = UUID.fromString("00000000-0000-0000-0000-000000000904");
	private static final UUID RECEIVER_B = UUID.fromString("00000000-0000-0000-0000-000000000905");
	private static StackKey PRIMARY;
	private static StackKey INCIDENTAL;
	private static StackKey UNDECLARED;
	private static final CreateRouteKey INPUT = route("input");
	private static final CreateRouteKey OUTPUT_A = route("output-a");
	private static final CreateRouteKey OTHER_NETWORK_DESTINATION = new CreateRouteKey(
		UUID.fromString("00000000-0000-0000-0000-000000000999"), "destination-a");
	private static final CreateRouteKey OUTPUT_B = route("output-b");
	private static final CreateRouteKey DESTINATION_A = route("destination-a");
	private static final CreateRouteKey DESTINATION_B = route("destination-b");
	private static final CreateRouteKey WAREHOUSE = route("warehouse");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		PRIMARY = new StackKey(new ItemStack(Items.IRON_INGOT));
		INCIDENTAL = new StackKey(new ItemStack(Items.FLINT));
		UNDECLARED = new StackKey(new ItemStack(Items.CLAY_BALL));
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

	@Test
	void targetRecordsEarlyOutputButCannotSealBeforeInputEnds() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 2)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));

		assertTrue(harness.contract.beginAtInputReady(harness.capacity).started());
		assertEquals(ProductionOutputContract.OutputObservation.ATTRIBUTED,
			harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 2)));
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		assertTrue(harness.contract.markInputEnded(harness.run.fence()));
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		advance(harness, 0, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
		assertEquals(2L, harness.contract.creditedOutputs().get(PRIMARY));
	}

	@Test
	void unloadedOrEpochUnstableReadinessBlocksEveryTargetAndSignalSealPath() {
		Harness target = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		target.contract.beginAtInputReady(target.capacity);
		target.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		target.contract.markInputEnded(target.run.fence());
		assertEquals(Milestone.OBSERVING, target.contract.milestone());
		target.contract.advanceCompletionClock(authoritativeReadiness(target, false, true, EPOCH, 0));
		assertEquals(Milestone.OBSERVING, target.contract.milestone());
		target.contract.advanceCompletionClock(authoritativeReadiness(target, true, true,
			UUID.randomUUID(), 0));
		assertEquals(Milestone.OBSERVING, target.contract.milestone());
		target.contract.advanceCompletionClock(authoritativeReadiness(target, true, true, EPOCH, 0));
		assertEquals(Milestone.OUTPUT_SEALED, target.contract.milestone());

		Harness signal = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		signal.contract.beginAtInputReady(signal.capacity);
		signal.contract.observeOutput(event(2, OUTPUT_A, PRIMARY, 1));
		signal.contract.markInputEnded(signal.run.fence());
		signal.contract.publishSignal(signal.run.fence());
		assertEquals(Milestone.OBSERVING, signal.contract.milestone());
		signal.contract.advanceCompletionClock(authoritativeReadiness(signal, true, true, EPOCH, 0));
		assertEquals(Milestone.OUTPUT_SEALED, signal.contract.milestone());
	}

	@Test
	void readinessClockIsAuthorityOwnedAndAnOpaqueFactCannotBeReplayed() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		OutputLogisticsGateway.ReadinessSnapshot fourTicks = authoritativeReadiness(harness, true,
			true, EPOCH, 4);

		harness.contract.advanceCompletionClock(fourTicks);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		harness.contract.advanceCompletionClock(fourTicks);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		harness.contract.advanceCompletionClock(authoritativeReadiness(harness, true, true, EPOCH, 1));
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
	}

	@Test
	void outputDispatchAndDrainRequireOpaqueServerGatewayFacts() {
		assertTrue(java.util.Arrays.stream(OutputLogisticsGateway.class.getDeclaredConstructors())
			.noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(
				constructor.getModifiers())));
		assertThrows(NoSuchMethodException.class,
			() -> ProductionOutputContract.class.getMethod("confirmDispatch", UUID.class));
		assertThrows(NoSuchMethodException.class,
			() -> ProductionOutputContract.class.getMethod("completeDispatch", boolean.class,
				boolean.class));
		assertTrue(java.util.Arrays.stream(OutputLogisticsGateway.CommitReceipt.class
			.getDeclaredConstructors()).allMatch(constructor -> java.lang.reflect.Modifier
				.isPrivate(constructor.getModifiers())));
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.advanceCompletionClock(
			authoritativeReadiness(harness, true, true, EPOCH, 0));
		ProductionOutputContract.DispatchInstruction intent = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		OutputLogisticsGateway.DrainSnapshot staleDrain = outputGateway(harness, true)
			.observeDrain(harness.contract.drainRequest()).snapshot().orElseThrow();

		OutputLogisticsGateway offThread = outputGateway(harness, false);
		assertTrue(offThread.commit(intent).confirmationReceipt().isEmpty());
		OutputLogisticsGateway gateway = outputGateway(harness, true);
		assertTrue(harness.contract.confirmDispatch(gateway.commit(intent)
			.confirmationReceipt().orElseThrow()));
		assertFalse(harness.contract.completeDispatch(staleDrain));
		assertFalse(complete(harness.contract, false, true));
		assertTrue(complete(harness.contract, true, true));
	}

	@Test
	void arithmeticOverflowFailsDurablyWithoutPartiallyCommittingOutputIdentity() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		assertEquals(ProductionOutputContract.OutputObservation.ATTRIBUTED,
			harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, Long.MAX_VALUE)));
		assertEquals(ProductionOutputContract.OutputObservation.ARITHMETIC_FAILURE,
			harness.contract.observeOutput(event(2, OUTPUT_A, PRIMARY, 1)));
		assertEquals(Milestone.FAILED, harness.contract.milestone());
		assertEquals(1, harness.contract.pendingOutputCount());
		assertFalse(harness.contract.snapshot().seenOutputEventIds().contains(new UUID(0, 2)));
		assertTrue(harness.contract.snapshot().failure().isPresent());
		assertEquals(harness.contract.snapshot(), harness.store.snapshots.getLast());
	}

	@Test
	void dispatchAggregateOverflowKeepsThePendingEventAndIntentInDurableFailedState() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(), Optional.empty(), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, INCIDENTAL, Long.MAX_VALUE));
		harness.contract.observeOutput(event(2, OUTPUT_A, INCIDENTAL, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.DispatchInstruction first = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertTrue(confirm(harness.contract, first));
		ProductionOutputContract.DispatchInstruction second = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		OutputLogisticsGateway.CommitReceipt receipt = outputGateway(harness, true).commit(second)
			.confirmationReceipt().orElseThrow();

		assertFalse(harness.contract.confirmDispatch(receipt));
		assertEquals(Milestone.FAILED, harness.contract.milestone());
		assertEquals(1, harness.contract.pendingOutputCount());
		assertEquals(Optional.of(second), harness.contract.snapshot().dispatchIntent());
		assertEquals(Map.of(INCIDENTAL, Long.MAX_VALUE),
			harness.contract.snapshot().dispatchedOutputs().get(WAREHOUSE));
		assertEquals(Optional.of(receipt.operation().operationId()), harness.contract.snapshot()
			.failure().orElseThrow().nativeOperationId());
		assertEquals(harness.contract.snapshot(), harness.store.snapshots.getLast());
	}

	@Test
	void noProgressOverflowFailsBeforeSealingOrReleasingTheAgent() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), Integer.MAX_VALUE,
			new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());

		advance(harness, 0, true, true, EPOCH);

		assertEquals(Milestone.FAILED, harness.contract.milestone());
		assertTrue(harness.contract.sealOutcome().isEmpty());
		assertFalse(harness.contract.agentReleased());
		assertEquals(harness.contract.snapshot(), harness.store.snapshots.getLast());
	}

	@Test
	void directCapacityFailureOnOlderAddressDoesNotPreventLeasingYoungerAddress() {
		Capacity capacity = new Capacity(2, Set.of(DESTINATION_A));
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, target(DESTINATION_A, 1), UNDECLARED, target(DESTINATION_B, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		harness.contract.advanceCompletionClock(
			authoritativeReadiness(harness, true, true, EPOCH, 0));

		assertEquals(new UUID(0, 2), harness.contract.nextDispatch().instruction()
			.orElseThrow().eventId());
	}

	@Test
	void requiredOutputProofMustMatchItemNetworkAndExactlyOneReceiverKind() {
		StockObservationScope.NetworkSummary wrongItem = new StockObservationScope.NetworkSummary(NETWORK,
			INCIDENTAL, 1);
		StockObservationScope.NetworkSummary wrongNetwork = new StockObservationScope.NetworkSummary(
			UUID.randomUUID(), PRIMARY, 1);
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareNetwork(wrongItem))), Optional.of(PRIMARY), false,
			false, Set.of(OUTPUT_A)));
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareNetwork(wrongNetwork))), Optional.of(PRIMARY), false,
			false, Set.of(OUTPUT_A)));
		assertTrue(ProductionOutputPolicy.OutputReceiver.class.isSealed());
	}

	@Test
	void attachedProofRequiresOpaqueExactDestinationPackagerNetworkAndInventoryBinding() {
		assertTrue(java.util.Arrays.stream(AttachedInventoryBindingResolver.class
			.getDeclaredConstructors()).noneMatch(constructor -> java.lang.reflect.Modifier
				.isPublic(constructor.getModifiers())));
		assertTrue(java.util.Arrays.stream(AttachedInventoryBindingResolver.BindingFact.class
			.getDeclaredConstructors()).allMatch(constructor -> java.lang.reflect.Modifier
				.isPrivate(constructor.getModifiers())));
		SpaceAddress packagerA = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(1, 2, 3));
		SpaceAddress packagerB = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(4, 5, 6));
		InventoryIdentity inventoryA = inventory(packagerA, 1);
		InventoryIdentity inventoryB = inventory(packagerB, 2);
		StockObservationScope.AttachedInventory scopeA = new StockObservationScope.AttachedInventory(
			packagerA, inventoryA, PRIMARY, 5);
		StockObservationScope.AttachedInventory scopeB = new StockObservationScope.AttachedInventory(
			packagerB, inventoryB, PRIMARY, 5);
		var bindingA = AttachedInventoryBindingTestFixture.fact(DESTINATION_A, packagerA,
			inventoryA);
		var bindingB = AttachedInventoryBindingTestFixture.fact(DESTINATION_B, packagerB,
			inventoryB);
		var wrongNetwork = AttachedInventoryBindingTestFixture.fact(OTHER_NETWORK_DESTINATION,
			packagerA, inventoryA);

		policy(AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareAttached(scopeA, bindingA))), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A));
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareAttached(scopeB, bindingA))), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)));
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareAttached(scopeA, bindingB))), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)));
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareAttached(scopeA, wrongNetwork))), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)));
		StockObservationScope.AttachedInventory wrongIdentity =
			new StockObservationScope.AttachedInventory(packagerA, inventory(packagerA, 9), PRIMARY, 5);
		assertThrows(IllegalArgumentException.class, () -> policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareAttached(wrongIdentity, bindingA))), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)));
	}

	@Test
	void signalSealsOnlyAfterExplicitFencedSignalAndInputEnd() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		assertFalse(harness.contract.publishSignal(harness.run.fence() + 1));
		assertTrue(harness.contract.markInputEnded(harness.run.fence()));
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		assertTrue(harness.contract.publishSignal(harness.run.fence()));
		advance(harness, 0, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
	}

	@Test
	void quietStartsAtFirstOutputResetsOnEveryOutputAndPausesWhileUnloaded() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 2)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.markInputEnded(harness.run.fence());

		advance(harness, 20, true, true, EPOCH);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone(), "quiet has no clock before output");
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		advance(harness, 4, true, true, EPOCH);
		harness.contract.observeOutput(event(2, OUTPUT_A, PRIMARY, 1));
		advance(harness, 4, false, true, EPOCH);
		assertEquals(Optional.of(WaitReason.AGENT_UNLOADED), harness.contract.waitReason());
		advance(harness, 4, true, true, EPOCH);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		advance(harness, 1, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
		assertEquals(0, harness.capacity.activeObservationLeases());
	}

	@Test
	void quietObservationCapacityIsAtomicAndBlocksInputDispatch() {
		Capacity capacity = new Capacity(1);
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A, OUTPUT_B)), 0, capacity);

		ProductionOutputContract.StartResult result = harness.contract.beginAtInputReady(capacity);
		assertFalse(result.started());
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE), harness.contract.waitReason());
		assertFalse(harness.contract.inputDispatchAllowed());
		assertEquals(0, capacity.activeObservationLeases());
		capacity.addCapacity(2);
		assertTrue(harness.contract.beginAtInputReady(capacity).started());
		assertEquals(Optional.empty(), harness.contract.waitReason());
		assertTrue(harness.contract.inputDispatchAllowed());
	}

	@Test
	void preDispatchAbortReleasesOutputComputeAndAllowsCleanRetry() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, target(DESTINATION_A, 1)), Optional.empty(), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(1));
		assertTrue(harness.contract.beginAtInputReady(harness.capacity).started());
		assertEquals(1, harness.capacity.activeObservationLeases());

		assertTrue(harness.contract.abortBeforeInputDispatch());
		assertEquals(Milestone.WAITING_INPUT_READY, harness.contract.milestone());
		assertEquals(0, harness.capacity.activeObservationLeases());
		assertFalse(harness.contract.inputDispatchAllowed());
		assertTrue(harness.contract.beginAtInputReady(harness.capacity).started());
	}

	@Test
	void quietClockPausesAndReacquiresWhenObservationLeaseIsRevoked() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, target(DESTINATION_A, 1)), Optional.empty(), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(1));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.capacity.revokeObservation(OUTPUT_A);

		advance(harness, 5, true, true, EPOCH);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE), harness.contract.waitReason());

		harness.capacity.addCapacity(1);
		advance(harness, 5, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
	}

	@Test
	void firstOutputTimeoutSealsOnlyConfiguredNoOutputRunAfterInputEnd() {
		Harness noOutput = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(), Optional.empty(), true, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		noOutput.contract.beginAtInputReady(noOutput.capacity);
		advance(noOutput, 7, true, true, EPOCH);
		assertEquals(Milestone.OBSERVING, noOutput.contract.milestone());
		noOutput.contract.markInputEnded(noOutput.run.fence());
		advance(noOutput, 1, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, noOutput.contract.milestone());

		Harness unexpectedMaterial = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(), Optional.empty(), true, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		unexpectedMaterial.contract.beginAtInputReady(unexpectedMaterial.capacity);
		unexpectedMaterial.contract.observeOutput(event(2, OUTPUT_A, INCIDENTAL, 1));
		unexpectedMaterial.contract.markInputEnded(unexpectedMaterial.run.fence());
		advance(unexpectedMaterial, 8, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, unexpectedMaterial.contract.milestone());

		Harness expectedOutput = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		expectedOutput.contract.beginAtInputReady(expectedOutput.capacity);
		expectedOutput.contract.markInputEnded(expectedOutput.run.fence());
		advance(expectedOutput, 100, true, true, EPOCH);
		assertEquals(Milestone.OBSERVING, expectedOutput.contract.milestone());
	}

	@Test
	void probabilisticShortfallCountsOnlyPrimaryAndTripsBreakerWithoutTimerRetry() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), 1, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, INCIDENTAL, 4));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 5, true, true, EPOCH);

		ProductionOutputContract.SealOutcome outcome = harness.contract.sealOutcome().orElseThrow();
		assertEquals(0, outcome.primaryCount());
		assertTrue(outcome.probabilityStalled());
		assertFalse(outcome.requiresAnotherRun());
		assertEquals(Optional.of(WaitReason.PROBABILITY_STALLED), harness.contract.waitReason());
		advance(harness, 1000, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone(), "no blind timer retry");
	}

	@Test
	void probabilisticShortfallRequestsOneFiniteFollowupBeforeBreaker() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 2)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		ProductionOutputContract.SealOutcome outcome = harness.contract.sealOutcome().orElseThrow();
		assertTrue(outcome.probabilisticShortfall());
		assertTrue(outcome.requiresAnotherRun());
		assertFalse(outcome.probabilityStalled());
	}

	@Test
	void probabilisticZeroOutputUsesFirstOutputTimeoutToRemainFinite() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 8, true, true, EPOCH);

		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
		assertTrue(harness.contract.sealOutcome().orElseThrow().requiresAnotherRun());
	}

	@Test
	void probabilisticTargetWithOnlyIncidentalOutputStillSealsAtFiniteBoundary() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, INCIDENTAL, 2));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 8, true, true, EPOCH);

		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());
		assertEquals(0, harness.contract.sealOutcome().orElseThrow().primaryCount());
	}

	@Test
	void warehouseAllRoutesRequiredIncidentalAndUndeclaredThenPersistsDrainLedger() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, INCIDENTAL, 2));
		harness.contract.observeOutput(event(3, OUTPUT_A, UNDECLARED, 3));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		drainAll(harness.contract);
		assertFalse(complete(harness.contract, true, false));
		assertTrue(complete(harness.contract, true, true));
		assertEquals(Milestone.OUTPUT_DISPATCHED, harness.contract.milestone());
		assertEquals(1, harness.store.ledgers.size());
		Map<StackKey, Long> warehouse = harness.store.ledgers.getFirst().dispatchedOutputs().get(WAREHOUSE);
		assertEquals(Map.of(PRIMARY, 1L, INCIDENTAL, 2L, UNDECLARED, 3L), warehouse);
		assertTrue(harness.contract.agentReleased());
	}

	@Test
	void directRequiredChargesDistinctDestinationsSharesAddressAndReleasesIndependently() {
		Capacity capacity = new Capacity(2);
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, target(DESTINATION_A, 1), UNDECLARED, target(DESTINATION_A, 2),
				INCIDENTAL, target(DESTINATION_B, 1)), Optional.empty(), false, false,
			Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 2));
		harness.contract.observeOutput(event(3, OUTPUT_A, INCIDENTAL, 1));
		assertEquals(1, capacity.destinationAcquisitions(DESTINATION_A));
		assertEquals(1, capacity.destinationAcquisitions(DESTINATION_B));
		assertTrue(capacity.hasDestinationLease(DESTINATION_A));
		assertTrue(capacity.hasDestinationLease(DESTINATION_B));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		DispatchDecision first = harness.contract.nextDispatch();
		assertEquals(DESTINATION_A, first.instruction().orElseThrow().destination());
		assertEquals(1, capacity.destinationAcquisitions(DESTINATION_A));
		confirm(harness.contract, first.instruction().orElseThrow());
		DispatchDecision second = harness.contract.nextDispatch();
		assertEquals(DESTINATION_A, second.instruction().orElseThrow().destination());
		assertEquals(1, capacity.destinationAcquisitions(DESTINATION_A), "items share one address process");
		confirm(harness.contract, second.instruction().orElseThrow());
		assertFalse(capacity.hasDestinationLease(DESTINATION_A));
		DispatchDecision third = harness.contract.nextDispatch();
		assertEquals(DESTINATION_B, third.instruction().orElseThrow().destination());
		assertTrue(capacity.hasDestinationLease(DESTINATION_B));
		confirm(harness.contract, third.instruction().orElseThrow());
		assertFalse(capacity.hasDestinationLease(DESTINATION_B));
	}

	@Test
	void directRequiredWaitsForOutputComputeAndDoesNotMoveBlockedOutput() {
		Capacity capacity = new Capacity(0);
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE), harness.contract.waitReason());
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		DispatchDecision blocked = harness.contract.nextDispatch();
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE), blocked.waitReason());
		assertTrue(blocked.instruction().isEmpty());
		assertEquals(1, harness.contract.pendingOutputCount());
	}

	@Test
	void revokedDestinationLeaseCannotAuthorizePhysicalOutputMovement() {
		Capacity capacity = new Capacity(1);
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		capacity.revokeDestination(DESTINATION_A);

		DispatchDecision blocked = harness.contract.nextDispatch();
		assertTrue(blocked.instruction().isEmpty());
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE), blocked.waitReason());
	}

	@Test
	void directRequiredAdvancesLeasedAddressPastOlderBlockedAddress() {
		Capacity capacity = new Capacity(1);
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, target(DESTINATION_B, 3), UNDECLARED, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 1));
		harness.contract.observeOutput(event(3, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		ProductionOutputContract.DispatchInstruction first = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertEquals(new UUID(0, 1), first.eventId());
		assertTrue(confirm(harness.contract, first));
		ProductionOutputContract.DispatchInstruction independent = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertEquals(new UUID(0, 3), independent.eventId(),
			"leased destination must advance past an older capacity-blocked address");
	}

	@Test
	void directRequiredRoutesIncidentalAndUndeclaredOutputToOneWarehouseProcess() {
		Capacity capacity = new Capacity(2);
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, capacity);
		harness.contract.beginAtInputReady(capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, INCIDENTAL, 2));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 3));

		assertEquals(1, capacity.destinationAcquisitions(WAREHOUSE));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		assertEquals(WAREHOUSE, harness.contract.nextDispatch().instruction().orElseThrow().destination());
	}

	@Test
	void lateOutputIsWarehouseDiagnosticAndCannotBeCredited() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		assertEquals(ProductionOutputContract.OutputObservation.LATE_DIAGNOSTIC,
			harness.contract.observeOutput(event(2, OUTPUT_A, PRIMARY, 5)));
		assertEquals(1L, harness.contract.creditedOutputs().get(PRIMARY));
		assertEquals(5L, harness.contract.diagnosticOutputs().get(PRIMARY));
		ProductionOutputContract.DispatchInstruction credited = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertEquals(DESTINATION_A, credited.destination());
		assertTrue(confirm(harness.contract, credited));
		assertEquals(WAREHOUSE, harness.contract.nextDispatch().instruction().orElseThrow().destination());
	}

	@Test
	void observedEventIdentityCannotBeDispatchedTwiceAfterItsBacklogEntryLeaves() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		OutputEvent output = event(1, OUTPUT_A, PRIMARY, 1);
		harness.contract.observeOutput(output);
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.DispatchInstruction instruction = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertTrue(confirm(harness.contract, instruction));

		assertEquals(ProductionOutputContract.OutputObservation.DUPLICATE,
			harness.contract.observeOutput(output));
		assertEquals(0, harness.contract.pendingOutputCount());
	}

	@Test
	void dispatchIntentIsDurableBeforeExposureAndCannotBeReturnedAgain() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		ProductionOutputContract.DispatchInstruction instruction = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertEquals(Optional.of(instruction), harness.store.snapshots.getLast().dispatchIntent());
		assertTrue(harness.contract.nextDispatch().instruction().isEmpty(),
			"an uncertain irreversible dispatch is never exposed twice");
		assertTrue(confirm(harness.contract, instruction));
	}

	@Test
	void restorePreservesUncertainDispatchIntentWithoutAutomaticResend() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		harness.contract.nextDispatch().instruction().orElseThrow();

		ProductionOutputContract restored = ProductionOutputContract.restore(harness.contract.snapshot(),
			harness.policy, Optional.empty(), harness.store);
		assertTrue(restored.nextDispatch().instruction().isEmpty());
		assertTrue(restored.snapshot().dispatchIntent().isPresent());
	}

	@Test
	void durableDispatchLedgerOverridesOlderSealedSnapshotDuringRestore() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.Snapshot olderSealed = harness.contract.snapshot();
		drainAll(harness.contract);
		complete(harness.contract, true, true);

		ProductionOutputContract restored = ProductionOutputContract.restore(olderSealed, harness.policy,
			Optional.of(harness.store.ledgers.getLast()), harness.store);
		assertEquals(Milestone.OUTPUT_DISPATCHED, restored.milestone());
		assertTrue(restored.agentReleased());
		assertEquals(0, restored.pendingOutputCount());
		assertTrue(restored.nextDispatch().instruction().isEmpty());
	}

	@Test
	void restoreRejectsLedgerThatDropsAnEventKnownByTheSealedSnapshot() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		ProductionOutputLedger forged = new ProductionOutputLedger(harness.run.runId(),
			harness.run.goalId(), harness.run.fence(), EPOCH, harness.policy.generation(), WAREHOUSE,
			Map.of(), Map.of(), Map.of(), new ProductionOutputLedger.SealFact(0, false, false, 0,
				false, sealed.sealOutcome().orElseThrow().completionBoundary()));

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(sealed,
			harness.policy, Optional.of(forged), harness.store));
	}

	@Test
	void restoreRejectsLedgerThatRewritesASealedManifestEvent() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		UUID eventId = new UUID(0, 1);
		ProductionOutputLedger.DispatchedEvent rewritten = new ProductionOutputLedger.DispatchedEvent(
			eventId, UUID.randomUUID(), WAREHOUSE, PRIMARY, 2, true);
		ProductionOutputLedger forged = new ProductionOutputLedger(harness.run.runId(),
			harness.run.goalId(), harness.run.fence(), EPOCH, harness.policy.generation(), WAREHOUSE,
			Map.of(eventId, rewritten), Map.of(WAREHOUSE, Map.of(PRIMARY, 2L)),
			Map.of(PRIMARY, 2L), new ProductionOutputLedger.SealFact(2, false, false, 0, false,
				sealed.sealOutcome().orElseThrow().completionBoundary()));

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(sealed,
			harness.policy, Optional.of(forged), harness.store));
	}

	@Test
	void restoreRejectsExtraSeenEventAndSealedSnapshotWithoutInputEnd() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		Set<UUID> extraSeen = new java.util.LinkedHashSet<>(sealed.seenOutputEventIds());
		extraSeen.add(new UUID(0, 404));

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(sealed, false, sealed.signalObserved(),
				sealed.firstOutputObserved(), sealed.firstOutputElapsedTicks(),
				sealed.quietElapsedTicks(), sealed.probabilityElapsedTicks(),
				sealed.seenOutputEventIds(), sealed.lastReadinessObservationSequence(),
				sealed.readinessEvidence()), harness.policy, Optional.empty(), harness.store));
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(sealed, sealed.inputEnded(), sealed.signalObserved(),
				sealed.firstOutputObserved(), sealed.firstOutputElapsedTicks(),
				sealed.quietElapsedTicks(), sealed.probabilityElapsedTicks(), extraSeen,
				sealed.lastReadinessObservationSequence(), sealed.readinessEvidence()),
			harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void restoreRejectsTargetSealWithoutFreshLoadedMatchingEpochReadiness() {
		Harness harness = sealedTarget();
		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		ProductionOutputContract.ReadinessEvidence unloaded = new ProductionOutputContract.ReadinessEvidence(
			Map.of(OUTPUT_A, new OutputLogisticsGateway.EndpointReadiness(false, EPOCH, true)));
		ProductionOutputContract.ReadinessEvidence wrongEpoch = new ProductionOutputContract.ReadinessEvidence(
			Map.of(OUTPUT_A, new OutputLogisticsGateway.EndpointReadiness(true, UUID.randomUUID(), true)));

		for (ProductionOutputContract.Snapshot invalid : List.of(
			copyBoundarySnapshot(sealed, true, false, true, 0, 0, 0,
				sealed.seenOutputEventIds(), -1, sealed.readinessEvidence()),
			copyBoundarySnapshot(sealed, true, false, true, 0, 0, 0,
				sealed.seenOutputEventIds(), sealed.lastReadinessObservationSequence(),
				Optional.of(unloaded)),
			copyBoundarySnapshot(sealed, true, false, true, 0, 0, 0,
				sealed.seenOutputEventIds(), sealed.lastReadinessObservationSequence(),
				Optional.of(wrongEpoch))))
			assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(invalid,
				harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void restoreRejectsSignalQuietNoOutputAndProbabilitySealsWithoutTheirBoundary() {
		Harness signal = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		signal.contract.beginAtInputReady(signal.capacity);
		signal.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		signal.contract.markInputEnded(signal.run.fence());
		signal.contract.publishSignal(signal.run.fence());
		advance(signal, 0, true, true, EPOCH);
		ProductionOutputContract.Snapshot signalSealed = signal.contract.snapshot();
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(signalSealed, true, false, true, 0, 0, 0,
				signalSealed.seenOutputEventIds(), signalSealed.lastReadinessObservationSequence(),
				signalSealed.readinessEvidence()), signal.policy, Optional.empty(), signal.store));

		Harness quiet = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		quiet.contract.beginAtInputReady(quiet.capacity);
		quiet.contract.observeOutput(event(2, OUTPUT_A, PRIMARY, 1));
		quiet.contract.markInputEnded(quiet.run.fence());
		advance(quiet, 5, true, true, EPOCH);
		ProductionOutputContract.Snapshot quietSealed = quiet.contract.snapshot();
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(quietSealed, true, false, true, 0, 4, 0,
				quietSealed.seenOutputEventIds(), quietSealed.lastReadinessObservationSequence(),
				quietSealed.readinessEvidence()), quiet.policy, Optional.empty(), quiet.store));

		Harness noOutput = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(), Optional.empty(), true, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		noOutput.contract.beginAtInputReady(noOutput.capacity);
		noOutput.contract.markInputEnded(noOutput.run.fence());
		advance(noOutput, 8, true, true, EPOCH);
		ProductionOutputContract.Snapshot noOutputSealed = noOutput.contract.snapshot();
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(noOutputSealed, true, false, false, 7, 0, 0,
				noOutputSealed.seenOutputEventIds(),
				noOutputSealed.lastReadinessObservationSequence(),
				noOutputSealed.readinessEvidence()), noOutput.policy, Optional.empty(), noOutput.store));

		Harness probability = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, true, Set.of(OUTPUT_A)), 0, new Capacity(8));
		probability.contract.beginAtInputReady(probability.capacity);
		probability.contract.markInputEnded(probability.run.fence());
		advance(probability, 8, true, true, EPOCH);
		ProductionOutputContract.Snapshot probabilitySealed = probability.contract.snapshot();
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(probabilitySealed, true, false, false, 0, 0, 0,
				probabilitySealed.seenOutputEventIds(),
				probabilitySealed.lastReadinessObservationSequence(),
				probabilitySealed.readinessEvidence()), probability.policy, Optional.empty(),
			probability.store));
	}

	@Test
	void quietSealAndRestoreRequireCurrentlyDrainedEndpointEvidence() {
		Harness harness = harness(policy(AgentCompletionMode.QUIET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		advance(harness, 5, true, true, EPOCH);
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, false, EPOCH);
		assertEquals(Milestone.OBSERVING, harness.contract.milestone());
		advance(harness, 0, true, true, EPOCH);
		assertEquals(Milestone.OUTPUT_SEALED, harness.contract.milestone());

		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		ProductionOutputContract.ReadinessEvidence undrained = new ProductionOutputContract.ReadinessEvidence(
			Map.of(OUTPUT_A, new OutputLogisticsGateway.EndpointReadiness(true, EPOCH, false)));
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			copyBoundarySnapshot(sealed, sealed.inputEnded(), sealed.signalObserved(),
				sealed.firstOutputObserved(), sealed.firstOutputElapsedTicks(),
				sealed.quietElapsedTicks(), sealed.probabilityElapsedTicks(),
				sealed.seenOutputEventIds(), sealed.lastReadinessObservationSequence(),
				Optional.of(undrained)), harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void restoreRejectsLateEvidenceThatRewritesSameIdPhysicalManifest() {
		Harness harness = sealedTarget();
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 4));
		ProductionOutputContract.Snapshot sealed = harness.contract.snapshot();
		ProductionOutputContract.LateWarehouseMaterial rewritten =
			new ProductionOutputContract.LateWarehouseMaterial(new UUID(0, 2), WAREHOUSE, INCIDENTAL, 4);
		ProductionOutputContract.Snapshot forged = copyLateSnapshot(sealed,
			Map.of(INCIDENTAL, 4L), List.of(rewritten));

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(forged,
			harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void restoreRejectsLedgerWhoseSealFactRewritesCompletionBoundary() {
		Harness harness = sealedTarget();
		ProductionOutputContract.Snapshot olderSealed = harness.contract.snapshot();
		drainAll(harness.contract);
		complete(harness.contract, true, true);
		ProductionOutputLedger valid = harness.store.ledgers.getLast();
		ProductionOutputLedger.CompletionBoundary boundary = valid.sealFact().completionBoundary();
		ProductionOutputLedger.CompletionBoundary forgedBoundary =
			new ProductionOutputLedger.CompletionBoundary(boundary.completionMode(), boundary.reason(),
				boundary.inputEnded(), boundary.signalObserved(), boundary.firstOutputObserved(),
				boundary.firstOutputElapsedTicks(), boundary.quietElapsedTicks(),
				boundary.probabilityElapsedTicks(), boundary.readinessObservationSequence(),
				UUID.randomUUID(), boundary.endpointReadiness());
		ProductionOutputLedger forged = new ProductionOutputLedger(valid.runId(), valid.goalId(),
			valid.fence(), valid.outputObservationEpoch(), valid.outputPolicyGeneration(),
			valid.returnWarehouseRoute(), valid.dispatchedEvents(), valid.dispatchedOutputs(),
			valid.creditedOutputs(), new ProductionOutputLedger.SealFact(valid.sealFact().primaryCount(),
				valid.sealFact().probabilisticShortfall(), valid.sealFact().requiresAnotherRun(),
				valid.sealFact().consecutiveNoProgressRuns(),
				valid.sealFact().probabilityStalled(), forgedBoundary));

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(olderSealed,
			harness.policy, Optional.of(forged), harness.store));
	}

	@Test
	void restoredDirectBacklogWaitsUntilAuthoritativeCapacityIsReattached() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, target(DESTINATION_A, 1)), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(1));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);

		ProductionOutputContract restored = ProductionOutputContract.restore(harness.contract.snapshot(),
			harness.policy, Optional.empty(), harness.store);
		assertEquals(Optional.of(WaitReason.OUTPUT_COMPUTE),
			restored.nextDispatch().waitReason());

		Capacity currentCapacity = new Capacity(1);
		assertTrue(restored.reattachCapacity(currentCapacity));
		assertEquals(DESTINATION_A,
			restored.nextDispatch().instruction().orElseThrow().destination());
	}

	@Test
	void restoreRejectsPendingDestinationOutsideFrozenPolicy() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, target(DESTINATION_A, 1)), Optional.of(PRIMARY), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		ProductionOutputContract.Snapshot valid = harness.contract.snapshot();
		ProductionOutputContract.PendingOutput pending = valid.pendingOutputs().getFirst();
		ProductionOutputContract.Snapshot corrupted = new ProductionOutputContract.Snapshot(valid.agentId(),
			valid.agentResetGeneration(), valid.outputObservationEpoch(), valid.run(),
			valid.priorNoProgressRuns(), valid.milestone(), valid.waitReason(), valid.inputEnded(),
			valid.signalObserved(), valid.firstOutputObserved(), valid.firstOutputElapsedTicks(),
			valid.quietElapsedTicks(), valid.probabilityElapsedTicks(), valid.creditedOutputs(),
			valid.diagnosticOutputs(), List.of(new ProductionOutputContract.PendingOutput(
				pending.eventId(), DESTINATION_B, pending.stack(), pending.count(), pending.credited())),
			valid.dispatchedOutputs(), valid.dispatchedEvents(), valid.seenOutputEventIds(),
			valid.satisfiedStockScopes(),
			valid.acceptedAgentReceipts(), valid.lateWarehouseMaterial(), valid.dispatchIntent(),
			valid.lastReadinessObservationSequence(), valid.confirmedDispatchSequence(),
			valid.readinessEvidence(), valid.sealOutcome(), valid.failure(), valid.agentReleased());

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(corrupted,
			harness.policy, Optional.empty(), harness.store));
		ProductionOutputContract.Snapshot uncredited = copySnapshot(valid,
			List.of(new ProductionOutputContract.PendingOutput(pending.eventId(), WAREHOUSE,
				pending.stack(), pending.count(), false)), valid.dispatchedOutputs(),
			valid.dispatchedEvents());
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(uncredited,
			harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void restoreRejectsOverlappingOrNonConservedDispatchEventPartitions() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(), Optional.empty(), false, false,
			Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, INCIDENTAL, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		ProductionOutputContract.DispatchInstruction first = harness.contract.nextDispatch()
			.instruction().orElseThrow();
		assertTrue(confirm(harness.contract, first));
		ProductionOutputContract.Snapshot valid = harness.contract.snapshot();
		ProductionOutputLedger.DispatchedEvent dispatched = valid.dispatchedEvents().get(first.eventId());
		List<ProductionOutputContract.PendingOutput> overlapping = new ArrayList<>(valid.pendingOutputs());
		overlapping.add(new ProductionOutputContract.PendingOutput(dispatched.eventId(),
			dispatched.destination(), dispatched.stack(), dispatched.count(), dispatched.credited()));
		ProductionOutputContract.Snapshot duplicatePartition = copySnapshot(valid, overlapping,
			valid.dispatchedOutputs(), valid.dispatchedEvents());
		ProductionOutputContract.Snapshot wrongAggregate = copySnapshot(valid, valid.pendingOutputs(),
			Map.of(WAREHOUSE, Map.of(INCIDENTAL, 2L)), valid.dispatchedEvents());

		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(
			duplicatePartition, harness.policy, Optional.empty(), harness.store));
		assertThrows(IllegalArgumentException.class, () -> ProductionOutputContract.restore(wrongAggregate,
			harness.policy, Optional.empty(), harness.store));
	}

	@Test
	void receiptBeforeDispatchReconcilesButMissingReceiptNeverRelocksOrResends() {
		StockObservationScope.NetworkSummary scope = new StockObservationScope.NetworkSummary(
			NETWORK, PRIMARY, 10);
		Harness early = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareNetwork(scope))),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		early.contract.beginAtInputReady(early.capacity);
		early.contract.reconcileStock(new StockObservationService.Observation(scope, 10, 999,
			StockObservationService.Status.TARGET_REACHED));
		sealAndDrain(early);
		assertEquals(Milestone.OUTPUT_RECEIVED, early.contract.milestone());

		Harness missing = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			Map.of(PRIMARY, new ProductionOutputPolicy.RequiredOutput(DESTINATION_A, 1,
				bareNetwork(scope))),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		missing.contract.beginAtInputReady(missing.capacity);
		sealAndDrain(missing);
		assertEquals(Milestone.OUTPUT_DISPATCHED, missing.contract.milestone());
		assertEquals(Optional.of(WaitReason.OUTPUT_DELIVERY), missing.contract.waitReason());
		assertTrue(missing.contract.agentReleased());
		assertEquals(0, missing.contract.pendingOutputCount());
		assertTrue(missing.contract.nextDispatch().instruction().isEmpty(), "dispatch cannot be recreated");
	}

	@Test
	void onlyFencedAgentReceiverAcknowledgementCanCompleteReceipt() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, targetAgent(DESTINATION_A, 1, RECEIVER_A)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		sealAndDrain(harness);
		assertFalse(harness.contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
			RECEIVER_A, DESTINATION_A, harness.run.runId(), harness.run.fence() + 1, EPOCH)));
		assertFalse(harness.contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
			RECEIVER_B, DESTINATION_A, harness.run.runId(), harness.run.fence(), EPOCH)));
		assertTrue(harness.contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
			RECEIVER_A, DESTINATION_A, harness.run.runId(), harness.run.fence(), EPOCH)));
		assertEquals(Milestone.OUTPUT_RECEIVED, harness.contract.milestone());
	}

	@Test
	void everyDistinctAgentReceiverMustAcknowledgeItsOwnDestination() {
		Harness harness = harness(policy(AgentCompletionMode.SIGNAL,
			AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			Map.of(PRIMARY, targetAgent(DESTINATION_A, 1, RECEIVER_A),
				UNDECLARED, targetAgent(DESTINATION_B, 1, RECEIVER_B)),
			Optional.empty(), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 1));
		harness.contract.markInputEnded(harness.run.fence());
		harness.contract.publishSignal(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		drainAll(harness.contract);
		complete(harness.contract, true, true);

		assertTrue(harness.contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
			RECEIVER_A, DESTINATION_A, harness.run.runId(), harness.run.fence(), EPOCH)));
		assertEquals(Milestone.OUTPUT_DISPATCHED, harness.contract.milestone());
		assertTrue(harness.contract.publishAgentReceipt(new ProductionOutputContract.AgentReceiptAck(
			RECEIVER_B, DESTINATION_B, harness.run.runId(), harness.run.fence(), EPOCH)));
		assertEquals(Milestone.OUTPUT_RECEIVED, harness.contract.milestone());
	}

	@Test
	void postDispatchLateOutputRemainsExplicitWarehouseMaterialWithoutRelockingAgent() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		sealAndDrain(harness);

		assertEquals(ProductionOutputContract.OutputObservation.LATE_DIAGNOSTIC,
			harness.contract.observeOutput(event(2, OUTPUT_A, UNDECLARED, 4)));
		ProductionOutputContract.LateWarehouseMaterial material =
			harness.contract.lateWarehouseMaterial().getLast();
		assertEquals(WAREHOUSE, material.destination());
		assertEquals(4, material.count());
		assertEquals(0, harness.contract.pendingOutputCount());
		assertEquals(Milestone.OUTPUT_DISPATCHED, harness.contract.milestone());
		assertTrue(harness.contract.agentReleased());
	}

	private static void sealAndDrain(Harness harness) {
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		drainAll(harness.contract);
		assertTrue(complete(harness.contract, true, true));
	}

	private static ProductionOutputContract.Snapshot copySnapshot(ProductionOutputContract.Snapshot source,
		List<ProductionOutputContract.PendingOutput> pending,
		Map<CreateRouteKey, Map<StackKey, Long>> dispatched,
		Map<UUID, ProductionOutputLedger.DispatchedEvent> dispatchedEvents) {
		return new ProductionOutputContract.Snapshot(source.agentId(), source.agentResetGeneration(),
			source.outputObservationEpoch(), source.run(), source.priorNoProgressRuns(),
			source.milestone(), source.waitReason(), source.inputEnded(), source.signalObserved(),
			source.firstOutputObserved(), source.firstOutputElapsedTicks(), source.quietElapsedTicks(),
			source.probabilityElapsedTicks(), source.creditedOutputs(), source.diagnosticOutputs(),
			pending, dispatched, dispatchedEvents, source.seenOutputEventIds(),
			source.satisfiedStockScopes(), source.acceptedAgentReceipts(), source.lateWarehouseMaterial(),
			source.dispatchIntent(), source.lastReadinessObservationSequence(),
			source.confirmedDispatchSequence(), source.readinessEvidence(), source.sealOutcome(),
			source.failure(), source.agentReleased());
	}

	private static ProductionOutputContract.Snapshot copyBoundarySnapshot(
		ProductionOutputContract.Snapshot source, boolean inputEnded, boolean signalObserved,
		boolean firstOutputObserved, long firstOutputElapsedTicks, long quietElapsedTicks,
		long probabilityElapsedTicks, Set<UUID> seenOutputEventIds,
		long lastReadinessObservationSequence,
		Optional<ProductionOutputContract.ReadinessEvidence> readinessEvidence) {
		return new ProductionOutputContract.Snapshot(source.agentId(), source.agentResetGeneration(),
			source.outputObservationEpoch(), source.run(), source.priorNoProgressRuns(),
			source.milestone(), source.waitReason(), inputEnded, signalObserved, firstOutputObserved,
			firstOutputElapsedTicks, quietElapsedTicks, probabilityElapsedTicks,
			source.creditedOutputs(), source.diagnosticOutputs(), source.pendingOutputs(),
			source.dispatchedOutputs(), source.dispatchedEvents(), seenOutputEventIds,
			source.satisfiedStockScopes(), source.acceptedAgentReceipts(),
			source.lateWarehouseMaterial(), source.dispatchIntent(),
			lastReadinessObservationSequence, source.confirmedDispatchSequence(),
			readinessEvidence, source.sealOutcome(), source.failure(), source.agentReleased());
	}

	private static ProductionOutputContract.Snapshot copyLateSnapshot(
		ProductionOutputContract.Snapshot source, Map<StackKey, Long> diagnosticOutputs,
		List<ProductionOutputContract.LateWarehouseMaterial> lateWarehouseMaterial) {
		return new ProductionOutputContract.Snapshot(source.agentId(), source.agentResetGeneration(),
			source.outputObservationEpoch(), source.run(), source.priorNoProgressRuns(),
			source.milestone(), source.waitReason(), source.inputEnded(), source.signalObserved(),
			source.firstOutputObserved(), source.firstOutputElapsedTicks(), source.quietElapsedTicks(),
			source.probabilityElapsedTicks(), source.creditedOutputs(), diagnosticOutputs,
			source.pendingOutputs(), source.dispatchedOutputs(), source.dispatchedEvents(),
			source.seenOutputEventIds(), source.satisfiedStockScopes(),
			source.acceptedAgentReceipts(), lateWarehouseMaterial, source.dispatchIntent(),
			source.lastReadinessObservationSequence(), source.confirmedDispatchSequence(),
			source.readinessEvidence(), source.sealOutcome(), source.failure(),
			source.agentReleased());
	}

	private static Harness sealedTarget() {
		Harness harness = harness(policy(AgentCompletionMode.TARGET,
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, Map.of(PRIMARY, target(DESTINATION_A, 1)),
			Optional.of(PRIMARY), false, false, Set.of(OUTPUT_A)), 0, new Capacity(8));
		harness.contract.beginAtInputReady(harness.capacity);
		harness.contract.observeOutput(event(1, OUTPUT_A, PRIMARY, 1));
		harness.contract.markInputEnded(harness.run.fence());
		advance(harness, 0, true, true, EPOCH);
		return harness;
	}

	private static void drainAll(ProductionOutputContract contract) {
		while (contract.pendingOutputCount() > 0) {
			ProductionOutputContract.DispatchInstruction instruction = contract.nextDispatch()
				.instruction().orElseThrow();
			assertTrue(confirm(contract, instruction));
		}
	}

	private static void advance(Harness harness, long ticks, boolean loaded, boolean drained,
		UUID epoch) {
		harness.contract.advanceCompletionClock(
			authoritativeReadiness(harness, loaded, drained, epoch, ticks));
	}

	private static OutputLogisticsGateway.ReadinessSnapshot authoritativeReadiness(Harness harness,
		boolean loaded, boolean drained, UUID epoch, long ticks) {
		return OutputGatewayTestFixture.gateway(harness.contract, true, loaded, drained, epoch, true,
			ticks)
			.observeReadiness(harness.contract.readinessRequest()).snapshot().orElseThrow();
	}

	private static boolean confirm(ProductionOutputContract contract,
		ProductionOutputContract.DispatchInstruction instruction) {
		return contract.confirmDispatch(outputGateway(contract, true, true, true, EPOCH, true)
			.commit(instruction).confirmationReceipt().orElseThrow());
	}

	private static boolean complete(ProductionOutputContract contract, boolean endpointsEmpty,
		boolean packagerBuffersEmpty) {
		OutputLogisticsGateway.DrainSnapshot snapshot = outputGateway(contract, true, true,
			endpointsEmpty, EPOCH, packagerBuffersEmpty).observeDrain(contract.drainRequest())
			.snapshot().orElseThrow();
		return contract.completeDispatch(snapshot);
	}

	private static OutputLogisticsGateway outputGateway(Harness harness, boolean serverThread) {
		return outputGateway(harness.contract, serverThread, true, true, EPOCH, true);
	}

	private static OutputLogisticsGateway outputGateway(ProductionOutputContract contract,
		boolean serverThread, boolean loaded, boolean drained, UUID epoch,
		boolean packagerBuffersEmpty) {
		return OutputGatewayTestFixture.gateway(contract, serverThread, loaded, drained, epoch,
			packagerBuffersEmpty);
	}

	private static Harness harness(ProductionOutputPolicy policy, int priorNoProgressRuns,
		Capacity capacity) {
		ProductionRun run = new ProductionRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			4, 1, InputMode.EXACT, List.of(INPUT), WAREHOUSE, policy.generation(), Map.of(PRIMARY, 1L));
		RecordingStore store = new RecordingStore();
		return new Harness(run, new ProductionOutputContract(AGENT, 2, EPOCH, run, policy,
			priorNoProgressRuns, store), capacity, store, policy);
	}

	private static ProductionOutputPolicy policy(AgentCompletionMode completion,
		AgentEndpointState.OutputMode delivery, Map<StackKey, ProductionOutputPolicy.RequiredOutput> required,
		Optional<StackKey> primary, boolean noOutput, boolean probabilistic,
		Set<CreateRouteKey> observedRoutes) {
		Optional<AgentSignalSelector> selector = completion == AgentCompletionMode.SIGNAL
			? Optional.of(new AgentSignalSelector(new SpaceAddress(Level.OVERWORLD, null,
				BlockPos.ZERO), Direction.UP, 1)) : Optional.empty();
		return new ProductionOutputPolicy(9, delivery, completion, selector, WAREHOUSE, observedRoutes, required,
			Set.of(INCIDENTAL), primary, noOutput, probabilistic, 5, 8, 2);
	}

	private static ProductionOutputPolicy.RequiredOutput target(CreateRouteKey route, long count) {
		return new ProductionOutputPolicy.RequiredOutput(route, count,
			new ProductionOutputPolicy.OutputReceiver.Agent(RECEIVER_A));
	}

	private static ProductionOutputPolicy.RequiredOutput targetAgent(CreateRouteKey route, long count,
		UUID receiverAgentId) {
		return new ProductionOutputPolicy.RequiredOutput(route, count,
			new ProductionOutputPolicy.OutputReceiver.Agent(receiverAgentId));
	}

	private static ProductionOutputPolicy.OutputReceiver.Bare bareNetwork(
		StockObservationScope.NetworkSummary scope) {
		return new ProductionOutputPolicy.OutputReceiver.Bare(
			new ProductionOutputPolicy.BareReceiptProof.Network(scope));
	}

	private static ProductionOutputPolicy.OutputReceiver.Bare bareAttached(
		StockObservationScope.AttachedInventory scope,
		AttachedInventoryBindingResolver.BindingFact binding) {
		return new ProductionOutputPolicy.OutputReceiver.Bare(
			new ProductionOutputPolicy.BareReceiptProof.Attached(scope, binding));
	}

	private static InventoryIdentity inventory(SpaceAddress packager, long generation) {
		return new InventoryIdentity(new SpaceAddress(packager.dimension(), packager.subLevelId(),
			packager.localPos().relative(Direction.NORTH)), Direction.SOUTH,
			ResourceLocation.parse("minecraft:chest"),
			Optional.of(ResourceLocation.parse("minecraft:chest")),
			UUID.fromString("00000000-0000-0000-0000-000000000910"), generation);
	}

	private static OutputEvent event(int id, CreateRouteKey output, StackKey stack, long count) {
		return new OutputEvent(new UUID(0, id), EPOCH, output, stack, count);
	}

	private static CreateRouteKey route(String address) {
		return new CreateRouteKey(NETWORK, address);
	}

	private record Harness(ProductionRun run, ProductionOutputContract contract, Capacity capacity,
		RecordingStore store, ProductionOutputPolicy policy) {}

	private static final class RecordingStore implements ProductionOutputContract.Store {
		private final List<ProductionOutputContract.Snapshot> snapshots = new ArrayList<>();
		private final List<ProductionOutputLedger> ledgers = new ArrayList<>();
		@Override public void persist(ProductionOutputContract.Snapshot snapshot) { snapshots.add(snapshot); }
		@Override public void persistLedger(ProductionOutputLedger ledger) { ledgers.add(ledger); }
	}

	private static final class Capacity implements ProductionOutputContract.OutputProcessCapacity {
		private int available;
		private int nextId;
		private final Map<UUID, LeaseState> active = new LinkedHashMap<>();
		private final Map<CreateRouteKey, Integer> destinationAcquisitions = new LinkedHashMap<>();
		private final Set<CreateRouteKey> blockedDestinations;
		private Capacity(int available) { this(available, Set.of()); }
		private Capacity(int available, Set<CreateRouteKey> blockedDestinations) {
			this.available = available;
			this.blockedDestinations = Set.copyOf(blockedDestinations);
		}

		@Override
		public Optional<ProductionOutputContract.ProcessLease> tryAcquireObservation(UUID runId,
			UUID goalId, CreateRouteKey outputAddress) {
			return acquire(outputAddress, false);
		}

		@Override
		public Optional<ProductionOutputContract.ProcessLease> tryAcquireOutputDestination(UUID runId,
			UUID goalId, CreateRouteKey destination) {
			if (blockedDestinations.contains(destination)) return Optional.empty();
			Optional<ProductionOutputContract.ProcessLease> lease = acquire(destination, true);
			lease.ifPresent(ignored -> destinationAcquisitions.merge(destination, 1, Integer::sum));
			return lease;
		}

		private Optional<ProductionOutputContract.ProcessLease> acquire(CreateRouteKey address,
			boolean destination) {
			if (available == 0) return Optional.empty();
			available--;
			ProductionOutputContract.ProcessLease lease = new ProductionOutputContract.ProcessLease(
				new UUID(1, ++nextId), address);
			active.put(lease.processId(), new LeaseState(lease, destination));
			return Optional.of(lease);
		}

		@Override
		public void release(ProductionOutputContract.ProcessLease lease) {
			if (active.remove(lease.processId()) != null) available++;
		}

		@Override
		public boolean isLeaseActive(ProductionOutputContract.ProcessLease lease,
			ProductionOutputContract.LeasePurpose purpose, UUID runId, UUID goalId) {
			LeaseState state = active.get(lease.processId());
			return state != null && state.lease().equals(lease)
				&& state.destination() == (purpose == ProductionOutputContract.LeasePurpose.OUTPUT_DESTINATION);
		}

		private int activeObservationLeases() {
			return (int) active.values().stream().filter(state -> !state.destination()).count();
		}
		private int destinationAcquisitions(CreateRouteKey destination) {
			return destinationAcquisitions.getOrDefault(destination, 0);
		}
		private boolean hasDestinationLease(CreateRouteKey destination) {
			return active.values().stream().anyMatch(state -> state.destination()
				&& state.lease().address().equals(destination));
		}
		private void revokeDestination(CreateRouteKey destination) {
			active.entrySet().removeIf(entry -> entry.getValue().destination()
				&& entry.getValue().lease().address().equals(destination));
		}
		private void revokeObservation(CreateRouteKey outputAddress) {
			active.entrySet().removeIf(entry -> !entry.getValue().destination()
				&& entry.getValue().lease().address().equals(outputAddress));
		}
		private void addCapacity(int slots) { available += slots; }
		private record LeaseState(ProductionOutputContract.ProcessLease lease, boolean destination) {}
	}
}
