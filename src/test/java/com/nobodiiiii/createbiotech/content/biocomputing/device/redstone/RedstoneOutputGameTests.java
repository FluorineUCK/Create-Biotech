package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Physical latch only. Does not claim serial authorization, a player menu or a programmable driver. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class RedstoneOutputGameTests {
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void frontOnlyWeakOutputAndColdLatchNeverReplayTheSavedLevel(GameTestHelper h) {
        var level = h.getLevel(); var controller = UUID.randomUUID();
        var positions = new java.util.ArrayList<BlockPos>();
        for (var direction : Direction.values()) {
            var pos = h.absolutePos(new BlockPos(2 + direction.ordinal() * 2, 2, 3)); positions.add(pos);
            level.setBlock(pos, CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, direction), 3);
            var output = (RedstoneOutputBlockEntity) level.getBlockEntity(pos);
            h.assertTrue(output.state().controller().isEmpty(), "New output defaults closed");
            output.publish(output.state(), RedstoneOutputState.grant(output.state(), Optional.of(controller), UUID.randomUUID()));
            set(h, output, controller, 7);
            for (var query : Direction.values()) {
                h.assertValueEqual(level.getSignal(pos, query), query == direction.getOpposite() ? 7 : 0,
                    "Only front face receives weak power: " + direction + "/" + query);
                h.assertValueEqual(level.getDirectSignal(pos, query), 0, "No strong charging");
                h.assertTrue(output.getBlockState().getBlock() instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort,
                    "Real physical cable endpoint");
                h.assertValueEqual(((RedstoneOutputBlock)output.getBlockState().getBlock()).acceptsCable(output.getBlockState(), query),
                    query == direction.getOpposite(), "Cable only enters from rear");
            }
        }
        var pos = positions.get(Direction.NORTH.ordinal());
        var wire = pos.north();
        level.setBlock(wire.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        h.runAfterDelay(2, () -> {
            h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 7, "Actual wire receives analog power");
            var old = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
            var state = old.state(); var saved = old.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
            var restored = new RedstoneOutputBlockEntity(old.getType(), pos, level.getBlockState(pos));
            restored.loadWithComponents(saved, level.registryAccess());
            level.setBlockEntity(restored); restored.initialize();
            h.assertValueEqual(restored.state().desired(), 7, "Desired survives normal data serialization");
            h.assertValueEqual(restored.state().applied(), 0, "Load does not drive the saved level");
            h.assertValueEqual(restored.state().device(), state.device(), "Persistent device identity");
            h.assertValueEqual(RedstoneOutputState.set(restored.state(), controller, state.device(), state.session(), state.revision(), 7).code(),
                "stale_session", "Old accepted or queued command cannot re-energize after load");
            h.runAfterDelay(2, () -> {
                h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 0, "Neighbours see the loaded zero");
                set(h, restored, controller, 15);
                h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 15, "Explicit new command energizes");
                restored.publish(restored.state(), RedstoneOutputState.grant(restored.state(), Optional.empty(), UUID.randomUUID()));
                h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 0, "Revocation notifies the actual wire");
                h.assertValueEqual(restored.state().desired(), 15, "Revocation does not erase the desired value");
                restored.publish(restored.state(), RedstoneOutputState.grant(restored.state(), Optional.of(controller), UUID.randomUUID()));
                h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 0, "Regrant is not a set command");
                set(h, restored, controller, 9);
                level.removeBlock(pos, false);
                h.assertValueEqual(level.getBlockState(wire).getValue(RedStoneWireBlock.POWER), 0, "Removal clears physical power");
                h.succeed();
            });
        });
    }

    private static void set(GameTestHelper h, RedstoneOutputBlockEntity output, UUID source, int signal) {
        var before = output.state();
        var update = RedstoneOutputState.set(before, source, before.device(), before.session(), before.revision(), signal);
        h.assertValueEqual(update.code(), "applied", "Trusted hardware test accepted valid command");
        h.assertTrue(output.publish(before, update.state()), "Hardware state published once");
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void turningTheOutputFencesOldCommandsAndMalformedArchivesStayClosed(GameTestHelper h) {
        var level = h.getLevel(); var pos = h.absolutePos(new BlockPos(3, 2, 3)); var controller = UUID.randomUUID();
        var north = CBBlocks.REDSTONE_OUTPUT.get().defaultBlockState().setValue(RedstoneOutputBlock.FACING, Direction.NORTH);
        level.setBlock(pos, north, 3);
        var output = (RedstoneOutputBlockEntity)level.getBlockEntity(pos);
        output.publish(output.state(), RedstoneOutputState.grant(output.state(), Optional.of(controller), UUID.randomUUID()));
        set(h, output, controller, 11);
        var before = output.state();
        level.setBlock(pos, north.setValue(RedstoneOutputBlock.FACING, Direction.EAST), 3);
        h.assertTrue(level.getBlockEntity(pos) == output, "Turning preserves the physical owner");
        h.assertValueEqual(output.state().desired(), 11, "Turning keeps the desired value for inspection");
        for (var direction : Direction.values()) h.assertValueEqual(level.getSignal(pos, direction), 0, "Turning cannot energize a new target");
        h.assertValueEqual(RedstoneOutputState.set(output.state(), controller, before.device(), before.session(), before.revision(), 11).code(),
            "stale_session", "A pre-turn command cannot bind to the new output face");
        set(h, output, controller, 11);
        h.assertValueEqual(level.getSignal(pos, Direction.WEST), 11, "New command uses the newly selected face");

        var saved = output.saveWithFullMetadata(level.registryAccess());
        var malformed = saved.getCompound("BiocomputingRedstoneOutput");
        malformed.putInt("Version", 99); malformed.putString("FutureData", "preserve-me");
        level.removeBlockEntity(pos);
        var restored = new RedstoneOutputBlockEntity(output.getType(), pos, level.getBlockState(pos));
        restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored); restored.initialize();
        h.assertValueEqual(restored.appliedLevel(), 0, "Unknown archive stays electrically closed");
        h.assertTrue(!restored.publish(restored.state(), RedstoneOutputState.grant(restored.state(), Optional.of(controller), UUID.randomUUID())),
            "An unknown archive cannot be replaced by an ordinary grant");
        h.assertValueEqual(restored.saveWithFullMetadata(level.registryAccess()).get("BiocomputingRedstoneOutput"), malformed,
            "Normal save retains the exact rejected device archive");
        h.succeed();
    }
}
