package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

/** Preflights known vanilla container getter side effects; never resolves handlers or reads inventory slots. */
final class VanillaContainerReadGuard {
    private VanillaContainerReadGuard() {}

    /** NeoForge's chest item capability uses getContainer(..., true): lids and cats are irrelevant. */
    @Nullable static DataValue.ErrorValue inventory(BlockReadContext context) { return check(context, true); }

    /** The vanilla chest comparator uses getContainer(..., false), respecting blocked containers. */
    @Nullable static DataValue.ErrorValue comparator(BlockReadContext context) { return check(context, false); }

    @Nullable private static DataValue.ErrorValue check(BlockReadContext context, boolean ignoreBlocked) {
        if (context.state().getBlock() instanceof ChestBlock chest)
            return chest(context, chest, ignoreBlocked);
        return hasUnresolvedLoot(context.entity()) ? error("unresolved_loot") : null;
    }

    /** Follow DoubleBlockCombiner's actual participation branches before its unguarded world queries. */
    @Nullable private static DataValue.ErrorValue chest(BlockReadContext context, ChestBlock chest, boolean ignoreBlocked) {
        var level = context.level();
        var target = context.target();
        var state = context.state();
        var entity = context.entity();
        if (entity == null || entity.getType() != chest.blockEntityType()) return null;
        if (!ignoreBlocked) {
            if (!available(context, target.above())) return error("target_unloaded");
            if (ChestBlock.isChestBlockedAt(level, target)) return null;
        }
        BlockEntity partnerEntity = null;
        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            var partner = target.relative(ChestBlock.getConnectedDirection(state));
            if (!available(context, partner)) return error("target_unloaded");
            var partnerState = level.getBlockState(partner);
            if (partnerState.is(state.getBlock())
                    && partnerState.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && partnerState.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE)
                    && partnerState.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)) {
                if (!ignoreBlocked) {
                    if (!available(context, partner.above())) return error("target_unloaded");
                    if (ChestBlock.isChestBlockedAt(level, partner)) return null;
                }
                partnerEntity = chest.blockEntityType().getBlockEntity(level, partner);
            }
        }
        // getItem would unpack loot, clearing its key and filling the live inventory.
        return hasUnresolvedLoot(entity) || hasUnresolvedLoot(partnerEntity) ? error("unresolved_loot") : null;
    }

    private static boolean available(BlockReadContext context, BlockPos position) {
        return context.level().isOutsideBuildHeight(position) || context.level().isLoaded(position);
    }

    private static boolean hasUnresolvedLoot(@Nullable BlockEntity entity) {
        return entity instanceof RandomizableContainer container && container.getLootTable() != null;
    }

    private static DataValue.ErrorValue error(String code) { return new DataValue.ErrorValue(code, ""); }
}
