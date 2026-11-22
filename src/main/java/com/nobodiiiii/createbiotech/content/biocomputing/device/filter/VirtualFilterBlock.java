package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/** The output face must physically touch the filter host; the back accepts data cable. */
public final class VirtualFilterBlock extends DirectionalBlock implements DataPort, IBE<VirtualFilterBlockEntity>, IWrenchable {
    public static final MapCodec<VirtualFilterBlock> CODEC = simpleCodec(VirtualFilterBlock::new);
    public VirtualFilterBlock(Properties properties) {
        super(properties.isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends VirtualFilterBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return side == state.getValue(FACING).getOpposite(); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public Class<VirtualFilterBlockEntity> getBlockEntityClass() { return VirtualFilterBlockEntity.class; }
    @Override public BlockEntityType<? extends VirtualFilterBlockEntity> getBlockEntityType() { return CBBlockEntityTypes.VIRTUAL_FILTER.get(); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return player instanceof ServerPlayer actor && level.getBlockEntity(pos) instanceof VirtualFilterBlockEntity entity
            && VirtualFilterMenu.open(actor, entity, "ok") ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (state.is(next.getBlock()) && state.getValue(FACING) != next.getValue(FACING)
            && level.getBlockEntity(pos) instanceof VirtualFilterBlockEntity entity) entity.getBehaviour(VirtualFilterPort.TYPE).reorient();
        IBE.onRemove(state, level, pos, next);
    }
}
