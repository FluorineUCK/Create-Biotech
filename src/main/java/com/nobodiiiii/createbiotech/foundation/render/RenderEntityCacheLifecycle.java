package com.nobodiiiii.createbiotech.foundation.render;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Releases {@link BoundedRenderEntityCache} contents when the client level goes away. Without this
 * the caches keep the departed {@code ClientLevel} — and everything it references — reachable until
 * some renderer happens to request a proxy for a different level.
 */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class RenderEntityCacheLifecycle {
	private static final int PRUNE_INTERVAL_TICKS = 20;

	private static int ticksUntilPrune = PRUNE_INTERVAL_TICKS;

	private RenderEntityCacheLifecycle() {}

	@SubscribeEvent
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (event.getLevel().isClientSide())
			BoundedRenderEntityCache.clearAll();
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		BoundedRenderEntityCache.clearAll();
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (--ticksUntilPrune > 0)
			return;
		ticksUntilPrune = PRUNE_INTERVAL_TICKS;
		BoundedRenderEntityCache.pruneAll();
	}
}
