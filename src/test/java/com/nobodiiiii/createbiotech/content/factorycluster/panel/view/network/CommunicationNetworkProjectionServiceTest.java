package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.PanelTopologyTestHarness;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyTracker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.Capability;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.FormationState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.LinkType;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.OperationalState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection.Status;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.ClusterPublicState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.CaptureFacts;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.PublishedSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.SubnetPublication;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class CommunicationNetworkProjectionServiceTest {
	private static final List<FactoryRuntimeService> RUNTIMES = new java.util.ArrayList<>();
	private static final FactoryRuntimeConfig RUNTIME_CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 128, 20, 10, 100, 3, 32, 16);
	private static final UUID LOCAL = id(1), ORDINARY = id(2), EXTENDED = id(3), RELAY = id(4);
	private static final UUID VIRTUAL_REACHED = id(5), PRIVATE_SUBNET = id(6), PRIVATE_BEHIND = id(7);
	private static final UUID EPOCH = id(20), PANEL = id(21), SESSION = id(22), BOUNDARY = id(23);
	private static final UUID LOCAL_MEMBER_A = id(100), LOCAL_MEMBER_B = id(101);
	private static final UUID ORDINARY_MEMBER = id(102), EXTENDED_MEMBER = id(103);
	private static final UUID RELAY_MEMBER = id(104), VIRTUAL_MEMBER = id(105);
	private static final UUID PRIVATE_MEMBER = id(106), PRIVATE_BEHIND_MEMBER = id(107);
	private static final CommunicationFrequency RADIO = CommunicationFrequency.of("factory-net");
	private static final SpaceAddress PANEL_ADDRESS = address(0);
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(PANEL_ADDRESS, PANEL,
		SESSION, LOCAL, EPOCH);

	@AfterAll static void closeRuntimes() {
		RUNTIMES.forEach(FactoryRuntimeService::close);
		RUNTIMES.clear();
	}

	@Test
	void listsTheWholeReachableComponentWithEveryPublicLinkTypeAndOneCollapsedSubnet() {
		PublishedSnapshot snapshot = snapshot(9);
		CommunicationNetworkProjection view = service(snapshot, 16).project(
			authority(OptionalLong.of(snapshot.revision()), 0, 16));

		assertEquals(Status.CURRENT, view.status());
		assertEquals(6, view.cards().size());
		assertEquals(LinkType.ADJACENT, card(view, LOCAL).linkType());
		assertEquals(LinkType.ORDINARY, card(view, ORDINARY).linkType());
		assertEquals(LinkType.EXTENDED, card(view, EXTENDED).linkType());
		assertEquals(LinkType.RELAY, card(view, RELAY).linkType());
		assertEquals(LinkType.ORDINARY, card(view, VIRTUAL_REACHED).linkType());
		assertEquals(Set.of(shortId(LOCAL), shortId(ORDINARY), shortId(EXTENDED),
			shortId(RELAY), shortId(VIRTUAL_REACHED)), view.cards().stream()
			.filter(card -> !card.subnetBoundary()).map(ClusterCardProjection::clusterId)
			.collect(java.util.stream.Collectors.toSet()));

		ClusterCardProjection agent = card(view, VIRTUAL_REACHED);
		assertEquals(ClusterServiceRole.AGENT, agent.role());
		assertEquals(FormationState.FORMED, agent.formationState());
		assertEquals(OperationalState.ONLINE, agent.operationalState());
		assertEquals(2, agent.usedSlots());
		assertEquals(5, agent.totalSlots());
		assertEquals(3, agent.activeTasks());
		assertEquals(2, agent.waitingTasks());
		assertEquals(List.of("Alpha Warehouse", "[redacted]"), agent.logisticsAliases());
		assertEquals(RADIO.value(), agent.primaryFrequency());
		assertEquals(Set.of(Capability.VIEW), agent.capabilities());
		assertTrue(agent.remote());

		ClusterCardProjection local = card(view, LOCAL);
		assertEquals("", local.primaryFrequency(), "blank local frequency must stay blank");
		assertEquals(Set.of(Capability.VIEW, Capability.EDIT_DECLARATION,
			Capability.EDIT_LISTENERS), local.capabilities());
		assertFalse(local.remote());
		assertEquals(4, view.localEdits().orElseThrow().declarationRevision());
		assertTrue(view.localEdits().orElseThrow().listenerEditors().isEmpty());

		List<ClusterCardProjection> subnetCards = view.cards().stream()
			.filter(ClusterCardProjection::subnetBoundary).toList();
		assertEquals(1, subnetCards.size());
		ClusterCardProjection subnet = subnetCards.getFirst();
		assertEquals(LinkType.SUBNET, subnet.linkType());
		assertEquals("external", subnet.primaryFrequency());
		assertEquals(Set.of(SubnetBoundaryService.PublicService.AGENT,
			SubnetBoundaryService.PublicService.LOGISTICS_METADATA), subnet.publicServices());
		assertFalse(view.cards().stream().anyMatch(card -> card.clusterId().equals(shortId(PRIVATE_SUBNET))
			|| card.clusterId().equals(shortId(PRIVATE_BEHIND))));
	}

	@Test
	void aLocalSubnetSourceCanTraverseItsCurrentBoundaryWithoutHidingTheLocalCard() {
		CommunicationFrequency external = CommunicationFrequency.of("external");
		RadioEndpoint source = endpoint(LOCAL, LOCAL_MEMBER_A, 0, external,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(ORDINARY, ORDINARY_MEMBER, 24, external,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		samples.put(source.key(), sample(source));
		samples.put(target.key(), sample(target));
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(LOCAL, id(401), id(402),
			ORDINARY, external, source.hop(), target.hop());
		SubnetBoundaryService boundary = new SubnetBoundaryService(id(401), LOCAL, external,
			List.of(SubnetBoundaryService.PublicServiceSummary.of(
				SubnetBoundaryService.PublicService.AGENT)));
		PublishedSnapshot snapshot = publish(EPOCH, 15,
			new RadioTopologyFrame(201, true, samples), List.of(source, target), Map.of(
				LOCAL, state(LOCAL, ClusterServiceRole.STANDARD, external, 1, 2, 1, 0,
					List.of("Local"), true),
				ORDINARY, state(ORDINARY, ClusterServiceRole.AGENT, external, 1, 2, 1, 0,
					List.of("Remote"), false)),
			List.of(new SubnetPublication(BOUNDARY, binding, boundary.project())));

		CommunicationNetworkProjection view = service(snapshot, 8).project(
			authority(OptionalLong.of(snapshot.revision()), 0, 8));

		assertEquals(LinkType.ADJACENT, card(view, LOCAL).linkType());
		assertEquals(LinkType.ORDINARY, card(view, ORDINARY).linkType());
		assertEquals(1, view.cards().stream().filter(ClusterCardProjection::subnetBoundary).count());
	}

	@Test
	void zeroCapacityReceiverIsNeitherReachableNorACommunicationRelay() {
		UUID behind = id(440), behindMember = id(441);
		RadioEndpoint local = endpoint(LOCAL, LOCAL_MEMBER_A, 0, RADIO, 2, Set.of());
		RadioEndpoint zeroCapacity = endpoint(ORDINARY, ORDINARY_MEMBER, 20, RADIO, 0, Set.of());
		RadioEndpoint behindEndpoint = endpoint(behind, behindMember, 40, RADIO, 2, Set.of());
		List<RadioEndpoint> endpoints = List.of(local, zeroCapacity, behindEndpoint);
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		endpoints.forEach(endpoint -> samples.put(endpoint.key(), sample(endpoint)));
		PublishedSnapshot snapshot = publish(EPOCH, 15,
			new RadioTopologyFrame(202, true, samples), endpoints, Map.of(
				LOCAL, state(LOCAL, ClusterServiceRole.STANDARD, RADIO, 0, 2, 0, 0, List.of(), false),
				ORDINARY, state(ORDINARY, ClusterServiceRole.STANDARD, RADIO, 0, 0, 0, 0,
					List.of(), false),
				behind, state(behind, ClusterServiceRole.AGENT, RADIO, 0, 2, 0, 0, List.of(), false)),
			List.of());

		CommunicationNetworkProjection view = service(snapshot, 8).project(
			authority(OptionalLong.of(snapshot.revision()), 0, 8));

		assertEquals(List.of(shortId(LOCAL)), view.cards().stream()
			.map(ClusterCardProjection::clusterId).toList());
	}

	@Test
	void publicStateDerivesOnlyAggregateRuntimeCounts() {
		UUID runnableId = id(700), waitingId = id(701), processId = id(702), network = id(703);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER,
			1, 64).orElseThrow();
		GoalRecord runnable = goal(runnableId, network, GoalState.RUNNABLE, null);
		GoalRecord waiting = goal(waitingId, network, GoalState.WAITING, WaitReason.ROUTE);
		ProcessRecord process = new ProcessRecord(processId, runnableId, LOCAL_MEMBER_A, 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState engine = new ClusterEngineState(LOCAL, EPOCH, 3, 2, 0, 0,
			Map.of(runnableId, runnable, waitingId, waiting), Map.of(runnableId, 0L, waitingId, 1L),
			Map.of(runnableId, ProcessKind.GOAL, waitingId, ProcessKind.GOAL),
			Map.of(processId, process), List.of(new ClusterEngineState.MemberSnapshot(LOCAL_MEMBER_A,
				profile, true, 0, 0)), Map.of(), Set.of(), 0, null);
		ClusterPublicState state = ClusterPublicState.fromEngine(engine,
			new ClusterCommunicationConfiguration(LOCAL, CommunicationFrequency.UNCONFIGURED,
				ClusterServiceRole.STANDARD, 0), List.of("Main"), true);

		assertEquals(1, state.usedSlots());
		assertEquals(2, state.totalSlots());
		assertEquals(1, state.activeTasks());
		assertEquals(1, state.waitingTasks());
		assertEquals(OperationalState.ONLINE, state.operationalState());
	}

	@Test
	void unchangedTopologyResampleRotatesExactProofWithoutInvalidatingSemanticRevision() {
		ClusterEngineState engine = new ClusterEngineState(LOCAL, EPOCH, 14, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of(), 0, null);
		ClusterPublicState local = state(LOCAL, ClusterServiceRole.STANDARD,
			CommunicationFrequency.UNCONFIGURED, 0, 0, 0, 0, List.of(), false);
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32,128,64);
		RadioEndpoint endpoint = endpoint(LOCAL,LOCAL_MEMBER_A,0,RADIO,2,Set.of());
		assertTrue(router.register(endpoint));
		lifecycle.observe(new RadioTopologyLifecycle.Registration(LOCAL_MEMBER_A,LOCAL_MEMBER_A),endpoint,
			new RadioTopologyTracker.Pose(Optional.empty(),Vec3.atCenterOf(endpoint.address().localPos())),0);
		RadioTopologyPublicationAuthority topology = new RadioTopologyPublicationAuthority(lifecycle, router);
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		RUNTIMES.add(runtime); runtime.start(); runtime.restoreSnapshot(engine);
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(local.configuration(), 7)));
		PanelTopologyTestHarness.install(runtime, topology);
		runtime.installPanelCommunicationNetworkSource(request -> Optional.of(new CaptureFacts(engine, 7,
			request.topologyPublication(), Map.of(LOCAL, local), List.of(), Map.of(), Set.of())));
		PublishedSnapshot first = runtime.publishPanelCommunicationNetwork(LOCAL);
		lifecycle.refresh(20, (address, pose) -> new RadioTopologyTracker.ResolvedPose(pose.localCenter(), pose.physicalStructureId()));
		PublishedSnapshot second = runtime.publishPanelCommunicationNetwork(LOCAL);
		assertNotSame(first, second);
		assertFalse(first.isCurrentPublication());
		assertTrue(second.isCurrentPublication());
		assertEquals(first.revision(), second.revision(), "sample time alone is not an edit change");
		assertEquals(Status.CURRENT, service(second, 2).project(
			authority(OptionalLong.of(first.revision()), 0, 2)).status());
	}

	@Test
	void runtimeOwnerAssignsMonotonicRevisionsAndRetiredPublicationsNeverRevive() throws Exception {
		ClusterEngineState engine = new ClusterEngineState(LOCAL, EPOCH, 14, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of(), 0, null);
		ClusterPublicState local = state(LOCAL, ClusterServiceRole.STANDARD,
			CommunicationFrequency.UNCONFIGURED, 0, 0, 0, 0, List.of("Local"), false);
		TopologyFixture firstTopology = topology(List.of(), List.of());
		CaptureFacts firstFacts = new CaptureFacts(engine, 7, firstTopology.publication(),
			Map.of(LOCAL, local), List.of(), Map.of(), Set.of());
		java.util.concurrent.atomic.AtomicReference<CaptureFacts> current =
			new java.util.concurrent.atomic.AtomicReference<>(firstFacts);
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		runtime.restoreSnapshot(engine);
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(local.configuration(), 7)));
		PanelTopologyTestHarness.install(runtime, firstTopology.authority());
		AutoCloseable source = runtime.installPanelCommunicationNetworkSource(request ->
			Optional.of(current.get()));
		TopologyFixture foreignTopology = topology(List.of(), List.of());
		current.set(new CaptureFacts(engine, 7, foreignTopology.publication(),
			Map.of(LOCAL, local), List.of(), Map.of(), Set.of()));
		assertThrows(IllegalArgumentException.class,
			() -> runtime.publishPanelCommunicationNetwork(LOCAL));
		current.set(firstFacts);
		PublishedSnapshot first = runtime.publishPanelCommunicationNetwork(LOCAL);
		assertSame(first, runtime.publishPanelCommunicationNetwork(LOCAL));

		TopologyFixture replacementTopology = topology(List.of(), List.of());
		assertThrows(IllegalStateException.class,
			() -> PanelTopologyTestHarness.install(runtime, replacementTopology.authority()));
		source.close();
		AutoCloseable replacementSource = runtime.installPanelCommunicationNetworkSource(request ->
			Optional.of(current.get()));
		PublishedSnapshot replacement = runtime.publishPanelCommunicationNetwork(LOCAL);
		assertNotSame(first, replacement);
		assertEquals(first.revision() + 1, replacement.revision());
		assertFalse(first.isCurrentPublication());
		assertTrue(replacement.isCurrentPublication());
		assertEquals(Status.STALE, service(first, 8).project(
			authority(OptionalLong.of(first.revision()), 0, 8)).status());

		var ownerField = PublishedSnapshot.class.getDeclaredField("owner");
		ownerField.setAccessible(true);
		Object owner = ownerField.get(replacement);
		var revisionField = owner.getClass().getDeclaredField("revision");
		revisionField.setAccessible(true);
		revisionField.setLong(owner, Long.MAX_VALUE);
		replacementSource.close();
		assertFalse(replacement.isCurrentPublication());
		assertFalse(runtime.capturePanelCommunicationNetwork(LOCAL).isPresent());
		// Retirement at exhaustion permanently seals this Cluster publication owner.
		runtime.installPanelCommunicationNetworkSource(request -> Optional.of(current.get()));
		assertThrows(IllegalStateException.class,
			() -> runtime.publishPanelCommunicationNetwork(LOCAL));
		RUNTIMES.add(runtime);
	}

	@Test
	void installedTopologyOwnerCannotBeReplacedByAReentrantNetworkSource() {
		ClusterEngineState engine = new ClusterEngineState(LOCAL, EPOCH, 14, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of(), 0, null);
		ClusterPublicState local = state(LOCAL, ClusterServiceRole.STANDARD,
			CommunicationFrequency.UNCONFIGURED, 0, 0, 0, 0, List.of("Local"), false);
		TopologyFixture installed = topology(List.of(), List.of());
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		runtime.restoreSnapshot(engine);
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(local.configuration(), 7)));
		PanelTopologyTestHarness.install(runtime, installed.authority());
		java.util.concurrent.atomic.AtomicBoolean replacementRejected =
			new java.util.concurrent.atomic.AtomicBoolean();
		runtime.installPanelCommunicationNetworkSource(request -> {
			try {
				runtime.beginPanelProductionComposition();
			} catch (IllegalStateException rejected) {
				replacementRejected.set(true);
			}
			return Optional.of(new CaptureFacts(engine, 7, request.topologyPublication(),
				Map.of(LOCAL, local), List.of(), Map.of(), Set.of()));
		});

		PublishedSnapshot publication = runtime.publishPanelCommunicationNetwork(LOCAL);
		assertTrue(replacementRejected.get());
		assertTrue(publication.isCurrentPublication());
		assertFalse(java.util.Arrays.stream(FactoryRuntimeService.class.getMethods())
			.anyMatch(method -> java.util.Arrays.asList(method.getParameterTypes())
				.contains(RadioTopologyPublicationAuthority.class)));
		RUNTIMES.add(runtime);
	}

	@Test
	void productionTopologyCompositionHandleIsOneShot() {
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		FactoryRuntimeService.PanelProductionComposition composition =
			runtime.beginPanelProductionComposition();
		assertThrows(IllegalStateException.class, runtime::beginPanelProductionComposition);
		composition.bindTopology(topology(List.of(), List.of()).authority());
		assertThrows(IllegalStateException.class,
			() -> composition.bindTopology(topology(List.of(), List.of()).authority()));
		RUNTIMES.add(runtime);
	}

	@Test
	void closedProductionCompositionReleasesOnlyItsOwnTopologyBinding() throws Exception {
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		FactoryRuntimeService.PanelProductionComposition first =
			runtime.beginPanelProductionComposition();
		first.bindTopology(topology(List.of(), List.of()).authority());
		AutoCloseable firstLifecycle = assertInstanceOf(AutoCloseable.class, first);
		firstLifecycle.close();

		FactoryRuntimeService.PanelProductionComposition replacement =
			runtime.beginPanelProductionComposition();
		replacement.bindTopology(topology(List.of(), List.of()).authority());
		firstLifecycle.close();
		assertThrows(IllegalStateException.class, runtime::beginPanelProductionComposition);
		RUNTIMES.add(runtime);
	}

	@Test
	void staleOrWrongEpochCapturesNeverMixCardsAndPagingIsBounded() {
		PublishedSnapshot snapshot = snapshot(9);
		CommunicationNetworkProjection stale = service(snapshot, 3).project(
			authority(OptionalLong.of(snapshot.revision() + 1), 0, 3));
		assertEquals(Status.STALE, stale.status());
		assertTrue(stale.cards().isEmpty());
		assertEquals(snapshot.revision(), stale.revision());

		CommunicationNetworkProjection page = service(snapshot, 3).project(
			authority(OptionalLong.empty(), 2, 3));
		assertEquals(2, page.offset());
		assertEquals(3, page.cards().size());
		assertEquals(6, page.totalCards());
		assertTrue(page.hasMore());

		PublishedSnapshot wrongEpoch = publish(id(999), 14,
			snapshot.topology(), snapshot.endpoints(), snapshot.clusters(), snapshot.subnets());
		CommunicationNetworkProjection unavailable = service(wrongEpoch, 3).project(
			authority(OptionalLong.empty(), 0, 3));
		assertEquals(Status.STALE, unavailable.status());
		assertTrue(unavailable.cards().isEmpty());

		assertThrows(IllegalArgumentException.class, () -> service(snapshot, 3).project(
			authority(OptionalLong.empty(), 0, 4)));
	}


	@Test
	void codecShapeRejectsAFalseLocalCardAndAFullUuidSubnetIdentity() {
		ClusterCardProjection falseLocal = ClusterCardProjection.cluster(ORDINARY,
			ClusterServiceRole.STANDARD, FormationState.FORMED, OperationalState.ONLINE,
			0, 1, 0, 0, LinkType.ADJACENT, RADIO, List.of("Remote"), false, false);
		assertThrows(IllegalArgumentException.class, () -> new CommunicationNetworkProjection(
			LOCAL, EPOCH, 1, 1, Status.CURRENT, 0, 1, List.of(falseLocal), 8,
			Optional.of(new LocalCommunicationEditProjection(0, List.of()))));

		assertThrows(IllegalArgumentException.class, () -> new ClusterCardProjection(
			"subnet-" + BOUNDARY, ClusterServiceRole.AGENT, FormationState.BOUNDARY,
			OperationalState.ONLINE, 0, 0, 0, 0, LinkType.SUBNET, "external", List.of(),
			true, true, Set.of(Capability.VIEW), Set.of(SubnetBoundaryService.PublicService.AGENT)));

		assertThrows(IllegalArgumentException.class, () -> new ClusterCardProjection(
			shortId(LOCAL), ClusterServiceRole.STANDARD, FormationState.FORMED,
			OperationalState.ONLINE, 0, 1, 0, 0, LinkType.ADJACENT, "", List.of(),
			false, false, Set.of(Capability.VIEW), Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new ClusterCardProjection(
			"subnet-" + shortId(BOUNDARY), ClusterServiceRole.STANDARD, FormationState.BOUNDARY,
			OperationalState.ONLINE, 0, 0, 0, 0, LinkType.SUBNET, "external", List.of(),
			true, true, Set.of(Capability.VIEW), Set.of(SubnetBoundaryService.PublicService.AGENT)));
	}

	@Test
	void cardsAndListenerEditorsConsumeOneAggregatePageBudget() {
		ClusterCardProjection local = ClusterCardProjection.cluster(LOCAL,
			ClusterServiceRole.STANDARD, FormationState.FORMED, OperationalState.ONLINE,
			0, 1, 0, 0, LinkType.ADJACENT, CommunicationFrequency.UNCONFIGURED,
			List.of(), false, true);
		List<ListenerEditorProjection> editors = java.util.stream.IntStream.range(0, 8)
			.mapToObj(index -> new ListenerEditorProjection(
				String.format("listener-%016x", index), List.of(), index)).toList();
		assertThrows(IllegalArgumentException.class, () -> new CommunicationNetworkProjection(
			LOCAL, EPOCH, 1, 1, Status.CURRENT, 0, 1, List.of(local), 8,
			Optional.of(new LocalCommunicationEditProjection(0, editors))));
	}


	@Test
	void shortIdentityCollisionUsesFullUuidOrderingThenFailsClosedBeforePaging() {
		UUID collisionA = new UUID(1, 55), collisionB = new UUID(2, 55);
		RadioEndpoint local = endpoint(LOCAL, LOCAL_MEMBER_A, 0, RADIO, 2, Set.of());
		RadioEndpoint first = endpoint(collisionA, id(551), 10, RADIO, 2, Set.of());
		RadioEndpoint second = endpoint(collisionB, id(552), 12, RADIO, 2, Set.of());
		List<RadioEndpoint> endpoints = List.of(local, first, second);
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		endpoints.forEach(endpoint -> samples.put(endpoint.key(), sample(endpoint)));
		Map<UUID, ClusterPublicState> clusters = Map.of(
			LOCAL, state(LOCAL, ClusterServiceRole.STANDARD, RADIO, 0, 1, 0, 0, List.of(), false),
			collisionA, state(collisionA, ClusterServiceRole.STANDARD, RADIO, 0, 1, 0, 0,
				List.of(), false),
			collisionB, state(collisionB, ClusterServiceRole.AGENT, RADIO, 0, 1, 0, 0,
				List.of(), false));
		PublishedSnapshot publication = publish(EPOCH, 14,
			new RadioTopologyFrame(400, true, samples), endpoints, clusters, List.of());
		assertThrows(IllegalStateException.class, () -> service(publication, 1).project(
			authority(OptionalLong.of(publication.revision()), 0, 1)));
	}

	@Test
	void oneRowRemoteLaneCanReachEveryRemoteCardWithoutPinnedLocalConsumingBudget() {
		PublishedSnapshot snapshot = snapshot(9);
		java.util.HashSet<String> reached = new java.util.HashSet<>();
		for (int offset = 0; offset < 5; offset++) {
			var packet = new FactoryPanelAuthorityValidator.ViewRequest(
				OPENING,FactoryPanelView.NETWORK_COMMUNICATION,OptionalLong.empty(),offset,1,Optional.empty(),1,Optional.empty(),CommunicationPage.REMOTE);
			var accepted = FactoryPanelAuthorityValidator.validateView(packet,
				new FactoryPanelAuthorityValidator.ViewFacts(PANEL_ADDRESS,PANEL,SESSION,LOCAL,LOCAL,EPOCH,
					true,true,true,true,true,true,true,Optional.empty(),1)).orElseThrow();
			var page = service(snapshot,1).project(accepted);
			assertEquals(1,page.cards().size());
			assertTrue(page.cards().getFirst().remote());
			assertEquals(Set.of(Capability.VIEW),page.cards().getFirst().capabilities());
			reached.add(page.cards().getFirst().clusterId());
		}
		assertEquals(5,reached.size());
	}

	private static CommunicationNetworkProjectionService service(PublishedSnapshot snapshot,
		int rowLimit) {
		return new CommunicationNetworkProjectionService(clusterId -> Optional.of(snapshot),
			new RadioLinkPolicy(32, 128), rowLimit);
	}

	private static PublishedSnapshot snapshot(long revision) {
		List<RadioEndpoint> endpoints = List.of(
			endpoint(LOCAL, LOCAL_MEMBER_A, 0, Set.of()),
			endpoint(LOCAL, LOCAL_MEMBER_B, 1000, Set.of()),
			endpoint(ORDINARY, ORDINARY_MEMBER, 24, Set.of()),
			endpoint(EXTENDED, EXTENDED_MEMBER, -80, Set.of(ProductionCapability.EXTENDED_RADIO)),
			endpoint(RELAY, RELAY_MEMBER, 48, Set.of()),
			endpoint(VIRTUAL_REACHED, VIRTUAL_MEMBER, 1016, Set.of()),
			endpoint(PRIVATE_SUBNET, PRIVATE_MEMBER, 52, CommunicationFrequency.of("external"),
				Set.of(ProductionCapability.EXTENDED_RADIO)),
			endpoint(RELAY, PRIVATE_BEHIND_MEMBER, 49, CommunicationFrequency.of("external"),
				Set.of(ProductionCapability.EXTENDED_RADIO)));
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		for (RadioEndpoint endpoint : endpoints) samples.put(endpoint.key(), sample(endpoint));
		RadioTopologyFrame topology = new RadioTopologyFrame(200, true, samples);

		Map<UUID, ClusterPublicState> clusters = new LinkedHashMap<>();
		clusters.put(LOCAL, state(LOCAL, ClusterServiceRole.STANDARD,
			CommunicationFrequency.UNCONFIGURED, 1, 4, 1, 0, List.of("Local"), true));
		clusters.put(ORDINARY, state(ORDINARY, ClusterServiceRole.STANDARD, RADIO,
			1, 3, 1, 0, List.of("Ordinary"), true));
		clusters.put(EXTENDED, state(EXTENDED, ClusterServiceRole.STANDARD, RADIO,
			0, 2, 0, 1, List.of("Extended"), false));
		clusters.put(RELAY, state(RELAY, ClusterServiceRole.STANDARD, RADIO,
			1, 2, 1, 0, List.of("Relay"), true));
		clusters.put(VIRTUAL_REACHED, state(VIRTUAL_REACHED, ClusterServiceRole.AGENT, RADIO,
			2, 5, 3, 2, List.of("Alpha Warehouse", id(900).toString()), true));
		clusters.put(PRIVATE_SUBNET, state(PRIVATE_SUBNET, ClusterServiceRole.AGENT, RADIO,
			2, 2, 9, 9, List.of("must-not-leak"), true));

		SubnetBoundaryService boundary = new SubnetBoundaryService(id(300), PRIVATE_SUBNET,
			CommunicationFrequency.of("external"), List.of(
				SubnetBoundaryService.PublicServiceSummary.of(SubnetBoundaryService.PublicService.AGENT),
				SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.LOGISTICS_METADATA)));
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(PRIVATE_SUBNET, id(300), id(301),
			RELAY, CommunicationFrequency.of("external"), endpoints.get(6).hop(), endpoints.get(7).hop());
		return publish(EPOCH, 14, topology, endpoints, clusters,
			List.of(new SubnetPublication(BOUNDARY, binding, boundary.project())));
	}

	private static PublishedSnapshot publish(UUID epochId, long generation,
		RadioTopologyFrame topology, List<RadioEndpoint> endpoints,
		Map<UUID, ClusterPublicState> clusters, List<SubnetPublication> subnets) {
		ClusterEngineState engine = new ClusterEngineState(LOCAL, epochId, generation, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of(), 0, null);
		ClusterCommunicationConfiguration declaration = clusters.get(LOCAL).configuration();
		TopologyFixture topologyFixture = topology(endpoints, subnets);
		CaptureFacts facts = new CaptureFacts(engine, 7, topologyFixture.publication(), clusters,
			subnets, Map.of(), Set.of());
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		runtime.restoreSnapshot(engine);
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(declaration, 7)));
		PanelTopologyTestHarness.install(runtime, topologyFixture.authority());
		runtime.installPanelCommunicationNetworkSource(request -> Optional.of(facts));
		PublishedSnapshot publication = runtime.publishPanelCommunicationNetwork(LOCAL);
		RUNTIMES.add(runtime);
		return publication;
	}

	private static TopologyFixture topology(List<RadioEndpoint> endpoints,
		List<SubnetPublication> subnets) {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32, 128, 64);
		for (RadioEndpoint endpoint : endpoints) {
			assertTrue(router.register(endpoint));
			lifecycle.observe(new RadioTopologyLifecycle.Registration(endpoint.memberId(),
				endpoint.memberId()), endpoint, new RadioTopologyTracker.Pose(
					Optional.ofNullable(endpoint.address().subLevelId()),
					Vec3.atCenterOf(endpoint.address().localPos())), 0);
		}
		for (SubnetPublication subnet : subnets) assertTrue(router.registerBoundaryBinding(
			subnet.binding()));
		RadioTopologyPublicationAuthority authority = new RadioTopologyPublicationAuthority(
			lifecycle, router);
		return new TopologyFixture(authority, authority.capture());
	}

	private record TopologyFixture(RadioTopologyPublicationAuthority authority,
		RadioTopologyPublicationAuthority.Publication publication) {}

	private static ClusterPublicState state(UUID clusterId, ClusterServiceRole role,
		CommunicationFrequency frequency, int used, int total, int active, int waiting,
		List<String> aliases, boolean listenerEditing) {
		return new ClusterPublicState(new ClusterCommunicationConfiguration(clusterId, frequency,
			role, 4), FormationState.FORMED,
			total == 0 ? OperationalState.OFFLINE : OperationalState.ONLINE,
			used, total, active, waiting, aliases, listenerEditing);
	}

	private static FactoryPanelAuthorityValidator.ViewValidation authority(OptionalLong revision,
		int offset, int limit) {
		FactoryPanelAuthorityValidator.ViewRequest request = new FactoryPanelAuthorityValidator.ViewRequest(
			OPENING, FactoryPanelView.NETWORK_COMMUNICATION, revision, offset, limit, Optional.empty());
		return FactoryPanelAuthorityValidator.validateView(request,
			new FactoryPanelAuthorityValidator.ViewFacts(PANEL_ADDRESS, PANEL, SESSION, LOCAL, LOCAL,
				EPOCH, true, true, true, true, true, true, true, Optional.empty(), 16))
			.orElseThrow();
	}

	private static ClusterCardProjection card(CommunicationNetworkProjection projection,
		UUID clusterId) {
		return projection.cards().stream().filter(card -> card.clusterId().equals(shortId(clusterId)))
			.findFirst().orElseThrow();
	}

	private static RadioEndpoint endpoint(UUID cluster, UUID member, int x,
		Set<ProductionCapability> capabilities) {
		return endpoint(cluster, member, x, RADIO, capabilities);
	}

	private static RadioEndpoint endpoint(UUID cluster, UUID member, int x,
		CommunicationFrequency frequency, Set<ProductionCapability> capabilities) {
		return endpoint(cluster, member, x, frequency, 2, capabilities);
	}

	private static RadioEndpoint endpoint(UUID cluster, UUID member, int x,
		CommunicationFrequency frequency, int listenerCapacity,
		Set<ProductionCapability> capabilities) {
		return new RadioEndpoint(cluster, member, address(x), 1, frequency, listenerCapacity,
			capabilities);
	}

	private static GoalRecord goal(UUID goalId, UUID network, GoalState state, WaitReason wait) {
		return new GoalRecord(goalId, LOCAL,
			new GoalSubmission(goalId, id(800 + goalId.getLeastSignificantBits()), 1, InputMode.EXACT),
			new CreateRouteKey(network, "main"), new PolicySnapshot(0, 0, false), state, wait);
	}




	private static RadioTopologyFrame.Sample sample(RadioEndpoint endpoint) {
		return new RadioTopologyFrame.Sample(Level.OVERWORLD, endpoint.address(),
			new Vec3(endpoint.address().localPos().getX(), 64, 0), true, endpoint.generation(),
			Optional.empty());
	}

	private static SpaceAddress address(int x) {
		return new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 64, 0));
	}

	private static String shortId(UUID id) {
		String compact = id.toString().replace("-", "");
		return compact.substring(compact.length() - 8);
	}



	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
