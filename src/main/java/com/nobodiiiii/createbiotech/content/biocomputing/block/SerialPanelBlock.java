package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery;
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

/** Full shell cell with exactly one external cable face. Interior transfers are explicit controller routes. */
public final class SerialPanelBlock extends DirectionalBlock
    implements  DataPort, IBE<SerialPanelEntity>, IWrenchable {
    public static final MapCodec<SerialPanelBlock> CODEC = simpleCodec(SerialPanelBlock::new);
    public SerialPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends SerialPanelBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }
    public Direction outward(BlockState state) { return state.getValue(FACING); }
    @Override public boolean acceptsCable(BlockState state, Direction side) { return side == outward(state); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(outward(state))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(outward(state))); }
    @Override public Class<SerialPanelEntity> getBlockEntityClass() { return SerialPanelEntity.class; }
    @Override public BlockEntityType<? extends SerialPanelEntity> getBlockEntityType() { return CBBlockEntityTypes.SERIAL_PANEL.get(); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer server)) return InteractionResult.PASS;
        var panel=getBlockEntity(level,pos);
        if(panel==null || !level.mayInteract(server,pos)) return InteractionResult.FAIL;
        panel.select(server);
        server.displayClientMessage(net.minecraft.network.chat.Component.translatable("create_biotech.serial_panel.selected",pos.toShortString(), net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.communication.face."+panel.side().getName())),false);
        return InteractionResult.CONSUME;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        IBE.onRemove(state, level, pos, replacement);
    }
}
