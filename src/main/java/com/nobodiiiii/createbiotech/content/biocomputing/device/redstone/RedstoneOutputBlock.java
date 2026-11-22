package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** FACING is the visible output face. The opposite face accepts cable, never redstone power. */
public final class RedstoneOutputBlock extends DirectionalBlock implements DataPort, IBE<RedstoneOutputBlockEntity>, IWrenchable {
    public static final MapCodec<RedstoneOutputBlock> CODEC = simpleCodec(RedstoneOutputBlock::new);
    public RedstoneOutputBlock(Properties properties) {
        super(properties.isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends RedstoneOutputBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return side == state.getValue(FACING).getOpposite(); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public Class<RedstoneOutputBlockEntity> getBlockEntityClass() { return RedstoneOutputBlockEntity.class; }
    @Override public BlockEntityType<? extends RedstoneOutputBlockEntity> getBlockEntityType() { return CBBlockEntityTypes.REDSTONE_OUTPUT.get(); }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        return player instanceof net.minecraft.server.level.ServerPlayer server && level.getBlockEntity(pos) instanceof RedstoneOutputBlockEntity output
            && RedstoneOutputMenu.open(server, output, "ok") ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL;
    }
    @Override protected boolean isSignalSource(BlockState state) { return true; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction query) {
        // Minecraft queries from the recipient towards this source, opposite the visible outgoing face.
        if (query != state.getValue(FACING).getOpposite()) return 0;
        return level.getBlockEntity(pos) instanceof RedstoneOutputBlockEntity output ? output.appliedLevel() : 0;
    }
    @Override protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction query) { return 0; }
    @Override public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return side == state.getValue(FACING).getOpposite();
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (level.getBlockEntity(pos) instanceof RedstoneOutputBlockEntity output) {
            if (!state.is(next.getBlock())) output.depart(state.getValue(FACING));
            else if (state.getValue(FACING) != next.getValue(FACING)) output.reorient(state.getValue(FACING));
        }
        IBE.onRemove(state, level, pos, next);
    }
}
