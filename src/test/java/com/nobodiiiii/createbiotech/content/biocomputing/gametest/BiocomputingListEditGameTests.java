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
public final class BiocomputingListEditGameTests {
    record Session(ComputerBehaviour computer, FakePlayer player, UUID nonce) {
        void control(long intent, ComputerControlSession.Action action, DataValue.RecordValue inputs) {
            new ProgramControlRequest(2, nonce, intent, computer.programRevision(), action,
                inputs == null ? new byte[0] : ProgramEditorWire.encodeValue(inputs, computer.hardware().limits().archive())).handle(player);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuReplacementResumesAcrossRealSaveAndKeepsSource(GameTestHelper helper) { exercise(helper, ListOperation.REPLACE); }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuInsertionResumesAcrossRealSaveAndKeepsSource(GameTestHelper helper) { exercise(helper, ListOperation.INSERT); }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuRemovalResumesAcrossRealSaveAndKeepsSource(GameTestHelper helper) { exercise(helper, ListOperation.REMOVE); }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuSliceResumesAcrossRealSaveAndKeepsSource(GameTestHelper helper) { exercise(helper, ListOperation.SLICE); }

    private static void exercise(GameTestHelper helper, ListOperation operation) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var computer = BiocomputingWorldGameTests.host(block);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "list-edit")); player.moveTo(Vec3.atCenterOf(pos));
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>(); var bindings = new LinkedHashMap<String, String>();
        operation.inputs().keySet().forEach(name -> { nodes.put(name, new ComputationGraph.Input(name)); bindings.put(name, name); });
        nodes.put("edit", new ComputationGraph.ListEdit(operation, bindings));
        var graph = new ComputationGraph(operation.inputs(), nodes, Map.of("result", "edit", "original", "source"));
        var empty = new DataValue.ListValue(List.of());
        var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.ANY), empty)),
            List.of(new ProgramDefinition.Write("log", "result"))), Map.of());
        var document = new ProgramDocument(archive, Map.of("root", Map.of("edit", new ProgramDocument.Position(43, -17))));
        var session = open(helper, block, player);
        new ProgramEditorRequest(2, session.nonce(), 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Public menu installs new graph and layout");
        var values = java.util.stream.IntStream.range(0, 64).mapToObj(i -> (DataValue)new DataValue.IntegerValue(i)).toList();
        var source = new DataValue.ListValue(values);
        session.control(1, ComputerControlSession.Action.START_PAUSED, inputs(operation, source, 1));
        for (int i = 0; ; i++) {
            helper.assertTrue(i < 20, "Copy must expose a bounded continuation");
            var machine = computer.snapshot().orElseThrow().machine();
            if (machine.frames().stream().anyMatch(frame -> frame instanceof MachineCheckpoint.ListState list && list.index() == 2)) break;
            session.control(2 + i, ComputerControlSession.Action.STEP, null);
        }
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "log"), empty, "Partial copy cannot commit");
        var before = computer.snapshot().orElseThrow(); var restoredBlock = reload(helper, block);
        var restored = BiocomputingWorldGameTests.host(restoredBlock); restored.document();
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Real BE reload preserves exactly the paused copy");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(), before.machine(), "Paused natural ticks do not advance");
            var fresh = open(helper, restoredBlock, player); fresh.control(1, ComputerControlSession.Action.RESUME, null);
            helper.runAfterDelay(50, () -> {
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.SUCCEEDED, "Per-Computer hardware ticks complete");
                var expected = new ArrayList<>(values);
                switch (operation) {
                    case REPLACE -> expected.set(1, new DataValue.IntegerValue(99));
                    case INSERT -> expected.add(1, new DataValue.IntegerValue(99));
                    case REMOVE -> expected.remove(1);
                    case SLICE -> expected = new ArrayList<>(expected.subList(1, 63));
                }
                var result = new DataValue.ListValue(expected);
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), result, "Complete copy alone updates own RAM");
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().output().orElseThrow().field("original"), source, "Source output remains unchanged");
                helper.assertValueEqual(source, new DataValue.ListValue(values), "Frozen input unchanged");
                fresh.control(2, ComputerControlSession.Action.START_PAUSED, inputs(operation, source, 1L << 32));
                for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
                    helper.assertTrue(i < 20, "Bad bounds fail before starting copy"); fresh.control(3 + i, ComputerControlSession.Action.STEP, null);
                }
                var failed = restored.snapshot().orElseThrow();
                helper.assertValueEqual(failed.machine().diagnostic().orElseThrow().code(), ComputationException.Code.INDEX_OUT_OF_BOUNDS, "No integer narrowing");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), result, "Rejected edit keeps old RAM");
                var reloaded = BiocomputingWorldGameTests.host(reload(helper, restoredBlock)); reloaded.document();
                helper.assertValueEqual(reloaded.snapshot().orElseThrow(), failed, "Failure and previous result survive another real reload");
                helper.succeed();
            });
        });
    }
    private static DataValue.RecordValue inputs(ListOperation op, DataValue source, long index) {
        var values = new LinkedHashMap<String, DataValue>(); values.put("source", source);
        values.put(op == ListOperation.SLICE ? "start" : "index", new DataValue.IntegerValue(index));
        if (op == ListOperation.SLICE) values.put("end", new DataValue.IntegerValue(63));
        if (op.inputs().containsKey("value")) values.put("value", new DataValue.IntegerValue(99));
        return new DataValue.RecordValue(values);
    }

    static Session open(GameTestHelper helper, ComputerBlockEntity block, FakePlayer player) {
        var provider = ComputerProgramMenu.provider(block); player.containerMenu = provider.createMenu(2, player.getInventory(), player);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        UUID nonce; try { provider.writeOpeningData(buffer); nonce = buffer.readUUID(); } finally { buffer.release(); }
        return new Session(BiocomputingWorldGameTests.host(block), player, nonce);
    }
    static ComputerBlockEntity reload(GameTestHelper helper, ComputerBlockEntity block) {
        var level = helper.getLevel(); var pos = block.getBlockPos(); var state = block.getBlockState();
        var saved = block.saveWithFullMetadata(level.registryAccess()); level.removeBlockEntity(pos);
        var replacement = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement); return replacement;
    }
}
