package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Real registered hosts/cable/capabilities plus public control-session response encoding, not external wire scheduling. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingCablePinDiscoveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void actualCableRevealsOnlyPublicHardwareAndDiscoveryNeverConsumesAWaitingRecord(GameTestHelper helper) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, true); var level = helper.getLevel();
        var controller = f.controller(); var pos = controller.getBlockPos();
        var exports = new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(
            "io", new XBusPublicPin(new XBusWire.Pin("B", "right", "x1"), true),
            "hidden", new XBusPublicPin(new XBusWire.Pin("A", "left", "x0"), false)));
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, controller, f.player(), exports);
        var owner = BiocomputingWorldGameTests.computer(helper, pos.west(3)); var source = BiocomputingWorldGameTests.host(owner);
        source.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET),
            List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(77));
        source.configureEndpoint(new EndpointConfiguration("caller", true));
        source.step(); source.setPaused(false); f.a().step(); f.b().step(); f.a().setPaused(false); f.b().setPaused(false);
        level.setBlockAndUpdate(pos.west(), CBBlocks.DATA_CABLE.get().defaultBlockState());
        level.setBlockAndUpdate(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState());
        f.player().moveTo(Vec3.atCenterOf(owner.getBlockPos().north()));
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var answer = session.apply(nonce, 1, source.programRevision(), ComputerControlSession.Action.DISCOVER_PEERS,
            () -> PeerDiscovery.discover(owner, f.player()), source, () -> PeerDiscovery.authorize(owner, f.player())).orElseThrow();
        helper.assertValueEqual(answer.code(), "ok", "Real physical discovery is accepted by the public control session");
        var response = ProgramStatusResponse.fromAnswer(1, nonce, 1, answer, source.hardware().limits().archive());
        helper.assertValueEqual(response.code(), "ok", "Full snapshot fits the existing response archive budget");
        var directory = (DataValue.RecordValue)ProgramEditorWire.decodeValue(response.status(), source.hardware().limits().archive());
        assertPublic(helper, directory);

        level.setBlockAndUpdate(pos.west(2), Blocks.AIR.defaultBlockState());
        helper.assertTrue(peers(PeerDiscovery.discover(owner, f.player())).fields().isEmpty(), "Physical break removes public pins from a new discovery");
        assertPublic(helper, directory); // Frozen old observation is not rewritten into authority.
        level.setBlockAndUpdate(pos.west(2), CBBlocks.DATA_CABLE.get().defaultBlockState());
        f.b().disableEndpoint();
        var unavailable = peers(PeerDiscovery.discover(owner, f.player()));
        var endpoint = endpoint(unavailable, "io");
        helper.assertTrue(!endpoint.fields().containsKey("xbus_pins"), "Revoked member grant never advertises usable hardware");
        f.b().configureEndpoint(new EndpointConfiguration("right", true));
        assertPublic(helper, PeerDiscovery.discover(owner, f.player()));
        level.setBlockAndUpdate(f.min(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(peers(PeerDiscovery.discover(owner, f.player())).fields().isEmpty(), "Broken shell cannot reveal public pins");
        level.setBlockAndUpdate(f.min(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState());
        assertPublic(helper, PeerDiscovery.discover(owner, f.player()));
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(source.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "Discovery does not wire a waiting sender");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "Reader has not received the discovered sender");
            helper.assertTrue(source.flowSnapshot().orElseThrow().receipts().isEmpty() && f.b().flowSnapshot().orElseThrow().receipts().isEmpty(), "No transfer receipts created");
            helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "Discovery never enters a buffered mailbox");
            helper.succeed();
        });
    }
    private static DataValue.RecordValue peers(DataValue.RecordValue directory) { return (DataValue.RecordValue)directory.field("peers"); }
    private static DataValue.RecordValue endpoint(DataValue.RecordValue peers, String name) {
        return (DataValue.RecordValue)((DataValue.ListValue)((DataValue.RecordValue)peers.field(name)).field("endpoints")).values().getFirst();
    }
    private static void assertPublic(GameTestHelper helper, DataValue.RecordValue directory) {
        var peers = peers(directory);
        helper.assertValueEqual(peers.fields().keySet(), Set.of("io"), "Controller is a terminal: no private left/right grants or closed alias leak out");
        var endpoint = endpoint(peers, "io");
        helper.assertValueEqual(endpoint.fields().keySet(), Set.of("position", "xbus_pins"), "No payload, member identity, program or RAM escapes discovery");
        helper.assertValueEqual(endpoint.field("xbus_pins"), new DataValue.ListValue(List.of(new DataValue.TextValue("x1"))), "Exactly the explicitly exported hardware pin");
    }
}
