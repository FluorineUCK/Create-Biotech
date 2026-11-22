package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ClusterControllerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.*;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlDraft;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Actual registered capability and menu persistence. Explicit trusted pairing is NOT external wire scheduling. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingPublicXBusPinGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void playerConfiguredPassivePinSurvivesOwnerReloadAndHandsOffBothDirectionsWithoutAnInbox(GameTestHelper helper) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, false); var packet = BiocomputingFlowFixture.PACKET;
        var a = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("return", "x0", "answer", packet)));
        var b = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", packet), new FlowProgram.Write("reply", "x1", "packet")));
        install(helper, f, "A", a, BiocomputingFlowFixture.inputs(12));
        install(helper, f, "B", b, new DataValue.RecordValue(Map.of()));
        f.player().moveTo(net.minecraft.world.phys.Vec3.atCenterOf(f.controller().getBlockPos().west()));
        var config = exported(f); BiocomputingClusterXBusGameTests.configureThroughMenu(helper, f.controller(), f.player(), config);
        f.a().step(); f.b().step(); f.a().setPaused(false); f.b().setPaused(false);
        var level = helper.getLevel(); var pos = f.controller().getBlockPos();
        var old = level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST);
        helper.assertTrue(old == f.controller(), "Registered outward capability is the actual controller");
        helper.assertTrue(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.EAST) == null, "Controller does not bridge its inward face");
        helper.assertValueEqual(old.xbusPins("io"), List.of("x1"), "Discovery exposes only the explicitly mapped pin");
        var expected = old.xbusPort("io", "x1").orElseThrow();
        var oldAccess = old.xbusAccess("io", expected).orElseThrow();
        var left = f.a().xbusAccess("left", f.a().xbusPort("left", "x0").orElseThrow()).orElseThrow();
        var saved = f.controller().saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
        var restored = (ClusterControllerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess()));
        level.setBlockEntity(restored);
        helper.assertValueEqual(left.exchange(oldAccess), XBusExchange.Status.UNAVAILABLE, "Detached controller references cannot still grant access");
        helper.assertValueEqual(restored.routingState().orElseThrow().configuration(), config, "Actual controller archive preserves public pin table");
        var current = level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST);
        helper.assertTrue(current == restored, "Actual capability resolves the restored owner");
        helper.assertValueEqual(f.a().exchangeXBus("left", f.a().xbusPort("left", "x0").orElseThrow(), current, "io", expected),
            XBusExchange.Status.EXCHANGED, "Explicit trusted pairing hands the original packet directly to B");
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_READ, "A reaches its real reply read");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.WAITING_WRITE, "No automatic external return wire was invented");
            var read = f.a().xbusAccess("left", f.a().xbusPort("left", "x0").orElseThrow()).orElseThrow();
            var write = current.xbusAccess("io", current.xbusPort("io", "x1").orElseThrow()).orElseThrow();
            var draft = new ClusterControlDraft(config); draft.putPublicPin("io", "io", new XBusPublicPin(config.publicPins().get("io").target(), false));
            BiocomputingClusterXBusGameTests.configureThroughMenu(helper, restored, f.player(), draft.configuration());
            helper.assertValueEqual(read.exchange(write), XBusExchange.Status.CLOSED, "Saved revocation defeats already captured handles");
            helper.assertValueEqual(f.b().flowSnapshot().orElseThrow().receipts().size(), 1, "Revocation cannot consume the pending reply");
            BiocomputingClusterXBusGameTests.configureThroughMenu(helper, restored, f.player(), config);
            helper.assertValueEqual(read.exchange(write), XBusExchange.Status.STALE, "Reopening does not revive an old mapping revision");
            helper.assertValueEqual(current.exchangeXBus("io", current.xbusPort("io", "x1").orElseThrow(), f.a(), "left", f.a().xbusPort("left", "x0").orElseThrow()),
                XBusExchange.Status.EXCHANGED, "Fresh explicit pairing uses the same mapped pin in reverse");
            helper.assertValueEqual(f.a().flowSnapshot().orElseThrow().state().locals().field("answer"), BiocomputingFlowFixture.inputs(12).field("packet"), "Original caller receives its named record");
            helper.assertTrue(restored.routingState().orElseThrow().mailbox().queued().isEmpty(), "Public pin never enters legacy buffered router");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void replacementMemberCannotInheritAPublicPinGrantFromItsCoordinates(GameTestHelper helper) {
        var f = BiocomputingClusterXBusGameTests.fixture(helper, false, true); var config = exported(f);
        BiocomputingClusterXBusGameTests.configureThroughMenu(helper, f.controller(), f.player(), config);
        f.a().step(); f.b().step(); f.a().setPaused(false); f.b().setPaused(false);
        var expected = f.controller().xbusPort("io", "x1").orElseThrow();
        var access = f.controller().xbusAccess("io", expected).orElseThrow();
        var left = f.a().xbusAccess("left", f.a().xbusPort("left", "x0").orElseThrow()).orElseThrow();
        var level = helper.getLevel(); var pos = config.members().get("B").position();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        var replacement = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, pos));
        level.setBlock(pos.below(), CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
        replacement.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        replacement.configureEndpoint(new EndpointConfiguration("right", true)); replacement.step(); replacement.setPaused(false);
        var before = replacement.flowSnapshot();
        helper.assertValueEqual(left.exchange(access), XBusExchange.Status.STALE, "Mapping keeps the configured member identity, not just coordinates");
        helper.assertValueEqual(f.controller().publicPinDiagnostics().get("io"), "identity_changed", "Player management view names the actual failure");
        helper.assertValueEqual(replacement.flowSnapshot(), before, "Replacement receives nothing");
        helper.assertTrue(f.a().flowSnapshot().orElseThrow().receipts().isEmpty(), "Pending writer remains unconsumed");
        helper.succeed();
    }

    private static ClusterConfiguration exported(BiocomputingClusterXBusGameTests.Fixture f) {
        return new ClusterConfiguration(f.config().members(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of("io", new XBusPublicPin(new XBusWire.Pin("B", "right", "x1"), true)));
    }
    private static void install(GameTestHelper helper, BiocomputingClusterXBusGameTests.Fixture f, String name, FlowProgram program, DataValue.RecordValue initial) {
        BiocomputingClusterXBusGameTests.installThroughMenu(helper, f, name, new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
            Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial))));
    }
}
