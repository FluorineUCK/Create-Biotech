package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanFreezer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentPendingRunQueue;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation.AgentServiceIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.OrdinaryProviderRegistry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionSnapshot;

class FactoryRuntimeServiceTest {
	private static SpaceAddress worldAddress(RecipeSourceLocation source) {
		return new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
	}

	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
	@BeforeAll static void bootstrapMinecraftRegistries() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) == null)
				loadingModList.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true); bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}

	@Test void emptyLogisticsLibraryIsDormantAndRemovingNetworksRevokesItsRoute() throws Exception {
        UUID cluster=id(90_001), epoch=id(90_002), computer=id(90_003), network=id(90_004), library=id(90_005);
        PatternRecord candidate=pattern(id(90_006),library,Items.STONE,OptionalInt.empty(),"dormant-library");
        ProductionRecipeCatalog catalog=new ProductionRecipeCatalog(library,1,1,true,List.of(candidate));
        StackKey requested=new StackKey(new ItemStack(Items.STONE));
        try (FactoryRuntimeService service=new FactoryRuntimeService(CONFIG)) {
            service.start();
            service.restoreSnapshot(stateWithMembers(cluster,epoch,Map.of(computer,
                ProductionResourceProfile.forProfession(ProductionProfession.CLERIC,5,0).orElseThrow())));
            service.publishProductionLibraryAccess(cluster,Set.of(),1,CommunicationFrequency.of("0"),10,
                worldAddress(candidate.source().shelf()),LibraryRegistration.Reachability.REACHABLE,catalog);
            assertTrue(service.selectProductionPattern(cluster,network,requested).isEmpty());
            service.publishProductionLibraryAccess(cluster,Set.of(network),2,CommunicationFrequency.of("0"),10,
                worldAddress(candidate.source().shelf()),LibraryRegistration.Reachability.REACHABLE,catalog);
            assertEquals(candidate.patternId(),awaitProductionSelection(service,cluster,network,requested).pattern().patternId());
            service.publishProductionLibraryAccess(cluster,Set.of(),3,CommunicationFrequency.of("0"),10,
                worldAddress(candidate.source().shelf()),LibraryRegistration.Reachability.REACHABLE,catalog);
            assertTrue(service.selectProductionPattern(cluster,network,requested).isEmpty(),
                "Removing the last logistics network must revoke previously selected Library routes");
        }
    }

	@Test
	void panelStandardSnapshotFailsClosedWithoutAuthoritativeCommunicationSnapshot() {
		UUID cluster = id(89_991), epoch = id(89_992);
		CreateRouteKey route = new CreateRouteKey(id(89_993), "missing-panel-role");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(engine);

			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(
				engine, Map.of(), AgentNewTaskDefaults.standard(cluster, route, InputMode.BULK, 1, 0),
				Set.of(route)));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelRoleProofsComeFromOneInstalledAuthoritativeCommunicationSnapshot() {
		UUID cluster = id(89_981), epoch = id(89_982), agent = id(89_983);
		CreateRouteKey input = new CreateRouteKey(id(89_984), "authoritative-input");
		CreateRouteKey output = new CreateRouteKey(id(89_984), "authoritative-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
			new AtomicReference<>(Optional.empty());
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(engine);
			service.installPanelCommunicationSnapshotSource(ignored -> authoritative.get());
			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(
				engine, Map.of(), AgentNewTaskDefaults.standard(cluster, input, InputMode.BULK, 1, 0),
				Set.of(input)));

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("authoritative"),
					ClusterServiceRole.STANDARD, 2), 5)));
			TaskProjectionSnapshot.PublishedSnapshot standard = service.publishPanelStandardSnapshot(
				engine, Map.of(), AgentNewTaskDefaults.standard(cluster, input, InputMode.BULK, 1, 0),
				Set.of(input));
			assertTrue(standard.isCurrentPublication());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("authoritative"),
					ClusterServiceRole.AGENT, 3), 6)));
			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(
				engine, Map.of(), AgentNewTaskDefaults.standard(cluster, input, InputMode.BULK, 1, 0),
				Set.of(input)));
			var formationPublication = service.publishPanelAgentFormation(
				service.capturePanelAgentFormation(engine, formation, declared, Map.of(), 0));
			TaskProjectionSnapshot.PublishedSnapshot agentSnapshot = service.publishPanelAgentSnapshot(
				engine, Map.of(), formationPublication, Optional.empty(), Set.of(input));
			assertFalse(standard.isCurrentPublication());
			assertTrue(agentSnapshot.isCurrentPublication());

			authoritative.set(Optional.empty());
			assertThrows(IllegalStateException.class, () -> service.publishPanelAgentSnapshot(engine,
				Map.of(), formationPublication, Optional.empty(), Set.of(input)));
			assertFalse(formationPublication.isCurrentPublication());
			assertFalse(agentSnapshot.isCurrentPublication());
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelCaptureRejectsSnapshotFromReplacedLifecycleAdapter() {
		UUID cluster = id(89_971), epoch = id(89_972);
		CreateRouteKey route = new CreateRouteKey(id(89_973), "replaced-panel-adapter");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.standard(cluster, route,
			InputMode.BULK, 1, 0);
		FactoryRuntimeService.PanelCommunicationSnapshot replacement =
			new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("replacement"),
					ClusterServiceRole.STANDARD, 2), 2);
		AtomicBoolean replaceDuringCapture = new AtomicBoolean(true);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(engine);
			service.installPanelCommunicationSnapshotSource(request -> {
				if (replaceDuringCapture.getAndSet(false))
					service.installPanelCommunicationSnapshotSource(ignored -> Optional.of(replacement));
				return Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
					new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("replaced"),
						ClusterServiceRole.STANDARD, 1), 1));
			});

			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(
				engine, Map.of(), defaults, Set.of(route)));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
			assertTrue(service.publishPanelStandardSnapshot(engine, Map.of(), defaults, Set.of(route))
				.isCurrentPublication());
		}
	}

	@Test
	void panelPublicationFenceRejectsDelayedEpochAndCloseInvalidatesTokens() {
		UUID cluster = id(90_001), firstEpoch = id(90_002), secondEpoch = id(90_003);
		CreateRouteKey route = new CreateRouteKey(id(90_004), "panel-fence");
		ClusterEngineState first = ClusterEngineState.empty(cluster, firstEpoch);
		ClusterEngineState second = ClusterEngineState.empty(cluster, secondEpoch);
		AgentNewTaskDefaults firstDefaults = AgentNewTaskDefaults.standard(cluster, route,
			InputMode.BULK, 1, 0);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0);
			service.restoreSnapshot(first);
			TaskProjectionSnapshot.PublishedSnapshot firstPublication =
				service.publishPanelStandardSnapshot(first, Map.<UUID,
					TaskProjectionService.GoalDisplay>of(), firstDefaults, Set.of(route));
			service.restoreSnapshot(second);
			TaskProjectionSnapshot.PublishedSnapshot secondPublication =
				service.publishPanelStandardSnapshot(second, Map.<UUID,
					TaskProjectionService.GoalDisplay>of(), firstDefaults, Set.of(route));
			assertFalse(firstPublication.isCurrentPublication());
			assertTrue(secondPublication.isCurrentPublication());
			assertThrows(IllegalArgumentException.class, () -> service.publishPanelStandardSnapshot(
				first, Map.<UUID, TaskProjectionService.GoalDisplay>of(), firstDefaults, Set.of(route)));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(1, 0),
				service.panelPublicationCountsForTest());
			service.close();
			assertFalse(secondPublication.isCurrentPublication());
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelAgentRoleRequiresFormedPublicationAndExactEpochRevocation() {
		UUID cluster = id(90_011), epoch = id(90_012), agent = id(90_013);
		CreateRouteKey input = new CreateRouteKey(id(90_014), "agent-panel-input");
		CreateRouteKey output = new CreateRouteKey(id(90_014), "agent-panel-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("panel-agent-role"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(engine,
				Map.of(), AgentNewTaskDefaults.standard(cluster, input, InputMode.BULK, 1, 0),
				Set.of(input)));
			var publication = publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			TaskProjectionSnapshot.PublishedSnapshot task = service.publishPanelAgentSnapshot(engine,
				Map.of(), publication, Optional.empty(), Set.of(input));
			assertTrue(task.isCurrentPublication());
			ClusterEngineState replacement = ClusterEngineState.empty(cluster, id(90_016));
			service.restoreSnapshot(replacement);
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("panel-agent-role"), ClusterServiceRole.AGENT, 4), 8)));
			AgentFormation replacementFormation = AgentFormation.form(List.of(
				panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
				panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
			var replacementPublication = publishPanelAgentFormation(service, replacement, 8,
				replacementFormation, new AgentServiceIdentity(agent,
					replacementFormation.agentGeneration()), Map.of(), 0);
			TaskProjectionSnapshot.PublishedSnapshot replacementTask = service.publishPanelAgentSnapshot(
				replacement, Map.of(), replacementPublication, Optional.empty(), Set.of(input));
			assertFalse(task.isCurrentPublication());
			assertTrue(replacementTask.isCurrentPublication());
			assertThrows(IllegalArgumentException.class, () -> publishPanelAgentFormation(service, engine,
				7, formation, new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0));
			var captureBeforeUnformation = service.capturePanelAgentFormation(replacement,
				replacementFormation, new AgentServiceIdentity(agent,
					replacementFormation.agentGeneration()), Map.of(), 0);
			assertTrue(service.revokePanelAgentFormation(cluster, replacement.epochId()));
			assertThrows(IllegalStateException.class,
				() -> service.publishPanelAgentFormation(captureBeforeUnformation));
			assertFalse(replacementTask.isCurrentPublication());
			assertThrows(IllegalStateException.class, () -> publishPanelAgentFormation(service,
				replacement, 8, replacementFormation, new AgentServiceIdentity(agent,
					replacementFormation.agentGeneration()), Map.of(), 0));
			assertFalse(service.revokePanelAgentFormation(cluster, id(90_015)));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelPublicationCurrentCheckCannotSurviveConcurrentClose() throws Exception {
		UUID cluster = id(90_021), epoch = id(90_022);
		CreateRouteKey route = new CreateRouteKey(id(90_023), "concurrent-panel");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0);
			service.restoreSnapshot(engine);
			TaskProjectionSnapshot.PublishedSnapshot publication = service.publishPanelStandardSnapshot(
				engine, Map.of(), AgentNewTaskDefaults.standard(cluster, route, InputMode.BULK, 1, 0),
				Set.of(route));
			AtomicBoolean closed = new AtomicBoolean();
			AtomicBoolean observedAfterClose = new AtomicBoolean();
			Thread reader = new Thread(() -> {
				while (!closed.get()) publication.isCurrentPublication();
				for (int index = 0; index < 10_000; index++)
					if (publication.isCurrentPublication()) observedAfterClose.set(true);
			});
			reader.start(); service.close(); closed.set(true); reader.join();
			assertFalse(observedAfterClose.get());
		}
	}

	@Test
	void panelDeclarationChangeRevokesTheExactCurrentAgentPublication() {
		UUID cluster = id(90_026), epoch = id(90_027), agent = id(90_028);
		CreateRouteKey input = new CreateRouteKey(id(90_029), "role-revoke-input");
		CreateRouteKey output = new CreateRouteKey(id(90_029), "role-revoke-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("role-revoke"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			var publication = publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("role-revoke"),
					ClusterServiceRole.AGENT, 4), 8)));
			service.capturePanelAgentFormation(engine, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			assertTrue(AgentNewTaskDefaults.tryResolve(publication, Optional.empty()).isEmpty());
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelPublicationAuthoritiesAreIsolatedPerRuntime() {
		UUID cluster = id(90_031), epoch = id(90_032);
		CreateRouteKey route = new CreateRouteKey(id(90_033), "isolated-panel");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.standard(cluster, route,
			InputMode.BULK, 1, 0);
		try (FactoryRuntimeService first = new FactoryRuntimeService(CONFIG);
			FactoryRuntimeService second = new FactoryRuntimeService(CONFIG)) {
			first.start(); second.start();
			installPanelCommunication(first, new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0);
			installPanelCommunication(second, new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0);
			first.restoreSnapshot(engine); second.restoreSnapshot(engine);
			TaskProjectionSnapshot.PublishedSnapshot one = first.publishPanelStandardSnapshot(engine,
				Map.of(), defaults, Set.of(route));
			TaskProjectionSnapshot.PublishedSnapshot two = second.publishPanelStandardSnapshot(engine,
				Map.of(), defaults, Set.of(route));
			first.close();
			assertFalse(one.isCurrentPublication());
			assertTrue(two.isCurrentPublication());
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(1, 0),
				second.panelPublicationCountsForTest());
		}
	}

	@Test
	void panelAgentPublicationCannotCrossRuntimeAuthority() {
		UUID cluster = id(90_041), epoch = id(90_042), agent = id(90_043);
		CreateRouteKey input = new CreateRouteKey(id(90_044), "cross-runtime-input");
		CreateRouteKey output = new CreateRouteKey(id(90_044), "cross-runtime-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		try (FactoryRuntimeService first = new FactoryRuntimeService(CONFIG);
			FactoryRuntimeService second = new FactoryRuntimeService(CONFIG)) {
			first.start(); second.start();
			ClusterCommunicationConfiguration declaration = new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.of("cross-runtime"), ClusterServiceRole.AGENT, 3);
			installPanelCommunication(first, declaration, 7);
			installPanelCommunication(second, declaration, 7);
			first.restoreSnapshot(engine); second.restoreSnapshot(engine);
			var firstPublication = publishPanelAgentFormation(first, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			assertThrows(IllegalArgumentException.class, () -> second.publishPanelAgentSnapshot(engine,
				Map.of(), firstPublication, Optional.empty(), Set.of(input)));
		}
	}

	@Test
	void panelDeclarationRevokeRetainsItsEpochFenceUntilNewAuthoritativeFactsArrive() {
		UUID cluster = id(90_051), epoch = id(90_052), agent = id(90_053);
		CreateRouteKey input = new CreateRouteKey(id(90_054), "revoke-fence-input");
		CreateRouteKey output = new CreateRouteKey(id(90_054), "revoke-fence-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		ClusterCommunicationConfiguration first = new ClusterCommunicationConfiguration(cluster,
			CommunicationFrequency.of("revoke-fence"), ClusterServiceRole.AGENT, 3);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, first, 7);
			service.restoreSnapshot(engine);
			publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			assertThrows(IllegalStateException.class, () -> publishPanelAgentFormation(service, engine,
				7, formation, new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0));
			ClusterCommunicationConfiguration advanced = new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.of("revoke-fence"), ClusterServiceRole.AGENT, 4);
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(advanced, 7)));
			assertDoesNotThrow(() -> publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0));
		}
	}

	@Test
	void panelRoleTombstoneRequiresComponentWiseNonRegressingCoherentAdvance() {
		UUID cluster = id(90_071), epoch = id(90_072), agent = id(90_073);
		CreateRouteKey input = new CreateRouteKey(id(90_074), "coherent-input");
		CreateRouteKey output = new CreateRouteKey(id(90_074), "coherent-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		for (Map.Entry<Long, Long> candidate : List.of(Map.entry(2L, 8L), Map.entry(4L, 6L),
			Map.entry(3L, 7L))) {
			try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
				service.start();
				AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
					installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
						CommunicationFrequency.of("coherent"), ClusterServiceRole.AGENT, 3), 7);
				service.restoreSnapshot(engine);
				publishPanelAgentFormation(service, engine, 7, formation, declared, Map.of(), 0);
				assertTrue(service.revokePanelDeclaration(cluster, epoch));
				authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
					new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("coherent"),
						ClusterServiceRole.AGENT, candidate.getKey()), candidate.getValue())));

				assertThrows(IllegalStateException.class, () -> publishPanelAgentFormation(service,
					engine, candidate.getValue(), formation, declared, Map.of(), 0), candidate.toString());
				assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
					service.panelPublicationCountsForTest(), candidate.toString());
			}
		}

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("coherent"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			publishPanelAgentFormation(service, engine, 7, formation, declared, Map.of(), 0);
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("coherent"),
					ClusterServiceRole.AGENT, 3), 8)));
			assertDoesNotThrow(() -> publishPanelAgentFormation(service, engine, 8, formation,
				declared, Map.of(), 0));
		}
	}

	@Test
	void revokedAgentDeclarationCannotBecomeStandardAtSameRevisionWhenDirectoryAdvances() {
		UUID cluster = id(90_091), epoch = id(90_092), agent = id(90_093);
		CreateRouteKey input = new CreateRouteKey(id(90_094), "role-switch-input");
		CreateRouteKey output = new CreateRouteKey(id(90_094), "role-switch-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		AgentNewTaskDefaults standardDefaults = AgentNewTaskDefaults.standard(cluster, input,
			InputMode.BULK, 1, 0);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("role-a"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			var formationPublication = publishPanelAgentFormation(service, engine, 7, formation,
				declared, Map.of(), 0);
			TaskProjectionSnapshot.PublishedSnapshot agentSnapshot = service.publishPanelAgentSnapshot(
				engine, Map.of(), formationPublication, Optional.empty(), Set.of(input));
			assertTrue(agentSnapshot.isCurrentPublication());
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			assertFalse(agentSnapshot.isCurrentPublication());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("role-b"),
					ClusterServiceRole.STANDARD, 3), 8)));
			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(engine,
				Map.of(), standardDefaults, Set.of(input)));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("role-b"),
					ClusterServiceRole.STANDARD, 4), 8)));
			assertTrue(service.publishPanelStandardSnapshot(engine, Map.of(), standardDefaults,
				Set.of(input)).isCurrentPublication());
		}
	}

	@Test
	void revokedStandardDeclarationCannotBecomeAgentAtSameRevisionWhenDirectoryAdvances() {
		UUID cluster = id(90_101), epoch = id(90_102), agent = id(90_103);
		CreateRouteKey input = new CreateRouteKey(id(90_104), "reverse-input");
		CreateRouteKey output = new CreateRouteKey(id(90_104), "reverse-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("standard-a"), ClusterServiceRole.STANDARD, 3), 7);
			service.restoreSnapshot(engine);
			TaskProjectionSnapshot.PublishedSnapshot standardSnapshot =
				service.publishPanelStandardSnapshot(engine, Map.of(), AgentNewTaskDefaults.standard(
					cluster, input, InputMode.BULK, 1, 0), Set.of(input));
			assertTrue(standardSnapshot.isCurrentPublication());
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			assertFalse(standardSnapshot.isCurrentPublication());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("agent-b"),
					ClusterServiceRole.AGENT, 3), 8)));
			assertThrows(IllegalStateException.class, () -> service.capturePanelAgentFormation(engine,
				formation, declared, Map.of(), 0));
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("agent-b"),
					ClusterServiceRole.AGENT, 4), 8)));
			var formationPublication = publishPanelAgentFormation(service, engine, 8, formation,
				declared, Map.of(), 0);
			assertTrue(service.publishPanelAgentSnapshot(engine, Map.of(), formationPublication,
				Optional.empty(), Set.of(input)).isCurrentPublication());
		}
	}

	@Test
	void sameEpochPanelLifecycleRetiresPayloadsWithoutReusingTaskRevisionsOrCommands() {
		for (PanelRetirement retirement : PanelRetirement.values())
			assertPanelRetirementPreservesRevisionHighWater(retirement);
	}

	@Test
	void panelAgentSameFactsAfterEngineGenerationAdvanceMintsFreshRuntimeFence() {
		UUID cluster = id(90_081), epoch = id(90_082), agent = id(90_083);
		CreateRouteKey input = new CreateRouteKey(id(90_084), "generation-input");
		CreateRouteKey output = new CreateRouteKey(id(90_084), "generation-output");
		ClusterEngineState firstEngine = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState nextEngine = withGeneration(firstEngine, firstEngine.generation() + 1);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.of("generation"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(firstEngine);
			AgentNewTaskDefaults.FormationPublication first = publishPanelAgentFormation(service,
				firstEngine, 7, formation, declared, Map.of(), 0);

			service.restoreSnapshot(nextEngine);
			assertFalse(first.isCurrentPublication());
			AgentNewTaskDefaults.FormationPublication next = publishPanelAgentFormation(service,
				nextEngine, 7, formation, declared, Map.of(), 0);

			assertNotSame(first, next);
			assertFalse(first.isCurrentPublication());
			assertTrue(next.isCurrentPublication());
			assertTrue(service.publishPanelAgentSnapshot(nextEngine, Map.of(), next,
				Optional.empty(), Set.of(input)).isCurrentPublication());
		}
	}

	@Test
	void panelAgentFormationCaptureCannotBeReplayedAfterDeclarationRevoke() {
		UUID cluster = id(90_055), epoch = id(90_056), agent = id(90_057);
		CreateRouteKey input = new CreateRouteKey(id(90_058), "replayed-formation-input");
		CreateRouteKey output = new CreateRouteKey(id(90_058), "replayed-formation-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("replayed-formation"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			var delayedCapture = service.capturePanelAgentFormation(engine, formation, declared,
				Map.of(), 0);
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("replayed-formation"), ClusterServiceRole.AGENT, 4), 7)));
			assertThrows(IllegalStateException.class,
				() -> service.publishPanelAgentFormation(delayedCapture));
			var currentCapture = service.capturePanelAgentFormation(engine, formation, declared,
				Map.of(), 0);
			assertDoesNotThrow(() -> service.publishPanelAgentFormation(currentCapture));
		}
	}
	@Test
	void panelDeclarationRevokeBlocksDelayedStandardSnapshotUntilFreshRoleProof() {
		UUID cluster = id(90_056), epoch = id(90_057);
		CreateRouteKey route = new CreateRouteKey(id(90_058), "standard-revoke");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentNewTaskDefaults defaults = AgentNewTaskDefaults.standard(cluster, route,
			InputMode.BULK, 1, 0);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0);
			service.restoreSnapshot(engine);
			service.publishPanelStandardSnapshot(engine, Map.of(), defaults, Set.of(route));
			assertTrue(service.revokePanelDeclaration(cluster, epoch));
			assertThrows(IllegalStateException.class, () -> service.publishPanelStandardSnapshot(engine,
				Map.of(), defaults, Set.of(route)));
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("standard-revoke"),
					ClusterServiceRole.STANDARD, 1), 0)));
			assertDoesNotThrow(() -> service.publishPanelStandardSnapshot(engine, Map.of(), defaults,
				Set.of(route)));
		}
	}

	@Test
	void panelRoleAllowsIndependentDeclarationAndDirectoryAdvancement() {
		UUID cluster = id(90_061), epoch = id(90_062), agent = id(90_063);
		CreateRouteKey input = new CreateRouteKey(id(90_064), "independent-input");
		CreateRouteKey output = new CreateRouteKey(id(90_064), "independent-output");
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("independent"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0);
			ClusterCommunicationConfiguration revised = new ClusterCommunicationConfiguration(cluster,
				CommunicationFrequency.of("independent-revised"), ClusterServiceRole.AGENT, 4);
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(revised, 7)));
			assertDoesNotThrow(() -> publishPanelAgentFormation(service, engine, 7, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0));
			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(revised, 8)));
			assertDoesNotThrow(() -> publishPanelAgentFormation(service, engine, 8, formation,
				new AgentServiceIdentity(agent, formation.agentGeneration()), Map.of(), 0));
		}
	}

	@Test
	void partialResolverReplacementDoesNotRetainClosedScopeCallbacks() throws Exception {
		UUID cluster = id(50_001), epoch = id(50_002), computer = id(50_003);
		AtomicInteger staleRequesterCalls = new AtomicInteger();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			AutoCloseable scope = service.installProductionResolvers(
				request -> new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of(), List.of()),
				ignored -> Optional.empty(), ignored -> {
					staleRequesterCalls.incrementAndGet();
					return CommunicationFrequency.of("closed-partial-scope");
				});
			service.setProductionRouteCollector(request ->
				new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of(), List.of()));

			scope.close();
			service.selectProductionPattern(cluster, id(50_004),
				new StackKey(new ItemStack(Items.STONE)));

			assertEquals(0, staleRequesterCalls.get(),
				"closing a partially replaced scope must retire every callback it still owns");
		}
	}

	@Test
	void outOfOrderResolverCloseNeverResurrectsAnAlreadyClosedPriorScope() throws Exception {
		UUID cluster = id(50_011), epoch = id(50_012), computer = id(50_013);
		AtomicInteger firstRequesterCalls = new AtomicInteger();
		AtomicInteger secondRequesterCalls = new AtomicInteger();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			AutoCloseable first = service.installProductionResolvers(
				request -> new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of(), List.of()),
				ignored -> Optional.empty(), ignored -> {
					firstRequesterCalls.incrementAndGet();
					return CommunicationFrequency.of("closed-first-scope");
				});
			AutoCloseable second = service.installProductionResolvers(
				request -> new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of(), List.of()),
				ignored -> Optional.empty(), ignored -> {
					secondRequesterCalls.incrementAndGet();
					return CommunicationFrequency.of("closed-second-scope");
				});

			first.close();
			second.close();
			service.selectProductionPattern(cluster, id(50_014),
				new StackKey(new ItemStack(Items.STONE)));

			assertEquals(0, firstRequesterCalls.get(),
				"a successor close must skip a prior resolver scope that was already closed");
			assertEquals(0, secondRequesterCalls.get());
		}
	}

	@Test
	void receiverAllocationIsAtomicAcrossCompetingLeasesAndReplayAfterReload() throws Exception {
		UUID receiverCluster = id(320), receiverEpoch = id(321), receiverMember = id(322);
		List<RemoteProcessLease> leases = List.of(
			remoteReceiverLease(330, receiverCluster, 7),
			remoteReceiverLease(340, receiverCluster, 7),
			remoteReceiverLease(350, receiverCluster, 7));
		List<AtomicReference<Optional<FactoryRuntimeService.RemoteComputationAllocation>>> results =
			leases.stream().map(ignored -> new AtomicReference<Optional<
				FactoryRuntimeService.RemoteComputationAllocation>>(Optional.empty())).toList();
		CountDownLatch start = new CountDownLatch(1);
		ArrayList<Thread> contenders = new ArrayList<>();
		ClusterEngineState durable;

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(remoteReceiverState(receiverCluster, receiverEpoch,
				receiverMember));
			for (int index = 0; index < leases.size(); index++) {
				int contender = index;
				Thread thread = new Thread(() -> {
					try {
						assertTrue(start.await(3, TimeUnit.SECONDS));
						results.get(contender).set(service.acquireRemoteComputationProcess(
							leases.get(contender), receiverMember));
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new AssertionError(interrupted);
					}
				}, "remote-slot-contender-" + index);
				contenders.add(thread);
				thread.start();
			}
			start.countDown();
			for (Thread contender : contenders) {
				contender.join(Duration.ofSeconds(3).toMillis());
				assertFalse(contender.isAlive(), "receiver allocation contender did not finish");
			}

			List<FactoryRuntimeService.RemoteComputationAllocation> acquired = results.stream()
				.map(AtomicReference::get).flatMap(Optional::stream).toList();
			assertEquals(2, acquired.size(),
				"the two canonical Cleric slots must be installed at most once each");
			assertEquals(2, acquired.stream().map(
				FactoryRuntimeService.RemoteComputationAllocation::owningSlot).distinct().count());
			assertEquals(2, acquired.stream().map(
				FactoryRuntimeService.RemoteComputationAllocation::processId).distinct().count());
			durable = service.snapshot(receiverCluster).orElseThrow();
			assertEquals(2, durable.processes().size());
		}

		int replayIndex = 0;
		while (results.get(replayIndex).get().isEmpty()) replayIndex++;
		int blockedIndex = 0;
		while (results.get(blockedIndex).get().isPresent()) blockedIndex++;
		RemoteProcessLease replayLease = leases.get(replayIndex);
		RemoteProcessLease blockedLease = leases.get(blockedIndex);
		FactoryRuntimeService.RemoteComputationAllocation expectedReplay =
			results.get(replayIndex).get().orElseThrow();

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start();
			restored.restoreSnapshot(RuntimeNbt.load(RuntimeNbt.save(durable)).orElseThrow());
			assertEquals(expectedReplay, restored.acquireRemoteComputationProcess(replayLease,
				receiverMember).orElseThrow(), "the exact durable allocation must replay");
			assertTrue(restored.acquireRemoteComputationProcess(blockedLease, receiverMember).isEmpty(),
				"a competing lease cannot claim either occupied slot");
			assertTrue(restored.releaseRemoteComputationProcess(expectedReplay.processId(),
				replayLease, receiverMember, expectedReplay.owningSlot()));
			assertEquals(expectedReplay.owningSlot(), restored.acquireRemoteComputationProcess(
				blockedLease, receiverMember).orElseThrow().owningSlot(),
				"only an exact release may make the durable slot available again");
		}
	}

	@Test
	void terminalRemoteLeaseRetiresInFlightAndReadyPureResultsAtTheExactFence()
		throws Exception {
		UUID receiverCluster = id(360), receiverEpoch = id(361), receiverMember = id(362);
		RemoteProcessLease lease = remoteReceiverLease(370, receiverCluster, 7);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(remoteReceiverState(receiverCluster, receiverEpoch,
				receiverMember));
			FactoryRuntimeService.RemoteComputationAllocation allocation = service
				.acquireRemoteComputationProcess(lease, receiverMember).orElseThrow();
			FactoryComputeWorker.RemotePureWork work = new FactoryComputeWorker.RemotePureWork(
				lease.leaseId(), receiverCluster, receiverMember, allocation.processId(),
				allocation.owningSlot(), lease.receiverGeneration(), lease.revisionFence(),
				lease.requiredCapability(), lease.expansionRequest(),
				FactoryComputeWorker.RemotePureStage.REVISION, Optional.empty());
			assertTrue(service.submitRemotePureWork(work));

			assertTrue(service.retireRemotePureArtifacts(lease),
				"the terminal fence must remove submitted or already-ready pure work");
			assertTrue(service.releaseRemoteComputationProcess(allocation.processId(), lease,
				receiverMember, allocation.owningSlot()));
			Thread.sleep(20);
			assertTrue(service.drainRemotePureResult(work).isEmpty(),
				"a late worker completion cannot resurrect a terminal lease result");
			assertFalse(service.retireRemotePureArtifacts(lease));
		}
	}

	@Test
	void sourceChildExpansionAcknowledgementIsDurableAndReloadIdempotent() {
		UUID sourceCluster = id(280), receiverCluster = id(281), epoch = id(282);
		UUID sourceComputer = id(283), sourceProcessId = id(284);
		ClusterEngineState source = dispatchable(sourceCluster, epoch, sourceComputer,
			sourceProcessId);
		ProcessRecord sourceProcess = source.processes().get(sourceProcessId);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), sourceProcess.goalId(),
			sourceProcessId, sourceProcess.generation(), source.generation(), id(285), 2, id(286),
			0, InputMode.BULK, 2, List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(287), "remote-input"), Map.of(iron, 1L))),
			Map.of(iron, 1L));
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteProcessLease lease = new RemoteProcessLease(id(288), sourceProcess.goalId(),
			sourceProcessId, sourceCluster, receiverCluster, 4, 5,
			ProductionCapability.ALCHEMIST, request, source.generation());
		ClusterEngineState durable;

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(source);
			assertTrue(service.installRemoteChildRevision(lease, revision));
			assertTrue(service.remoteExpansionMaterialAuthorized(lease, revision));
			durable = service.snapshot(sourceCluster).orElseThrow();
			assertTrue(durable.processes().get(sourceProcessId).isChildAcknowledgedDurably(
				revision.childRevision().childProcessId(),
				revision.childRevision().childGeneration(), revision.childRevision().revision()));
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start();
			restored.restoreSnapshot(RuntimeNbt.load(RuntimeNbt.save(durable)).orElseThrow());
			assertTrue(restored.installRemoteChildRevision(lease, revision));
			assertTrue(restored.remoteExpansionMaterialAuthorized(lease, revision));
		}
	}

	@Test void durableChildAcknowledgementCannotAuthorizeANewerParentGeneration() {
		var fixture = materialSource();
		try (var service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(fixture.source());
			assertTrue(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			var durable = service.snapshot(fixture.source().clusterId()).orElseThrow();
			var parent = durable.processes().get(fixture.lease().sourceProcessId());
			var nextParent = new ProcessRecord(parent.processId(), parent.goalId(), parent.owningComputerId(),
				parent.owningSlot(), parent.owningComputer(), parent.kind(), parent.state(), parent.waitReason(),
				parent.generation() + 1, parent.childRevisionStates());
			var next = materialSourceState(durable, nextParent, durable.goals().get(parent.goalId()), durable.generation());
			service.restoreSnapshot(RuntimeNbt.load(RuntimeNbt.save(next)).orElseThrow());
			assertFalse(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			assertFalse(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()),
				"Durable child ACK belongs to the old parent generation, not the restored replacement");
			assertEquals(next, service.snapshot(next.clusterId()).orElseThrow());
		}
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"terminal_parent", "terminal_goal", "wrong_kind", "missing_parent", "missing_goal", "exhausted_generation"})
	void durableChildAcknowledgementRequiresACurrentSourceLifecycle(String change) {
		var fixture = materialSource();
		try (var service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(fixture.source());
			assertTrue(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			var durable = service.snapshot(fixture.source().clusterId()).orElseThrow();
			var parent = durable.processes().get(fixture.lease().sourceProcessId());
			var goal = durable.goals().get(parent.goalId());
			long generation = durable.generation();
			switch (change) {
				case "terminal_parent" -> parent = materialParent(parent, parent.kind(), ProcessState.TERMINAL);
				case "terminal_goal" -> goal = materialGoal(goal, GoalState.TERMINAL);
				case "wrong_kind" -> parent = materialParent(parent, ProcessKind.PRIORITY_LEASE, parent.state());
				case "missing_parent" -> parent = null;
				case "missing_goal" -> { parent = materialParent(parent, ProcessKind.PRIORITY_LEASE, parent.state()); goal = null; }
				case "exhausted_generation" -> generation = Long.MAX_VALUE;
				default -> throw new AssertionError(change);
			}
			var next = materialSourceState(durable, parent, goal, generation);
			service.restoreSnapshot(RuntimeNbt.load(RuntimeNbt.save(next)).orElseThrow());
			assertFalse(service.installRemoteChildRevision(fixture.lease(), fixture.revision()), change);
			assertFalse(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()), change);
			assertEquals(next, service.snapshot(next.clusterId()).orElseThrow(), "Rejected authority must not mutate restored state");
		}
	}

	@Test void durableChildAcknowledgementAllowsWaitingAndStateOnlyGenerationChanges() {
		var fixture = materialSource();
		try (var service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(fixture.source());
			assertFalse(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()));
			assertTrue(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			var durable = service.snapshot(fixture.source().clusterId()).orElseThrow();
			var parent = durable.processes().get(fixture.lease().sourceProcessId());
			var waiting = materialSourceState(durable, materialParent(parent, parent.kind(), ProcessState.WAITING),
				materialGoal(durable.goals().get(parent.goalId()), GoalState.WAITING), durable.generation() + 1);
			service.restoreSnapshot(RuntimeNbt.load(RuntimeNbt.save(waiting)).orElseThrow());
			assertTrue(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()));
			assertTrue(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			assertEquals(waiting, service.snapshot(waiting.clusterId()).orElseThrow(), "Exact replay remains read-only while waiting for ACK");
		}
	}

	@Test void durableChildAcknowledgementCannotAuthorizeWhileSourceWorkerOwnsTheSnapshot() throws Exception {
		var fixture = materialSource();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		var service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(fixture.source());
			assertTrue(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			assertTrue(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()));
			var before = service.snapshot(fixture.source().clusterId()).orElseThrow();
			assertTrue(service.publishFact(new WorldFact.PlayerCommand(before.clusterId(), before.epochId(),
				before.generation(), new WorldFact.Command.CancelGoal(fixture.lease().sourceGoalId()))));
			assertTrue(entered.await(3, TimeUnit.SECONDS), "Cancellation must actually reach the worker");
			assertTrue(service.workerInFlight(before.clusterId()));
			assertFalse(service.installRemoteChildRevision(fixture.lease(), fixture.revision()));
			assertFalse(service.remoteExpansionMaterialAuthorized(fixture.lease(), fixture.revision()),
				"The still-visible old ACK is not authority while cancellation is in flight");
			assertEquals(before, service.snapshot(before.clusterId()).orElseThrow());
		} finally {
			release.countDown(); service.close();
		}
	}

	private static ProcessRecord materialParent(ProcessRecord parent, ProcessKind kind, ProcessState state) {
		return new ProcessRecord(parent.processId(), parent.goalId(), parent.owningComputerId(), parent.owningSlot(),
			parent.owningComputer(), kind, state, state == ProcessState.WAITING ? WaitReason.PARENT_ACK : null,
			parent.generation(), parent.childRevisionStates());
	}

	private static GoalRecord materialGoal(GoalRecord goal, GoalState state) {
		return new GoalRecord(goal.goalId(), goal.owningClusterId(), goal.submission(), goal.deliveryRoute(),
			goal.policy(), state, state == GoalState.WAITING ? WaitReason.PARENT_ACK : null);
	}

	private record MaterialSource(ClusterEngineState source, RemoteProcessLease lease,
		RemoteExpansionRevision revision) {}

	private static MaterialSource materialSource() {
		var source = dispatchable(id(28_000), id(28_001), id(28_002), id(28_003));
		var parent = source.processes().get(id(28_003));
		var iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		var request = RemoteExpansionRequest.capture(RemoteExpansionTestRegistries.provider(),
			parent.goalId(), parent.processId(), parent.generation(), source.generation(), id(28_004), 2,
			id(28_005), 0, InputMode.BULK, 2, List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(28_006), "material-lifecycle"), Map.of(iron, 1L))), Map.of(iron, 1L));
		var lease = new RemoteProcessLease(id(28_007), parent.goalId(), parent.processId(), source.clusterId(),
			id(28_008), 1, 1, ProductionCapability.ALCHEMIST, request, source.generation());
		return new MaterialSource(source, lease, RemoteExpansionRevision.compute(request));
	}

	private static ClusterEngineState materialSourceState(ClusterEngineState source, ProcessRecord parent,
		GoalRecord goal, long generation) {
		return new ClusterEngineState(source.clusterId(), source.epochId(), generation,
			source.nextSubmissionSequence(), source.nextRouteSelectionSequence(), source.memberCursor(), 0,
			goal == null ? Map.of() : Map.of(goal.goalId(), goal),
			goal == null ? Map.of() : source.submissionSequences(), goal == null ? Map.of() : source.requestedKinds(),
			parent == null ? Map.of() : Map.of(parent.processId(), parent), source.members(), source.pendingIntents(),
			source.uncertainIntentIds(), 0, null, source.routeCursor(), source.coordinationAssociations(),
			source.priorityLeaseAssociations());
	}

	@Test void sameClusterPureExpansionUsesAPinnedAlchemistProcessWithoutARadioListener()
		throws Exception {
		UUID cluster = id(20_500), epoch = id(20_501), sourceMember = id(20_502);
		UUID receiverMember = id(20_503), goalId = id(20_504), parentProcessId = id(20_505);
		UUID incapableSpareMember = id(20_511);
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0)
			.orElseThrow();
		ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0)
			.orElseThrow();
		ProductionResourceProfile incapableSpareProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(20_506), 1, InputMode.BULK),
			new CreateRouteKey(id(20_507), "local-expansion"), new PolicySnapshot(1, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord parent = new ProcessRecord(parentProcessId, goalId, sourceMember, 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 3, Map.of());
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 4, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(parentProcessId, parent), List.of(
				new ClusterEngineState.MemberSnapshot(sourceMember, sourceProfile, true, 5, 5),
				new ClusterEngineState.MemberSnapshot(receiverMember, receiverProfile, true, 6, 6),
				new ClusterEngineState.MemberSnapshot(incapableSpareMember,
					incapableSpareProfile, true, 7, 7)),
			Map.of(), Set.of(), 0, null);
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, parentProcessId, parent.generation(),
			state.generation(), goal.submission().patternId(), 2, id(20_508), 1, InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(20_509), "local-input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
		RemoteProcessLease lease = new RemoteProcessLease(id(20_510), goalId, parentProcessId,
			cluster, cluster, 5, 6, ProductionCapability.ALCHEMIST, request, state.generation());

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(state);
			FactoryRuntimeService.RemoteComputationAllocation allocation = service
				.acquireRemoteComputationProcess(lease, receiverMember).orElseThrow();
			ProcessRecord child = service.snapshot(cluster).orElseThrow().processes()
				.get(allocation.processId());
			assertEquals(ProcessKind.PURE_EXPANSION, child.kind(),
				"same-Cluster work needs a dedicated lifecycle kind, not an ordinary Goal");
			assertTrue(child.kind().requiresAlchemist());
			assertEquals(goalId, child.goalId());
			assertEquals(0, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.REMOTE_LISTENER).count());
			FactoryComputeWorker.RemotePureWork work = new FactoryComputeWorker.RemotePureWork(
				lease.leaseId(), cluster, receiverMember, allocation.processId(), allocation.owningSlot(),
				lease.receiverGeneration(), lease.revisionFence(), lease.requiredCapability(), request,
				FactoryComputeWorker.RemotePureStage.REVISION, Optional.empty());
			assertTrue(service.submitRemotePureWork(work));
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			Optional<FactoryComputeWorker.RemotePureResult> result = Optional.empty();
			while (result.isEmpty() && System.nanoTime() < deadline) {
				result = service.drainRemotePureResult(work);
				if (result.isEmpty()) Thread.sleep(2);
			}
			assertTrue(result.isPresent());

			ClusterEngine engine = new ClusterEngine();
			ClusterEngineState allocated = service.snapshot(cluster).orElseThrow();
			ClusterEngineState receiverUnloaded = engine.advance(allocated, List.of(
				new WorldFact.MemberLoadState(cluster, epoch, allocated.generation(),
					receiverMember, receiverProfile, false, 6, 8)), CONFIG,
				Long.MAX_VALUE).state();
			receiverUnloaded = engine.advance(receiverUnloaded, List.of(), CONFIG,
				Long.MAX_VALUE).state();
			ProcessRecord pinned = receiverUnloaded.processes().get(allocation.processId());
			assertEquals(receiverMember, pinned.owningComputerId(),
				"an exact local allocation must not migrate to a different Computer");
			assertEquals(allocation.owningSlot(), pinned.owningSlot());
			assertEquals(lease.receiverGeneration(), pinned.generation());
			assertEquals(ProcessState.WAITING, pinned.state());
			assertEquals(WaitReason.PROCESS, pinned.waitReason());
			assertFalse(pinned.owningComputerId().equals(incapableSpareMember),
				"owner loss must not silently move Alchemist work onto an incapable spare");

			service.restoreSnapshot(receiverUnloaded);
			assertTrue(service.releaseRemoteComputationProcess(allocation.processId(), lease,
				receiverMember, allocation.owningSlot()),
				"receiver unload must leave the exact durable allocation releasable");
			assertFalse(service.snapshot(cluster).orElseThrow().processes()
				.containsKey(allocation.processId()), "termination must not leak a stale slot");

			ClusterEngineState released = service.snapshot(cluster).orElseThrow();
			ClusterEngineState receiverReloaded = engine.advance(released, List.of(
				new WorldFact.MemberLoadState(cluster, epoch, released.generation(),
					receiverMember, receiverProfile, true, 6, 9)), CONFIG,
				Long.MAX_VALUE).state();
			service.restoreSnapshot(receiverReloaded);
			FactoryRuntimeService.RemoteComputationAllocation recovered = service
				.acquireRemoteComputationProcess(lease, receiverMember).orElseThrow();
			assertEquals(allocation, recovered,
				"a retry after receiver recovery must reacquire one deterministic allocation");
			assertEquals(2, service.snapshot(cluster).orElseThrow().processes().size(),
				"recovery must retain only the source and one pure-expansion process");
			assertTrue(service.releaseRemoteComputationProcess(recovered.processId(), lease,
				receiverMember, recovered.owningSlot()));
			assertEquals(Set.of(parentProcessId),
				service.snapshot(cluster).orElseThrow().processes().keySet());
		}
	}

	@Test
	void serialExactRemoteExpansionsReuseOneReloadableSourceChildBeyondTheCodecBound() {
		UUID sourceCluster = id(290), receiverCluster = id(291), epoch = id(292);
		UUID sourceComputer = id(293), sourceProcessId = id(294), patternId = id(295);
		ClusterEngineState source = dispatchable(sourceCluster, epoch, sourceComputer,
			sourceProcessId);
		ProcessRecord sourceProcess = source.processes().get(sourceProcessId);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		CreateRouteKey input = new CreateRouteKey(id(296), "remote-exact-input");
		ClusterEngineState durable;

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(source);
			for (int run = 0; run < WorldFact.MAX_LIST_ENTRIES + 1; run++) {
				long revisionFence = Math.addExact(source.generation(), run + 1L);
				RemoteExpansionRequest request = RemoteExpansionRequest.capture(
					RemoteExpansionTestRegistries.provider(),
					sourceProcess.goalId(), sourceProcessId, sourceProcess.generation(),
					revisionFence, patternId, 2, new UUID(29, run + 1L), revisionFence,
					InputMode.EXACT, 1, List.of(new RemoteExpansionRequest.StageInput(0,
						input, Map.of(iron, 1L))), Map.of(iron, 1L));
				RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
				RemoteProcessLease lease = new RemoteProcessLease(new UUID(30, run + 1L),
					sourceProcess.goalId(), sourceProcessId, sourceCluster, receiverCluster,
					4, 5, ProductionCapability.ALCHEMIST, request, revisionFence);
				assertTrue(service.installRemoteChildRevision(lease, revision),
					"serial EXACT revision " + run + " must advance the same source lineage");
				assertTrue(service.remoteExpansionMaterialAuthorized(lease, revision));
			}
			durable = service.snapshot(sourceCluster).orElseThrow();
			assertEquals(1, durable.processes().get(sourceProcessId)
				.childRevisionStates().size(),
				"serial EXACT Runs must not append one permanent child per Run");
		}

		ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(durable)).orElseThrow();
		assertEquals(1, reloaded.processes().get(sourceProcessId).childRevisionStates().size(),
			"the genuine current-schema RuntimeNbt round trip must remain bounded");
	}

	@Test
	void serialExactRemoteExpansionsSurviveRealProcessMigrationBeyondTheCodecBound() {
		UUID sourceCluster = id(297), receiverCluster = id(298), epoch = id(299);
		UUID computerA = id(3000), computerB = id(3001), sourceProcessId = id(3002);
		UUID patternId = id(3003);
		ClusterEngine engine = new ClusterEngine(() -> 0L);
		ClusterEngineState state = dispatchable(sourceCluster, epoch, computerA,
			sourceProcessId);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		CreateRouteKey input = new CreateRouteKey(id(3004), "migrating_input");
		RemoteProcessLease staleLease = null;
		RemoteExpansionRevision staleRevision = null;

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			for (int run = 0; run < WorldFact.MAX_LIST_ENTRIES + 1; run++) {
				service.restoreSnapshot(state);
				if (staleLease != null)
					assertFalse(service.installRemoteChildRevision(staleLease, staleRevision),
						"a revision fenced to the pre-migration Process must remain stale");
				ProcessRecord parent = service.snapshot(sourceCluster).orElseThrow()
					.processes().get(sourceProcessId);
				long revisionFence = service.snapshot(sourceCluster).orElseThrow().generation();
				RemoteExpansionRequest request = RemoteExpansionRequest.capture(
					RemoteExpansionTestRegistries.provider(), parent.goalId(),
					sourceProcessId, parent.generation(), revisionFence, patternId, 2,
					new UUID(31, run + 1L), revisionFence, InputMode.EXACT, 1,
					List.of(new RemoteExpansionRequest.StageInput(0, input,
						Map.of(iron, 1L))), Map.of(iron, 1L));
				RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
				RemoteProcessLease lease = new RemoteProcessLease(new UUID(32, run + 1L),
					parent.goalId(), sourceProcessId, sourceCluster, receiverCluster, 4, 5,
					ProductionCapability.ALCHEMIST, request, revisionFence);
				assertTrue(service.installRemoteChildRevision(lease, revision));
				assertTrue(service.remoteExpansionMaterialAuthorized(lease, revision));
				state = service.snapshot(sourceCluster).orElseThrow();
				assertEquals(1, state.processes().get(sourceProcessId)
					.childRevisionStates().size());

				if (run == WorldFact.MAX_LIST_ENTRIES) break;
				staleLease = lease;
				staleRevision = revision;
				ProcessRecord beforeMigration = state.processes().get(sourceProcessId);
				UUID currentOwner = beforeMigration.owningComputerId();
				UUID nextOwner = currentOwner.equals(computerA) ? computerB : computerA;
				ProductionResourceProfile profile = beforeMigration.owningComputer();
				state = engine.advance(state, List.of(new WorldFact.MemberLoadState(
					sourceCluster, epoch, state.generation(), nextOwner, profile, true,
					state.generation() + 1, state.generation() + 1)), CONFIG,
					Long.MAX_VALUE).state();
				state = engine.advance(state, List.of(new WorldFact.MemberLoadState(
					sourceCluster, epoch, state.generation(), currentOwner, profile, false,
					state.generation() + 1, state.generation() + 1)), CONFIG,
					Long.MAX_VALUE).state();
				state = engine.advance(state, List.of(), CONFIG, Long.MAX_VALUE).state();
				ProcessRecord migrated = state.processes().get(sourceProcessId);
				assertEquals(nextOwner, migrated.owningComputerId());
				assertEquals(beforeMigration.generation() + 1, migrated.generation());
				assertEquals(1, migrated.childRevisionStates().size(),
					"ClusterEngine migration must preserve one logical child lineage");
			}
		}

		ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(state)).orElseThrow();
		assertEquals(1, reloaded.processes().get(sourceProcessId).childRevisionStates().size(),
			"257 migrated EXACT Runs must remain inside the real RuntimeNbt child bound");
	}

	@Test void serverEnqueueAdvancesImmutableLibraryCatalogOnWorkerAndDrainsReply() throws Exception {
		UUID cluster = id(300), epoch = id(301), library = id(302), queryId = id(303);
		PatternQuery query = new PatternQuery(queryId, id(304), id(305), library,
			new StackKey(new ItemStack(Items.STONE)), 7, 0, 8, 2, 11, id(306));
		PatternRecord pattern = new PatternRecord(id(307), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0), List.of(),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.STONE)), 1)),
			new StandardPatternBody("worker_route"), java.util.OptionalInt.empty());
		ProductionRecipeCatalog catalog = new ProductionRecipeCatalog(library, 2, 7, true, List.of(pattern));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			service.publishLibraryCatalog(catalog);
			assertTrue(service.enqueueLibraryQuery(cluster, epoch, query));
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			List<PatternReply> replies = new ArrayList<>();
			while (replies.size() < 2 && System.nanoTime() < deadline) {
				replies.addAll(service.drainLibraryReplies(cluster, 8));
				if (replies.size() < 2) Thread.sleep(5);
			}
			assertEquals(List.of(PatternReply.Status.MATCH_BATCH, PatternReply.Status.GROUP_END),
				replies.stream().map(PatternReply::status).toList());
			assertEquals(query.attemptId(), replies.getFirst().attemptId());
		}
	}

	@Test void libraryWorkloadDiagnosticsTracksRealActiveAndReadyWorkWithoutConsumingIt()
		throws Exception {
		UUID cluster = id(30_300), epoch = id(30_301), library = id(30_302);
		PatternQuery query = new PatternQuery(id(30_303), id(30_304), id(30_305), library,
			new StackKey(new ItemStack(Items.DIRT)), 7, 0, 8, 2, 11, id(30_306));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker)) {
			service.start();
			service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			service.publishLibraryCatalog(new ProductionRecipeCatalog(library, 2, 7, true, List.of()));
			assertTrue(service.enqueueLibraryQuery(cluster, epoch, query));
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			var active = new FactoryRuntimeService.LibraryWorkloadDiagnostics(1, 0);
			assertEquals(active, service.libraryWorkloadDiagnostics(cluster));
			assertEquals(active, service.libraryWorkloadDiagnostics(cluster),
				"read-only diagnostics must not pump or consume the in-flight query");

			release.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!service.libraryWorkloadDiagnostics(cluster).equals(
				new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1))
				&& System.nanoTime() < deadline) {
				service.snapshot(cluster);
				Thread.sleep(5);
			}
			var ready = new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1);
			assertEquals(ready, service.libraryWorkloadDiagnostics(cluster));
			assertEquals(ready, service.libraryWorkloadDiagnostics(cluster),
				"read-only diagnostics must not consume the ready reply");
			assertEquals(1, service.drainLibraryReplies(cluster, 8).size());
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 0),
				service.libraryWorkloadDiagnostics(cluster));
			assertTrue(service.enqueueLibraryQuery(cluster, epoch, query));
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!service.libraryWorkloadDiagnostics(cluster).equals(
				new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1))
				&& System.nanoTime() < deadline) {
				service.snapshot(cluster);
				Thread.sleep(5);
			}
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1),
				service.libraryWorkloadDiagnostics(cluster));
			while (service.workerInFlight(cluster) && System.nanoTime() < deadline)
				Thread.sleep(5);
			assertFalse(service.workerInFlight(cluster));
			service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 0),
				service.libraryWorkloadDiagnostics(cluster),
				"runtime state replacement must clear queued diagnostic workload");
		}
	}

	@Test void libraryWorkloadDiagnosticsClearOnRuntimeStateReplacementAndClose() throws Exception {
		UUID cluster = id(30_310), epoch = id(30_311), library = id(30_312);
		PatternQuery query = new PatternQuery(id(30_313), id(30_314), id(30_315), library,
			new StackKey(new ItemStack(Items.DIRT)), 7, 0, 8, 2, 11, id(30_316));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			service.publishLibraryCatalog(new ProductionRecipeCatalog(library, 2, 7, true, List.of()));
			assertTrue(service.enqueueLibraryQuery(cluster, epoch, query));
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(1, service.libraryWorkloadDiagnostics(cluster).activeQueries());
			release.countDown();
			service.close();
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 0),
				service.libraryWorkloadDiagnostics(cluster));
		} finally {
			release.countDown();
			service.close();
		}
	}

	@Test void remotePureCompletionDoesNotClearConcurrentLibraryWorkload() throws Exception {
		UUID cluster = id(30_330), epoch = id(30_331), member = id(30_332);
		UUID library = id(30_333);
		RemoteProcessLease lease = remoteReceiverLease(30_340, cluster, 0);
		PatternQuery query = new PatternQuery(id(30_334), id(30_335), id(30_336), library,
			new StackKey(new ItemStack(Items.DIRT)), 7, 0, 8, 2, 11, id(30_337));
		CountDownLatch remoteEntered = new CountDownLatch(1);
		CountDownLatch releaseRemote = new CountDownLatch(1);
		CountDownLatch libraryEntered = new CountDownLatch(1);
		CountDownLatch releaseLibrary = new CountDownLatch(1);
		AtomicInteger transitions = new AtomicInteger();
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			CountDownLatch entered = transitions.incrementAndGet() == 1 ? remoteEntered : libraryEntered;
			CountDownLatch release = entered == remoteEntered ? releaseRemote : releaseLibrary;
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker)) {
			service.start();
			service.restoreSnapshot(remoteReceiverState(cluster, epoch, member));
			FactoryRuntimeService.RemoteComputationAllocation allocation = service
				.acquireRemoteComputationProcess(lease, member).orElseThrow();
			FactoryComputeWorker.RemotePureWork remote = new FactoryComputeWorker.RemotePureWork(
				lease.leaseId(), cluster, member, allocation.processId(), allocation.owningSlot(),
				lease.receiverGeneration(), lease.revisionFence(), lease.requiredCapability(),
				lease.expansionRequest(), FactoryComputeWorker.RemotePureStage.REVISION, Optional.empty());
			assertTrue(service.submitRemotePureWork(remote));
			assertTrue(remoteEntered.await(3, TimeUnit.SECONDS));

			service.publishLibraryCatalog(new ProductionRecipeCatalog(library, 2, 7, true, List.of()));
			assertTrue(service.enqueueLibraryQuery(cluster, epoch, query));
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(1, 0),
				service.libraryWorkloadDiagnostics(cluster));

			releaseRemote.countDown();
			assertTrue(libraryEntered.await(3, TimeUnit.SECONDS));
			assertTrue(service.drainRemotePureResult(remote).isPresent(),
				"the earlier same-Cluster remote-pure result must be observed first");
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(1, 0),
				service.libraryWorkloadDiagnostics(cluster),
				"remote-pure completion must not clear the still-blocked Library marker");

			releaseLibrary.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!service.libraryWorkloadDiagnostics(cluster).equals(
				new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1))
				&& System.nanoTime() < deadline) {
				service.snapshot(cluster);
				Thread.sleep(5);
			}
			assertEquals(new FactoryRuntimeService.LibraryWorkloadDiagnostics(0, 1),
				service.libraryWorkloadDiagnostics(cluster));
		} finally {
			releaseRemote.countDown();
			releaseLibrary.countDown();
		}
	}

	@Test void nonPumpingPanelPeekPreservesARealPendingDispatch() throws Exception {
		UUID cluster = id(30_320), epoch = id(30_321), computer = id(30_322);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithComputer(cluster, epoch, computer));
			var reservation = service.reserveExternalListenerProcess(cluster, epoch, computer,
				id(30_323)).orElseThrow();
			ClusterEngineState process = service.snapshot(cluster).orElseThrow();
			assertFalse(process.processes().isEmpty());
			assertEquals(process, service.peekSnapshotForPanelView(cluster).orElseThrow());
			assertTrue(service.publishFact(new WorldFact.PlayerCommand(cluster, epoch,
				process.generation(), new WorldFact.Command.DispatchCreate(reservation.processId(),
					id(30_324), new CreateRouteKey(id(30_325), "browser-peek-pending"), List.of(
						new OrderedIngredient(new StackKey(new ItemStack(Items.STONE)), 1))))));
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			ClusterEngineState pending = service.snapshot(cluster).orElseThrow();
			while (pending.pendingIntents().isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(5);
				pending = service.snapshot(cluster).orElseThrow();
			}
			assertFalse(pending.pendingIntents().isEmpty());
			assertEquals(pending, service.peekSnapshotForPanelView(cluster).orElseThrow(),
				"a read-only panel/browser projection must not consume the pending intent");
		}
	}

	@Test void productionSelectionUsesBoundedWorkerTraversalBeforeReturningItsPatternIdentity()
		throws Exception {
		UUID library = id(330), network = id(331), cluster = id(333);
		PatternRecord pattern = new PatternRecord(id(332), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, 0), List.of(),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.STONE)), 2)),
			new StandardPatternBody("real_pattern_input"), java.util.OptionalInt.of(4));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithComputer(cluster, id(329), id(328)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 5,
				new ProductionRecipeCatalog(library, 5, 9, true, List.of(pattern)));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty(),
				"production admission must not eagerly traverse a complete Library on the server thread");
			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service, cluster,
				network, requested);
			assertEquals(library, selected.libraryId());
			assertEquals(pattern.patternId(), selected.pattern().patternId());
			assertTrue(service.selectProductionPattern(cluster, network,
				new StackKey(new ItemStack(Items.DIRT))).isEmpty());
			assertTrue(service.selectProductionPattern(id(334), network,
				new StackKey(new ItemStack(Items.STONE))).isEmpty(),
				"another Cluster must not select this Library");
		}
	}

	@Test void productionLibraryWaveOccupiesRealQuerySlotsAndIssuesOnlyTheAvailableCapacity()
		throws Exception {
		UUID cluster = id(20_000), epoch = id(20_001), computer = id(20_002);
		UUID network = id(20_003);
		CountDownLatch enteredFirstQuery = new CountDownLatch(1);
		CountDownLatch releaseFirstQuery = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredFirstQuery.countDown();
			try { releaseFirstQuery.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0).orElseThrow())));
			for (int index = 0; index < 3; index++) {
				UUID library = id(20_010 + index);
				service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
					new SpaceAddress(Level.OVERWORLD, library, new BlockPos(index, 64, 0)),
					LibraryRegistration.Reachability.REACHABLE,
					new ProductionRecipeCatalog(library, 1, 1, true, List.of(
						pattern(id(20_020 + index), library, Items.STONE, OptionalInt.empty(),
							"bounded-query-" + index))));
			}
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));

			assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty());
			assertTrue(enteredFirstQuery.await(3, TimeUnit.SECONDS));
			ClusterEngineState active = service.snapshot(cluster).orElseThrow();
			assertEquals(1, active.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.LIBRARY_QUERY).count(),
				"one physical slot may own only one active Library query");
			assertEquals(1, active.processes().size(),
				"the worker queue is not a substitute for Computer process slots");
		} finally {
			releaseFirstQuery.countDown();
			service.close();
		}
	}

	@Test void productionLibraryWaveReusesTwoPhysicalSlotsToFinishAThreeLibraryGroup()
		throws Exception {
		UUID cluster = id(20_050), epoch = id(20_051), computer = id(20_052), network = id(20_053);
		CountDownLatch enteredFirstUnit = new CountDownLatch(1);
		CountDownLatch releaseFirstUnit = new CountDownLatch(1);
		AtomicInteger units = new AtomicInteger();
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			if (units.incrementAndGet() != 1) return;
			enteredFirstUnit.countDown();
			try { releaseFirstUnit.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED, 1, 0).orElseThrow())));
			for (int index = 0; index < 3; index++) {
				UUID library = id(20_060 + index);
				service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
					new SpaceAddress(Level.OVERWORLD, library, new BlockPos(index, 64, 0)),
					LibraryRegistration.Reachability.REACHABLE,
					new ProductionRecipeCatalog(library, 1, 1, true, List.of(pattern(
						id(20_070 + index), library, Items.STONE, OptionalInt.empty(),
						"two-slot-wave-" + index))));
			}
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty());
			assertTrue(enteredFirstUnit.await(3, TimeUnit.SECONDS));
			assertEquals(2, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.LIBRARY_QUERY).count());
			releaseFirstUnit.countDown();
			assertNotNull(awaitProductionSelection(service, cluster, network, requested));
			assertTrue(units.get() >= 6, "all three Libraries must produce a match batch and GROUP_END");
			assertEquals(0, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.LIBRARY_QUERY).count(),
				"every progressively reused query slot must be released at group close");
		} finally {
			releaseFirstUnit.countDown();
			service.close();
		}
	}

	@Test void productionSearchQueriesReachableCrossClusterLibraryWithQueryAndListenerProcesses()
		throws Exception {
		UUID sourceCluster = id(20_100), sourceEpoch = id(20_101), sourceComputer = id(20_102);
		UUID ownerCluster = id(20_103), ownerEpoch = id(20_104), ownerComputer = id(20_105);
		UUID network = id(20_106), library = id(20_107);
		PatternRecord remote = pattern(id(20_108), library, Items.STONE, OptionalInt.empty(),
			"remote-library");
		CountDownLatch enteredQuery = new CountDownLatch(1);
		CountDownLatch releaseQuery = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredQuery.countDown();
			try { releaseQuery.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(stateWithComputer(sourceCluster, sourceEpoch, sourceComputer));
			service.restoreSnapshot(stateWithComputer(ownerCluster, ownerEpoch, ownerComputer));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			service.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				new SpaceAddress(Level.OVERWORLD, library, BlockPos.ZERO),
				LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(remote)));

			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			assertTrue(service.selectProductionPattern(sourceCluster, network, requested).isEmpty());
			assertTrue(enteredQuery.await(3, TimeUnit.SECONDS),
				"a frequency-reachable peer Library must enter the production wave");
			ClusterEngineState active = service.snapshot(sourceCluster).orElseThrow();
			assertEquals(1, active.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.LIBRARY_QUERY).count());
			assertEquals(1, active.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.REMOTE_LISTENER).count());
		} finally {
			releaseQuery.countDown();
			service.close();
		}
	}

	@Test void knownCrossClusterLibraryWithFrequencyMismatchWaitsInsteadOfDisappearing() {
		UUID sourceCluster = id(20_200), sourceEpoch = id(20_201), sourceComputer = id(20_202);
		UUID ownerCluster = id(20_203), network = id(20_204), library = id(20_205);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(sourceCluster, sourceEpoch,
				sourceComputer));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("18"));
			service.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				new SpaceAddress(Level.OVERWORLD, library, BlockPos.ZERO),
				LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(
					pattern(id(20_206), library, Items.STONE, OptionalInt.empty(), "mismatch"))));

			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			assertTrue(service.selectProductionPattern(sourceCluster, network, requested).isEmpty());
			assertEquals(FactoryRuntimeService.ProductionSearchStatus.WAITING_LIBRARY,
				service.productionSearchSnapshot(sourceCluster, network, requested).orElseThrow().status());
			assertTrue(service.snapshot(sourceCluster).orElseThrow().processes().isEmpty(),
				"an unavailable route waits without consuming a live query process");
		}
	}

	@Test void firstProductionSelectionActivatesFinePatternPriorityWithAnOwnerAlchemistLease()
		throws Exception {
		UUID cluster = id(20_300), epoch = id(20_301), computer = id(20_302);
		UUID network = id(20_303), firstLibrary = id(20_304), overrideLibrary = id(20_305);
		PatternRecord stableFirst = pattern(id(20_306), firstLibrary, Items.STONE,
			OptionalInt.empty(), "stable-first");
		PatternRecord fineOverride = pattern(id(20_307), overrideLibrary, Items.STONE,
			OptionalInt.of(100), "fine-override");
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(stableFirst.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(firstLibrary, 1, 1, true, List.of(stableFirst)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(fineOverride.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(overrideLibrary, 1, 1, true, List.of(fineOverride)));

			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service, cluster,
				network, new StackKey(new ItemStack(Items.STONE)));
			assertEquals(fineOverride.patternId(), selected.pattern().patternId(),
				"the pre-selection owner lease must affect the very first arbitration");
			ClusterEngineState leased = service.snapshot(cluster).orElseThrow();
			ProcessRecord coordination = leased.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE).findFirst()
				.orElseThrow();
			assertTrue(coordination.owningComputer().supports(ProductionCapability.ALCHEMIST));
			assertEquals(fineOverride.patternId(), leased.coordinationAssociations()
				.get(coordination.processId()).patternId());
		}
	}

	@Test void finePriorityLeaseMigratesOnlyToALoadedAlchemistAndReloadKeepsAuthority()
		throws Exception {
		UUID sourceCluster = id(20_308), sourceEpoch = id(20_309), sourceComputer = id(20_310);
		UUID ownerCluster = id(20_311), ownerEpoch = id(20_312), firstAlchemist = id(20_313);
		UUID ordinaryComputer = id(20_314), backupAlchemist = id(20_315);
		UUID network = id(20_316), library = id(20_317);
		PatternRecord fine = pattern(id(20_318), library, Items.STONE, OptionalInt.of(100),
			"migrating-fine-priority");
		ProductionResourceProfile cleric = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0)
			.orElseThrow();
		ProductionResourceProfile librarian = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0)
			.orElseThrow();
		ClusterEngineState migrated;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(sourceCluster, sourceEpoch, Map.of(sourceComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			service.restoreSnapshot(stateWithMembers(ownerCluster, ownerEpoch, Map.of(
				firstAlchemist, cleric, ordinaryComputer, librarian, backupAlchemist, cleric)));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			service.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(fine.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(fine)));

			assertEquals(fine.patternId(), awaitProductionSelection(service, sourceCluster, network,
				new StackKey(new ItemStack(Items.STONE))).pattern().patternId());
			ClusterEngineState leased = service.snapshot(ownerCluster).orElseThrow();
			ProcessRecord priority = leased.processes().values().stream()
				.filter(process -> leased.coordinationAssociations().containsKey(process.processId()))
				.findFirst().orElseThrow();
			assertTrue(priority.kind().requiresAlchemist(),
				"the durable kind, not an allocation-only flag, must preserve the capability contract");
			ClusterEngineState.MemberSnapshot first = leased.members().stream()
				.filter(member -> member.computerId().equals(firstAlchemist)).findFirst().orElseThrow();
			assertTrue(service.publishFact(new WorldFact.MemberLoadState(ownerCluster, ownerEpoch,
				leased.generation(), firstAlchemist, first.profile(), false,
				first.routeGeneration() + 1, first.fence() + 1)));

			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			do {
				migrated = service.snapshot(ownerCluster).orElseThrow();
				priority = migrated.processes().get(priority.processId());
				if (priority != null && priority.owningComputerId().equals(backupAlchemist)
					&& priority.state() == ProcessState.RUNNABLE) break;
				Thread.sleep(2);
			} while (System.nanoTime() < deadline);
			assertEquals(backupAlchemist, priority.owningComputerId(),
				"migration must skip an ordinary Computer even when it is first in the member cursor");
			assertEquals(ProcessState.RUNNABLE, priority.state());
			assertTrue(service.coordinationSnapshot(ownerCluster)
				.authorizes(ownerCluster, library, fine.patternId(), 1));
		}

		ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(migrated)).orElseThrow();
		assertTrue(PatternPriorityAuthority.fromAuthoritativeState(reloaded)
			.authorizes(ownerCluster, library, fine.patternId(), 1),
			"reload must retain only the migrated resident Alchemist authority");
	}

	@Test void waitingFinePriorityLeaseIsInactiveAndReacquiresWhenAlchemistCapacityReturns()
		throws Exception {
		UUID sourceCluster = id(20_319), sourceEpoch = id(20_319_1), sourceComputer = id(20_319_2);
		UUID ownerCluster = id(20_319_3), ownerEpoch = id(20_319_4), ownerComputer = id(20_319_5);
		UUID network = id(20_319_6), stableLibrary = id(20_319_7), fineLibrary = id(20_319_8);
		PatternRecord stable = pattern(id(20_319_9), stableLibrary, Items.STONE,
			OptionalInt.empty(), "waiting-priority-stable");
		PatternRecord fine = pattern(id(20_319_10), fineLibrary, Items.STONE,
			OptionalInt.of(100), "waiting-priority-fine");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		AtomicBoolean holdSource = new AtomicBoolean();
		AtomicBoolean unloadedOwner = new AtomicBoolean();
		CountDownLatch sourceEntered = new CountDownLatch(1);
		CountDownLatch releaseSource = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, clusterId -> {
			if (!holdSource.get() || !clusterId.equals(sourceCluster)) return;
			sourceEntered.countDown();
			try { releaseSource.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		AtomicReference<FactoryRuntimeService> serviceRef = new AtomicReference<>();
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		serviceRef.set(service);
		try {
			service.start();
			service.restoreSnapshot(stateWithMembers(sourceCluster, sourceEpoch, Map.of(sourceComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			service.restoreSnapshot(stateWithMembers(ownerCluster, ownerEpoch, Map.of(ownerComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			service.setProductionRouteCollector(request -> {
				if (unloadedOwner.compareAndSet(false, true)) {
					ClusterEngineState owner = serviceRef.get().snapshot(ownerCluster).orElseThrow();
					ClusterEngineState.MemberSnapshot member = owner.members().getFirst();
					assertTrue(serviceRef.get().publishFact(new WorldFact.MemberLoadState(ownerCluster,
						ownerEpoch, owner.generation(), member.computerId(), member.profile(), false,
						member.routeGeneration() + 1, member.fence() + 1)));
					long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
					boolean waiting = false;
					while (System.nanoTime() < deadline) {
						ClusterEngineState current = serviceRef.get().snapshot(ownerCluster).orElseThrow();
						waiting = current.processes().values().stream().anyMatch(process ->
							current.coordinationAssociations().containsKey(process.processId())
								&& process.state() == ProcessState.WAITING
								&& process.waitReason() == WaitReason.PROCESS);
						if (waiting) break;
						try { Thread.sleep(2); }
						catch (InterruptedException interrupted) {
							Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
						}
					}
					assertTrue(waiting, "the owner unload must durably park the admission lease");
					holdSource.set(true);
					ClusterEngineState source = serviceRef.get().snapshot(sourceCluster).orElseThrow();
					assertTrue(serviceRef.get().publishFact(new WorldFact.Signal(sourceCluster,
						sourceEpoch, source.generation(), "hold-waiting-priority-route", 1)));
					try { assertTrue(sourceEntered.await(3, TimeUnit.SECONDS)); }
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
					}
				}
				return new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of());
			});
			service.publishProductionLibraryAccess(sourceCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(stable.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(stableLibrary, 1, 1, true, List.of(stable)));
			service.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(fine.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(fineLibrary, 1, 1, true, List.of(fine)));

			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (sourceEntered.getCount() != 0 && System.nanoTime() < deadline) {
				assertTrue(service.selectProductionPattern(sourceCluster, network, requested).isEmpty());
				Thread.sleep(2);
			}
			assertTrue(sourceEntered.await(1, TimeUnit.SECONDS));
			assertFalse(service.coordinationSnapshot(ownerCluster)
				.authorizes(ownerCluster, fineLibrary, fine.patternId(), 1),
				"WAIT(PROCESS) on an unloaded owner is not live Alchemist authority");

			assertTrue(service.selectProductionPattern(sourceCluster, network, requested).isEmpty(),
				"the frozen route may not complete while its source Cluster is in flight");
			ClusterEngineState owner = service.snapshot(ownerCluster).orElseThrow();
			ClusterEngineState.MemberSnapshot member = owner.members().getFirst();
			assertTrue(service.publishFact(new WorldFact.MemberLoadState(ownerCluster, ownerEpoch,
				owner.generation(), member.computerId(), member.profile(), true,
				member.routeGeneration() + 1, member.fence() + 1)));
			releaseSource.countDown();
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (service.snapshot(ownerCluster).orElseThrow().members().stream()
				.noneMatch(candidate -> candidate.computerId().equals(member.computerId())
					&& candidate.loaded()) && System.nanoTime() < deadline) Thread.sleep(2);
			assertTrue(service.snapshot(ownerCluster).orElseThrow().members().stream()
				.anyMatch(candidate -> candidate.computerId().equals(member.computerId())
					&& candidate.loaded()));
			while (!service.coordinationSnapshot(ownerCluster)
				.authorizes(ownerCluster, fineLibrary, fine.patternId(), 1)
				&& System.nanoTime() < deadline) {
				service.selectProductionPattern(sourceCluster, network, requested);
				Thread.sleep(2);
			}
			assertTrue(service.coordinationSnapshot(ownerCluster)
				.authorizes(ownerCluster, fineLibrary, fine.patternId(), 1),
				() -> "the same admission must retry its fine-priority authority after capacity returns: owner="
					+ service.snapshot(ownerCluster) + " search=" + service.productionSearchSnapshot(
						sourceCluster, network, requested));

			assertEquals(fine.patternId(), awaitProductionSelection(service, sourceCluster, network,
				requested).pattern().patternId());
		} finally {
			releaseSource.countDown();
			service.close();
		}
	}

	@Test void retainedSpecialProcessDoesNotSuppressRequestedGoalAllocation() throws Exception {
		UUID cluster = id(20_320), epoch = id(20_321), computer = id(20_322);
		UUID goalId = id(20_323), specialProcessId = id(20_324);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0)
			.orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(20_325), 1, InputMode.BULK),
			new CreateRouteKey(id(20_326), "ordinary-after-special"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord special = new ProcessRecord(specialProcessId, goalId, computer, 0, profile,
			ProcessKind.SPECULATIVE_EXPANSION, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(specialProcessId, special), List.of(new ClusterEngineState.MemberSnapshot(
				computer, profile, true, 1, 1)), Map.of(), Set.of(), 0, null);

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(state);
			assertTrue(service.publishFact(new WorldFact.MemberLoadState(cluster, epoch,
				state.generation(), computer, profile, true, 1, 1)));
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (service.snapshot(cluster).orElseThrow().processes().values().stream()
				.noneMatch(process -> process.goalId().equals(goalId)
					&& process.kind() == ProcessKind.GOAL)
				&& System.nanoTime() < deadline) Thread.sleep(2);
			assertTrue(service.snapshot(cluster).orElseThrow().processes().values().stream()
				.anyMatch(process -> process.goalId().equals(goalId)
					&& process.kind() == ProcessKind.GOAL),
				"only the requested ordinary process kind may satisfy Goal allocation");
		}
	}

	@Test void searchRetirementAtomicallyWakesAProcessCapacityWaiter() {
		UUID cluster = id(20_330), epoch = id(20_331), computer = id(20_332);
		UUID searchId = id(20_333), waitingGoal = id(20_334), network = id(20_335);
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(specialProcessStateWithWaiter(cluster, epoch,
				computer, searchId, waitingGoal));
			service.restoreProductionSearchSnapshot(new FactoryRuntimeService.ProductionSearchSnapshot(
				searchId, cluster, epoch, network, requested, 0,
				FactoryRuntimeService.ProductionSearchStatus.SEARCHING, Optional.empty()));

			assertTrue(service.retireProductionSearch(searchId));

			ClusterEngineState released = service.snapshot(cluster).orElseThrow();
			assertFalse(released.processes().values().stream()
				.anyMatch(process -> process.goalId().equals(searchId)));
			assertNotEquals(GoalState.WAITING, released.goals().get(waitingGoal).state(),
				"the slot release and WAIT(PROCESS) wake must publish as one state transition");
		}
	}

	@Test void adoptedGoalProcessReleaseAtomicallyWakesAProcessCapacityWaiter() {
		UUID cluster = id(20_340), epoch = id(20_341), computer = id(20_342);
		UUID adoptedGoal = id(20_343), waitingGoal = id(20_344);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(specialProcessStateWithWaiter(cluster, epoch,
				computer, adoptedGoal, waitingGoal));

			assertTrue(service.releaseProductionGoalProcesses(adoptedGoal));

			ClusterEngineState released = service.snapshot(cluster).orElseThrow();
			assertFalse(released.processes().values().stream()
				.anyMatch(process -> process.goalId().equals(adoptedGoal)));
			assertNotEquals(GoalState.WAITING, released.goals().get(waitingGoal).state(),
				"goal cleanup must not strand a capacity waiter after freeing its slot");
		}
	}

	@Test void inFlightSearchRetirementKeepsItsDurableCursorUntilCleanupCanCommit()
		throws Exception {
		UUID cluster = id(20_370), epoch = id(20_371), computer = id(20_372);
		UUID searchId = id(20_373), waitingGoal = id(20_374), network = id(20_375);
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(specialProcessStateWithWaiter(cluster, epoch,
				computer, searchId, waitingGoal));
			service.restoreProductionSearchSnapshot(new FactoryRuntimeService.ProductionSearchSnapshot(
				searchId, cluster, epoch, network, requested, 0,
				FactoryRuntimeService.ProductionSearchStatus.SEARCHING, Optional.empty()));
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 0,
				"hold-search-cleanup", 1)));
			assertTrue(entered.await(3, TimeUnit.SECONDS));

			assertFalse(service.retireProductionSearch(searchId),
				"retirement is not committed while an owning Cluster snapshot is in flight");
			assertEquals(searchId, service.productionSearchSnapshot(cluster, network, requested)
				.orElseThrow().searchId());
			assertTrue(service.snapshot(cluster).orElseThrow().processes().values().stream()
				.anyMatch(process -> process.goalId().equals(searchId)));

			release.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			boolean retired = false;
			while (!(retired = service.retireProductionSearch(searchId))
				&& System.nanoTime() < deadline) Thread.sleep(2);
			assertTrue(retired);
			assertTrue(service.productionSearchSnapshot(cluster, network, requested).isEmpty());
		} finally {
			release.countDown();
			service.close();
		}
	}

	@Test void searchRetirementCleansOrphanProcessesEvenWhenItsCursorWasAlreadyLost() {
		UUID cluster = id(20_376), epoch = id(20_377), computer = id(20_378);
		UUID searchId = id(20_379), waitingGoal = id(20_376_1);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(specialProcessStateWithWaiter(cluster, epoch,
				computer, searchId, waitingGoal));
			assertTrue(service.retireProductionSearch(searchId),
				"cursor absence must not suppress idempotent process cleanup after a split crash");
			ClusterEngineState released = service.snapshot(cluster).orElseThrow();
			assertFalse(released.processes().values().stream()
				.anyMatch(process -> process.goalId().equals(searchId)));
			assertNotEquals(GoalState.WAITING, released.goals().get(waitingGoal).state());
		}
	}

	@Test void uniqueFineWinnerAdoptionIsReplaySafeAndRejectsThirdPartyLineage()
		throws Exception {
		UUID cluster = id(20_380), epoch = id(20_381), computer = id(20_382);
		UUID network = id(20_383), stableLibrary = id(20_384), fineLibrary = id(20_385);
		UUID targetGoal = id(20_386), thirdPartyGoal = id(20_387);
		PatternRecord stable = pattern(id(20_388), stableLibrary, Items.STONE,
			OptionalInt.empty(), "adopt-stable");
		PatternRecord fine = pattern(id(20_389), fineLibrary, Items.STONE,
			OptionalInt.of(100), "adopt-fine");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		ClusterEngineState adoptedState;
		FactoryRuntimeService.ProductionSearchSnapshot durableSearch;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			publishFineAdoptionLibraries(service, cluster, network, stable, fine);
			assertEquals(fine.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId());
			durableSearch = service.productionSearchSnapshot(cluster, network, requested).orElseThrow();

			assertTrue(awaitProductionSearchAdoption(service, durableSearch.searchId(), targetGoal));
			adoptedState = service.snapshot(cluster).orElseThrow();
			ProcessRecord adopted = adoptedState.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE)
				.findFirst().orElseThrow();
			assertEquals(targetGoal, adopted.goalId(),
				"a sole fine-priority winner is still the retained winner");
			assertTrue(service.adoptProductionSearchWinner(durableSearch.searchId(), targetGoal),
				"replaying adoption after the ownership write must be idempotent");
		}

		try (FactoryRuntimeService replay = new FactoryRuntimeService(CONFIG)) {
			replay.start(); replay.restoreSnapshot(adoptedState);
			publishFineAdoptionLibraries(replay, cluster, network, stable, fine);
			replay.restoreProductionSearchSnapshot(durableSearch);
			assertTrue(replay.adoptProductionSearchWinner(durableSearch.searchId(), targetGoal),
				"a crash after runtime adoption but before the admission fence must replay");
		}

		ClusterEngineState thirdPartyState = withSpecialProcessGoal(adoptedState, thirdPartyGoal);
		try (FactoryRuntimeService replay = new FactoryRuntimeService(CONFIG)) {
			replay.start(); replay.restoreSnapshot(thirdPartyState);
			publishFineAdoptionLibraries(replay, cluster, network, stable, fine);
			replay.restoreProductionSearchSnapshot(durableSearch);
			assertFalse(replay.adoptProductionSearchWinner(durableSearch.searchId(), targetGoal),
				"an exact retained winner owned by an unrelated Goal is not adoptable");
		}
	}

	@Test void selectedSearchSurvivesFirstLoadedLibraryPublicationUntilPreparedAdmissionAdopts()
		throws Exception {
		UUID cluster = id(20_396), epoch = id(20_397), computer = id(20_398);
		UUID network = id(20_399), library = id(20_396_1), goal = id(20_396_2);
		PatternRecord fine = pattern(id(20_396_3), library, Items.STONE,
			OptionalInt.of(80), "prepared-reload-fine");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		ClusterEngineState selectedState;
		FactoryRuntimeService.ProductionSearchSnapshot durable;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 2, 0).orElseThrow())));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("7"), 10,
				worldAddress(fine.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(fine)));
			assertEquals(fine.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId());
			durable = service.productionSearchSnapshot(cluster, network, requested).orElseThrow();
			selectedState = service.snapshot(cluster).orElseThrow();
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(selectedState);
			restored.restoreProductionSearchSnapshot(durable);
			// This is the normal post-bootstrap transition from crash-demoted UNLOADED state to the
			// first live Core publication.  The PREPARED admission already owns the exact selection.
			restored.publishProductionLibraryAccess(cluster, Set.of(network), 2, CommunicationFrequency.of("7"), 10,
				worldAddress(fine.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(fine)));

			assertEquals(FactoryRuntimeService.ProductionSearchStatus.SELECTED,
				restored.productionSearchSnapshot(cluster, network, requested).orElseThrow().status(),
				"a live Library publication must not erase an already durably selected cursor");
			assertTrue(awaitProductionSearchAdoption(restored, durable.searchId(), goal),
				"PREPARED recovery must still adopt its exact pre-admission Alchemist lease");
		}
	}

	@Test void routePolicyWithoutWinnerCancelsItsAttemptAndRetriesAtANewGeneration()
		throws Exception {
		UUID cluster = id(20_390), epoch = id(20_391), computer = id(20_392);
		UUID network = id(20_393), library = id(20_394);
		PatternRecord pattern = pattern(id(20_395), library, Items.STONE, OptionalInt.empty(),
			"retry-after-busy-route");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		AtomicInteger collections = new AtomicInteger();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			service.setProductionRouteCollector(request -> {
				ProductionRouteTable.ProviderSource source = request.patternSources().getFirst();
				if (collections.getAndIncrement() == 0)
					source = new ProductionRouteTable.ProviderSource(source.scope(), source.libraryId(),
						source.patternId(), source.providerKey(), source.providerKind(),
						source.effectivePriority(), RouteCandidate.Availability.BUSY,
						RouteCandidate.Blocker.NONE);
				return new FactoryRuntimeService.ProductionRouteSources(List.of(source), List.of(),
					List.of());
			});
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(pattern.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(pattern)));

			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service, cluster,
				network, requested);

			assertEquals(pattern.patternId(), selected.pattern().patternId());
			assertTrue(collections.get() >= 2, "the BUSY attempt must create a fresh collection poll");
			FactoryRuntimeService.ProductionSearchSnapshot snapshot = service
				.productionSearchSnapshot(cluster, network, requested).orElseThrow();
			assertTrue(snapshot.searchGeneration() >= 1,
				"retrying route collection must advance the durable search generation");
			List<ProcessRecord> speculation = service.snapshot(cluster).orElseThrow().processes()
				.values().stream().filter(process ->
					process.kind() == ProcessKind.SPECULATIVE_EXPANSION).toList();
			assertEquals(1, speculation.size(),
				"the rejected generation's speculative branch must not leak into its retry");
			assertEquals(snapshot.searchGeneration(), speculation.getFirst().generation());
		}
	}

	@Test void remoteFinePriorityLeaseIsOwnedReloadedAdoptedAndReleasedByTheLibraryCluster()
		throws Exception {
		UUID sourceCluster = id(20_350), sourceEpoch = id(20_351), sourceComputer = id(20_352);
		UUID ownerCluster = id(20_353), ownerEpoch = id(20_354), ownerComputer = id(20_355);
		UUID network = id(20_356), localLibrary = id(20_357), remoteLibrary = id(20_358);
		PatternRecord local = pattern(id(20_359), localLibrary, Items.STONE, OptionalInt.empty(),
			"remote-priority-local");
		PatternRecord remote = pattern(id(20_360), remoteLibrary, Items.STONE, OptionalInt.of(100),
			"remote-priority-owner");
		ClusterEngineState durableSource;
		ClusterEngineState durableOwner;
		FactoryRuntimeService.ProductionSearchSnapshot durableSearch;
		FactoryRuntimeService.SelectedPattern selected;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithComputer(sourceCluster, sourceEpoch, sourceComputer));
			service.restoreSnapshot(stateWithMembers(ownerCluster, ownerEpoch, Map.of(ownerComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow())));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			service.publishProductionLibraryAccess(sourceCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(local.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(localLibrary, 1, 1, true, List.of(local)));
			service.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(remote.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(remoteLibrary, 1, 1, true, List.of(remote)));
			selected = awaitProductionSelection(service, sourceCluster, network,
				new StackKey(new ItemStack(Items.STONE)));
			assertEquals(remote.patternId(), selected.pattern().patternId());
			assertEquals(0, service.snapshot(sourceCluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE).count());
			assertEquals(1, service.snapshot(ownerCluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE).count());
			durableSource = RuntimeNbt.load(RuntimeNbt.save(service.snapshot(sourceCluster)
				.orElseThrow())).orElseThrow();
			durableOwner = RuntimeNbt.load(RuntimeNbt.save(service.snapshot(ownerCluster)
				.orElseThrow())).orElseThrow();
			durableSearch = service.productionSearchSnapshot(sourceCluster, network,
				new StackKey(new ItemStack(Items.STONE))).orElseThrow();
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(durableSource); restored.restoreSnapshot(durableOwner);
			restored.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			restored.publishProductionLibraryAccess(sourceCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(local.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(localLibrary, 1, 1, true, List.of(local)));
			restored.publishProductionLibraryAccess(ownerCluster, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(remote.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(remoteLibrary, 1, 1, true, List.of(remote)));
			restored.restoreProductionSearchSnapshot(durableSearch);
			assertEquals(remote.patternId(), restored.selectProductionPattern(sourceCluster, network,
				new StackKey(new ItemStack(Items.STONE))).orElseThrow().pattern().patternId());
			UUID goalId = id(20_361);
			assertTrue(restored.ensurePanelGoal(goalId, sourceCluster, sourceEpoch, remote.patternId(),
				new CreateRouteKey(network, "remote-priority-goal"), 1, InputMode.BULK, 0).isPresent());
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!restored.adoptProductionSearchWinner(durableSearch.searchId(), goalId)
				&& System.nanoTime() < deadline) Thread.sleep(2);
			assertTrue(restored.retireProductionSearch(durableSearch.searchId()));
			ProcessRecord adopted = restored.snapshot(ownerCluster).orElseThrow().processes().values()
				.stream().filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE)
				.findFirst().orElseThrow();
			assertEquals(goalId, adopted.goalId());
			assertTrue(restored.revalidateProductionProvider(sourceCluster, network,
				selected.provider()).isPresent());
			assertTrue(restored.releaseProductionGoalProcesses(goalId));
			assertEquals(0, restored.snapshot(ownerCluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE).count());
		}
	}

	@Test void selectedPublicationWaitsForRemoteLosingPriorityLeaseRelease() throws Exception {
		UUID source = id(20_362), sourceEpoch = id(20_363), sourceComputer = id(20_364);
		UUID winnerOwner = id(20_365), winnerEpoch = id(20_366), winnerComputer = id(20_367);
		UUID loserOwner = id(20_368), loserEpoch = id(20_369), loserComputer = id(20_370);
		UUID network = id(20_371), winnerLibrary = id(20_372), loserLibrary = id(20_373);
		PatternRecord winner = pattern(id(20_374), winnerLibrary, Items.STONE,
			OptionalInt.of(100), "winner-cleanup-fence");
		PatternRecord loser = pattern(id(20_375), loserLibrary, Items.STONE,
			OptionalInt.of(50), "loser-cleanup-fence");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		AtomicBoolean armed = new AtomicBoolean();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, cluster -> {
			if (!armed.get() || !cluster.equals(loserOwner)) return;
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		AtomicReference<FactoryRuntimeService> serviceRef = new AtomicReference<>();
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		serviceRef.set(service);
		try {
			service.start();
			service.restoreSnapshot(stateWithMembers(source, sourceEpoch, Map.of(sourceComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			service.restoreSnapshot(stateWithMembers(winnerOwner, winnerEpoch, Map.of(winnerComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 3, 0).orElseThrow())));
			service.restoreSnapshot(stateWithMembers(loserOwner, loserEpoch, Map.of(loserComputer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 3, 0).orElseThrow())));
			service.setProductionRequesterFrequencyResolver(ignored -> CommunicationFrequency.of("17"));
			service.publishProductionLibraryAccess(winnerOwner, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(winner.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(winnerLibrary, 1, 1, true, List.of(winner)));
			service.publishProductionLibraryAccess(loserOwner, Set.of(network), 1, CommunicationFrequency.of("17"), 10,
				worldAddress(loser.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(loserLibrary, 1, 1, true, List.of(loser)));
			service.setProductionRouteCollector(request -> {
				if (armed.compareAndSet(false, true)) {
					ClusterEngineState state = serviceRef.get().snapshot(loserOwner).orElseThrow();
					assertTrue(serviceRef.get().publishFact(new WorldFact.Signal(loserOwner,
						loserEpoch, state.generation(), "hold-loser-release", 1)));
					try { assertTrue(entered.await(3, TimeUnit.SECONDS)); }
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new AssertionError(interrupted);
					}
				}
				return new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of());
			});

			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (entered.getCount() != 0 && System.nanoTime() < deadline) {
				assertTrue(service.selectProductionPattern(source, network, requested).isEmpty());
				Thread.sleep(2);
			}
			assertTrue(entered.await(1, TimeUnit.SECONDS));
			assertEquals(FactoryRuntimeService.ProductionSearchStatus.SELECTING_ROUTE,
				service.productionSearchSnapshot(source, network, requested).orElseThrow().status(),
				"SELECTED cannot cross an in-flight remote loser cleanup fence");

			release.countDown();
			assertEquals(winner.patternId(), awaitProductionSelection(service, source, network,
				requested).pattern().patternId());
			assertEquals(0, service.snapshot(loserOwner).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE).count());
		} finally {
			release.countDown();
			service.close();
		}
	}

	@Test void productionSpeculationUsesARealAlchemistProcessAndCancelsTheLosingBranch()
		throws Exception {
		UUID cluster = id(20_400), epoch = id(20_401), computer = id(20_402), network = id(20_403);
		UUID firstLibrary = id(20_404), secondLibrary = id(20_405);
		PatternRecord first = pattern(id(20_406), firstLibrary, Items.STONE, OptionalInt.empty(),
			"spec-first");
		PatternRecord second = pattern(id(20_407), secondLibrary, Items.STONE, OptionalInt.empty(),
			"spec-second");
		AtomicInteger transitions = new AtomicInteger();
		CountDownLatch enteredSecondUnit = new CountDownLatch(1);
		CountDownLatch releaseSecondUnit = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			if (transitions.incrementAndGet() != 2) return;
			enteredSecondUnit.countDown();
			try { releaseSecondUnit.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			for (PatternRecord candidate : List.of(first, second))
				service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
					worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
					new ProductionRecipeCatalog(candidate.source().shelf().subLevelId(), 1, 1,
						true, List.of(candidate)));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			service.selectProductionPattern(cluster, network, requested);
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (enteredSecondUnit.getCount() != 0 && System.nanoTime() < deadline) {
				service.selectProductionPattern(cluster, network, requested);
				Thread.sleep(2);
			}
			assertTrue(enteredSecondUnit.await(1, TimeUnit.SECONDS));
			ClusterEngineState speculative = service.snapshot(cluster).orElseThrow();
			assertEquals(1, speculative.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count());
			assertTrue(speculative.processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION)
				.allMatch(process -> process.owningComputer().supports(ProductionCapability.ALCHEMIST)));

			releaseSecondUnit.countDown();
			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service, cluster,
				network, requested);
			assertTrue(Set.of(first.patternId(), second.patternId()).contains(selected.pattern().patternId()));
			assertEquals(1, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count(),
				"the selected speculative branch is retained and its loser is cancelled");
			UUID searchId = service.productionSearchSnapshot(cluster, network, requested).orElseThrow()
				.searchId();
			// Selecting a winner does not fence a worker already advancing the retained process.
			// Like the production caller, retry only the exact durable search until cleanup commits.
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			boolean retired = false;
			while (!(retired = service.retireProductionSearch(searchId))
				&& System.nanoTime() < deadline) {
				assertEquals(searchId, service.productionSearchSnapshot(cluster, network, requested)
					.orElseThrow().searchId(), "uncommitted cleanup must retain its durable cursor");
				assertTrue(service.peekSnapshotForPanelView(cluster).orElseThrow().processes().values()
					.stream().anyMatch(process -> process.goalId().equals(searchId)),
					"uncommitted cleanup must not partially release the selected search");
				Thread.sleep(2);
			}
			assertTrue(retired, "the retained speculative search must eventually finish cleanup");
			assertTrue(service.productionSearchSnapshot(cluster, network, requested).isEmpty());
			assertFalse(service.peekSnapshotForPanelView(cluster).orElseThrow().processes().values()
				.stream().anyMatch(process -> process.goalId().equals(searchId)));
			assertEquals(0, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count());
			assertTrue(service.retireProductionSearch(searchId), "completed cleanup is idempotent");
		} finally {
			releaseSecondUnit.countDown();
			service.close();
		}
	}

	@Test void retainedProductionSpeculationSurvivesReloadWithoutMaterialAndRetiresExactly()
		throws Exception {
		UUID cluster = id(20_450), epoch = id(20_451), computer = id(20_452), network = id(20_453);
		UUID firstLibrary = id(20_454), secondLibrary = id(20_455);
		PatternRecord first = pattern(id(20_456), firstLibrary, Items.STONE, OptionalInt.empty(),
			"spec-reload-first");
		PatternRecord second = pattern(id(20_457), secondLibrary, Items.STONE, OptionalInt.empty(),
			"spec-reload-second");
		ClusterEngineState durableState;
		FactoryRuntimeService.ProductionSearchSnapshot durableSearch;
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0).orElseThrow())));
			for (PatternRecord candidate : List.of(first, second))
				service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
					worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
					new ProductionRecipeCatalog(candidate.source().shelf().subLevelId(), 1, 1,
						true, List.of(candidate)));
			awaitProductionSelection(service, cluster, network, requested);
			assertEquals(1, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count());
			assertTrue(service.drainIntents(cluster).isEmpty(),
				"side-effect-free speculation cannot create a material/Create intent");
			durableState = RuntimeNbt.load(RuntimeNbt.save(service.snapshot(cluster).orElseThrow()))
				.orElseThrow();
			durableSearch = service.productionSearchSnapshot(cluster, network, requested).orElseThrow();
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(durableState);
			for (PatternRecord candidate : List.of(first, second))
				restored.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
					worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
					new ProductionRecipeCatalog(candidate.source().shelf().subLevelId(), 1, 1,
						true, List.of(candidate)));
			restored.restoreProductionSearchSnapshot(durableSearch);
			assertTrue(restored.selectProductionPattern(cluster, network, requested).isPresent());
			assertEquals(1, restored.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count());
			assertTrue(restored.retireProductionSearch(durableSearch.searchId()));
			assertEquals(0, restored.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.SPECULATIVE_EXPANSION).count());
		}
	}

	@Test void productionSelectionWaitsForAnUnloadedPeerInsteadOfFallingThrough() throws Exception {
		UUID cluster = id(340), epoch = id(341), network = id(342);
		UUID unavailableLibrary = id(343), loadedLibrary = id(344);
		PatternRecord lowerMatch = pattern(id(345), loadedLibrary, Items.STONE,
			java.util.OptionalInt.empty(), "lower");
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(339)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				new SpaceAddress(Level.OVERWORLD, unavailableLibrary, BlockPos.ZERO),
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration
					.Reachability.REACHABLE,
				new ProductionRecipeCatalog(unavailableLibrary, 1, 1, false, List.of()));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 0,
				new SpaceAddress(Level.OVERWORLD, loadedLibrary, BlockPos.ZERO),
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration
					.Reachability.REACHABLE,
				new ProductionRecipeCatalog(loadedLibrary, 1, 1, true, List.of(lowerMatch)));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			for (int attempt = 0; attempt < 20; attempt++) {
				assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty(),
					"a known unloaded peer in the active wave must not silently disappear");
				Thread.sleep(5);
			}
			service.publishProductionLibraryAccess(cluster, Set.of(network), 2, CommunicationFrequency.of("0"), 10,
				new SpaceAddress(Level.OVERWORLD, unavailableLibrary, BlockPos.ZERO),
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration
					.Reachability.REACHABLE,
				new ProductionRecipeCatalog(unavailableLibrary, 1, 2, true, List.of()));
			assertEquals(lowerMatch.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId());
		}
	}

	@Test void productionSelectionIgnoresPatternOverrideWithoutAuthoritativeCoordinationLease()
		throws Exception {
		UUID cluster = id(350), epoch = id(351), network = id(352);
		UUID firstLibrary = id(353), overrideLibrary = id(354);
		PatternRecord stableFirst = pattern(id(355), firstLibrary, Items.STONE,
			java.util.OptionalInt.empty(), "first");
		PatternRecord inactiveOverride = pattern(id(356), overrideLibrary, Items.STONE,
			java.util.OptionalInt.of(100), "override");
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(349)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(firstLibrary, 1, 1, true, List.of(stableFirst)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(overrideLibrary, 1, 1, true, List.of(inactiveOverride)));
			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service, cluster,
				network, new StackKey(new ItemStack(Items.STONE)));
			assertEquals(stableFirst.patternId(), selected.pattern().patternId(),
				"stored fine priority is inactive without its durable Alchemist coordination proof");
		}
	}

	@Test void productionSelectionWritesTheUnifiedRoutePolicyCursor() throws Exception {
		UUID cluster = id(360), epoch = id(361), network = id(362), library = id(363);
		PatternRecord candidate = pattern(id(364), library, Items.STONE,
			java.util.OptionalInt.empty(), "route");
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(359)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(candidate)));
			awaitProductionSelection(service, cluster, network,
				new StackKey(new ItemStack(Items.STONE)));
			assertFalse(service.snapshot(cluster).orElseThrow().routeCursor()
				.lastSelectedByPriority().isEmpty(),
				"the Goal-admission Pattern must be selected by the production route table");
		}
	}

	@Test void productionSelectionCollectsRealAgentAndGaugeCandidatesInTheSameScope()
		throws Exception {
		UUID cluster = id(388), epoch = id(389), network = id(390), library = id(391);
		PatternRecord candidate = pattern(id(392), library, Items.STONE,
			OptionalInt.empty(), "unified-input");
		AtomicInteger collections = new AtomicInteger();
		AtomicReference<ProductionRouteTable.ProviderSource> currentAgent =
			new AtomicReference<>();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(393)));
			service.setProductionRouteCollector(request -> {
				collections.incrementAndGet();
				assertEquals(cluster, request.clusterId());
				assertEquals(network, request.logisticsId());
				assertEquals(1, request.patternSources().size());
				var pattern = request.patternSources().getFirst();
				ProductionRouteTable.ProviderSource agent =
					new ProductionRouteTable.ProviderSource(request.scope(), pattern.libraryId(),
						pattern.patternId(), "agent/real", RouteCandidate.ProviderKind.AGENT, 20,
						RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
				currentAgent.set(agent);
				return new FactoryRuntimeService.ProductionRouteSources(List.of(agent), List.of(
					new ProductionRouteTable.ProviderSource(request.scope(), pattern.libraryId(),
						pattern.patternId(), "gauge/real", RouteCandidate.ProviderKind.FACTORY_GAUGE,
						10, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE)));
			});
			service.setProductionProviderResolver(request -> Optional.ofNullable(currentAgent.get()));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(candidate)));
			awaitProductionSelection(service, cluster, network,
				new StackKey(new ItemStack(Items.STONE)));
			assertEquals(1, collections.get());
			assertTrue(service.snapshot(cluster).orElseThrow().routeCursor()
				.lastSelectedByPriority().values().stream()
				.anyMatch(cursor -> cursor.providerKey().equals("agent/real")));
		}
	}

	@Test void productionSelectionCarriesAndRevalidatesTheExactTypedProviderFence()
		throws Exception {
		UUID cluster = id(394), epoch = id(395), network = id(396), library = id(397);
		PatternRecord pattern = pattern(id(398), library, Items.STONE,
			OptionalInt.empty(), "selected-provider-input");
		AtomicReference<ProductionRouteTable.ProviderSource> currentProvider =
			new AtomicReference<>();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(399)));
			service.setProductionRouteCollector(request -> {
				ProductionRouteTable.ProviderSource patternSource = request.patternSources().getFirst();
				ProductionRouteTable.ProviderSource agent = new ProductionRouteTable.ProviderSource(
					request.scope(), patternSource.libraryId(), patternSource.patternId(),
					"agent/exact-provider", RouteCandidate.ProviderKind.AGENT, 37,
					RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
				currentProvider.set(agent);
				return new FactoryRuntimeService.ProductionRouteSources(List.of(agent), List.of());
			});
			service.setProductionProviderResolver(request ->
				Optional.ofNullable(currentProvider.get()));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(pattern)));

			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service,
				cluster, network, new StackKey(new ItemStack(Items.STONE)));
			SelectedProviderDescriptor provider = selected.provider();
			FactoryRuntimeService.ProductionSearchSnapshot snapshot = service
				.productionSearchSnapshot(cluster, network, provider.selectionScope().requestedStackKey())
				.orElseThrow();
			assertEquals(RouteCandidate.ProviderKind.AGENT, provider.providerKind());
			assertEquals("agent/exact-provider", provider.providerKey());
			assertEquals(37, provider.effectivePriority());
			assertEquals(RouteCandidate.Availability.AVAILABLE, provider.availability());
			assertEquals(epoch, provider.selectionScope().epochId());
			assertEquals(snapshot.searchId(), provider.selectionScope().queryId());
			assertEquals(snapshot.searchGeneration(), provider.selectionScope().searchGeneration());
			assertEquals(Optional.of(provider), snapshot.selectedProvider());
			assertEquals(selected, service.revalidateProductionProvider(cluster, network, provider)
				.orElseThrow());

			List<ProductionRouteTable.ProviderSource> changedProviders = List.of(
				new ProductionRouteTable.ProviderSource(provider.selectionScope(),
					provider.libraryId(), provider.patternId(), "agent/different-provider",
					provider.providerKind(), provider.effectivePriority(), provider.availability(),
					RouteCandidate.Blocker.NONE),
				new ProductionRouteTable.ProviderSource(provider.selectionScope(),
					provider.libraryId(), provider.patternId(), provider.providerKey(),
					RouteCandidate.ProviderKind.FACTORY_GAUGE, provider.effectivePriority(),
					provider.availability(), RouteCandidate.Blocker.NONE),
				new ProductionRouteTable.ProviderSource(provider.selectionScope(),
					provider.libraryId(), provider.patternId(), provider.providerKey(),
					provider.providerKind(), 38, provider.availability(),
					RouteCandidate.Blocker.NONE),
				new ProductionRouteTable.ProviderSource(provider.selectionScope(),
					provider.libraryId(), provider.patternId(), provider.providerKey(),
					provider.providerKind(), provider.effectivePriority(),
					RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE),
				new ProductionRouteTable.ProviderSource(new RouteSelectionScope(epoch,
					provider.selectionScope().queryId(), provider.selectionScope().requestedStackKey(),
					provider.selectionScope().searchGeneration() + 1,
					provider.selectionScope().attemptSequence(),
					provider.selectionScope().attemptId()), provider.libraryId(), provider.patternId(),
					provider.providerKey(), provider.providerKind(), provider.effectivePriority(),
					provider.availability(), RouteCandidate.Blocker.NONE));
			for (ProductionRouteTable.ProviderSource changed : changedProviders) {
				currentProvider.set(changed);
				assertTrue(service.revalidateProductionProvider(cluster, network, provider).isEmpty(),
					"kind/key/priority/availability/scope changes must fence the selected provider");
			}
			assertTrue(service.retireProductionSearch(snapshot.searchId()),
				"an admission transition must retire its exact search cursor");
			assertTrue(service.retireProductionSearch(snapshot.searchId()),
				"exact search retirement replay must remain an idempotent cleanup success");
			assertTrue(service.productionSearchSnapshot(cluster, network,
				provider.selectionScope().requestedStackKey()).isEmpty(),
				"retired searches must not consume the bounded runtime or journal namespace");
		}
	}

	@Test void persistedFourKindPriorityWinnerDispatchesOnlyTheExactProviderAfterReload()
		throws Exception {
		UUID cluster = id(400), epoch = id(401), network = id(402), library = id(403);
		UUID goal = id(404), process = id(405), target = id(406);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey ingredient = new StackKey(new ItemStack(Items.STONE));
		PatternRecord pattern = new PatternRecord(id(407), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1,
				DataComponentPredicate.EMPTY)), List.of(new PatternOutput(output, 1)),
			new StandardPatternBody("three-kind-input"), OptionalInt.empty());
		ProductionRecipeCatalog catalog = new ProductionRecipeCatalog(library, 1, 1, true,
			List.of(pattern));
		AtomicReference<ProductionRouteTable.ProviderSource> currentProvider =
			new AtomicReference<>();
		OrdinaryProviderRegistry ordinary = new OrdinaryProviderRegistry();
		ordinary.register(new OrdinaryProviderRegistry.Registration(id(409), network, output, 40,
			RouteCandidate.Availability.AVAILABLE));
		AtomicReference<List<ProductionRouteTable.ProviderSource>> collected =
			new AtomicReference<>(List.of());
		CompoundTag savedState;
		CompoundTag savedSearch;
		CompoundTag savedPlan;

		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, target));
			service.setProductionRouteCollector(request -> {
				ProductionRouteTable.ProviderSource patternSource =
					request.patternSources().getFirst();
				ProductionRouteTable.ProviderSource busyAgent =
					new ProductionRouteTable.ProviderSource(request.scope(), library,
						pattern.patternId(), "agent/higher-but-busy",
						RouteCandidate.ProviderKind.AGENT, 30,
						RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
				ProductionRouteTable.ProviderSource gauge =
					new ProductionRouteTable.ProviderSource(request.scope(), library,
						pattern.patternId(), "factory-gauge/exact-winner",
						RouteCandidate.ProviderKind.FACTORY_GAUGE, 20,
						RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
				ProductionRouteTable.ProviderSource ordinaryProvider = ordinary.collect(request.scope(),
					network, library, pattern.patternId()).getFirst();
				collected.set(List.of(patternSource, busyAgent, gauge, ordinaryProvider));
				currentProvider.set(ordinaryProvider);
				return new FactoryRuntimeService.ProductionRouteSources(List.of(), List.of(busyAgent),
					List.of(gauge), List.of(ordinaryProvider));
			});
			service.setProductionProviderResolver(request ->
				Optional.ofNullable(currentProvider.get()));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(pattern.source().shelf()), LibraryRegistration.Reachability.REACHABLE, catalog);

			FactoryRuntimeService.SelectedPattern selected = awaitProductionSelection(service,
				cluster, network, output);
			EnumMap<RouteCandidate.ProviderKind, ProductionRouteTable.ProviderSource> byKind =
				new EnumMap<>(RouteCandidate.ProviderKind.class);
			collected.get().forEach(source -> byKind.put(source.providerKind(), source));
			assertEquals(EnumSet.allOf(RouteCandidate.ProviderKind.class), byKind.keySet());
			assertAll(
				() -> assertEquals(10,
					byKind.get(RouteCandidate.ProviderKind.PATTERN).effectivePriority()),
				() -> assertEquals(RouteCandidate.Availability.AVAILABLE,
					byKind.get(RouteCandidate.ProviderKind.PATTERN).availability()),
				() -> assertEquals(30,
					byKind.get(RouteCandidate.ProviderKind.AGENT).effectivePriority()),
				() -> assertEquals(RouteCandidate.Availability.BUSY,
					byKind.get(RouteCandidate.ProviderKind.AGENT).availability()),
				() -> assertEquals(20,
					byKind.get(RouteCandidate.ProviderKind.FACTORY_GAUGE).effectivePriority()),
				() -> assertEquals(RouteCandidate.Availability.AVAILABLE,
					byKind.get(RouteCandidate.ProviderKind.FACTORY_GAUGE).availability()),
				() -> assertEquals(40,
					byKind.get(RouteCandidate.ProviderKind.ORDINARY_PROVIDER).effectivePriority()),
				() -> assertEquals(RouteCandidate.ProviderKind.ORDINARY_PROVIDER,
					selected.provider().providerKind()),
				() -> assertEquals("ordinary/" + id(409),
					selected.provider().providerKey()),
				() -> assertEquals(40, selected.provider().effectivePriority()));

			FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster,
				selected.provider(), pattern, new CreateRouteKey(network, "delivery"), 1,
				InputMode.BULK, 0,
				new StockObservationScope.NetworkSummary(network, output, 1),
				Map.of(ingredient, 1L)).orElseThrow();
			savedState = RuntimeNbt.save(service.snapshot(cluster).orElseThrow());
			savedSearch = CurrentSchemaStateNbt.save(service.productionSearchSnapshot(cluster,
				network, output).orElseThrow(), RegistryAccess.EMPTY);
			savedPlan = CurrentSchemaStateNbt.save(plan, RegistryAccess.EMPTY);
		}

		ClusterEngineState reloadedState = RuntimeNbt.load(savedState).orElseThrow();
		FactoryRuntimeService.ProductionSearchSnapshot reloadedSearch = CurrentSchemaStateNbt.load(
			savedSearch, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).orElseThrow();
		FrozenProductionPlan reloadedPlan = CurrentSchemaStateNbt.load(savedPlan,
			RegistryAccess.EMPTY, FrozenProductionPlan.class).orElseThrow();
		assertEquals(reloadedSearch.selectedProvider().orElseThrow(),
			reloadedPlan.selectedProvider(), "the persisted search and plan must fence one winner");

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start();
			// Mirrors RuntimeState bootstrap: durable Cluster/search state precedes Library publication.
			restored.restoreSnapshot(reloadedState);
			restored.restoreProductionSearchSnapshot(reloadedSearch);
			SelectedProviderDescriptor exactOrdinary = reloadedPlan.selectedProvider();
			currentProvider.set(new ProductionRouteTable.ProviderSource(
				exactOrdinary.selectionScope(), exactOrdinary.libraryId(), exactOrdinary.patternId(),
				exactOrdinary.providerKey(), exactOrdinary.providerKind(),
				exactOrdinary.effectivePriority(), exactOrdinary.availability(),
				RouteCandidate.Blocker.NONE));
			restored.setProductionProviderResolver(request ->
				Optional.ofNullable(currentProvider.get()));
			restored.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(pattern.source().shelf()), LibraryRegistration.Reachability.REACHABLE, catalog);

			ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(id(408), cluster, 0,
				new ProductionEffect.TargetFence(epoch, target, 0, 0), process,
				reloadedPlan.runs().currentRun().runId(), reloadedPlan.stages().getFirst().route(),
				List.of(new OrderedIngredient(ingredient, 1)));
			List<SelectedProviderDescriptor> dispatchAuthorities = new ArrayList<>();
			List<ProductionEffect.CreateDispatch> committedDispatches = new ArrayList<>();
			Optional<FactoryRuntimeService.SelectedPattern> exactAfterReload =
				restored.revalidateProductionProvider(cluster, network, exactOrdinary);
			assertEquals(Optional.of(exactOrdinary),
				exactAfterReload.map(FactoryRuntimeService.SelectedPattern::provider),
				"the persisted exact winner must revalidate after bootstrap publication");
			exactAfterReload.ifPresent(exact -> {
				dispatchAuthorities.add(exact.provider());
				committedDispatches.add(intent);
			});
			assertEquals(List.of(exactOrdinary), dispatchAuthorities,
				"reload must dispatch under the persisted exact ordinary-provider winner");
			assertEquals(List.of(intent), committedDispatches);

			currentProvider.set(new ProductionRouteTable.ProviderSource(
				exactOrdinary.selectionScope(), exactOrdinary.libraryId(), exactOrdinary.patternId(),
				"agent/available-substitute", RouteCandidate.ProviderKind.AGENT, 30,
				RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE));
			Optional<FactoryRuntimeService.SelectedPattern> agentSubstitute =
				restored.revalidateProductionProvider(cluster, network, exactOrdinary);
			assertTrue(agentSubstitute.isEmpty(),
				"an available Agent is not the persisted ordinary provider");
			agentSubstitute.ifPresent(exact -> {
				dispatchAuthorities.add(exact.provider());
				committedDispatches.add(intent);
			});
			currentProvider.set(new ProductionRouteTable.ProviderSource(
				exactOrdinary.selectionScope(), exactOrdinary.libraryId(), exactOrdinary.patternId(),
				exactOrdinary.patternId().toString(), RouteCandidate.ProviderKind.PATTERN, 10,
				RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE));
			Optional<FactoryRuntimeService.SelectedPattern> patternSubstitute =
				restored.revalidateProductionProvider(cluster, network, exactOrdinary);
			assertTrue(patternSubstitute.isEmpty(),
				"an available lower-priority Pattern is not the persisted ordinary provider");
			patternSubstitute.ifPresent(exact -> {
				dispatchAuthorities.add(exact.provider());
				committedDispatches.add(intent);
			});
			assertEquals(List.of(exactOrdinary), dispatchAuthorities,
				"available Agent/Pattern fallbacks must not silently replace the persisted winner");
			assertEquals(List.of(intent), committedDispatches,
				"a substituted provider must not reach the Create dispatch commit gate");
		}
	}

	@Test void repeatedIdenticalProductionPublicationDoesNotRestartAnActiveSearch()
		throws Exception {
		UUID cluster = id(365), epoch = id(366), network = id(367), library = id(368);
		PatternRecord candidate = pattern(id(369), library, Items.STONE,
			java.util.OptionalInt.empty(), "idempotent-publication");
		ProductionRecipeCatalog catalog = new ProductionRecipeCatalog(library, 1, 1, true,
			List.of(candidate));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(stateWithComputer(cluster, epoch, id(3650)));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
			Optional<FactoryRuntimeService.SelectedPattern> selected = Optional.empty();
			while (selected.isEmpty() && System.nanoTime() < deadline) {
				service.publishProductionLibraryAccess(cluster, Set.of(network), 1, catalog);
				selected = service.selectProductionPattern(cluster, network, requested);
				if (selected.isEmpty()) Thread.sleep(5);
			}
			assertEquals(candidate.patternId(), selected.orElseThrow(() ->
				new AssertionError("an idempotent block-entity publication starved the search"))
				.pattern().patternId());
		}
	}

	@Test void productionSelectionNeedsARealLoadedRequesterComputer() throws Exception {
		UUID cluster = id(370), epoch = id(371), network = id(372), library = id(373);
		PatternRecord candidate = pattern(id(374), library, Items.STONE,
			java.util.OptionalInt.empty(), "real-requester");
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(candidate)));
			StackKey requested = new StackKey(new ItemStack(Items.STONE));
			long deadline = System.nanoTime() + Duration.ofMillis(250).toNanos();
			while (System.nanoTime() < deadline) {
				assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty(),
					"a synthetic requester UUID must never stand in for a loaded Computer");
				Thread.sleep(5);
			}
			service.restoreSnapshot(stateWithComputer(cluster, epoch, id(375)));
			assertEquals(candidate.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId());
		}
	}

	@Test void productionSelectionDoesNotReuseADurableWinnerFromAPriorClusterEpoch()
		throws Exception {
		UUID cluster = id(380), priorEpoch = id(381), currentEpoch = id(382);
		UUID network = id(383), library = id(384);
		PatternRecord candidate = pattern(id(385), library, Items.STONE,
			java.util.OptionalInt.empty(), "epoch-fenced-winner");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithComputer(cluster, currentEpoch, id(386)));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(candidate)));
			SelectedProviderDescriptor durable = new SelectedProviderDescriptor(
				new RouteSelectionScope(priorEpoch, id(387), requested, 4, 0, id(3870)),
				library, candidate.patternId(), candidate.patternId().toString(),
				RouteCandidate.ProviderKind.PATTERN, 0,
				RouteCandidate.Availability.AVAILABLE);
			service.restoreProductionSearchSnapshot(new FactoryRuntimeService.ProductionSearchSnapshot(
				id(387), cluster, priorEpoch, network, requested, 4,
				FactoryRuntimeService.ProductionSearchStatus.SELECTED,
				Optional.of(durable)));

			assertTrue(service.selectProductionPattern(cluster, network, requested).isEmpty(),
				"a winner frozen under another Cluster epoch must restart bounded search");
			assertEquals(candidate.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId());
		}
	}

	@Test void productionPriorityLeaseUsesAnOrdinarySlotAndRestoresItsTicketAssociation() {
		UUID cluster = id(900), epoch = id(901);
		ProductionRequestTicket ticket = priorityTicket(cluster, 902, 0, 7);
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.PRIORITY_LEASE_UNAVAILABLE,
				service.pendingAgentRuns().enqueue(ticket, ownerClaim(ticket, cluster, 1)));
			assertTrue(service.snapshot(cluster).orElseThrow().processes().isEmpty());

			service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(ticket, ownerClaim(ticket, cluster, 1)));
			ClusterEngineState leased = service.snapshot(cluster).orElseThrow();
			ProcessRecord process = leased.processes().values().stream()
				.filter(candidate -> candidate.kind() == ProcessKind.PRIORITY_LEASE)
				.findFirst().orElseThrow();
			assertEquals(ProductionProfession.LIBRARIAN, process.owningComputer().profession());
			assertFalse(process.kind().requiresAlchemist());
			PriorityLeaseAssociation association = leased.priorityLeaseAssociations()
				.get(process.processId());
			assertNotNull(association);
			assertTrue(association.matches(ticket, cluster));
			assertEquals(ticket.enqueueSequence(), association.enqueueSequence());
			saved = RuntimeNbt.save(leased);
			ClusterEngineState roundTripped = RuntimeNbt.load(saved).orElseThrow();
			assertEquals(association, roundTripped.priorityLeaseAssociations().get(process.processId()));
			net.minecraft.nbt.CompoundTag missingSequence = saved.copy();
			missingSequence.getList("PriorityLeaseAssociations", net.minecraft.nbt.Tag.TAG_COMPOUND)
				.getCompound(0).remove("EnqueueSequence");
			assertTrue(RuntimeNbt.load(missingSequence).isEmpty(),
				"enqueue sequence is required by the sole strict runtime schema");
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start();
			restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				restored.pendingAgentRuns().enqueue(ticket, ownerClaim(ticket, cluster, 1)));
			assertEquals(1, restored.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PRIORITY_LEASE).count(),
				"restoring the same ticket must reclaim its durable lease rather than duplicate it");
		}
	}

	@Test void durablePriorityLeaseReclaimRejectsEveryChangedNonGoalLineageField() {
		UUID cluster = id(940), epoch = id(941);
		ProductionRequestTicket original = priorityTicket(cluster, 942, 0, 7);
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(original, ownerClaim(original, cluster, 1)));
			saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow());
		}
		List<ProductionRequestTicket> changed = List.of(
			copyTicket(original, original.runId(), original.fence() + 1, original.sourceClusterId(),
				original.targetRoute(), original.sourceMaterialSnapshotGeneration()),
			copyTicket(original, id(943), original.fence(), original.sourceClusterId(),
				original.targetRoute(), original.sourceMaterialSnapshotGeneration()),
			copyTicket(original, original.runId(), original.fence(), id(944),
				original.targetRoute(), original.sourceMaterialSnapshotGeneration()),
			copyTicket(original, original.runId(), original.fence(), original.sourceClusterId(),
				original.targetRoute(), original.sourceMaterialSnapshotGeneration() + 1));
		for (ProductionRequestTicket candidate : changed) {
			try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
				restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
				assertEquals(AgentPendingRunQueue.EnqueueResult.PRIORITY_LEASE_UNAVAILABLE,
					restored.pendingAgentRuns().enqueue(candidate,
						ownerClaim(candidate, cluster, 1)), candidate.toString());
			}
		}
	}

	@Test void durablePriorityLeaseReclaimRejectsAChangedEnqueueSequenceCollision() {
		UUID cluster = id(945), epoch = id(946);
		ProductionRequestTicket prefix = priorityTicket(cluster, 947, 0, 0);
		ProductionRequestTicket original = priorityTicket(cluster, 948, 1, 7);
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(prefix, ownerClaim(prefix, cluster, 1)));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(original, ownerClaim(original, cluster, 1)));
			saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow());
		}

		ProductionRequestTicket colliding = original.withEnqueueSequence(0);
		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			assertEquals(AgentPendingRunQueue.EnqueueResult.PRIORITY_LEASE_UNAVAILABLE,
				restored.pendingAgentRuns().enqueue(colliding,
					ownerClaim(colliding, cluster, 1)),
				"a fresh queue sequence must not reclaim a durable lease for a different admission");
			assertEquals(1, restored.snapshot(cluster).orElseThrow()
				.priorityLeaseAssociations().size());
		}
	}

	@Test void remoteSourcePriorityLeaseConsumesOnlyTheResolvedAgentOwnersSlot() {
		UUID sourceCluster = id(930), agentOwnerCluster = id(931), epoch = id(932);
		ProductionRequestTicket ticket = priorityTicket(sourceCluster, 933, 0, 7);
		AgentAddressRegistration ownerClaim = ownerClaim(ticket, agentOwnerCluster, 3);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(librarianState(sourceCluster, epoch));
			service.restoreSnapshot(librarianState(agentOwnerCluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(ticket, ownerClaim));
			assertTrue(service.snapshot(sourceCluster).orElseThrow().processes().isEmpty());
			assertEquals(1, service.snapshot(agentOwnerCluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PRIORITY_LEASE).count());

			ProductionRequestTicket mismatch = priorityTicket(sourceCluster, 934, 1, 6);
			AgentAddressRegistration forged = new AgentAddressRegistration(mismatch.targetRoute(),
				id(9999), agentOwnerCluster, 3, CommunicationFrequency.of("17"), AgentAddressRegistration.LoadState.LOADED,
				AgentAddressRegistration.Reachability.REACHABLE);
			assertEquals(AgentPendingRunQueue.EnqueueResult.AGENT_OWNER_MISMATCH,
				service.pendingAgentRuns().enqueue(mismatch, forged));
		}
	}

	@Test void productionPriorityLeaseSaturationPreservesFifoAndEveryReleasePathFreesItsSlot() {
		UUID cluster = id(910), epoch = id(911);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			AgentPendingRunQueue queue = service.pendingAgentRuns();
			ProductionRequestTicket ordinary = priorityTicket(cluster, 912, 0, 0);
			ProductionRequestTicket dequeued = priorityTicket(cluster, 913, 1, 9);
			ProductionRequestTicket cancelled = priorityTicket(cluster, 914, 2, 8);
			ProductionRequestTicket defaulted = priorityTicket(cluster, 915, 3, 7);
			ProductionRequestTicket remaining = priorityTicket(cluster, 916, 4, 6);
			for (ProductionRequestTicket ticket : List.of(ordinary, dequeued, cancelled, defaulted, remaining))
				assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
					queue.enqueue(ticket, ownerClaim(ticket, cluster, 1)));
			assertEquals(4, service.snapshot(cluster).orElseThrow().processes().size());
			assertEquals(AgentPendingRunQueue.PriorityEditResult.LEASE_UNAVAILABLE,
				queue.editPriority(ordinary.requestId(), 10));
			assertEquals(List.of(dequeued.requestId(), cancelled.requestId(), defaulted.requestId(),
				remaining.requestId(), ordinary.requestId()), queue.snapshot().stream()
				.map(ProductionRequestTicket::requestId).toList(), "failed edit must preserve FIFO/order indexes");

			assertEquals(dequeued.requestId(), queue.dequeue().orElseThrow().requestId());
			assertTrue(queue.cancel(cancelled.requestId()));
			assertEquals(AgentPendingRunQueue.PriorityEditResult.UPDATED,
				queue.editPriority(defaulted.requestId(), AgentPendingRunQueue.DEFAULT_PRIORITY));
			assertTrue(queue.cancel(remaining.requestId()));
			assertEquals(0, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.PRIORITY_LEASE).count());
		}
	}

	@Test void productionPriorityEditPersistsTheRequestedPriorityForRestoredAssociation() {
		UUID cluster = id(917), epoch = id(918);
		ProductionRequestTicket ordinary = priorityTicket(cluster, 919, 0, 0);
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(ordinary, ownerClaim(ordinary, cluster, 1)));
			assertEquals(AgentPendingRunQueue.PriorityEditResult.UPDATED,
				service.pendingAgentRuns().editPriority(ordinary.requestId(), 5));
			saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow());
		}
		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				restored.pendingAgentRuns().enqueue(ordinary.withPriority(5),
					ownerClaim(ordinary, cluster, 1)));
		}
	}

	@Test void productionNonDefaultPriorityEditReplacesTheDurableTicketBeforeReordering() {
		UUID cluster = id(925), epoch = id(926);
		ProductionRequestTicket original = priorityTicket(cluster, 927, 0, 4);
		ProductionRequestTicket updated = original.withPriority(9);
		net.minecraft.nbt.CompoundTag saved;
		UUID processId;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(original, ownerClaim(original, cluster, 1)));
			processId = service.snapshot(cluster).orElseThrow().priorityLeaseAssociations()
				.keySet().iterator().next();

			assertEquals(AgentPendingRunQueue.PriorityEditResult.UPDATED,
				service.pendingAgentRuns().editPriority(original.requestId(), 9));
			ClusterEngineState edited = service.snapshot(cluster).orElseThrow();
			PriorityLeaseAssociation association = edited.priorityLeaseAssociations().get(processId);
			assertNotNull(association);
			assertTrue(association.matches(updated, cluster));
			assertFalse(association.matches(original, cluster));
			assertEquals(9, edited.goals().get(edited.processes().get(processId).goalId())
				.policy().priority());
			assertEquals(Set.of(processId), edited.priorityLeaseAssociations().keySet(),
				"the ordinary lease must be retained rather than replaced");
			saved = RuntimeNbt.save(edited);
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			assertEquals(AgentPendingRunQueue.EnqueueResult.PRIORITY_LEASE_UNAVAILABLE,
				restored.pendingAgentRuns().enqueue(original, ownerClaim(original, cluster, 1)));
			assertTrue(restored.pendingAgentRuns().snapshot().isEmpty());
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				restored.pendingAgentRuns().enqueue(updated, ownerClaim(updated, cluster, 1)));
			assertEquals(Set.of(processId), restored.snapshot(cluster).orElseThrow()
				.priorityLeaseAssociations().keySet());
		}
	}

	@Test void failedInFlightPriorityAssociationReplacementLeavesQueueAndFifoUnchanged()
		throws Exception {
		UUID cluster = id(935), epoch = id(936);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		ProductionRequestTicket prioritized = priorityTicket(cluster, 937, 0, 4);
		ProductionRequestTicket ordinary = priorityTicket(cluster, 938, 1, 0);
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(prioritized,
					ownerClaim(prioritized, cluster, 1)));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(ordinary, ownerClaim(ordinary, cluster, 1)));
			ClusterEngineState leased = service.snapshot(cluster).orElseThrow();
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, leased.generation(),
				"hold-edit", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));

			assertEquals(AgentPendingRunQueue.PriorityEditResult.LEASE_UNAVAILABLE,
				service.pendingAgentRuns().editPriority(prioritized.requestId(), 9));
			assertEquals(List.of(prioritized, ordinary), service.pendingAgentRuns().snapshot());
			assertEquals(prioritized, service.pendingAgentRuns().find(prioritized.requestId())
				.orElseThrow());
			PriorityLeaseAssociation association = service.snapshot(cluster).orElseThrow()
				.priorityLeaseAssociations().values().iterator().next();
			assertTrue(association.matches(prioritized, cluster));
			assertFalse(association.matches(prioritized.withPriority(9), cluster));
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void productionPriorityLeaseReleaseWakesProcessWaitersAndServiceCloseTearsDownLeases() {
		UUID cluster = id(920), epoch = id(921), waitingGoal = id(922);
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG);
		service.start(); service.restoreSnapshot(librarianStateWithProcessWaiter(cluster, epoch, waitingGoal));
		ProductionRequestTicket first = priorityTicket(cluster, 923, 0, 9);
		ProductionRequestTicket second = priorityTicket(cluster, 924, 1, 8);
		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
			service.pendingAgentRuns().enqueue(first, ownerClaim(first, cluster, 1)));
		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
			service.pendingAgentRuns().enqueue(second, ownerClaim(second, cluster, 1)));
		assertTrue(service.pendingAgentRuns().cancel(first.requestId()));
		assertNotEquals(GoalState.WAITING,
			service.snapshot(cluster).orElseThrow().goals().get(waitingGoal).state());
		service.close();
		assertEquals(0, service.snapshot().get(cluster).processes().values().stream()
			.filter(process -> process.kind() == ProcessKind.PRIORITY_LEASE).count());
	}

	@Test void priorityLeaseReleaseDefersUntilTheRealInFlightSnapshotReturns() throws Exception {
		UUID cluster = id(950), epoch = id(951);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		ProductionRequestTicket ticket = priorityTicket(cluster, 952, 0, 7);
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(librarianState(cluster, epoch));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				service.pendingAgentRuns().enqueue(ticket, ownerClaim(ticket, cluster, 1)));
			ClusterEngineState leased = service.snapshot(cluster).orElseThrow();
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, leased.generation(),
				"hold", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));
			assertTrue(service.pendingAgentRuns().cancel(ticket.requestId()));
			assertEquals(1, service.snapshot(cluster).orElseThrow().priorityLeaseAssociations().size(),
				"the server thread must not overwrite a worker-owned snapshot");

			releaseWorker.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!service.snapshot(cluster).orElseThrow().priorityLeaseAssociations().isEmpty()
				&& System.nanoTime() < deadline) Thread.sleep(5);
			ClusterEngineState released = service.snapshot(cluster).orElseThrow();
			assertTrue(released.priorityLeaseAssociations().isEmpty());
			assertTrue(released.processes().values().stream()
				.noneMatch(process -> process.kind() == ProcessKind.PRIORITY_LEASE));
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void serverObservationWaitDefersUntilTheRealInFlightSnapshotReturns() throws Exception {
		UUID cluster = id(960), epoch = id(961), computer = id(962), process = id(963);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		ClusterEngineState initial = dispatchable(cluster, epoch, computer, process);
		UUID goalId = initial.processes().get(process).goalId();
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(initial);
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch,
				initial.generation(), "hold-server-observation", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));

			assertTrue(service.waitGoalFromServerObservation(cluster, epoch,
				initial.generation(), goalId, WaitReason.AGENT_UNLOADED),
				"an authoritative server wait must be accepted while the worker owns the snapshot");
			assertEquals(GoalState.RUNNABLE,
				service.snapshot(cluster).orElseThrow().goals().get(goalId).state(),
				"the server thread must not overwrite the in-flight snapshot");

			releaseWorker.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			GoalRecord observed;
			do {
				observed = service.snapshot(cluster).orElseThrow().goals().get(goalId);
				if (observed.state() != GoalState.WAITING) Thread.sleep(5);
			} while (observed.state() != GoalState.WAITING && System.nanoTime() < deadline);
			assertEquals(GoalState.WAITING, observed.state());
			assertEquals(WaitReason.AGENT_UNLOADED, observed.waitReason());
			assertEquals(ProcessState.WAITING,
				service.snapshot(cluster).orElseThrow().processes().get(process).state());
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void durableCompletionCreditReplayDeduplicatesWhileWorkerIsInFlight() throws Exception {
		UUID cluster = id(964), epoch = id(965), computer = id(966), process = id(967);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		ClusterEngineState initial = dispatchable(cluster, epoch, computer, process);
		UUID goalId = initial.processes().get(process).goalId();
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(initial);
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch,
				initial.generation(), "hold-credit-replay", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));

			for (int replay = 0; replay < CONFIG.pendingRunCapacity() + 5; replay++)
				assertTrue(service.creditPanelGoalActualStock(cluster, epoch,
					initial.generation(), goalId, Long.MAX_VALUE),
					"an identical durable completion replay must share one mailbox slot");
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void panelSubmissionClaimsCompletedWorkerSnapshotBeforeContinuationRedispatch() throws Exception {
		UUID cluster = id(970), epoch = id(971);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseContinuation = new CountDownLatch(1);
		AtomicInteger transitions = new AtomicInteger();
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			int transition = transitions.incrementAndGet();
			if (transition == 1) enteredWorker.countDown();
			if (transition == 2) try { releaseContinuation.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 0,
				"panel-mutation-window", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));
			Thread.sleep(20);
			ClusterEngineState menuProjection = service.snapshotForServerMutation(cluster).orElseThrow();

			assertTrue(service.submitPanelGoal(cluster, epoch, menuProjection.generation(), id(972),
				new CreateRouteKey(id(973), "panel-route"), 1, InputMode.BULK, 0).isPresent(),
				"a completed worker result must not be immediately re-dispatched before the "
					+ "server-authoritative Panel mutation can claim its snapshot");
		} finally {
			releaseContinuation.countDown();
			service.close();
		}
	}

	@Test void deferredListenerReleasesRemoveTwoSlotsBeforeTheNextWorkerSnapshot() throws Exception {
		UUID cluster = id(975), epoch = id(976), computer = id(977);
		ProductionResourceProfile trader = ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64)
			.orElseThrow();
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(new ClusterEngineState.MemberSnapshot(
				computer, trader, true, 1, 1)), Map.of(), Set.of(), 0, null);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start(); service.restoreSnapshot(state);
			var first = service.reserveExternalListenerProcess(cluster, epoch, computer, id(978)).orElseThrow();
			var second = service.reserveExternalListenerProcess(cluster, epoch, computer, id(979)).orElseThrow();
			assertEquals(Set.of(0, 1), Set.of(first.slot(), second.slot()));
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 0, "first", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 1, "second", 2)));
			assertTrue(service.releaseExternalListenerProcess(first));
			assertTrue(service.releaseExternalListenerProcess(second));
			releaseWorker.countDown();

			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (service.snapshot(cluster).orElseThrow().processes().values().stream()
				.anyMatch(process -> process.kind() == ProcessKind.RADIO_LISTENER)
				&& System.nanoTime() < deadline) Thread.sleep(5);
			assertEquals(0, service.snapshot(cluster).orElseThrow().processes().values().stream()
				.filter(process -> process.kind() == ProcessKind.RADIO_LISTENER).count());
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			Optional<FactoryRuntimeService.ExternalListenerProcessReservation> reused = Optional.empty();
			while (reused.isEmpty() && System.nanoTime() < deadline) {
				reused = service.reserveExternalListenerProcess(cluster, epoch, computer, id(980));
				if (reused.isEmpty()) Thread.sleep(5);
			}
			assertTrue(reused.isPresent(),
				"both ordinary slots become reusable after all worker snapshots settle");
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			Optional<FactoryRuntimeService.ExternalListenerProcessReservation> secondReused = Optional.empty();
			while (secondReused.isEmpty() && System.nanoTime() < deadline) {
				secondReused = service.reserveExternalListenerProcess(cluster, epoch, computer, id(981));
				if (secondReused.isEmpty()) Thread.sleep(5);
			}
			assertTrue(secondReused.isPresent());
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void exactListenerReclaimCancelsItsStaleDeferredRelease() throws Exception {
		UUID cluster = id(50_090), epoch = id(50_091), computer = id(50_092), lease = id(50_093);
		ProductionResourceProfile trader = ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64)
			.orElseThrow();
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(new ClusterEngineState.MemberSnapshot(
				computer, trader, true, 1, 1)), Map.of(), Set.of(), 0, null);
		CountDownLatch enteredWorker = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG, ignored -> {
			enteredWorker.countDown();
			try { releaseWorker.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, worker);
		try {
			service.start();
			service.restoreSnapshot(state);
			FactoryRuntimeService.ExternalListenerProcessReservation original = service
				.reserveExternalListenerProcess(cluster, epoch, computer, lease).orElseThrow();
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 0,
				"listener-reclaim-window", 1)));
			assertTrue(enteredWorker.await(3, TimeUnit.SECONDS));
			assertTrue(service.releaseExternalListenerProcess(original));
			FactoryRuntimeService.ExternalListenerProcessReservation reclaimed = service
				.reserveExternalListenerProcess(cluster, epoch, computer, lease).orElseThrow();
			assertEquals(original, reclaimed, "only the exact immutable listener claim is reclaimed");

			releaseWorker.countDown();
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (service.workerInFlight(cluster) && System.nanoTime() < deadline) Thread.sleep(5);
			assertFalse(service.workerInFlight(cluster));
			ClusterEngineState settled = service.snapshot(cluster).orElseThrow();
			assertEquals(1L, settled.processes().values().stream().filter(process ->
				process.processId().equals(reclaimed.processId())).count());
			assertEquals(reclaimed.slot(), settled.processes().get(reclaimed.processId()).owningSlot(),
				"the reclaimed ordinary slot must remain occupied after the old worker completes");

			assertTrue(service.releaseExternalListenerProcess(reclaimed));
			deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (service.snapshot(cluster).orElseThrow().processes().containsKey(reclaimed.processId())
				&& System.nanoTime() < deadline) Thread.sleep(5);
			assertFalse(service.snapshot(cluster).orElseThrow().processes()
				.containsKey(reclaimed.processId()));
		} finally {
			releaseWorker.countDown();
			service.close();
		}
	}

	@Test void restoredExactListenerLeasesAreReclaimedBeforeGlobalCapacityFences() {
		UUID cluster = id(50_100), epoch = id(50_101), computer = id(50_102);
		UUID firstFrequencyLease = id(50_103), secondFrequencyLease = id(50_104);
		ClusterEngineState full = fullListenerCapacityState(cluster, epoch, computer,
			firstFrequencyLease, secondFrequencyLease);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(full);

			FactoryRuntimeService.ExternalListenerProcessReservation first = service
				.reserveExternalListenerProcess(cluster, epoch, computer, firstFrequencyLease).orElseThrow();
			FactoryRuntimeService.ExternalListenerProcessReservation second = service
				.reserveExternalListenerProcess(cluster, epoch, computer, secondFrequencyLease).orElseThrow();
			assertEquals(Set.of(0, 1), Set.of(first.slot(), second.slot()),
				"two restored frequencies on one Computer must reclaim their exact deterministic leases");
			assertTrue(service.reserveExternalListenerProcess(cluster, epoch, computer, id(50_105)).isEmpty(),
				"a new frequency remains fenced once the cluster reaches MAX_PROCESSES");
			assertTrue(service.releaseExternalListenerProcess(first));
			ClusterEngineState afterFirstRetirement = service.snapshot(cluster).orElseThrow();
			assertFalse(afterFirstRetirement.processes().containsKey(first.processId()));
			assertTrue(afterFirstRetirement.processes().containsKey(second.processId()),
				"retiring one frequency must retain the other exact listener lease");
			assertTrue(service.releaseExternalListenerProcess(second));
		}
	}

	@Test void routeCursorAutomaticallyWritesBackAndRestoresThroughRuntimeSnapshot() {
		UUID cluster = id(320), epoch = id(321);
		RouteSelectionScope firstScope = scope(epoch, 322, Items.STONE, 1, 323);
		List<ProductionRouteTable.ProviderSource> feed = List.of(
			new ProductionRouteTable.ProviderSource(firstScope, id(1), id(1), "a", RouteCandidate.ProviderKind.PATTERN,
				7, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE),
			new ProductionRouteTable.ProviderSource(firstScope, id(1), id(2), "b", RouteCandidate.ProviderKind.AGENT,
				7, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE));
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService first = new FactoryRuntimeService(CONFIG)) {
			first.start(); first.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			beginAndComplete(first, cluster, firstScope, feed);
			assertEquals("a", first.trySelectRoute(cluster, firstScope).orElseThrow()
				.winner().orElseThrow().providerKey());
			saved = RuntimeNbt.save(first.snapshot(cluster).orElseThrow());
		}
		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			RouteSelectionScope nextScope = scope(epoch, 324, Items.STONE, 1, 1, 325);
			List<ProductionRouteTable.ProviderSource> nextFeed = feed.stream()
				.map(source -> source.withScope(nextScope)).toList();
			beginAndComplete(restored, cluster, nextScope, nextFeed);
			assertEquals("b", restored.trySelectRoute(cluster, nextScope).orElseThrow()
				.winner().orElseThrow().providerKey());
		}
	}

	@Test void runtimeWaitsForEveryFrozenCollectorThenSelectsExactlyOnce() {
		UUID cluster = id(330), epoch = id(331);
		RouteSelectionScope scope = scope(epoch, 332, Items.STONE, 4, 333);
		ProductionRouteTable.ProviderSource patternSource = new ProductionRouteTable.ProviderSource(
			scope, id(1), id(1), "pattern", RouteCandidate.ProviderKind.PATTERN, 7,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		List<ProductionRouteTable.ProviderSource> nativeSources = List.of(
			new ProductionRouteTable.ProviderSource(scope, id(2), id(2), "agent",
				RouteCandidate.ProviderKind.AGENT, 7, RouteCandidate.Availability.AVAILABLE,
				RouteCandidate.Blocker.NONE),
			new ProductionRouteTable.ProviderSource(scope, id(3), id(3), "gauge",
				RouteCandidate.ProviderKind.FACTORY_GAUGE, 7,
				RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE),
			new ProductionRouteTable.ProviderSource(scope, id(4), id(4), "ordinary",
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER, 7,
				RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.allRequired(scope)));
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN,
				List.of(patternSource)));
			assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.PATTERN));
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.AGENT,
				List.of(nativeSources.getFirst())));
			assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.AGENT));
			assertTrue(service.trySelectRoute(cluster, scope).isEmpty());
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.FACTORY_GAUGE,
				List.of(nativeSources.get(1))));
			assertTrue(service.trySelectRoute(cluster, scope).isEmpty());
			assertTrue(service.completeRouteCollector(cluster, scope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE));
			assertTrue(service.replaceRouteSources(cluster, scope,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER, List.of(nativeSources.get(2))));
			assertTrue(service.completeRouteCollector(cluster, scope,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER));
			assertEquals("pattern", service.trySelectRoute(cluster, scope).orElseThrow()
				.winner().orElseThrow().providerKey());
			assertTrue(service.trySelectRoute(cluster, scope).isEmpty(), "selection and cursor writeback are one-shot");
			assertTrue(service.routeSourceSnapshot(cluster, scope).isEmpty(),
				"a terminal selection must release its complete provider feed");
			assertFalse(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.AGENT,
				List.of(nativeSources.getFirst())), "a late collector must remain fenced after cleanup");
			assertFalse(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.AGENT));
		}
	}

	@Test void completedOptionalCollectorIsAnIdempotentSuccessWhileItsPeerFinishesLater() {
		UUID cluster = id(20_600), epoch = id(20_601);
		RouteSelectionScope scope = scope(epoch, 20_602, Items.STONE, 1, 20_603);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(scope)));
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN,
				List.of(source(scope, 20_604, "pattern"))));
			assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.PATTERN));
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.AGENT,
				List.of()));
			assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.AGENT));
			assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.AGENT),
				"a completed Agent collector must not become the failure condition for a later Gauge retry");
			assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.AGENT,
				List.of()), "an exact completed-feed retry must also remain idempotent");
			assertTrue(service.replaceRouteSources(cluster, scope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE, List.of()));
			assertTrue(service.completeRouteCollector(cluster, scope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE));
			assertTrue(service.replaceRouteSources(cluster, scope,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER, List.of()));
			assertTrue(service.completeRouteCollector(cluster, scope,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER));
			assertTrue(service.trySelectRoute(cluster, scope).isPresent());
		}
	}

	@Test void restoredCollectorProgressIsRetainedUntilANewGenerationResetsEveryCollector() {
		UUID cluster = id(20_610), epoch = id(20_611), computer = id(20_612);
		UUID network = id(20_613), library = id(20_614), search = id(20_615);
		PatternRecord candidate = pattern(id(20_616), library, Items.STONE, OptionalInt.empty(),
			"collector-reload-reset");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		FactoryRuntimeService.ProductionSearchSnapshot interrupted =
			new FactoryRuntimeService.ProductionSearchSnapshot(search, cluster, epoch, network,
				requested, 7, FactoryRuntimeService.ProductionSearchStatus.SELECTING_ROUTE,
				Optional.empty(), true, false);
		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start();
			restored.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 3, 0).orElseThrow())));
			restored.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, false, List.of(candidate)));
			restored.restoreProductionSearchSnapshot(interrupted);

			FactoryRuntimeService.ProductionSearchSnapshot beforeRestart = restored
				.productionSearchSnapshot(cluster, network, requested).orElseThrow();
			assertEquals(7, beforeRestart.searchGeneration());
			assertTrue(beforeRestart.agentCollectorComplete());
			assertFalse(beforeRestart.gaugeCollectorComplete());

			assertTrue(restored.selectProductionPattern(cluster, network, requested).isEmpty());
			FactoryRuntimeService.ProductionSearchSnapshot restarted = restored
				.productionSearchSnapshot(cluster, network, requested).orElseThrow();
			assertEquals(8, restarted.searchGeneration());
			assertFalse(restarted.agentCollectorComplete());
			assertFalse(restarted.gaugeCollectorComplete(),
				"a fresh exact attempt may not inherit either collector acknowledgement");
		}
	}

	@Test void catalogInvalidationRetiresPartialCollectorScopeAndCompletesNewGenerationOnceAcrossReload()
		throws Exception {
		UUID cluster = id(20_620), epoch = id(20_621), computer = id(20_622);
		UUID network = id(20_623), library = id(20_624), search = id(20_625);
		PatternRecord candidate = pattern(id(20_626), library, Items.STONE, OptionalInt.empty(),
			"collector-retire");
		StackKey requested = new StackKey(new ItemStack(Items.STONE));
		AtomicInteger collections = new AtomicInteger();
		ClusterEngineState selectedState;
		FactoryRuntimeService.ProductionSearchSnapshot selectedSearch;
		RouteSelectionScope interruptedScope = new RouteSelectionScope(epoch, search, requested,
			7, 0, id(20_627));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(stateWithMembers(cluster, epoch, Map.of(computer,
				ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 3, 0).orElseThrow())));
			service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
				worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 1, true, List.of(candidate)));
			assertTrue(service.beginRouteSelection(cluster,
				RouteSelectionRequest.patternOnly(interruptedScope)));
			assertTrue(service.replaceRouteSources(cluster, interruptedScope,
				RouteCandidate.ProviderKind.PATTERN,
				List.of(source(interruptedScope, 20_628, "interrupted-pattern"))));
			assertTrue(service.completeRouteCollector(cluster, interruptedScope,
				RouteCandidate.ProviderKind.PATTERN));
			assertTrue(service.replaceRouteSources(cluster, interruptedScope,
				RouteCandidate.ProviderKind.AGENT, List.of()));
			assertTrue(service.completeRouteCollector(cluster, interruptedScope,
				RouteCandidate.ProviderKind.AGENT));
			service.restoreProductionSearchSnapshot(new FactoryRuntimeService.ProductionSearchSnapshot(
				search, cluster, epoch, network, requested, 7,
				FactoryRuntimeService.ProductionSearchStatus.SELECTING_ROUTE,
				Optional.empty(), true, false));

			service.publishProductionLibraryAccess(cluster, Set.of(network), 2, CommunicationFrequency.of("0"), 10,
				worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 2, true, List.of(candidate)));
			FactoryRuntimeService.ProductionSearchSnapshot invalidated = service
				.productionSearchSnapshot(cluster, network, requested).orElseThrow();
			assertEquals(FactoryRuntimeService.ProductionSearchStatus.SEARCHING,
				invalidated.status());
			assertFalse(invalidated.agentCollectorComplete());
			assertFalse(invalidated.gaugeCollectorComplete());
			service.setProductionRouteCollector(request -> {
				collections.incrementAndGet();
				return new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of());
			});

			assertEquals(candidate.patternId(), awaitProductionSelection(service, cluster, network,
				requested).pattern().patternId(),
				"the invalidated generation must not be rejected by its own retired query scope");
			assertEquals(1, collections.get(), "the replacement generation collects exactly once");
			assertFalse(service.replaceRouteSources(cluster, interruptedScope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE, List.of()),
				"a late Gauge completion from the retired generation stays fenced");
			assertFalse(service.completeRouteCollector(cluster, interruptedScope,
				RouteCandidate.ProviderKind.FACTORY_GAUGE));
			selectedState = RuntimeNbt.load(RuntimeNbt.save(
				service.snapshot(cluster).orElseThrow())).orElseThrow();
			selectedSearch = service.productionSearchSnapshot(cluster, network, requested).orElseThrow();
		}

		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG)) {
			restored.start(); restored.restoreSnapshot(selectedState);
			restored.publishProductionLibraryAccess(cluster, Set.of(network), 2, CommunicationFrequency.of("0"), 10,
				worldAddress(candidate.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(library, 1, 2, true, List.of(candidate)));
			restored.restoreProductionSearchSnapshot(selectedSearch);
			restored.setProductionRouteCollector(request -> {
				collections.incrementAndGet();
				return new FactoryRuntimeService.ProductionRouteSources(request.patternSources(),
					List.of(), List.of());
			});
			assertEquals(candidate.patternId(), restored.selectProductionPattern(cluster, network,
				requested).orElseThrow().pattern().patternId());
			assertEquals(1, collections.get(),
				"a durably selected replacement generation may not recollect after reload");
		}
	}

	@Test void routeFeedsAreIsolatedAcrossInterleavedQueryAndOutputScopes() {
		UUID cluster = id(350), epoch = id(351);
		RouteSelectionScope stone = scope(epoch, 352, Items.STONE, 1, 353);
		RouteSelectionScope dirt = scope(epoch, 354, Items.DIRT, 1, 1, 355);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(stone)));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(dirt)));
			assertTrue(service.replaceRouteSources(cluster, dirt, RouteCandidate.ProviderKind.PATTERN,
				List.of(source(dirt, 2, "dirt"))));
			assertTrue(service.replaceRouteSources(cluster, stone, RouteCandidate.ProviderKind.PATTERN,
				List.of(source(stone, 1, "stone"))));
			completeEmptyNativeCollectors(service, cluster, dirt);
			completeEmptyNativeCollectors(service, cluster, stone);
			assertEquals(List.of("stone"), service.routeSourceSnapshot(cluster, stone).stream()
				.map(ProductionRouteTable.ProviderSource::providerKey).toList());
			assertEquals(List.of("dirt"), service.routeSourceSnapshot(cluster, dirt).stream()
				.map(ProductionRouteTable.ProviderSource::providerKey).toList());
			assertTrue(service.completeRouteCollector(cluster, dirt, RouteCandidate.ProviderKind.PATTERN));
			assertTrue(service.completeRouteCollector(cluster, stone, RouteCandidate.ProviderKind.PATTERN));
			assertEquals("stone", service.trySelectRoute(cluster, stone).orElseThrow()
				.winner().orElseThrow().providerKey());
			assertEquals("dirt", service.trySelectRoute(cluster, dirt).orElseThrow()
				.winner().orElseThrow().providerKey());
		}
	}

	@Test void completedUniqueScopesReleaseCapacityAndPersistTheirMonotonicStaleFence() {
		UUID cluster = id(356), epoch = id(357);
		RouteSelectionRequest firstRequest = null;
		net.minecraft.nbt.CompoundTag saved;
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, 2)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			for (int sequence = 0; sequence < 3; sequence++) {
				RouteSelectionScope scope = scope(epoch, 370 + sequence, Items.STONE, 1,
					sequence, 380 + sequence);
				RouteSelectionRequest request = RouteSelectionRequest.patternOnly(scope);
				if (sequence == 0) firstRequest = request;
				assertTrue(service.beginRouteSelection(cluster, request),
					"a completed query must not consume the two-scope active limit");
				assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN,
					List.of(source(scope, 390 + sequence, "route-" + sequence))));
				assertTrue(service.completeRouteCollector(cluster, scope, RouteCandidate.ProviderKind.PATTERN));
				completeEmptyNativeCollectors(service, cluster, scope);
				assertTrue(service.trySelectRoute(cluster, scope).isPresent());
				assertTrue(service.routeSourceSnapshot(cluster, scope).isEmpty());
				assertFalse(service.beginRouteSelection(cluster, request),
					"the monotonic ledger must reject a terminal attempt after source cleanup");
			}
			saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow());
		}
		try (FactoryRuntimeService restored = new FactoryRuntimeService(CONFIG, 2)) {
			restored.start(); restored.restoreSnapshot(RuntimeNbt.load(saved).orElseThrow());
			assertFalse(restored.beginRouteSelection(cluster, Objects.requireNonNull(firstRequest)));
			RouteSelectionScope next = scope(epoch, 373, Items.STONE, 1, 3, 383);
			assertTrue(restored.beginRouteSelection(cluster,
				RouteSelectionRequest.patternOnly(next)));
		}
	}

	@Test void exhaustedMonotonicSelectionLedgerBackpressuresInsteadOfWrapping() {
		UUID cluster = id(358), epoch = id(359);
		ClusterEngineState exhausted = ClusterEngineState.empty(cluster, epoch)
			.withNextRouteSelectionSequence(Long.MAX_VALUE);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG, 2)) {
			service.start(); service.restoreSnapshot(exhausted);
			RouteSelectionScope scope = scope(epoch, 374, Items.STONE, 1, Long.MAX_VALUE, 384);
			assertFalse(service.beginRouteSelection(cluster,
				RouteSelectionRequest.patternOnly(scope)));
			assertEquals(Long.MAX_VALUE, service.snapshot(cluster).orElseThrow()
				.nextRouteSelectionSequence());
		}
	}

	@Test void authoritativeCasSupersedeCannotBeReversedByALateOldBegin() {
		UUID cluster = id(360), epoch = id(361), query = id(362);
		RouteSelectionScope oldScope = new RouteSelectionScope(epoch, query,
			new StackKey(new ItemStack(Items.STONE)), 5, 0, id(363));
		RouteSelectionScope nextScope = new RouteSelectionScope(epoch, query,
			new StackKey(new ItemStack(Items.STONE)), 5, 1, id(364));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(oldScope)));
			assertTrue(service.replaceRouteSources(cluster, oldScope, RouteCandidate.ProviderKind.AGENT,
				List.of(new ProductionRouteTable.ProviderSource(oldScope, id(1), id(2), "old-agent",
					RouteCandidate.ProviderKind.AGENT, 7, RouteCandidate.Availability.AVAILABLE,
					RouteCandidate.Blocker.NONE))));
			assertTrue(service.supersedeRouteSelection(cluster, oldScope,
				RouteSelectionRequest.patternOnly(nextScope)));
			assertFalse(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(oldScope)),
				"a delayed old open must not replace the authoritative newer attempt");
			assertFalse(service.replaceRouteSources(cluster, oldScope, RouteCandidate.ProviderKind.FACTORY_GAUGE,
				List.of()));
			assertFalse(service.completeRouteCollector(cluster, oldScope, RouteCandidate.ProviderKind.AGENT));
			assertTrue(service.routeSourceSnapshot(cluster, oldScope).isEmpty());
			assertTrue(service.replaceRouteSources(cluster, nextScope, RouteCandidate.ProviderKind.AGENT,
				List.of(new ProductionRouteTable.ProviderSource(nextScope, id(1), id(3), "next-agent",
					RouteCandidate.ProviderKind.AGENT, 7, RouteCandidate.Availability.AVAILABLE,
					RouteCandidate.Blocker.NONE))));
			assertEquals(List.of("next-agent"), service.routeSourceSnapshot(cluster, nextScope).stream()
				.map(ProductionRouteTable.ProviderSource::providerKey).toList());
			RouteSelectionScope reusedAttemptId = new RouteSelectionScope(epoch, query,
				new StackKey(new ItemStack(Items.STONE)), 5, 2, oldScope.attemptId());
			assertTrue(service.supersedeRouteSelection(cluster, nextScope,
				RouteSelectionRequest.patternOnly(reusedAttemptId)));
			assertFalse(service.replaceRouteSources(cluster, oldScope, RouteCandidate.ProviderKind.AGENT,
				List.of(new ProductionRouteTable.ProviderSource(oldScope, id(1), id(4), "late-old-agent",
					RouteCandidate.ProviderKind.AGENT, 7, RouteCandidate.Availability.AVAILABLE,
					RouteCandidate.Blocker.NONE))),
				"the monotonic sequence must remain part of the collector scope even if an UUID is reused");
			assertTrue(service.replaceRouteSources(cluster, reusedAttemptId,
				RouteCandidate.ProviderKind.AGENT, List.of()));
			RouteSelectionScope mismatchedNext = new RouteSelectionScope(epoch, query,
				new StackKey(new ItemStack(Items.STONE)), 6, 3, id(366));
			assertFalse(service.supersedeRouteSelection(cluster, oldScope,
				RouteSelectionRequest.patternOnly(mismatchedNext)));
			RouteSelectionScope olderGeneration = new RouteSelectionScope(epoch, query,
				new StackKey(new ItemStack(Items.STONE)), 4, 3, id(365));
			assertFalse(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(olderGeneration)));
		}
	}

	@Test void oldEpochAttemptCannotReopenOrFeedAfterEpochReplacement() {
		UUID cluster = id(367), oldEpoch = id(368), newEpoch = id(369), query = id(370);
		RouteSelectionScope oldScope = new RouteSelectionScope(oldEpoch, query,
			new StackKey(new ItemStack(Items.STONE)), 5, 0, id(371));
		RouteSelectionScope newScope = new RouteSelectionScope(newEpoch, query,
			new StackKey(new ItemStack(Items.STONE)), 5, 0, id(371));
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, oldEpoch));
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(oldScope)));
			service.restoreSnapshot(ClusterEngineState.empty(cluster, newEpoch));
			assertFalse(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(oldScope)),
				"an old epoch cannot consume the reset epoch's first monotonic sequence");
			assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(newScope)));
			assertFalse(service.replaceRouteSources(cluster, oldScope,
				RouteCandidate.ProviderKind.PATTERN, List.of(source(oldScope, 372, "old"))));
			assertTrue(service.replaceRouteSources(cluster, newScope,
				RouteCandidate.ProviderKind.PATTERN, List.of(source(newScope, 373, "new"))));
		}
	}

	@Test void coordinationProofIsIssuedOnlyFromAuthoritativeActiveAlchemistProcessAssociation() {
		UUID cluster = id(340), epoch = id(341), processId = id(342), library = id(343), pattern = id(344);
		ProductionResourceProfile alchemist = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(coordinationState(cluster, epoch, processId,
				pattern, alchemist, ProcessState.RUNNABLE));
			assertFalse(service.associateCoordinationProcess(cluster,
				new CoordinationAssociation(id(999), library, pattern, 7)));
			assertFalse(service.associateCoordinationProcess(cluster,
				new CoordinationAssociation(processId, library, id(998), 7)));
			assertTrue(service.associateCoordinationProcess(cluster,
				new CoordinationAssociation(processId, library, pattern, 7)));
			PatternPriorityAuthority proof = service.coordinationSnapshot(cluster);
			assertTrue(proof.authorizes(cluster, library, pattern, 7));
			assertFalse(proof.authorizes(cluster, library, id(345), 7));
			ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(
				service.snapshot(cluster).orElseThrow())).orElseThrow();
			assertEquals(pattern, reloaded.coordinationAssociations().get(processId).patternId());
		}

		for (ClusterEngineState rejected : List.of(
			coordinationState(cluster, epoch, processId, pattern, alchemist, ProcessState.TERMINAL),
			coordinationState(cluster, epoch, processId, pattern, alchemist,
				ProcessState.RUNNABLE, ProcessKind.PRIORITY_LEASE))) {
			try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
				service.start(); service.restoreSnapshot(rejected);
				assertFalse(service.associateCoordinationProcess(cluster,
					new CoordinationAssociation(processId, library, pattern, 7)));
			}
		}
	}

	@Test void coordinatorCanDrainOnlyItsClustersIntentsWithoutConsumingAnotherClustersWork() throws Exception {
		UUID clusterA = id(1), clusterB = id(2);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			ClusterEngineState stateA = dispatchable(clusterA, id(11), id(21), id(31));
			ClusterEngineState stateB = dispatchable(clusterB, id(12), id(22), id(32));
			service.restoreSnapshot(stateA);
			service.restoreSnapshot(stateB);
			service.publishFact(dispatch(clusterA, stateA, id(41)));
			service.publishFact(dispatch(clusterB, stateB, id(42)));
			List<ProductionEffect> a = waitFor(service, clusterA);
			List<ProductionEffect> b = waitFor(service, clusterB);
			assertEquals(List.of(clusterA), a.stream().map(ProductionEffect::clusterId).toList());
			assertEquals(List.of(clusterB), b.stream().map(ProductionEffect::clusterId).toList());
		}
	}

	@Test void newEpochReplacesStaleStateAndDiscardsItsLateWorkerResult() throws Exception {
		UUID cluster = id(5), oldEpoch = id(15), newEpoch = id(16);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			service.restoreSnapshot(ClusterEngineState.empty(cluster, oldEpoch));
			service.publishFact(new WorldFact.ClusterEpochFact(cluster, newEpoch, 0));
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (!newEpoch.equals(service.snapshot().get(cluster).epochId()) && System.nanoTime() < deadline)
				Thread.sleep(5);
			assertEquals(newEpoch, service.snapshot().get(cluster).epochId());
			service.publishFact(new WorldFact.Signal(cluster, oldEpoch, 1, "late", 1));
			assertEquals(newEpoch, service.snapshot().get(cluster).epochId());
			assertTrue(service.drainDiagnostics().isEmpty());
		}
	}

	@Test void restoredPendingIntentIsUncertainAndNeverAutomaticallyResent() {
		UUID cluster = id(9);
		ClusterEngineState base = dispatchable(cluster, id(19), id(29), id(39));
		ProductionEffect intent = new ProductionEffect.CreateDispatch(id(49), cluster, 2,
			new ProductionEffect.TargetFence(base.epochId(), id(29), 1, 1), id(59),
			new CreateRouteKey(id(69), "route"),
			List.of(new OrderedIngredient(
				new StackKey(new ItemStack(Items.STONE)), 1)));
		ClusterEngineState restored = new ClusterEngineState(base.clusterId(), base.epochId(), 2,
			base.nextSubmissionSequence(), base.memberCursor(), base.processCursor(), base.goals(),
			base.submissionSequences(), base.requestedKinds(), base.processes(), base.members(),
			Map.of(intent.intentId(), intent), Set.of(intent.intentId()), 0, null);
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start(); service.restoreSnapshot(restored);
			assertTrue(service.drainIntents(cluster).isEmpty());
			assertTrue(service.snapshot().get(cluster).pendingIntents().containsKey(intent.intentId()));
			assertTrue(service.snapshot().get(cluster).uncertainIntentIds().contains(intent.intentId()));
			assertTrue(service.drainDiagnostics().stream()
				.anyMatch(diagnostic -> diagnostic.message().contains("uncertain")));
		}
	}

	@Test void saturatedIntentDeliveryRecoversEveryDurablePendingIntent() throws Exception {
		FactoryRuntimeConfig oneIntent = new FactoryRuntimeConfig(1, 500, 4,
			1, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		UUID clusterA = id(6), clusterB = id(7);
		try (FactoryRuntimeService service = new FactoryRuntimeService(oneIntent)) {
			service.start();
			ClusterEngineState stateA = dispatchable(clusterA, id(61), id(71), id(81));
			ClusterEngineState stateB = dispatchable(clusterB, id(62), id(72), id(82));
			service.restoreSnapshot(stateA); service.restoreSnapshot(stateB);
			service.publishFact(dispatch(clusterA, stateA, id(91)));
			service.publishFact(dispatch(clusterB, stateB, id(92)));
			assertEquals(clusterA, waitFor(service, clusterA).getFirst().clusterId());
			assertEquals(clusterB, waitFor(service, clusterB).getFirst().clusterId());
		}
	}

	@Test void repeatedMemberSnapshotsAreCoalescedWithinTheBoundedMailbox() {
		FactoryRuntimeConfig oneFact = new FactoryRuntimeConfig(50, 500, 4,
			1, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		UUID cluster = id(8), epoch = id(18), computer = id(28);
		var profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		try (FactoryRuntimeService service = new FactoryRuntimeService(oneFact)) {
			service.start();
			for (int generation = 0; generation < 20; generation++)
				service.publishFact(new WorldFact.MemberLoadState(cluster, epoch, generation,
					computer, profile, true, 1, 1));
		}
	}

	@Test void commitBudgetIsGlobalPerServerTickAndRotatesClustersFairly() throws Exception {
		FactoryRuntimeConfig oneCommit = new FactoryRuntimeConfig(1, 100_000, 1,
			8, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		UUID a = id(100), b = id(101);
		try (FactoryRuntimeService service = new FactoryRuntimeService(oneCommit)) {
			service.start();
			ClusterEngineState sa = dispatchable(a, id(110), id(120), id(130));
			ClusterEngineState sb = dispatchable(b, id(111), id(121), id(131));
			service.restoreSnapshot(sa); service.restoreSnapshot(sb);
			service.publishFact(dispatch(a, sa, id(140))); service.publishFact(dispatch(b, sb, id(141)));
			waitUntilPending(service, a, b);
			List<ProductionEffect> tickOne = new ArrayList<>();
			tickOne.addAll(service.drainIntents(a, 200)); tickOne.addAll(service.drainIntents(b, 200));
			assertEquals(1, tickOne.size());
			List<ProductionEffect> tickTwo = new ArrayList<>();
			tickTwo.addAll(service.drainIntents(a, 201)); tickTwo.addAll(service.drainIntents(b, 201));
			assertEquals(1, tickTwo.size());
			assertNotEquals(tickOne.getFirst().clusterId(), tickTwo.getFirst().clusterId());
		}
	}

	@Test void ordinaryFactSaturationBackpressuresWithoutRejectingIntentConfirmation() throws Exception {
		FactoryRuntimeConfig oneFact = new FactoryRuntimeConfig(1000, 100, 1,
			1, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		UUID cluster = id(150), epoch = id(151);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), oneFact, ignored -> {
			entered.countDown();
			try { release.await(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		FactoryRuntimeService service = new FactoryRuntimeService(oneFact, worker);
		try {
			service.start();
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 0, "first", 1)));
			assertTrue(entered.await(3, TimeUnit.SECONDS), "hold the actual worker before testing a full mailbox");
			assertTrue(service.publishFact(new WorldFact.Signal(cluster, epoch, 1, "queued", 2)));
			assertFalse(service.publishFact(new WorldFact.Signal(cluster, epoch, 2, "backpressure", 3)));
			assertTrue(service.publishFact(new WorldFact.IntentResult(cluster, epoch, 3, id(152), true, "confirmed")));
		} finally {
			release.countDown();
			service.close();
		}
	}

	private static void waitUntilPending(FactoryRuntimeService service, UUID... clusters) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		while (System.nanoTime() < deadline) {
			Map<UUID, ClusterEngineState> states = service.snapshot();
			if (Arrays.stream(clusters).allMatch(cluster -> !states.get(cluster).pendingIntents().isEmpty())) return;
			Thread.sleep(5);
		}
		fail("intents did not become pending");
	}

	private static List<ProductionEffect> waitFor(FactoryRuntimeService service, UUID clusterId) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		List<ProductionEffect> result = List.of();
		while (result.isEmpty() && System.nanoTime() < deadline) {
			result = service.drainIntents(clusterId);
			if (result.isEmpty()) Thread.sleep(5);
		}
		assertFalse(result.isEmpty(), () -> service.drainDiagnostics().toString());
		return result;
	}

	private static ClusterEngineState dispatchable(UUID cluster, UUID epoch, UUID computer, UUID processId) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(id(processId.getLeastSignificantBits() + 1000), cluster,
			new GoalSubmission(id(processId.getLeastSignificantBits() + 1000), id(90), 1, InputMode.BULK),
			new CreateRouteKey(id(91), "route"), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goal.goalId(), computer, 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		return new ClusterEngineState(cluster, epoch, 1, 1, 0, 0, Map.of(goal.goalId(), goal),
			Map.of(goal.goalId(), 0L), Map.of(goal.goalId(), ProcessKind.GOAL), Map.of(processId, process),
			List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 1, 1)), Map.of(), Set.of(), 0, null);
	}

	private static ClusterEngineState remoteReceiverState(UUID cluster, UUID epoch,
		UUID receiverMember) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0)
			.orElseThrow();
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		return new ClusterEngineState(cluster, epoch, 0, 0, 0, 0, empty.goals(),
			empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			List.of(new ClusterEngineState.MemberSnapshot(receiverMember, profile, true, 1, 1)),
			empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
	}

	private static RemoteProcessLease remoteReceiverLease(long identity, UUID receiverCluster,
		long receiverGeneration) {
		UUID goalId = id(identity), processId = id(identity + 1), sourceCluster = id(identity + 2);
		long revisionFence = 9;
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, processId, 3, revisionFence,
			id(identity + 3), 2, id(identity + 4), revisionFence, InputMode.EXACT, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(identity + 5), "remote-slot-race"),
				Map.of(ingredient, 1L))), Map.of(ingredient, 1L));
		return new RemoteProcessLease(id(identity + 6), goalId, processId, sourceCluster,
			receiverCluster, 3, receiverGeneration, ProductionCapability.ALCHEMIST, request,
			revisionFence);
	}

	private static ClusterEngineState coordinationState(UUID cluster, UUID epoch, UUID processId,
		UUID patternId, ProductionResourceProfile profile, ProcessState processState) {
		return coordinationState(cluster, epoch, processId, patternId, profile, processState,
			ProcessKind.PATTERN_PRIORITY_LEASE);
	}

	private static ClusterEngineState coordinationState(UUID cluster, UUID epoch, UUID processId,
		UUID patternId, ProductionResourceProfile profile, ProcessState processState, ProcessKind kind) {
		UUID goalId = id(processId.getLeastSignificantBits() + 2000), computerId = id(880);
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, patternId, 1, InputMode.BULK),
			new CreateRouteKey(id(882), "coordination"), new PolicySnapshot(1, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, computerId, 0, profile,
			kind, processState, null, 0, Map.of());
		return new ClusterEngineState(cluster, epoch, 1, 1, 0, 0, Map.of(goalId, goal),
			Map.of(goalId, 0L), Map.of(goalId, kind),
			Map.of(processId, process), List.of(new ClusterEngineState.MemberSnapshot(computerId,
				profile, true, 1, 1)), Map.of(), Set.of(), 0, null);
	}

	private static ClusterEngineState librarianState(UUID cluster, UUID epoch) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0)
			.orElseThrow();
		return new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(),
			List.of(new ClusterEngineState.MemberSnapshot(id(990), profile, true, 1, 1)),
			Map.of(), Set.of(), 0, null);
	}

	private static ClusterEngineState librarianStateWithProcessWaiter(UUID cluster, UUID epoch,
		UUID waitingGoalId) {
		ClusterEngineState base = librarianState(cluster, epoch);
		GoalRecord waiting = new GoalRecord(waitingGoalId, cluster,
			new GoalSubmission(waitingGoalId, id(991), 1, InputMode.BULK),
			new CreateRouteKey(id(992), "waiting"), new PolicySnapshot(1, 0, false),
			GoalState.WAITING, WaitReason.PROCESS);
		return new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(waitingGoalId, waiting), Map.of(waitingGoalId, 0L),
			Map.of(waitingGoalId, ProcessKind.GOAL), Map.of(), base.members(),
			Map.of(), Set.of(), 0, null);
	}

	private static ProductionRequestTicket priorityTicket(UUID cluster, long identity,
		long enqueueSequence, int priority) {
		return new ProductionRequestTicket(id(identity), id(identity + 100), 1, cluster,
			id(identity + 200), new CreateRouteKey(id(identity + 300), "priority-" + identity),
			id(identity + 400), InputMode.BULK, 1, enqueueSequence, priority, 1);
	}

	private static AgentAddressRegistration ownerClaim(ProductionRequestTicket ticket,
		UUID ownerCluster, long generation) {
		return new AgentAddressRegistration(ticket.targetRoute(), ticket.agentId(), ownerCluster,
			generation, CommunicationFrequency.of("17"), AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
	}

	private static ProductionRequestTicket copyTicket(ProductionRequestTicket source,
		UUID runId, long fence, UUID sourceClusterId, CreateRouteKey route,
		long materialGeneration) {
		return new ProductionRequestTicket(source.requestId(), runId, fence, sourceClusterId,
			source.agentId(), route, source.patternId(), source.mode(), source.multiplier(),
			source.enqueueSequence(), source.requestedQueuePriority(), materialGeneration);
	}

	private static WorldFact dispatch(UUID cluster, ClusterEngineState state, UUID runId) {
		return new WorldFact.PlayerCommand(cluster, state.epochId(), state.generation(),
			new WorldFact.Command.DispatchCreate(state.processes().keySet().iterator().next(), runId,
				new CreateRouteKey(id(92), "route"),
				List.of(new OrderedIngredient(
					new StackKey(new ItemStack(Items.STONE)), 1))));
	}

	private static RouteSelectionScope scope(UUID epochId, long queryId,
		net.minecraft.world.item.Item item,
		long generation, long attemptId) {
		return scope(epochId, queryId, item, generation, 0, attemptId);
	}

	private static RouteSelectionScope scope(UUID epochId, long queryId,
		net.minecraft.world.item.Item item,
		long generation, long attemptSequence, long attemptId) {
		return new RouteSelectionScope(epochId, id(queryId), new StackKey(new ItemStack(item)),
			generation, attemptSequence, id(attemptId));
	}

	private static ProductionRouteTable.ProviderSource source(RouteSelectionScope scope,
		long identity, String providerKey) {
		return new ProductionRouteTable.ProviderSource(scope, id(identity), id(identity), providerKey,
			RouteCandidate.ProviderKind.PATTERN, 7, RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE);
	}

	private static void beginAndComplete(FactoryRuntimeService service, UUID cluster,
		RouteSelectionScope scope, List<ProductionRouteTable.ProviderSource> feed) {
		assertEquals(service.snapshot(cluster).orElseThrow().nextRouteSelectionSequence(),
			scope.attemptSequence());
		assertTrue(service.beginRouteSelection(cluster, new RouteSelectionRequest(scope,
			EnumSet.of(RouteCandidate.ProviderKind.PATTERN, RouteCandidate.ProviderKind.AGENT),
			EnumSet.of(RouteCandidate.ProviderKind.FACTORY_GAUGE,
				RouteCandidate.ProviderKind.ORDINARY_PROVIDER))));
		for (RouteCandidate.ProviderKind kind : RouteCandidate.ProviderKind.values()) {
			List<ProductionRouteTable.ProviderSource> kindFeed = feed.stream()
				.filter(source -> source.providerKind() == kind).toList();
			assertTrue(service.replaceRouteSources(cluster, scope, kind, kindFeed));
			assertTrue(service.completeRouteCollector(cluster, scope, kind));
		}
	}

	private static void completeEmptyNativeCollectors(FactoryRuntimeService service, UUID cluster,
		RouteSelectionScope scope) {
		for (RouteCandidate.ProviderKind kind : List.of(RouteCandidate.ProviderKind.AGENT,
			RouteCandidate.ProviderKind.FACTORY_GAUGE,
			RouteCandidate.ProviderKind.ORDINARY_PROVIDER)) {
			assertTrue(service.replaceRouteSources(cluster, scope, kind, List.of()));
			assertTrue(service.completeRouteCollector(cluster, scope, kind));
		}
	}

	private static FactoryRuntimeService.SelectedPattern awaitProductionSelection(
		FactoryRuntimeService service, UUID cluster, UUID logisticsNetwork, StackKey requested)
		throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		Optional<FactoryRuntimeService.SelectedPattern> selected = Optional.empty();
		while (selected.isEmpty() && System.nanoTime() < deadline) {
			selected = service.selectProductionPattern(cluster, logisticsNetwork, requested);
			if (selected.isEmpty()) Thread.sleep(5);
		}
		return selected.orElseThrow(() -> new AssertionError(
			"production Library search did not produce a selection: "
				+ service.productionSearchSnapshot(cluster, logisticsNetwork, requested)
				+ " diagnostics=" + service.drainDiagnostics()));
	}

	private static boolean awaitProductionSearchAdoption(FactoryRuntimeService service,
		UUID searchId, UUID goalId) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		boolean adopted = false;
		while (!(adopted = service.adoptProductionSearchWinner(searchId, goalId))
			&& System.nanoTime() < deadline) Thread.sleep(2);
		return adopted;
	}

	private static PatternRecord pattern(UUID patternId, UUID libraryId,
		net.minecraft.world.item.Item item, OptionalInt priorityOverride, String route) {
		return new PatternRecord(patternId, new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, libraryId, BlockPos.ZERO), 0, 0), List.of(),
			List.of(new PatternOutput(new StackKey(new ItemStack(item)), 1)),
			new StandardPatternBody(route), priorityOverride);
	}

	private static ClusterEngineState stateWithComputer(UUID cluster, UUID epoch, UUID computer) {
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0)
			.orElseThrow();
		return new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 0, 0)),
			empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
	}

	private static ClusterEngineState fullListenerCapacityState(UUID cluster, UUID epoch,
		UUID targetComputer, UUID firstLease, UUID secondLease) {
		ProductionResourceProfile librarian = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 5, 0)
			.orElseThrow();
		ArrayList<ClusterEngineState.MemberSnapshot> members = new ArrayList<>();
		for (int index = 0; index < ClusterEngineState.MAX_MEMBERS; index++) {
			UUID computer = index == 0 ? targetComputer : id(60_000L + index);
			members.add(new ClusterEngineState.MemberSnapshot(computer, librarian, true, 1, 1));
		}
		members.sort(Comparator.comparing(ClusterEngineState.MemberSnapshot::computerId));
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>();
		for (ClusterEngineState.MemberSnapshot member : members) for (int slot = 0;
			slot < librarian.processSlots(); slot++) {
			UUID lease = member.computerId().equals(targetComputer) && slot == 0 ? firstLease
				: member.computerId().equals(targetComputer) && slot == 1 ? secondLease
				: id(70_000L + member.computerId().getLeastSignificantBits() * 16L + slot);
			UUID processId = member.computerId().equals(targetComputer) && slot < 2
				? UUID.nameUUIDFromBytes(("external-listener:" + cluster + ":" + epoch + ":"
					+ targetComputer + ":" + lease).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				: id(80_000L + member.computerId().getLeastSignificantBits() * 16L + slot);
			processes.put(processId, new ProcessRecord(processId, lease, member.computerId(), slot,
				librarian, ProcessKind.RADIO_LISTENER, ProcessState.RUNNABLE, null, 0, Map.of()));
		}
		assertEquals(ClusterEngineState.MAX_PROCESSES, processes.size());
		return new ClusterEngineState(cluster, epoch, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(),
			processes, members, Map.of(), Set.of(), 0, null);
	}

	private static ClusterEngineState stateWithMembers(UUID cluster, UUID epoch,
		Map<UUID, ProductionResourceProfile> profiles) {
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		List<ClusterEngineState.MemberSnapshot> members = profiles.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> new ClusterEngineState.MemberSnapshot(entry.getKey(), entry.getValue(),
				true, 1, 1))
			.toList();
		return new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			members, empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
	}

	private static ClusterEngineState specialProcessStateWithWaiter(UUID cluster, UUID epoch,
		UUID computer, UUID specialGoalId, UUID waitingGoalId) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0)
			.orElseThrow();
		GoalRecord waiting = new GoalRecord(waitingGoalId, cluster,
			new GoalSubmission(waitingGoalId, id(20_990), 1, InputMode.BULK),
			new CreateRouteKey(id(20_991), "special-release-waiter"),
			new PolicySnapshot(1, 0, false), GoalState.WAITING, WaitReason.PROCESS);
		ProcessRecord first = new ProcessRecord(id(specialGoalId.getLeastSignificantBits() + 1_000),
			specialGoalId, computer, 0, profile, ProcessKind.SPECULATIVE_EXPANSION,
			ProcessState.RUNNABLE, null, 0, Map.of());
		ProcessRecord second = new ProcessRecord(id(specialGoalId.getLeastSignificantBits() + 1_001),
			specialGoalId, computer, 1, profile, ProcessKind.SPECULATIVE_EXPANSION,
			ProcessState.RUNNABLE, null, 0, Map.of());
		return new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(waitingGoalId, waiting), Map.of(waitingGoalId, 0L),
			Map.of(waitingGoalId, ProcessKind.GOAL), Map.of(first.processId(), first,
				second.processId(), second),
			List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 1, 1)),
			Map.of(), Set.of(), 0, null);
	}

	private static void publishFineAdoptionLibraries(FactoryRuntimeService service, UUID cluster,
		UUID network, PatternRecord stable, PatternRecord fine) {
		service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
			worldAddress(stable.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
			new ProductionRecipeCatalog(stable.source().shelf().subLevelId(), 1, 1, true,
				List.of(stable)));
		service.publishProductionLibraryAccess(cluster, Set.of(network), 1, CommunicationFrequency.of("0"), 10,
			worldAddress(fine.source().shelf()), LibraryRegistration.Reachability.REACHABLE,
			new ProductionRecipeCatalog(fine.source().shelf().subLevelId(), 1, 1, true,
				List.of(fine)));
	}

	private static ClusterEngineState withSpecialProcessGoal(ClusterEngineState state, UUID goalId) {
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes());
		processes.replaceAll((processId, process) -> process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE
			? new ProcessRecord(process.processId(), goalId, process.owningComputerId(),
				process.owningSlot(), process.owningComputer(), process.kind(), process.state(),
				process.waitReason(), process.generation(), process.childRevisionStates())
			: process);
		return new ClusterEngineState(state.clusterId(), state.epochId(), state.generation() + 1,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), state.memberCursor(),
			state.processCursor(), state.goals(), state.submissionSequences(), state.requestedKinds(),
			processes, state.members(), state.pendingIntents(), state.uncertainIntentIds(),
			state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
			state.coordinationAssociations(), state.priorityLeaseAssociations());
	}

	private static ClusterEngineState withGeneration(ClusterEngineState state, long generation) {
		return new ClusterEngineState(state.clusterId(), state.epochId(), generation,
			state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), state.memberCursor(),
			state.processCursor(), state.goals(), state.submissionSequences(), state.requestedKinds(),
			state.processes(), state.members(), state.pendingIntents(), state.uncertainIntentIds(),
			state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(),
			state.coordinationAssociations(), state.priorityLeaseAssociations());
	}

	private static void assertPanelRetirementPreservesRevisionHighWater(PanelRetirement retirement) {
		long base = 91_000L + retirement.ordinal() * 100L;
		UUID cluster = id(base + 1), epoch = id(base + 2), agent = id(base + 3);
		UUID panel = id(base + 4), session = id(base + 5);
		CreateRouteKey input = new CreateRouteKey(id(base + 6), "retire-input");
		CreateRouteKey output = new CreateRouteKey(id(base + 6), "retire-output");
		SpaceAddress panelAddress = new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(92 + retirement.ordinal(), 4, 5));
		ClusterEngineState engine = ClusterEngineState.empty(cluster, epoch);
		AgentFormation formation = AgentFormation.form(List.of(
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.INPUT, input, 0),
			panelAgentEndpoint(agent, cluster, AgentEndpointRole.OUTPUT, output, 0)));
		AgentServiceIdentity declared = new AgentServiceIdentity(agent, formation.agentGeneration());
		try (FactoryRuntimeService service = new FactoryRuntimeService(CONFIG)) {
			service.start();
			AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> authoritative =
				installPanelCommunication(service, new ClusterCommunicationConfiguration(cluster,
					CommunicationFrequency.of("retire"), ClusterServiceRole.AGENT, 3), 7);
			service.restoreSnapshot(engine);
			AgentNewTaskDefaults.FormationPublication firstFormation = publishPanelAgentFormation(
				service, engine, 7, formation, declared, Map.of(), 0);
			TaskProjectionSnapshot.PublishedSnapshot first = service.publishPanelAgentSnapshot(engine,
				Map.of(), firstFormation, Optional.empty(), Set.of(input));
			assertTrue(switch (retirement) {
				case UNFORMATION -> service.revokePanelAgentFormation(cluster, epoch);
				case DECLARATION_REVOKE -> service.revokePanelDeclaration(cluster, epoch);
				case CLUSTER_UNLOAD -> service.revokePanelCluster(cluster, epoch);
			}, retirement.name());
			assertFalse(firstFormation.isCurrentPublication(), retirement.name());
			assertFalse(first.isCurrentPublication(), retirement.name());
			assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
				service.panelPublicationCountsForTest(), retirement.name());

			authoritative.set(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(cluster, CommunicationFrequency.of("retire"),
					ClusterServiceRole.AGENT, 4), 8)));
			AgentNewTaskDefaults.FormationPublication nextFormation = publishPanelAgentFormation(
				service, engine, 8, formation, declared, Map.of(), 1);
			TaskProjectionSnapshot.PublishedSnapshot next = service.publishPanelAgentSnapshot(engine,
				Map.of(), nextFormation, Optional.empty(), Set.of(input));
			assertTrue(next.tasksRevision() > first.tasksRevision(), retirement.name());
			assertTrue(next.newTaskRevision() > first.newTaskRevision(), retirement.name());


		}
	}

	private enum PanelRetirement { UNFORMATION, DECLARATION_REVOKE, CLUSTER_UNLOAD }

	private static AgentNewTaskDefaults.FormationPublication publishPanelAgentFormation(
		FactoryRuntimeService service, ClusterEngineState engine, long directoryGeneration,
		AgentFormation formation, AgentServiceIdentity declared, Map<UUID, String> aliases,
		int normalPriority) {
		AgentNewTaskDefaults.FormationPublication publication = service.publishPanelAgentFormation(
			service.capturePanelAgentFormation(engine, formation, declared, aliases, normalPriority));
		assertEquals(directoryGeneration, publication.directoryGeneration());
		return publication;
	}
	private static AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>>
	installPanelCommunication(FactoryRuntimeService service,
		ClusterCommunicationConfiguration declaration, long directoryGeneration) {
		AtomicReference<Optional<FactoryRuntimeService.PanelCommunicationSnapshot>> source =
			new AtomicReference<>(Optional.of(new FactoryRuntimeService.PanelCommunicationSnapshot(
				declaration, directoryGeneration)));
		service.installPanelCommunicationSnapshotSource(request -> source.get().filter(snapshot ->
			snapshot.declaration().clusterId().equals(request.clusterId())));
		return source;
	}
	private static AgentEndpointState panelAgentEndpoint(UUID agentId, UUID clusterId,
		AgentEndpointRole role, CreateRouteKey route, long generation) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(role == AgentEndpointRole.INPUT ? 90 : 91, 4, 5)), agentId, clusterId,
			generation, role, route, 0, CommunicationFrequency.of("panel-agent-role"),
			AgentCompletionMode.TARGET, Optional.empty(), AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			1, route, true, true);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
