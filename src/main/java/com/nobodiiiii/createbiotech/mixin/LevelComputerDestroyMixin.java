package com.nobodiiiii.createbiotech.mixin;

import javax.annotation.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelComputerDestroyMixin {
	@Inject(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("HEAD"), cancellable = true
	)
	private void createBiotech$replaceOccupiedUpperAtomically(BlockPos pos,
		BlockState replacement, int flags, int recursionLeft,
		CallbackInfoReturnable<Boolean> callback) {
		Level level = (Level) (Object) this;
		ComputerBlock.UpperReplacementResult result = ComputerBlock.replaceOccupiedUpper(level, pos,
			replacement, flags, recursionLeft);
		if (result != ComputerBlock.UpperReplacementResult.PASSTHROUGH)
			callback.setReturnValue(result == ComputerBlock.UpperReplacementResult.APPLIED);
	}

	@WrapOperation(
		method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V")
	)
	private void createBiotech$deferOccupiedComputerDrops(BlockState state, Level level,
		BlockPos pos, @Nullable BlockEntity blockEntity, @Nullable Entity breaker, ItemStack tool,
		Operation<Void> original, @Share("computerDrop") LocalRef<DeferredDrop> deferredDrop) {
		ComputerPhysicalHost occupied = level.isClientSide ? null
			: createBiotech$occupiedComputer(state, blockEntity);
		if (occupied == null) {
			original.call(state, level, pos, blockEntity, breaker, tool);
			return;
		}
		deferredDrop.set(new DeferredDrop(state, level, pos.immutable(), occupied, breaker, tool,
			original));
	}

	@WrapOperation(
		method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
	)
	private boolean createBiotech$dropOccupiedComputerAfterRemoval(Level level, BlockPos pos,
		BlockState replacement, int flags, int targetRecursionLeft, Operation<Boolean> original,
		@Share("computerDrop") LocalRef<DeferredDrop> deferredDrop) {
		boolean removed = original.call(level, pos, replacement, flags, targetRecursionLeft);
		DeferredDrop deferred = deferredDrop.get();
		if (removed && deferred != null) {
			deferredDrop.set(null);
			if (!deferred.computer().hasResidentSource()
				&& level.getBlockEntity(deferred.pos()) != deferred.computer().residentOwner()) deferred.emit();
		}
		return removed;
	}

	@Unique
	private static @Nullable ComputerPhysicalHost createBiotech$occupiedComputer(BlockState state,
		@Nullable BlockEntity blockEntity) {
		return state.getBlock() instanceof ComputerBlock
			&& blockEntity instanceof ComputerPhysicalHost computer
			&& computer.hasResidentSource() ? computer : null;
	}

	private record DeferredDrop(BlockState state, Level level, BlockPos pos,
		ComputerPhysicalHost computer, @Nullable Entity breaker, ItemStack tool,
		Operation<Void> operation) {
		private void emit() {
			operation.call(state, level, pos, computer.residentOwner(), breaker, tool);
		}
	}
}
