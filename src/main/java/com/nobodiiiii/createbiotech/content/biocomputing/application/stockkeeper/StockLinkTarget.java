package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Loaded adjacency and incarnation checks shared by output, frontend and Reader provider. */
record StockLinkTarget(BlockPos position, UUID link, UUID network) {
    static BlockEntity loaded(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return null;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        var entity = chunk == null ? null : chunk.getBlockEntity(pos);
        return entity == null || entity.isRemoved() || entity.getLevel() != level ? null : entity;
    }
    static Optional<UUID> identity(PackagerLinkBlockEntity link) {
        var marker = link.getBehaviour(StockLinkIdentity.TYPE);
        return marker == null ? Optional.empty() : marker.identity();
    }
    static StockLinkTarget capture(PackagerLinkBlockEntity link) {
        var marker = link.getBehaviour(StockLinkIdentity.TYPE);
        if (marker == null || link.behaviour == null || link.behaviour.freqId == null) throw new IllegalArgumentException("link_unavailable");
        return new StockLinkTarget(link.getBlockPos(), marker.bindIdentity(), link.behaviour.freqId);
    }
    PackagerLinkBlockEntity resolve(ServerLevel level, BlockPos from) {
        return SubLevelCompat.sameSpace(level, from, position) && loaded(level, position) instanceof PackagerLinkBlockEntity found
            && found.behaviour != null && network.equals(found.behaviour.freqId) && identity(found).filter(link::equals).isPresent() ? found : null;
    }
    static List<StockApplicationBehaviour> adjacentOutputs(PackagerLinkBlockEntity link) {
        if (!(link.getLevel() instanceof ServerLevel level)) return List.of();
        var result = new ArrayList<StockApplicationBehaviour>();
        for (var direction : Direction.values()) {
            var pos = link.getBlockPos().relative(direction);
            if (!(loaded(level, pos) instanceof StockLinkOutputBlockEntity output) || !SubLevelCompat.sameSpace(level, link.getBlockPos(), pos)) continue;
            var service = output.getBehaviour(StockApplicationBehaviour.TYPE);
            if (service != null && service.targetPosition().equals(link.getBlockPos()) && service.targets(link)) result.add(service);
        }
        return List.copyOf(result);
    }
    CompoundTag save() {
        var tag = new CompoundTag(); tag.putLong("Pos", position.asLong()); tag.putUUID("Link", link); tag.putUUID("Network", network);
        return tag;
    }
    static StockLinkTarget load(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Pos","Link","Network")) || !tag.contains("Pos", Tag.TAG_LONG)
            || !tag.hasUUID("Link") || !tag.hasUUID("Network")) throw new IllegalArgumentException("invalid_link");
        return new StockLinkTarget(BlockPos.of(tag.getLong("Pos")),tag.getUUID("Link"),tag.getUUID("Network"));
    }
}
