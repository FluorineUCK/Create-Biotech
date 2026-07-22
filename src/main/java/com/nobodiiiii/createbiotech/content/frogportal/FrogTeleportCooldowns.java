package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.level.Level;

/**
 * Transient, server-side cooldown registry shared by the Frog Portal and the Frog Esophagus so that
 * an entity that has just been teleported (in either direction) is not immediately bounced back by
 * the block it lands on/near. Keyed by entity UUID against the server's overworld game time so the
 * comparison is consistent across dimensions. Not persisted — cooldowns need not survive a restart.
 */
public final class FrogTeleportCooldowns {

	private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

	private FrogTeleportCooldowns() {}

	private static long gameTime(Level level) {
		return level.getServer() != null ? level.getServer().overworld().getGameTime() : level.getGameTime();
	}

	public static void mark(Level level, UUID uuid, int ticks) {
		COOLDOWNS.put(uuid, gameTime(level) + ticks);
	}

	public static boolean isActive(Level level, UUID uuid) {
		Long until = COOLDOWNS.get(uuid);
		if (until == null)
			return false;
		if (gameTime(level) >= until) {
			COOLDOWNS.remove(uuid);
			return false;
		}
		return true;
	}
}
