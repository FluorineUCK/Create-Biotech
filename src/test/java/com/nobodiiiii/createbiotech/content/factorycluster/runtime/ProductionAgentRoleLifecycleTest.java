package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentFormationPreflight;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentRoleTransitionPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ProductionAgentRoleLifecycleTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), AGENT = id(3);
	private static final UUID NETWORK = id(4), COMPUTER = id(5), PANEL = id(6);
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
	void appliedStandardTransitionClosesAllLiveRoleStateAndEndpointsCanFormAgain() {
		List<AgentEndpointState> endpoints = List.of(
			endpoint(0, AgentEndpointRole.INPUT, INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT, OUTPUT));
		Map<UUID, AgentCoordinator> agents = new LinkedHashMap<>();
		Map<UUID, AgentResetService> resets = new LinkedHashMap<>();
		AgentAddressDirectory claims = new AgentAddressDirectory(8, 8);
		Map<UUID, AgentCoordinator.PersistedState> coordinatorJournal = new LinkedHashMap<>();
		Map<UUID, AgentResetService.State> resetJournal = new LinkedHashMap<>();
		AtomicInteger acquisitions = new AtomicInteger();
		AtomicInteger releases = new AtomicInteger();
		Set<AgentProcess.ResidentSlot> residentSlots = new LinkedHashSet<>();
		AgentProcess.SlotAllocator allocator = new AgentProcess.SlotAllocator() {
			@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID agentId,
				UUID ownerClusterId, ProcessKind kind) {
				AgentProcess.ResidentSlot slot = new AgentProcess.ResidentSlot(
					id(100 + acquisitions.incrementAndGet()), COMPUTER, 0, EPOCH,
					kind, true);
				residentSlots.add(slot);
				return Optional.of(slot);
			}
			@Override public boolean stillValid(AgentProcess.ResidentSlot slot) {
				return residentSlots.contains(slot);
			}
			@Override public void release(AgentProcess.ResidentSlot slot) {
				if (residentSlots.remove(slot)) releases.incrementAndGet();
			}
		};
		ClusterCommunicationDirectory declarations = new ClusterCommunicationDirectory();
		Consumer<UUID> rebuild = agentId -> {
			AgentFormation formation = AgentFormation.form(endpoints.stream()
				.filter(endpoint -> endpoint.agentId().equals(agentId)).toList());
			AgentCoordinator coordinator = AgentCoordinator.formOrRestore(formation, allocator,
				new AgentCoordinator.StateStore() {
					@Override public Optional<AgentCoordinator.PersistedState> load(UUID requested) {
						return Optional.ofNullable(coordinatorJournal.get(requested));
					}
					@Override public void persist(AgentCoordinator.PersistedState state) {
						coordinatorJournal.put(state.agentId(), state);
					}
				}, 8);
			if (!coordinator.process().available()) assertTrue(coordinator.recover(formation));
			coordinator.publishInputRegistrations(claims,
				declarations.configuration(CLUSTER).orElseThrow());
			agents.put(agentId, coordinator);
			resets.put(agentId, new AgentResetService(config(), agentId, CLUSTER, 4, null,
				false, new AgentResetService.StateStore() {
					@Override public Optional<AgentResetService.State> load(UUID requested) {
						return Optional.ofNullable(resetJournal.get(requested));
					}
					@Override public boolean compareAndSet(UUID requested, long expectedRevision,
						AgentResetService.State state) {
						AgentResetService.State current = resetJournal.get(requested);
						if ((current == null ? -1 : current.revision()) != expectedRevision) return false;
						resetJournal.put(requested, state);
						return true;
					}
				}));
		};
		ProductionAgentRoleLifecycle lifecycle = new ProductionAgentRoleLifecycle(endpoints,
			agents, resets, claims, rebuild);
		AgentRevocationDrainState drained = new AgentRevocationDrainState(CLUSTER,
			AgentRevocationDrainState.Disposition.DRAINED, List.of(
				new AgentRevocationDrainState.Finding(AgentRevocationDrainState.Source.COORDINATOR,
					AgentRevocationDrainState.Disposition.DRAINED, "explicit test drain")));
		ClusterCommunicationCommandService service = new ClusterCommunicationCommandService(
			declarations, ignored -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			new ProductionAgentRoleTransitionPolicy(clusterId ->
				ProductionAgentFormationPreflight.hasExactFormation(clusterId, endpoints),
				ignored -> drained), lifecycle::configurationChanged);
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, EPOCH,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COMPUTER), PANEL);

		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 0));
		assertEquals(1, agents.size());
		assertEquals(1, resets.size());
		assertEquals(1, residentSlots.size());
		assertInstanceOf(AgentResolution.Unique.class,
			claims.resolve(INPUT, CLUSTER, CommunicationFrequency.UNCONFIGURED));

		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.STANDARD, 1));
		assertTrue(agents.isEmpty(), "no resident Agent coordinator may survive revocation");
		assertTrue(resets.isEmpty(), "no live Reset service may survive revocation");
		assertTrue(residentSlots.isEmpty(), "the finite Cluster slot must be released immediately");
		assertEquals(1, releases.get());
		assertEquals(0, claims.activeCount());
		assertFalse(claims.resolve(INPUT, CLUSTER, CommunicationFrequency.UNCONFIGURED)
			instanceof AgentResolution.Unique);
		assertEquals(2, endpoints.size(), "native endpoint discovery remains available");

		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			service.changeRole(authority, ClusterServiceRole.AGENT, 2));
		assertEquals(1, agents.size());
		assertEquals(1, resets.size());
		assertEquals(1, residentSlots.size());
		assertEquals(2, acquisitions.get());
		assertInstanceOf(AgentResolution.Unique.class,
			claims.resolve(INPUT, CLUSTER, CommunicationFrequency.UNCONFIGURED));
	}

	private static AgentEndpointState endpoint(int x, AgentEndpointRole role, CreateRouteKey route) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 0, 0)), AGENT, CLUSTER, 4, role, route, 0,
			CommunicationFrequency.of("endpoint-local"), AgentCompletionMode.TARGET,
			Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, 0, WAREHOUSE,
			true, true);
	}

	private static FactoryRuntimeConfig config() {
		return new FactoryRuntimeConfig(1, 500, 4, 32, 4, 4, 32, 64, 20, 10, 100, 3,
			32, 16);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
