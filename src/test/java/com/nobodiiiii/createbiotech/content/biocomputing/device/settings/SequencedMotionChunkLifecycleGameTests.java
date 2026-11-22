package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class SequencedMotionChunkLifecycleGameTests {
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void interruptedMotionDoesNotReviveAcrossActuallyUnloadedChunks(GameTestHelper h) {
        recoverAcrossChunks(h, false);
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void interruptedMotionDoesNotMoveAnActualPulleyOnReload(GameTestHelper h) {
        recoverAcrossChunks(h, true);
    }

    private static void recoverAcrossChunks(GameTestHelper h, boolean pulley) {
        var level = h.getLevel();
        // A GameTest ticket would prevent unloading its own structure. Put the real mechanism at
        // a remote chunk border and let Minecraft own both eviction and reconstruction.
        var far = h.absolutePos(new BlockPos(pulley ? 65536 : 32768, 4, 1));
        var gearPos = new BlockPos((far.getX() & ~15) + 8, far.getY(), (far.getZ() & ~15) + 15);
        var shaftPos = gearPos.south();
        var motorPos = gearPos.north();
        var gearChunk = new ChunkPos(gearPos);
        var shaftChunk = new ChunkPos(shaftPos);
        var firstLoadAnchor = new ChunkPos(gearChunk.x, gearChunk.z - 2);
        var downstreamLoadAnchor = new ChunkPos(shaftChunk.x, shaftChunk.z + 2);
        h.assertTrue(!gearChunk.equals(shaftChunk), "The gearshift and downstream must reside in separate chunks");
        level.setChunkForced(gearChunk.x, gearChunk.z, true);
        level.setChunkForced(shaftChunk.x, shaftChunk.z, true);

        var gearState = AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
            .setValue(SequencedGearshiftBlock.HORIZONTAL_AXIS, Direction.Axis.Z)
            .setValue(SequencedGearshiftBlock.VERTICAL, false);
        level.setBlockAndUpdate(gearPos, gearState);
        if (pulley) {
            level.setBlockAndUpdate(shaftPos.below(), Blocks.GOLD_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(shaftPos, AllBlocks.ROPE_PULLEY.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z));
        } else {
            level.setBlockAndUpdate(shaftPos, AllBlocks.SHAFT.getDefaultState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        }
        level.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.SOUTH));
        var drive = (CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
        drive.initialize();
        drive.generatedSpeed.setValue(2);
        drive.updateGeneratedRotation();
        var gear = (SequencedGearshiftBlockEntity)level.getBlockEntity(gearPos);
        var shaft = (KineticBlockEntity)level.getBlockEntity(shaftPos);
        var action = UUID.randomUUID();
        var preset = Instruction.serializeAll(gear.getInstructions());
        var chunks = level.getChunkSource();
        float[] restoredOffset = new float[1];

        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(level.shouldTickBlocksAt(gearChunk.toLong()) && level.shouldTickBlocksAt(shaftChunk.toLong()),
                "Both chunks actually tick before starting");
            h.assertValueEqual(SequencedMotion.readiness(gear), "ok", "Native motor drives the gearshift");
        }).thenExecute(() -> {
            h.assertTrue(((SequencedMotion.Access)gear).biocomputingStart(action, new Instruction(
                SequencerInstructions.TURN_DISTANCE, InstructionSpeedModifiers.getByModifier(1), 128), 1),
                "Tracked native action starts");
        }).thenWaitUntil(() -> {
            h.assertTrue(shaft.getSpeed() != 0 && shaft.sequenceContext != null && gearPos.equals(shaft.source),
                "Running distance context crosses the real chunk boundary");
            if (pulley) {
                h.assertTrue(pulleyOffset((PulleyBlockEntity)shaft) > 1f
                    && !level.getBlockState(shaftPos.below()).is(Blocks.GOLD_BLOCK),
                    "The driven pulley has already assembled and moved its real load");
            }
        }).thenExecute(() -> {
            level.setChunkForced(shaftChunk.x, shaftChunk.z, false);
            level.setChunkForced(gearChunk.x, gearChunk.z, false);
        }).thenWaitUntil(() -> {
            h.assertTrue(chunks.getChunkNow(gearChunk.x, gearChunk.z) == null && chunks.getChunkNow(shaftChunk.x, shaftChunk.z) == null
                && gear.isRemoved() && shaft.isRemoved(), "Actual eviction, not just paused ticking");
        }).thenExecute(() -> {
            level.setChunkForced(firstLoadAnchor.x, firstLoadAnchor.z, true);
        }).thenWaitUntil(() -> {
            // The gearshift is at the full-but-not-ticking edge of the anchor ticket.
            // The next chunk is farther away and cannot restore the downstream yet.
            h.assertTrue(chunks.getChunkNow(gearChunk.x, gearChunk.z) != null
                && chunks.getChunkNow(shaftChunk.x, shaftChunk.z) == null
                && !level.shouldTickBlocksAt(gearChunk.toLong()), "Gearshift chunk loads ahead of the downstream without ticking");
        }).thenExecute(() -> {
            var first = (SequencedGearshiftBlockEntity)level.getBlockEntity(gearPos);
            h.assertTrue(first != null && first != gear, "Gearshift has already been restored from its chunk");
            var motion = (SequencedMotion.Access)first;
            h.assertValueEqual(motion.biocomputingMotionId(), action, "The early load already sees the held action identity");
            h.assertValueEqual(first.getRotationSpeedModifier(Direction.SOUTH), 0f, "The early load cannot drive motion before retirement");
            if (pulley) level.setChunkForced(downstreamLoadAnchor.x, downstreamLoadAnchor.z, true);
        }).thenWaitUntil(() -> {
            if (pulley) {
                h.assertTrue(chunks.getChunkNow(shaftChunk.x, shaftChunk.z) != null
                    && !level.shouldTickBlocksAt(shaftChunk.toLong()), "Pulley loads safely before its first mechanical tick");
            }
        }).thenExecute(() -> {
            if (pulley) {
                var loadedPulley = (PulleyBlockEntity)level.getBlockEntity(shaftPos);
                restoredOffset[0] = pulleyOffset(loadedPulley);
                h.assertTrue(restoredOffset[0] > 0, "The restored mechanism has a real in-flight offset");
            }
            level.setChunkForced(gearChunk.x, gearChunk.z, true);
            level.setChunkForced(shaftChunk.x, shaftChunk.z, true);
        }).thenWaitUntil(() -> {
            h.assertTrue(chunks.isPositionTicking(gearChunk.toLong()) && chunks.isPositionTicking(shaftChunk.toLong())
                && level.areEntitiesLoaded(gearChunk.toLong()) && level.areEntitiesLoaded(shaftChunk.toLong()),
                "Both chunks have reached the native block-entity tick boundary");
            h.assertTrue(level.getBlockEntity(gearPos) instanceof SequencedGearshiftBlockEntity
                && level.getBlockEntity(shaftPos) instanceof KineticBlockEntity, "Normal chunk load restores both block entities");
        }).thenIdle(1).thenExecute(() -> {
            // The lookup above may materialize a pending saved block entity after this world's
            // tick. Give its registered ticker one real pass before inspecting runtime speed.
            var firstTickingShaft = (KineticBlockEntity)level.getBlockEntity(shaftPos);
            h.assertValueEqual(firstTickingShaft.getSpeed(), 0f, "No stale rotation after the first actual block-entity tick");
            h.assertTrue(firstTickingShaft.sequenceContext == null && firstTickingShaft.source == null,
                "The first tick clears the old distance context and kinetic source");
            if (pulley) {
                // Create's native disassembly rounds a stopped mechanism to its grid position.
                h.assertValueEqual(pulleyOffset((PulleyBlockEntity)firstTickingShaft), (float)Math.round(restoredOffset[0]),
                    "The first mechanical tick settles on the saved grid position, not a continued motion");
            }
        }).thenIdle(29).thenExecute(() -> {
            var actualGear = (SequencedGearshiftBlockEntity)level.getBlockEntity(gearPos);
            var actualShaft = (KineticBlockEntity)level.getBlockEntity(shaftPos);
            var motor = (CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
            h.assertTrue(actualGear != gear && actualShaft != shaft, "Recovery uses newly loaded block entities");
            var motion = (SequencedMotion.Access)actualGear;
            h.assertValueEqual(motion.biocomputingMotionId(), action, "Original action identity survives");
            h.assertValueEqual(motion.biocomputingPhase(), "interrupted", "Unload did not complete or replay the motion");
            h.assertTrue(actualGear.isIdle(), "Native gearshift is idle");
            h.assertValueEqual(actualShaft.getSpeed(), 0f, "Downstream is not driven by an expired instruction");
            h.assertTrue(actualShaft.sequenceContext == null && actualShaft.source == null, "Old context and kinetic source are gone");
            if (pulley) {
                int settled = Math.round(restoredOffset[0]);
                h.assertValueEqual(pulleyOffset((PulleyBlockEntity)actualShaft), (float)settled,
                    "The interrupted pulley holds its settled position");
                h.assertTrue(level.getBlockState(shaftPos.below(settled + 1)).is(Blocks.GOLD_BLOCK)
                    && !level.getBlockState(shaftPos.below()).is(Blocks.GOLD_BLOCK),
                    "The real load is placed once at the settled position, not moved back to the starting block");
            }
            h.assertValueEqual(motor.getSpeed(), 2f, "Recovery does not stop the independent input motor");
            h.assertValueEqual(Instruction.serializeAll(actualGear.getInstructions()), preset, "Native preset stays intact");
            level.setChunkForced(shaftChunk.x, shaftChunk.z, false);
            level.setChunkForced(gearChunk.x, gearChunk.z, false);
            level.setChunkForced(firstLoadAnchor.x, firstLoadAnchor.z, false);
            if (pulley) level.setChunkForced(downstreamLoadAnchor.x, downstreamLoadAnchor.z, false);
        }).thenSucceed();
    }

    private static float pulleyOffset(PulleyBlockEntity pulley) {
        return pulley.saveWithFullMetadata(pulley.getLevel().registryAccess()).getFloat("Offset");
    }
}
