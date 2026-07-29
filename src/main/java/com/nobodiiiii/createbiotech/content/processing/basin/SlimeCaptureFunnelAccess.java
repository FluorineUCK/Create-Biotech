package com.nobodiiiii.createbiotech.content.processing.basin;

import net.minecraft.world.entity.monster.Slime;

/**
 * Runtime bridge implemented by Create's funnel block entity mixin.
 * <p>
 * Funnel blocks call this only when a small slime actually overlaps their capture volume, keeping capture work
 * event-driven instead of polling every loaded funnel each tick.
 */
public interface SlimeCaptureFunnelAccess {

	boolean createBiotech$tryCaptureSmallSlime(Slime slime);
}
