package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ProductionAgentFormationPreflightTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), AGENT = id(3);
	private static final UUID NETWORK = id(4), COMPUTER = id(5), PANEL = id(6);
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
	void hostileMixedGenerationEndpointsCannotPersistAgentRoleOrAdvanceRevision() {
		AtomicReference<List<AgentEndpointState>> endpoints = new AtomicReference<>(List.of(
			endpoint(0, 4, AgentEndpointRole.INPUT, "input"),
			endpoint(1, 5, AgentEndpointRole.OUTPUT, "output")));
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(
			directory, ignored -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			new ProductionAgentRoleTransitionPolicy(clusterId ->
				ProductionAgentFormationPreflight.hasExactFormation(clusterId, endpoints.get()),
				ignored -> { throw new AssertionError("revocation audit is not used for declaration"); }));
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER), PANEL);

		assertEquals(ClusterCommunicationCommandService.ChangeResult.AGENT_FORMATION_REQUIRED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));
		ClusterCommunicationConfiguration rejected = service.configuration(CLUSTER).orElseThrow();
		assertEquals(ClusterServiceRole.STANDARD, rejected.role());
		assertEquals(0, rejected.revision());

		endpoints.set(List.of(endpoint(0, 4, AgentEndpointRole.INPUT, "input"),
			endpoint(1, 4, AgentEndpointRole.OUTPUT, "output")));
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));
		assertEquals(ClusterServiceRole.AGENT, service.configuration(CLUSTER).orElseThrow().role());
		assertEquals(1, service.configuration(CLUSTER).orElseThrow().revision());
	}

	private static AgentEndpointState endpoint(int x, long generation, AgentEndpointRole role,
		String route) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 0, 0)), AGENT, CLUSTER, generation, role,
			new CreateRouteKey(NETWORK, route), 0, CommunicationFrequency.of("7"),
			AgentCompletionMode.TARGET, Optional.empty(),
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, 0, WAREHOUSE, true, true);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
