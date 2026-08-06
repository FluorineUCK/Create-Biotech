package com.nobodiiiii.createbiotech.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltConnectorItem;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import com.simibubi.create.content.schematics.SchematicInstances;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.item.ItemHelper.ExtractionCountMode;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.items.ItemHandlerHelper;

@Mixin(DeployerMovementBehaviour.class)
public abstract class DeployerMovementBehaviourMixin {

	@Inject(method = "activateAsSchematicPrinter", at = @At("HEAD"), cancellable = true)
	private void createBiotech$placeSlimeBeltChain(MovementContext context, BlockPos pos, DeployerFakePlayer player,
		Level level, ItemStack filter, CallbackInfo ci) {
		if (!filter.has(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR))
			return;
		if (!filter.getOrDefault(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, false))
			return;
		if (!level.getBlockState(pos).canBeReplaced())
			return;

		SchematicLevel schematicWorld = SchematicInstances.get(level, filter);
		if (schematicWorld == null || !schematicWorld.getBounds().isInside(pos.subtract(schematicWorld.anchor)))
			return;
		BlockState state = schematicWorld.getBlockState(pos);
		if (!state.is(CBBlocks.SLIME_BELT.get()))
			return;

		// Middle segments and pulley markers are consumed by the endpoint's one-shot placement.
		if (state.getValue(SlimeBeltBlock.PART) == BeltPart.MIDDLE
			|| state.getValue(SlimeBeltBlock.PART) == BeltPart.PULLEY) {
			ci.cancel();
			return;
		}

		BlockPos start = findChainStart(schematicWorld, pos);
		if (start == null) {
			ci.cancel();
			return;
		}
		List<BlockPos> chain = readChain(schematicWorld, start);
		if (chain == null || chain.size() < 2 || !canPlaceChain(level, chain)) {
			ci.cancel();
			return;
		}

		List<ItemRequirement.StackRequirement> requirements = collectRequirements(schematicWorld, chain);
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
			Axis shaftAxis = state.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.SIDEWAYS ? Axis.Y
				: state.getValue(SlimeBeltBlock.HORIZONTAL_FACING).getClockWise().getAxis();
			for (BlockPos chainPos : chain) {
				BlockState chainState = schematicWorld.getBlockState(chainPos);
				if (chainState.getValue(SlimeBeltBlock.PART) != BeltPart.MIDDLE)
					level.setBlockAndUpdate(chainPos, com.simibubi.create.AllBlocks.SHAFT.getDefaultState()
						.setValue(com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock.AXIS,
							shaftAxis));
			}
			SlimeBeltConnectorItem.createBelts(level, start, chain.get(chain.size() - 1));

			for (BlockPos chainPos : chain)
				if (!level.getBlockState(chainPos).is(CBBlocks.SLIME_BELT.get())) {
					return;
				}

			if (EventHooks.onMultiBlockPlace(player, snapshots, Direction.UP))
				return;
			committed = true;
		} finally {
			if (!committed) {
				try {
					restoreSnapshots(snapshots);
				} finally {
					refundRequirements(context, extracted, level, pos);
				}
			}
		}
	}

	private static BlockPos findChainStart(SchematicLevel schematicWorld, BlockPos endpoint) {
		BlockPos current = endpoint;
		for (int i = 0; i < 1000; i++) {
			BlockState state = schematicWorld.getBlockState(current);
			if (!state.is(CBBlocks.SLIME_BELT.get()))
				return null;
			if (state.getValue(SlimeBeltBlock.PART) == BeltPart.START)
				return current;
			BlockPos previous = SlimeBeltBlock.nextSegmentPosition(state, current, false);
			if (previous == null || previous.equals(current))
				return null;
			current = previous;
		}
		return null;
	}

	private static List<BlockPos> readChain(SchematicLevel schematicWorld, BlockPos start) {
		List<BlockPos> chain = new ArrayList<>();
		BlockPos current = start;
		for (int i = 0; i < 1000; i++) {
			BlockState state = schematicWorld.getBlockState(current);
			if (!state.is(CBBlocks.SLIME_BELT.get()))
				return null;
			chain.add(current);
			if (state.getValue(SlimeBeltBlock.PART) == BeltPart.END)
				return chain;
			BlockPos next = SlimeBeltBlock.nextSegmentPosition(state, current, true);
			if (next == null || next.equals(current))
				return null;
			current = next;
		}
		return null;
	}

	private static boolean canPlaceChain(Level level, List<BlockPos> chain) {
		for (BlockPos chainPos : chain)
			if (!level.getBlockState(chainPos).canBeReplaced())
				return false;
		return true;
	}

	private static List<ItemRequirement.StackRequirement> collectRequirements(SchematicLevel schematicWorld,
		List<BlockPos> chain) {
		List<ItemRequirement.StackRequirement> requirements = new ArrayList<>();
		for (BlockPos chainPos : chain) {
			ItemRequirement requirement = ItemRequirement.of(schematicWorld.getBlockState(chainPos),
				schematicWorld.getBlockEntity(chainPos));
			if (requirement.isInvalid())
				return null;
			requirements.addAll(requirement.getRequiredItems());
		}
		return requirements;
	}

	private static List<ItemStack> extractRequirements(MovementContext context,
		List<ItemRequirement.StackRequirement> requirements, Level level, BlockPos pos) {
		if (context.contraption.hasUniversalCreativeCrate)
			return List.of();
		var items = context.contraption.getStorage().getAllItems();
		for (ItemRequirement.StackRequirement required : requirements) {
			if (required.usage != ItemRequirement.ItemUseType.CONSUME)
				return null;
			ItemStack simulated = ItemHelper.extract(items, required::matches, ExtractionCountMode.EXACTLY,
				required.stack.getCount(), true);
			if (simulated.getCount() != required.stack.getCount())
				return null;
		}

		List<ItemStack> extractedItems = new ArrayList<>(requirements.size());
		for (ItemRequirement.StackRequirement required : requirements) {
			ItemStack extracted = ItemHelper.extract(items, required::matches, ExtractionCountMode.EXACTLY,
				required.stack.getCount(), false);
			if (extracted.getCount() != required.stack.getCount()) {
				refundRequirements(context, extractedItems, level, pos);
				return null;
			}
			extractedItems.add(extracted);
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

	private static void restoreSnapshots(List<BlockSnapshot> snapshots) {
		for (int i = snapshots.size() - 1; i >= 0; i--)
			snapshots.get(i).restore(Block.UPDATE_ALL);
	}
}
