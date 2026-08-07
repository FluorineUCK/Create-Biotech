package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

public interface SlimeBeltTunnelCapabilityInvalidator {

	void createBiotech$clearItemCapability();

	static void invalidate(Level level, BlockPos tunnelPos) {
		if (!(level instanceof ServerLevel serverLevel))
			return;

		// This is also called from BlockEntity#setRemoved while a server chunk is unloading. A
		// normal Level#getBlockEntity call may synchronously load the target chunk and wait for the
		// server chunk executor, which is the thread currently doing the unload.
		LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(
			SectionPos.blockToSectionCoord(tunnelPos.getX()), SectionPos.blockToSectionCoord(tunnelPos.getZ()));
		if (chunk == null)
			return;

		BlockEntity blockEntity = chunk.getBlockEntity(tunnelPos, LevelChunk.EntityCreationType.IMMEDIATE);
		if (blockEntity instanceof SlimeBeltTunnelCapabilityInvalidator invalidator)
			invalidator.createBiotech$clearItemCapability();
		level.invalidateCapabilities(tunnelPos);
	}
}
