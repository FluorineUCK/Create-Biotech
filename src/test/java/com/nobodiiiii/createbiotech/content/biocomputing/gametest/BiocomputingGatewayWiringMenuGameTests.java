package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusWiringSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Actual menu packets, physical cables and captured server responses, not a rendered client. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingGatewayWiringMenuGameTests {
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void explicitActivationPersistsWithoutExecutingAndCanBeDisabledAfterTheCableBreaks(GameTestHelper helper) {
        var g = new Gateway(helper); var f = g.fixture; var controller = f.cluster().controller();
        var caller = f.caller().flowSnapshot(); var member = f.cluster().b().flowSnapshot();
        var saved = WiringStatusView.decode(g.value(g.add(g.read("io"), "wire")));
        helper.assertTrue(!saved.entries().getFirst().enabled(), "Saving a declaration does not enable it");
        var enabled = WiringStatusView.decode(g.value(g.ask(ClusterControlSession.Action.SET_WIRING_ENABLED,
            new WiringControlData.SetEnabled(saved.view(), "wire", true).encode())));
        helper.assertTrue(enabled.entries().getFirst().enabled(), "Explicit menu action enables the selected saved declaration");
        var store = XBusWiringSavedData.get(helper.getLevel());
        var restored = XBusWiringSavedData.load(store.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.assertValueEqual(restored.view(controller.controllerId()), store.view(controller.controllerId()), "Configuration round trip retains activation and fixed bindings");
        helper.getLevel().setBlockAndUpdate(controller.getBlockPos().west(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        var disabled = WiringStatusView.decode(g.value(g.ask(ClusterControlSession.Action.SET_WIRING_ENABLED,
            new WiringControlData.SetEnabled(enabled.view(), "wire", false).encode())));
        helper.assertTrue(!disabled.entries().getFirst().enabled(), "Broken peer does not prevent explicit disable");
        var rejected = g.ask(ClusterControlSession.Action.SET_WIRING_ENABLED,
            new WiringControlData.SetEnabled(disabled.view(), "wire", true).encode());
        helper.assertValueEqual(rejected.detail(), "unreachable", "Re-enable rechecks the actual cable route");
        helper.assertTrue(!store.view(controller.controllerId()).entries().getFirst().enabled(), "Failed activation remains disabled");
        helper.assertValueEqual(f.caller().flowSnapshot(), caller, "Configuration does not execute the caller");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), member, "Configuration does not execute the member");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void removedPublicNameRemainsListedAndCanBeCleanedWithoutRegrantingItsPin(GameTestHelper helper) {
        var g = new Gateway(helper); var f = g.fixture; var controller = f.cluster().controller();
        var caller = f.caller().flowSnapshot(); var member = f.cluster().b().flowSnapshot();
        var sources = ClusterWiringSources.decode(g.value(g.ask(ClusterControlSession.Action.LIST_WIRING, null)));
        helper.assertValueEqual(sources.position(), controller.getBlockPos(), "Menu publishes actual controller source position");
        helper.assertValueEqual(sources.endpoints(), Map.of("io", true), "Only configured public names are listed initially");
        var view = g.read("io"); g.add(view, "retained");
        var configuration = controller.routingState().orElseThrow().configuration();
        var without = new ClusterConfiguration(configuration.members(), configuration.ports(), configuration.connections(), configuration.wires(), configuration.signals(), Map.of());
        var configured = g.send(ClusterControlSession.Action.CONFIGURE, controller.configurationRevision(), ClusterControlWire.encodeConfiguration(without, g.archive));
        helper.assertValueEqual(configured.code(), "configured", "Player explicitly removes the public mapping");
        sources = ClusterWiringSources.decode(g.value(g.ask(ClusterControlSession.Action.LIST_WIRING, null)));
        helper.assertValueEqual(sources.endpoints(), Map.of("io", false), "Orphaned scope remains visible for explicit cleanup");
        var orphan = g.read("io");
        helper.assertTrue(orphan.localPins().isEmpty(), "Listing an old name never reopens its member pin");
        helper.assertValueEqual(orphan.discovery(), "public_pin_missing", "Unavailable public source is distinguished from an empty ready source");
        helper.assertValueEqual(orphan.entries().getFirst().name(), "retained", "Saved metadata remains inspectable");
        var removed = g.ask(ClusterControlSession.Action.REMOVE_WIRING, new WiringControlData.Remove(orphan.view(), "retained").encode());
        helper.assertValueEqual(removed.code(), "ok", "Explicit removal does not require restoring its public pin");
        sources = ClusterWiringSources.decode(g.value(g.ask(ClusterControlSession.Action.LIST_WIRING, null)));
        helper.assertTrue(sources.endpoints().isEmpty(), "Cleaned orphan scope disappears instead of becoming a new port");
        helper.assertValueEqual(f.caller().flowSnapshot(), caller, "No caller execution");
        helper.assertValueEqual(f.cluster().b().flowSnapshot(), member, "No member execution"); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerMenuRejectsForgedInputsOldRevisionAndViewsAbandonedByNavigation(GameTestHelper helper) {
        var g = new Gateway(helper); var c = g.fixture.cluster().controller(); var store = XBusWiringSavedData.get(helper.getLevel());
        var view = g.read("io"); var add = g.addInputs(view, "rejected");
        var forged = new LinkedHashMap<>(add.fields()); forged.put("endpoint", new DataValue.TextValue("private"));
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, new DataValue.RecordValue(forged)).code(), "invalid", "Extra authority fields are rejected");
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, add).detail(), "stale", "Invalid attempt consumes its old view");
        view = g.read("io"); add = g.addInputs(view, "rejected");
        helper.assertValueEqual(g.send(ClusterControlSession.Action.ADD_WIRING, c.configurationRevision() + 1, ProgramEditorWire.encodeValue(add, g.archive)).code(), "stale", "Controller revision fences mutation");
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, add).detail(), "stale", "Revision rejection cannot revive an old view");
        view = g.read("io"); add = g.addInputs(view, "rejected");
        g.ask(ClusterControlSession.Action.LIST_WIRING, null);
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, add).detail(), "stale", "Returning to the scope list revokes the chosen pins");
        view = g.read("io"); add = g.addInputs(view, "rejected");
        g.ask(ClusterControlSession.Action.REFRESH, null);
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, add).detail(), "stale", "Returning to configuration revokes the chosen pins");
        helper.assertTrue(store.view(c.controllerId()).entries().isEmpty(), "Rejected menu mutations never publish");
        view = g.read("io"); var saved = g.add(view, "accepted");
        int responses = g.replies.size();
        new ClusterControlRequest(11, g.nonce, saved.intent(), c.configurationRevision(), ClusterControlSession.Action.ADD_WIRING,
            ProgramEditorWire.encodeValue(g.addInputs(view, "replay"), g.archive)).handle(g.fixture.cluster().player());
        helper.assertValueEqual(g.replies.size(), responses, "Repeated intent is ignored rather than replayed");
        var fresh = g.read("io");
        var duplicate = g.ask(ClusterControlSession.Action.ADD_WIRING, g.addInputs(fresh, "accepted"));
        helper.assertValueEqual(duplicate.detail(), "duplicate_name", "A name conflict is not misreported as an invalid pin selection");
        helper.assertValueEqual(store.view(c.controllerId()).entries().size(), 1, "Only the explicitly accepted declaration exists"); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void replyCapacityIsCheckedBeforeControllerMenuPublishesADeclaration(GameTestHelper helper) {
        var endpoints = CBConfigs.SERVER.biocomputing.communicationEndpoints; int previous = endpoints.get();
        try { endpoints.set(128); verifyReplyCapacity(helper); }
        finally { endpoints.set(previous); }
    }
    private static void verifyReplyCapacity(GameTestHelper helper) {
        var g = new Gateway(helper); var c = g.fixture.cluster().controller();
        // Keep all physical host archives valid. A request-only budget would disable the
        // controller/Computer before reaching the response boundary we intend to test.
        int nodes = com.nobodiiiii.createbiotech.content.biocomputing.persistence.ClusterNbtCodec.encode(c.routingState().orElseThrow(), g.archive).getList("Values", 10).size();
        for (var computer : List.of(g.fixture.caller(), g.fixture.cluster().a(), g.fixture.cluster().b()))
            nodes = Math.max(nodes, com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.encode(computer.flowSnapshot().orElseThrow(), g.archive)
                .getCompound("Data").getList("Values", 10).size());
        var replyLimit = new ArchiveLimits(nodes, g.archive.maxStringChars(), g.archive.maxValueUnits());
        WiringStatusView view; int seeded = 0;
        while (true) {
            view = g.read("io");
            try { ProgramEditorWire.encodeValue(g.value(g.replies.getLast()), replyLimit); }
            catch (IllegalArgumentException largerThanHosts) { break; }
            g.add(view, "seed_" + seeded++);
            helper.assertTrue(seeded < CBConfigs.SERVER.biocomputing.communication(true).maxEndpoints() / 2, "Fixture must reach reply limit before declaration capacity");
        }
        var inputs = g.addInputs(view, "capacity");
        ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(inputs, g.archive), replyLimit);
        var store = XBusWiringSavedData.get(helper.getLevel()); var before = store.view(c.controllerId());
        var setting = CBConfigs.SERVER.biocomputing.archiveNodes; int previous = setting.get();
        try {
            setting.set(nodes);
            helper.assertTrue(c.routingState().isPresent() && g.fixture.caller().communicationIdentity().isPresent()
                && g.fixture.cluster().b().communicationIdentity().isPresent(), "Physical participants still valid under the reply budget");
            var reply = g.ask(ClusterControlSession.Action.ADD_WIRING, inputs);
            helper.assertValueEqual(reply.code(), "too_large", "Whole wiring reply, not just the accepted request, must fit");
            helper.assertTrue(reply.state().length == 0, "No truncated success reply");
        } finally { setting.set(previous); }
        helper.assertValueEqual(store.view(c.controllerId()), before, "Reply preflight failure publishes no new declaration");
        helper.assertValueEqual(g.ask(ClusterControlSession.Action.ADD_WIRING, inputs).detail(), "stale", "Restoring capacity does not revive an old selection");
        g.add(g.read("io"), "fresh"); helper.succeed();
    }

    private static final class Gateway {
        final GameTestHelper helper;
        final BiocomputingCableXBusRouteGameTests.Fixture fixture;
        final List<ClusterControlResponse> replies = new ArrayList<>();
        final UUID nonce;
        final ArchiveLimits archive = CBConfigs.SERVER.biocomputing.cluster(true).archive();
        long intent;
        Gateway(GameTestHelper helper) {
            this.helper = helper; fixture = BiocomputingCableXBusRouteGameTests.fixture(helper);
            var player = fixture.cluster().player(); var pos = fixture.cluster().controller().getBlockPos();
            player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom && custom.payload() instanceof ClusterControlResponse reply) {
                        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
                        try { reply.write(buffer); replies.add(new ClusterControlResponse(buffer)); } finally { buffer.release(); }
                    }
                }
            };
            var provider = ClusterControllerMenu.provider(fixture.cluster().controller());
            player.containerMenu = provider.createMenu(11, player.getInventory(), player);
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        }
        ClusterControlResponse ask(ClusterControlSession.Action action, DataValue.RecordValue inputs) {
            return send(action, fixture.cluster().controller().configurationRevision(), inputs == null ? new byte[0] : ProgramEditorWire.encodeValue(inputs, archive));
        }
        ClusterControlResponse send(ClusterControlSession.Action action, long revision, byte[] inputs) {
            var request = new ClusterControlRequest(11, nonce, ++intent, revision, action, inputs);
            int before = replies.size();
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            try { request.write(buffer); new ClusterControlRequest(buffer).handle(fixture.cluster().player()); } finally { buffer.release(); }
            helper.assertValueEqual(replies.size(), before + 1, "Actual menu emits one response");
            helper.assertValueEqual(replies.getLast().intent(), intent, "Response correlates to this request"); return replies.getLast();
        }
        DataValue.RecordValue value(ClusterControlResponse reply) {
            helper.assertValueEqual(reply.code(), "ok", "Successful dedicated wiring response");
            return (DataValue.RecordValue) ProgramEditorWire.decodeValue(reply.state(), reply.archiveLimits());
        }
        WiringStatusView read(String endpoint) {
            return WiringStatusView.decode(value(ask(ClusterControlSession.Action.READ_WIRING,
                new DataValue.RecordValue(Map.of("endpoint", new DataValue.TextValue(endpoint))))));
        }
        DataValue.RecordValue addInputs(WiringStatusView view, String name) {
            int peer = -1;
            for (int i = 0; i < view.peers().size(); i++) if (view.peers().get(i).endpoint().equals("caller") && view.peers().get(i).pin().equals("x0")) peer = i;
            helper.assertTrue(peer >= 0, "Actual external caller pin discovered");
            return new WiringControlData.Add(view.view(), name, "x1", peer).encode();
        }
        ClusterControlResponse add(WiringStatusView view, String name) {
            var reply = ask(ClusterControlSession.Action.ADD_WIRING, addInputs(view, name));
            helper.assertValueEqual(reply.code(), "ok", "Actual controller menu accepts explicitly selected wiring"); return reply;
        }
    }
}
