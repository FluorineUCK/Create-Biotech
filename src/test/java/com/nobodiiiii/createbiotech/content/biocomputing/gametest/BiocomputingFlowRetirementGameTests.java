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

/** Trusted host pairing, not a player wiring/GUI or independent-JVM acceptance test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowRetirementGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void actualResetRetiresOldReadsAndEventModeCannotEraseTheBoundary(GameTestHelper helper) {
        var level = helper.getLevel(); var leftPos = helper.absolutePos(new BlockPos(2, 2, 2)); var rightPos = helper.absolutePos(new BlockPos(5, 2, 2));
        var left = BiocomputingWorldGameTests.computer(helper, leftPos); var right = BiocomputingWorldGameTests.computer(helper, rightPos);
        var writer = BiocomputingWorldGameTests.host(left); var reader = BiocomputingWorldGameTests.host(right);
        var sender = new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet")));
        writer.installFlow(sender, BiocomputingFlowFixture.inputs(12));
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false);
        helper.runAfterDelay(4, () -> {
            var oldReader = right.saveWithFullMetadata(level.registryAccess());
            helper.assertValueEqual(exchange(writer, reader), XBusExchange.Status.EXCHANGED, "Initial real-host handoff");
            var peerBefore = reader.flowSnapshot(); writer.reset();
            helper.assertValueEqual(reader.flowSnapshot(), peerBefore, "Reset must not reset another Computer");
            helper.assertTrue(writer.flowSnapshot().orElseThrow().receipts().isEmpty(), "Consumed record copies released on explicit reset");
            helper.assertTrue(writer.communicationQuiescent(), "Reset owner can be explicitly reconfigured again");
            var a = reload(level, leftPos, left.saveWithFullMetadata(level.registryAccess())); var b = reload(level, rightPos, oldReader);
            a.setPaused(false);
            helper.runAfterDelay(4, () -> {
                helper.assertValueEqual(exchange(a, b), XBusExchange.Status.RETIRED, "Old read image cannot consume a new-generation value");
                b.reset(); b.setPaused(false);
                helper.runAfterDelay(4, () -> {
                    var newRead = level.getBlockEntity(rightPos).saveWithFullMetadata(level.registryAccess());
                    helper.assertValueEqual(exchange(a, b), XBusExchange.Status.EXCHANGED, "Explicit new flow can exchange normally");
                    helper.assertValueEqual(b.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "New operation gets the record");
                    a.reset(); var control = a.flowSnapshot().orElseThrow().control();
                    a.install(BiocomputingWorldGameTests.counter(), ComputerRuntime.MemoryPolicy.RESET);
                    var modeSave = level.getBlockEntity(leftPos).saveWithFullMetadata(level.registryAccess());
                    var restored = reload(level, leftPos, modeSave); var older = reload(level, rightPos, newRead);
                    restored.installFlow(sender, BiocomputingFlowFixture.inputs(12));
                    helper.assertValueEqual(restored.flowSnapshot().orElseThrow().control().peers(), control.peers(), "Event-mode BE reload retains peer frontier");
                    helper.assertValueEqual(restored.flowSnapshot().orElseThrow().control().generation(), control.generation() + 1, "Mode switch advances, never reuses generation");
                    restored.setPaused(false);
                    helper.runAfterDelay(4, () -> {
                        helper.assertValueEqual(exchange(restored, older), XBusExchange.Status.RETIRED, "Event mode is not a retirement bypass");
                        helper.succeed();
                    });
                });
            });
        });
    }
    private static XBusExchange.Status exchange(ComputerBehaviour writer, ComputerBehaviour reader) {
        return writer.exchangeXBus("writer", writer.xbusPort("writer", "x0").orElseThrow(), reader,
            "reader", reader.xbusPort("reader", "x1").orElseThrow());
    }
    private static ComputerBehaviour reload(ServerLevel level, BlockPos pos, CompoundTag saved) {
        var state = level.getBlockState(pos); level.removeBlockEntity(pos);
        var block = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(block); return BiocomputingWorldGameTests.host(block);
    }
}
