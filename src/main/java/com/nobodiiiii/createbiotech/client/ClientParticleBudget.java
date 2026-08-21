package com.nobodiiiii.createbiotech.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

/** Scales decorative particle bursts down to the particle setting the player picked. */
public final class ClientParticleBudget {

	private ClientParticleBudget() {
	}

	/**
	 * Emit only every n-th candidate position of a decorative burst. {@code ParticleEngine} applies
	 * the particle setting itself, but always-visible particles bypass that path, so bursts built
	 * from them have to be thinned at the call site.
	 */
	public static int decorativeStride() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options == null)
			return 1;
		ParticleStatus status = minecraft.options.particles()
			.get();
		return switch (status) {
			case ALL -> 1;
			case DECREASED -> 2;
			case MINIMAL -> 4;
		};
	}
}
