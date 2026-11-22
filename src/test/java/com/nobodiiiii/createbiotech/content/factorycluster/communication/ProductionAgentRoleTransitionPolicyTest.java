package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionAgentRoleTransitionPolicyTest {
	private static final UUID CLUSTER = id(1);
	private static final UUID AGENT = id(2);
	private static final UUID RUN = id(3);
	private static final UUID REQUEST = id(4);
	private static final UUID GOAL = id(5);
	private static final UUID PATTERN = id(6);
	private static final UUID NETWORK = id(7);
	private static final UUID SOURCE_CLUSTER = id(50);
	private static final UUID MESSAGE = id(51);
	private static final CreateRouteKey INPUT = new CreateRouteKey(NETWORK, "input");
	private static final CreateRouteKey OUTPUT = new CreateRouteKey(NETWORK, "output");
	private static final CreateRouteKey WAREHOUSE = new CreateRouteKey(NETWORK, "warehouse");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class,
					java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(),
					java.util.Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void realCommandValidatorRejectsEveryUnsafeAuditAndAllowsOnlyExplicitDrainProof() {
		AtomicReference<AgentRevocationDrainState> state = new AtomicReference<>(
			AgentRevocationDrainState.assess(CLUSTER, sources(List.of())));
		ProductionAgentRoleTransitionPolicy policy = new ProductionAgentRoleTransitionPolicy(
			clusterId -> true, clusterId -> state.get());
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(
			directory, authority -> ClusterCommunicationCommandService.AuthorityStatus.READY, policy);
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, id(20),
			new com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority(
				com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType.COMPUTER_COORDINATOR,
				id(21)), id(22));

		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
		assertEquals(AgentRevocationDrainState.Disposition.MISSING_EVIDENCE,
			state.get().disposition());

		state.set(AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), recovery(queuedRecord()), List.of(ticket()),
				List.of(), List.of(), List.of())));
		assertCommandRejects(service, authority, state.get(),
			AgentRevocationDrainState.Disposition.NON_DRAINED);

		state.set(AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), emptyRecovery(), List.of(),
				List.of(inputState(AgentInputPhase.DISPATCH_INTENT)), List.of(), List.of())));
		assertCommandRejects(service, authority, state.get(),
			AgentRevocationDrainState.Disposition.UNCERTAIN);

		state.set(AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), recovery(faultRecord()), List.of(), List.of(),
				List.of(), List.of())));
		assertCommandRejects(service, authority, state.get(),
			AgentRevocationDrainState.Disposition.IRREVERSIBLE);

		state.set(AgentRevocationDrainState.assess(CLUSTER, sources(List.of(idleCoordinator()))));
		assertTrue(state.get().safeToRevoke());
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
	}

	@Test
	void pendingRecoveryEffectAndLoadedActiveContractCannotBeMistakenForDrain() {
		RemoteAgentRecord terminal = terminalRecord();
		RecoveryLedger.State effectPending = recovery(terminal,
			Map.of(key(), new RecoveryLedger.PendingEffect(key(),
				RecoveryLedger.EffectKind.RELEASE_PROCESS, Optional.empty())));
		AgentRevocationDrainState pending = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(activeCoordinator()), effectPending, List.of(), List.of(), List.of(),
				List.of()));
		assertEquals(AgentRevocationDrainState.Disposition.NON_DRAINED, pending.disposition());

		AgentRevocationDrainState loaded = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(activeCoordinator()), List.of(activeCoordinator()), recovery(terminal),
				List.of(), List.of(), List.of(), List.of(), new DurableRadioOutbox.State(Map.of())));
		assertEquals(AgentRevocationDrainState.Disposition.NON_DRAINED, loaded.disposition());
		assertFalse(loaded.safeToRevoke());
	}

	@Test
	void terminalRecoveryReleasesAnUnloadedStaleCoordinatorAndRetainedNativeEvidence() {
		RemoteAgentRecord terminal = terminalRecord();
		AgentRevocationDrainState state = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(activeCoordinator()), recovery(terminal), List.of(), List.of(),
				List.of(), List.of()));

		assertEquals(AgentRevocationDrainState.Disposition.DRAINED, state.disposition());
		assertTrue(state.safeToRevoke());
		assertTrue(state.findings().stream().anyMatch(finding ->
			finding.source() == AgentRevocationDrainState.Source.RECOVERY
				&& finding.disposition() == AgentRevocationDrainState.Disposition.DRAINED));

		AgentRevocationDrainState retainedNative = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), tombstoneRecovery(
				RemoteAgentRecord.TerminalOutcome.COMPLETED), List.of(),
				List.of(inputState(AgentInputPhase.INPUT_ENDED)), List.of(), List.of()));
		assertEquals(AgentRevocationDrainState.Disposition.DRAINED,
			retainedNative.disposition());
	}

	@Test
	void remoteDispatchAndOutboxNeedExactTerminalCorrelationButDoNotBlockForever() {
		AgentRevocationDrainState.RadioDispatchEvidence committed = dispatch(true, CLUSTER, AGENT);
		DurableRadioOutbox.State delivered = new DurableRadioOutbox.State(Map.of(MESSAGE,
			new DurableRadioOutbox.Entry(committed.envelope(), committed.relays(),
				RadioRouter.RouteStatus.DELIVERED)));
		AgentRevocationDrainState drained = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), List.of(), tombstoneRecovery(
				RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT), List.of(), List.of(),
				List.of(), List.of(committed), delivered));
		assertEquals(AgentRevocationDrainState.Disposition.DRAINED, drained.disposition());

		AgentRevocationDrainState missing = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), List.of(), emptyRecovery(), List.of(), List.of(),
				List.of(), List.of(committed), delivered));
		assertEquals(AgentRevocationDrainState.Disposition.MISSING_EVIDENCE,
			missing.disposition());

		AgentRevocationDrainState pending = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), List.of(), emptyRecovery(), List.of(), List.of(),
				List.of(), List.of(dispatch(false, CLUSTER, AGENT)), delivered));
		assertEquals(AgentRevocationDrainState.Disposition.NON_DRAINED,
			pending.disposition());
	}

	@Test
	void outboundUseOfAnotherClustersAgentIsNotOwnedByTheRevokedCluster() {
		AgentRevocationDrainState.RadioDispatchEvidence outbound = dispatch(false, CLUSTER,
			id(70), id(71));
		AgentRevocationDrainState state = AgentRevocationDrainState.assess(CLUSTER,
			sources(List.of(idleCoordinator()), emptyRecovery(), List.of(), List.of(), List.of(),
				List.of(outbound)));

		assertEquals(AgentRevocationDrainState.Disposition.DRAINED, state.disposition());
	}

	private static AgentRevocationDrainState.Sources sources(
		List<AgentCoordinator.PersistedState> coordinators) {
		return sources(coordinators, emptyRecovery(), List.of(), List.of(), List.of(), List.of());
	}

	private static AgentRevocationDrainState.Sources sources(
		List<AgentCoordinator.PersistedState> coordinators, RecoveryLedger.State recovery,
		List<ProductionRequestTicket> pendingRuns, List<AgentInputContract.State> inputs,
		List<com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Snapshot> outputs,
		List<AgentRevocationDrainState.RadioDispatchEvidence> dispatches) {
		return new AgentRevocationDrainState.Sources(coordinators, idleResets(coordinators),
			recovery, pendingRuns, inputs, outputs, dispatches,
			new DurableRadioOutbox.State(Map.of()));
	}

	private static AgentRevocationDrainState.Sources sources(
		List<AgentCoordinator.PersistedState> durable,
		List<AgentCoordinator.PersistedState> live, RecoveryLedger.State recovery,
		List<ProductionRequestTicket> pendingRuns, List<AgentInputContract.State> inputs,
		List<com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Snapshot> outputs,
		List<AgentRevocationDrainState.RadioDispatchEvidence> dispatches,
		DurableRadioOutbox.State outbox) {
		return new AgentRevocationDrainState.Sources(durable, live, idleResets(durable),
			idleResets(live), recovery, pendingRuns, inputs, outputs, dispatches, outbox);
	}

	private static List<AgentResetService.State> idleResets(
		List<AgentCoordinator.PersistedState> coordinators) {
		return coordinators.stream().map(coordinator -> new AgentResetService.State(
			coordinator.agentId(), coordinator.ownerClusterId(), coordinator.agentGeneration(),
			Optional.empty(), false, List.of(), List.of(), 0)).toList();
	}

	private static AgentCoordinator.PersistedState idleCoordinator() {
		return coordinator(Optional.empty());
	}

	private static AgentCoordinator.PersistedState activeCoordinator() {
		return coordinator(Optional.of(new AgentProcess.ActiveContract(REQUEST, 8)));
	}

	private static AgentCoordinator.PersistedState coordinator(
		Optional<AgentProcess.ActiveContract> active) {
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT, INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT, OUTPUT);
		return new AgentCoordinator.PersistedState(AGENT, CLUSTER, 4, input.endpointAddress(),
			List.of(input, output), Optional.empty(), active, false, 4, List.of(), 8,
			Map.of());
	}

	private static AgentEndpointState endpoint(int x, AgentEndpointRole role, CreateRouteKey route) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 0, 0)), AGENT, CLUSTER, 4, role, route, 0,
			CommunicationFrequency.of("7"), AgentCompletionMode.TARGET, Optional.empty(),
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, 0, WAREHOUSE, true, true);
	}

	private static AgentInputContract.State inputState(AgentInputPhase phase) {
		ProductionRun run = new ProductionRun(RUN, GOAL, PATTERN, 8, 1, InputMode.EXACT,
			List.of(INPUT), WAREHOUSE, 0, Map.of());
		return new AgentInputContract.State(AGENT, 4, id(40), run,
			AgentInputContract.ExecutionShape.STANDARD, List.of(), 20, phase, 0,
			OptionalLong.empty(), Optional.empty(), Optional.empty(), 0, false, 0, false,
			false, 0);
	}

	private static RemoteAgentRecord queuedRecord() {
		return new RemoteAgentRecord(key(), CLUSTER, ticket(), RemoteAgentPhase.QUEUED,
			RemoteAgentRecord.RecoveryCondition.NORMAL, false, false, Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.empty(), 0);
	}

	private static RemoteAgentRecord terminalRecord() {
		return new RemoteAgentRecord(key(), CLUSTER, ticket(), RemoteAgentPhase.RELEASED,
			RemoteAgentRecord.RecoveryCondition.NORMAL, true, true, Optional.empty(),
			Optional.empty(), Optional.empty(),
			Optional.of(RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT), 1);
	}

	private static RemoteAgentRecord faultRecord() {
		return new RemoteAgentRecord(key(), CLUSTER, ticket(), RemoteAgentPhase.RESET_REQUIRED,
			RemoteAgentRecord.RecoveryCondition.FAULT, false, true, Optional.empty(),
			Optional.empty(), Optional.empty(),
			Optional.of(RemoteAgentRecord.TerminalOutcome.FAULT_RESET_REQUIRED), 1);
	}

	private static RecoveryLedger.State recovery(RemoteAgentRecord record) {
		return recovery(record, Map.of());
	}

	private static RecoveryLedger.State recovery(RemoteAgentRecord record,
		Map<RemoteRequestKey, RecoveryLedger.PendingEffect> effects) {
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(REQUEST, RUN);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key(), id(50), AGENT, INPUT, record.isTerminal());
		return new RecoveryLedger.State(Map.of(key(), record), Map.of(), effects,
			Map.of(lineageId, lineage));
	}

	private static RecoveryLedger.State tombstoneRecovery(
		RemoteAgentRecord.TerminalOutcome outcome) {
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(REQUEST, RUN);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key(), SOURCE_CLUSTER, AGENT, INPUT, true);
		RecoveryLedger.AcknowledgedTombstone tombstone =
			new RecoveryLedger.AcknowledgedTombstone(key(), outcome, 2);
		return new RecoveryLedger.State(Map.of(), Map.of(key(), tombstone), Map.of(),
			Map.of(lineageId, lineage));
	}

	private static RecoveryLedger.State emptyRecovery() {
		return new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of());
	}

	private static ProductionRequestTicket ticket() {
		return new ProductionRequestTicket(REQUEST, RUN, 8, SOURCE_CLUSTER, AGENT, INPUT, PATTERN,
			InputMode.EXACT, 1, 0, 0, 0);
	}

	private static RemoteRequestKey key() {
		return new RemoteRequestKey(REQUEST, RUN, 8, 3, 4);
	}

	private static AgentRevocationDrainState.RadioDispatchEvidence dispatch(boolean committed,
		UUID targetCluster, UUID agentId) {
		return dispatch(committed, SOURCE_CLUSTER, targetCluster, agentId);
	}

	private static AgentRevocationDrainState.RadioDispatchEvidence dispatch(boolean committed,
		UUID sourceCluster, UUID targetCluster, UUID agentId) {
		ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(REQUEST, sourceCluster, 8,
			new ProductionEffect.TargetFence(id(60), id(61), 0, 0), RUN, INPUT,
			List.of(new OrderedIngredient(
				new StackKey(new ItemStack(Items.IRON_INGOT)), 1)));
		ProcessProtocolMessage.Data payload = new ProcessProtocolMessage.Data(
			"agent-dispatch:" + REQUEST);
		RadioEnvelope envelope = new RadioEnvelope(MESSAGE, sourceCluster, id(62), targetCluster,
			id(63), CommunicationFrequency.of("7"), 0, 3, 4, payload.fingerprint(), payload);
		return new AgentRevocationDrainState.RadioDispatchEvidence(MESSAGE, intent, agentId, 4,
			envelope, List.of(), committed);
	}

	private static void assertCommandRejects(ClusterCommunicationCommandService service,
		ClusterCommunicationCommandService.CommandAuthority authority,
		AgentRevocationDrainState state,
		AgentRevocationDrainState.Disposition expected) {
		assertEquals(expected, state.disposition());
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
