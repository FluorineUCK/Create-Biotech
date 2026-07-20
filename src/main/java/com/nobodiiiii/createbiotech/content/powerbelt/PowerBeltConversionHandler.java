package com.nobodiiiii.createbiotech.content.powerbelt;

import java.util.ArrayList;
import java.util.List;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class PowerBeltConversionHandler {

	/*
	 * UPDATE_MOVE_BY_PISTON prevents BeltBlock#onRemove from dismantling the rest
	 * of the chain while the conversion clears its segments.
	 */
	private static final int ATOMIC_CHAIN_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
		| Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_SUPPRESS_DROPS;

	private PowerBeltConversionHandler() {}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		Player player = event.getEntity();
		if (player.isShiftKeyDown() || !player.mayBuild())
			return;

		Level level = event.getLevel();
		BlockState clickedState = level.getBlockState(event.getPos());
		ItemStack heldItem = player.getItemInHand(event.getHand());
		if (!isConvertibleBelt(clickedState) || !AllItems.ANDESITE_ALLOY.isIn(heldItem))
			return;

		event.setCanceled(true);
		if (level.isClientSide) {
			event.setCancellationResult(InteractionResult.SUCCESS);
			return;
		}

		BeltSnapshot snapshot = BeltSnapshot.capture(level, event.getPos());
		if (snapshot == null || !replaceChain(level, snapshot)) {
			event.setCancellationResult(InteractionResult.FAIL);
			return;
		}

		level.playSound(null, event.getPos(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, .5F, 1F);
		if (!player.isCreative())
			heldItem.shrink(1);
		if (player instanceof ServerPlayer serverPlayer)
			CBAdvancements.award(serverPlayer, CBAdvancements.POWER_BELT);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	private static boolean replaceChain(Level level, BeltSnapshot snapshot) {
		if (!clearChain(level, snapshot.segments())) {
			restoreOriginalChain(level, snapshot);
			return false;
		}

		for (BeltSegment segment : snapshot.segments()) {
			boolean changed = level.setBlock(segment.pos(), createPowerBeltState(segment.state()), ATOMIC_CHAIN_FLAGS);
			if (!changed || !PowerBeltBlock.isPowerBelt(level.getBlockState(segment.pos()))) {
				restoreOriginalChain(level, snapshot);
				return false;
			}
		}

		if (!isCompletePowerBeltChain(level, snapshot)) {
			restoreOriginalChain(level, snapshot);
			return false;
		}

		/*
		 * Do not initialize or attach kinetics here. This intentionally mirrors
		 * Create's BeltConnectorItem: PowerBeltBlockEntity initializes the complete
		 * chain on its next server tick, after all block updates have settled.
		 */
		return true;
	}

	private static boolean clearChain(Level level, List<BeltSegment> segments) {
		for (BeltSegment segment : segments) {
			if (!level.isLoaded(segment.pos()))
				return false;
			boolean changed = level.setBlock(segment.pos(), Blocks.AIR.defaultBlockState(), ATOMIC_CHAIN_FLAGS);
			if (!changed && !level.isEmptyBlock(segment.pos()))
				return false;
		}
		return true;
	}

	private static BlockState createPowerBeltState(BlockState originalState) {
		return CBBlocks.POWER_BELT.get()
			.defaultBlockState()
			.setValue(PowerBeltBlock.SLOPE, originalState.getValue(BeltBlock.SLOPE))
			.setValue(PowerBeltBlock.PART, originalState.getValue(BeltBlock.PART))
			.setValue(PowerBeltBlock.HORIZONTAL_FACING, originalState.getValue(BeltBlock.HORIZONTAL_FACING))
			.setValue(PowerBeltBlock.CASING, false)
			.setValue(BlockStateProperties.WATERLOGGED, originalState.getValue(BlockStateProperties.WATERLOGGED));
	}

	private static boolean isCompletePowerBeltChain(Level level, BeltSnapshot snapshot) {
		List<BlockPos> chain = PowerBeltBlock.getBeltChain(level, snapshot.controllerPos());
		if (chain.size() != snapshot.segments().size())
			return false;

		for (int i = 0; i < chain.size(); i++) {
			BeltSegment expected = snapshot.segments()
				.get(i);
			BlockPos actualPos = chain.get(i);
			if (!actualPos.equals(expected.pos()))
				return false;
			BlockEntity blockEntity = level.getBlockEntity(actualPos);
			if (!(blockEntity instanceof PowerBeltBlockEntity))
				return false;
		}
		return true;
	}

	private static void restoreOriginalChain(Level level, BeltSnapshot snapshot) {
		clearChain(level, snapshot.segments());
		for (BeltSegment segment : snapshot.segments())
			level.setBlock(segment.pos(), segment.state(), ATOMIC_CHAIN_FLAGS);
	}

	private static boolean isConvertibleBelt(BlockState state) {
		return AllBlocks.BELT.has(state) && state.getValue(BeltBlock.SLOPE) == BeltSlope.HORIZONTAL;
	}

	private record BeltSegment(BlockPos pos, BlockState state) {}

	private record BeltSnapshot(BlockPos controllerPos, List<BeltSegment> segments) {

		private static BeltSnapshot capture(Level level, BlockPos clickedPos) {
			BeltBlockEntity clickedBelt = BeltHelper.getSegmentBE(level, clickedPos);
			if (clickedBelt == null || clickedBelt.beltLength < 2)
				return null;

			BlockPos controllerPos = clickedBelt.getController();
			BeltBlockEntity controller = BeltHelper.getSegmentBE(level, controllerPos);
			if (controller == null || !controller.isController() || controller.beltLength < 2)
				return null;

			List<BlockPos> chain = BeltBlock.getBeltChain(level, controllerPos);
			if (chain.size() != controller.beltLength || !chain.contains(clickedPos))
				return null;

			List<BeltSegment> segments = new ArrayList<>(chain.size());
			for (int i = 0; i < chain.size(); i++) {
				BlockPos pos = chain.get(i);
				if (!level.isLoaded(pos))
					return null;

				BlockState state = level.getBlockState(pos);
				BeltBlockEntity belt = BeltHelper.getSegmentBE(level, pos);
				if (!isConvertibleBelt(state) || belt == null || belt.index != i || belt.beltLength != chain.size()
					|| !controllerPos.equals(belt.getController()))
					return null;
				segments.add(new BeltSegment(pos.immutable(), state));
			}

			return new BeltSnapshot(controllerPos.immutable(), List.copyOf(segments));
		}
	}
}
