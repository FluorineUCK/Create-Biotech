package com.nobodiiiii.createbiotech.mixin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryContraptionMovement;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeaseOwner;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltChain;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

@Mixin(Contraption.class)
public abstract class ContraptionMixin implements LibraryMovementLeaseOwner {
	@Unique
	private final Map<UUID, UUID> createBiotech$patternCoreMovementLeases =
		new LinkedHashMap<>();

	@WrapOperation(method = "moveBlock", at = @At(value = "INVOKE",
		target = "Lcom/tterrag/registrate/util/entry/BlockEntry;has(Lnet/minecraft/world/level/block/state/BlockState;)Z",
		ordinal = 0))
	private boolean createBiotech$recognizeBelt(BlockEntry<?> entry, BlockState state,
		Operation<Boolean> original) {
		return original.call(entry, state) || CBBeltChain.isBiotechBelt(state);
	}

	@Inject(method = "moveBelt", at = @At("HEAD"), cancellable = true)
	private void createBiotech$collectBeltChain(BlockPos pos, Queue<BlockPos> frontier,
		Set<BlockPos> visited, BlockState state, CallbackInfo ci) {
		if (!CBBeltChain.isBiotechBelt(state))
			return;
		CBBeltChain.addConnectedSegments(state, pos, frontier, visited);
		ci.cancel();
	}

	@WrapOperation(method = "removeBlocksFromWorld", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"))
	private void createBiotech$retainPatternCoreBeforeRemoval(Level level, BlockPos pos,
		Operation<Void> original) {
		LibraryContraptionMovement.removeBlockEntityWithLease(
			(Contraption) (Object) this, level, pos, () -> original.call(level, pos));
	}

	@WrapOperation(method = "addBlocksToWorld", at = @At(value = "INVOKE",
		target = "Lcom/simibubi/create/content/contraptions/MountedStorageManager;unmount("
			+ "Lnet/minecraft/world/level/Level;"
			+ "Lnet/minecraft/world/level/levelgen/structure/templatesystem/"
			+ "StructureTemplate$StructureBlockInfo;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
	private void createBiotech$claimReassembledPatternCore(MountedStorageManager storage,
		Level level, StructureBlockInfo block, BlockPos targetPos, BlockEntity blockEntity,
		Operation<Void> original) {
		original.call(storage, level, block, targetPos, blockEntity);
		LibraryContraptionMovement.claimReassembledCore(this, level, blockEntity);
	}

	@Inject(method = "writeNBT", at = @At("RETURN"))
	private void createBiotech$writePatternCoreLeases(HolderLookup.Provider registries,
		boolean spawnPacket, CallbackInfoReturnable<CompoundTag> cir) {
		if (!spawnPacket)
			LibraryContraptionMovement.writeLeases(this, cir.getReturnValue());
	}

	@Inject(method = "readNBT", at = @At("RETURN"))
	private void createBiotech$readPatternCoreLeases(Level level, CompoundTag nbt,
		boolean spawnData, CallbackInfo ci) {
		if (!spawnData)
			LibraryContraptionMovement.readAndRestoreLeases(
				(Contraption) (Object) this, this, level, nbt);
	}

	@Override
	public boolean createBiotech$addPatternCoreLease(UUID libraryId, UUID movementToken) {
		UUID existingToken = createBiotech$patternCoreMovementLeases.get(libraryId);
		if (existingToken != null)
			return existingToken.equals(movementToken);
		if (createBiotech$patternCoreMovementLeases.containsValue(movementToken))
			return false;
		createBiotech$patternCoreMovementLeases.put(libraryId, movementToken);
		return true;
	}

	@Override
	public boolean createBiotech$removePatternCoreLease(UUID libraryId, UUID movementToken) {
		if (!movementToken.equals(createBiotech$patternCoreMovementLeases.get(libraryId)))
			return false;
		createBiotech$patternCoreMovementLeases.remove(libraryId);
		return true;
	}

	@Override
	public Map<UUID, UUID> createBiotech$patternCoreLeases() {
		return Map.copyOf(createBiotech$patternCoreMovementLeases);
	}

	@Override
	public void createBiotech$clearPatternCoreLeases() {
		createBiotech$patternCoreMovementLeases.clear();
	}
}
