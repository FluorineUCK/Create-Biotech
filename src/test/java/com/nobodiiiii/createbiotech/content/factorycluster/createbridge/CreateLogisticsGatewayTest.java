package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class CreateLogisticsGatewayTest {
	private static final UUID AGENT = id(1);
	private static final UUID RUN = id(2);
	private static final UUID OUTPUT_EPOCH = id(3);
	private static final CreateRouteKey ROUTE = new CreateRouteKey(id(4), "managed-input");
	private static final CreateRouteKey OTHER_ROUTE = new CreateRouteKey(id(5), "other-input");
	private static Map<StackKey, Long> MANIFEST;

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
		MANIFEST = Map.of(new StackKey(new ItemStack(Items.STONE)), 4L);
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

	@Test void validCommitCallsCreateOnceAndDuplicateIntentReturnsTheSameImmutableFact() {
		FakeWorld world = new FakeWorld(validState());
		world.nextOrders = List.of(new NativeOrderLedger.NativeOrderIdentity(77, Set.of(0, 1)));
		CreateLogisticsGateway gateway = gateway(world, new FakeCommitStore());
		CreateDispatchCommand command = command(id(10));

		CreateLogisticsGateway.CommitResult first = gateway.commit(command);
		CreateLogisticsGateway.CommitResult duplicate = gateway.commit(command);
		assertTrue(first.committed());
		assertSame(first, duplicate);
		assertEquals(1, world.dispatches);
		assertEquals(List.of(new NativeOrderLedger.NativeOrderIdentity(77, Set.of(0, 1))),
			first.nativeOrders());
		assertFalse(first.nativeOrders() instanceof ArrayList);
	}

	@Test void everyAuthoritativeFenceIsRevalidatedBeforeCreateIsCalled() {
		List<CreateLogisticsGateway.CommitContext> invalid = List.of(
			state(id(99), 5, 17, ROUTE, true, 31, AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 6, 17, ROUTE, true, 31, AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 5, 18, ROUTE, true, 31, AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 5, 17, new CreateRouteKey(id(4), "other"), true, 31,
				AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 5, 17, ROUTE, false, 31, AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 5, 17, ROUTE, true, 32, AgentInputPhase.DISPATCH_INTENT),
			state(AGENT, 5, 17, ROUTE, true, 31, AgentInputPhase.READY));

		for (int index = 0; index < invalid.size(); index++) {
			FakeWorld world = new FakeWorld(invalid.get(index));
			CreateLogisticsGateway.CommitResult result =
				gateway(world, new FakeCommitStore()).commit(command(id(20 + index)));
			assertFalse(result.committed(), "invalid authoritative field " + index);
			assertEquals(0, world.dispatches);
		}
	}

	@Test void intentIdCollisionWithDifferentCommandNeverCallsCreateAgain() {
		FakeWorld world = new FakeWorld(validState());
		CreateLogisticsGateway gateway = gateway(world, new FakeCommitStore());
		CreateDispatchCommand first = command(id(30));
		assertTrue(gateway.commit(first).committed());
		CreateDispatchCommand collision = first.withSourceInventorySnapshotGeneration(32);
		CreateLogisticsGateway.CommitResult result = gateway.commit(collision);
		assertFalse(result.committed());
		assertEquals(CreateLogisticsGateway.Rejection.INTENT_ID_COLLISION, result.rejection());
		assertEquals(1, world.dispatches);
	}

	@Test void failedNativeDispatchCanRetryTheSameApprovedIntentAfterReload() {
		FakeWorld world = new FakeWorld(validState());
		world.dispatchAccepted = false;
		FakeCommitStore store = new FakeCommitStore();
		CreateLogisticsGateway gateway = gateway(world, store);
		CreateDispatchCommand command = command(id(40));
		CreateLogisticsGateway.CommitResult first = gateway.commit(command);
		assertFalse(first.committed());
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED, first.rejection());
		assertTrue(store.load(command.intentId()).orElseThrow().allowsRetryWithoutMaterial());
		world.dispatchAccepted = true;
		CreateLogisticsGateway.CommitResult recovered = gateway(world, store).commit(command);
		CreateLogisticsGateway.CommitResult duplicate = gateway(world, store).commit(command);
		assertTrue(recovered.committed());
		assertSame(recovered, duplicate);
		assertTrue(store.load(command.intentId()).orElseThrow().provesMaterialCommit());
		assertEquals(command.intentId(), recovered.command().intentId());
		assertEquals(2, world.dispatches, "one rejected attempt plus one accepted native order");
	}

	@Test void unloadedPackagerArtifactRetriesTheSameApprovedIntentAfterReload() {
		FakeWorld world = new FakeWorld(
			state(AGENT, 5, 17, ROUTE, false, 31, AgentInputPhase.DISPATCH_INTENT));
		FakeCommitStore store = new FakeCommitStore();
		CreateDispatchCommand command = command(id(140));
		CreateLogisticsGateway.CommitResult rejected = gateway(world, store).commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED, rejected.rejection());
		assertTrue(store.load(command.intentId()).orElseThrow().allowsRetryWithoutMaterial());
		assertFalse(store.load(command.intentId()).orElseThrow().provesMaterialCommit());
		assertEquals(0, world.dispatches);

		world.state = validState();
		CreateLogisticsGateway.CommitResult recovered = gateway(world, store).commit(command);
		assertTrue(recovered.committed());
		assertEquals(command.intentId(), recovered.command().intentId());
		assertTrue(store.load(command.intentId()).orElseThrow().provesMaterialCommit());
		assertEquals(1, world.dispatches);
		assertTrue(gateway(world, store).commit(command).committed());
		assertEquals(1, world.dispatches);
	}

	@Test void sourceSnapshotMismatchArtifactRetriesOnlyTheExactApprovedCommand() {
		FakeWorld world = new FakeWorld(
			state(AGENT, 5, 17, ROUTE, true, 32, AgentInputPhase.DISPATCH_INTENT));
		FakeCommitStore store = new FakeCommitStore();
		CreateDispatchCommand command = command(id(141));
		CreateLogisticsGateway.CommitResult rejected = gateway(world, store).commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.SOURCE_SNAPSHOT_MISMATCH,
			rejected.rejection());
		assertTrue(store.load(command.intentId()).orElseThrow().allowsRetryWithoutMaterial());
		CreateLogisticsGateway.CommitResult collision = gateway(world, store).commit(
			command.withSourceInventorySnapshotGeneration(32));
		assertEquals(CreateLogisticsGateway.Rejection.INTENT_ID_COLLISION,
			collision.rejection());
		assertEquals(0, world.dispatches);

		world.state = validState();
		CreateLogisticsGateway.CommitResult recovered = gateway(world, store).commit(command);
		assertTrue(recovered.committed());
		assertEquals(command.intentId(), recovered.command().intentId());
		assertEquals(1, world.dispatches);
	}

	@Test void durableCommitFactPreventsASecondWorldCallAfterGatewayRestart() {
		FakeWorld world = new FakeWorld(validState());
		FakeCommitStore store = new FakeCommitStore();
		CreateDispatchCommand command = command(id(41));
		CreateLogisticsGateway.CommitResult first = gateway(world, store).commit(command);
		CreateLogisticsGateway.CommitResult restored = gateway(world, store).commit(command);
		assertEquals(first, restored);
		assertEquals(1, world.dispatches);
	}

	@Test void crashAfterWorldCallLeavesDurableUncertaintyAndRestartNeverRetries() {
		FakeWorld world = new FakeWorld(validState());
		FakeCommitStore store = new FakeCommitStore();
		store.failCompletedPersist = true;
		CreateDispatchCommand command = command(id(42));
		CreateLogisticsGateway.CommitResult first = gateway(world, store).commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN,
			first.rejection());
		store.failCompletedPersist = false;
		CreateLogisticsGateway.CommitResult restored = gateway(world, store).commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN,
			restored.rejection());
		assertEquals(1, world.dispatches);
	}

	@Test void suspendedDurableAttemptingBoundaryNeverCallsCreateAndReloadNeverRedispatches() {
		FakeWorld world = new FakeWorld(validState());
		FakeCommitStore store = new FakeCommitStore();
		CreateDispatchCommand command = command(id(142));
		CreateLogisticsGateway suspended = new CreateLogisticsGateway(world, world, world, store,
			ignored -> false);

		CreateLogisticsGateway.CommitResult first = suspended.commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN,
			first.rejection());
		assertEquals(CreateLogisticsGateway.CommitAttemptState.ATTEMPTING,
			store.load(command.intentId()).orElseThrow().state());
		assertEquals(0, world.dispatches);

		CreateLogisticsGateway.CommitResult restored = gateway(world, store).commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN,
			restored.rejection());
		assertEquals(0, world.dispatches, "reload must not cross an uncertain native boundary");
	}

	@Test void offThreadGuardRejectsBeforeAnyAuthoritativeWorldCapture() {
		FakeWorld world = new FakeWorld(validState());
		world.serverThread = false;
		CreateLogisticsGateway.CommitResult result = gateway(world, new FakeCommitStore())
			.commit(command(id(43)));
		assertEquals(CreateLogisticsGateway.Rejection.NOT_SERVER_THREAD, result.rejection());
		assertEquals(0, world.captures);
		assertEquals(0, world.dispatches);
	}

	@Test void durableDispatchIntentMustBeTheExactCommandBeingCommitted() {
		CreateDispatchCommand command = command(id(44));
		CreateLogisticsGateway.CommitContext mismatched = new CreateLogisticsGateway.CommitContext(
			AGENT, 5, 17, ROUTE, true, 31, AgentInputPhase.DISPATCH_INTENT,
			command.withIntentId(id(45)));
		FakeWorld world = new FakeWorld(mismatched);
		world.bindDurableCommandToRequest = false;
		CreateLogisticsGateway.CommitResult result = gateway(world, new FakeCommitStore())
			.commit(command);
		assertEquals(CreateLogisticsGateway.Rejection.DURABLE_COMMAND_MISMATCH,
			result.rejection());
		assertTrue(result.confirmationReceipt().isEmpty());
		assertEquals(0, world.dispatches);
	}

	@Test void immutablePackageObservationDefensivelyCopiesAcceptedCountsAndContext() {
		Map<StackKey, Long> accepted = new java.util.LinkedHashMap<>(MANIFEST);
		Map<StackKey, Long> context = new java.util.LinkedHashMap<>(MANIFEST);
		NativePackageObservation observation = new NativePackageObservation(ROUTE, true, 7, 0, 0,
			true, true, new NativeOrderLedger.OrderContext(context), accepted);
		accepted.clear();
		context.clear();
		assertEquals(MANIFEST, observation.acceptedStacks());
		assertEquals(MANIFEST, observation.orderContext().orElseThrow().orderedStacks());
		assertNotSame(accepted, observation.acceptedStacks());
	}

	@Test void publishAndRecoveryPersistenceFailureLatchesRouteBeforeEscalatingGlobal() {
		NativePackageObservation observation = new NativePackageObservation(ROUTE, true, 7, 0, 0,
			true, true, new NativeOrderLedger.OrderContext(MANIFEST), MANIFEST);
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext context =
			NativePackageObservation.RecoveryContext.routeSpecific(ROUTE, ROUTE.address(),
				new BlockPos(1, 2, 3));
		int[] recoveryAttempts = {0};
		NativePackageObservation.AcceptancePublisher publisher =
			new NativePackageObservation.AcceptancePublisher() {
				@Override public Optional<CreateRouteKey> managedInputRoute(
					com.simibubi.create.content.logistics.packager.PackagerBlockEntity ignored,
					String address) {
					return Optional.of(ROUTE);
				}
				@Override public void publish(NativePackageObservation ignored) {
					throw new IllegalStateException("persistence unavailable");
				}
				@Override public void recordRecoveryRequired(
					NativePackageObservation.RecoveryContext actualContext,
					NativePackageObservation ignored,
					Throwable failure) {
					assertEquals(context, actualContext);
					assertTrue(fence.routeFenced(ROUTE),
						"runtime route latch must precede recovery persistence");
					assertFalse(fence.globallyFenced());
					recoveryAttempts[0]++;
					throw new IllegalStateException("recovery persistence unavailable");
				}
			};
		assertDoesNotThrow(() -> NativePackageObservation.publishSafely(publisher, observation,
			fence, context));
		assertEquals(1, recoveryAttempts[0]);
		assertTrue(fence.routeFenced(ROUTE));
		assertTrue(fence.globallyFenced(),
			"failed route-specific recovery persistence must escalate server-wide");
		assertEquals("recovery persistence unavailable",
			fence.globalFailure().orElseThrow().getMessage());
	}

	@Test void runtimeFenceSurvivesCloseReinstallAndInFlightOldPublisher() throws Exception {
		Object server = new Object();
		NativePackageObservation.RuntimeFenceRegistry<Object> registry =
			new NativePackageObservation.RuntimeFenceRegistry<>();
		NativePackageObservation.AcceptancePublisher oldPublisher = publisherThatFailsPublish(
			(context, observation, failure) -> {
				throw new IllegalStateException("old recovery store unavailable");
			});
		AutoCloseable oldInstallation = registry.install(server, oldPublisher);
		NativePackageObservation.RuntimeBinding inFlight = registry.binding(server).orElseThrow();
		NativePackageObservation.RuntimeRecoveryFence originalFence = inFlight.fence();
		oldInstallation.close();

		AutoCloseable newInstallation = registry.install(server, publisherThatFailsPublish(
			(context, observation, failure) -> {}));
		try {
			assertSame(originalFence, registry.fence(server).orElseThrow(),
				"runtime reinstall must not replace the monotonic server-lifetime fence");
			NativePackageObservation.publishSafely(inFlight.publisher(), observation(ROUTE),
				inFlight.fence(), routeContext(ROUTE));
			assertTrue(registry.fence(server).orElseThrow().globallyFenced(),
				"in-flight work from a closed publisher must latch the still-authoritative fence");
		} finally {
			newInstallation.close();
		}
	}

	@Test void latchedFenceSuppressesLaterObservationsBeforeTheyReachPublisher() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		int[] attempts = {0};
		NativePackageObservation.AcceptancePublisher publisher =
			new NativePackageObservation.AcceptancePublisher() {
				@Override public Optional<CreateRouteKey> managedInputRoute(
					com.simibubi.create.content.logistics.packager.PackagerBlockEntity ignored,
					String address) {
					return Optional.of(ROUTE);
				}
				@Override public void publish(NativePackageObservation ignored) {
					attempts[0]++;
					if (ignored.route().equals(ROUTE))
						throw new IllegalStateException("observation persistence unavailable");
				}
				@Override public void recordRecoveryRequired(
					NativePackageObservation.RecoveryContext context,
					NativePackageObservation observation, Throwable failure) {}
			};

		NativePackageObservation.publishSafely(publisher, observation(ROUTE), fence,
			routeContext(ROUTE));
		NativePackageObservation.publishSafely(publisher, observation(ROUTE), fence,
			routeContext(ROUTE));
		assertEquals(1, attempts[0], "a route latch must actively stop later observations");

		NativePackageObservation.publishSafely(publisher, observation(OTHER_ROUTE), fence,
			routeContext(OTHER_ROUTE));
		assertEquals(2, attempts[0], "a route latch must not stop an unrelated route");
		NativePackageObservation.handlePreparationFailure(publisher, fence,
			NativePackageObservation.RecoveryContext.global(null, null),
			new IllegalStateException("route lookup unavailable"));
		NativePackageObservation.publishSafely(publisher, observation(OTHER_ROUTE), fence,
			routeContext(OTHER_ROUTE));
		assertEquals(2, attempts[0], "a global latch must actively stop every route");
	}

	@Test void globalRecoveryContextIsEnrichedWhenLaterFailureLocatesTheRoute() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(context, observation, failure) -> {});
		NativePackageObservation.handlePreparationFailure(publisher, fence,
			NativePackageObservation.RecoveryContext.global(null, null),
			new IllegalStateException("early lookup failure"));

		NativePackageObservation.RecoveryContext richer = routeContext(ROUTE);
		NativePackageObservation.handlePreparationFailure(publisher, fence, richer,
			new IllegalStateException("metadata failure"));
		assertEquals(richer, fence.globalContext().orElseThrow(),
			"later route/address/Packager evidence must enrich the global recovery latch");
		assertTrue(fence.routeFenced(ROUTE));
	}

	@Test void productionObservationShortCircuitsGlobalFenceBeforeLookupOrMetadata() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		int[] routeLookups = {0};
		int[] sourceReads = {0};
		NativePackageObservation.AcceptancePublisher publisher = countingPublisher(routeLookups,
			new int[1]);
		NativePackageObservation.handlePreparationFailure(publisher, fence,
			NativePackageObservation.RecoveryContext.global(null, null),
			new IllegalStateException("global recovery required"));

		NativePackageObservation.publishAccepted(
			new NativePackageObservation.RuntimeBinding(publisher, fence),
			countingAcceptedSource(sourceReads));
		assertEquals(0, routeLookups[0]);
		assertEquals(0, sourceReads[0],
			"global latch must stop before address, route, or metadata reads");
	}

	@Test void productionObservationShortCircuitsRouteFenceBeforeMetadataCopy() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.publishSafely(publisherThatFailsPublish(
			(context, observation, failure) -> {}), observation(ROUTE), fence,
			routeContext(ROUTE));
		int[] routeLookups = {0};
		int[] publishes = {0};
		int[] sourceReads = {0};
		NativePackageObservation.AcceptancePublisher publisher = countingPublisher(routeLookups,
			publishes);

		NativePackageObservation.publishAccepted(
			new NativePackageObservation.RuntimeBinding(publisher, fence),
			countingAcceptedSource(sourceReads));
		assertEquals(1, routeLookups[0], "route lookup is required to identify a route latch");
		assertEquals(3, sourceReads[0],
			"only the native marker, Packager position, and address may be read before route lookup");
		assertEquals(0, publishes[0], "latched route must not publish copied evidence");
	}

	@Test void recoveryEvidenceNeverSynthesizesContextsOrOverwritesCallbackFailure() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext first = routeContext(ROUTE);
		BlockPos otherPosition = new BlockPos(20, 21, 22);
		NativePackageObservation.RecoveryContext unrelatedPartial =
			NativePackageObservation.RecoveryContext.global(OTHER_ROUTE.address(), otherPosition);
		IllegalStateException callbackFailure =
			new IllegalStateException("recovery persistence unavailable");
		int[] callbacks = {0};
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(context, observation, failure) -> {
				if (callbacks[0]++ == 0) throw callbackFailure;
			});

		NativePackageObservation.handlePreparationFailure(publisher, fence, first,
			new IllegalStateException("metadata failure"));
		NativePackageObservation.handlePreparationFailure(publisher, fence, unrelatedPartial,
			new IllegalStateException("later lookup failure"));
		assertEquals(first, fence.globalContext().orElseThrow(),
			"recovery context must remain one real event, never a field-wise synthetic identity");
		assertSame(callbackFailure, fence.globalFailure().orElseThrow(),
			"later preparation failures must not overwrite the recovery-store failure");
	}

	@Test void callbackFailureRemainsAtomicallyPairedWithItsOwnContext() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext first =
			NativePackageObservation.RecoveryContext.global(null, null);
		IllegalStateException callbackFailure =
			new IllegalStateException("recovery persistence unavailable");
		int[] callbacks = {0};
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(context, observation, failure) -> {
				if (callbacks[0]++ == 0) throw callbackFailure;
			});
		NativePackageObservation.handlePreparationFailure(publisher, fence, first,
			new IllegalStateException("early lookup failure"));
		NativePackageObservation.handlePreparationFailure(publisher, fence, routeContext(ROUTE),
			new IllegalStateException("later metadata failure"));

		NativePackageObservation.RecoveryEvidence evidence =
			fence.globalEvidence().orElseThrow();
		assertEquals(first, evidence.context());
		assertSame(callbackFailure, evidence.failure(),
			"context and recovery callback failure must always identify the same event");
	}

	@Test void mismatchedLookupCannotHideBehindAnExistingRouteFence() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.publishSafely(publisherThatFailsPublish(
			(context, observation, failure) -> {}), observation(OTHER_ROUTE), fence,
			routeContext(OTHER_ROUTE));
		NativePackageObservation.AcceptancePublisher publisher = countingPublisher(new int[1],
			new int[1]);
		NativePackageObservation.AcceptedPackageSource mismatched =
			new NativePackageObservation.AcceptedPackageSource() {
				@Override public boolean isNativePackage() { return true; }
				@Override public BlockPos packagerPosition() { return new BlockPos(1, 2, 3); }
				@Override public String address() { return ROUTE.address(); }
				@Override public Optional<CreateRouteKey> managedInputRoute(
					NativePackageObservation.AcceptancePublisher ignored, String exactAddress) {
					return Optional.of(OTHER_ROUTE);
				}
				@Override public NativePackageObservation copy(CreateRouteKey route) {
					throw new AssertionError("mismatched lookup must fail before metadata copy");
				}
			};

		NativePackageObservation.publishAccepted(
			new NativePackageObservation.RuntimeBinding(publisher, fence), mismatched);
		assertTrue(fence.globallyFenced(),
			"an inconsistent lookup must fail globally even when its returned route is latched");
		assertEquals(Optional.empty(), fence.globalContext().orElseThrow().route());
	}

	@Test void successfulRecoveryPersistenceKeepsRouteSpecificContextAndFence() {
		NativePackageObservation observation = new NativePackageObservation(ROUTE, true, 8, 0, 0,
			true, true, new NativeOrderLedger.OrderContext(MANIFEST), MANIFEST);
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext context =
			NativePackageObservation.RecoveryContext.routeSpecific(ROUTE, ROUTE.address(),
				new BlockPos(4, 5, 6));
		NativePackageObservation.RecoveryContext[] recorded = {null};
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(actualContext, ignored, failure) -> recorded[0] = actualContext);
		NativePackageObservation.publishSafely(publisher, observation, fence, context);
		assertEquals(context, recorded[0]);
		assertTrue(fence.routeFenced(ROUTE));
		assertFalse(fence.globallyFenced());
	}

	@Test void routeLookupFailureLatchesGlobalBeforeRecoveryPersistence() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext context =
			NativePackageObservation.RecoveryContext.global("raw-input", new BlockPos(7, 8, 9));
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(actualContext, ignored, failure) -> {
				assertEquals(context, actualContext);
				assertTrue(fence.globallyFenced(),
					"unlocatable route failure must latch globally before persistence");
				throw new IllegalStateException("recovery persistence unavailable");
			});
		assertDoesNotThrow(() -> NativePackageObservation.handlePreparationFailure(publisher,
			fence, context, new IllegalStateException("route lookup failed")));
		assertTrue(fence.globallyFenced());
		assertEquals(context, fence.globalContext().orElseThrow());
	}

	@Test void metadataCopyFailurePreservesKnownContextButFencesServerGlobally() {
		NativePackageObservation.RuntimeRecoveryFence fence =
			new NativePackageObservation.RuntimeRecoveryFence();
		NativePackageObservation.RecoveryContext context =
			NativePackageObservation.RecoveryContext.routeSpecific(ROUTE, ROUTE.address(),
				new BlockPos(10, 11, 12));
		NativePackageObservation.RecoveryContext[] recorded = {null};
		NativePackageObservation.AcceptancePublisher publisher = publisherThatFailsPublish(
			(actualContext, ignored, failure) -> recorded[0] = actualContext);
		NativePackageObservation.handlePreparationFailure(publisher, fence, context,
			new IllegalStateException("metadata copy failed"));
		assertEquals(context, recorded[0]);
		assertTrue(fence.routeFenced(ROUTE));
		assertTrue(fence.globallyFenced());
		assertEquals(context, fence.routeContext(ROUTE).orElseThrow());
	}

	@Test void observationFailureCannotChangeTheNativeAcceptedReturnValue() {
		assertTrue(NativePackageObservation.preserveNativeAcceptance(true, false,
			() -> { throw new AssertionError("observer failed"); }));
		assertFalse(NativePackageObservation.preserveNativeAcceptance(false, false,
			() -> { throw new AssertionError("must not observe rejected unpack"); }));
	}

	@Test void bareAdapterDispatchesOnlyNeverRegisteredPermittedStandardPatterns() {
		FakeWorld world = new FakeWorld(validState());
		CreateLogisticsGateway.BareDispatchAdapter bare =
			bareGateway(world, new FakeBareCommitStore());
		CreateLogisticsGateway.BareDispatchRequest command = bareCommand(id(50));
		assertTrue(bare.commit(command).committed());
		assertFalse(bare.commit(bareCommand(id(51), PatternKind.STANDARD, false)).committed());
		world.bareState = new CreateLogisticsGateway.BareCommitContext(
			new AgentResolution.KnownUnavailable(ROUTE, List.of(), 9), 9,
			PatternKind.STANDARD, true, 31);
		assertFalse(bare.commit(bareCommand(id(52))).committed());
		world.bareState = validBareState(31);
		assertFalse(bare.commit(bareCommand(id(55), PatternKind.SEQUENCED, true)).committed());
		assertEquals(1, world.dispatches);
	}

	@Test void bareAdapterAlsoRevalidatesServerThreadAndSourceSnapshot() {
		FakeWorld offThread = new FakeWorld(validState());
		offThread.serverThread = false;
		assertFalse(bareGateway(offThread, new FakeBareCommitStore())
			.commit(bareCommand(id(53)))
			.committed());
		assertEquals(0, offThread.bareCaptures);
		assertEquals(0, offThread.dispatches);

		FakeWorld changedSource = new FakeWorld(validState());
		changedSource.bareState = validBareState(32);
		assertFalse(bareGateway(changedSource, new FakeBareCommitStore())
			.commit(bareCommand(id(54)))
			.committed());
		assertEquals(0, changedSource.dispatches);
	}

	@Test void bareAdapterRevalidatesDirectoryGenerationResolutionAndFrozenPermissionAtCommit() {
		CreateLogisticsGateway.BareDispatchRequest request = new CreateLogisticsGateway.BareDispatchRequest(
			id(56), RUN, ROUTE, 31, 9, PatternKind.STANDARD, true, MANIFEST);
		FakeWorld world = new FakeWorld(validState());
		world.bareState = new CreateLogisticsGateway.BareCommitContext(
			new AgentResolution.NeverRegistered(ROUTE), 10, PatternKind.STANDARD, true, 31);
		CreateLogisticsGateway.BareCommitResult stale = bareGateway(world,
			new FakeBareCommitStore()).commit(request);
		assertEquals(CreateLogisticsGateway.Rejection.DIRECTORY_STATE_MISMATCH, stale.rejection());
		assertEquals(0, world.dispatches);

		world.bareState = new CreateLogisticsGateway.BareCommitContext(
			new AgentResolution.KnownUnavailable(ROUTE, List.of(), 10), 9,
			PatternKind.STANDARD, true, 31);
		CreateLogisticsGateway.BareCommitResult registered = bareGateway(world,
			new FakeBareCommitStore()).commit(request);
		assertEquals(CreateLogisticsGateway.Rejection.DIRECTORY_STATE_MISMATCH,
			registered.rejection());
		assertEquals(0, world.dispatches);

		world.bareState = new CreateLogisticsGateway.BareCommitContext(
			new AgentResolution.NeverRegistered(ROUTE), 9, PatternKind.STANDARD, false, 31);
		CreateLogisticsGateway.BareCommitResult permissionChanged = bareGateway(world,
			new FakeBareCommitStore()).commit(request);
		assertEquals(CreateLogisticsGateway.Rejection.BARE_PERMISSION_MISMATCH,
			permissionChanged.rejection());
		assertEquals(0, world.dispatches);
	}

	private static CreateLogisticsGateway.BareDispatchRequest bareCommand(UUID intentId) {
		return bareCommand(intentId, PatternKind.STANDARD, true);
	}

	private static CreateLogisticsGateway.BareDispatchRequest bareCommand(UUID intentId,
		PatternKind kind, boolean permitsBare) {
		return new CreateLogisticsGateway.BareDispatchRequest(intentId, RUN, ROUTE, 31, 9,
			kind, permitsBare, MANIFEST);
	}

	private static CreateLogisticsGateway.BareCommitContext validBareState(long sourceGeneration) {
		return new CreateLogisticsGateway.BareCommitContext(
			new AgentResolution.NeverRegistered(ROUTE), 9, PatternKind.STANDARD, true,
			sourceGeneration);
	}

	private static CreateDispatchCommand command(UUID intentId) {
		return new CreateDispatchCommand(intentId, AGENT, 5, 17, RUN, OUTPUT_EPOCH, 0, ROUTE, 31,
			MANIFEST);
	}

	private static CreateLogisticsGateway.CommitContext validState() {
		return state(AGENT, 5, 17, ROUTE, true, 31, AgentInputPhase.DISPATCH_INTENT);
	}

	private static CreateLogisticsGateway.CommitContext state(UUID agentId, long resetGeneration,
		long fence, CreateRouteKey route, boolean loaded, long sourceGeneration,
		AgentInputPhase phase) {
		return new CreateLogisticsGateway.CommitContext(agentId, resetGeneration, fence, route,
			loaded, sourceGeneration, phase, command(id(0)));
	}

	private static CreateLogisticsGateway gateway(FakeWorld world, FakeCommitStore store) {
		return new CreateLogisticsGateway(world, world, world, store);
	}

	private static CreateLogisticsGateway.BareDispatchAdapter bareGateway(FakeWorld world,
		FakeBareCommitStore store) {
		return new CreateLogisticsGateway.BareDispatchAdapter(world, world, world, store);
	}

	private static NativePackageObservation.AcceptancePublisher publisherThatFailsPublish(
		RecoveryRecorder recorder) {
		return new NativePackageObservation.AcceptancePublisher() {
			@Override public Optional<CreateRouteKey> managedInputRoute(
				com.simibubi.create.content.logistics.packager.PackagerBlockEntity ignored,
				String address) {
				return Optional.of(ROUTE);
			}
			@Override public void publish(NativePackageObservation ignored) {
				throw new IllegalStateException("observation persistence unavailable");
			}
			@Override public void recordRecoveryRequired(
				NativePackageObservation.RecoveryContext context,
				NativePackageObservation observation, Throwable failure) {
				recorder.record(context, observation, failure);
			}
		};
	}

	private static NativePackageObservation.AcceptancePublisher countingPublisher(
		int[] routeLookups, int[] publishes) {
		return new NativePackageObservation.AcceptancePublisher() {
			@Override public Optional<CreateRouteKey> managedInputRoute(
				com.simibubi.create.content.logistics.packager.PackagerBlockEntity ignored,
				String address) {
				routeLookups[0]++;
				return Optional.of(ROUTE);
			}
			@Override public void publish(NativePackageObservation ignored) { publishes[0]++; }
			@Override public void recordRecoveryRequired(
				NativePackageObservation.RecoveryContext context,
				NativePackageObservation observation, Throwable failure) {}
		};
	}

	private static NativePackageObservation.AcceptedPackageSource countingAcceptedSource(
		int[] sourceReads) {
		return new NativePackageObservation.AcceptedPackageSource() {
			@Override public boolean isNativePackage() { sourceReads[0]++; return true; }
			@Override public BlockPos packagerPosition() {
				sourceReads[0]++;
				return new BlockPos(1, 2, 3);
			}
			@Override public String address() { sourceReads[0]++; return ROUTE.address(); }
			@Override public Optional<CreateRouteKey> managedInputRoute(
				NativePackageObservation.AcceptancePublisher publisher, String exactAddress) {
				return publisher.managedInputRoute(null, exactAddress);
			}
			@Override public NativePackageObservation copy(CreateRouteKey route) {
				sourceReads[0]++;
				throw new AssertionError("metadata copy must be fenced before this call");
			}
		};
	}

	private static NativePackageObservation observation(
		CreateRouteKey route) {
		return new NativePackageObservation(route, true, 7, 0, 0, true, true,
			new NativeOrderLedger.OrderContext(MANIFEST), MANIFEST);
	}

	private static NativePackageObservation.RecoveryContext routeContext(CreateRouteKey route) {
		return NativePackageObservation.RecoveryContext.routeSpecific(route, route.address(),
			new BlockPos(1, 2, 3));
	}

	@FunctionalInterface
	private interface RecoveryRecorder {
		void record(NativePackageObservation.RecoveryContext context,
			NativePackageObservation observation, Throwable failure);
	}

	private static final class FakeWorld implements CreateLogisticsGateway.AuthoritativeState,
		CreateLogisticsGateway.NativeDispatcher, CreateLogisticsGateway.BareAuthoritativeState,
		CreateLogisticsGateway.ServerThreadGuard {
		private CreateLogisticsGateway.CommitContext state;
		private CreateLogisticsGateway.BareCommitContext bareState =
			validBareState(31);
		private boolean bindDurableCommandToRequest = true;
		private boolean serverThread = true;
		private int captures;
		private int bareCaptures;
		private int dispatches;
		private boolean dispatchAccepted = true;
		private List<NativeOrderLedger.NativeOrderIdentity> nextOrders =
			List.of(new NativeOrderLedger.NativeOrderIdentity(1, Set.of(0)));

		private FakeWorld(CreateLogisticsGateway.CommitContext state) { this.state = state; }
		@Override public CreateLogisticsGateway.CommitContext capture(CreateDispatchCommand ignored) {
			captures++;
			return new CreateLogisticsGateway.CommitContext(state.agentId(),
				state.agentResetGeneration(), state.fence(), state.inputRoute(), state.packagerLoaded(),
				state.sourceInventorySnapshotGeneration(), state.durablePhase(),
				bindDurableCommandToRequest ? ignored : state.durableCommand());
		}
		@Override public CreateLogisticsGateway.BareCommitContext capture(
			CreateLogisticsGateway.BareDispatchRequest ignored) {
			bareCaptures++;
			return bareState;
		}
		@Override public boolean isServerThread() { return serverThread; }
		@Override public CreateLogisticsGateway.NativeDispatchFact dispatch(
			CreateLogisticsGateway.DispatchRequest ignored) {
			dispatches++;
			return new CreateLogisticsGateway.NativeDispatchFact(dispatchAccepted,
				dispatchAccepted ? nextOrders : List.of());
		}
	}

	private static final class FakeCommitStore implements CreateLogisticsGateway.CommitStore {
		private final Map<UUID, CreateLogisticsGateway.PersistedCommit> commits = new HashMap<>();
		private boolean failCompletedPersist;
		@Override public Optional<CreateLogisticsGateway.PersistedCommit> load(UUID intentId) {
			return Optional.ofNullable(commits.get(intentId));
		}
		@Override public void persist(CreateLogisticsGateway.PersistedCommit commit) {
			if (failCompletedPersist
				&& commit.state() == CreateLogisticsGateway.CommitAttemptState.COMPLETED)
				throw new IllegalStateException("simulated crash");
			commits.put(commit.command().intentId(), commit);
		}
	}

	private static final class FakeBareCommitStore
		implements CreateLogisticsGateway.BareCommitStore {
		private final Map<UUID, CreateLogisticsGateway.PersistedBareCommit> commits =
			new HashMap<>();
		@Override public Optional<CreateLogisticsGateway.PersistedBareCommit> load(UUID intentId) {
			return Optional.ofNullable(commits.get(intentId));
		}
		@Override public void persist(CreateLogisticsGateway.PersistedBareCommit commit) {
			commits.put(commit.request().intentId(), commit);
		}
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
