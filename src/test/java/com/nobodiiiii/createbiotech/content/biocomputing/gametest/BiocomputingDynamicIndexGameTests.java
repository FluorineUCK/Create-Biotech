package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Real public server menu actions and registered Computer; not rendered client or cross-JVM evidence. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingDynamicIndexGameTests {
    private record Session(ComputerBehaviour computer, FakePlayer player, UUID nonce) {
        void control(long intent, ComputerControlSession.Action action, DataValue.RecordValue inputs) {
            new ProgramControlRequest(2, nonce, intent, computer.programRevision(), action,
                inputs == null ? new byte[0] : ProgramEditorWire.encodeValue(inputs, computer.hardware().limits().archive())).handle(player);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void menuInstalledDynamicIndexResumesExactPausedPointAndRejectsWrappedIndexWithoutOverwritingRam(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var computer = BiocomputingWorldGameTests.host(block);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "dynamic-index")); player.moveTo(Vec3.atCenterOf(pos));
        var graph = new ComputationGraph(Map.of("items", new DataType.ListType(DataType.Scalar.INTEGER), "offset", DataType.Scalar.INTEGER), Map.of(
            "items", new ComputationGraph.Input("items"), "offset", new ComputationGraph.Input("offset"),
            "pick", new ComputationGraph.DynamicIndex("items", "offset")), Map.of("item", "pick"));
        var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of("chosen", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(99))),
            List.of(new ProgramDefinition.Write("chosen", "item"))), Map.of());
        var document = new ProgramDocument(archive, Map.of("root", Map.of("pick", new ProgramDocument.Position(43, -17))));
        var session = open(helper, block, player);
        new ProgramEditorRequest(2, session.nonce(), 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Actual server menu installs versioned dynamic node and layout");
        var items = new DataValue.ListValue(List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(19)));
        session.control(1, ComputerControlSession.Action.START_PAUSED, inputs(items, 1));
        session.control(2, ComputerControlSession.Action.STEP, null);
        session.control(3, ComputerControlSession.Action.STEP, null);
        var before = computer.snapshot().orElseThrow();
        helper.assertValueEqual(before.machine().workUnits(), 2L, "Only the two dependency nodes have run");
        helper.assertValueEqual(before.machine().status(), ComputationMachine.Status.RUNNING, "Index remains unfinished");
        helper.assertTrue(before.machine().paused(), "Menu single-step leaves event paused");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "chosen"), new DataValue.IntegerValue(99), "No partial RAM write");
        var restoredBlock = reload(helper, block); var restored = BiocomputingWorldGameTests.host(restoredBlock);
        helper.assertValueEqual(restored.document().orElseThrow(), document, "NBT reload keeps the public program document");
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Restore preserves the exact charged continuation");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(), before.machine(), "Paused real ticks do not recompute inputs or index");
            var fresh = open(helper, restoredBlock, player); fresh.control(1, ComputerControlSession.Action.RESUME, null);
            helper.runAfterDelay(8, () -> {
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.SUCCEEDED, "Actual ticks complete the same event");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "chosen"), new DataValue.IntegerValue(19), "Input index selects second item into own RAM");
                helper.assertValueEqual(items.values(), List.of(new DataValue.IntegerValue(7), new DataValue.IntegerValue(19)), "Frozen source remains unchanged");
                fresh.control(2, ComputerControlSession.Action.START_PAUSED, inputs(items, 1L << 32));
                for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
                    helper.assertTrue(i < 10, "Invalid index fails within the ordinary node budget");
                    fresh.control(3 + i, ComputerControlSession.Action.STEP, null);
                }
                var failed = restored.snapshot().orElseThrow();
                helper.assertValueEqual(failed.machine().diagnostic().orElseThrow().code(), ComputationException.Code.INDEX_OUT_OF_BOUNDS, "Huge input is not narrowed to zero");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "chosen"), new DataValue.IntegerValue(19), "Failed event cannot overwrite previous RAM");
                var failedReload = BiocomputingWorldGameTests.host(reload(helper, restoredBlock)); failedReload.document();
                helper.assertValueEqual(failedReload.snapshot().orElseThrow(), failed, "Failure and previous RAM also persist exactly");
                helper.succeed();
            });
        });
    }

    private static DataValue.RecordValue inputs(DataValue items, long offset) {
        return new DataValue.RecordValue(Map.of("items", items, "offset", new DataValue.IntegerValue(offset)));
    }
    private static Session open(GameTestHelper helper, ComputerBlockEntity block, FakePlayer player) {
        var provider = ComputerProgramMenu.provider(block); player.containerMenu = provider.createMenu(2, player.getInventory(), player);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        return new Session(BiocomputingWorldGameTests.host(block), player, nonce);
    }
    private static ComputerBlockEntity reload(GameTestHelper helper, ComputerBlockEntity block) {
        var level = helper.getLevel(); var pos = block.getBlockPos(); var state = block.getBlockState();
        var saved = block.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
        var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement); return replacement;
    }
}
