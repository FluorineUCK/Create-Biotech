package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Persistent binding for the Giant Frog Portal (巨型青蛙传送门). On the first completed portal
 * transition a private room is allocated and built; the resulting space index is stored here (and
 * travels with the dropped item via a data component) so the binding is permanent.
 */
public class GiantFrogPortalBlockEntity extends BlockEntity {

	private boolean hasSpace = false;
	private long spaceIndex = -1L;

	public GiantFrogPortalBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.GIANT_FROG_PORTAL.get(), pos, state);
	}

	public long ensureRoom(MinecraftServer server, ServerLevel frogLevel) {
		if (!hasSpace) {
			spaceIndex = FrogStomachSavedData.get(server).allocateSpace();
			hasSpace = true;
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
			setChanged();
		} else if (!FrogStomachSpace.isBuilt(frogLevel, spaceIndex)) {
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
		}
		return spaceIndex;
	}

	public boolean hasSpace() {
		return hasSpace;
	}

	public long getSpaceIndex() {
		return spaceIndex;
	}

	/** Re-bind this portal to an existing room (used when placing a block that carries a space id). */
	public void setSpaceIndex(long index) {
		this.spaceIndex = index;
		this.hasSpace = true;
		setChanged();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean("HasSpace", hasSpace);
		tag.putLong("SpaceIndex", spaceIndex);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		hasSpace = tag.getBoolean("HasSpace");
		spaceIndex = tag.getLong("SpaceIndex");
	}
}
