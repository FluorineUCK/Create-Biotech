package com.nobodiiiii.createbiotech.content.allay.block.allayport;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class AllayPortWakeupHandler {
	private AllayPortWakeupHandler() {}

	public static void tryWakePortAbove(PackagerBlockEntity packager) {
		if (packager.getLevel() == null || packager.getLevel().isClientSide()) {
			return;
		}

		BlockEntity blockEntity = packager.getLevel().getBlockEntity(packager.getBlockPos().above());
		if (blockEntity instanceof AllayPortBlockEntity port) {
			port.tryPullFromBelow();
		}
	}
}
