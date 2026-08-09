package com.nobodiiiii.createbiotech.content.beltsurface;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Clears Create's cached tunnel capability without loading a chunk during belt rewiring or unload. */
public interface BeltTunnelCapabilityInvalidator {

	void createBiotech$clearItemCapability();

	static void invalidate(Level level, BlockPos tunnelPos) {
		BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, tunnelPos);
		if (blockEntity instanceof BeltTunnelCapabilityInvalidator invalidator)
			invalidator.createBiotech$clearItemCapability();
		level.invalidateCapabilities(tunnelPos);
	}
}
