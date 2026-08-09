package com.nobodiiiii.createbiotech.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.foundation.block.CBBeltChainPlacement;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltPlacementBlock;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import com.simibubi.create.content.schematics.SchematicInstances;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.item.ItemHelper.ExtractionCountMode;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.items.ItemHandlerHelper;

@Mixin(DeployerMovementBehaviour.class)
public abstract class DeployerMovementBehaviourMixin {

	@Inject(method = "activateAsSchematicPrinter", at = @At("HEAD"), cancellable = true)
	private void createBiotech$placeBeltChain(MovementContext context, BlockPos pos, DeployerFakePlayer player,
		Level level, ItemStack filter, CallbackInfo ci) {
		if (!filter.has(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR)
			|| !filter.getOrDefault(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, false)
			|| !level.getBlockState(pos).canBeReplaced())
			return;

		SchematicLevel schematic = SchematicInstances.get(level, filter);
		if (schematic == null || !schematic.getBounds().isInside(pos.subtract(schematic.anchor)))
			return;
		BlockState state = schematic.getBlockState(pos);
		if (!(state.getBlock() instanceof CBBeltPlacementBlock belt))
			return;

		BeltPart part = state.getValue(belt.createBiotech$partProperty());
		if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY) {
			ci.cancel();
			return;
		}

		List<BlockPos> chain = CBBeltChainPlacement.readChain(schematic, pos);
		if (chain == null || chain.size() < 2) {
			ci.cancel();
			return;
		}
		int[] pulleys = CBBeltChainPlacement.collectPulleyOffsets(schematic, chain);
		CasingType[] casings = CBBeltChainPlacement.collectCasings(schematic, chain);
		if (!CBBeltChainPlacement.canPlaceChain(level, chain, pulleys)) {
			ci.cancel();
			return;
		}

		List<ItemRequirement.StackRequirement> requirements = collectRequirements(schematic, chain);
		if (requirements == null) {
			ci.cancel();
			return;
		}
		List<ItemStack> extracted = extractRequirements(context, requirements, level, pos);
		if (extracted == null) {
			ci.cancel();
			return;
		}

		List<BlockSnapshot> snapshots = new ArrayList<>(chain.size());
		for (BlockPos chainPos : chain)
			snapshots.add(BlockSnapshot.create(level.dimension(), level, chainPos));

		ci.cancel();
		boolean committed = false;
		try {
			if (!CBBeltChainPlacement.placeAtomically(level, state, chain, pulleys, casings))
				return;
			if (EventHooks.onMultiBlockPlace(player, snapshots, Direction.UP))
				return;
			committed = true;
		} finally {
			if (!committed) {
				try {
					CBBeltChainPlacement.restoreSnapshots(snapshots);
				} finally {
					refundRequirements(context, extracted, level, pos);
				}
			}
		}
	}

	private static List<ItemRequirement.StackRequirement> collectRequirements(SchematicLevel schematic,
		List<BlockPos> chain) {
		List<ItemRequirement.StackRequirement> requirements = new ArrayList<>();
		for (BlockPos chainPos : chain) {
			BlockEntity blockEntity = schematic.getBlockEntity(chainPos);
			ItemRequirement requirement = ItemRequirement.of(schematic.getBlockState(chainPos), blockEntity);
			if (requirement.isInvalid())
				return null;
			requirements.addAll(requirement.getRequiredItems());
		}
		return requirements;
	}

	/** Sequential real reservations make duplicate/tag-overlapping requirements cumulative; failures are refunded. */
	private static List<ItemStack> extractRequirements(MovementContext context,
		List<ItemRequirement.StackRequirement> requirements, Level level, BlockPos pos) {
		if (context.contraption.hasUniversalCreativeCrate)
			return List.of();
		var items = context.contraption.getStorage().getAllItems();
		List<ItemStack> extractedItems = new ArrayList<>(requirements.size());
		for (ItemRequirement.StackRequirement required : requirements) {
			if (required.usage != ItemRequirement.ItemUseType.CONSUME) {
				refundRequirements(context, extractedItems, level, pos);
				return null;
			}
			ItemStack extracted = ItemHelper.extract(items, required::matches, ExtractionCountMode.EXACTLY,
				required.stack.getCount(), false);
			if (!extracted.isEmpty())
				extractedItems.add(extracted);
			if (extracted.getCount() != required.stack.getCount()) {
				refundRequirements(context, extractedItems, level, pos);
				return null;
			}
		}
		return extractedItems;
	}

	private static void refundRequirements(MovementContext context, List<ItemStack> extracted, Level level,
		BlockPos pos) {
		var items = context.contraption.getStorage().getAllItems();
		for (ItemStack stack : extracted) {
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(items, stack, false);
			if (!remainder.isEmpty())
				Containers.dropItemStack(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, remainder);
		}
	}
}
