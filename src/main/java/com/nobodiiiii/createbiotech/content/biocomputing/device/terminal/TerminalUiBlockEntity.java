package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/** Owns a programmable terminal's published layout and transient events, never another program's state. */
public final class TerminalUiBlockEntity extends SmartBlockEntity {
    private static final String ROOT = "BiocomputingTerminalUi";
    private static final String CC_READ = "BiocomputingTerminalCcReader";
    private TerminalUiState.State state = TerminalUiState.closed(UUID.randomUUID(), UUID.randomUUID());
    private Tag invalid;
    private boolean departed;
    private int ccReader = -1;

    public TerminalUiBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) { super(type, pos, blockState); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new TerminalUiSerialBehaviour(this)); }
    TerminalUiState.State state() { return state; }
    OptionalInt ccReader() { return ccReader < 0 ? OptionalInt.empty() : OptionalInt.of(ccReader); }

    String configureCcReader(ServerPlayer actor, OptionalInt observed, OptionalInt next) {
        if (!getBehaviour(TerminalUiSerialBehaviour.TYPE).mayManage(actor)
            || !ccInstalled()) return "access_denied";
        if (!ccReader().equals(observed)) return "changed";
        ccReader = next.orElse(-1);
        setChanged();
        return "configured";
    }

    String configureCcPublisher(ServerPlayer actor, UUID session, OptionalInt next) {
        var serial = getBehaviour(TerminalUiSerialBehaviour.TYPE);
        if (!serial.mayManage(actor) || next.isPresent() && !ccInstalled()) return "access_denied";
        var before = state;
        if (!before.session().equals(session)) return "changed";
        if (before.ccPublisher().equals(next)) return "configured";
        if (next.isPresent() && (before.controller().isPresent() || serial.binding().isPresent())) return "publisher_in_use";
        var after = TerminalUiState.grantCc(before, next, UUID.randomUUID());
        return publish(before, after) ? "configured" : "changed";
    }

    static boolean ccInstalled() { return ModList.get().isLoaded("computercraft"); }

    /** Server-thread-only call from the optional peripheral. Caller identity comes from the actual CC attachment. */
    TerminalCharacterView.Frame ccFrame(int caller, int width, int height, int offset) {
        if (!available()) throw new IllegalArgumentException("unavailable");
        if (caller < 0 || caller != ccReader) throw new IllegalArgumentException("access_denied");
        var page = state.page().orElseThrow(() -> new IllegalArgumentException("no_view"));
        return TerminalCharacterView.render(page, width, height, offset, Map.of());
    }

    /** Compare-before-publication; a stale prepared command must not change hardware. */
    boolean publish(TerminalUiState.State expected, TerminalUiState.State next) {
        return publish(expected, next, null);
    }
    boolean available() {
        if (invalid != null || departed || isRemoved() || !(level instanceof ServerLevel server)
            || !server.shouldTickBlocksAt(worldPosition) || !server.getWorldBorder().isWithinBounds(worldPosition)) return false;
        var chunk = server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(worldPosition) == this;
    }
    /** Consume the command and commit its bounded reply before replacing a page or removing an event. */
    boolean publish(TerminalUiState.State expected, TerminalUiState.State next,
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Prepared exchange) {
        if (invalid != null || departed || isRemoved() || state != expected || !state.device().equals(next.device())
            || !available()) return false;
        if (exchange != null && !exchange.publish()) return false;
        if (state == next && exchange == null) return true;
        state = next; setChanged(); return true;
    }
    @Override public void initialize() { super.initialize(); departed = false; }
    void depart() {
        if (departed || !(level instanceof ServerLevel)) return;
        departed = true;
        state = TerminalUiState.restart(state, UUID.randomUUID());
        setChanged();
    }
    void reorient() { if (!(level instanceof ServerLevel)) return; state = TerminalUiState.restart(state, UUID.randomUUID()); setChanged(); }
    @Override public void onChunkUnloaded() { depart(); super.onChunkUnloaded(); }
    @Override public void invalidate() { depart(); super.invalidate(); }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.remove(ROOT);
        tag.remove(CC_READ);
        if (!clientPacket && ccReader >= 0) tag.putInt(CC_READ, ccReader);
        if (!clientPacket) tag.put(ROOT, invalid != null ? invalid.copy() : TerminalUiState.save(state));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) return;
        departed = false;
        ccReader = tag.contains(CC_READ, Tag.TAG_INT) ? Math.max(-1, tag.getInt(CC_READ)) : -1;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        state = TerminalUiState.closed(UUID.randomUUID(), UUID.randomUUID());
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            state = TerminalUiState.load(root, UUID.randomUUID()); invalid = null;
        } catch (IllegalArgumentException rejected) { /* Preserve the exact archive and remain unavailable. */ }
    }
}
