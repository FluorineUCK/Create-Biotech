package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainPlacement;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(LaunchedItem.ForBelt.class)
public abstract class LaunchedItemForBeltMixin implements CBBeltChainData {

	@Unique
	private int[] createBiotech$pulleyOffsets;

	@Shadow
	public BlockState state;

	@Shadow
	public int length;

	@Shadow
	public CasingType[] casings;

	@Shadow
	public BlockPos target;

	@Override
	@Unique
	public void createBiotech$setPulleyOffsets(int[] offsets) {
		createBiotech$pulleyOffsets = offsets == null ? null : offsets.clone();
	}

	@Override
	@Unique
	public int[] createBiotech$getPulleyOffsets() {
		return createBiotech$pulleyOffsets == null ? null : createBiotech$pulleyOffsets.clone();
	}

	@Inject(method = "serializeNBT", at = @At("RETURN"))
	private void createBiotech$serializeSlimeChain(HolderLookup.Provider registries,
		CallbackInfoReturnable<CompoundTag> cir) {
		if (!CBBeltChainPlacement.isPlacementBelt(state) || createBiotech$pulleyOffsets == null)
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
		if (!CBBeltChainPlacement.isPlacementBelt(state))
			return;
		int[] pulleys = createBiotech$pulleyOffsets == null ? new int[0] : createBiotech$pulleyOffsets;
		CBBeltChainPlacement.placeAtomically(world, state,
			CBBeltChainPlacement.positionsFromPayload(state, target, length), pulleys, casings);
		ci.cancel();
	}
}
