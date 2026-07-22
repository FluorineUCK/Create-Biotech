package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.List;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;

/**
 * The Giant Frog Portal (巨型青蛙传送门). A standalone (non-kinetic) block that teleports entities
 * standing on it into a private room in the Frog Stomach dimension. The bound room's space index is
 * kept on the block entity and travels with the item ({@link CBDataComponents#FROG_STOMACH_SPACE}) so
 * breaking and re-placing rebinds to the same room; the item is non-stackable, one per unique room.
 */
public class GiantFrogPortalBlock extends Block implements EntityBlock {

	public GiantFrogPortalBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new GiantFrogPortalBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (level.isClientSide)
			return null;
		return createTicker(type, CBBlockEntityTypes.GIANT_FROG_PORTAL.get(), GiantFrogPortalBlockEntity::serverTick);
	}

	@Nullable
	@SuppressWarnings("unchecked")
	private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTicker(
		BlockEntityType<A> given, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
		return expected == given ? (BlockEntityTicker<A>) ticker : null;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
		ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		Long index = stack.get(CBDataComponents.FROG_STOMACH_SPACE.get());
		if (index != null && level.getBlockEntity(pos) instanceof GiantFrogPortalBlockEntity portal)
			portal.setSpaceIndex(index);
	}

	@Override
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		ItemStack stack = new ItemStack(CBItems.GIANT_FROG_PORTAL.get());
		if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof GiantFrogPortalBlockEntity portal
			&& portal.hasSpace())
			stack.set(CBDataComponents.FROG_STOMACH_SPACE.get(), portal.getSpaceIndex());
		return List.of(stack);
	}

	@Override
	public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
		Player player) {
		ItemStack stack = new ItemStack(CBItems.GIANT_FROG_PORTAL.get());
		if (level.getBlockEntity(pos) instanceof GiantFrogPortalBlockEntity portal && portal.hasSpace())
			stack.set(CBDataComponents.FROG_STOMACH_SPACE.get(), portal.getSpaceIndex());
		return stack;
	}
}
