package com.nobodiiiii.createbiotech.mixin;

import javax.annotation.Nullable;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disk compatibility only. Never register an endpoint, interpret a configuration, or alter Create interactions. */
@Mixin(FactoryPanelBlockEntity.class)
public abstract class FactoryGaugeArchiveMixin {
    @Unique private static final String createBiotech$agentKey = "CreateBiotechAgent";
    @Unique private static final String createBiotech$providerKey = "CreateBiotechFactoryProvider";
    @Unique private @Nullable Tag createBiotech$oldAgent;
    @Unique private @Nullable Tag createBiotech$oldProvider;

    @Inject(method = "read", at = @At("TAIL"), remap = false)
    private void createBiotech$loadArchive(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (clientPacket) return;
        Tag agent = tag.get(createBiotech$agentKey), provider = tag.get(createBiotech$providerKey);
        createBiotech$oldAgent = agent == null ? null : agent.copy();
        createBiotech$oldProvider = provider == null ? null : provider.copy();
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false)
    private void createBiotech$saveArchive(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket && createBiotech$oldAgent != null) tag.put(createBiotech$agentKey, createBiotech$oldAgent.copy());
        else tag.remove(createBiotech$agentKey);
        if (!clientPacket && createBiotech$oldProvider != null) tag.put(createBiotech$providerKey, createBiotech$oldProvider.copy());
        else tag.remove(createBiotech$providerKey);
    }
}
