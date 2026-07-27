package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Persistent room binding for the {@link FrogEsophagusBlock}. Generated rooms set it explicitly; old
 * or repaired rooms can infer it from the deterministic room-grid position.
 */
public class FrogEsophagusBlockEntity extends BlockEntity {

	private long spaceIndex = -1L;

	public FrogEsophagusBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.FROG_ESOPHAGUS.get(), pos, state);
	}

	public void setSpaceIndex(long index) {
		spaceIndex = index;
		setChanged();
	}

	public long getSpaceIndex() {
		if (spaceIndex >= 0)
			return spaceIndex;
		long inferred = FrogStomachSpace.spaceIndexFromEsophagusPos(worldPosition);
		if (inferred >= 0)
			setSpaceIndex(inferred);
		return spaceIndex;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putLong("SpaceIndex", spaceIndex);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		spaceIndex = tag.contains("SpaceIndex", Tag.TAG_LONG) ? tag.getLong("SpaceIndex") : -1L;
	}
}
