package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Explicit protocol fixture in actual blocks, not player-created loops or a cross-JVM test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingFlowOperationGameTests {
    private static final long OPERATION = (1L << 32) + 17;
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualBlockReloadPreservesIndependentReceiptAndRetirement(GameTestHelper helper) {
        var level = helper.getLevel(); var leftPos = helper.absolutePos(new BlockPos(2, 2, 2)); var rightPos = helper.absolutePos(new BlockPos(5, 2, 2));
        var left = BiocomputingWorldGameTests.computer(helper, leftPos); var right = BiocomputingWorldGameTests.computer(helper, rightPos);
        var writer = BiocomputingWorldGameTests.host(left); var reader = BiocomputingWorldGameTests.host(right);
        writer.installFlow(new FlowProgram(Map.of("packet", BiocomputingFlowFixture.PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))), BiocomputingFlowFixture.inputs(12));
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET))), new DataValue.RecordValue(Map.of()));
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false);
        helper.runAfterDelay(4, () -> {
            var a = reload(level, leftPos, operationFixture(left, writer, OPERATION));
            var oldRead = operationFixture(right, reader, OPERATION + 7);
            var b = reload(level, rightPos, oldRead);
            helper.assertValueEqual(exchange(a, b), XBusExchange.Status.EXCHANGED, "Actual endpoint handoff");
            var receipt = a.flowSnapshot().orElseThrow().receipts().getFirst();
            helper.assertValueEqual(receipt.writer().operation(), OPERATION, "Writer operation is not truncated or replaced by cursor");
            helper.assertValueEqual(receipt.reader().operation(), OPERATION + 7, "Reader operation is independent");
            var complete = level.getBlockEntity(leftPos).saveWithFullMetadata(level.registryAccess());
            var advanced = reload(level, leftPos, complete); var older = reload(level, rightPos, oldRead);
            helper.assertTrue(advanced.xbusPort("writer", "x0").isPresent(), "Cold owner is validated before observing its snapshot");
            var before = advanced.flowSnapshot();
            helper.assertValueEqual(exchange(advanced, older), XBusExchange.Status.EXCHANGED, "Only the old matching reader is repaired");
            helper.assertTrue(advanced.flowSnapshot().equals(before), "Completed writer does not replay");
            helper.assertValueEqual(older.flowSnapshot().orElseThrow().state().locals(), BiocomputingFlowFixture.inputs(12), "Named packet restored");
            advanced.reset();
            var reset = reload(level, leftPos, level.getBlockEntity(leftPos).saveWithFullMetadata(level.registryAccess()));
            var stale = reload(level, rightPos, oldRead); reset.setPaused(false);
            helper.runAfterDelay(4, () -> {
                helper.assertValueEqual(exchange(reset, stale), XBusExchange.Status.RETIRED, "Wide retired operation survives actual block reload");
                helper.succeed();
            });
        });
    }
    private static CompoundTag operationFixture(ComputerBlockEntity block, ComputerBehaviour host, long operation) {
        var saved = host.flowSnapshot().orElseThrow(); var s = saved.state();
        var state = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(), s.paused(),
            s.expression(), s.diagnostic(), s.sleepTicks(), s.signals(), operation);
        var tag = block.saveWithFullMetadata(block.getLevel().registryAccess());
        tag.put(ComputerBehaviour.ROOT, FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), state,
            saved.receipts(), saved.control()), new ArchiveLimits(100000, 1000000, 1000000)));
        return tag;
    }
    private static XBusExchange.Status exchange(ComputerBehaviour writer, ComputerBehaviour reader) {
        return writer.exchangeXBus("writer", writer.xbusPort("writer", "x0").orElseThrow(), reader,
            "reader", reader.xbusPort("reader", "x1").orElseThrow());
    }
    private static ComputerBehaviour reload(ServerLevel level, BlockPos pos, CompoundTag saved) {
        var state = level.getBlockState(pos); level.removeBlockEntity(pos);
        var block = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(block); return BiocomputingWorldGameTests.host(block);
    }
}
