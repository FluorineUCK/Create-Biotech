package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Manual in-world recipe. A static recipe result cannot carry the actual captured resident. */
public final class LibraryLecternConversion {
    private LibraryLecternConversion() {}

    public static InteractionResult tryConvert(UseOnContext context) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var lectern = level.getBlockState(pos);
        var player = context.getPlayer();
        if (!lectern.is(Blocks.LECTERN) || player == null || player.isShiftKeyDown()) return InteractionResult.PASS;
        var box = context.getItemInHand();
        if (!LibraryResidentCapture.project(box, level.registryAccess()).valid()) return InteractionResult.PASS;
        var upper = pos.above();
        var core = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
            .setValue(LibraryCoreBlock.FACING, lectern.getValue(LecternBlock.FACING));
        if (box.getCount() != 1 || !player.mayBuild() || player.isSpectator()
            || !level.mayInteract(player, pos) || !level.mayInteract(player, upper)
            || lectern.getValue(LecternBlock.HAS_BOOK)
            || !(level.getBlockEntity(pos) instanceof LecternBlockEntity old) || !old.getBook().isEmpty()
            || !LibraryCoreBlock.hasSpaceForUpperHalf(level, pos) || !level.isEmptyBlock(upper)
            || !core.canSurvive(level, pos)) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (new LibraryResidentCapture(level).inspect(box) != LibraryResident.InstallResult.SUCCESS)
            return InteractionResult.FAIL;

        if (!level.setBlock(pos, core, Block.UPDATE_ALL)) return InteractionResult.FAIL;
        if (level.setBlock(upper, core.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL)
            && level.getBlockEntity(pos) instanceof LibraryCoreHost host
            && host.applyPlacementData(LibraryCoreItemData.unconfigured())
            && host.installLibraryResident(player, context.getHand())) {
            return InteractionResult.CONSUME;
        }
        // No resident transferred. Undo the partial placement without dropping a second core.
        LibraryRemoval.withRemovalGuard(level, pos, () -> {
            if (LibraryCoreBlock.isHalf(level.getBlockState(upper), DoubleBlockHalf.UPPER))
                level.removeBlock(upper, false);
            level.setBlock(pos, lectern, Block.UPDATE_ALL);
        });
        return InteractionResult.FAIL;
    }
}
