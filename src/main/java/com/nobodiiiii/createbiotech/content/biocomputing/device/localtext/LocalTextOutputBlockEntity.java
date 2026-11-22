package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Owns one transient text admission; the effect does not save text or replay. */
public final class LocalTextOutputBlockEntity extends SmartBlockEntity {
    private LocalTextOutputState.State state = LocalTextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;
    final SpeakerPlaybackServer playback = new SpeakerPlaybackServer();

    public LocalTextOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new LocalTextOutputSerialBehaviour(this));
    }

    LocalTextOutputState.State state() { return state; }
    LocalTextOutputKind kind() { return ((LocalTextOutputBlock)getBlockState().getBlock()).kind(); }
    private String root() { return kind().archivePrefix + "Output"; }

    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }

    boolean publish(LocalTextOutputState.State expected, LocalTextOutputState.State next) {
        return publish(expected, next, null);
    }

    /** Consume the incoming frame and reserve its reply before dispatching an utterance. */
    boolean publish(LocalTextOutputState.State expected, LocalTextOutputState.State next, SerialDevice.Prepared exchange) {
        if (state != expected || !state.device().equals(next.device()) || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next) return true;
        state = next;
        setChanged();
        return true;
    }

    void dispatchCommitted(LocalTextOutputState.State expected, String utterance, LocalTextOutputKind.Limits limits, BooleanSupplier authorized) {
        if (state != expected || !expected.pending()) return;
        String result;
        try {
            if (!available() || !authorized.getAsBoolean()) result = "interrupted";
            else {
                var server = (ServerLevel)level;
                result = kind().dispatch(server, worldPosition, server.getGameTime(), limits, utterance);
            }
        } catch (RuntimeException failure) {
            // A platform may have partially delivered. Never replay or retry this utterance.
            result = "device_error";
        }
        if (state == expected) {
            state = LocalTextOutputState.finish(expected, result);
            setChanged();
        }
    }

    @Override public void initialize() { super.initialize(); departed = false; }

    void depart() {
        if (departed || !(level instanceof ServerLevel)) return;
        departed = true;
        reorient();
    }

    void reorient() {
        if (!(level instanceof ServerLevel) || invalid != null) return;
        cancelMedia("interrupted");
        state = LocalTextOutputState.restart(state, UUID.randomUUID());
        setChanged();
    }

    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }

    void cancelMedia(String outcome) {
        if (level instanceof ServerLevel server && kind() == LocalTextOutputKind.NARRATION)
            playback.cancel(server, worldPosition, outcome);
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        String root = root();
        tag.remove(root);
        if (!clientPacket) tag.put(root, invalid != null ? invalid.copy() : LocalTextOutputState.save(state));
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        cancelMedia("idle");
        departed = false;
        invalid = tag.contains(root()) ? tag.get(root()).copy() : null;
        state = LocalTextOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (!(invalid instanceof CompoundTag root)) return;
        try {
            state = LocalTextOutputState.load(root, UUID.randomUUID());
            invalid = null;
        } catch (IllegalArgumentException malformed) {
            // Preserve the rejected archive without exposing or executing its content.
        }
    }
}
