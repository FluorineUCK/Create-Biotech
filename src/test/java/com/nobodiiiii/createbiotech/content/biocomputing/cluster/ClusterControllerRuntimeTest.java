package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.*;

class ClusterControllerRuntimeTest {    @Test void recentControllerEventsShowShellFailureAndRepairWithoutPublishingTheQueuedPayload() throws Exception {
        var f = fixture(); var block = f.controller;
        assertEquals("configured", block.configure(configuration(f.computer), f.player));
        assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request()));
        block.recentEvents();
        f.world.states.remove(BlockPos.ZERO); block.tick(); block.tick();
        f.world.states.put(BlockPos.ZERO, WorldEnclosureViewTest.casing.defaultBlockState()); block.tick(); block.tick();
        var log = block.recentEvents(); var entries = ((DataValue.ListValue)log.field("entries")).values();
        assertEquals(1, entries.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("open_shell"))).count());
        assertEquals(1, entries.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("recovered"))).count());
        assertFalse(log.toString().contains("payload"));
        assertEquals(1, f.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(log, block.recentEvents());
    }
    @Test void failedParticleDeliveryCannotAbortAnAlreadyAcceptedConcentratedInput() throws Exception {
        var f = fixture();
        assertEquals("configured", f.controller.configure(configuration(f.computer), f.player));
        var request = request();
        assertEquals(AdmissionStatus.ACCEPTED, f.controller.receive("query", request));
        f.world.particleFailure = true;
        assertDoesNotThrow(f.controller::tick);
        assertEquals("accepted", f.controller.diagnosticCode());
        assertTrue(f.controller.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(1, f.computer.snapshot().orElseThrow().mailbox().queued().size());
        var queued = f.computer.snapshot().orElseThrow().mailbox().queued().getFirst();
        assertEquals(request.eventId(), queued.eventId());
        assertEquals(request.payload(), queued.payload());
        var saved = f.controller.saveWithFullMetadata(RegistryAccess.EMPTY);
        f.controller.loadWithComponents(saved, RegistryAccess.EMPTY);
        f.world.particleFailure = false;
        f.controller.tick();
        assertTrue(f.controller.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(1, f.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(0, f.world.particles, "Do not replay a failed cosmetic effect after data acknowledgement");
        assertEquals(AdmissionStatus.ACCEPTED, f.controller.receive("query", request()));
        f.controller.tick();
        assertEquals(2, f.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(1, f.world.particles, "Recovery still renders newly accepted transfers");
    }

    @Test void failedParticleDeliveryCannotReportACommittedConcentratedReplyAsFailed() throws Exception {
        var f = fixture();
        assertEquals("configured", f.controller.configure(configuration(f.computer), f.player));
        var request = request();
        assertEquals(AdmissionStatus.ACCEPTED, f.controller.receive("query", request));
        f.controller.tick();
        f.world.particleFailure = true;
        assertDoesNotThrow(f.computer::tick);
        assertEquals("accepted", f.computer.transportResult().orElseThrow().code());
        assertEquals(1, f.computer.snapshot().orElseThrow().delivery().orElseThrow().acknowledged());
        var expected = f.controller.routingState().orElseThrow();
        assertEquals(1, expected.mailbox().queued().size());
        var returned = expected.outbound().orElseThrow().envelope();
        assertEquals(request.payload(), returned.payload());
        assertEquals(((MessageProtocol.Request)request.protocol()).context().correlation(),
            ((MessageProtocol.Reply)returned.protocol()).correlation());
        var controllerSave = f.controller.saveWithFullMetadata(RegistryAccess.EMPTY);
        var computerSave = new CompoundTag();
        f.computer.write(computerSave, RegistryAccess.EMPTY, false);
        f.controller.loadWithComponents(controllerSave, RegistryAccess.EMPTY);
        f.computer.read(computerSave, RegistryAccess.EMPTY, false);
        f.world.particleFailure = false;
        f.computer.tick();
        assertEquals(expected, f.controller.routingState().orElseThrow());
        assertEquals(1, f.computer.snapshot().orElseThrow().delivery().orElseThrow().acknowledged());
        assertEquals(new DataValue.IntegerValue(7), f.computer.snapshot().orElseThrow().machine().memory().field("value"));
        assertEquals(1, f.world.particles, "Only the initial successful input transfer had a particle");
    }

    @Test void failedParticleDeliveryCannotLeaveInternalSenderWaitingAfterRecipientAccepted() throws Exception {
        var f = internal(false);
        assertEquals("configured", f.fixture.controller.configure(internalConfig(f, false), f.fixture.player));
        f.fixture.world.particleFailure = true;
        f.caller.begin(payload(61));
        assertDoesNotThrow(f.caller::tick);
        assertEquals("accepted", f.caller.transportResult().orElseThrow().code());
        assertEquals(1, f.caller.snapshot().orElseThrow().delivery().orElseThrow().acknowledged());
        assertEquals(1, f.fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(new DataValue.IntegerValue(0), f.fixture.computer.snapshot().orElseThrow().machine().memory().field("value"));
        f.fixture.world.particleFailure = false;
        f.caller.tick();
        assertEquals(1, f.fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(0, f.fixture.world.particles);
        f.fixture.computer.tick();
        assertEquals(new DataValue.IntegerValue(61), f.fixture.computer.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(f.fixture.computer.snapshot().orElseThrow().mailbox().queued().isEmpty());
    }

    @Test void reloadedOverCapacityBindingsKeepGeometryAndConfigurationButStopCommunication() throws Exception {
        verifyOverCapacityArchive(false);
    }
    @Test void reloadedOverCapacityBindingsDoNotDrainAnAlreadyAdmittedRequest() throws Exception {
        verifyOverCapacityArchive(true);
    }
    private void verifyOverCapacityArchive(boolean queued) throws Exception {
        var setup = internal(true); var fixture = setup.fixture();
        var configuration = new ClusterConfiguration(internalConfig(setup, false).members(),
            Map.of("query", new ClusterConfiguration.Port("B", "worker", "return", "client", true)));
        assertEquals("configured", fixture.controller.configure(configuration, fixture.player));
        var original = request();
        if (queued) assertEquals(AdmissionStatus.ACCEPTED, fixture.controller.receive("query", original));
        var saved = fixture.controller.saveWithFullMetadata(RegistryAccess.EMPTY);
        var spec = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER_SPEC;
        assertFalse(spec.isLoaded(), "The isolated JVM fixture must not overwrite an existing config owner");
        var values = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        spec.correct(values); values.set("biocomputing.cluster.maxComputers", 1);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(
            com.electronwill.nightconfig.core.CommentedConfig.class, java.nio.file.Path.class, net.neoforged.fml.config.ModConfig.class);
        constructor.setAccessible(true);
        try {
            spec.acceptConfig((net.neoforged.fml.config.IConfigSpec.ILoadedConfig)constructor.newInstance(values, null, null));
            fixture.controller.loadWithComponents(saved, RegistryAccess.EMPTY);
            assertEquals(EnclosureScanner.Status.READY, fixture.controller.enclosure().status());
            assertEquals(Set.of(MEMBER, FAR), fixture.controller.enclosure().enclosure().orElseThrow().members());
            assertEquals(configuration, fixture.controller.routingState().orElseThrow().configuration());
            assertTrue(fixture.controller.advertisements().isEmpty(), "Over-capacity saved bindings cannot advertise live services");
            assertEquals("computer_limit", fixture.controller.diagnosticCode());
            assertEquals(AdmissionStatus.UNAVAILABLE, fixture.controller.receive("query", request()));
            assertEquals("computer_limit", fixture.controller.receiveInternal(MEMBER, "return", request()).code());
            fixture.controller.tick();
            assertEquals(queued ? 1 : 0, fixture.controller.routingState().orElseThrow().mailbox().queued().size());
            assertEquals(0, fixture.world.particles);
            assertTrue(fixture.computer.snapshot().orElseThrow().mailbox().queued().isEmpty());
            assertEquals(queued ? "busy" : "configured", fixture.controller.configure(ClusterConfiguration.empty(), fixture.player));
            assertEquals(EnclosureScanner.Status.READY, fixture.controller.enclosure().status());
            assertEquals(queued ? "computer_limit" : "configured", fixture.controller.diagnosticCode());
            if (queued) {
                values.set("biocomputing.cluster.maxComputers", 2); spec.afterReload();
                assertEquals(Set.of("query"), fixture.controller.advertisements().stream().map(endpoint -> endpoint.name()).collect(java.util.stream.Collectors.toSet()));
                fixture.controller.tick();
                var received = fixture.computer.snapshot().orElseThrow().mailbox().queued().getFirst();
                assertEquals(original.eventId(), received.eventId());
                assertEquals(original.payload(), received.payload());
                assertTrue(fixture.controller.routingState().orElseThrow().mailbox().queued().isEmpty());
                assertEquals(1, fixture.world.particles);
            }
        } finally { spec.acceptConfig(null); }
    }
    @Test void clearingCommunicationBindingsDoesNotRemoveGeometricMembershipOrExposePorts() throws Exception {
        var fixture = fixture();
        assertEquals(Set.of(MEMBER), fixture.controller.enclosure().enclosure().orElseThrow().members());
        assertTrue(fixture.controller.routingState().orElseThrow().configuration().members().isEmpty());
        assertEquals("configured", fixture.controller.configure(configuration(fixture.computer), fixture.player));
        assertEquals("configured", fixture.controller.configure(ClusterConfiguration.empty(), fixture.player));
        assertTrue(fixture.controller.enclosure().enclosure().orElseThrow().containsMember(MEMBER));
        assertTrue(fixture.computer.clusterAttachment().isEmpty());
        assertTrue(fixture.controller.advertisements().isEmpty());
        assertTrue(publicView(fixture).candidates().getFirst().member());
    }
    @Test void unprogrammedTouchingComputerHasGeometricMembershipBeforeAnyConfiguration() throws Exception {
        var fixture = fixture();
        var computer = addComputer(fixture.world, MEMBER, null, "worker");
        var view = publicView(fixture);
        assertTrue(view.configuration().members().isEmpty());
        assertFalse(view.candidates().getFirst().selectable());
        assertEquals("no_program", view.candidates().getFirst().diagnostic());
        var encoded = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.decodeValue(
            com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlWire.encodeView(view, LIMITS.archive()), LIMITS.archive());
        var candidate = (DataValue.RecordValue)((DataValue.ListValue)encoded.field("candidates")).values().getFirst();
        assertEquals(new DataValue.BooleanValue(true), candidate.field("member"));
        assertTrue(computer.clusterAttachment().isEmpty(), "Geometry must not reserve outputs or grant communication");
        assertTrue(fixture.controller.advertisements().isEmpty());
    }

    @Test void bindingCapacityDoesNotChangePhysicalFormation() throws Exception {
        var fixture = fixture();
        int limit = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.enclosure(
            com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER_SPEC.isLoaded()).maxComputers();
        var bindings = new LinkedHashMap<String, ClusterConfiguration.Member>();
        for (int i = 0; i <= limit; i++)
            bindings.put("node" + i, new ClusterConfiguration.Member(UUID.randomUUID(), new BlockPos(i + 1, 1, 1)));
        assertEquals("computer_limit", fixture.controller.configure(new ClusterConfiguration(bindings, Map.of()), fixture.player));
        assertEquals(EnclosureScanner.Status.READY, fixture.controller.enclosure().status());
        assertTrue(fixture.controller.routingState().orElseThrow().configuration().members().isEmpty());
    }

    private static com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlView publicView(Fixture fixture) throws Exception {
        var type = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerMenu.class;
        var menu = allocate(type);
        var owner = type.getDeclaredField("serverOwner"); owner.setAccessible(true); owner.set(menu, fixture.controller);
        var method = type.getDeclaredMethod("publicView", ServerPlayer.class); method.setAccessible(true);
        return (com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlView)method.invoke(menu, fixture.player);
    }
    static final BlockPos CONTROLLER = new BlockPos(0, 1, 1), MEMBER = new BlockPos(1, 1, 1);
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(100, 100_000, 32), new ArchiveLimits(100_000, 1_000_000, 10_000_000)));
    @BeforeAll static void bootstrap() throws Exception {
        WorldEnclosureViewTest.bootstrap();
        var registry = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE;
        for (var type : List.of(com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT,
                com.nobodiiiii.createbiotech.registry.CBParticleTypes.BIOCOMPUTING_READ_WAIT)) {
            if (!registry.containsKey(type.getId())) Registry.register(registry, type.getId(), new net.minecraft.core.particles.SimpleParticleType(false));
        }
    }

    @Test void touchingComputersTransferPlainValuesOnlyOnExplicitConnectionAndShowDirectedInteraction() throws Exception {
        var f = internal(false); var config = internalConfig(f, false);
        assertEquals("configured", f.fixture.controller.configure(config, f.fixture.player));
        var a = f.caller; var b = f.fixture.computer;
        a.begin(payload(29)); a.tick();
        assertEquals(1, b.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(new DataValue.IntegerValue(0), b.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(f.fixture.controller.advertisements().isEmpty());
        assertEquals(1, f.fixture.world.particles);
        assertSame(com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT.get(), f.fixture.world.particleType,
            "Use the existing solid particle, visible before translucent shell rendering");
        assertEquals(FAR, f.fixture.world.particleFrom); assertEquals(MEMBER, f.fixture.world.particleTo);
        assertEquals("busy", f.fixture.controller.configure(ClusterConfiguration.empty(), f.fixture.player));
        b.tick(); assertEquals(new DataValue.IntegerValue(29), b.snapshot().orElseThrow().machine().memory().field("value"));
        assertEquals("configured", f.fixture.controller.configure(ClusterConfiguration.empty(), f.fixture.player));
    }
    @Test void internalRpcPreservesCorrelationAndRequiresExplicitReverseConnection() throws Exception {
        var f = internal(true); var config = internalConfig(f, true);
        assertEquals("configured", f.fixture.controller.configure(config, f.fixture.player));
        f.caller.begin(payload(31)); f.caller.tick();
        var envelope = f.fixture.computer.snapshot().orElseThrow().mailbox().queued().getFirst();
        assertEquals("caller", ((MessageProtocol.Request)envelope.protocol()).context().returnEndpoint());
        assertTrue(f.fixture.controller.routingState().orElseThrow().pending().isEmpty());
        assertEquals("busy", f.fixture.controller.configure(ClusterConfiguration.empty(), f.fixture.player));
        f.fixture.computer.tick();
        assertEquals(new DataValue.IntegerValue(0), f.caller.snapshot().orElseThrow().machine().memory().field("value"));
        f.caller.tick();
        assertEquals(1, f.caller.snapshot().orElseThrow().mailbox().queued().size(), "Reply waits for the caller's next world activity tick");
        f.fixture.world.clock++;
        f.caller.tick(); assertEquals(new DataValue.IntegerValue(31), f.caller.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(f.caller.snapshot().orElseThrow().rpc().pending().isEmpty());
        assertEquals(2, f.fixture.world.particles);
        assertEquals("configured", f.fixture.controller.configure(ClusterConfiguration.empty(), f.fixture.player));

        var oneWay = internal(true);
        assertEquals("configured", oneWay.fixture.controller.configure(internalConfig(oneWay, false), oneWay.fixture.player));
        oneWay.caller.begin(payload(1)); oneWay.caller.tick();
        assertEquals("return_route_missing", oneWay.caller.transportResult().orElseThrow().code());
        assertTrue(oneWay.fixture.computer.snapshot().orElseThrow().mailbox().queued().isEmpty());
    }
    @Test void brokenShellAndClosedInternalTargetRetainSourceOutboxUntilRecoveryWithoutWiredFallback() throws Exception {
        var f = internal(false); assertEquals("configured", f.fixture.controller.configure(internalConfig(f, false), f.fixture.player));
        f.fixture.world.states.remove(BlockPos.ZERO); f.caller.begin(payload(37)); f.caller.tick();
        assertEquals("open_shell", f.caller.transportResult().orElseThrow().code()); assertEquals(0, f.fixture.world.particles);
        f.fixture.world.states.put(BlockPos.ZERO, WorldEnclosureViewTest.casing.defaultBlockState());
        f.fixture.computer.disableEndpoint(); f.caller.tick();
        assertEquals("closed", f.caller.transportResult().orElseThrow().code());
        f.fixture.computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("worker", true));
        f.caller.tick(); assertTrue(f.caller.transportResult().orElseThrow().accepted());
        assertEquals(1, f.fixture.computer.snapshot().orElseThrow().mailbox().queued().size()); assertEquals(1, f.fixture.world.particles);
        f.caller.tick(); assertEquals(1, f.fixture.world.particles);
    }
    @Test void internalOldReplyCannotOverwriteANewerRequestAndDoesNotReplayParticles() throws Exception {
        var f = internal(true); assertEquals("configured", f.fixture.controller.configure(internalConfig(f, true), f.fixture.player));
        f.caller.begin(payload(43)); f.caller.tick(); f.fixture.computer.tick();
        var oldReply = f.caller.snapshot().orElseThrow().mailbox().queued().getFirst();
        f.fixture.world.clock++; f.caller.tick();
        f.fixture.world.clock++;
        f.caller.begin(payload(47)); f.caller.tick(); int particles = f.fixture.world.particles;
        assertEquals("stale_reply", f.fixture.controller.receiveInternal(MEMBER, "caller", oldReply).code());
        assertEquals(particles, f.fixture.world.particles);
        assertEquals(new DataValue.IntegerValue(43), f.caller.snapshot().orElseThrow().machine().memory().field("value"));
        f.fixture.computer.tick(); f.fixture.world.clock++; f.caller.tick();
        assertEquals(new DataValue.IntegerValue(47), f.caller.snapshot().orElseThrow().machine().memory().field("value"));
    }
    @Test void replacementControllerCannotReleaseReservedOutputsOfAnInFlightInternalMessage() throws Exception {
        var f = internal(false); var config = internalConfig(f, false);
        assertEquals("configured", f.fixture.controller.configure(config, f.fixture.player));
        f.fixture.computer.disableEndpoint(); f.caller.begin(payload(53)); f.caller.tick();
        var attachment = f.caller.clusterAttachment(); var before = f.caller.snapshot().orElseThrow();
        var replacement = new Controller(CONTROLLER, f.fixture.controller.getBlockState()); replacement.setLevel(f.fixture.world);
        f.fixture.world.entities.put(CONTROLLER, replacement);
        var memberOnly = new ClusterConfiguration(Map.of("A", config.members().get("A")), Map.of());
        assertEquals("busy", replacement.configure(memberOnly, f.fixture.player));
        assertEquals(attachment, f.caller.clusterAttachment()); assertEquals(before, f.caller.snapshot().orElseThrow());
        assertTrue(replacement.routingState().orElseThrow().configuration().members().isEmpty());
        f.caller.tick(); assertEquals("controller_identity_changed", f.caller.transportResult().orElseThrow().code());
    }
    @Test void removingAnAttachmentCannotReleasePendingConcentratedOutputToCableFallback() throws Exception {
        var f = internal(false);
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(f.caller.communicationIdentity().orElseThrow(), FAR)),
            Map.of("public", new ClusterConfiguration.Port("A", "caller", "to-b", "external", false)));
        assertEquals("configured", f.fixture.controller.configure(config, f.fixture.player));
        f.caller.begin(payload(59)); f.caller.tick(); var attachment = f.caller.clusterAttachment();
        assertEquals("closed", f.caller.transportResult().orElseThrow().code());
        assertEquals("busy", f.fixture.controller.configure(ClusterConfiguration.empty(), f.fixture.player));
        assertEquals(attachment, f.caller.clusterAttachment());
    }
    @Test void disabledOrAbsentInternalConnectionCannotUseMembershipAsAnImplicitRoute() throws Exception {
        var f = internal(false); var cfg = internalConfig(f, false);
        var disabled = new ClusterConfiguration(cfg.members(), cfg.ports(), Map.of("a-b", new ClusterConfiguration.Connection("A", "to-b", "B", "worker", false)));
        assertEquals("configured", f.fixture.controller.configure(disabled, f.fixture.player));
        f.caller.begin(payload(41)); f.caller.tick();
        assertEquals("closed", f.caller.transportResult().orElseThrow().code());
        assertEquals("closed", f.fixture.controller.receiveInternal(FAR, "undeclared",
            new MessageEnvelope(f.caller.communicationIdentity().orElseThrow(), 50, UUID.randomUUID(), 0, payload(41))).code());
        assertTrue(f.fixture.computer.snapshot().orElseThrow().mailbox().queued().isEmpty());
    }

    static final BlockPos FAR = new BlockPos(1, 1, 3);

    @Test void closedMemberBehindASecondControllerKeepsOriginalRequestAcrossRouterReloadUntilReopened() throws Exception {
        // Catches acknowledging a CLOSED admission, or dropping the frozen return route on reload.
        // Two individually complete shells: a missing casing must not masquerade as endpoint closure.
        var f = internal(true); var world = f.fixture.world;
        for (var p : BlockPos.betweenClosed(new BlockPos(0, 0, 2), new BlockPos(2, 3, 2)))
            world.states.put(p.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
        var sourcePos = new BlockPos(0, 1, 3);
        var source = new Controller(sourcePos, f.fixture.controller.getBlockState()); source.setLevel(world);
        world.states.put(sourcePos, source.getBlockState()); world.entities.put(sourcePos, source);
        var target = f.fixture.controller;
        var cable = new DataCableBlock(BlockBehaviour.Properties.of());
        for (int z = 1; z <= 3; z++) world.states.put(new BlockPos(-1, 1, z), cable.defaultBlockState());
        var sourceConfig = new ClusterConfiguration(
            Map.of("caller", new ClusterConfiguration.Member(f.caller.communicationIdentity().orElseThrow(), FAR)),
            Map.of("client", new ClusterConfiguration.Port("caller", "caller", "to-b", "query", true)));
        assertEquals("configured", source.configure(sourceConfig, f.fixture.player));
        assertEquals("configured", target.configure(configuration(f.fixture.computer), f.fixture.player));
        f.fixture.computer.disableEndpoint();
        assertEquals(EnclosureScanner.Status.READY, source.enclosure().status());
        assertEquals(EnclosureScanner.Status.READY, target.enclosure().status());
        f.caller.begin(payload(47)); f.caller.tick();
        var original = source.routingState().orElseThrow().outbound().orElseThrow();
        source.tick();
        assertEquals("closed", source.diagnosticCode());
        assertEquals(original, source.routingState().orElseThrow().outbound().orElseThrow());
        assertTrue(target.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(new DataValue.IntegerValue(0), f.fixture.computer.snapshot().orElseThrow().machine().memory().field("value"));

        var sourceSave = source.saveWithFullMetadata(RegistryAccess.EMPTY);
        var targetSave = target.saveWithFullMetadata(RegistryAccess.EMPTY);
        source = new Controller(sourcePos, source.getBlockState()); source.setLevel(world);
        source.loadWithComponents(sourceSave, RegistryAccess.EMPTY); world.entities.put(sourcePos, source);
        target = new Controller(CONTROLLER, target.getBlockState()); target.setLevel(world);
        target.loadWithComponents(targetSave, RegistryAccess.EMPTY); world.entities.put(CONTROLLER, target);
        source.tick();
        assertEquals("closed", source.diagnosticCode());
        assertEquals(original, source.routingState().orElseThrow().outbound().orElseThrow());
        assertEquals(1, f.caller.snapshot().orElseThrow().rpc().pending().size());

        f.fixture.computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("worker", true));
        source.tick();
        assertTrue(source.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(1, target.routingState().orElseThrow().mailbox().queued().size());
        var forwarded = target.routingState().orElseThrow().mailbox().queued().getFirst();
        assertEquals(original.envelope(), forwarded, "Reopening must continue the same envelope, not allocate another request");
        target.tick(); f.fixture.computer.tick(); target.tick(); source.tick(); world.clock++; f.caller.tick();
        assertEquals(new DataValue.IntegerValue(47), f.caller.snapshot().orElseThrow().machine().memory().field("value"));
        assertEquals(new DataValue.IntegerValue(47), f.fixture.computer.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(f.caller.snapshot().orElseThrow().rpc().pending().isEmpty());
        assertFalse(source.routingState().orElseThrow().busy()); assertFalse(target.routingState().orElseThrow().busy());
    }

    record InternalFixture(Fixture fixture, ComputerBehaviour caller) {}
    /** Synthetic world fixture: deliver the same physical tick witnesses and post-level event as the native server. */
    static void finishFlowTick(InternalFixture f) {
        f.fixture().computer().tick(); f.caller().tick();
        com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionTicks.endTick(
            new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(() -> true, f.fixture().world()));
    }
    static InternalFixture internal(boolean rpc) throws Exception {
        var f = fixture(); var world = f.world; var lower = world.states.get(MEMBER); var upper = world.states.get(MEMBER.above());
        world.states.clear();
        for (var p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(2, 3, 4)))
            if (p.getX() == 0 || p.getX() == 2 || p.getY() == 0 || p.getY() == 3 || p.getZ() == 0 || p.getZ() == 4)
                world.states.put(p.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
        world.states.put(CONTROLLER, f.controller.getBlockState()); world.states.put(MEMBER, lower); world.states.put(MEMBER.above(), upper);
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of("v", new ComputationGraph.Input("value"),
            "target", new ComputationGraph.Constant(new DataValue.TextValue("to-b")), "record", new ComputationGraph.MakeRecord(Map.of("value", "v"))),
            Map.of("value", "v", "target", "target", "payload", "record"));
        var cells = Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)));
        var write = List.of(new ProgramDefinition.Write("value", "value"));
        if (!rpc) f.computer.install(new ProgramArchive(new ProgramDefinition(graph, cells, List.of(), List.of(),
            Map.of("message", new ProgramDefinition.Entry(graph, write, List.of()))), Map.of()), ComputerRuntime.MemoryPolicy.RESET);
        var sends = List.of(rpc ? ProgramDefinition.Send.request("target", "payload", "query", "reply") : new ProgramDefinition.Send("target", "payload"));
        var caller = addComputer(world, FAR, new ProgramArchive(new ProgramDefinition(graph, cells, List.of(), sends,
            rpc ? Map.of("reply", new ProgramDefinition.Entry(graph, write, List.of())) : Map.of()), Map.of()), "caller");
        return new InternalFixture(f, caller);
    }
    static ClusterConfiguration internalConfig(InternalFixture f, boolean reply) {
        var links = new LinkedHashMap<String, ClusterConfiguration.Connection>();
        links.put("a-b", new ClusterConfiguration.Connection("A", "to-b", "B", "worker", true));
        if (reply) links.put("b-a", new ClusterConfiguration.Connection("B", "caller", "A", "caller", true));
        return new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(f.caller.communicationIdentity().orElseThrow(), FAR),
            "B", new ClusterConfiguration.Member(f.fixture.computer.communicationIdentity().orElseThrow(), MEMBER)), Map.of(), links);
    }
    static DataValue.RecordValue payload(long value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }

    @Test void externalRequestQueuesThenEntersOnlyConfiguredMemberWithFrozenReturnPort() throws Exception {
        var fixture = fixture(); var block = fixture.controller; var computer = fixture.computer;
        assertEquals("configured", block.configure(configuration(computer), fixture.player));
        assertEquals(Set.of("query"), block.advertisements().stream().map(c -> c.name()).collect(java.util.stream.Collectors.toSet()));
        var request = request(); assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request));
        assertEquals(0, computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(1, block.routingState().orElseThrow().mailbox().queued().size());
        block.tick();
        var delivered = computer.snapshot().orElseThrow().mailbox().queued().getFirst();
        assertEquals(request.source(), delivered.source()); assertEquals(request.payload(), delivered.payload());
        var protocol = (MessageProtocol.Request)delivered.protocol();
        assertEquals("return", protocol.context().returnEndpoint());
        assertEquals(((MessageProtocol.Request)request.protocol()).context().correlation(), protocol.context().correlation());
        assertTrue(block.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(new DataValue.IntegerValue(0), computer.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(fixture.world.particles > 0);
    }

    @Test void brokenShellRetainsAcceptedQueueAndStopsAdmissionsUntilRepaired() throws Exception {
        var fixture = fixture(); var block = fixture.controller;
        assertEquals("configured", block.configure(configuration(fixture.computer), fixture.player));
        assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request()));
        fixture.world.states.remove(BlockPos.ZERO); block.tick();
        assertEquals(1, block.routingState().orElseThrow().mailbox().queued().size());
        assertEquals(0, fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertEquals(AdmissionStatus.UNAVAILABLE, block.receive("query", request()));
        assertEquals("open_shell", block.diagnosticCode()); assertTrue(block.advertisements().isEmpty());
        fixture.world.states.put(BlockPos.ZERO, WorldEnclosureViewTest.casing.defaultBlockState()); block.tick();
        assertEquals(1, fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
    }

    @Test void identityMismatchOrClosedEndpointNeverRedirectsAlreadyAcceptedInput() throws Exception {
        var fixture = fixture(); var block = fixture.controller;
        assertEquals("configured", block.configure(configuration(fixture.computer), fixture.player));
        assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request()));
        fixture.computer.disableEndpoint(); block.tick();
        assertEquals("closed", block.diagnosticCode()); assertEquals(1, block.routingState().orElseThrow().mailbox().queued().size());
        fixture.computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("different", true)); block.tick();
        assertEquals("endpoint_changed", block.diagnosticCode()); assertEquals(0, fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
    }

    @Test void configurationRejectsUnownedPositionIdentityAndPlayerPermissions() throws Exception {
        var fixture = fixture(); var actual = configuration(fixture.computer);
        var wrong = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(UUID.randomUUID(), MEMBER)), actual.ports());
        assertEquals("identity_changed", fixture.controller.configure(wrong, fixture.player));
        fixture.world.denied = MEMBER;
        assertEquals("access_denied", fixture.controller.configure(actual, fixture.player));
        fixture.world.denied = null;
        var outside = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(fixture.computer.snapshot().orElseThrow().id(), new BlockPos(99, 1, 1))), actual.ports());
        assertEquals("not_contacting", fixture.controller.configure(outside, fixture.player));
        assertTrue(fixture.controller.routingState().orElseThrow().configuration().members().isEmpty());
    }

    @Test void persistedControllerIdentityAndPendingQueueSurviveBlockEntityReload() throws Exception {
        var fixture = fixture(); var block = fixture.controller;
        assertEquals("configured", block.configure(configuration(fixture.computer), fixture.player));
        var request = request(); assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request));
        var tag = block.saveWithFullMetadata(RegistryAccess.EMPTY); var identity = block.controllerId();
        var restored = new Controller(CONTROLLER, block.getBlockState()); restored.setLevel(fixture.world);
        restored.loadWithComponents(tag, RegistryAccess.EMPTY); fixture.world.entities.put(CONTROLLER, restored);
        assertEquals(identity, restored.controllerId());
        assertEquals(AdmissionStatus.DUPLICATE, restored.receive("query", request));
        restored.tick(); assertEquals(1, fixture.computer.snapshot().orElseThrow().mailbox().queued().size());
        assertTrue(restored.routingState().orElseThrow().mailbox().queued().isEmpty());
    }

    @Test void memberExecutesOnOwnTickAndReturnsReplyOverExplicitAttachmentWithoutCable() throws Exception {
        var fixture = fixture(); var block = fixture.controller; var computer = fixture.computer;
        assertEquals("configured", block.configure(configuration(computer), fixture.player));
        var request = request(); assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request)); block.tick();
        computer.tick();
        assertEquals(new DataValue.IntegerValue(7), computer.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(computer.transportResult().orElseThrow().accepted());
        var returned = block.routingState().orElseThrow().outbound().orElseThrow();
        assertEquals(ClusterState.Side.EXTERNAL, returned.target().side()); assertEquals("client", returned.target().port());
        assertEquals(request.payload(), returned.envelope().payload());
        assertEquals(((MessageProtocol.Request)request.protocol()).context().correlation(), ((MessageProtocol.Reply)returned.envelope().protocol()).correlation());
    }

    @Test void attachmentFailureDoesNotAcknowledgeOrFallBackToWiredNames() throws Exception {
        var fixture = fixture(); var block = fixture.controller; var computer = fixture.computer;
        assertEquals("configured", block.configure(configuration(computer), fixture.player));
        assertEquals(AdmissionStatus.ACCEPTED, block.receive("query", request())); block.tick();
        fixture.world.entities.remove(CONTROLLER); computer.tick();
        assertEquals("controller_missing", computer.transportResult().orElseThrow().code());
        assertFalse(computer.transportResult().orElseThrow().accepted());
        fixture.world.entities.put(CONTROLLER, block); computer.tick();
        assertTrue(computer.transportResult().orElseThrow().accepted());
        assertEquals(1, block.routingState().orElseThrow().mailbox().queued().size());
    }

    @Test void forgedInternalSourceCannotUseAConfiguredReturnPort() throws Exception {
        var fixture = fixture(); var block = fixture.controller;
        assertEquals("configured", block.configure(configuration(fixture.computer), fixture.player));
        var envelope = request();
        assertEquals("identity_changed", block.receiveInternal(MEMBER, "return", envelope).code());
        assertEquals("not_member", block.receiveInternal(new BlockPos(99, 1, 1), "return", envelope).code());
        assertTrue(block.routingState().orElseThrow().mailbox().queued().isEmpty());
    }

    @Test void actualComputerRequestCrossesExternalCableAndReturnsOnlyAfterIndependentMemberExecution() throws Exception {
        var fixture = fixture(); var controller = fixture.controller; var world = fixture.world;
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of("v", new ComputationGraph.Input("value"),
            "target", new ComputationGraph.Constant(new DataValue.TextValue("query")), "record", new ComputationGraph.MakeRecord(Map.of("value", "v"))),
            Map.of("value", "v", "target", "target", "payload", "record"));
        var program = new ProgramDefinition(graph, Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(), List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "reply")),
            Map.of("reply", new ProgramDefinition.Entry(graph, List.of(new ProgramDefinition.Write("value", "value")), List.of())));
        var client = addComputer(world, new BlockPos(-2, 1, 1), new ProgramArchive(program, Map.of()), "client");
        var cable = new DataCableBlock(BlockBehaviour.Properties.of()); var cablePos = new BlockPos(-1, 1, 1);
        world.states.put(cablePos, cable.defaultBlockState());
        assertEquals("configured", controller.configure(configuration(fixture.computer), fixture.player));
        client.begin(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(11)))); client.tick();
        assertEquals(1, controller.routingState().orElseThrow().mailbox().queued().size());
        controller.tick(); fixture.computer.tick();
        assertEquals(new DataValue.IntegerValue(11), fixture.computer.snapshot().orElseThrow().machine().memory().field("value"));
        world.states.remove(cablePos); controller.tick();
        assertEquals("not_found", controller.diagnosticCode()); assertEquals(1, controller.routingState().orElseThrow().mailbox().queued().size());
        fixture.computer.disableEndpoint();
        world.states.put(cablePos, cable.defaultBlockState()); controller.tick();
        assertEquals("closed", controller.diagnosticCode());
        assertEquals(1, controller.routingState().orElseThrow().mailbox().queued().size());
        assertTrue(client.snapshot().orElseThrow().mailbox().queued().isEmpty());
        fixture.computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("worker", true));
        controller.tick();
        assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty());
        assertEquals(new DataValue.IntegerValue(0), client.snapshot().orElseThrow().machine().memory().field("value"));
        world.clock++; client.tick(); assertEquals(new DataValue.IntegerValue(11), client.snapshot().orElseThrow().machine().memory().field("value"));
        assertTrue(client.snapshot().orElseThrow().rpc().pending().isEmpty());
    }

    @Test void genericBlockReaderCannotReadPrivateControllerQueuesOrRoutingSave() throws Exception {
        var fixture = fixture();
        var result = com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BlockSnapshotReader.readComponents(fixture.world, CONTROLLER, Direction.WEST,
            fixture.player, new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReadLimits(4096, 32768, 65536), Set.of("create_biotech:block"));
        assertEquals("access_denied", ((DataValue.ErrorValue)result.field("error")).code());
    }

    @Test void newlyFormedAndRepairedIdleControllerReportsReadyInsteadOfAnOldStructureFailure() throws Exception {
        var fixture = fixture(); var controller = fixture.controller;
        assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
        assertEquals("ready", controller.diagnosticCode());
        fixture.world.states.remove(BlockPos.ZERO);
        assertEquals(EnclosureScanner.Status.OPEN_SHELL, controller.enclosure().status());
        assertEquals("open_shell", controller.diagnosticCode());
        fixture.world.states.put(BlockPos.ZERO, WorldEnclosureViewTest.casing.defaultBlockState());
        assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
        assertEquals("ready", controller.diagnosticCode());
    }

    @Test void corruptControllerOrRouterSaveIsReportedAndRetainedInsteadOfExposingAnOldUsableState() throws Exception {
        for (boolean outer : new boolean[]{false, true}) {
            var fixture = fixture(); var controller = fixture.controller;
            assertEquals("configured", controller.configure(configuration(fixture.computer), fixture.player));
            var tag = controller.saveWithFullMetadata(RegistryAccess.EMPTY);
            if (outer) tag.put(ClusterControllerBlockEntity.ROOT, net.minecraft.nbt.StringTag.valueOf("broken controller"));
            else tag.getCompound(ClusterControllerBlockEntity.ROOT).put("Router", net.minecraft.nbt.StringTag.valueOf("broken router"));
            controller.loadWithComponents(tag, RegistryAccess.EMPTY);
            assertTrue(controller.routingState().isEmpty());
            controller.enclosure();
            assertEquals("invalid_save", controller.diagnosticCode());
            assertTrue(controller.advertisements().isEmpty());
            assertEquals(tag.get(ClusterControllerBlockEntity.ROOT), controller.saveWithFullMetadata(RegistryAccess.EMPTY).get(ClusterControllerBlockEntity.ROOT));
        }
    }

    static ClusterConfiguration configuration(ComputerBehaviour computer) {
        return new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(computer.snapshot().orElseThrow().id(), MEMBER)),
            Map.of("query", new ClusterConfiguration.Port("A", "worker", "return", "client", true)));
    }
    static MessageEnvelope request() {
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        return new MessageEnvelope(source, 1, event, 0, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(7))),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "client")));
    }
    static Fixture fixture() throws Exception {
        var world = allocate(World.class); world.states = new HashMap<>(); world.entities = new HashMap<>(); world.border = new WorldBorder(); world.clock = 10;
        world.chunks = allocate(LoadedChunks.class); world.chunks.chunk = allocate(LoadedChunk.class); world.chunks.chunk.world = world;
        world.storage = new net.minecraft.world.level.storage.DimensionDataStorage(new java.io.File("unused-in-memory-world"), null, null);
        world.storage.set("create_biotech_xbus_wiring", new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusWiringSavedData());
        world.storage.set(com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData.DATA_NAME,
            new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData());
        for (var pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(2, 3, 2)))
            if (pos.getX() == 0 || pos.getX() == 2 || pos.getY() == 0 || pos.getY() == 3 || pos.getZ() == 0 || pos.getZ() == 2)
                world.states.put(pos.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
        var controller = new Controller(CONTROLLER, Controller.BLOCK.defaultBlockState()); controller.setLevel(world);
        world.states.put(CONTROLLER, controller.getBlockState()); world.entities.put(CONTROLLER, controller);
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of("v", new ComputationGraph.Input("value"),
            "record", new ComputationGraph.MakeRecord(Map.of("value", "v"))), Map.of("value", "v", "payload", "record"));
        var entry = new ProgramDefinition.Entry(graph, List.of(new ProgramDefinition.Write("value", "value")), List.of(ProgramDefinition.Send.reply("payload")));
        var program = new ProgramDefinition(graph, Map.of("value", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(), List.of(), Map.of("request", entry));
        var computer = addComputer(world, MEMBER, new ProgramArchive(program, Map.of()), "worker");
        var player = allocate(Actor.class); player.world = world;
        return new Fixture(world, controller, computer, player);
    }
    static ComputerBehaviour addComputer(World world, BlockPos position, ProgramArchive program, String endpoint) throws Exception {
        var lower = WorldEnclosureViewTest.computer.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        world.states.put(position, lower); world.states.put(position.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        var host = new Host(position, lower); host.setLevel(world); world.entities.put(position, host);
        var constructor = ComputerBehaviour.class.getDeclaredConstructor(SmartBlockEntity.class, java.util.function.BooleanSupplier.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        host.computer = (ComputerBehaviour)constructor.newInstance(host, (java.util.function.BooleanSupplier) () -> true,
            (java.util.function.Supplier<HardwareSelection>) () -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", LIMITS));
        if (program != null) host.computer.install(program, ComputerRuntime.MemoryPolicy.RESET);
        host.computer.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration(endpoint, true));
        return host.computer;
    }
    record Fixture(World world, Controller controller, ComputerBehaviour computer, Actor player) {}
    static <T> T allocate(Class<T> type) throws Exception { var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return type.cast(((sun.misc.Unsafe)f.get(null)).allocateInstance(type)); }
    static final class Actor extends ServerPlayer {
        World world; private Actor() { super(null, null, null, null); }
        @Override public ServerLevel serverLevel() { return world; }
        @Override public double distanceToSqr(double x, double y, double z) { return 0; }
    }
    static final class World extends ServerLevel {
        LoadedChunks chunks; int requestingEntityReads; long blockStateReads; BlockPos unloaded;
        net.minecraft.world.level.storage.DimensionDataStorage storage;
        Map<BlockPos, BlockState> states; Map<BlockPos, BlockEntity> entities; WorldBorder border; BlockPos denied; int particles;
        BlockPos particleFrom, particleTo; ParticleOptions particleType; boolean particleFailure;
        List<MarkerParticle> markers = new ArrayList<>();
        long clock;
        private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean isLoaded(BlockPos pos) { return !pos.equals(unloaded); }
        @Override public boolean hasChunkAt(BlockPos pos) { return true; }
        @Override public BlockState getBlockState(BlockPos pos) { blockStateReads++; return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { requestingEntityReads++; return entities.get(pos); }
        @Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return chunks; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean mayInteract(Player player, BlockPos pos) { return !pos.equals(denied); }
        @Override public long getGameTime() { return clock; }
        @Override public net.minecraft.world.level.storage.DimensionDataStorage getDataStorage() { return storage; }
        @Override public <T, C> T getCapability(net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability, BlockPos pos, C side) {
            if (capability != com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationEndpoint.CAPABILITY) return null;
            var entity = entities.get(pos);
            if (entity instanceof ClusterControllerBlockEntity controller && side == Direction.WEST) return capability.typeClass().cast(controller);
            if (entity instanceof Host host) return capability.typeClass().cast(host.computer);
            return null;
        }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public <T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
            if (particleFailure) throw new IllegalStateException("Injected particle provider failure");
            if (count == 1) {
                assertEquals(0, speed); assertEquals(0, dx); assertEquals(0, dy); assertEquals(0, dz);
                if (markers == null) markers = new ArrayList<>(); // This world fixture is allocated without its constructor.
                markers.add(new MarkerParticle(particle, new net.minecraft.world.phys.Vec3(x, y, z)));
                return 1;
            }
            assertEquals(0, count, "A directed packet, not random particle spread");
            assertEquals(1, speed, "StraightEnchant consumes full displacement, not speed");
            particles++; particleType = particle; particleFrom = BlockPos.containing(x, y, z);
            particleTo = BlockPos.containing(x + dx, y + dy, z + dz); return 1;
        }
    }
    static final class LoadedChunks extends net.minecraft.server.level.ServerChunkCache {
        LoadedChunk chunk; boolean unavailable;
        private LoadedChunks() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x, int z) { return unavailable ? null : chunk; }
    }
    static final class LoadedChunk extends net.minecraft.world.level.chunk.LevelChunk {
        World world;
        private LoadedChunk() { super(null, new net.minecraft.world.level.ChunkPos(0, 0)); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return world.entities.get(pos); }
    }
    record MarkerParticle(ParticleOptions type, net.minecraft.world.phys.Vec3 point) {}
    static final class Controller extends ClusterControllerBlockEntity {
        static final Marker BLOCK = new Marker();
        static final BlockEntityType<Controller> TYPE = Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE,
            net.minecraft.resources.ResourceLocation.parse("create_biotech:test_cluster_controller_runtime"), BlockEntityType.Builder.of(Controller::new, BLOCK).build(null));
        Controller(BlockPos position, BlockState state) { super(TYPE, position, state); }
        @Override public void setChanged() {}
    }
    static final class Marker extends DirectionalBlock implements EnclosureController, DataPort {
        Marker() { super(BlockBehaviour.Properties.of()); registerDefaultState(defaultBlockState().setValue(FACING, Direction.WEST)); }
        @Override protected com.mojang.serialization.MapCodec<? extends DirectionalBlock> codec() { return simpleCodec(p -> new Marker()); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
        public Direction outward(BlockState state) { return state.getValue(FACING); }
        public boolean acceptsCable(BlockState state, Direction side) { return side == outward(state); }
    }
    static final class Host extends SmartBlockEntity {
        static final BlockEntityType<Host> TYPE = BlockEntityType.Builder.of(Host::new, WorldEnclosureViewTest.computer).build(null);
        ComputerBehaviour computer;
        Host(BlockPos pos, BlockState state) { super(TYPE, pos, state); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
        @Override public <T extends BlockEntityBehaviour> T getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType<T> type) {
            return type == ComputerBehaviour.TYPE ? (T)computer : super.getBehaviour(type);
        }
        @Override public void setChanged() {}
    }
}
