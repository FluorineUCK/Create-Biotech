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
public final class BiocomputingValueEqualityGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void menuValueEqualityResumesTypedComparisonAcrossSaveAndResourceFailureKeepsRam(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var computer = BiocomputingWorldGameTests.host(block);
        var hardware = computer.hardware();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "value_equals")); player.moveTo(Vec3.atCenterOf(pos));
        var empty = new DataValue.BooleanValue(false);
        var draft = new ProgramDraft(); draft.putInput("left", DataType.Scalar.ANY); draft.putInput("right", DataType.Scalar.ANY);
        draft.putCell("log", DataType.Scalar.BOOLEAN, empty);
        String input = draft.add(new ComputationGraph.Input("left"), 0, 0);
        String right = draft.add(new ComputationGraph.Input("right"), 0, 40);
        String same = draft.add(new ComputationGraph.ValueEquals("left", "right"), 43, -17);
        draft.connect(same, "left", input); draft.connect(same, "right", right);
        draft.output("result", same); draft.output("left", input); draft.output("right", right); draft.setWrite("log", "result");
        var document = new ProgramDocument(draft.snapshot(), Map.of("root", Map.of(same, new ProgramDocument.Position(43, -17))));
        var session = BiocomputingListEditGameTests.open(helper, block, player);
        new ProgramEditorRequest(2, session.nonce(), 1, computer.programRevision(), true, true,
            ProgramDocumentWire.encode(document, computer.hardware().limits().archive())).handle(player);
        helper.assertValueEqual(computer.document().orElseThrow(), document, "Public draft installation preserves VALUE_EQUALS and layout");
        String prefix = "\uD83D\uDE00".repeat(32);
        var text = new DataValue.TextValue(prefix);
        var named = new DataValue.RecordValue(Map.of("v", new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)))));
        var values = List.<DataValue>of(text, new DataValue.TextValue(new String(prefix)), new DataValue.IntegerValue(3), new DataValue.IntegerValue(1),
            new DataValue.IntegerValue(3), new DataValue.TextValue("1"), named, new DataValue.RecordValue(named.fields()));
        var source = new DataValue.ListValue(values);
        session.control(1, ComputerControlSession.Action.START_PAUSED, inputs(source));
        for (int i = 0; ; i++) {
            helper.assertTrue(i < 100, "Comparison exposes a saved character cursor");
            var machine = computer.snapshot().orElseThrow().machine();
            boolean compared = false;
            for (var frame : machine.frames()) if (frame instanceof MachineCheckpoint.ValueEqualityState equality) {
                var frames = ((DataValue.ListValue)equality.progress().field("frames")).values();
                if (!frames.isEmpty() && ((DataValue.IntegerValue)((DataValue.RecordValue)frames.getLast()).field("offset")).value() > 0) compared = true;
            }
            if (compared) break;
            session.control(2 + i, ComputerControlSession.Action.STEP, null);
        }
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(computer, "log"), empty, "Partial comparison cannot commit RAM");
        var before = computer.snapshot().orElseThrow(); var restoredBlock = BiocomputingListEditGameTests.reload(helper, block);
        var restored = BiocomputingWorldGameTests.host(restoredBlock); restored.document();
        helper.assertValueEqual(restored.hardware(), hardware, "Resident profession/level/profile and per-Computer limits remain unchanged");
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Actual BE reload preserves comparison cursor and arena");
        helper.runAfterDelay(4, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(), before.machine(), "Paused natural ticks do not advance");
            var fresh = BiocomputingListEditGameTests.open(helper, restoredBlock, player); fresh.control(1, ComputerControlSession.Action.RESUME, null);
            helper.runAfterDelay(100, () -> {
                var state = restored.snapshot().orElseThrow().machine();
                helper.assertValueEqual(state.status(), ComputationMachine.Status.SUCCEEDED, "Per-Computer ticks finish restored VALUE_EQUALS");
                var expected = new DataValue.BooleanValue(true);
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), expected, "Only the completed Boolean result commits RAM");
                helper.assertValueEqual(state.output().orElseThrow().field("left"), source, "Source output is unchanged");
                helper.assertValueEqual(state.output().orElseThrow().field("right"), new DataValue.ListValue(values), "Independent right source output is unchanged");
                helper.assertValueEqual(source, new DataValue.ListValue(values), "Frozen source is unchanged");
                // Six charged text occurrences: input record, two Input node values, and two original-value outputs.
                // /6 stays within the public 24KiB control wire; record/RAM/cursor overhead then exhausts own memory.
                int length = Math.toIntExact(restored.hardware().limits().hardware().memoryUnits() / 6);
                var large = new DataValue.ListValue(List.of(new DataValue.TextValue("a".repeat(length)), new DataValue.TextValue("b".repeat(length))));
                var failureInputs = new DataValue.RecordValue(Map.of("left", large.values().get(0), "right", large.values().get(1)));
                var requestBytes = ProgramEditorWire.encodeValue(failureInputs, restored.hardware().limits().archive());
                helper.assertTrue(requestBytes.length <= ProgramEditorWire.MAX_BYTES, "Memory failure input is legal on the unchanged public control wire");
                System.out.println("VALUE_EQUALS memory fixture: memory=" + restored.hardware().limits().hardware().memoryUnits()
                    + ", length=" + length + ", wireBytes=" + requestBytes.length + ", inputUnits=" + failureInputs.storageUnits());
                fresh.control(2, ComputerControlSession.Action.START_PAUSED, failureInputs);
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.RUNNING,
                    "Public control request was admitted before the machine's later output reservation fails");
                for (int i = 0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
                    helper.assertTrue(i < 60, "Input/output reservations fail inside this Computer's configured budget");
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
    private static DataValue.RecordValue inputs(DataValue source) { return new DataValue.RecordValue(Map.of("left", source, "right", source instanceof DataValue.ListValue list ? new DataValue.ListValue(list.values()) : source)); }
}
