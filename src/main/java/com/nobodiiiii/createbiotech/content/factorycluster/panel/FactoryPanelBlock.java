package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public class FactoryPanelBlock extends HorizontalDirectionalBlock
	implements IBE<FactoryPanelBlockEntity>, IWrenchable {
	public static final MapCodec<FactoryPanelBlock> CODEC = simpleCodec(FactoryPanelBlock::new);
	public static final DirectionProperty HORIZONTAL_FACING = HorizontalDirectionalBlock.FACING;

	public FactoryPanelBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(HORIZONTAL_FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HORIZONTAL_FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(HORIZONTAL_FACING,
			context.getHorizontalDirection().getOpposite());
	}

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		if (context.getLevel().isClientSide)
			return InteractionResult.SUCCESS;
		Player player = context.getPlayer();
		FactoryPanelBlockEntity panel = getBlockEntity(context.getLevel(), context.getClickedPos());
		if (!(player instanceof ServerPlayer serverPlayer) || panel == null)
			return InteractionResult.FAIL;

		Optional<ClusterMember> selected = ClusterBindingSelection.resolve(serverPlayer);
		if (selected.isPresent())
			return ClusterBindingService.bind(serverPlayer, selected.get(), panel).succeeded()
				? InteractionResult.SUCCESS : InteractionResult.FAIL;
		ClusterBindingSelection.begin(serverPlayer, panel);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		return blockEntity instanceof FactoryPanelBlockEntity panel
			? List.of(panel.asConfiguredStack(builder.getLevel().registryAccess()))
			: List.of();
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
		boolean isMoving) {
		IBE.onRemove(state, level, pos, newState);
	}

	@Override
	public Class<FactoryPanelBlockEntity> getBlockEntityClass() {
		return FactoryPanelBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends FactoryPanelBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.FACTORY_PANEL.get();
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return level.isClientSide ? null : IBE.super.getTicker(level, state, type);
	}
}
