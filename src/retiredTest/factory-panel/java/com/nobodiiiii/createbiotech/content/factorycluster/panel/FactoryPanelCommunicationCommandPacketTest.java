package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket.ChangePrimary;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket.ChangeRole;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket.Failure;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket.ReplaceExternalListeners;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.CaptureFacts;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjectionService.ClusterPublicState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.FormationState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.OperationalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.PanelListenerTestHarness;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.PanelTopologyTestHarness;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyPublicationAuthority;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;

class FactoryPanelCommunicationCommandPacketTest {
	private static final List<FactoryRuntimeService> RUNTIMES = new ArrayList<>();
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID COORDINATOR = id(5), LOCAL_TRADER = id(6);
	private static final long GENERATION = 11, VIEW_REVISION = 0;
	private static final String LOCAL_TARGET = "listener-0000000000000001";
	private static final String REMOTE_TARGET = "listener-0000000000000002";
	private static final FactoryRuntimeConfig RUNTIME_CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 128, 20, 10, 100, 3, 32, 16);
	private static final SpaceAddress ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(4, 64, 4));
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(ADDRESS, PANEL,
		SESSION, CLUSTER, EPOCH);

	@AfterAll static void closeRuntimes() {
		RUNTIMES.forEach(FactoryRuntimeService::close);
		RUNTIMES.clear();
	}

	@Test
	void primaryAndRoleCommandsCanOnlyMutateTheLocalDeclarationThroughItsCasService() {
		Fixture fixture = fixture(false);
		var declare = packet(new ChangePrimary(0, CommunicationFrequency.of("local")))
			.execute(authority(fixture), fixture.commands);
		assertEquals(Optional.of(ClusterCommunicationCommandService.ChangeResult.APPLIED),
			declare.communicationResult());
		assertEquals(CommunicationFrequency.of("local"), fixture.directory.configuration(CLUSTER)
			.orElseThrow().primaryFrequency());

		var stale = packet(new ChangeRole(0, ClusterServiceRole.AGENT))
			.execute(authority(fixture), fixture.commands);
		assertEquals(Optional.of(ClusterCommunicationCommandService.ChangeResult.STALE),
			stale.communicationResult());

		var agent = packet(new ChangeRole(1, ClusterServiceRole.AGENT))
			.execute(authority(fixture), fixture.commands);
		assertTrue(agent.applied());
		assertEquals(ClusterServiceRole.AGENT, fixture.directory.configuration(CLUSTER)
			.orElseThrow().role());

		Fixture unsafe = fixture(true);
		unsafe.directory.compareAndSet(CLUSTER, 0, new ClusterCommunicationConfiguration(CLUSTER,
			CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.AGENT, 1));
		var revoke = packet(new ChangeRole(1, ClusterServiceRole.STANDARD))
			.execute(authority(unsafe), unsafe.commands);
		assertEquals(Optional.of(ClusterCommunicationCommandService.ChangeResult.UNSAFE_REVOCATION),
			revoke.communicationResult());
		assertEquals(ClusterServiceRole.AGENT, unsafe.directory.configuration(CLUSTER)
			.orElseThrow().role());
	}

	@Test
	void exactPanelViewFenceAndFreshLocalCaptureRejectRemoteOrStaleMutations() {
		Fixture fixture = fixture(false);
		var stalePacket = new FactoryPanelCommunicationCommandPacket(OPENING, GENERATION,
			VIEW_REVISION + 1, new ChangePrimary(0, CommunicationFrequency.of("new")));
		assertTrue(FactoryPanelAuthorityValidator.validateCommunication(stalePacket.authorityRequest(),
			facts(), fixture.publication).isEmpty());
		assertEquals(CommunicationFrequency.UNCONFIGURED, fixture.directory.configuration(CLUSTER)
			.orElseThrow().primaryFrequency());

		var missing = packet(new ReplaceExternalListeners(REMOTE_TARGET, 0,
			List.of(CommunicationFrequency.of("external"))))
			.execute(authority(fixture), fixture.commands);
		assertEquals(Optional.of(Failure.NOT_LOCAL_WANDERING_LISTENER), missing.failure());
		assertTrue(fixture.trader.configuration().frequencies().isEmpty());

		var exactValidation = authority(fixture);
		fixture.runtime.revokePanelDeclaration(CLUSTER, EPOCH);
		var wrong = packet(new ChangePrimary(0, CommunicationFrequency.of("new"))).execute(
			exactValidation, fixture.commands);
		assertEquals(Optional.of(Failure.STALE_STATE), wrong.failure());
	}

	@Test
	void registeredLocalWanderingCaptureGrantsPresentationCapability() {
		var projected = projectListenerFixture(fixture(false), 16);
		assertTrue(projected.cards().getFirst().capabilities().contains(
			com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ClusterCardProjection.Capability.EDIT_LISTENERS));
	}

	@Test
	void oneRowBudgetStillPublishesLocalCardWithRegisteredWandering() {
		var projected = projectListenerFixture(fixture(false), 1);
		assertEquals(com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection.Status.CURRENT,
			projected.status());
		assertEquals(1, projected.cards().size());
	}

	@Test
	void singleRowListenerDetailIsRequestedSeparatelyWithoutLosingAuthority() throws Exception {
		Fixture fixture = fixture(false);
		var request = new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket(OPENING,
			com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_COMMUNICATION,
			java.util.OptionalLong.empty(), 0, 1, Optional.empty(), 41, Optional.empty(),
			com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationPage.LISTENERS);
		RegistryFriendlyByteBuf wire = buffer(); request.write(wire);
		var decoded = com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket.class
			.getConstructor(RegistryFriendlyByteBuf.class).newInstance((Object) wire);
		assertEquals(request, decoded);
		var projection = new CommunicationNetworkProjectionService(cluster -> Optional.of(fixture.publication),
			new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy(32, 128), 1)
			.project(FactoryPanelAuthorityValidator.validateView(decoded.authorityRequest(), facts(), 1).orElseThrow());
		assertTrue(projection.cards().isEmpty());
		assertEquals(1,projection.localEdits().orElseThrow().totalListeners());
		assertEquals(List.of(fixture.target()),projection.localEdits().orElseThrow().listenerEditors().stream().map(editor -> editor.target()).toList());
		assertTrue(projection.localEdits().orElseThrow().listenerEditing());
		var response = new com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkResponsePacket(OPENING,projection,41);
		RegistryFriendlyByteBuf reply = buffer(); response.write(reply);
		assertEquals(response, com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkResponsePacket.class
			.getConstructor(RegistryFriendlyByteBuf.class).newInstance((Object) reply));
	}

	private static com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkProjection
		projectListenerFixture(Fixture fixture, int limit) {
		var request = new FactoryPanelAuthorityValidator.ViewRequest(OPENING,
			com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_COMMUNICATION,
			java.util.OptionalLong.empty(), 0, limit, Optional.empty());
		return new CommunicationNetworkProjectionService(cluster -> Optional.of(fixture.publication),
			new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy(32, 128), limit)
			.project(FactoryPanelAuthorityValidator.validateView(request, facts(), limit).orElseThrow());
	}

	@Test
	void authorizedWanderingListenerListEditsUseTheExternalListenerServiceCas() {
		Fixture fixture = fixture(false);
		var editor = fixture.publication.listenerEditors().getFirst();
		assertEquals(fixture.target(), editor.target());
		assertEquals(0, editor.configurationRevision());
		assertTrue(editor.frequencies().isEmpty());
		List<CommunicationFrequency> requested = List.of(CommunicationFrequency.of("alpha"),
			CommunicationFrequency.of("beta"));
		var validation = authority(fixture);
		var applied = packet(new ReplaceExternalListeners(fixture.target(), 0, requested))
			.execute(validation, fixture.commands);
		assertEquals(Optional.of(ExternalListenerService.EditResult.APPLIED), applied.listenerResult());
		assertEquals(requested, fixture.trader.configuration().frequencies());
		assertEquals(0, fixture.scheduler.occupancy(LOCAL_TRADER, ProcessKind.RADIO_LISTENER));

		var stale = packet(new ReplaceExternalListeners(fixture.target(), 0,
			List.of(CommunicationFrequency.of("gamma"))))
			.execute(validation, fixture.commands);
		assertEquals(Optional.of(Failure.STALE_STATE), stale.failure());
		assertEquals(requested, fixture.trader.configuration().frequencies());
	}

	@Test
	void communicationProofIsDomainBrandedAndLoadedOwnerReplacementInvalidatesListenerEdit() {
		Fixture fixture = fixture(false);
		Class<?> firstParameter = java.util.Arrays.stream(
			FactoryPanelCommunicationCommandPacket.class.getMethods())
			.filter(method -> method.getName().equals("execute"))
			.findFirst().orElseThrow().getParameterTypes()[0];
		assertEquals(FactoryPanelAuthorityValidator.CommunicationValidation.class, firstParameter);

		var validation = authority(fixture);
		fixture.trader.rotateOwner();
		var stale = packet(new ReplaceExternalListeners(fixture.target(), 0,
			List.of(CommunicationFrequency.of("new")))).execute(validation, fixture.commands);
		assertEquals(Optional.of(ExternalListenerService.EditResult.STALE), stale.listenerResult());
		assertTrue(fixture.trader.configuration().frequencies().isEmpty());
	}

	@Test
	void listenerRegistryRetirementInvalidatesTheExactPublicationBeforeCas() {
		Fixture fixture = fixture(false);
		var validation = authority(fixture);
		fixture.listenerRegistry.retire(fixture.trader.listenerOwner());
		var stale = packet(new ReplaceExternalListeners(fixture.target(), 0,
			List.of(CommunicationFrequency.of("new")))).execute(validation, fixture.commands);
		assertEquals(Optional.of(Failure.STALE_STATE), stale.failure());
		assertTrue(fixture.trader.configuration().frequencies().isEmpty());
	}

	@Test
	void listenerRegistryIsNotAPublicReplaceableRawComputerSeam() {
		assertFalse(java.util.Arrays.stream(FactoryRuntimeService.class.getMethods())
			.anyMatch(method -> method.getName().equals("installPanelListenerRegistry")));
		assertFalse(java.util.Arrays.stream(FactoryRuntimeService.class.getMethods())
			.anyMatch(method -> java.util.Arrays.asList(method.getParameterTypes())
				.contains(ExternalListenerService.ListenerComputer.class)));
		assertFalse(java.util.Arrays.stream(FactoryRuntimeService.class.getMethods())
			.anyMatch(method -> java.util.Arrays.asList(method.getParameterTypes())
				.contains(ExternalListenerService.class)));
	}

	@Test
	void listenerLifecycleCannotRetireItsPublicationInsideTheConfigurationCas() {
		Fixture fixture = fixture(false);
		fixture.trader.duringCompareAndSet(() -> fixture.listenerRegistry.retire(
			fixture.trader.listenerOwner()));

		var applied = packet(new ReplaceExternalListeners(fixture.target(), 0,
			List.of(CommunicationFrequency.of("new"))))
			.execute(authority(fixture), fixture.commands);

		assertEquals(Optional.of(ExternalListenerService.EditResult.APPLIED),
			applied.listenerResult());
		assertTrue(fixture.trader.lifecycleMutationRejected());
		assertEquals(List.of(CommunicationFrequency.of("new")),
			fixture.trader.configuration().frequencies());
	}

	@Test
	void listenerLifecycleCannotCloseTheRuntimeInsideTheConfigurationCas() {
		Fixture fixture = fixture(false);
		fixture.trader.duringCompareAndSet(fixture.runtime::close);

		var applied = packet(new ReplaceExternalListeners(fixture.target(), 0,
			List.of(CommunicationFrequency.of("new"))))
			.execute(authority(fixture), fixture.commands);

		assertEquals(Optional.of(ExternalListenerService.EditResult.APPLIED),
			applied.listenerResult());
		assertTrue(fixture.trader.lifecycleMutationRejected());
		assertTrue(fixture.runtime.capturePanelCommunicationNetwork(CLUSTER).isEmpty(),
			"the successful edit retires the view, not the runtime");
	}

	@Test
	void commandCodecIsBoundedAndHasNoForceResetVariant() {
		FactoryPanelCommunicationCommandPacket packet = packet(new ReplaceExternalListeners(LOCAL_TARGET,
			4, List.of(CommunicationFrequency.of("a"), CommunicationFrequency.of("b"))));
		RegistryFriendlyByteBuf buffer = buffer();
		packet.write(buffer);
		assertEquals(packet, decode(buffer));

		Set<String> variants = java.util.Arrays.stream(
			FactoryPanelCommunicationCommandPacket.Command.class.getPermittedSubclasses())
			.map(Class::getSimpleName).collect(java.util.stream.Collectors.toSet());
		assertEquals(Set.of("ChangePrimary", "ChangeRole", "ReplaceExternalListeners"), variants);
		assertFalse(variants.stream().anyMatch(name -> name.contains("Force")));
	}

	private static FactoryPanelCommunicationCommandPacket packet(
		FactoryPanelCommunicationCommandPacket.Command command) {
		return new FactoryPanelCommunicationCommandPacket(OPENING, GENERATION, VIEW_REVISION, command);
	}

	private static FactoryPanelAuthorityValidator.CommunicationValidation authority(Fixture fixture) {
		return FactoryPanelAuthorityValidator.validateCommunication(
			packet(new ChangePrimary(0, CommunicationFrequency.UNCONFIGURED)).authorityRequest(),
			facts(), fixture.publication)
			.orElseThrow();
	}

	private static FactoryPanelAuthorityValidator.Facts facts() {
		return new FactoryPanelAuthorityValidator.Facts(ADDRESS, PANEL, SESSION, CLUSTER, CLUSTER,
			EPOCH, GENERATION, VIEW_REVISION, true, true, true, true, true, true, true);
	}

	private static Fixture fixture(boolean unsafeRevocation) {
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory();
		directory.ensure(CLUSTER);
		ClusterCommunicationCommandService commands = new ClusterCommunicationCommandService(directory,
			authority -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			(authority, current, role) -> unsafeRevocation && role == ClusterServiceRole.STANDARD
				? ClusterCommunicationCommandService.RoleTransitionResult.UNSAFE_REVOCATION
				: ClusterCommunicationCommandService.RoleTransitionResult.ALLOWED);
		TestScheduler scheduler = new TestScheduler();
		ExternalListenerService listeners = new ExternalListenerService(scheduler,
			new RadioRouter(32, 128, 16), (computer, frequency, reservation) -> {
				throw new IllegalStateException("route unavailable in unit fixture");
			}, (computer, frequency, ingress) -> true);
		TestTrader trader = new TestTrader();
		ProductionResourceProfile panelProfile = ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED,
			1, 64).orElseThrow();
		ClusterEngineState engine = new ClusterEngineState(CLUSTER, EPOCH, GENERATION, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(
				new ClusterEngineState.MemberSnapshot(COORDINATOR, panelProfile, true, 0, 0),
				new ClusterEngineState.MemberSnapshot(LOCAL_TRADER, trader.profile(), true, 0, 0)),
			Map.of(), Set.of(), 0, null);
		ClusterCommunicationConfiguration declaration = directory.configuration(CLUSTER).orElseThrow();
		ClusterPublicState local = new ClusterPublicState(declaration, FormationState.FORMED,
			OperationalState.ONLINE, 0, 4, 0, 0, List.of("Local"), false);
		var commandAuthority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER,
			EPOCH, new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, COORDINATOR), PANEL);
		FactoryRuntimeService runtime = new FactoryRuntimeService(RUNTIME_CONFIG);
		runtime.start();
		runtime.restoreSnapshot(engine);
		RadioTopologyPublicationAuthority topology = new RadioTopologyPublicationAuthority(
			new RadioTopologyLifecycle(false), new RadioRouter(32, 128, 16));
		PanelTopologyTestHarness.install(runtime, topology);
		PanelListenerTestHarness.Registration listenerRegistry =
			PanelListenerTestHarness.install(runtime, listeners);
		listenerRegistry.observe(CLUSTER, EPOCH, trader);
		CaptureFacts capture = new CaptureFacts(engine, 1, topology.capture(),
			Map.of(CLUSTER, local), List.of(), Map.of(PANEL, commandAuthority),
			Set.of(commandAuthority.authority()));
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(declaration, 1)));
		runtime.installPanelCommunicationNetworkSource(request -> Optional.of(capture));
		CommunicationNetworkProjectionService.PublishedSnapshot publication =
			runtime.publishPanelCommunicationNetwork(CLUSTER);
		RUNTIMES.add(runtime);
		return new Fixture(directory, commands, listeners, scheduler, trader, runtime,
			listenerRegistry, publication);
	}

	private record Fixture(ClusterCommunicationDirectory directory,
		ClusterCommunicationCommandService commands, ExternalListenerService listeners,
		TestScheduler scheduler, TestTrader trader, FactoryRuntimeService runtime,
		PanelListenerTestHarness.Registration listenerRegistry,
		CommunicationNetworkProjectionService.PublishedSnapshot publication) {
		private String target() {
			return publication.listenerEditors().getFirst().target();
		}
	}

	private static final class TestTrader implements ExternalListenerService.ListenerComputer {
		private final ProductionResourceProfile profile = ProductionResourceProfile.forProfession(
			ProductionProfession.WANDERING_TRADER, 1, 64).orElseThrow();
		private ExternalListenerConfiguration configuration = ExternalListenerConfiguration.empty();
		private UUID ownerToken = FactoryPanelCommunicationCommandPacketTest.id(600);
		private Runnable duringCompareAndSet = () -> {};
		private boolean lifecycleMutationRejected;
		@Override public UUID id() { return LOCAL_TRADER; }
		@Override public ExternalListenerService.ListenerOwner listenerOwner() {
			return new ExternalListenerService.ListenerOwner(LOCAL_TRADER, EPOCH, ownerToken);
		}
		private void rotateOwner() { ownerToken = FactoryPanelCommunicationCommandPacketTest.id(601); }
		private void duringCompareAndSet(Runnable action) {
			duringCompareAndSet = action;
		}
		private boolean lifecycleMutationRejected() { return lifecycleMutationRejected; }
		@Override public ProductionResourceProfile profile() { return profile; }
		@Override public boolean listenerPersistenceAuthorized() { return true; }
		@Override public ExternalListenerConfiguration configuration() { return configuration; }
		@Override public boolean compareAndSet(ExternalListenerConfiguration expected,
			ExternalListenerConfiguration next) {
			if (!configuration.equals(expected)) return false;
			try {
				duringCompareAndSet.run();
			} catch (IllegalStateException rejected) {
				lifecycleMutationRejected = true;
			}
			configuration = next;
			return true;
		}
	}

	private static final class TestScheduler implements ExternalListenerService.Scheduler {
		private final Map<UUID, List<ExternalListenerService.Reservation>> reservations =
			new java.util.HashMap<>();
		@Override public Optional<ExternalListenerService.Reservation> reserve(UUID computerId,
			CommunicationFrequency frequency, int slotLimit) {
			List<ExternalListenerService.Reservation> current = reservations.computeIfAbsent(computerId,
				ignored -> new ArrayList<>());
			if (current.size() >= slotLimit) return Optional.empty();
			var reservation = new ExternalListenerService.Reservation(id(100 + current.size()),
				computerId, current.size());
			current.add(reservation);
			return Optional.of(reservation);
		}
		@Override public void release(ExternalListenerService.Reservation reservation) {
			reservations.getOrDefault(reservation.computerId(), List.of()).remove(reservation);
		}
		@Override public int occupancy(UUID computerId, ProcessKind kind) {
			return kind == ProcessKind.RADIO_LISTENER
				? reservations.getOrDefault(computerId, List.of()).size() : 0;
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static RegistryFriendlyByteBuf buffer() {
		try {
			Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
			Object byteBuf = Class.forName("io.netty.buffer.Unpooled")
				.getMethod("buffer").invoke(null);
			return (RegistryFriendlyByteBuf) RegistryFriendlyByteBuf.class
				.getConstructor(byteBufType, RegistryAccess.class)
				.newInstance(byteBuf, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static FactoryPanelCommunicationCommandPacket decode(Object buffer) {
		try {
			return (FactoryPanelCommunicationCommandPacket) FactoryPanelCommunicationCommandPacket.class
				.getConstructor(RegistryFriendlyByteBuf.class).newInstance(buffer);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError(exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
