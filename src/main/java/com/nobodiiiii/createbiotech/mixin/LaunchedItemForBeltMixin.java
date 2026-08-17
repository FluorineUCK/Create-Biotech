package com.nobodiiiii.createbiotech.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainData;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainPlacement;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;

@Mixin(LaunchedItem.ForBelt.class)
public abstract class LaunchedItemForBeltMixin implements CBBeltChainData {

	@Unique
	private int[] createBiotech$pulleyOffsets;

	@Override
	@Unique
	public void createBiotech$setPulleyOffsets(int[] offsets) {
		createBiotech$pulleyOffsets = offsets == null ? null : Arrays.copyOf(offsets, offsets.length);
	}

	@Override
	@Unique
	public int[] createBiotech$getPulleyOffsets() {
		return createBiotech$pulleyOffsets == null ? null
			: Arrays.copyOf(createBiotech$pulleyOffsets, createBiotech$pulleyOffsets.length);
	}

	@Inject(method = "serializeNBT", at = @At("RETURN"))
	private void createBiotech$serializeSlimeChain(HolderLookup.Provider registries,
		CallbackInfoReturnable<CompoundTag> cir) {
		LaunchedItem.ForBelt launched = (LaunchedItem.ForBelt) (Object) this;
		if (!CBBeltChainPlacement.isPlacementBelt(launched.state) || createBiotech$pulleyOffsets == null)
			return;
		cir.getReturnValue().putIntArray(CBBeltChainPlacement.PULLEY_OFFSETS_TAG, createBiotech$pulleyOffsets);
	}

	@Inject(method = "readNBT", at = @At("TAIL"))
	private void createBiotech$readSlimeChain(CompoundTag nbt, HolderLookup.Provider registries,
		HolderGetter<Block> holderGetter, CallbackInfo ci) {
		if (nbt.contains(CBBeltChainPlacement.PULLEY_OFFSETS_TAG))
			createBiotech$pulleyOffsets = nbt.getIntArray(CBBeltChainPlacement.PULLEY_OFFSETS_TAG);
	}

	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void createBiotech$placeBeltChain(Level world, CallbackInfo ci) {
		LaunchedItem.ForBelt launched = (LaunchedItem.ForBelt) (Object) this;
		if (!CBBeltChainPlacement.isPlacementBelt(launched.state))
			return;
		int[] pulleys = createBiotech$pulleyOffsets == null ? new int[0] : createBiotech$pulleyOffsets;
		CBBeltChainPlacement.placeAtomically(world, launched.state,
			CBBeltChainPlacement.positionsFromPayload(launched.state, launched.target, launched.length), pulleys,
			launched.casings);
		ci.cancel();
	}
}
