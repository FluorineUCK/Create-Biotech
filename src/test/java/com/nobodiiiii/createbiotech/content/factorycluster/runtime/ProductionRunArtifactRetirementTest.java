package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class ProductionRunArtifactRetirementTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void durableConfirmedCreditDoesNotIncludeASealedButUnreceivedLaterExactRun() {
		Fixture fixture = fixture();
		GoalObservationState afterFirstReceipt =
			new GoalObservationState(fixture.received().goalId(),
				fixture.beforeAdvance().clusterId(), fixture.beforeAdvance().observationScope(),
				0, 6, 2, 2, Optional.of(fixture.received().runId()));

		assertEquals(2, afterFirstReceipt.confirmedOutputCount(),
			"dispatch progress for the next Run must not masquerade as a receipt");
		GoalObservationState afterSecondReceipt =
			afterFirstReceipt.withNewlyConfirmedOutput(fixture.next().runId(), 2);
		assertEquals(4, afterSecondReceipt.confirmedOutputCount());
		assertEquals(4, afterSecondReceipt.creditedCount());
		assertEquals(fixture.next().runId(), afterSecondReceipt.lastReceivedRunId().orElseThrow());
		assertEquals(afterSecondReceipt, afterSecondReceipt.withNewlyConfirmedOutput(
			fixture.next().runId(), 0),
			"NO_OUTPUT retirement consumes its artifacts without inventing Goal credit");
	}

	@Test
	void receiptCreditUsesActualPrimaryAndIsBoundedByTheFrozenReceiverTarget() {
		Fixture fixture = fixture();
		RecoveryLedger.State completed = acknowledgedCompletion(fixture);
		GoalObservationState observation = observation(fixture, 0);

		assertEquals(1, ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), receivedOutput(fixture, 1), outputPolicy(fixture, 2),
			fixture.afterAdvance(), observation).orElseThrow());
		assertEquals(2, ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), receivedOutput(fixture, 4), outputPolicy(fixture, 2),
			fixture.afterAdvance(), observation).orElseThrow(),
			"an ACK proves only the frozen receiver target, not every overproduced item");
		assertEquals(0, ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), receivedOutput(fixture, 0), noOutputPolicy(fixture),
			fixture.afterAdvance(), observation).orElseThrow());
	}

	@Test
	void goalCreditAndActiveArtifactRemovalShareOneJournalMutation() {
		Fixture fixture = fixture();
		FactoryOrchestrationSavedData journal = journalWithRunArtifacts(fixture.received().runId());
		GoalObservationState credited =
			new GoalObservationState(fixture.received().goalId(),
				fixture.beforeAdvance().clusterId(), fixture.beforeAdvance().observationScope(),
				0, 6, 2, 2, Optional.of(fixture.received().runId()));
		String goalKey = "goal_observation/" + fixture.received().goalId();

		journal.writeBatchAndRemoveActiveRunArtifacts(Map.of(goalKey, credited),
			fixture.received().runId(), RegistryAccess.EMPTY);

		assertEquals(credited, journal.read(goalKey,
			GoalObservationState.class, RegistryAccess.EMPTY)
			.orElseThrow());
		for (String key : FactoryOrchestrationSavedData.activeRunArtifactKeys(
			fixture.received().runId()))
			assertTrue(journal.read(key, ProductionAdmissionLedger.State.class,
				RegistryAccess.EMPTY).isEmpty(), key);
		assertTrue(journal.read("input_commit/irreversible",
			ProductionAdmissionLedger.State.class, RegistryAccess.EMPTY).isPresent());
		assertTrue(journal.read("output_commit/irreversible",
			ProductionAdmissionLedger.State.class, RegistryAccess.EMPTY).isPresent());
		assertTrue(journal.read("output_native_receipt/irreversible",
			ProductionAdmissionLedger.State.class, RegistryAccess.EMPTY).isPresent());
		assertTrue(journal.read("input_contract/" + id(999),
			ProductionAdmissionLedger.State.class, RegistryAccess.EMPTY).isPresent());
	}

	@Test
	void receivedOutputRetiresOnlyAfterTerminalEvidenceAndPlanAdvance() {
		Fixture fixture = fixture();
		RecoveryLedger.State completed = acknowledgedCompletion(fixture);
		GoalObservationState uncredited = observation(fixture, 0);
		ProductionOutputPolicy policy = outputPolicy(fixture, 2);

		assertTrue(ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), outputSnapshot(fixture, 2,
				ProductionOutputContract.Milestone.OUTPUT_DISPATCHED), policy,
			fixture.afterAdvance(), uncredited).isEmpty());
		assertTrue(ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), receivedOutput(fixture, 2), policy,
			fixture.beforeAdvance(), uncredited).isEmpty());
		assertEquals(2, ProductionGoalSettlement.activeRunRetirementCredit(completed,
			fixture.key(), receivedOutput(fixture, 2), policy,
			fixture.afterAdvance(), uncredited).orElseThrow());
	}

	@Test
	void cancelDrainRetiresTheExactReceivedRunWithoutGoalCreditOrPlanAdvance() {
		Fixture fixture = fixture();
		FrozenProductionPlan cancelled = fixture.beforeAdvance().cancelAfterDrainNow();
		RecoveryLedger.State drained = acknowledgedTerminal(fixture,
			RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED);

		assertTrue(ProductionGoalSettlement.cancelledRunRetirementReady(drained,
			fixture.key(), receivedOutput(fixture, 2), outputPolicy(fixture, 2), cancelled));
		assertFalse(ProductionGoalSettlement.cancelledRunRetirementReady(
			acknowledgedCompletion(fixture), fixture.key(), receivedOutput(fixture, 2),
			outputPolicy(fixture, 2), cancelled),
			"ordinary completion evidence cannot authorize a no-credit cancellation retirement");
		assertFalse(ProductionGoalSettlement.cancelledRunRetirementReady(drained,
			fixture.key(), outputSnapshot(fixture, 2,
				ProductionOutputContract.Milestone.OUTPUT_DISPATCHED), outputPolicy(fixture, 2),
			cancelled));
		assertFalse(ProductionGoalSettlement.cancelledRunRetirementReady(drained,
			fixture.key(), receivedOutput(fixture, 2), outputPolicy(fixture, 2),
			fixture.beforeAdvance()));
	}

	@Test
	void noCreditCancellationRetirementAtomicallyRemovesRunArtifactsAndKeepsObservation() {
		Fixture fixture = fixture();
		FactoryOrchestrationSavedData journal = journalWithRunArtifacts(fixture.received().runId());
		GoalObservationState observation = observation(fixture, 0);
		String goalKey = "goal_observation/" + fixture.received().goalId();
		journal.write(goalKey, observation, RegistryAccess.EMPTY);

		journal.writeBatchAndRemoveActiveRunArtifacts(Map.of(), fixture.received().runId(),
			RegistryAccess.EMPTY);

		assertEquals(observation, journal.read(goalKey,
			GoalObservationState.class, RegistryAccess.EMPTY)
			.orElseThrow(), "cancel retirement must not manufacture Goal credit");
		for (String key : FactoryOrchestrationSavedData.activeRunArtifactKeys(
			fixture.received().runId()))
			assertTrue(journal.read(key, ProductionAdmissionLedger.State.class,
				RegistryAccess.EMPTY).isEmpty(), key);
	}

	@Test
	void reloadNormalizesPersistedCancelDrainEvidenceIntoTheNoCreditPlanFence() {
		Fixture fixture = fixture();
		FrozenProductionPlan normalized = FactoryOrchestrationServer
			.normalizeCancellationPlanFence(fixture.beforeAdvance(),
				Set.of(fixture.received().runId()));

		assertTrue(normalized.cancelAfterDrain());
		assertEquals(fixture.received(), normalized.runs().currentRun());
		assertEquals(normalized, FactoryOrchestrationServer.normalizeCancellationPlanFence(
			normalized, Set.of(fixture.received().runId())),
			"reload reconciliation must be idempotent");
		assertEquals(fixture.beforeAdvance(), FactoryOrchestrationServer
			.normalizeCancellationPlanFence(fixture.beforeAdvance(), Set.of(id(999))));
	}

	@Test
	void encodeFailureLeavesPriorCreditAndEveryActiveArtifactUntouched() {
		Fixture fixture = fixture();
		FactoryOrchestrationSavedData journal = journalWithRunArtifacts(fixture.received().runId());
		GoalObservationState prior = observation(fixture, 0);
		String goalKey = "goal_observation/" + fixture.received().goalId();
		journal.write(goalKey, prior, RegistryAccess.EMPTY);
		LinkedHashMap<String, Object> invalidBatch = new LinkedHashMap<>();
		invalidBatch.put(goalKey, prior.withNewlyConfirmedOutput(fixture.received().runId(), 2));
		invalidBatch.put("invalid", new Object());

		assertThrows(RuntimeException.class, () -> journal.writeBatchAndRemoveActiveRunArtifacts(
			invalidBatch, fixture.received().runId(), RegistryAccess.EMPTY));
		assertEquals(prior, journal.read(goalKey,
			GoalObservationState.class, RegistryAccess.EMPTY)
			.orElseThrow());
		for (String key : FactoryOrchestrationSavedData.activeRunArtifactKeys(
			fixture.received().runId()))
			assertTrue(journal.read(key, ProductionAdmissionLedger.State.class,
				RegistryAccess.EMPTY).isPresent(), key);
	}

	@Test
	void panelArtifactSelectionUsesOnlyThePlansExactCurrentRun() {
		Fixture fixture = fixture();
		Map<UUID, String> artifacts = Map.of(fixture.received().runId(), "stale",
			fixture.next().runId(), "current");

		assertEquals("current", FactoryOrchestrationServer.currentRunArtifact(
			fixture.afterAdvance(), artifacts));
		assertNull(FactoryOrchestrationServer.currentRunArtifact(
			fixture.completed(), Map.of(fixture.received().runId(), "stale")));
	}

	@Test
	void productionStatusPrefersLiveCurrentRunThenLatestBoundedTerminalEvidence() {
		Fixture fixture = fixture();
		RemoteRequestKey older = fixture.key();
		RemoteRequestKey newer = new RemoteRequestKey(id(401), id(402), 7, 9, 11);

		assertEquals(fixture.next().runId(), FactoryOrchestrationServer
			.selectProductionStatusRunId(fixture.next().runId(),
				Set.of(fixture.next().runId()), List.of(older, newer)).orElseThrow());
		assertEquals(fixture.next().runId(), FactoryOrchestrationServer
			.selectProductionStatusRunId(fixture.next().runId(), Set.of(), List.of(older, newer))
			.orElseThrow());
		assertEquals(newer.runId(), FactoryOrchestrationServer.selectProductionStatusRunId(
			null, Set.of(), List.of(older, newer)).orElseThrow());
	}

	private static FactoryOrchestrationSavedData journalWithRunArtifacts(UUID runId) {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		ProductionAdmissionLedger.State value = new ProductionAdmissionLedger.State(Map.of(),
			Map.of(), 0, Optional.empty());
		for (String key : FactoryOrchestrationSavedData.activeRunArtifactKeys(runId))
			journal.write(key, value, RegistryAccess.EMPTY);
		journal.write("input_commit/irreversible", value, RegistryAccess.EMPTY);
		journal.write("output_commit/irreversible", value, RegistryAccess.EMPTY);
		journal.write("output_native_receipt/irreversible", value, RegistryAccess.EMPTY);
		journal.write("input_contract/" + id(999), value, RegistryAccess.EMPTY);
		return journal;
	}

	private static RecoveryLedger.State acknowledgedCompletion(Fixture fixture) {
		return acknowledgedTerminal(fixture, RemoteAgentRecord.TerminalOutcome.COMPLETED);
	}

	private static RecoveryLedger.State acknowledgedTerminal(Fixture fixture,
		RemoteAgentRecord.TerminalOutcome outcome) {
		RemoteRequestKey key = fixture.key();
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(key.requestId(),
			key.runId());
		RecoveryLedger.AcknowledgedTombstone tombstone = new RecoveryLedger.AcknowledgedTombstone(
			key, outcome, 4);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key, fixture.beforeAdvance().clusterId(), agentId(),
			fixture.received().inputRoutes().getFirst(), true);
		return new RecoveryLedger.State(Map.of(), Map.of(key, tombstone), Map.of(),
			Map.of(lineageId, lineage));
	}

	private static GoalObservationState observation(Fixture fixture,
		long credited) {
		return new GoalObservationState(fixture.received().goalId(),
			fixture.beforeAdvance().clusterId(), fixture.beforeAdvance().observationScope(), 0, 6,
			credited, credited, credited == 0 ? Optional.empty()
				: Optional.of(fixture.received().runId()));
	}

	private static ProductionOutputPolicy outputPolicy(Fixture fixture, long target) {
		return new ProductionOutputPolicy(0, AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			AgentCompletionMode.TARGET, Optional.empty(), fixture.beforeAdvance().deliveryRoute(),
			Set.of(fixture.beforeAdvance().deliveryRoute()), Map.of(fixture.beforeAdvance().output(),
				new ProductionOutputPolicy.RequiredOutput(fixture.beforeAdvance().deliveryRoute(), target,
					new ProductionOutputPolicy.OutputReceiver.Bare(
						new ProductionOutputPolicy.BareReceiptProof.Network(
							(StockObservationScope.NetworkSummary) fixture.beforeAdvance()
								.observationScope())))), Set.of(),
			Optional.of(fixture.beforeAdvance().output()), false, false, 1, 1, 1);
	}

	private static ProductionOutputPolicy noOutputPolicy(Fixture fixture) {
		return new ProductionOutputPolicy(0, AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			AgentCompletionMode.QUIET, Optional.empty(), fixture.beforeAdvance().deliveryRoute(),
			Set.of(fixture.beforeAdvance().deliveryRoute()), Map.of(), Set.of(), Optional.empty(),
			true, false, 1, 1, 1);
	}

	private static ProductionOutputContract.Snapshot receivedOutput(Fixture fixture, long primary) {
		return outputSnapshot(fixture, primary, ProductionOutputContract.Milestone.OUTPUT_RECEIVED);
	}

	private static ProductionOutputContract.Snapshot outputSnapshot(Fixture fixture, long primary,
		ProductionOutputContract.Milestone milestone) {
		Map<StackKey, Long> credited = primary == 0 ? Map.of()
			: Map.of(fixture.beforeAdvance().output(), primary);
		return new ProductionOutputContract.Snapshot(agentId(), fixture.key().agentGeneration(), id(500),
			fixture.received(), 0, milestone, Optional.empty(), true, false, primary > 0,
			0, 0, 0, credited, Map.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(),
			Set.of(), List.of(), Optional.empty(), 0, 0, Optional.empty(), Optional.empty(),
			Optional.empty(), true);
	}

	private static UUID agentId() {
		return id(302);
	}

	private static Fixture fixture() {
		UUID cluster = id(1), goal = id(2), pattern = id(3), network = id(4);
		CreateRouteKey input = new CreateRouteKey(network, "input");
		CreateRouteKey delivery = new CreateRouteKey(network, "warehouse");
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		ProductionRun first = new ProductionRun(id(10), goal, pattern, 5, 1, InputMode.EXACT,
			List.of(input), delivery, 0, Map.of(ingredient, 1L));
		ProductionRun next = new ProductionRun(id(11), goal, pattern, 6, 1, InputMode.EXACT,
			List.of(input), delivery, 0, Map.of(ingredient, 1L));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(
			new RouteSelectionScope(id(12), id(13), output, 1, 1, id(14)), id(15), pattern,
			pattern.toString(), RouteCandidate.ProviderKind.PATTERN, 0,
			RouteCandidate.Availability.AVAILABLE);
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 6);
		List<FrozenProductionPlan.FrozenStage> stages = List.of(
			new FrozenProductionPlan.FrozenStage(0, input, Map.of(ingredient, 1L),
				List.of(new OrderedIngredient(ingredient, 1L))));
		FrozenProductionPlan before = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			new ProductionRun.RunSequence(first, 3), scope, 0, 6, false, false,
			Optional.empty());
		FrozenProductionPlan after = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			new ProductionRun.RunSequence(next, 2), scope, 0, 6, false, false,
			Optional.empty());
		FrozenProductionPlan completed = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			ProductionRun.RunSequence.completed(), scope, 0, 6, false, false,
			Optional.empty());
		RemoteRequestKey key = new RemoteRequestKey(id(20), first.runId(), first.fence(), 8, 9);
		return new Fixture(first, next, key, before, after, completed);
	}

	private static Fixture singleRunFixture(InputMode mode, long multiplier) {
		Fixture exact = fixture();
		ProductionRun run = new ProductionRun(exact.received().runId(),
			exact.received().goalId(), exact.received().patternId(), exact.received().fence(),
			multiplier, mode, exact.received().inputRoutes(),
			exact.received().returnWarehouseRoute(), exact.received().outputPolicyGeneration(),
			exact.received().manifest());
		FrozenProductionPlan before = new FrozenProductionPlan(exact.beforeAdvance().goalId(),
			exact.beforeAdvance().clusterId(), exact.beforeAdvance().selectedProvider(),
			exact.beforeAdvance().patternKind(), exact.beforeAdvance().output(),
			exact.beforeAdvance().outputPerOperation(), exact.beforeAdvance().deliveryRoute(),
			exact.beforeAdvance().stages(), new ProductionRun.RunSequence(run, 1),
			exact.beforeAdvance().observationScope(), 0, 6, false, false, Optional.empty());
		return new Fixture(run, exact.next(), exact.key(), before, exact.afterAdvance(),
			exact.completed());
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private record Fixture(ProductionRun received, ProductionRun next, RemoteRequestKey key,
		FrozenProductionPlan beforeAdvance, FrozenProductionPlan afterAdvance,
		FrozenProductionPlan completed) {}
}
