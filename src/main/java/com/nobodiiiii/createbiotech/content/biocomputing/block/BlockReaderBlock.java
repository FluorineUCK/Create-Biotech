package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** Front reads its one adjacent target; only the back accepts a data cable. */
public final class BlockReaderBlock extends DirectionalBlock implements DataPort, IWrenchable, com.simibubi.create.foundation.block.IBE<BlockReaderBlockEntity> {
    public static final MapCodec<BlockReaderBlock> CODEC = simpleCodec(BlockReaderBlock::new);
    public BlockReaderBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends BlockReaderBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return side == state.getValue(FACING).getOpposite(); }
    @Override public Class<BlockReaderBlockEntity> getBlockEntityClass() { return BlockReaderBlockEntity.class; }
    @Override public net.minecraft.world.level.block.entity.BlockEntityType<? extends BlockReaderBlockEntity> getBlockEntityType() {
        return com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes.BLOCK_READER.get();
    }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        return player instanceof net.minecraft.server.level.ServerPlayer server && level.getBlockEntity(pos) instanceof BlockReaderBlockEntity reader
            && com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderMenu.open(server, reader, "ok")
                ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL;
    }
    @Override protected void onRemove(BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof BlockReaderBlockEntity reader)
            reader.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSerialBehaviour.TYPE).unload();
        else if (state.is(next.getBlock()) && state.getValue(FACING) != next.getValue(FACING)
            && level.getBlockEntity(pos) instanceof BlockReaderBlockEntity reader)
            reader.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSerialBehaviour.TYPE).reorient();
        com.simibubi.create.foundation.block.IBE.onRemove(state, level, pos, next);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
