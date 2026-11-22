package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

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

/** Persistent literal text page owner. Serial/control adapters publish validated state; no generic world-write API. */
public final class TextOutputBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingTextOutput";
    private TextOutputState.State state = TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;

    public TextOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new TextOutputSerialBehaviour(this)); }
    TextOutputState.State state() { return state; }
    public List<String> displayLines() { return available() ? state.lines() : List.of(); }

    /** Compare-before-publication; a stale prepared command must not change hardware. */
    boolean publish(TextOutputState.State expected, TextOutputState.State next) {
        return publish(expected, next, null);
    }
    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }
    /** No observers run between consuming the frame, queueing its finite reply and replacing the page. */
    boolean publish(TextOutputState.State expected, TextOutputState.State next,
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
        state = TextOutputState.restart(state, UUID.randomUUID());
        setChanged();
    }
    void reorient() { state = TextOutputState.restart(state, UUID.randomUUID()); setChanged(); }
    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        if (!clientPacket) tag.put(ROOT, invalid != null ? invalid.copy() : TextOutputState.save(state));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        departed = false;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = TextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            state = TextOutputState.load(root, UUID.randomUUID()); invalid = null;
        } catch (IllegalArgumentException rejected) { /* Preserve the exact archive and remain unavailable. */ }
    }
}
