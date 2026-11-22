package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** FACING is the visible output face. The opposite face accepts serial cable; the front must face a real note block. */
public final class MusicOutputBlock extends DirectionalBlock implements DataPort, IBE<MusicOutputBlockEntity>, IWrenchable {
    public static final MapCodec<MusicOutputBlock> CODEC = simpleCodec(MusicOutputBlock::new);
    public MusicOutputBlock(Properties properties) {
        super(properties.isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends MusicOutputBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return side == state.getValue(FACING).getOpposite(); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public Class<MusicOutputBlockEntity> getBlockEntityClass() { return MusicOutputBlockEntity.class; }
    @Override public BlockEntityType<? extends MusicOutputBlockEntity> getBlockEntityType() { return CBBlockEntityTypes.MUSIC_OUTPUT.get(); }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        return player instanceof net.minecraft.server.level.ServerPlayer server && level.getBlockEntity(pos) instanceof MusicOutputBlockEntity output
            && MusicOutputMenu.open(server, output, "ok") ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL;
    }
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState neighbor,
            net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (side == state.getValue(FACING) && level instanceof net.minecraft.server.level.ServerLevel
                && level.getBlockEntity(pos) instanceof MusicOutputBlockEntity output)
            output.observeTarget(neighbor.is(net.minecraft.world.level.block.Blocks.NOTE_BLOCK) ? "ready" : "target_missing");
        return super.updateShape(state, side, neighbor, level, pos, neighborPos);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (level.getBlockEntity(pos) instanceof MusicOutputBlockEntity output) {
            if (!state.is(next.getBlock())) output.depart();
            else if (state.getValue(FACING) != next.getValue(FACING)) output.reorient();
        }
        IBE.onRemove(state, level, pos, next);
    }
}
