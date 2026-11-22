package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
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

/** Persists exactly one label at this block. Rendering never uses a spawned entity or world write. */
public final class LabelOutputBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingLabelOutput";
    private static final String RENDER_LABEL = "RenderLabel";
    private LabelOutputState.State state = LabelOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
    private String clientLabel = "";
    private LabelOffset clientOffset = LabelOffset.ZERO;
    private static final String RENDER_OFFSET = "RenderOffset";
    private Tag invalid;
    private boolean departed;

    public LabelOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }

    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new LabelOutputSerialBehaviour(this));
    }

    LabelOutputState.State state() { return state; }
    String clientLabel() { return clientLabel; }

    LabelOffset clientOffset() { return clientOffset; }

    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }

    boolean publish(LabelOutputState.State expected, LabelOutputState.State next) {
        return publish(expected, next, null);
    }

    /** Consume the incoming frame and reserve its reply before making the new label visible. */
    boolean publish(LabelOutputState.State expected, LabelOutputState.State next, SerialDevice.Prepared exchange) {
        if (state != expected || !state.device().equals(next.device()) || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next) return true;
        boolean visibleChange = !state.label().equals(next.label()) || !state.offset().equals(next.offset());
        state = next;
        setChanged();
        if (visibleChange) sendData();
        return true;
    }

    @Override public void initialize() { super.initialize(); departed = false; }

    void depart() {
        if (departed || !(level instanceof ServerLevel)) return;
        departed = true;
        reorient();
    }

    void reorient() {
        if (!(level instanceof ServerLevel) || invalid != null) return;
        state = LabelOutputState.restart(state, UUID.randomUUID());
        setChanged();
    }

    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        tag.remove(RENDER_LABEL);
        tag.remove(RENDER_OFFSET);
        if (clientPacket) {
            var service = getBehaviour(LabelOutputSerialBehaviour.TYPE);
            boolean valid = invalid == null && !departed && service != null && service.archiveMatches();
            // Send an explicit empty value as a clearing update, not an empty update packet.
            tag.putString(RENDER_LABEL, valid ? state.label() : "");
            if (valid && !state.offset().equals(LabelOffset.ZERO)) tag.put(RENDER_OFFSET, state.offset().save());
        } else {
            tag.put(ROOT, invalid != null ? invalid.copy() : LabelOutputState.save(state));
        }
    }

    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            var label = tag.getString(RENDER_LABEL);
            try {
                LabelOutputState.literalBytes(label);
                clientOffset = tag.contains(RENDER_OFFSET) ? LabelOffset.load(tag.get(RENDER_OFFSET)) : LabelOffset.ZERO;
                clientLabel = label;
            } catch (IllegalArgumentException malformed) {
                clientLabel = "";
                clientOffset = LabelOffset.ZERO;
            }
            return;
        }
        departed = false;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = LabelOutputState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (!(invalid instanceof CompoundTag root)) return;
        try {
            state = LabelOutputState.load(root, UUID.randomUUID());
            invalid = null;
        } catch (IllegalArgumentException malformed) {
            // Preserve the rejected archive without exposing or executing its content.
        }
    }
}
