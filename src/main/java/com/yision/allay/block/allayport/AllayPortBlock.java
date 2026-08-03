package com.yision.allay.block.allayport;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.foundation.block.CBWrenchHelper;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.yision.allay.registry.AllBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

public class AllayPortBlock extends HorizontalDirectionalBlock implements IWrenchable, IBE<AllayPortBlockEntity> {
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final MapCodec<AllayPortBlock> CODEC = simpleCodec(AllayPortBlock::new);
	private static final VoxelShape SHAPE = Shapes.or(
		box(0, 0, 0, 16, 4, 16),
		box(2, 4, 2, 14, 16, 14)
	).optimize();

	public AllayPortBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public @NotNull ItemInteractionResult useItemOn(@NotNull ItemStack stack, @NotNull BlockState state,
		@NotNull Level level, @NotNull BlockPos pos,
		@NotNull Player player, @NotNull InteractionHand hand,
		@NotNull BlockHitResult hitResult) {
		if (CBWrenchHelper.isWrench(stack))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		return onBlockEntityUseItemOn(level, pos, blockEntity -> blockEntity.use(player));
	}

	@Override
	public @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos,
		@NotNull CollisionContext context) {
		return SHAPE;
	}

	@Override
	public @NotNull VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level,
		@NotNull BlockPos pos, @NotNull CollisionContext context) {
		return SHAPE;
	}

	@Override
	public @NotNull InteractionResult onWrenched(BlockState state, UseOnContext context) {
		BlockState rotated = state.setValue(FACING, state.getValue(FACING).getOpposite());
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		if (!rotated.canSurvive(level, pos)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		level.setBlock(pos, rotated, 3);
		IWrenchable.playRotateSound(level, pos);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			IBE.onRemove(state, level, pos, newState);
			return;
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public Class<AllayPortBlockEntity> getBlockEntityClass() {
		return AllayPortBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends AllayPortBlockEntity> getBlockEntityType() {
		return AllBlockEntityTypes.ALLAY_PORT.get();
	}
}
