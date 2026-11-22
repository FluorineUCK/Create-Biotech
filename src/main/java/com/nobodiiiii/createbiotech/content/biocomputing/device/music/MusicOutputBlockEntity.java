package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Owns one committed note slot and its result. No target NBT or BlockState is written. */
public final class MusicOutputBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingMusicOutput";
    private MusicOutputState.State state = MusicOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;
    private String observedTarget;

    public MusicOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new MusicOutputSerialBehaviour(this)); }
    MusicOutputState.State state() { return state; }
    BlockPos target() { return worldPosition.relative(getBlockState().getValue(MusicOutputBlock.FACING)); }
    String targetStatus() {
        if (!(level instanceof ServerLevel server)) return "target_unloaded";
        String status = MusicOutputPlayback.targetStatus(server, target());
        return status.equals("ready") && !SubLevelCompat.sameSpace(server, worldPosition, target())
            ? "target_missing" : status;
    }
    void refreshTarget() { observeTarget(targetStatus()); }
    void observeTarget(String status) {
        if (observedTarget != null && !observedTarget.equals(status) && !departed && invalid == null) {
            state = MusicOutputState.restart(state, UUID.randomUUID());
            setChanged();
        }
        observedTarget = status;
    }

    void playCommitted(MusicOutputState.State accepted, java.util.function.BooleanSupplier authorized) {
        if (state != accepted || accepted.pending().isEmpty() || !available()) return;
        var server = (ServerLevel)level;
        String result = targetStatus();
        try {
            if (result.equals("ready")) result = MusicOutputPlayback.play(server, target(), accepted.pending().orElseThrow(),
                () -> state == accepted && available() && targetStatus().equals("ready") && authorized.getAsBoolean());
        } catch (RuntimeException failure) {
            // A provider or event may already have applied an effect. Never re-run the slot.
            result = "device_error";
        }
        if (state == accepted) {
            state = MusicOutputState.complete(accepted, result);
            setChanged();
        }
    }

    /** Compare-before-publication; a stale prepared command must not change hardware. */
    boolean publish(MusicOutputState.State expected, MusicOutputState.State next) {
        return publish(expected, next, null);
    }
    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }
    /** No observers run between consuming the frame, queueing its finite reply and committing the note slot. */
    boolean publish(MusicOutputState.State expected, MusicOutputState.State next,
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Prepared exchange) {
        if (invalid != null || departed || isRemoved() || state != expected || !state.device().equals(next.device())
            || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next && exchange == null) return true;
        state = next; setChanged(); return true;
    }
    @Override public void initialize() { super.initialize(); departed = false; }
    void depart() {
        if (departed) return;
        departed = true;
        state = MusicOutputState.restart(state, UUID.randomUUID());
        setChanged();
    }
    void reorient() { observedTarget = null; state = MusicOutputState.restart(state, UUID.randomUUID()); setChanged(); }
    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        if (!clientPacket) tag.put(ROOT, invalid != null ? invalid.copy() : MusicOutputState.save(state));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        departed = false; observedTarget = null;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = MusicOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            state = MusicOutputState.load(root, UUID.randomUUID()); invalid = null;
        } catch (IllegalArgumentException rejected) { /* Preserve the exact archive and remain unavailable. */ }
    }
}
