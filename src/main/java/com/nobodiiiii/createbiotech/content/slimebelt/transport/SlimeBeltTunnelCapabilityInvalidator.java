package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public interface SlimeBeltTunnelCapabilityInvalidator {

	void createBiotech$clearItemCapability();

	static void invalidate(Level level, BlockPos tunnelPos) {
		if (level.isClientSide)
			return;
		if (level.getBlockEntity(tunnelPos) instanceof SlimeBeltTunnelCapabilityInvalidator invalidator)
			invalidator.createBiotech$clearItemCapability();
		level.invalidateCapabilities(tunnelPos);
	}
}
