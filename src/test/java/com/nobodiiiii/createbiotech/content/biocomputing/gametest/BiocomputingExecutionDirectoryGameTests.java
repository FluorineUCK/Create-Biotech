package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.ComputerExecutionDirectory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Real Create initialization/invalidation and vanilla block ticks; no direct registry registration. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingExecutionDirectoryGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void physicalReplacementAndReloadRetireOnlyTheOldInstanceWithoutChangingProgramData(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(2, 1, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos); var old = block.getBehaviour(ComputerBehaviour.TYPE);
        sleeping(old); var before = old.flowSnapshot().orElseThrow();
        helper.runAfterDelay(3, () -> {
            var seen = ComputerExecutionDirectory.snapshot(level);
            helper.assertTrue(seen.loaded().contains(old) && seen.ticking().contains(old), "Actual block initialization and tick register the physical instance");
            helper.assertValueEqual(old.flowSnapshot().orElseThrow(), before, "Observing the directory does not run a paused program");
            var saved = block.saveWithFullMetadata(level.registryAccess()); var state = block.getBlockState();
            level.removeBlockEntity(pos);
            helper.assertTrue(!ComputerExecutionDirectory.snapshot(level).loaded().contains(old), "SmartBlockEntity invalidation unregisters immediately");
            var restored = (ComputerBlockEntity)Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
            level.setBlockEntity(restored); var replacement = restored.getBehaviour(ComputerBehaviour.TYPE);
            helper.assertTrue(!ComputerExecutionDirectory.snapshot(level).ticking().contains(replacement), "Loading NBT is not a tick witness");
            helper.runAfterDelay(3, () -> {
                var current = ComputerExecutionDirectory.snapshot(level);
                helper.assertTrue(current.loaded().contains(replacement) && current.ticking().contains(replacement), "Vanilla ticking registers the restored instance");
                helper.assertTrue(!current.loaded().contains(old), "No retained old authority at the same coordinates");
                old.unload();
                helper.assertTrue(ComputerExecutionDirectory.snapshot(level).loaded().contains(replacement), "Late invalidation of old instance cannot remove replacement");
                helper.assertValueEqual(replacement.flowSnapshot().orElseThrow(), before, "Program, locals, sleep and identity survive actual block serialization");
                var nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
                helper.assertTrue(!ComputerExecutionDirectory.snapshot(nether).loaded().contains(replacement), "Dimension rosters are independent");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pausedHeterogeneousComputersRemainSeparatePhysicalTickParticipants(GameTestHelper helper) {
        var level = helper.getLevel();
        var high = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(4, 1, 2)), VillagerProfession.NONE, 1);
        var low = BiocomputingWorldGameTests.computer(helper, helper.absolutePos(new BlockPos(2, 1, 2)), VillagerProfession.CLERIC, 5);
        var a = high.getBehaviour(ComputerBehaviour.TYPE); var b = low.getBehaviour(ComputerBehaviour.TYPE);
        sleeping(a); sleeping(b); var beforeA = a.flowSnapshot(); var beforeB = b.flowSnapshot();
        var hardwareA = a.hardware(); var hardwareB = b.hardware();
        helper.runAfterDelay(3, () -> {
            var roster = ComputerExecutionDirectory.snapshot(level);
            var ours = roster.ticking().stream().filter(c -> c == a || c == b).toList();
            helper.assertValueEqual(ours, List.of(b, a), "Canonical positions, not placement order; paused computers retain their place");
            helper.assertTrue(!hardwareA.limits().equals(hardwareB.limits()), "Fixture uses genuinely different resident hardware");
            helper.assertValueEqual(a.hardware(), hardwareA, "First hardware is unchanged");
            helper.assertValueEqual(b.hardware(), hardwareB, "Second hardware is unchanged");
            helper.assertValueEqual(a.flowSnapshot(), beforeA, "First independent memory and clock are unchanged");
            helper.assertValueEqual(b.flowSnapshot(), beforeB, "Second independent memory and clock are unchanged");
            helper.succeed();
        });
    }
    private static void sleeping(ComputerBehaviour computer) {
        computer.installFlow(new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("wait", "ticks"))),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(3))));
        computer.step(); // Explicit debugger entry; remains paused while ordinary vanilla block ticks continue.
    }
}
