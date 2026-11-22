package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** A physically directed carrier link, never a DataPort or a redstone component. */
public abstract class CarrierPathBlock extends DirectionalBlock {
    protected CarrierPathBlock(Properties properties) {
        super(properties.isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    public static Direction output(BlockState state) { return state.getValue(FACING); }
    public Direction input(BlockState state) { return output(state).getOpposite(); }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Clicking the upstream device face places this link pointing away from the upstream device.
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(output(state))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(output(state))); }
}
