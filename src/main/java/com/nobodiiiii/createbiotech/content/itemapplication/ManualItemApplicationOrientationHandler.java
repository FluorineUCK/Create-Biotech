package com.nobodiiiii.createbiotech.content.itemapplication;

import java.util.Optional;
import java.util.Set;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.deployer.ManualApplicationRecipe;
import com.simibubi.create.foundation.utility.BlockHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class ManualItemApplicationOrientationHandler {
	private static final Set<ResourceLocation> CUSTOM_CONVERSIONS = Set.of(
		CreateBiotech.asResource("explosion_proof_item_vault"),
		CreateBiotech.asResource("shulker_packager_manual_only")
	);

	private ManualItemApplicationOrientationHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.isCanceled())
			return;

		Player player = event.getEntity();
		if (!player.mayBuild())
			return;

		Level level = event.getLevel();
		ItemStack heldItem = event.getItemStack();
		BlockPos pos = event.getPos();
		BlockState blockState = level.getBlockState(pos);

		if (heldItem.isEmpty() || blockState.isAir())
			return;

		Optional<RecipeHolder<ManualApplicationRecipe>> foundRecipe =
			findCreateBiotechManualApplicationRecipe(level, blockState, heldItem);
		if (foundRecipe.isEmpty())
			return;

		ManualApplicationRecipe recipe = foundRecipe.get().value();
		BlockState transformedBlock = transformBlock(recipe, blockState, player, level.random);
		if (transformedBlock.isAir())
			return;

		event.setCanceled(true);
		if (level.isClientSide()) {
			event.setCancellationResult(InteractionResult.SUCCESS);
			return;
		}

		boolean converted = applyRecipeInWorld(level, pos, blockState, transformedBlock, recipe, player, event.getHand(),
			heldItem);
		event.setCancellationResult(converted ? InteractionResult.SUCCESS : InteractionResult.FAIL);
	}

	private static Optional<RecipeHolder<ManualApplicationRecipe>> findCreateBiotechManualApplicationRecipe(Level level,
		BlockState blockState, ItemStack heldItem) {
		RecipeType<Recipe<RecipeWrapper>> type = AllRecipeTypes.ITEM_APPLICATION.getType();
		return level.getRecipeManager()
			.getAllRecipesFor(type)
			.stream()
			.filter(recipe -> CreateBiotech.MOD_ID.equals(recipe.id()
				.getNamespace()))
			.filter(recipe -> !CUSTOM_CONVERSIONS.contains(recipe.id()))
			.map(recipe -> recipe.value() instanceof ManualApplicationRecipe manualRecipe
				? Optional.of(new RecipeHolder<>(recipe.id(), manualRecipe))
				: Optional.<RecipeHolder<ManualApplicationRecipe>>empty())
			.flatMap(Optional::stream)
			.filter(recipe -> recipe.value()
				.testBlock(blockState))
			.filter(recipe -> recipe.value()
				.getRequiredHeldItem()
				.test(heldItem))
			.findFirst();
	}

	private static BlockState transformBlock(ManualApplicationRecipe recipe, BlockState sourceState, Player player,
		RandomSource random) {
		if (recipe.getRollableResults()
			.isEmpty())
			return Blocks.AIR.defaultBlockState();

		ItemStack output = recipe.getRollableResults()
			.getFirst()
			.rollOutput(random);
		if (!(output.getItem() instanceof BlockItem blockItem))
			return Blocks.AIR.defaultBlockState();

		BlockState targetState = BlockHelper.copyProperties(sourceState, blockItem.getBlock()
			.defaultBlockState());
		return applyDirectionalContext(targetState, sourceState, player);
	}

	private static BlockState applyDirectionalContext(BlockState targetState, BlockState sourceState, Player player) {
		targetState = applyFacing(targetState, sourceState, player, BlockStateProperties.FACING, false);
		targetState = applyFacing(targetState, sourceState, player, BlockStateProperties.HORIZONTAL_FACING, true);
		targetState = applyAxis(targetState, sourceState, player, BlockStateProperties.AXIS, false);
		return applyAxis(targetState, sourceState, player, BlockStateProperties.HORIZONTAL_AXIS, true);
	}

	private static BlockState applyFacing(BlockState targetState, BlockState sourceState, Player player,
		DirectionProperty property, boolean horizontalOnly) {
		if (!targetState.hasProperty(property) || sourceState.hasProperty(property))
			return targetState;

		Direction facing = sourceFacing(sourceState, player, horizontalOnly);
		if (facing == null)
			facing = playerPlacementFacing(player, horizontalOnly);
		if (horizontalOnly && facing.getAxis() == Direction.Axis.Y)
			facing = player.getDirection()
				.getOpposite();
		if (!property.getPossibleValues()
			.contains(facing))
			return targetState;
		return targetState.setValue(property, facing);
	}

	private static BlockState applyAxis(BlockState targetState, BlockState sourceState, Player player,
		EnumProperty<Direction.Axis> property, boolean horizontalOnly) {
		if (!targetState.hasProperty(property) || sourceState.hasProperty(property))
			return targetState;

		Direction.Axis axis = sourceAxis(sourceState);
		if (axis == null) {
			Direction facing = sourceFacing(sourceState, player, false);
			axis = facing == null ? playerFacing(player, horizontalOnly)
				.getAxis() : facing.getAxis();
		}
		if (horizontalOnly && axis == Direction.Axis.Y)
			axis = player.getDirection()
				.getAxis();
		if (!property.getPossibleValues()
			.contains(axis))
			return targetState;
		return targetState.setValue(property, axis);
	}

	private static Direction sourceFacing(BlockState sourceState, Player player, boolean horizontalOnly) {
		if (sourceState.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
			return sourceState.getValue(BlockStateProperties.HORIZONTAL_FACING);
		if (sourceState.hasProperty(BlockStateProperties.FACING)) {
			Direction facing = sourceState.getValue(BlockStateProperties.FACING);
			if (!horizontalOnly || facing.getAxis() != Direction.Axis.Y)
				return facing;
		}

		Direction.Axis axis = sourceAxis(sourceState);
		if (axis == null || horizontalOnly && axis == Direction.Axis.Y)
			return null;
		Direction hint = playerFacing(player, horizontalOnly);
		return Direction.fromAxisAndDirection(axis, hint.getAxisDirection());
	}

	private static Direction.Axis sourceAxis(BlockState sourceState) {
		if (sourceState.hasProperty(BlockStateProperties.AXIS))
			return sourceState.getValue(BlockStateProperties.AXIS);
		if (sourceState.hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
			return sourceState.getValue(BlockStateProperties.HORIZONTAL_AXIS);
		return null;
	}

	private static Direction playerFacing(Player player, boolean horizontalOnly) {
		if (horizontalOnly)
			return player.getDirection();
		return Direction.getNearest(player.getLookAngle().x, player.getLookAngle().y, player.getLookAngle().z);
	}

	private static Direction playerPlacementFacing(Player player, boolean horizontalOnly) {
		if (horizontalOnly)
			return player.getDirection()
				.getOpposite();
		return playerFacing(player, false).getOpposite();
	}

	private static boolean applyRecipeInWorld(Level level, BlockPos pos, BlockState oldState,
		BlockState transformedBlock, ManualApplicationRecipe recipe, Player player, InteractionHand hand,
		ItemStack heldItem) {
		level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.PLAYERS, 1, 1.45f);
		level.destroyBlock(pos, false);
		if (!level.setBlock(pos, transformedBlock, Block.UPDATE_ALL))
			return false;

		transformedBlock.getBlock()
			.setPlacedBy(level, pos, transformedBlock, player, heldItem);
		recipe.rollResults(level.random)
			.forEach(stack -> Block.popResource(level, pos, stack));
		consumeHeldItem(recipe, player, hand, heldItem);
		return true;
	}

	private static void consumeHeldItem(ManualApplicationRecipe recipe, Player player, InteractionHand hand,
		ItemStack heldItem) {
		boolean keepHeld = recipe.shouldKeepHeldItem() || player.isCreative();
		if (heldItem.has(DataComponents.UNBREAKABLE) || keepHeld)
			return;

		if (heldItem.getMaxDamage() > 0) {
			heldItem.hurtAndBreak(1, player, hand == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND
				: EquipmentSlot.MAINHAND);
			return;
		}

		ItemStack leftover = heldItem.getCraftingRemainingItem();
		heldItem.shrink(1);
		if (leftover.isEmpty())
			return;
		if (heldItem.isEmpty()) {
			player.setItemInHand(hand, leftover);
			return;
		}
		if (!player.getInventory()
			.add(leftover))
			player.drop(leftover, false);
	}
}
