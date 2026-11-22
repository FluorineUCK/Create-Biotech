package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class AgentEndpointConfigurationBatchTest {
	private static final UUID AGENT = id(1), OWNER = id(2), EPOCH = id(3), NETWORK = id(4);
	private static final SpaceAddress INPUT_A = address(1), INPUT_B = address(2), OUTPUT = address(3);

	@Test
	void policyEditPreservesMembershipGenerationAndOnlyWritesTheEditedEndpoint() {
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = formation(7);
		AgentEndpointConfigurationBatch.Plan plan = AgentEndpointConfigurationBatch.configure(current,
			request(INPUT_A, AgentEndpointRole.INPUT, "input-a", 99, "31",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL))
			.orElseThrow();

		assertFalse(plan.membershipChanged());
		assertEquals(Set.of(AGENT), plan.rebuildAgentIds());
		assertEquals(Set.of(INPUT_A), plan.updates().keySet());
		FactoryPanelAgentAccess.Configuration changed = plan.updates().get(INPUT_A).orElseThrow();
		assertEquals(7, changed.agentGeneration());
		assertEquals(99, changed.routePriority());
		assertEquals(CommunicationFrequency.of("31"), changed.frequency());
		assertEquals(current.get(INPUT_A).outputPolicyGeneration(), changed.outputPolicyGeneration());
	}

	@Test
	void addingASecondInputAdvancesEveryMemberOnceAndProducesOneCoherentFormation() {
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = new LinkedHashMap<>();
		current.put(INPUT_A, config(AGENT, 4, AgentEndpointRole.INPUT, "input-a", 10, 0));
		current.put(OUTPUT, config(AGENT, 4, AgentEndpointRole.OUTPUT, "output", 11, 0));

		AgentEndpointConfigurationBatch.Plan plan = AgentEndpointConfigurationBatch.configure(current,
			request(INPUT_B, AgentEndpointRole.INPUT, "input-b", 0, "7",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL))
			.orElseThrow();

		assertTrue(plan.membershipChanged());
		assertEquals(Set.of(INPUT_A, INPUT_B, OUTPUT), plan.updates().keySet());
		assertTrue(plan.updates().values().stream().flatMap(Optional::stream)
			.allMatch(configuration -> configuration.agentGeneration() == 5));
		plan.updates().forEach((address, changed) -> {
			FactoryPanelAgentAccess.Configuration before = current.get(address);
			if (before != null) assertTrue(changed.orElseThrow().configurationRevision()
				> before.configurationRevision(),
				"each endpoint keeps its own monotonic durable configuration revision");
		});
	}

	@Test
	void clearingOneOfSeveralInputsAdvancesAllRemainingMembersWithoutMixedGenerations() {
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = formation(12);

		AgentEndpointConfigurationBatch.Plan plan = AgentEndpointConfigurationBatch.clear(current,
			INPUT_B, OWNER, EPOCH, 100).orElseThrow();

		assertTrue(plan.membershipChanged());
		assertEquals(Optional.empty(), plan.updates().get(INPUT_B));
		assertEquals(13, plan.updates().get(INPUT_A).orElseThrow().agentGeneration());
		assertEquals(13, plan.updates().get(OUTPUT).orElseThrow().agentGeneration());
	}

	@Test
	void mixedExistingGenerationIsRejectedBeforeAnyEndpointCanBeWritten() {
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = formation(7);
		current = new LinkedHashMap<>(current);
		current.put(OUTPUT, config(AGENT, 8, AgentEndpointRole.OUTPUT, "output", 15, 0));

		assertTrue(AgentEndpointConfigurationBatch.configure(current,
			request(INPUT_A, AgentEndpointRole.INPUT, "input-a", 1, "7",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL)).isEmpty());
	}

	@Test
	void configureRejectsEndpointOwnedByAnotherCluster() {
		UUID foreignOwner = id(20), foreignEpoch = id(21);
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = new LinkedHashMap<>();
		current.put(INPUT_A, config(AGENT, foreignOwner, foreignEpoch, 0,
			AgentEndpointRole.INPUT, "input-a", 10, 0));

		assertTrue(AgentEndpointConfigurationBatch.configure(current,
			request(INPUT_A, AgentEndpointRole.INPUT, "input-a", 1, "7",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL)).isEmpty());
	}

	@Test
	void configureRejectsJoiningAnAgentOwnedByAnotherCluster() {
		UUID foreignOwner = id(22), foreignEpoch = id(23);
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> current = new LinkedHashMap<>();
		current.put(INPUT_A, config(AGENT, foreignOwner, foreignEpoch, 3,
			AgentEndpointRole.INPUT, "input-a", 10, 0));

		assertTrue(AgentEndpointConfigurationBatch.configure(current,
			request(INPUT_B, AgentEndpointRole.INPUT, "input-b", 1, "7",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL)).isEmpty());
	}

	@Test
	void mixedOwnerOrEpochForOneAgentIsRejected() {
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> mixedOwner = new LinkedHashMap<>();
		mixedOwner.put(INPUT_A, config(AGENT, OWNER, EPOCH, 7,
			AgentEndpointRole.INPUT, "input-a", 10, 0));
		mixedOwner.put(OUTPUT, config(AGENT, id(24), EPOCH, 7,
			AgentEndpointRole.OUTPUT, "output", 11, 0));
		assertTrue(AgentEndpointConfigurationBatch.configure(mixedOwner,
			request(INPUT_A, AgentEndpointRole.INPUT, "input-a", 1, "7",
				AgentCompletionMode.TARGET, AgentEndpointState.OutputMode.WAREHOUSE_ALL)).isEmpty());

		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> mixedEpoch = new LinkedHashMap<>();
		mixedEpoch.put(INPUT_A, config(AGENT, OWNER, EPOCH, 7,
			AgentEndpointRole.INPUT, "input-a", 10, 0));
		mixedEpoch.put(OUTPUT, config(AGENT, OWNER, id(25), 7,
			AgentEndpointRole.OUTPUT, "output", 11, 0));
		assertTrue(AgentEndpointConfigurationBatch.clear(mixedEpoch, INPUT_A, OWNER, EPOCH, 100)
			.isEmpty());
	}

	private static Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> formation(long generation) {
		LinkedHashMap<SpaceAddress, FactoryPanelAgentAccess.Configuration> result = new LinkedHashMap<>();
		result.put(INPUT_A, config(AGENT, generation, AgentEndpointRole.INPUT, "input-a", 10, 0));
		result.put(INPUT_B, config(AGENT, generation, AgentEndpointRole.INPUT, "input-b", 11, 0));
		result.put(OUTPUT, config(AGENT, generation, AgentEndpointRole.OUTPUT, "output", 12, 0));
		return result;
	}

	private static AgentEndpointConfigurationBatch.ConfigureRequest request(SpaceAddress endpoint,
		AgentEndpointRole role, String route, int priority, String frequency,
		AgentCompletionMode completion, AgentEndpointState.OutputMode outputMode) {
		return new AgentEndpointConfigurationBatch.ConfigureRequest(endpoint, AGENT, OWNER, EPOCH,
			100, role, route, priority, CommunicationFrequency.of(frequency), completion, Optional.empty(), outputMode,
			new CreateRouteKey(NETWORK, "warehouse"));
	}

	private static FactoryPanelAgentAccess.Configuration config(UUID agent, long generation,
		AgentEndpointRole role, String route, long revision, long outputGeneration) {
		return config(agent, OWNER, EPOCH, generation, role, route, revision, outputGeneration);
	}

	private static FactoryPanelAgentAccess.Configuration config(UUID agent, UUID owner, UUID epoch,
		long generation, AgentEndpointRole role, String route, long revision,
		long outputGeneration) {
		return new FactoryPanelAgentAccess.Configuration(agent, owner, epoch, revision, generation,
			role, route, 0, CommunicationFrequency.of("7"), AgentCompletionMode.TARGET,
			Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, outputGeneration,
			new CreateRouteKey(NETWORK, "warehouse"));
	}

	private static SpaceAddress address(int x) {
		return new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 4, 5));
	}
	private static UUID id(long value) { return new UUID(0, value); }
}
