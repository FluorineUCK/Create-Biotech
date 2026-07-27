package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Persistent room and mouth/tail binding for the {@link FrogDigestiveTractBlock}. Generated rooms
 * set both explicitly; old or repaired rooms can infer them from the deterministic room-grid
 * position.
 */
public class FrogDigestiveTractBlockEntity extends BlockEntity {

	private long spaceIndex = -1L;
	private FrogStomachSpace.PortalType portalType;

	public FrogDigestiveTractBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.FROG_DIGESTIVE_TRACT.get(), pos, state);
	}

	public void setSpaceIndex(long index) {
		spaceIndex = index;
		setChanged();
	}

	public void setBinding(long index, FrogStomachSpace.PortalType type) {
		spaceIndex = index;
		portalType = type;
		setChanged();
	}

	public long getSpaceIndex() {
		if (spaceIndex >= 0)
			return spaceIndex;
		long inferred = FrogStomachSpace.spaceIndexFromDigestiveTractPos(worldPosition);
		if (inferred >= 0)
			setSpaceIndex(inferred);
		return spaceIndex;
	}

	public FrogStomachSpace.PortalType getPortalType() {
		if (portalType != null)
			return portalType;
		long index = getSpaceIndex();
		if (index >= 0) {
			portalType = FrogStomachSpace.portalTypeFromDigestiveTractPos(index, worldPosition);
			if (portalType != null)
				setChanged();
		}
		return portalType;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putLong("SpaceIndex", spaceIndex);
		if (portalType != null)
			tag.putString("PortalType", portalType.name());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		spaceIndex = tag.contains("SpaceIndex", Tag.TAG_LONG) ? tag.getLong("SpaceIndex") : -1L;
		portalType = null;
		if (tag.contains("PortalType", Tag.TAG_STRING)) {
			try {
				portalType = FrogStomachSpace.PortalType.valueOf(tag.getString("PortalType"));
			} catch (IllegalArgumentException ignored) {}
		}
	}
}
