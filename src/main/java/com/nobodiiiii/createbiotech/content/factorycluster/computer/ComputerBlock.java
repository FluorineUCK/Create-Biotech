package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ComputerBlock extends Block implements IBE<ComputerBlockEntity> {
	public static final MapCodec<ComputerBlock> CODEC = simpleCodec(ComputerBlock::new);

	public ComputerBlock(Properties properties) { super(properties); }
	@Override protected MapCodec<? extends Block> codec() { return CODEC; }

	@Override
	protected ItemInteractionResult useItemOn(ItemStack ignored, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		ItemStack held = player.getItemInHand(hand);
		if (!CapturedEntityBoxItem.isBox(held))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!CapturedEntityBoxHelper.hasCapturedEntity(held))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide) return ItemInteractionResult.sidedSuccess(true);
		if (!(player instanceof ServerPlayer serverPlayer)
			|| !(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
			return ItemInteractionResult.FAIL;
		return computer.installResident(serverPlayer, hand) == ComputerInstallResult.SUCCESS
			? ItemInteractionResult.sidedSuccess(false) : ItemInteractionResult.FAIL;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
		boolean isMoving) {
		ComputerBlockEntity oldComputer = level.getBlockEntity(pos) instanceof ComputerBlockEntity computer
			? computer : null;
		if (ComputerResidentLifecycle.onRemove(state, level, pos, newState, isMoving, oldComputer)
			== ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER) {
			IBE.onRemove(state, level, pos, newState);
			super.onRemove(state, level, pos, newState, isMoving);
		}
	}

	@Override public Class<ComputerBlockEntity> getBlockEntityClass() { return ComputerBlockEntity.class; }
	@Override public BlockEntityType<? extends ComputerBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.COMPUTER.get();
	}
	@Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,
		BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : IBE.super.getTicker(level, state, type);
	}
}
