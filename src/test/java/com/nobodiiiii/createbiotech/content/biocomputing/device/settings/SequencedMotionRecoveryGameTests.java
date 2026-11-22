package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native saved block-entity load ordering. Not a substitute for actual chunk unload/player tests. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class SequencedMotionRecoveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void downstreamRestoredAfterInterruptedGearshiftDoesNotReviveMotion(GameTestHelper h) {
        restoreInOrder(h, true);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void gearshiftRestoredAfterDownstreamRetiresSavedMotion(GameTestHelper h) {
        restoreInOrder(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void nativePresetStillRunsAfterTrackedRecovery(GameTestHelper h) {
        restoreInOrder(h, true, Next.NATIVE);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void freshExplicitActionStillRunsAfterTrackedRecovery(GameTestHelper h) {
        restoreInOrder(h, true, Next.EXPLICIT);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void lateAngleOutputAlsoStopsAfterRecovery(GameTestHelper h) {
        restoreInOrder(h, true, Next.NONE, SequencerInstructions.TURN_ANGLE);
    }

    private enum Next { NONE, NATIVE, EXPLICIT }
    private static void restoreInOrder(GameTestHelper h, boolean gearshiftFirst) {
        restoreInOrder(h, gearshiftFirst, Next.NONE);
    }
    private static void restoreInOrder(GameTestHelper h, boolean gearshiftFirst, Next next) {
        restoreInOrder(h, gearshiftFirst, next, SequencerInstructions.TURN_DISTANCE);
    }
    private static void restoreInOrder(GameTestHelper h, boolean gearshiftFirst, Next next, SequencerInstructions kind) {
        var level = h.getLevel();
        var target = h.absolutePos(new BlockPos(3, 3, 3));
        var shaftPos = target.south();
        var motorPos = target.north();
        var gearState = AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
            .setValue(SequencedGearshiftBlock.HORIZONTAL_AXIS, Direction.Axis.Z)
            .setValue(SequencedGearshiftBlock.VERTICAL, false);
        var shaftState = AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        level.setBlockAndUpdate(target, gearState);
        level.setBlockAndUpdate(shaftPos, shaftState);
        level.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        var drive = (CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
        drive.initialize(); drive.generatedSpeed.setValue(2); drive.updateGeneratedRotation();
        h.runAfterDelay(10, () -> {
            var gear = (SequencedGearshiftBlockEntity)level.getBlockEntity(target);
            var shaft = (KineticBlockEntity)level.getBlockEntity(shaftPos);
            var access = (SequencedMotion.Access)gear;
            var preset = Instruction.serializeAll(gear.getInstructions());
            var action = UUID.randomUUID();
            h.assertValueEqual(SequencedMotion.readiness(gear), "ok", "Real motor powers the native device");
            h.assertTrue(access.biocomputingStart(action, new Instruction(kind,
                InstructionSpeedModifiers.getByModifier(1), 128), 1), "Native distance action starts");
            h.runAfterDelay(4, () -> {
                h.assertTrue(shaft.getSpeed() != 0, "Save actual in-flight output");
                if (kind == SequencerInstructions.TURN_DISTANCE) h.assertTrue(shaft.sequenceContext != null, "Distance context actually propagated");
                var gearTag = gear.saveWithFullMetadata(level.registryAccess());
                var shaftTag = shaft.saveWithFullMetadata(level.registryAccess());
                level.removeBlockEntity(target); level.removeBlockEntity(shaftPos);
                var restoredGear = (SequencedGearshiftBlockEntity)BlockEntity.loadStatic(target, gearState, gearTag, level.registryAccess());
                var restoredShaft = (KineticBlockEntity)BlockEntity.loadStatic(shaftPos, shaftState, shaftTag, level.registryAccess());
                level.setBlockEntity(gearshiftFirst ? restoredGear : restoredShaft);
                h.runAfterDelay(12, () -> {
                    if (gearshiftFirst) {
                        h.assertTrue(restoredGear.isIdle(), "Gearshift retires before the downstream exists");
                        h.assertValueEqual(((SequencedMotion.Access)restoredGear).biocomputingPhase(), "interrupted", "Interrupted, not completed");
                    }
                    level.setBlockEntity(gearshiftFirst ? restoredShaft : restoredGear);
                    h.runAfterDelay(40, () -> {
                        h.assertValueEqual(((SequencedMotion.Access)restoredGear).biocomputingMotionId(), action, "Receipt identity survives ordering");
                        h.assertValueEqual(((SequencedMotion.Access)restoredGear).biocomputingPhase(), "interrupted", "No replay or preset launch");
                        h.assertTrue(restoredGear.isIdle(), "Native gearshift is idle");
                        h.assertValueEqual(restoredShaft.getSpeed(), 0f, "Late saved downstream must not retain stale rotation");
                        h.assertTrue(restoredShaft.sequenceContext == null, "Late saved downstream must not retain the old distance instruction");
                        h.assertValueEqual(drive.getSpeed(), 2f, "Do not stop the source motor");
                        h.assertValueEqual(Instruction.serializeAll(restoredGear.getInstructions()), preset, "Do not rewrite the native preset");
                        if (next == Next.NONE) { h.succeed(); return; }
                        var fresh = UUID.randomUUID();
                        if (next == Next.NATIVE) restoredGear.run(0);
                        else h.assertTrue(((SequencedMotion.Access)restoredGear).biocomputingStart(fresh,
                            new Instruction(SequencerInstructions.TURN_DISTANCE, InstructionSpeedModifiers.getByModifier(1), 128), 1),
                            "An explicitly started new action is not mistaken for the retired one");
                        h.runAfterDelay(8, () -> {
                            h.assertTrue(!restoredGear.isIdle() && restoredShaft.getSpeed() != 0,
                                "Retirement does not inhibit a subsequent legitimate action");
                            h.assertValueEqual(((SequencedMotion.Access)restoredGear).biocomputingPhase(),
                                next == Next.NATIVE ? "native_running" : "running", "Native and program actions remain distinguishable");
                            if (next == Next.EXPLICIT)
                                h.assertValueEqual(((SequencedMotion.Access)restoredGear).biocomputingMotionId(), fresh, "New identity remains intact");
                            h.succeed();
                        });
                    });
                });
            });
        });
    }
}
