package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingListCompositionGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void registeredComputerResumesPausedListCompositionWithoutChangingFrozenSource(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var host = BiocomputingWorldGameTests.host(block);
        var empty = new DataValue.ListValue(List.of());
        var five = new DataValue.IntegerValue(5); var seven = new DataValue.IntegerValue(7);
        var source = new DataValue.ListValue(Collections.nCopies(64, five));
        var graph = new ComputationGraph(Map.of("items", new DataType.ListType(DataType.Scalar.INTEGER)), Map.of(
            "items", new ComputationGraph.Input("items"), "seven", new ComputationGraph.Constant(seven),
            "tail", new ComputationGraph.MakeList(List.of("seven")), "joined", new ComputationGraph.ConcatLists("items", "tail")),
            Map.of("result", "joined"));
        var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of("log", new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.INTEGER), empty)),
            List.of(new ProgramDefinition.Write("log", "result"))), Map.of());
        var limits = new ArchiveLimits(10000, 100000, 1000000);
        host.install(ProgramEditorWire.decode(ProgramEditorWire.encode(archive, limits), limits), ComputerRuntime.MemoryPolicy.RESET);
        host.begin(new DataValue.RecordValue(Map.of("items", source))); host.setPaused(true);
        for (int guard = 0; ; guard++) {
            helper.assertTrue(guard < 30, "Must reach actual composition progress");
            var machine = host.snapshot().orElseThrow().machine();
            helper.assertValueEqual(machine.status(), ComputationMachine.Status.RUNNING, "Event remains unfinished");
            if (machine.frames().stream().anyMatch(frame -> frame instanceof MachineCheckpoint.ListState list && list.index() == 2)) break;
            host.step();
        }
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(host, "log"), empty, "Partial result is not committed");
        var before = host.snapshot().orElseThrow(); var state = block.getBlockState(); var saved = block.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var replacement = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(replacement); var restored = BiocomputingWorldGameTests.host(replacement); restored.document();
        helper.assertValueEqual(restored.snapshot().orElseThrow(), before, "Real NBT reload is inert and exact");
        helper.runAfterDelay(8, () -> {
            helper.assertValueEqual(restored.snapshot().orElseThrow().machine(), before.machine(), "Paused real ticks do not consume work");
            restored.setPaused(false);
            helper.runAfterDelay(45, () -> {
                helper.assertValueEqual(restored.snapshot().orElseThrow().machine().status(), ComputationMachine.Status.SUCCEEDED, "Actual ticks complete the event");
                var expected = new ArrayList<DataValue>(Collections.nCopies(64, five)); expected.add(seven);
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "log"), new DataValue.ListValue(expected), "Constructed tail follows the original list");
                helper.assertValueEqual(source, new DataValue.ListValue(Collections.nCopies(64, five)), "Frozen source is unchanged");
                helper.succeed();
            });
        });
    }
}
