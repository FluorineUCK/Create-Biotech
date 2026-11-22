package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

/** Public draft/menu, actual registered Computer, and actual BE save/load continuation. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingDistinctGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuDistinctResumesTypedComparisonAcrossSaveAndResourceFailureKeepsRam(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var computer = BiocomputingWorldGameTests.host(block);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "distinct")); player.moveTo(Vec3.atCenterOf(pos));
        var empty = new DataValue.ListValue(List.of());
        var draft = new ProgramDraft(); draft.putInput("source", new DataType.ListType(DataType.Scalar.ANY));
        draft.putCell("log", new DataType.ListType(DataType.Scalar.ANY), empty);
        String input = draft.add(new ComputationGraph.Input("source"), 0, 0);
        String unique = draft.add(new ComputationGraph.Distinct("source"), 43, -17);
        draft.connect(unique, "source", input); draft.output("result", unique); draft.output("original", input); draft.setWrite("log", "result");
        var document = new ProgramDocument(draft.snapshot(), Map.of("root", Map.of(unique, new ProgramDocument.Position(43, -17))));
        var session = BiocomputingListEditGameTests.open(helper, block, player);
        new ProgramEditorRequest(2, session.nonce(), 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Public draft installation preserves DISTINCT and layout");
        String prefix = "\uD83D\uDE00".repeat(32);
        var text = new DataValue.TextValue(prefix);
        var named = new DataValue.RecordValue(Map.of("v", new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)))));
        var values = List.<DataValue>of(text, new DataValue.TextValue(new String(prefix)), new DataValue.IntegerValue(3), new DataValue.IntegerValue(1),
            new DataValue.IntegerValue(3), new DataValue.TextValue("1"), named, new DataValue.RecordValue(named.fields()));
        var source = new DataValue.ListValue(values);
        session.control(1, ComputerControlSession.Action.START_PAUSED, inputs(source));
        for (int i = 0; ; i++) {
            helper.assertTrue(i < 40, "Comparison exposes a saved character cursor");
            var machine = computer.snapshot().orElseThrow().machine();
            boolean compared = false;
            for (var frame : machine.frames()) if (frame instanceof MachineCheckpoint.DistinctState distinct
                && distinct.progress().field("comparison") instanceof DataValue.RecordValue comparison) {
                var frames = ((DataValue.ListValue)comparison.field("frames")).values();
                if (!frames.isEmpty() && ((DataValue.IntegerValue)((DataValue.RecordValue)frames.getLast()).field("offset")).value() > 0) compared = true;
            }
            if (compared) break;
            session.control(2 + i, ComputerControlSession.Action.STEP, null);
        }
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "log"), empty, "Partial retention cannot commit RAM");
        var before = computer.snapshot().orElseThrow(); var restoredBlock = BiocomputingListEditGameTests.reload(helper, block);
        var restored = BiocomputingWorldGameTests.host(restoredBlock); restored.document();
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Actual BE reload preserves comparison cursor and arena");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(), before.machine(), "Paused natural ticks do not advance");
            var fresh = BiocomputingListEditGameTests.open(helper, restoredBlock, player); fresh.control(1, ComputerControlSession.Action.RESUME, null);
            helper.runAfterDelay(70, () -> {
                var state = restored.snapshot().orElseThrow().machine();
                helper.assertValueEqual(state.status(), ComputationMachine.Status.SUCCEEDED, "Per-Computer ticks finish restored DISTINCT");
                var expected = new DataValue.ListValue(List.of(text, new DataValue.IntegerValue(3), new DataValue.IntegerValue(1), new DataValue.TextValue("1"), named));
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), expected, "Only stable typed result commits RAM");
                helper.assertValueEqual(state.output().orElseThrow().field("original"), source, "Source output is unchanged");
                helper.assertValueEqual(source, new DataValue.ListValue(values), "Frozen source is unchanged");
                int length = Math.toIntExact(restored.hardware().limits().hardware().memoryUnits() / 6);
                var large = new DataValue.ListValue(List.of(new DataValue.TextValue("a".repeat(length)), new DataValue.TextValue("b".repeat(length))));
                fresh.control(2, ComputerControlSession.Action.START_PAUSED, inputs(large));
                for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
                    helper.assertTrue(i < 60, "Copy/output reservations fail inside this Computer's configured budget");
                    fresh.control(3 + i, ComputerControlSession.Action.STEP, null);
                }
                var failed = restored.snapshot().orElseThrow();
                helper.assertValueEqual(failed.machine().status(), ComputationMachine.Status.FAILED, "Resource failure is explicit");
                helper.assertValueEqual(failed.machine().diagnostic().orElseThrow().code(), ComputationException.Code.MEMORY_LIMIT, "Uses own memory budget");
                helper.assertTrue(failed.machine().output().isEmpty(), "Partial results are not published");
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), expected, "Resource failure retains previous RAM");
                var reloaded = BiocomputingWorldGameTests.host(BiocomputingListEditGameTests.reload(helper, restoredBlock)); reloaded.document();
                helper.assertValueEqual(reloaded.snapshot().orElseThrow(), failed, "Failure and unchanged RAM survive another actual reload");
                helper.succeed();
            });
        });
    }
    private static DataValue.RecordValue inputs(DataValue source) { return new DataValue.RecordValue(Map.of("source", source)); }
}
