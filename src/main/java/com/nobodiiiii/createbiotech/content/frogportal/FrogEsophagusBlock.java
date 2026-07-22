package com.nobodiiiii.createbiotech.content.frogportal;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Frog Esophagus (青蛙食道) — the indestructible return portal placed inside every Frog Stomach
 * room. Standing on it sends the player back to wherever they entered from (recorded in
 * {@link FrogStomachSavedData}). Not obtainable as an item; generated with the room.
 */
public class FrogEsophagusBlock extends Block implements EntityBlock {

	public FrogEsophagusBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FrogEsophagusBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (level.isClientSide)
			return null;
		return createTicker(type, CBBlockEntityTypes.FROG_ESOPHAGUS.get(), FrogEsophagusBlockEntity::serverTick);
	}

	@Nullable
	@SuppressWarnings("unchecked")
	private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTicker(
		BlockEntityType<A> given, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
		return expected == given ? (BlockEntityTicker<A>) ticker : null;
	}
}
