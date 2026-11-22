package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Trusted server adapter context, scoped to one capture. Never passed into the program or saved. */
public final class BlockReadContext {
    private final ServerLevel level;
    private final BlockPos target;
    private final Direction face;
    private final ServerPlayer actor;
    private final BlockState state;
    private final BlockEntity entity;
    private final ReadLimits limits;
    private final boolean allowSerializedData;
    private DataValue publicData, serializedData;

    public BlockReadContext(ServerLevel level, BlockPos target, Direction face, ServerPlayer actor,
                            BlockState state, BlockEntity entity, ReadLimits limits, boolean allowSerializedData) {
        this.level = Objects.requireNonNull(level); this.target = Objects.requireNonNull(target).immutable();
        this.face = Objects.requireNonNull(face); this.actor = Objects.requireNonNull(actor);
        this.state = Objects.requireNonNull(state); this.entity = entity;
        this.limits = Objects.requireNonNull(limits); this.allowSerializedData = allowSerializedData;
    }
    public ServerLevel level() { return level; }
    public BlockPos target() { return target; }
    public Direction face() { return face; }
    public ServerPlayer actor() { return actor; }
    public BlockState state() { return state; }
    public BlockEntity entity() { return entity; }
    public ReadLimits limits() { return limits; }

    /** Public client synchronization data, not a claim to contain the full persistent state. */
    public DataValue publicData() {
        if (publicData == null) publicData = entity == null ? CapabilitySnapshotValues.unsupported()
            : CapabilitySnapshotValues.guarded(() -> NbtSnapshotValues.copy(entity.getUpdateTag(level.registryAccess()), limits), limits);
        return publicData;
    }
    /** Authorized serialization into a data copy, never a write back or a client-selected object reference. */
    public DataValue serializedData() {
        if (serializedData == null) serializedData = !allowSerializedData
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockLinkOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterBlockEntity
            || entity instanceof com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlockEntity
            || entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart
                && smart.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortBehaviour.TYPE) != null
            ? CapabilitySnapshotValues.error("access_denied")
            : entity == null ? CapabilitySnapshotValues.unsupported()
            : CapabilitySnapshotValues.guarded(() -> NbtSnapshotValues.copy(entity.saveWithFullMetadata(level.registryAccess()), limits), limits);
        return serializedData;
    }
}
