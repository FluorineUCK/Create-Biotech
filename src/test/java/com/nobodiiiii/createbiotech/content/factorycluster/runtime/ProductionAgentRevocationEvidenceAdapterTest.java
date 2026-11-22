package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentRoleTransitionPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionAgentRevocationEvidenceAdapterTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), AGENT = id(3);
	private static final UUID RUN = id(4), REQUEST = id(5), GOAL = id(6), PATTERN = id(7);
	private static final UUID NETWORK = id(8), COMPUTER = id(9), PANEL = id(10);
	private static final CreateRouteKey INPUT = new CreateRouteKey(NETWORK, "input");
	private static final CreateRouteKey OUTPUT = new CreateRouteKey(NETWORK, "output");
	private static final CreateRouteKey WAREHOUSE = new CreateRouteKey(NETWORK, "warehouse");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
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
	void commandReadsUnloadedResetAndNativeContractEvidenceDirectlyFromProductionJournal() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		journal.write("agent/" + AGENT, idleCoordinator(), RegistryAccess.EMPTY);
		AtomicReference<RecoveryLedger.State> recovery = new AtomicReference<>(emptyRecovery());
		ProductionAgentRevocationEvidenceAdapter evidence =
			new ProductionAgentRevocationEvidenceAdapter(journal, RegistryAccess.EMPTY,
				recovery::get, List::of, List::of, List::of);
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(
			directory, ignored -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			new ProductionAgentRoleTransitionPolicy(ignored -> true, evidence::query));
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER), PANEL);

		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
		assertEquals(AgentRevocationDrainState.Disposition.MISSING_EVIDENCE,
			evidence.query(CLUSTER).disposition(), "a missing durable Reset record fails closed");

		journal.write("reset/" + AGENT, new AgentResetService.State(AGENT, CLUSTER, 4,
			Optional.of(key()), true, List.of(), List.of(), 0), RegistryAccess.EMPTY);
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
		assertEquals(AgentRevocationDrainState.Disposition.IRREVERSIBLE,
			evidence.query(CLUSTER).disposition());

		journal.write("reset/" + AGENT, idleReset(1), RegistryAccess.EMPTY);
		journal.write("input_contract/" + RUN, inputState(AgentInputPhase.DISPATCH_INTENT),
			RegistryAccess.EMPTY);
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
		assertEquals(AgentRevocationDrainState.Disposition.UNCERTAIN,
			evidence.query(CLUSTER).disposition(),
			"an unloaded native contract present only in the journal must still reject");

		journal.write("input_contract/" + RUN, inputState(AgentInputPhase.INPUT_ENDED),
			RegistryAccess.EMPTY);
		recovery.set(tombstoneRecovery());
		AgentRevocationDrainState drained = evidence.query(CLUSTER);
		assertEquals(AgentRevocationDrainState.Disposition.DRAINED, drained.disposition());
		assertTrue(drained.findings().stream().anyMatch(finding ->
			finding.source() == AgentRevocationDrainState.Source.RESET
				&& finding.disposition() == AgentRevocationDrainState.Disposition.DRAINED));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
	}

	@Test
	void commandReadsUnloadedRemoteDispatchAndOutboxEvidenceDirectlyFromProductionJournal() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		journal.write("agent/" + AGENT, idleCoordinator(), RegistryAccess.EMPTY);
		journal.write("reset/" + AGENT, idleReset(0), RegistryAccess.EMPTY);
		AtomicReference<RecoveryLedger.State> recovery = new AtomicReference<>(tombstoneRecovery());
		ProductionAgentRevocationEvidenceAdapter evidence =
			new ProductionAgentRevocationEvidenceAdapter(journal, RegistryAccess.EMPTY,
				recovery::get, List::of, List::of, List::of);
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(
			new ClusterCommunicationDirectory(),
			ignored -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			new ProductionAgentRoleTransitionPolicy(ignored -> true, evidence::query));
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER), PANEL);
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));

		FactoryOrchestrationServer.RemoteAgentRadioDispatch dispatch = remoteDispatch(true);
		journal.write(FactoryOrchestrationServer.REMOTE_AGENT_DISPATCH_PREFIX
			+ dispatch.messageId(), dispatch, RegistryAccess.EMPTY);
		journal.write(FactoryOrchestrationServer.RADIO_OUTBOX_KEY,
			new DurableRadioOutbox.State(Map.of(dispatch.messageId(),
				new DurableRadioOutbox.Entry(dispatch.envelope(), dispatch.relays(),
					RadioRouter.RouteStatus.WAITING_ROUTE))), RegistryAccess.EMPTY);
		assertEquals(AgentRevocationDrainState.Disposition.MISSING_EVIDENCE,
			evidence.query(CLUSTER).disposition());
		assertEquals(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));

		journal.write(FactoryOrchestrationServer.RADIO_OUTBOX_KEY,
			new DurableRadioOutbox.State(Map.of(dispatch.messageId(),
				new DurableRadioOutbox.Entry(dispatch.envelope(), dispatch.relays(),
					RadioRouter.RouteStatus.DELIVERED))), RegistryAccess.EMPTY);
		assertEquals(AgentRevocationDrainState.Disposition.DRAINED,
			evidence.query(CLUSTER).disposition());
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
	}

	private static AgentCoordinator.PersistedState idleCoordinator() {
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT, INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT, OUTPUT);
		return new AgentCoordinator.PersistedState(AGENT, CLUSTER, 4, input.endpointAddress(),
			List.of(input, output), Optional.empty(), Optional.empty(), false, 4, List.of(), 8,
			Map.of());
	}

	private static AgentResetService.State idleReset(long revision) {
		return new AgentResetService.State(AGENT, CLUSTER, 4, Optional.empty(), false,
			List.of(), List.of(), revision);
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
		return new AgentInputContract.State(AGENT, 4, id(20), run,
			AgentInputContract.ExecutionShape.STANDARD, List.of(), 20, phase, 0,
			OptionalLong.empty(), Optional.empty(), Optional.empty(), 0, false, 0, false,
			false, 0);
	}

	private static RecoveryLedger.State tombstoneRecovery() {
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(REQUEST, RUN);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key(), id(30), AGENT, INPUT, true);
		RecoveryLedger.AcknowledgedTombstone tombstone =
			new RecoveryLedger.AcknowledgedTombstone(key(),
				RemoteAgentRecord.TerminalOutcome.COMPLETED, 1);
		return new RecoveryLedger.State(Map.of(), Map.of(key(), tombstone), Map.of(),
			Map.of(lineageId, lineage));
	}

	private static RecoveryLedger.State emptyRecovery() {
		return new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of());
	}

	private static FactoryOrchestrationServer.RemoteAgentRadioDispatch remoteDispatch(
		boolean committed) {
		UUID message = id(40), sourceCluster = id(30);
		ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(REQUEST, sourceCluster, 8,
			new ProductionEffect.TargetFence(id(41), id(42), 0, 0), RUN, INPUT,
			List.of(new OrderedIngredient(
				new StackKey(new ItemStack(Items.IRON_INGOT)), 1)));
		ProcessProtocolMessage.Data payload = new ProcessProtocolMessage.Data(
			"agent-dispatch:" + REQUEST);
		RadioEnvelope envelope = new RadioEnvelope(message, sourceCluster, id(43), CLUSTER,
			id(44), CommunicationFrequency.of("7"), 0, 3, 4, payload.fingerprint(), payload);
		return new FactoryOrchestrationServer.RemoteAgentRadioDispatch(message, intent, AGENT, 4,
			CommunicationFrequency.of("7"), envelope, List.of(), committed);
	}

	private static RemoteRequestKey key() {
		return new RemoteRequestKey(REQUEST, RUN, 8, 3, 4);
	}

	@SuppressWarnings("unused")
	private static ProductionRequestTicket ticket() {
		return new ProductionRequestTicket(REQUEST, RUN, 8, id(30), AGENT, INPUT, PATTERN,
			InputMode.EXACT, 1, 0, 0, 0);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
