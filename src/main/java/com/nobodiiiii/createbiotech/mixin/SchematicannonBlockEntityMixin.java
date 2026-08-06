package com.nobodiiiii.createbiotech.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(SchematicannonBlockEntity.class)
public abstract class SchematicannonBlockEntityMixin {

	@Shadow
	public List<LaunchedItem> flyingBlocks;

	@Shadow
	protected abstract void launchBelt(BlockPos target, BlockState state, int length, CasingType[] casings);

	@Shadow
	protected abstract void launchBlock(BlockPos target, ItemStack stack, BlockState state,
		@org.jetbrains.annotations.Nullable CompoundTag data);

	@Inject(method = "shouldIgnoreBlockState", at = @At("HEAD"), cancellable = true)
	private void createBiotech$ignoreSlimeMiddle(BlockState state, BlockEntity blockEntity,
		CallbackInfoReturnable<Boolean> cir) {
		if (state.is(CBBlocks.SLIME_BELT.get()) && state.getValue(SlimeBeltBlock.PART) == BeltPart.MIDDLE)
			cir.setReturnValue(true);
	}

	@Inject(method = "launchBlockOrBelt", at = @At("HEAD"), cancellable = true)
	private void createBiotech$launchSlimeChain(BlockPos target, ItemStack icon, BlockState state,
		BlockEntity blockEntity, CallbackInfo ci) {
		if (!state.is(CBBlocks.SLIME_BELT.get()))
			return;

		if (state.getValue(SlimeBeltBlock.PART) == BeltPart.MIDDLE) {
			ci.cancel();
			return;
		}

		if (!isLastEndpoint(state)) {
			launchBlock(target, icon, shaftState(state), null);
			ci.cancel();
			return;
		}

		if (!(blockEntity instanceof SlimeBeltBlockEntity belt) || belt.beltLength < 2) {
			// A single segment is not a valid slime belt. Leaving it unlaunched avoids
			// the connector's invalid-chain cleanup destroying the cannon target.
			ci.cancel();
			return;
		}

		CasingType[] noCasings = new CasingType[belt.beltLength];
		Arrays.fill(noCasings, CasingType.NONE);
		launchBelt(target, state, belt.beltLength, noCasings);
		if (!flyingBlocks.isEmpty()) {
			LaunchedItem launched = flyingBlocks.get(flyingBlocks.size() - 1);
			if (launched instanceof SlimeChainData chainData)
				chainData.createBiotech$setPulleyOffsets(collectPulleyOffsets(belt, target, state));
		}
		ci.cancel();
	}

	private static int[] collectPulleyOffsets(SlimeBeltBlockEntity belt, BlockPos target, BlockState state) {
		if (belt.getLevel() == null)
			return new int[0];

		boolean isStart = state.getValue(SlimeBeltBlock.PART) == BeltPart.START;
		BlockPos current = target;
		List<Integer> offsets = new ArrayList<Integer>();
		for (int segment = 0; segment < belt.beltLength; segment++) {
			BlockState currentState = belt.getLevel().getBlockState(current);
			if (currentState.is(CBBlocks.SLIME_BELT.get())
				&& currentState.getValue(SlimeBeltBlock.PART) == BeltPart.PULLEY)
				offsets.add(segment);

			if (segment + 1 >= belt.beltLength)
				break;
			BlockPos next = SlimeBeltBlock.nextSegmentPosition(currentState, current, isStart);
			if (next == null)
				break;
			current = next;
		}

		return offsets.stream().mapToInt(Integer::intValue).toArray();
	}

	private static boolean isLastEndpoint(BlockState state) {
		BeltPart part = state.getValue(SlimeBeltBlock.PART);
		boolean positive = state.getValue(SlimeBeltBlock.HORIZONTAL_FACING).getAxisDirection()
			== net.minecraft.core.Direction.AxisDirection.POSITIVE;
		return switch (state.getValue(SlimeBeltBlock.SLOPE)) {
		case DOWNWARD -> part == BeltPart.START;
		case UPWARD -> part == BeltPart.END;
		default -> positive && part == BeltPart.END || !positive && part == BeltPart.START;
		};
	}

	private static BlockState shaftState(BlockState state) {
		Axis axis = state.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.SIDEWAYS ? Axis.Y
			: state.getValue(SlimeBeltBlock.HORIZONTAL_FACING).getClockWise().getAxis();
		return AllBlocks.SHAFT.getDefaultState().setValue(AbstractSimpleShaftBlock.AXIS, axis);
	}
}
