package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import java.util.List;
import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public class MaterialRequestPortBlock extends HorizontalDirectionalBlock
	implements IBE<MaterialRequestPortBlockEntity>, IWrenchable, ComputerShellBlock,
        com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort {
	public static final MapCodec<MaterialRequestPortBlock> CODEC = simpleCodec(MaterialRequestPortBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final DirectionProperty HORIZONTAL_FACING = FACING;
	public static final EnumProperty<ComputerShellMaterial> BASE_MATERIAL =
		ComputerShellMaterial.BASE_MATERIAL;

	public MaterialRequestPortBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)
			.setValue(BASE_MATERIAL, ComputerShellMaterial.OPAQUE));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, BASE_MATERIAL);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return null;
	}

	@Override
	public ComputerShellMaterial shellMaterial(BlockState state) {
		return state.getValue(BASE_MATERIAL);
	}

    @Override
    public boolean acceptsCable(BlockState state, Direction side) { return side == state.getValue(FACING); }

    private InteractionResult openProductionPort(Level level, BlockPos pos, Player player) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var host = getBlockEntity(level, pos);
        return host != null && player instanceof ServerPlayer serverPlayer
            && ProductionPortMenu.open(serverPlayer, host, "ok")
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		return InteractionResult.FAIL;
	}

	@Override
	public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
		if (context.getLevel().isClientSide)
			return InteractionResult.SUCCESS;
		MaterialRequestPortBlockEntity panel = getBlockEntity(context.getLevel(), context.getClickedPos());
		if (panel == null) return InteractionResult.FAIL;
		BlockState restored = state.getValue(BASE_MATERIAL) == ComputerShellMaterial.TRANSPARENT
			? CBBlocks.TRANSPARENT_COMPUTER_CASING.get().defaultBlockState()
			: CBBlocks.COMPUTER_CASING.get().defaultBlockState();
		ItemStack configured = panel.asConfiguredStack(context.getLevel().registryAccess());
		context.getLevel().setBlock(context.getClickedPos(), restored, Block.UPDATE_ALL);
		Block.popResource(context.getLevel(), context.getClickedPos(), configured);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // Sneaking with a held item can bypass block use entirely. A normal click with a native
        // logistics item is the primary entry; empty-hand sneak remains an alternate entry.
        if (stack.getItem() instanceof com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem
            || player.isShiftKeyDown() && !(stack.getItem() instanceof com.simibubi.create.content.equipment.wrench.WrenchItem))
            return openProductionPort(level, pos, player).consumesAction() ? ItemInteractionResult.SUCCESS : ItemInteractionResult.FAIL;
		return super.useItemOn(stack, state, level, pos, player, hand, hit);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, BlockHitResult hit) {
        return openProductionPort(level, pos, player);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		return blockEntity instanceof MaterialRequestPortBlockEntity panel
			? List.of(panel.asConfiguredStack(builder.getLevel().registryAccess()))
			: List.of();
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
		boolean isMoving) {
		IBE.onRemove(state, level, pos, newState);
	}

	@Override
	public Class<MaterialRequestPortBlockEntity> getBlockEntityClass() {
		return MaterialRequestPortBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends MaterialRequestPortBlockEntity> getBlockEntityType() {
		return CBBlockEntityTypes.MATERIAL_REQUEST_PORT.get();
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		return level.isClientSide ? null : IBE.super.getTicker(level, state, type);
	}
}
