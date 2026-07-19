package com.nobodiiiii.createbiotech.content.creeperblastchamber;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public class CreeperBlastChamberBlock extends BaseEntityBlock implements IWrenchable {
	public static final MapCodec<CreeperBlastChamberBlock> CODEC = simpleCodec(CreeperBlastChamberBlock::new);

	public static final BooleanProperty FORMED = BooleanProperty.create("formed");

	public CreeperBlastChamberBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FORMED, false));
	}

	@Override
	protected MapCodec<? extends CreeperBlastChamberBlock> codec() {
		return CODEC;
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CreeperBlastChamberBlockEntity(pos, state);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
																	BlockEntityType<T> type) {
		return createTickerHelper(type, CBBlockEntityTypes.CREEPER_BLAST_CHAMBER.get(),
			CreeperBlastChamberBlockEntity::tick);
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
		super.onPlace(state, level, pos, oldState, moved);
		if (oldState.is(state.getBlock()))
			return;
		if (level.getBlockEntity(pos) instanceof CreeperBlastChamberBlockEntity be)
			be.forceStructureCheck();
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											   BlockHitResult hit) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		if (!(level.getBlockEntity(pos) instanceof CreeperBlastChamberBlockEntity be))
			return InteractionResult.PASS;

		be.forceStructureCheck();

		if (be.isStructureValid()) {
			player.displayClientMessage(
				Component.translatable("block.create_biotech.creeper_blast_chamber.status.formed",
					be.getStructureSize(), be.getStructureSize()), true);
		} else {
			player.displayClientMessage(
				Component.translatable("block.create_biotech.creeper_blast_chamber.status.not_formed"), true);
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock())
			&& level.getBlockEntity(pos) instanceof CreeperBlastChamberBlockEntity be) {
			be.onControllerRemoved();
			be.clearCurrentVaultRoleBindings();
		}
		super.onRemove(state, level, pos, newState, moved);
	}

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		return CreeperBlastChamberBlockEntity.onStructureCasingWrenched(context.getLevel(), context.getClickedPos(),
			context.getPlayer());
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(FORMED);
	}
}
