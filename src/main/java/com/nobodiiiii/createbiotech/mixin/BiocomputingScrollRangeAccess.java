package com.nobodiiiii.createbiotech.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ScrollValueBehaviour.class, remap = false)
public interface BiocomputingScrollRangeAccess {
    @Accessor("min") int createBiotech$min();
    @Accessor("max") int createBiotech$max();
}
