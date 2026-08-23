package com.nobodiiiii.createbiotech.mixin.client;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

@Mixin(RenderStateShard.EmptyTextureStateShard.class)
public interface TextureStateShardAccessor {

	@Invoker("cutoutTexture")
	Optional<ResourceLocation> createBiotech$getTexture();
}
