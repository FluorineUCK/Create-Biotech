package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Actual hosts and save images only. The trusted pair call does not assert a physical wire or player UI exists. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingXBusHostGameTests {
    /** Group supplied explicitly by this fixture; not evidence of world multi-point wiring or automatic scheduling. */
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void actualThirdComputerCannotStealAnExchangeFromAnOlderBlockSave(GameTestHelper helper) {
        var level = helper.getLevel();
        var left = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 2, 2)));
        var right = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2)));
        var extra = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 2, 5)));
        var writer = BiocomputingWorldGameTests.host(left); var reader = BiocomputingWorldGameTests.host(right);
        var third = BiocomputingWorldGameTests.host(extra);
        var send = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet")));
        writer.installFlow(send, BiocomputingFlowFixture.inputs(12)); third.installFlow(send, BiocomputingFlowFixture.inputs(99));
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); third.configureEndpoint(new EndpointConfiguration("third", true));
        reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false); third.setPaused(false);
        helper.runAfterDelay(4, () -> {
            var oldRead = right.saveWithFullMetadata(level.registryAccess());
            helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.EXCHANGED, "Original pair consumes 12");
            var advanced = reload(level, left, left.saveWithFullMetadata(level.registryAccess()));
            var older = reload(level, right, oldRead);
            var a = advanced.xbusPort("writer", "x0").orElseThrow(); var b = older.xbusPort("reader", "x1").orElseThrow();
            var c = third.xbusPort("third", "x0").orElseThrow();
            var network = XBusAccess.network(List.of(third.xbusAccess("third", c).orElseThrow(),
                older.xbusAccess("reader", b).orElseThrow(), advanced.xbusAccess("writer", a).orElseThrow()));
            var beforeA = advanced.flowSnapshot(); var beforeB = older.flowSnapshot(); var beforeC = third.flowSnapshot();
            var ap = new XBusNetwork.Pin(a.computer(), a.pin()); var bp = new XBusNetwork.Pin(b.computer(), b.pin());
            var cp = new XBusNetwork.Pin(c.computer(), c.pin());
            helper.assertValueEqual(network.exchange(cp, bp), XBusExchange.Status.RECOVERY_REQUIRED, "No third-party consumption before recovery");
            helper.assertValueEqual(advanced.flowSnapshot(), beforeA, "Inspection does not replay the advanced Computer");
            helper.assertValueEqual(older.flowSnapshot(), beforeB, "No premature read publication");
            helper.assertValueEqual(third.flowSnapshot(), beforeC, "Third writer retains its own independent continuation");
            helper.assertValueEqual(network.exchange(ap, bp), XBusExchange.Status.EXCHANGED, "Original pair repairs the older block image");
            helper.assertValueEqual(older.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Restores original 12, never competing 99");
            helper.assertValueEqual(advanced.flowSnapshot(), beforeA, "No replay on receipt owner");
            helper.assertValueEqual(third.flowSnapshot(), beforeC, "No accidental wake of competing writer");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void actualComputerPairRecoversEitherOlderSaveWithoutRepeatingTheCompletedSide(GameTestHelper helper) {
        var level = helper.getLevel(); var left = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 2, 2)));
        var right = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2)));
        var writer = BiocomputingWorldGameTests.host(left); var reader = BiocomputingWorldGameTests.host(right);
        writer.installFlow(BiocomputingFlowFixture.calculator(), BiocomputingFlowFixture.inputs(5));
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false);
        helper.runAfterDelay(20, () -> {
            var beforeLeft = left.saveWithFullMetadata(level.registryAccess()); var beforeRight = right.saveWithFullMetadata(level.registryAccess());
            helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.EXCHANGED, "Actual waiting hosts exchange");
            var afterLeft = left.saveWithFullMetadata(level.registryAccess()); var afterRight = right.saveWithFullMetadata(level.registryAccess());
            var completedLeft = writer.flowSnapshot(); var completedRight = reader.flowSnapshot();
            for (boolean preserveWriter : List.of(true, false)) {
                var a = reload(level, left, preserveWriter ? afterLeft : beforeLeft);
                var b = reload(level, right, preserveWriter ? beforeRight : afterRight);
                helper.assertValueEqual(exchange(a, b), XBusExchange.Status.EXCHANGED, "Repair either independently older BE image");
                helper.assertValueEqual(a.flowSnapshot(), completedLeft, "Writer preserves full logical continuation and receipts");
                helper.assertValueEqual(b.flowSnapshot(), completedRight, "Reader retains exactly the original consumed value");
                helper.assertValueEqual(exchange(a, b), XBusExchange.Status.NOT_READY, "A second retry does not advance a successor");
                helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Only the computed immutable record is transferred");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void actualReadableWakeDoesNotCompleteTheWriterOrConsumeTheRecord(GameTestHelper helper) {
        var writer = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 2, 2))));
        var reader = BiocomputingWorldGameTests.host(BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(5, 2, 2))));
        writer.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(12));
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wake", "x1"),
            new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false);
        helper.runAfterDelay(4, () -> {
            var before = writer.flowSnapshot();
            helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.READABLE, "slx only wakes");
            helper.assertValueEqual(writer.flowSnapshot(), before, "Writer is still blocked with original value");
            helper.assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty(), "Wake is not a consumption receipt");
            helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.NOT_READY, "Declaration of the later read is not readiness");
            helper.runAfterDelay(4, () -> {
                helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.EXCHANGED, "Own tick reaches the actual read");
                helper.assertValueEqual(reader.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Original record consumed once");
                helper.succeed();
            });
        });
    }
    private static XBusExchange.Status exchange(ComputerBehaviour writer, ComputerBehaviour reader) {
        return writer.exchangeXBus("writer", writer.xbusPort("writer", "x0").orElseThrow(), reader,
            "reader", reader.xbusPort("reader", "x1").orElseThrow());
    }
    private static ComputerBehaviour reload(ServerLevel level, ComputerBlockEntity block, CompoundTag saved) {
        var pos = block.getBlockPos(); var state = block.getBlockState(); level.removeBlockEntity(pos);
        var restored = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restored); return BiocomputingWorldGameTests.host(restored);
    }
}
