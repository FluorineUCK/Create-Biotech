package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.InstructionSpeedModifiers;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlock;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Measures native movement, separately from the adapter's sequence-completed receipt. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class SequencedMotionArrivalGameTests {
    @GameTest(template = "empty", timeoutTicks = 500)
    public static void distanceActionMovesActualPulleyLoadThreeBlocks(GameTestHelper h) {
        var level = h.getLevel();
        var target = h.absolutePos(new BlockPos(3, 8, 3));
        var pulleyPos = target.south();
        var loadStart = pulleyPos.below();
        var loadEnd = loadStart.below(3);
        level.setBlockAndUpdate(target, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
            .setValue(SequencedGearshiftBlock.HORIZONTAL_AXIS, Direction.Axis.Z)
            .setValue(SequencedGearshiftBlock.VERTICAL, false));
        level.setBlockAndUpdate(pulleyPos, AllBlocks.ROPE_PULLEY.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(loadStart, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(target.north(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(target.north());
        motor.initialize(); motor.generatedSpeed.setValue(16); motor.updateGeneratedRotation();
        h.runAfterDelay(10, () -> {
            var gear = (SequencedGearshiftBlockEntity)level.getBlockEntity(target);
            var pulley = (PulleyBlockEntity)level.getBlockEntity(pulleyPos);
            var access = (SequencedMotion.Access)gear;
            var preset = Instruction.serializeAll(gear.getInstructions());
            var action = UUID.randomUUID();
            h.assertValueEqual(SequencedMotion.readiness(gear), "ok", "Real input power is required");
            h.assertTrue(access.biocomputingStart(action, new Instruction(SequencerInstructions.TURN_DISTANCE,
                InstructionSpeedModifiers.getByModifier(1), 3), 1), "Start the native distance instruction");
            h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(pulley.running && pulley.movedContraption != null && pulley.offset > 0,
                    "Real pulley assembles the load and moves it, not just a rotating shaft"))
                .thenWaitUntil(() -> {
                    h.assertTrue(gear.isIdle(), "The native sequence has stopped");
                    h.assertTrue(level.getBlockState(loadEnd).is(Blocks.GOLD_BLOCK), "The original load is placed three blocks below");
                })
                .thenExecute(() -> {
                    h.assertTrue(Math.abs(pulley.offset - 3) < 0.001, "Measured pulley extension is three blocks");
                    h.assertValueEqual(access.biocomputingPhase(), "sequence_completed", "Sequence completion remains its own receipt");
                    h.assertValueEqual(access.biocomputingMotionId(), action, "Receipt retains the action identity");
                    h.assertValueEqual(SequencedMotion.state(gear, null).field("arrival"),
                        new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.TextValue("not_measured"),
                        "Adapter does not pretend to contain an arrival sensor");
                    h.assertValueEqual(Instruction.serializeAll(gear.getInstructions()), preset, "Native preset is unchanged");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(loadEnd).is(Blocks.GOLD_BLOCK), "Load stays at the requested distance");
                    h.assertTrue(!level.getBlockState(loadStart).is(Blocks.GOLD_BLOCK), "No duplicate load at the starting position");
                    h.assertValueEqual(pulley.getSpeed(), 0f, "Completed action no longer drives the pulley");
                    h.assertValueEqual(motor.getSpeed(), 16f, "Input motor remains powered");
                }).thenSucceed();
        });
    }
}
