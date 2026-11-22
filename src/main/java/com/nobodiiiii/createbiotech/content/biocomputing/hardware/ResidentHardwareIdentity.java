package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import net.minecraft.resources.ResourceLocation;

/** Immutable server-side identity extracted from a computer's captured resident data. */
public record ResidentHardwareIdentity(ResourceLocation entityType, ResourceLocation profession, int level) {
    public static boolean fixedSpecies(ResourceLocation id) { return ResidentModels.ENTITY_IDS.contains(id); }
}
