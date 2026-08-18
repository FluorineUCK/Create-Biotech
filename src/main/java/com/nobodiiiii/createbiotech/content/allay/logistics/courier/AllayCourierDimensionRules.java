package com.nobodiiiii.createbiotech.content.allay.logistics.courier;

import com.nobodiiiii.createbiotech.registry.CBConfigs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class AllayCourierDimensionRules {
	private AllayCourierDimensionRules() {}

	public static boolean allowCrossDimensionDelivery() {
		return CBConfigs.SERVER.allayCourier.allowCrossDimensionDelivery.get();
	}

	public static boolean canTarget(ServerLevel originLevel, ResourceKey<Level> targetDimension) {
		return allowCrossDimensionDelivery() || originLevel.dimension().equals(targetDimension);
	}
}
