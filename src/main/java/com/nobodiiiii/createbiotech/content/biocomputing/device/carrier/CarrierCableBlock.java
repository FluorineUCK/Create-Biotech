package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A two-ended carrier line with one explicitly chosen input, including a right-angle bend. */
public final class CarrierCableBlock extends CarrierPathBlock {
    public static final MapCodec<CarrierCableBlock> CODEC = simpleCodec(CarrierCableBlock::new);
    public static final DirectionProperty INPUT = DirectionProperty.create("input");
    private static final VoxelShape HUB = box(6, 6, 6, 10, 10, 10);
    private static final EnumMap<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);
    static {
        for (Direction side : Direction.values()) {
            ARMS.put(side, box(side == Direction.WEST ? 0 : 6, side == Direction.DOWN ? 0 : 6,
                side == Direction.NORTH ? 0 : 6, side == Direction.EAST ? 16 : 10,
                side == Direction.UP ? 16 : 10, side == Direction.SOUTH ? 16 : 10));
        }
    }

    public CarrierCableBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(INPUT, Direction.SOUTH));
    }

    @Override protected MapCodec<? extends CarrierCableBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(INPUT);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context).setValue(INPUT, context.getClickedFace().getOpposite());
    }
    @Override public Direction input(BlockState state) { return state.getValue(INPUT); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return super.rotate(state, rotation).setValue(INPUT, rotation.rotate(input(state)));
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(output(state))).setValue(INPUT, mirror.mirror(input(state)));
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.or(HUB, ARMS.get(output(state)), ARMS.get(input(state)));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.mayBuild() || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!level.mayInteract(player, pos)) return InteractionResult.FAIL;
        var side = player.isShiftKeyDown() ? INPUT : FACING;
        var next = state.cycle(side);
        if (input(next) == output(next)) next = next.cycle(side);
        return level.setBlock(pos, next, Block.UPDATE_ALL) ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
}
