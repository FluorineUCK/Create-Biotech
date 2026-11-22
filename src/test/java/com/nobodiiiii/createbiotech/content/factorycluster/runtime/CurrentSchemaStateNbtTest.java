package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RecoveryProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class CurrentSchemaStateNbtTest {
    @Test void remoteExpansionRetainsEveryHistoricalWireIdentityAndRejectsNewPackageReflection() {
        var request = com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest.capture(
            com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries.provider(),
            id(290), id(291), 0, 1, id(292), 2, id(293), 0,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode.EXACT, 1,
            List.of(new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest.StageInput(0,
                new CreateRouteKey(id(294), "input"), Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)), 1L))),
            Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)), 1L));
        var lease = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease(
            id(295), id(290), id(291), id(296), id(297), 1, 2, ProductionCapability.ALCHEMIST, request, 1);
        var source = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint(id(296), id(298),
            new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0,64,0)), 1, CommunicationFrequency.of("17"), 1, Set.of());
        var receiver = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint(id(297), id(299),
            new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8,64,0)), 2, CommunicationFrequency.of("17"), 1, Set.of(ProductionCapability.ALCHEMIST));
        var offer = com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope.create(id(300), source, receiver,
            CommunicationFrequency.of("17"), new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage.LeaseOffer(lease));
        var exchange = new RemoteExpansionProtocol.RemoteComputationExchange(id(290), id(293), lease, 1, offer,
            Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), RemoteExpansionProtocol.RemoteComputationPhase.REVISION_COMPUTING);
        var job = RemoteExpansionProtocol.RemoteComputationJob.begin(exchange, new FactoryRuntimeService.RemoteComputationAllocation(id(301), receiver.memberId(), 0));
        var lifecycle = new RemoteExpansionProtocol.RemoteComputationLifecycleState(Map.of(job.runId(), exchange), Map.of(job.runId(), job), Map.of());
        var retired = new RemoteExpansionProtocol.RetiredRemoteComputation(
            com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision.logicalChildId(request),
            request.sourceGoalId(), request.runId(), lease.leaseId(), 1, RemoteExpansionProtocol.RemoteComputationTerminalOutcome.CANCELLED,
            request.fingerprint(), Optional.empty());
        for (Object value : List.of(exchange, job, lifecycle, retired,
            new RemoteExpansionProtocol.RemoteOfferAcceptance(exchange, job),
            new RemoteExpansionProtocol.RemoteComputationBeginResult(RemoteExpansionProtocol.RemoteComputationBeginStatus.STARTED, Optional.of(exchange)),
            RemoteExpansionProtocol.RemoteComputationPhase.REVISION_COMPUTING,
            RemoteExpansionProtocol.RemoteComputationJobPhase.REVISION_WORK_PENDING,
            RemoteExpansionProtocol.RemoteComputationBeginStatus.STARTED,
            RemoteExpansionProtocol.RemoteComputationTerminalOutcome.CANCELLED)) {
            CompoundTag encoded = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
            assertEquals(FactoryOrchestrationServer.class.getName() + "$" + value.getClass().getSimpleName(), encoded.getString("Type"));
            assertEquals(value, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, value.getClass()).orElseThrow());
            CompoundTag forbidden = encoded.copy(); forbidden.putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(forbidden, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            CompoundTag extra = encoded.copy(); extra.putInt("Unowned", 1);
            assertTrue(CurrentSchemaStateNbt.load(extra, RegistryAccess.EMPTY, value.getClass()).isEmpty());
        }
        CompoundTag encoded = CurrentSchemaStateNbt.save(lifecycle, RegistryAccess.EMPTY);
        CompoundTag nested = encoded.getCompound("Value").getCompound("jobs").getList("Entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("Value");
        assertEquals(FactoryOrchestrationServer.class.getName() + "$RemoteComputationJob", nested.getString("Type"));
        assertEquals(FactoryOrchestrationServer.class.getName() + "$RemoteComputationJobPhase", nested.getCompound("phase").getString("Type"));
        nested.getCompound("phase").putString("Type", RemoteExpansionProtocol.RemoteComputationJobPhase.class.getName());
        assertTrue(CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, lifecycle.getClass()).isEmpty());
    }

	@Test void localExpansionKeepsExactlyThreeHistoricalWireIdentitiesAtRootAndNestedValues() {
		String old = FactoryOrchestrationServer.class.getName() + "$LocalComputation";
		var request = com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest.capture(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries.provider(),
			id(190), id(191), 0, 1, id(192), 2, id(193), 0,
			com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode.EXACT, 1,
			List.of(new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(194), "input"), Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)), 1L))), Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)), 1L));
		var lease = new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease(id(195), id(190), id(191), id(196), id(196), 1, 1, ProductionCapability.ALCHEMIST, request, 1);
		var job = com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Job.begin(id(193), id(190), lease, id(197), new FactoryRuntimeService.RemoteComputationAllocation(id(198), id(199), 0));
		var lifecycle = new com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Lifecycle(Map.of(job.runId(), job));
		CompoundTag encoded = CurrentSchemaStateNbt.save(lifecycle, RegistryAccess.EMPTY);
		assertEquals(old + "LifecycleState", encoded.getString("Type"));
		assertEquals(old + "Job", CurrentSchemaStateNbt.save(job, RegistryAccess.EMPTY).getString("Type"));
		assertEquals(old + "Phase", CurrentSchemaStateNbt.save(job.phase(), RegistryAccess.EMPTY).getString("Type"));
		var entries = encoded.getCompound("Value").getCompound("jobs").getList("Entries", Tag.TAG_COMPOUND);
		var nested = entries.getCompound(0).getCompound("Value");
		assertEquals(old + "Job", nested.getString("Type"));
		assertEquals(old + "Phase", nested.getCompound("phase").getString("Type"));
		assertEquals(lifecycle, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, lifecycle.getClass()).orElseThrow());
		assertEquals(job, CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(job, RegistryAccess.EMPTY), RegistryAccess.EMPTY, job.getClass()).orElseThrow());
		assertEquals(job.phase(), CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(job.phase(), RegistryAccess.EMPTY), RegistryAccess.EMPTY, job.phase().getClass()).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(new com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Target(id(200), 1, 0, 0), RegistryAccess.EMPTY));
		CompoundTag forbidden = encoded.copy();
		forbidden.putString("Type", lifecycle.getClass().getName());
		assertTrue(CurrentSchemaStateNbt.load(forbidden, RegistryAccess.EMPTY, lifecycle.getClass()).isEmpty());
	}

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markBootstrapped();
	}

	@Test void dormantLibraryWithoutLogisticsRoundTripsWithoutInventingNetworkAccess() {
        UUID library=id(90_007),cluster=id(90_008);
        var dormant=new FactoryOrchestrationServer.DurableProductionLibrary(library,cluster,Set.of(),0,0,
            CommunicationFrequency.of("0"),0,new SpaceAddress(Level.OVERWORLD,null,BlockPos.ZERO),
            LibraryRegistration.Reachability.REACHABLE,new ProductionRecipeCatalog(library,0,0,true,List.of()));
        var restored=CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(dormant,RegistryAccess.EMPTY),
            RegistryAccess.EMPTY,FactoryOrchestrationServer.DurableProductionLibrary.class).orElseThrow();
        assertEquals(dormant,restored);
        assertTrue(restored.logisticsIds().isEmpty());
    }

	@Test
	void currentSchemaPersistsLogisticsDirectoryStateAtItsCurrentVersion() {
		LogisticsNetworkDirectory.State state = new LogisticsNetworkDirectory.State(Map.of(id(101),
			new LogisticsNetworkDirectoryEntry(id(101), 7, Optional.of("Northern Depot"), 3)), 8);
		CompoundTag saved = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);

		assertEquals(6, saved.getInt("Version"));
		assertEquals(state, CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY,
			LogisticsNetworkDirectory.State.class).orElseThrow());
		saved.putInt("Version", 4);
		assertEquals(state, CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY,
			LogisticsNetworkDirectory.State.class).orElseThrow());
	}

	@Test
	void currentSchemaRejectsDuplicateLogisticsDirectoryOrdinals() {
		UUID first = id(102), second = id(103);
		LogisticsNetworkDirectory.State state = new LogisticsNetworkDirectory.State(Map.of(
			first, new LogisticsNetworkDirectoryEntry(first, 1, Optional.empty(), 0),
			second, new LogisticsNetworkDirectoryEntry(second, 2, Optional.empty(), 0)), 3);
		CompoundTag saved = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		ListTag entries = (ListTag) saved.getCompound("Value").getCompound("entries").get("Entries");
		CompoundTag duplicateOrdinal = ((CompoundTag) entries.get(1)).getCompound("Value")
			.getCompound("ordinal");
		duplicateOrdinal.putLong("Value", 1);

		assertTrue(CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY,
			LogisticsNetworkDirectory.State.class).isEmpty());
	}

	@Test
	void previousAliasBearingLogisticsBindingShapeIsRejectedAtItsPriorSchemaVersion() {
		CompoundTag prior = CurrentSchemaStateNbt.save(new LogisticsBinding(id(1011)),
			RegistryAccess.EMPTY);
		prior.putInt("Version", 4);
		CompoundTag value = prior.getCompound("Value");
		value.put("logisticsId", value.get("logisticsNetworkId").copy());
		value.remove("logisticsNetworkId");
		CompoundTag alias = new CompoundTag();
		alias.putString("Kind", "string");
		alias.putString("Value", "former local alias");
		value.put("alias", alias);

		assertTrue(CurrentSchemaStateNbt.load(prior, RegistryAccess.EMPTY,
			LogisticsBinding.class).isEmpty());
	}
	@Test
	void productionJournalInitializesResetWithMinusOneCasAndRestoresAttemptingFailClosed() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		UUID agent = id(11), cluster = id(12), intent = id(13), run = id(14);
		AgentResetService.State initial = new AgentResetService.State(agent, cluster, 0,
			Optional.empty(), false, List.of(), List.of(), 0);
		AgentResetService.StateStore reset = journal.resetStore(RegistryAccess.EMPTY);
		assertTrue(reset.compareAndSet(agent, -1, initial));
		assertEquals(initial, reset.load(agent).orElseThrow());
		assertTrue(!reset.compareAndSet(agent, -1, initial));

		CreateDispatchCommand command = new CreateDispatchCommand(intent, agent, 0, 4, run,
			id(15), 0, new CreateRouteKey(id(16), "input"), 7,
			Map.of(new StackKey(new ItemStack(Items.STONE)), 1L));
		journal.inputCommitStore(RegistryAccess.EMPTY)
			.persist(CreateLogisticsGateway.PersistedCommit.attempting(command));
		int[] dispatches = {0};
		CreateLogisticsGateway recovered = new CreateLogisticsGateway(ignored ->
			new CreateLogisticsGateway.CommitContext(agent, 0, 4, command.route(), true, 7,
				AgentInputPhase.DISPATCH_INTENT, command), ignored -> {
				dispatches[0]++; return new CreateLogisticsGateway.NativeDispatchFact(true,
					List.of(new com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger.NativeOrderIdentity(1,
						java.util.Set.of(0))));
			}, () -> true, journal.inputCommitStore(RegistryAccess.EMPTY));
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_UNCERTAIN,
			recovered.commit(command).rejection());
		assertEquals(0, dispatches[0], "an ATTEMPTING reload must never redispatch Create");
	}

	@Test
	void communicationFrequencyUsesItsOwnFailClosedLiteralSchema() {
		CompoundTag saved = CurrentSchemaStateNbt.save(CommunicationFrequency.of("  Raw  "),
			RegistryAccess.EMPTY);
		assertEquals("communication_frequency", saved.getCompound("Value").getString("Kind"));
		assertEquals(CommunicationFrequency.of("  Raw  "), CurrentSchemaStateNbt.load(saved,
			RegistryAccess.EMPTY, CommunicationFrequency.class).orElseThrow());
		assertTrue(CurrentSchemaStateNbt.load(saved, RegistryAccess.EMPTY, String.class).isEmpty());
	}

	@Test
	void clusterCommunicationStorePersistsOnlyExactCurrentSchemaRevisionCas() throws Exception {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		UUID cluster = id(18);
		var store = journal.clusterCommunicationStore(RegistryAccess.EMPTY);
		var initial = new com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ClusterCommunicationConfiguration(cluster,
				com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of(" A "),
				com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole.STANDARD,
				0);
		var next = new com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ClusterCommunicationConfiguration(cluster,
				com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("A"),
				com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole.AGENT,
				1);

		assertTrue(store.compareAndSet(cluster, -1, initial));
		assertFalse(store.compareAndSet(cluster, -1, initial));
		assertTrue(store.compareAndSet(cluster, 0, next));
		assertEquals(next, store.load(cluster).orElseThrow());

		var field = FactoryOrchestrationSavedData.class.getDeclaredField("entries");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, CompoundTag> entries = (Map<String, CompoundTag>) field.get(journal);
		CompoundTag foreign = CurrentSchemaStateNbt.save(next, RegistryAccess.EMPTY);
		((CompoundTag) foreign.get("Value")).remove("role");
		entries.put("cluster_communication/" + cluster, foreign);
		assertThrows(IllegalStateException.class, () -> store.load(cluster));
		assertFalse(journal.persistenceValid());
	}

	@Test
	void clusterCommunicationKeyAndEmbeddedClusterIdMustMatchAfterSavedDataReload() throws Exception {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		UUID keyed = id(181), embedded = id(182);
		var store = journal.clusterCommunicationStore(RegistryAccess.EMPTY);
		var foreign = new com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ClusterCommunicationConfiguration(embedded, CommunicationFrequency.of("A"),
				com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole.AGENT, 0);
		var field = FactoryOrchestrationSavedData.class.getDeclaredField("entries");
		field.setAccessible(true);
		@SuppressWarnings("unchecked") Map<String, CompoundTag> entries = (Map<String, CompoundTag>) field.get(journal);
		entries.put("cluster_communication/" + keyed, CurrentSchemaStateNbt.save(foreign, RegistryAccess.EMPTY));
		assertThrows(IllegalStateException.class, () -> store.load(keyed));
		assertFalse(journal.persistenceValid());
	}

	@Test
	void productionJournalEnumeratesOnlyExactCurrentSchemaEntriesForRecovery() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		AgentResetService.State first = new AgentResetService.State(id(21), id(22), 0,
			Optional.empty(), false, List.of(), List.of(), 0);
		AgentResetService.State unrelated = new AgentResetService.State(id(23), id(24), 0,
			Optional.empty(), false, List.of(), List.of(), 0);
		journal.write("input_contract/first", first, RegistryAccess.EMPTY);
		journal.write("reset/unrelated", unrelated, RegistryAccess.EMPTY);
		assertEquals(Map.of("input_contract/first", first),
			journal.readPrefix("input_contract/", AgentResetService.State.class,
				RegistryAccess.EMPTY));
	}

	@Test
	void productionJournalBatchPreflightsEveryValueBeforePublishingAnyEndpointMutation() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		AgentResetService.State prior = new AgentResetService.State(id(31), id(32), 0,
			Optional.empty(), false, List.of(), List.of(), 0);
		AgentResetService.State next = new AgentResetService.State(id(33), id(34), 0,
			Optional.empty(), false, List.of(), List.of(), 0);
		journal.write("stable", prior, RegistryAccess.EMPTY);

		assertThrows(IllegalArgumentException.class, () -> journal.writeBatch(Map.of(
			"endpoint/a", next,
			"endpoint/b", new Object()), RegistryAccess.EMPTY));
		assertEquals(prior, journal.read("stable", AgentResetService.State.class,
			RegistryAccess.EMPTY).orElseThrow());
		assertTrue(journal.read("endpoint/a", AgentResetService.State.class,
			RegistryAccess.EMPTY).isEmpty(), "failed preflight must publish no partial endpoint batch");
	}

	@Test
	@SuppressWarnings("unchecked")
	void presentMalformedSingletonFencesJournalInsteadOfLookingAbsent() throws Exception {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		var field = FactoryOrchestrationSavedData.class.getDeclaredField("entries");
		field.setAccessible(true);
		Map<String, CompoundTag> entries = (Map<String, CompoundTag>) field.get(journal);
		CompoundTag malformed = new CompoundTag();
		malformed.putInt("Version", 999);
		entries.put("recovery/global", malformed);

		assertThrows(IllegalStateException.class,
			() -> journal.recoveryStore(RegistryAccess.EMPTY).load());
		assertFalse(journal.persistenceValid(),
			"a malformed current-schema singleton must fence subsequent writes");
	}

	private static void markBootstrapped() {
		try {
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void agentCoordinatorStoreRoundTripsOnlyTheCurrentBoundedSchema() {
		UUID agent = id(1), cluster = id(2), network = id(3);
		SpaceAddress endpointAddress = new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(4, 5, 6));
		CreateRouteKey inputRoute = new CreateRouteKey(network, "input");
		CreateRouteKey warehouse = new CreateRouteKey(network, "warehouse");
		AgentEndpointState endpoint = new AgentEndpointState(endpointAddress, agent, cluster, 7,
			AgentEndpointRole.INPUT, inputRoute, 4, CommunicationFrequency.of("91"), AgentCompletionMode.QUIET,
			Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, 8, warehouse, true, true);
		AgentAddressRegistration publication = new AgentAddressRegistration(inputRoute, agent,
			cluster, 9, CommunicationFrequency.of("91"), AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
		AgentCoordinator.PersistedState state = new AgentCoordinator.PersistedState(agent,
			cluster, 7, endpointAddress, List.of(endpoint), Optional.empty(), Optional.empty(),
			false, 9, List.of(publication), 32, Map.of());

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			AgentCoordinator.PersistedState.class).orElseThrow());

		CompoundTag oldVersion = encoded.copy();
		oldVersion.putInt("Version", 0);
		assertTrue(CurrentSchemaStateNbt.load(oldVersion, RegistryAccess.EMPTY,
			AgentCoordinator.PersistedState.class).isEmpty());
		CompoundTag foreign = encoded.copy();
		foreign.putBoolean("Legacy", true);
		assertTrue(CurrentSchemaStateNbt.load(foreign, RegistryAccess.EMPTY,
			AgentCoordinator.PersistedState.class).isEmpty());
		CompoundTag nestedForeign = encoded.copy();
		CompoundTag value = (CompoundTag) nestedForeign.get("Value");
		((CompoundTag) value.get("agentGeneration")).putBoolean("Legacy", true);
		assertTrue(CurrentSchemaStateNbt.load(nestedForeign, RegistryAccess.EMPTY,
			AgentCoordinator.PersistedState.class).isEmpty(),
			"every nested value must also be exact current schema");
	}

	@Test
	void unorderedCurrentSchemaContainersHaveCanonicalEncoding() {
		LinkedHashMap<String, Integer> forward = new LinkedHashMap<>();
		forward.put("alpha", 1); forward.put("beta", 2);
		LinkedHashMap<String, Integer> reverse = new LinkedHashMap<>();
		reverse.put("beta", 2); reverse.put("alpha", 1);
		CompoundTag first = CurrentSchemaStateNbt.save(new MapState(forward), RegistryAccess.EMPTY);
		CompoundTag second = CurrentSchemaStateNbt.save(new MapState(reverse), RegistryAccess.EMPTY);
		assertEquals(first, second,
			"durable current-schema bytes cannot depend on map insertion order");
	}

	@Test
	void durableAgentReadySourceOptionalLongRoundTripsPresentAndEmpty() {
		for (OptionalLong value : List.of(OptionalLong.empty(), OptionalLong.of(37))) {
			OptionalLongState state = new OptionalLongState(value);
			CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
			assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
				OptionalLongState.class).orElseThrow());
		}
	}

	@Test
	void durableProductionLibraryRoundTripsComponentPredicatesAndPriorityOverrides() {
		UUID library = id(251), cluster = id(252), logistics = id(253);
		SpaceAddress shelf = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(4, 2, 7));
		DataComponentPredicate predicate = DataComponentPredicate.builder()
			.expect(DataComponents.MAX_STACK_SIZE, 1).build();
		ItemStack outputStack = new ItemStack(Items.GOLD_INGOT);
		outputStack.set(DataComponents.MAX_STACK_SIZE, 1);
		PatternRecord pattern = new PatternRecord(id(254), new PatternPageKey(new RecipeSourceLocation(shelf.dimension(), shelf.subLevelId(), shelf.localPos()), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_ingot"), null,
				2, predicate)), List.of(new PatternOutput(new StackKey(outputStack), 1)),
			new StandardPatternBody("component_library"), OptionalInt.of(7));
		ProductionRecipeCatalog catalog = new ProductionRecipeCatalog(library, 3, 4, true,
			List.of(pattern));
		FactoryOrchestrationServer.DurableProductionLibrary durable =
			new FactoryOrchestrationServer.DurableProductionLibrary(library, cluster,
				Set.of(logistics), 5, 6, CommunicationFrequency.of("11"), 13, shelf,
				LibraryRegistration.Reachability.REACHABLE, catalog);

		CompoundTag encoded = CurrentSchemaStateNbt.save(durable, RegistryAccess.EMPTY);
		CompoundTag storedPattern = encoded.getCompound("Value").getCompound("catalog")
			.getCompound("patterns").getList("Values", Tag.TAG_COMPOUND).getCompound(0);
		CompoundTag storedInput = storedPattern.getCompound("inputs").getList("Values", Tag.TAG_COMPOUND).getCompound(0);
		CompoundTag storedOutput = storedPattern.getCompound("outputs").getList("Values", Tag.TAG_COMPOUND).getCompound(0);
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternIngredient", storedInput.getString("Type"));
		assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternOutput", storedOutput.getString("Type"));
		assertEquals(durable, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FactoryOrchestrationServer.DurableProductionLibrary.class).orElseThrow());
		storedInput.putString("Type", PatternIngredient.class.getName());
		assertTrue(CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FactoryOrchestrationServer.DurableProductionLibrary.class).isEmpty(),
			"Relocated Java names are not new serialized type identifiers");
	}

	@Test
	void relocatedRecipeLeavesKeepHistoricalRootNamesWithoutAuthorizingOtherApplicationRecords() {
		List<Object> leaves = List.of(new StackKey(new ItemStack(Items.PAPER)),
			new PatternIngredient(ResourceLocation.withDefaultNamespace("paper"), null, 9, DataComponentPredicate.EMPTY),
			new PatternOutput(new StackKey(new ItemStack(Items.PAPER)), 4));
		for (Object leaf : leaves) {
			CompoundTag encoded = CurrentSchemaStateNbt.save(leaf, RegistryAccess.EMPTY);
			assertEquals("com.nobodiiiii.createbiotech.content.factorycluster.pattern." + leaf.getClass().getSimpleName(),
				encoded.getString("Type"));
			assertEquals(leaf, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, leaf.getClass()).orElseThrow());
			encoded.putString("Type", leaf.getClass().getName());
			assertTrue(CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY, leaf.getClass()).isEmpty());
		}
		var unrelated = new com.nobodiiiii.createbiotech.content.biocomputing.application.production
			.PatternPriorityPolicy.Resolution(OptionalInt.empty(), false, 8);
		assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(unrelated, RegistryAccess.EMPTY));
	}

	@Test
	void selectedProviderDescriptorRoundTripsEveryTypedIdentityAndSelectionFenceField() {
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		RouteSelectionScope fence = new RouteSelectionScope(id(261), id(262), output,
			17, 19, id(263));
		SelectedProviderDescriptor descriptor = new SelectedProviderDescriptor(fence, id(264),
			id(265), "factory-gauge/overworld/4,5,6/2",
			RouteCandidate.ProviderKind.FACTORY_GAUGE, 31,
			RouteCandidate.Availability.AVAILABLE);

		CompoundTag encoded = CurrentSchemaStateNbt.save(descriptor, RegistryAccess.EMPTY);
		assertEquals(descriptor, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			SelectedProviderDescriptor.class).orElseThrow());
		FactoryRuntimeService.ProductionSearchSnapshot snapshot =
			new FactoryRuntimeService.ProductionSearchSnapshot(fence.queryId(), id(266),
				fence.epochId(), id(267), output, fence.searchGeneration(),
				FactoryRuntimeService.ProductionSearchStatus.SELECTED, Optional.of(descriptor),
				true, true, true);
		CompoundTag encodedSnapshot = CurrentSchemaStateNbt.save(snapshot, RegistryAccess.EMPTY);
		assertEquals(snapshot, CurrentSchemaStateNbt.load(encodedSnapshot, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).orElseThrow());
		FactoryRuntimeService.ProductionSearchSnapshot progress =
			new FactoryRuntimeService.ProductionSearchSnapshot(fence.queryId(), id(266),
				fence.epochId(), id(267), output, fence.searchGeneration(),
				FactoryRuntimeService.ProductionSearchStatus.SELECTING_ROUTE, Optional.empty(),
				true, false, false);
		CompoundTag encodedProgress = CurrentSchemaStateNbt.save(progress, RegistryAccess.EMPTY);
		FactoryRuntimeService.ProductionSearchSnapshot decodedProgress = CurrentSchemaStateNbt.load(
			encodedProgress, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).orElseThrow();
		assertEquals(progress, decodedProgress);
		assertTrue(decodedProgress.agentCollectorComplete());
		assertFalse(decodedProgress.gaugeCollectorComplete(),
			"Agent and Gauge progress must remain independent in the durable current schema");
		assertFalse(decodedProgress.ordinaryCollectorComplete(),
			"ordinary provider progress is an independent durable collector fence");
		assertThrows(IllegalArgumentException.class,
			() -> new FactoryRuntimeService.ProductionSearchSnapshot(fence.queryId(), id(266),
				fence.epochId(), id(267), output, fence.searchGeneration(),
				FactoryRuntimeService.ProductionSearchStatus.SEARCHING, Optional.empty(),
				true, false),
			"a restarted search may not inherit completion from the abandoned route attempt");
		CompoundTag missingCollectorProgress = encodedProgress.copy();
		((CompoundTag) missingCollectorProgress.get("Value")).remove("agentCollectorComplete");
		assertTrue(CurrentSchemaStateNbt.load(missingCollectorProgress, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).isEmpty(),
			"collector progress is required current-schema state, not an inferred legacy default");
		CompoundTag missingOrdinaryProgress = encodedProgress.copy();
		((CompoundTag) missingOrdinaryProgress.get("Value")).remove("ordinaryCollectorComplete");
		assertTrue(CurrentSchemaStateNbt.load(missingOrdinaryProgress, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).isEmpty(),
			"ordinary collector progress is required current-schema state");
		CompoundTag providerlessSnapshot = encodedSnapshot.copy();
		((CompoundTag) providerlessSnapshot.get("Value")).remove("selectedProvider");
		assertTrue(CurrentSchemaStateNbt.load(providerlessSnapshot, RegistryAccess.EMPTY,
			FactoryRuntimeService.ProductionSearchSnapshot.class).isEmpty(),
			"the prior provider-less search snapshot is not part of the current schema");
		CompoundTag missingFence = encoded.copy();
		((CompoundTag) missingFence.get("Value")).remove("selectionScope");
		assertTrue(CurrentSchemaStateNbt.load(missingFence, RegistryAccess.EMPTY,
			SelectedProviderDescriptor.class).isEmpty(),
			"the current schema must not synthesize a selection/generation fence");
	}

	@Test
	void oneServerJournalRetainsLiveAttachmentAuthorityAcrossCompositionRootReload() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();

		assertSame(journal.attachmentBindingRegistry(RegistryAccess.EMPTY),
			journal.attachmentBindingRegistry(RegistryAccess.EMPTY),
			"restarting only the orchestration composition root must not impersonate an inventory replacement");
	}

	@Test
	void currentSchemaCarriesTheRemoteProtocolsFullBoundedCanonicalBytePayload() {
		byte[] canonical = new byte[8_192];
		for (int index = 0; index < canonical.length; index++) canonical[index] = (byte) index;
		CompoundTag encoded = CurrentSchemaStateNbt.save(canonical, RegistryAccess.EMPTY);
		assertTrue(java.util.Arrays.equals(canonical, CurrentSchemaStateNbt.load(encoded,
			RegistryAccess.EMPTY, byte[].class).orElseThrow()));
		assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(
			new byte[(1 << 20) + 1], RegistryAccess.EMPTY));
	}

	@Test
	void productionOutputProbeRoundTripsItsEpochCountsAndNativePackagerDrainEvidence() {
		CreateRouteKey output = new CreateRouteKey(id(31), "agent-output");
		SpaceAddress packager = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(7, 8, 9));
		SpaceAddress inventory = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(6, 8, 9));
		InventoryIdentity identity = new InventoryIdentity(inventory, Direction.EAST,
			ResourceLocation.withDefaultNamespace("chest"),
			Optional.empty(), id(34), 0);
		FactoryOrchestrationServer.OutputProbeState state =
			new FactoryOrchestrationServer.OutputProbeState(id(32), id(33), 4,
				Map.of(output, Optional.of(new StackKey(new ItemStack(Items.STONE)))),
				Map.of(output, 11L), Map.of(output, 13L), Map.of(output,
					new FactoryOrchestrationServer.ManagedOutputEndpoint(packager, identity)),
				Set.of(packager));

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FactoryOrchestrationServer.OutputProbeState.class).orElseThrow());
	}

	@Test
	void durableRadioOutboxRoundTripsRecoveryMessagesWithIndependentLogicalGenerations() {
		RadioEndpoint.Hop source = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(id(41), id(42)), 3);
		RadioEndpoint.Hop target = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(id(43), id(44)), 5);
		RemoteRequestKey logical = new RemoteRequestKey(id(45), id(46), 7, 900, 901);
		RecoveryProtocolMessage.StatusQuery message = new RecoveryProtocolMessage.StatusQuery(
			logical, new RecoveryProtocolMessage.PeerFence(source, target));
		RadioEnvelope envelope = new RadioEnvelope(id(47), source.key().clusterId(),
			source.key().memberId(), target.key().clusterId(), target.key().memberId(), CommunicationFrequency.of("11"), 0,
			source.generation(), target.generation(), message.fingerprint(), message);
		DurableRadioOutbox.State state = new DurableRadioOutbox.State(Map.of(envelope.messageId(),
			new DurableRadioOutbox.Entry(envelope, List.of(), RadioRouter.RouteStatus.QUEUED)));

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).orElseThrow());
	}

	@Test
	void durableRadioAuthorityRoundTripsCurrentAndRetiredFences() {
		RadioEndpoint current = new RadioEndpoint(id(50), id(51),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), 7,
			CommunicationFrequency.of("authority"), 1, Set.of());
		SubnetBoundaryBinding retired = new SubnetBoundaryBinding(id(52), id(53), id(54),
			id(55), CommunicationFrequency.of("boundary"),
			new RadioEndpoint.Hop(new RadioEndpoint.Key(id(52), id(56)), 8),
			new RadioEndpoint.Hop(new RadioEndpoint.Key(id(55), id(57)), 9));
		RadioRouter.AuthorityState state = new RadioRouter.AuthorityState(11,
			Map.of(current.key(), new RadioRouter.EndpointAuthority(7, Optional.of(current))),
			Map.of(retired.sourceClusterId(), new RadioRouter.BoundaryAuthority(4, retired, false)));

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			RadioRouter.AuthorityState.class).orElseThrow());
	}

	@Test
	void durableListenerRetirementObligationRoundTripsThroughTheProductionJournalKey() {
		CommunicationFrequency frequency = CommunicationFrequency.of("retiring-listener");
		RadioEndpoint source = new RadioEndpoint(id(58), id(59),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), 3, frequency, 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint relay = new RadioEndpoint(id(60), id(61),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO.east()), 4, frequency, 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(source.clusterId(), id(62),
			id(63), relay.clusterId(), frequency, source.hop(), relay.hop());
		ExternalListenerService.ListenerOwner owner =
			new ExternalListenerService.ListenerOwner(id(64), id(65), id(66));
		ExternalListenerService.Publication publication =
			new ExternalListenerService.Publication(source, relay, binding);
		ExternalListenerService.RetirementState state = new ExternalListenerService.RetirementState(
			List.of(new ExternalListenerService.RetirementObligation(owner, frequency, publication)));

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		assertEquals(state, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			ExternalListenerService.RetirementState.class).orElseThrow());
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		journal.write(FactoryOrchestrationServer.RADIO_LISTENER_RETIREMENTS_KEY, state,
			RegistryAccess.EMPTY);
		assertEquals(state, journal.read(FactoryOrchestrationServer.RADIO_LISTENER_RETIREMENTS_KEY,
			ExternalListenerService.RetirementState.class, RegistryAccess.EMPTY).orElseThrow());
	}

	@Test
	void durableOutboxRoundTripsBoundaryForwardedTraceEvidence() {
		UUID networkA = id(60), networkB = id(61), networkC = id(62);
		UUID clusterA = id(63), clusterB = id(64), clusterC = id(65);
		RadioEndpoint source = new RadioEndpoint(clusterB, id(66),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), 1,
			CommunicationFrequency.of("external"), 1, Set.of());
		RadioEndpoint target = new RadioEndpoint(clusterC, id(67),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO.east()), 1,
			CommunicationFrequency.of("external"), 1, Set.of());
		SubnetRouteTrace trace = SubnetRouteTrace.start(networkA, clusterA)
			.visit(networkB, clusterB);
		RadioEnvelope envelope = RadioEnvelope.create(id(68), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("boundary"), trace);
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(clusterB, networkB, networkC,
			clusterC, CommunicationFrequency.of("external"), source.hop(), target.hop());
		RadioRouter authorizer = new RadioRouter(16, 48, 8);
		authorizer.register(source);
		authorizer.register(target);
		assertTrue(authorizer.registerBoundaryBinding(binding));
		RadioEnvelope forwarded = authorizer.forwardBoundary(envelope).orElseThrow();
		DurableRadioOutbox.State state = new DurableRadioOutbox.State(Map.of(forwarded.messageId(),
			new DurableRadioOutbox.Entry(forwarded, List.of(), RadioRouter.RouteStatus.QUEUED)));

		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		DurableRadioOutbox.State reloaded = CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).orElseThrow();
		assertEquals(state, reloaded);
		CompoundTag currentTransitionAtLegacyVersion = encoded.copy();
		currentTransitionAtLegacyVersion.putInt("Version", 1);
		assertTrue(CurrentSchemaStateNbt.load(currentTransitionAtLegacyVersion,
			RegistryAccess.EMPTY, DurableRadioOutbox.State.class).isEmpty(),
			"a v2 structured transition cannot masquerade as v1 data");
		CompoundTag priorTransitionSchema = encoded.copy();
		priorTransitionSchema.putInt("Version", 2);
		CompoundTag priorEvidence = (CompoundTag) legacyEnvelope(priorTransitionSchema)
			.get("boundaryTransition");
		priorEvidence.remove("sourceHop");
		priorEvidence.remove("targetHop");
		assertTrue(CurrentSchemaStateNbt.load(priorTransitionSchema, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty(),
			"v2 transition proof lacking exact gateway hops must fail closed");
		DurableRadioOutbox.StateStore store = new DurableRadioOutbox.StateStore() {
			@Override public Optional<DurableRadioOutbox.State> load() { return Optional.of(reloaded); }
			@Override public void persist(DurableRadioOutbox.State ignored) {}
		};
		RadioRouter missingBinding = new RadioRouter(16, 48, 8);
		missingBinding.register(source);
		missingBinding.register(target);

		DurableRadioOutbox retryableOutbox = new DurableRadioOutbox(1, store);
		assertDoesNotThrow(() -> retryableOutbox.pump(missingBinding, 1));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			retryableOutbox.status(forwarded.messageId()).orElseThrow());
		assertEquals(1, missingBinding.activeRouteCount());
		assertTrue(missingBinding.registerBoundaryBinding(binding));
		assertEquals(1, assertDoesNotThrow(() -> retryableOutbox.pump(missingBinding, 2)).size(),
			"the exact restored binding must resume the durable route once");
		assertTrue(retryableOutbox.pump(missingBinding, 3).isEmpty(),
			"a recovered durable route must not replay its final delivery");
		RadioRouter removedBinding = new RadioRouter(16, 48, 8);
		removedBinding.register(source);
		removedBinding.register(target);
		assertTrue(removedBinding.registerBoundaryBinding(binding));
		assertTrue(removedBinding.unregisterBoundaryBinding(binding));
		DurableRadioOutbox removedOutbox = new DurableRadioOutbox(1, store);
		assertDoesNotThrow(() -> removedOutbox.pump(removedBinding, 1));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			removedOutbox.status(forwarded.messageId()).orElseThrow());
		assertEquals(1, removedBinding.activeRouteCount());
		RadioRouter mismatchedBinding = new RadioRouter(16, 48, 8);
		mismatchedBinding.register(source);
		mismatchedBinding.register(target);
		assertFalse(mismatchedBinding.registerBoundaryBinding(new SubnetBoundaryBinding(clusterB,
			networkB, networkC, id(69), CommunicationFrequency.of("external"), source.hop(),
			new RadioEndpoint.Hop(new RadioEndpoint.Key(id(69), id(70)), 1))));
		DurableRadioOutbox mismatchedOutbox = new DurableRadioOutbox(1, store);
		assertDoesNotThrow(() -> mismatchedOutbox.pump(mismatchedBinding, 1));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			mismatchedOutbox.status(forwarded.messageId()).orElseThrow());
		assertEquals(1, mismatchedBinding.activeRouteCount());
		RadioEndpoint alternateTarget = new RadioEndpoint(clusterC, id(88),
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO.south()), 1,
			CommunicationFrequency.of("external"), 1, Set.of());
		RadioRouter replacedBinding = new RadioRouter(16, 48, 8);
		replacedBinding.register(source);
		replacedBinding.register(target);
		replacedBinding.register(alternateTarget);
		assertTrue(replacedBinding.registerBoundaryBinding(binding));
		assertTrue(replacedBinding.replaceBoundaryBinding(binding, new SubnetBoundaryBinding(clusterB,
			networkB, networkC, clusterC, CommunicationFrequency.of("external"), source.hop(),
			alternateTarget.hop())));
		DurableRadioOutbox replacedOutbox = new DurableRadioOutbox(1, store);
		assertDoesNotThrow(() -> replacedOutbox.pump(replacedBinding, 1));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			replacedOutbox.status(forwarded.messageId()).orElseThrow());
		assertEquals(1, replacedBinding.activeRouteCount());
		RadioRouter restoredBinding = new RadioRouter(16, 48, 8);
		restoredBinding.register(source);
		restoredBinding.register(target);
		assertTrue(restoredBinding.registerBoundaryBinding(binding));

		assertDoesNotThrow(() -> new DurableRadioOutbox(1, store).pump(restoredBinding, 1));
	}

	@Test
	void currentSchemaMigratesOnlyLegacyUnmarkedEnvelopes() {
		RadioEndpoint.Hop source = new RadioEndpoint.Hop(new RadioEndpoint.Key(id(71), id(72)), 3);
		RadioEndpoint.Hop target = new RadioEndpoint.Hop(new RadioEndpoint.Key(id(73), id(74)), 4);
		ProcessProtocolMessage.Data payload = new ProcessProtocolMessage.Data("legacy-unmarked");
		RadioEnvelope envelope = new RadioEnvelope(id(75), source.key().clusterId(),
			source.key().memberId(), target.key().clusterId(), target.key().memberId(),
			CommunicationFrequency.of("legacy"), 0, source.generation(), target.generation(),
			payload.fingerprint(), payload);
		DurableRadioOutbox.State state = new DurableRadioOutbox.State(Map.of(envelope.messageId(),
			new DurableRadioOutbox.Entry(envelope, List.of(), RadioRouter.RouteStatus.QUEUED)));

		assertEquals(6, CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY).getInt("Version"));
		CompoundTag priorUnmarked = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		priorUnmarked.putInt("Version", 2);
		assertEquals(state, CurrentSchemaStateNbt.load(priorUnmarked, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).orElseThrow());
		CompoundTag exactLegacyNull = legacyRadioEnvelopeShape(state, false);
		assertEquals(state, CurrentSchemaStateNbt.load(exactLegacyNull,
			RegistryAccess.EMPTY, DurableRadioOutbox.State.class).orElseThrow());
		assertTrue(legacyEnvelope(exactLegacyNull).contains("boundaryForwardMarker"),
			"migration must operate on a copy, not mutate journal-owned NBT");
		assertFalse(legacyEnvelope(exactLegacyNull).contains("boundaryTransition"));
		assertTrue(CurrentSchemaStateNbt.load(legacyRadioEnvelopeShape(state, true),
			RegistryAccess.EMPTY, DurableRadioOutbox.State.class).isEmpty(),
			"the removed marker cannot be migrated into a new boundary authorization");
		CompoundTag obsoleteShapeAtCurrentVersion = legacyRadioEnvelopeShape(state, false);
		obsoleteShapeAtCurrentVersion.putInt("Version", 2);
		assertTrue(CurrentSchemaStateNbt.load(obsoleteShapeAtCurrentVersion, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty(),
			"the legacy envelope shape is accepted only at its explicit version boundary");
		CompoundTag currentNullAtLegacyVersion = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		currentNullAtLegacyVersion.putInt("Version", 1);
		assertTrue(CurrentSchemaStateNbt.load(currentNullAtLegacyVersion, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty(),
			"a v2 null transition cannot masquerade as the v1 envelope shape");
		CompoundTag extraLegacyKey = legacyRadioEnvelopeShape(state, false);
		legacyEnvelope(extraLegacyKey).put("unexpected", new CompoundTag());
		assertTrue(CurrentSchemaStateNbt.load(extraLegacyKey, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty());
		CompoundTag missingLegacyKey = legacyRadioEnvelopeShape(state, false);
		legacyEnvelope(missingLegacyKey).remove("payload");
		assertTrue(CurrentSchemaStateNbt.load(missingLegacyKey, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty());
		CompoundTag deeplyNestedLegacy = legacyRadioEnvelopeShape(state, false);
		CompoundTag nested = (CompoundTag) legacyEnvelope(deeplyNestedLegacy).get("payload");
		for (int depth = 0; depth <= 64; depth++) {
			CompoundTag next = new CompoundTag();
			nested.put("nested", next);
			nested = next;
		}
		assertTrue(CurrentSchemaStateNbt.load(deeplyNestedLegacy, RegistryAccess.EMPTY,
			DurableRadioOutbox.State.class).isEmpty(),
			"legacy migration must retain the current-schema nesting fence");
	}

	private static CompoundTag legacyRadioEnvelopeShape(DurableRadioOutbox.State state,
		boolean boundaryMarked) {
		CompoundTag encoded = CurrentSchemaStateNbt.save(state, RegistryAccess.EMPTY);
		encoded.putInt("Version", 1);
		CompoundTag value = (CompoundTag) encoded.get("Value");
		CompoundTag entries = (CompoundTag) value.get("entries");
		ListTag encodedEntries = (ListTag) entries.get("Entries");
		CompoundTag entry = (CompoundTag) ((CompoundTag) encodedEntries.get(0)).get("Value");
		CompoundTag envelope = (CompoundTag) entry.get("envelope");
		var transition = envelope.get("boundaryTransition");
		envelope.remove("boundaryTransition");
		if (boundaryMarked) {
			CompoundTag marker = new CompoundTag();
			marker.putString("Kind", "enum");
			marker.putString("Type",
				"com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.BoundaryForwardMarker");
			marker.putString("Value", "INSTANCE");
			envelope.put("boundaryForwardMarker", marker);
		} else envelope.put("boundaryForwardMarker", transition);
		return encoded;
	}

	private static CompoundTag legacyEnvelope(CompoundTag encoded) {
		CompoundTag value = (CompoundTag) encoded.get("Value");
		CompoundTag entries = (CompoundTag) value.get("entries");
		ListTag encodedEntries = (ListTag) entries.get("Entries");
		CompoundTag entry = (CompoundTag) ((CompoundTag) encodedEntries.get(0)).get("Value");
		return (CompoundTag) entry.get("envelope");
	}

	private record MapState(Map<String, Integer> values) {}
	private record OptionalLongState(OptionalLong value) {}

	private static UUID id(long value) { return new UUID(0, value); }
}
