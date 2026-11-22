package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

import com.nobodiiiii.createbiotech.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.*;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class TextOutputGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeLinkReadsOnlyTheAcceptedPageAndRespectsUnpoweredBoard(GameTestHelper h) {
        var level = h.getLevel(); var pos = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, CBBlocks.TEXT_OUTPUT.get().defaultBlockState(), 3);
        var output = (TextOutputBlockEntity)level.getBlockEntity(pos);
        h.assertTrue(DisplaySource.getAll(level, pos).contains(CBDisplaySources.TEXT_OUTPUT.get()), "Real source registered on real block");
        h.assertTrue(com.simibubi.create.api.registry.CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(CBDisplaySources.TEXT_OUTPUT.get()) != null, "Create registry identity exists for the link GUI");
        var source = UUID.randomUUID(); var before = output.state();
        h.assertTrue(output.publish(before, TextOutputState.grant(before, Optional.of(source), UUID.randomUUID())), "Explicit grant");
        before = output.state();
        var page = TextOutputState.replace(before, source, before.device(), before.session(), before.revision(), List.of("READY", "SECOND"), new TextOutputState.Limits(8, 256));
        h.assertTrue(output.publish(before, page.state()), "Accepted buffer is published");
        var linkPos = pos.above();
        level.setBlock(linkPos, AllBlocks.DISPLAY_LINK.getDefaultState().setValue(DisplayLinkBlock.FACING, Direction.UP), 3);
        var link = (DisplayLinkBlockEntity)level.getBlockEntity(linkPos); link.initialize();
        var boardPos = pos.offset(3, 0, 0);
        level.setBlock(boardPos, AllBlocks.DISPLAY_BOARD.getDefaultState(), 3);
        var board = (FlapDisplayBlockEntity)level.getBlockEntity(boardPos); board.updateControllerStatus();
        link.target(boardPos); link.activeSource = CBDisplaySources.TEXT_OUTPUT.get();
        var context = new DisplayLinkContext(level, link);
        h.assertValueEqual(link.activeSource.provideText(context, null).stream().map(Component::getString).toList(), List.of("READY", "SECOND"), "Plain literal source page");
        board.applyTextManually(0, Component.literal("OLD"));
        link.tickSource();
        h.assertTrue(!board.isSpeedRequirementFulfilled(), "Board without kinetics is not running");
        h.assertValueEqual(board.getLines().getFirst().getSections().getFirst().getText().getString(), "OLD", "Unpowered native target is untouched");
        // Fixture provides kinetic speed; native tick still decides whether the target can run.
        powerBoard(level, board);
        h.assertTrue(board.isSpeedRequirementFulfilled(), "Native speed requirement fulfilled");
        board.applyTextManually(1, Component.literal("RESERVED"));
        link.tickSource();
        h.assertValueEqual(board.getLines().getFirst().getSections().getFirst().getText().getString(), "READY", "Actual DisplayBoardTarget receives text through link");
        h.assertValueEqual(board.getLines().get(1).getSections().getFirst().getText().getString(), "RESERVED", "Native manual row reservation wins");
        h.assertValueEqual(output.state().lines(), List.of("READY", "SECOND"), "Acceptance never claims physical animation completion");
        var saved = output.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var restored = new TextOutputBlockEntity(output.getType(), pos, output.getBlockState());
        restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored); restored.initialize();
        h.assertValueEqual(restored.state().lines(), List.of("READY", "SECOND"), "Reload keeps the accepted text");
        h.assertTrue(!restored.state().session().equals(output.state().session()), "Cold commands use a new session");
        var loadedSession = restored.state().session();
        restored.onChunkUnloaded(); restored.initialize();
        h.assertTrue(!loadedSession.equals(restored.state().session()), "Retained block entity reactivation also fences old commands");
        h.assertValueEqual(restored.state().lines(), List.of("READY", "SECOND"), "Unload never clears accepted text");
        var invalid = restored.saveWithFullMetadata(level.registryAccess());
        invalid.getCompound("BiocomputingTextOutput").putInt("Version", 99);
        restored.loadWithComponents(invalid, level.registryAccess());
        link.tickSource();
        h.assertValueEqual(board.getLines().getFirst().getSections().getFirst().getText().getString(), "READY", "Invalid archive is unavailable, not an implicit clear command");
        h.assertValueEqual(restored.saveWithFullMetadata(level.registryAccess()).get("BiocomputingTextOutput"), invalid.get("BiocomputingTextOutput"), "Unknown archive preserved exactly");
        h.succeed();
    }
    public static void publishFixturePage(GameTestHelper h, BlockPos pos, List<String> lines) {
        var output = (TextOutputBlockEntity)h.getLevel().getBlockEntity(pos);
        var source = UUID.randomUUID();
        var before = output.state();
        h.assertTrue(output.publish(before, TextOutputState.grant(before, Optional.of(source), UUID.randomUUID())), "Fixture source opened");
        before = output.state();
        var page = TextOutputState.replace(before, source, before.device(), before.session(), before.revision(), lines,
            new TextOutputState.Limits(8, 256));
        h.assertTrue(output.publish(before, page.state()), "Fixture text ready");
    }

    public static void powerBoard(net.minecraft.server.level.ServerLevel level, FlapDisplayBlockEntity board) {
        var back = board.getBlockState().getValue(com.simibubi.create.content.trains.display.FlapDisplayBlock.HORIZONTAL_FACING).getOpposite();
        var cog = board.getBlockPos().above();
        level.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, back.getAxis()), 3);
        var pos = cog.relative(back);
        level.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(com.simibubi.create.content.kinetics.motor.CreativeMotorBlock.FACING, back.getOpposite()), 3);
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(pos);
        motor.initialize(); motor.generatedSpeed.setValue(64); motor.updateGeneratedRotation(); board.tick();
    }
}
