package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataPort;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** One scan's world adapter, shared by authorized discovery and open-endpoint transport. */
final class LoadedEndpointWorld implements PeerDiscovery.DiscoveryWorld, EndpointTransport.World {
    private final ServerLevel level;
    private final BlockPos origin;
    private final Predicate<BlockPos> permission;
    private final Map<BlockPos, Direction> connected = new HashMap<>();
    private final Set<Direction> sourceFaces;

    LoadedEndpointWorld(ServerLevel level, BlockPos origin, Predicate<BlockPos> permission) {
        this(level, origin, permission, EnumSet.allOf(Direction.class));
    }
    LoadedEndpointWorld(ServerLevel level, BlockPos origin, Predicate<BlockPos> permission, Set<Direction> sourceFaces) {
        this.level = level; this.origin = origin.immutable(); this.permission = permission;
        this.sourceFaces = Set.copyOf(sourceFaces);
    }
    public boolean loaded(BlockPos pos) { return level.isOutsideBuildHeight(pos) || level.hasChunkAt(pos); }
    public boolean allowed(BlockPos pos) { return level.isOutsideBuildHeight(pos) || permission.test(pos); }
    private void check(BlockPos pos) {
        if (!loaded(pos)) throw new CableDiscovery.DiscoveryException("topology_unloaded");
        if (!allowed(pos)) throw new CableDiscovery.DiscoveryException("access_denied");
    }
    public CableDiscovery.Cell cell(BlockPos pos) {
        check(pos);
        if (level.isOutsideBuildHeight(pos)) return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof DataCableBlock) return DataCableBlock.cell(state);
        return new CableDiscovery.Cell(state.getBlock() instanceof DataPort ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
    }
    public boolean acceptsCable(BlockPos pos, Direction side) {
        check(pos);
        if (level.isOutsideBuildHeight(pos)) return false;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DataPort port) || !port.acceptsCable(state, side)) return false;
        if (pos.equals(origin)) return sourceFaces.contains(side);
        if (level.getCapability(CommunicationEndpoint.CAPABILITY, pos, side) == null) return false;
        connected.putIfAbsent(pos.immutable(), side); return true;
    }
    public CommunicationEndpoint endpoint(BlockPos pos) {
        var side = connected.get(pos);
        if (side == null) { check(pos); return null; }
        return endpoint(pos, side);
    }
    @Override public CommunicationEndpoint endpoint(BlockPos pos, Direction side) {
        check(pos);
        if (level.isOutsideBuildHeight(pos)) return null;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DataPort port) || !port.acceptsCable(state, side)) return null;
        return level.getCapability(CommunicationEndpoint.CAPABILITY, pos, side);
    }
    public long gameTime() { return level.getGameTime(); }
    public String dimension() { return level.dimension().location().toString(); }
}
