package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Physical latch owner. Serial/control adapters publish validated state; no generic world-write API. */
public final class RedstoneOutputBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingRedstoneOutput";
    private RedstoneOutputState.State state = RedstoneOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;
    private int clientApplied;

    public RedstoneOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new RedstoneOutputSerialBehaviour(this)); }
    RedstoneOutputState.State state() { return state; }
    public int appliedLevel() {
        if (invalid != null || departed || isRemoved()) return 0;
        return level != null && level.isClientSide ? clientApplied : state.applied();
    }

    /** Compare-before-publication; a stale prepared command must not change hardware. */
    boolean publish(RedstoneOutputState.State expected, RedstoneOutputState.State next) {
        return publish(expected, next, null);
    }
    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }
    /** No observers run between consuming the frame, queueing its finite reply and replacing the latch. */
    boolean publish(RedstoneOutputState.State expected, RedstoneOutputState.State next,
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Prepared exchange) {
        if (invalid != null || departed || isRemoved() || state != expected || !state.device().equals(next.device())
            || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next && exchange == null) return true;
        int before = state.applied(); state = next;
        setChanged(); sendData();
        if (before != state.applied()) notifyFront(getBlockState().getValue(RedstoneOutputBlock.FACING));
        return true;
    }

    private void notifyFront(Direction face) {
        if (level instanceof ServerLevel server && server.hasChunkAt(worldPosition.relative(face)))
            server.neighborChanged(worldPosition.relative(face), getBlockState().getBlock(), worldPosition);
    }
    @Override public void initialize() {
        super.initialize(); departed = false;
        // A restored neighbouring wire can still contain yesterday's power. Loading is not a set command.
        notifyFront(getBlockState().getValue(RedstoneOutputBlock.FACING));
    }
    void depart(Direction oldFace) {
        if (departed) return;
        departed = true;
        int before = state.applied(); state = RedstoneOutputState.restart(state, UUID.randomUUID());
        setChanged();
        if (before != 0) notifyFront(oldFace);
    }
    void reorient(Direction oldFace) {
        int before = state.applied(); state = RedstoneOutputState.restart(state, UUID.randomUUID());
        setChanged(); sendData();
        if (before != 0) notifyFront(oldFace);
    }
    @Override public void onChunkUnloaded() {
        depart(getBlockState().getValue(RedstoneOutputBlock.FACING)); super.onChunkUnloaded();
    }
    @Override public void invalidate() {
        depart(getBlockState().getValue(RedstoneOutputBlock.FACING)); super.invalidate();
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        if (clientPacket) tag.putInt("OutputLevel", appliedLevel());
        else tag.put(ROOT, invalid != null ? invalid.copy() : RedstoneOutputState.save(state));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            int value = tag.getInt("OutputLevel");
            clientApplied = value >= 0 && value <= 15 ? value : 0; return;
        }
        departed = false;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = RedstoneOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            state = RedstoneOutputState.load(root, UUID.randomUUID()); invalid = null;
        } catch (IllegalArgumentException rejected) { /* Preserve the exact archive and emit zero. */ }
    }
}
