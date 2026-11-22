package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation.AgentServiceIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.PanelPublicationFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class AgentNewTaskDefaultsTest {
	private static final UUID CLUSTER = id(1), AGENT = id(2), EPOCH = id(200);

	@Test
	void agentNewTaskUsesCurrentFormedReachableHighestPriorityStableAliasAndAddressDefaults() {
		AgentEndpointState unreachable = endpoint(10, id(10), "unreachable", 99,
			AgentEndpointRole.INPUT, true, false);
		AgentEndpointState lower = endpoint(11, id(11), "lower", 8, AgentEndpointRole.INPUT,
			true, true);
		AgentEndpointState aliasZ = endpoint(12, id(12), "alpha", 9, AgentEndpointRole.INPUT,
			true, true);
		AgentEndpointState addressZ = endpoint(13, id(13), "zeta", 9, AgentEndpointRole.INPUT,
			true, true);
		AgentEndpointState selected = endpoint(14, id(14), "alpha", 9, AgentEndpointRole.INPUT,
			true, true);
		AgentEndpointState output = endpoint(15, id(15), "out", 0, AgentEndpointRole.OUTPUT,
			true, true);
		var publication = publish(authority(), List.of(unreachable, lower, aliasZ, addressZ, selected,
			output), Map.of(aliasZ.route().logisticsNetworkId(), "zeta-alias",
			addressZ.route().logisticsNetworkId(), "alpha-alias",
			selected.route().logisticsNetworkId(), "alpha-alias"), 4, 0);
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.resolve(publication, Optional.empty());
		assertEquals(selected.route(), defaults.selectedInput());
		assertEquals(InputMode.EXACT, defaults.inputMode());
		assertEquals(1, defaults.multiplier());
		assertEquals(selected.route().logisticsNetworkId(), defaults.outputObservationNetworkId());
		assertEquals(selected.route().address(), defaults.finishRoute());
		assertEquals(CLUSTER, defaults.ownerClusterId());
		assertEquals(4, defaults.priority());
	}

	@Test
	void explicitOverrideMustBeReachableAndCurrentFormationPublication() {
		var current = authority();
		AgentEndpointState preferred = endpoint(20, id(20), "preferred", 20,
			AgentEndpointRole.INPUT, true, true);
		AgentEndpointState override = endpoint(21, id(21), "override", -5,
			AgentEndpointRole.INPUT, true, true);
		AgentEndpointState output = endpoint(22, id(22), "out", 0, AgentEndpointRole.OUTPUT,
			true, true);
		var first = publish(current, List.of(preferred, override, output), Map.of(), 0, 0);
		assertEquals(override.route(), AgentNewTaskDefaults.resolve(first,
			Optional.of(override.route())).selectedInput());
		assertTrue(AgentNewTaskDefaults.tryResolve(first,
			Optional.of(new CreateRouteKey(id(99), "foreign"))).isEmpty());
		var replacement = publish(current, List.of(preferred.withAgentIdentity(AGENT, CLUSTER, 4),
			override.withAgentIdentity(AGENT, CLUSTER, 4), output.withAgentIdentity(AGENT, CLUSTER, 4)),
			Map.of(), 0, 4);
		assertTrue(AgentNewTaskDefaults.tryResolve(first, Optional.empty()).isEmpty());
		assertTrue(replacement.isCurrentPublication());
		UUID reformedAgent = id(77);
		var reformed = publish(current, List.of(preferred.withAgentIdentity(reformedAgent, CLUSTER, 0),
			override.withAgentIdentity(reformedAgent, CLUSTER, 0),
			output.withAgentIdentity(reformedAgent, CLUSTER, 0)), Map.of(), 0, 5);
		assertTrue(AgentNewTaskDefaults.tryResolve(replacement, Optional.empty()).isEmpty());
		assertTrue(reformed.isCurrentPublication());
	}

	@Test
	void resolverCannotUseUnformedOrMismatchedGenerationDirectory() {
		AgentEndpointState input = endpoint(30, id(30), "input", 1, AgentEndpointRole.INPUT,
			true, true);
		assertThrows(IllegalArgumentException.class, () -> AgentFormation.form(List.of(input)));
		AgentEndpointState output = endpoint(31, id(31), "output", 1, AgentEndpointRole.OUTPUT,
			true, true).withAgentIdentity(AGENT, CLUSTER, 4);
		assertThrows(IllegalArgumentException.class, () -> publish(authority(), List.of(input, output),
			Map.of(), 0, 0));
	}

	@Test
	void standardClusterRetainsItsNormalPolicyInsteadOfAgentExactDefaults() {
		CreateRouteKey warehouse = new CreateRouteKey(id(30), "main_warehouse");
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.standard(CLUSTER, warehouse,
			InputMode.PARTIAL, 7, 12);
		assertEquals(CLUSTER, defaults.ownerClusterId());
		assertEquals(warehouse, defaults.selectedInput());
		assertEquals(InputMode.PARTIAL, defaults.inputMode());
		assertEquals(7, defaults.multiplier());
		assertEquals(12, defaults.priority());
	}

	@Test
	void authorityRevokeInvalidatesExactEpochAndReleasesOwnership() {
		var current = authority();
		AgentEndpointState input = endpoint(90, id(90), "revoke", 1, AgentEndpointRole.INPUT,
			true, true);
		AgentEndpointState output = endpoint(91, id(91), "revoke-out", 0,
			AgentEndpointRole.OUTPUT, true, true);
		var publication = publish(current, List.of(input, output), Map.of(), 0, 7);
		current.revoke(CLUSTER, EPOCH);
		assertTrue(AgentNewTaskDefaults.tryResolve(publication, Optional.empty()).isEmpty());
		assertEquals(0, current.publicationCountForTest());
	}

	private static AgentNewTaskDefaults.PublicationAuthority authority() {
		try {
			Constructor<AgentNewTaskDefaults.PublicationAuthority> constructor =
				AgentNewTaskDefaults.PublicationAuthority.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static AgentNewTaskDefaults.FormationPublication publish(
		AgentNewTaskDefaults.PublicationAuthority authority, List<AgentEndpointState> endpoints,
		Map<UUID, String> aliases, int priority, long directoryGeneration) {
		try {
			AgentFormation formation = AgentFormation.form(endpoints);
			Method method = AgentNewTaskDefaults.PublicationAuthority.class.getDeclaredMethod(
				"publishUnchecked", PanelPublicationFence.class, UUID.class, long.class,
				AgentFormation.class, AgentServiceIdentity.class, Map.class, int.class);
			method.setAccessible(true);
			return (AgentNewTaskDefaults.FormationPublication) method.invoke(authority, null, EPOCH,
				directoryGeneration, formation,
				new AgentServiceIdentity(formation.agentId(), formation.agentGeneration()), aliases, priority);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static AgentEndpointState endpoint(long position, UUID network, String address,
		int priority, AgentEndpointRole role, boolean loaded, boolean reachable) {
		CreateRouteKey route = new CreateRouteKey(network, address);
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos((int) position, 4, 5)), AGENT, CLUSTER, 3, role, route, priority,
			com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("17"),
			AgentCompletionMode.TARGET, Optional.empty(), AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			2, new CreateRouteKey(network, "return_warehouse"), loaded, reachable);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}