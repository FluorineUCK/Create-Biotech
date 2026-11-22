package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Owns generic service state and physical discovery; no recipe index or Computer RAM is consulted. */
public final class LibraryServiceBehaviour extends BlockEntityBehaviour implements CommunicationEndpoint {
    public static final BehaviourType<LibraryServiceBehaviour> TYPE = new BehaviourType<>();
    public static final String ROOT = "BiocomputingLibraryService";
    private final LibraryServiceStore store;
    private LibraryServiceWorldSource source;
    private LibraryServiceLimits sourceLimits;
    private int removalDepth;
    private String runtimeStatus = "idle", transportStatus = "";
    public LibraryServiceBehaviour(SmartBlockEntity owner) {
        super(owner); store = new LibraryServiceStore(owner::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    private LibraryServiceLimits limits() { return CBConfigs.SERVER.biocomputing.libraryService(CBConfigs.SERVER_SPEC.isLoaded()); }
    private boolean available() {
        if (removalDepth != 0 || !(blockEntity instanceof LibraryCoreHost host) || !host.libraryIdentityReady()
            || blockEntity.isRemoved() || !(blockEntity.getLevel() instanceof ServerLevel level)) return false;
        var pos = blockEntity.getBlockPos();
        // The global service loop must not run a loaded-but-dormant device or acquire a loading ticket.
        if (!level.shouldTickBlocksAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) == blockEntity;
    }
    @Override public Optional<EndpointConfiguration> advertisement() {
        return advertisements().stream().filter(EndpointConfiguration::enabled).findFirst();
    }
    @Override public List<EndpointConfiguration> advertisements() {
        if (!available()) return List.of();
        return store.snapshot(limits()).flatMap(s -> s.configuration().endpoint()).stream().toList();
    }
    @Override public AdmissionStatus receive(MessageEnvelope message) {
        if (!available()) return AdmissionStatus.UNAVAILABLE;
        var batch = LibraryServiceTicks.register(((ServerLevel)blockEntity.getLevel()).getServer(), this);
        return store.receive(message, limits(), batch);
    }
    @Override public void tick() {
        if (!available()) return;
        LibraryServiceTicks.register(((ServerLevel)blockEntity.getLevel()).getServer(), this);
    }
    void endTick(net.minecraft.server.MinecraftServer server, LibraryWriteBatch now) {
        if (!available() || ((ServerLevel)blockEntity.getLevel()).getServer() != server) return;
        var limits = limits();
        var drive = blockEntity.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveBehaviour.TYPE);
        if (drive != null && !drive.sealWrites(store, () -> source(limits).snapshot(), limits, now)) {
            runtimeStatus = "batch_pending"; return;
        }
        runtimeStatus = store.tick(() -> source(limits).snapshot(), limits, now);
        var outgoing = store.outbound(limits);
        if (outgoing.isEmpty()) { transportStatus = ""; return; }
        var reply = outgoing.orElseThrow();
        var result = EndpointTransport.deliverEnvelope(blockEntity, EnumSet.allOf(Direction.class), reply.endpoint(), reply.envelope());
        transportStatus = result.code();
        if (result.accepted()) store.acknowledge(reply, limits, () -> source(limits).snapshot());
    }
    private LibraryServiceWorldSource source(LibraryServiceLimits limits) {
        if (source == null || !limits.equals(sourceLimits)) {
            if (source != null) source.close();
            source = new LibraryServiceWorldSource(blockEntity.getLevel(), blockEntity.getBlockPos(), this::available, this::scanStructure,
                limits.archive(), CBConfigs.SERVER.biocomputing.libraryBookBytes(CBConfigs.SERVER_SPEC.isLoaded()));
            sourceLimits = limits;
        }
        return source;
    }
    private LibraryStructureScanner.ScanResult scanStructure() {
        return LibraryStructureScanner.scan(new LibraryStructureWorldView(blockEntity.getLevel()), blockEntity.getBlockPos(),
            CBConfigs.SERVER.library.libraryMaxMembers.get(), CBConfigs.SERVER.library.libraryMaxSpan.get());
    }
    /** Only an authorized local menu may call this; it is absent from the public record protocol. */
    public String configure(long revision, LibraryServiceConfiguration configuration) {
        return available() ? store.configure(revision, configuration, limits()) : "unavailable";
    }
    /** Shared device-owned grants; does not require opening the transitional autonomous RPC endpoint. */
    public LibraryServiceConfiguration.Access volumeAccess(String volume) {
        if (!available()) throw new LibraryBookAccess.Failure("unavailable");
        return store.snapshot(limits()).orElseThrow(() -> new LibraryBookAccess.Failure(
            store.diagnostic().orElse("invalid_configuration"))).configuration().access(volume);
    }
    public DataValue.RecordValue status() {
        var fields = new LinkedHashMap<String, DataValue>();
        var state = available() ? store.snapshot(limits()) : Optional.<LibraryServiceState>empty();
        fields.put("code", new DataValue.TextValue(state.isPresent() ? "ok" : store.diagnostic().orElse("unavailable")));
        fields.put("revision", new DataValue.IntegerValue(state.map(LibraryServiceState::revision).orElse(0L)));
        fields.put("configuration", state.map(s -> s.configuration().value()).orElse(LibraryServiceConfiguration.closed().value()));
        fields.put("queued", new DataValue.IntegerValue(state.map(s -> (long)s.mailbox().queued().size()).orElse(0L)));
        fields.put("runtime", new DataValue.TextValue(runtimeStatus)); fields.put("transport", new DataValue.TextValue(transportStatus));
        return new DataValue.RecordValue(fields);
    }
    /** Captures only this service, never book contents, production state, or another device's RAM. */
    public Optional<LibraryServiceItemData> captureCarriedService(UUID libraryId) {
        var data = store.write();
        return data == null ? Optional.empty() : Optional.of(new LibraryServiceItemData(libraryId, data));
    }
    /** The item placement transaction calls this only after its physical identity claim succeeds. */
    public void restoreCarriedService(LibraryServiceItemData carried) {
        closeSource(); store.read(carried.archive()); runtimeStatus = "idle"; transportStatus = "";
        blockEntity.setChanged();
    }
    /** Synchronous handoff barrier, including re-entrant entity-spawn callbacks. Never persisted. */
    static <T> T duringRemoval(net.minecraft.world.level.block.entity.BlockEntity owner, java.util.function.Supplier<T> action) {
        var service = owner instanceof SmartBlockEntity smart ? smart.getBehaviour(TYPE) : null;
        if (service == null) return action.get();
        service.removalDepth++;
        try { return action.get(); }
        finally { service.removalDepth--; }
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (!clientPacket) { closeSource(); store.read(tag.get(ROOT)); runtimeStatus = "idle"; transportStatus = ""; }
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) { tag.remove(ROOT); return; }
        var data = store.write(); if (data == null) tag.remove(ROOT); else tag.put(ROOT, data);
    }
    private void closeSource() { if (source != null) source.close(); source = null; sourceLimits = null; }
    @Override public void unload() { LibraryServiceTicks.forget(this); closeSource(); store.unload(); }
}
