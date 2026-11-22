package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

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

/** Owns one transient burst admission; only the session and outcome persist. */
public final class ParticleOutputBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingParticleOutput";
    private ParticleOutputState.State state = ParticleOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;

    public ParticleOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new ParticleOutputSerialBehaviour(this));
    }

    ParticleOutputState.State state() { return state; }

    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }

    boolean publish(ParticleOutputState.State expected, ParticleOutputState.State next) {
        return publish(expected, next, null);
    }

    /** Consume the incoming frame and reserve its reply before settling a burst. */
    boolean publish(ParticleOutputState.State expected, ParticleOutputState.State next, SerialDevice.Prepared exchange) {
        if (state != expected || !state.device().equals(next.device()) || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next) return true;
        state = next;
        setChanged();
        return true;
    }

    void emitCommitted(ParticleOutputState.State expected, ParticleOutputBurst burst, ParticleOutputBudget budget,
            int limit, BooleanSupplier authorized) {
        if (state != expected || !expected.pending()) return;
        String result;
        try {
            if (!available() || !authorized.getAsBoolean()) result = "interrupted";
            else if (!budget.consume(level.getGameTime(), burst.count(), limit)) result = "particle_budget_exhausted";
            else result = ParticleOutputPlayback.emit((ServerLevel)level, worldPosition,
                getBlockState().getValue(ParticleOutputBlock.FACING), burst);
        } catch (RuntimeException failure) {
            // A provider may have partially dispatched the burst. Report the failure without retrying.
            result = "device_error";
        }
        if (state == expected) {
            state = ParticleOutputState.finish(expected, result);
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
        state = ParticleOutputState.restart(state, UUID.randomUUID());
        setChanged();
    }

    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        if (!clientPacket) tag.put(ROOT, invalid != null ? invalid.copy() : ParticleOutputState.save(state));
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        departed = false;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = ParticleOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (!(invalid instanceof CompoundTag root)) return;
        try {
            state = ParticleOutputState.load(root, UUID.randomUUID());
            invalid = null;
        } catch (IllegalArgumentException malformed) {
            // Preserve the rejected archive without exposing or executing its content.
        }
    }
}
