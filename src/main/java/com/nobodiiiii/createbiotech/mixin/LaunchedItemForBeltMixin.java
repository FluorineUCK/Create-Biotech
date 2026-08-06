package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltConnectorItem;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(LaunchedItem.ForBelt.class)
public abstract class LaunchedItemForBeltMixin implements SlimeChainData {

	@Unique
	private int[] createBiotech$pulleyOffsets;

	@Shadow
	public BlockState state;

	@Shadow
	public int length;

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
		if (!state.is(com.nobodiiiii.createbiotech.registry.CBBlocks.SLIME_BELT.get()) || createBiotech$pulleyOffsets == null)
			return;
		cir.getReturnValue().putIntArray("CreateBiotechPulleyOffsets", createBiotech$pulleyOffsets);
	}

	@Inject(method = "readNBT", at = @At("TAIL"), remap = false)
	private void createBiotech$readSlimeChain(CompoundTag nbt, HolderLookup.Provider registries,
		HolderGetter<Block> holderGetter, CallbackInfo ci) {
		if (nbt.contains("CreateBiotechPulleyOffsets"))
			createBiotech$pulleyOffsets = nbt.getIntArray("CreateBiotechPulleyOffsets");
	}

	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void createBiotech$placeSlimeChain(Level world, CallbackInfo ci) {
		if (!state.is(com.nobodiiiii.createbiotech.registry.CBBlocks.SLIME_BELT.get()))
			return;
		if (length < 2) {
			ci.cancel();
			return;
		}

		boolean isStart = state.getValue(SlimeBeltBlock.PART)
			== com.simibubi.create.content.kinetics.belt.BeltPart.START;
		BlockPos offset = SlimeBeltBlock.nextSegmentPosition(state, BlockPos.ZERO, isStart);
		if (offset == null) {
			ci.cancel();
			return;
		}
		Axis shaftAxis = state.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.SIDEWAYS ? Axis.Y
			: state.getValue(SlimeBeltBlock.HORIZONTAL_FACING).getClockWise().getAxis();
		world.setBlockAndUpdate(target, AllBlocks.SHAFT.getDefaultState()
			.setValue(AbstractSimpleShaftBlock.AXIS, shaftAxis));
		if (createBiotech$pulleyOffsets != null)
			for (int pulleyOffset : createBiotech$pulleyOffsets)
				world.setBlockAndUpdate(target.offset(offset.getX() * pulleyOffset, offset.getY() * pulleyOffset,
					offset.getZ() * pulleyOffset), AllBlocks.SHAFT.getDefaultState()
						.setValue(AbstractSimpleShaftBlock.AXIS, shaftAxis));
		BlockPos end = target.offset(offset.getX() * (length - 1), offset.getY() * (length - 1),
			offset.getZ() * (length - 1));
		SlimeBeltConnectorItem.createBelts(world, target, end);
		ci.cancel();
	}
}
