package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Explicit grants and bounded samples; no world polling, retries, or access through null actors. */
public final class ReaderSerialBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<ReaderSerialBehaviour> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingReaderSerial";
    private final BlockReaderBlockEntity reader;
    private final SerialDevice serial;
    private UUID identity = UUID.randomUUID(), session = UUID.randomUUID();
    private long revision;
    private Optional<SerialControlAccess.Binding> binding = Optional.empty();
    private GameProfile grantor;
    private Set<String> components = Set.of();
    private Tag invalid;
    private String last = "idle";
    private boolean withdrawing;
    private long withdrawalTick = Long.MIN_VALUE;
    public ReaderSerialBehaviour(BlockReaderBlockEntity reader) {
        super(reader); this.reader = reader;
        serial = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.readerSerial(CBConfigs.SERVER_SPEC.isLoaded()), this::hardwareAvailable,
            () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), reader::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return serial; }
    public long revision() { return revision; }
    public Set<String> components() { return components; }
    public Optional<SerialControlAccess.Binding> binding() { return binding; }
    private boolean live() {
        if (reader.isRemoved() || !(reader.getLevel() instanceof ServerLevel level) || !level.shouldTickBlocksAt(reader.getBlockPos())
            || !level.getWorldBorder().isWithinBounds(reader.getBlockPos())) return false;
        var p = reader.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(p) == reader;
    }
    private boolean hardwareAvailable() { return invalid == null && live(); }
    @Override protected boolean available() { return hardwareAvailable() && binding.isPresent() && !withdrawing; }
    private SerialControlAccess world(ServerPlayer actor) { return new SerialControlAccess((ServerLevel)reader.getLevel(), reader.getBlockPos(), actor); }
    public boolean mayManage(ServerPlayer actor) {
        return live() && actor.serverLevel() == reader.getLevel() && actor.isAlive() && actor.mayBuild() && !actor.isSpectator()
            && actor.serverLevel().mayInteract(actor, reader.getBlockPos())
            && actor.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(reader.getBlockPos())) <= 64;
    }
    public List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        if (!mayManage(actor)) throw new IllegalArgumentException("access_denied");
        if (!hardwareAvailable()) throw new IllegalArgumentException("invalid_configuration");
        return world(actor).discover();
    }
    public String configure(ServerPlayer actor, long expected, Optional<SerialControlAccess.Binding> next, Set<String> ids) {
        if (!mayManage(actor)) return "access_denied";
        if (!hardwareAvailable()) return "invalid_configuration";
        if (revision != expected) return "changed";
        if (next.isPresent()) {
            if (revision == Long.MAX_VALUE) return "version_exhausted";
            if (ids.isEmpty() || !ReadComponents.registry().ids().containsAll(ids)) return "invalid_component";
            world(actor).verify(next.orElseThrow());
            if (serial.diagnostic().isPresent()) return serial.diagnostic().orElseThrow();
        } else if (!ids.isEmpty()) return "invalid_component";
        String issue = null;
        try { serial.configure("s0", reader.getBlockState().getValue(BlockReaderBlock.FACING).getOpposite(), next.isPresent()); }
        catch (IllegalArgumentException failure) {
            if (next.isPresent()) throw failure;
            issue = String.valueOf(failure.getMessage()); serial.unload();
        }
        // Even an unchanged physical port must not finish a reply prepared under a replaced grant.
        // Keep complete incoming frames/history; old sample sessions are rejected, never replayed.
        withdraw();
        binding = next; components = Set.copyOf(ids); grantor = next.isPresent() ? actor.getGameProfile() : null;
        if (revision < Long.MAX_VALUE) revision++;
        session = UUID.randomUUID(); last = issue == null ? "configured" : issue; reader.setChanged(); return "configured";
    }
    public DataValue.RecordValue address() { return BlockSnapshotReader.position(reader.getBlockPos()); }
    public DataValue.RecordValue status() {
        var status = serial.status("s0");
        String connection = binding.isEmpty() ? "closed" : withdrawing ? "configuration_changed" : status.connection();
        return new DataValue.RecordValue(Map.of("revision", integer(revision), "target", address(),
            "binding", binding.<DataValue>map(SerialControlAccess.Binding::summary).orElse(DataValue.NullValue.INSTANCE),
            "components", new DataValue.ListValue(components.stream().sorted().<DataValue>map(ReaderSerialBehaviour::text).toList()),
            "last", text(last), "connection", text(connection), "failure", text(status.failure()),
            "diagnostic", text(invalid != null ? "invalid_configuration" : serial.diagnostic().orElse(""))));
    }
    private void withdraw() {
        serial.unload(); resetActivity(); withdrawing = true; withdrawalTick = Long.MIN_VALUE;
    }
    public void reorient() { session = UUID.randomUUID(); withdraw(); reader.setChanged(); }
    @Override public void tick() {
        if (withdrawing && reader.getLevel() instanceof ServerLevel level) {
            if (withdrawalTick == Long.MIN_VALUE) withdrawalTick = level.getGameTime();
            // A local interrupted flag does not cancel bytes already in the shared medium.
            // Stay absent for one observed block-tick/Post boundary so normal topology handling
            // retires that channel. No frame history is erased and no payload is retransmitted.
            if (withdrawalTick == level.getGameTime()) { resetActivity(); return; }
            withdrawing = false;
        }
        if (available() && serial.diagnostic().isEmpty()) {
            var port = serial.view().ports().get("s0"); var side = reader.getBlockState().getValue(BlockReaderBlock.FACING).getOpposite();
            if (port == null || port.side().isEmpty()) { last = "invalid_configuration"; resetActivity(); return; }
            if (port.side().orElseThrow() != side) try { serial.configure("s0", side, true); }
            catch (IllegalArgumentException failure) { last = String.valueOf(failure.getMessage()); resetActivity(); serial.unload(); return; }
        }
        super.tick();
    }
    private ServerPlayer actor() {
        var level = (ServerLevel)reader.getLevel();
        var online = level.getServer().getPlayerList().getPlayer(grantor.getId());
        if (online != null && online.serverLevel() == level) return online;
        var offline = FakePlayerFactory.get(level, grantor);
        offline.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(reader.getBlockPos()));
        return offline;
    }
    /** The actual finite, authorized Reader path for preflighting an application input. No sample is taken here. */
    public record ApplicationPath(ReaderSamplingProtocol protocol, ReadLimits snapshot, net.minecraft.core.BlockPos target,
                                  SerialRecordCodec.Limits sender, SerialRecordCodec.Limits receiver) {}
    public Optional<ApplicationPath> applicationPath(net.minecraft.core.BlockPos target, UUID computer, String component) {
        if (!available() || !components.contains(component) || grantor == null || serial.diagnostic().isPresent()) return Optional.empty();
        var selected = binding.orElseThrow();
        if (!selected.computer().equals(computer)) return Optional.empty();
        var face = reader.getBlockState().getValue(BlockReaderBlock.FACING);
        if (!reader.getBlockPos().relative(face).equals(target)) return Optional.empty();
        var port = serial.view().ports().get("s0");
        if (port == null || !port.enabled() || port.side().filter(face.getOpposite()::equals).isEmpty()) return Optional.empty();
        var actor = actor(); var level = (ServerLevel)reader.getLevel();
        if (!level.mayInteract(actor, reader.getBlockPos())) return Optional.empty();
        try {
            var source = world(actor).verify(selected);
            var limits = CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot();
            if (!BlockSnapshotReader.mayReadTarget(level,target,face.getOpposite(),actor,limits)) return Optional.empty();
            return Optional.of(new ApplicationPath(new ReaderSamplingProtocol(identity, session, address(), components),
                limits, target,
                serial.encodingLimits("s0"), source.serialDevice().encodingLimits(selected.pin())));
        } catch (IllegalArgumentException denied) { return Optional.empty(); }
    }
    private DataValue.RecordValue sample(ServerPlayer actor, Set<String> ids) {
        var level = (ServerLevel)reader.getLevel(); var face = reader.getBlockState().getValue(BlockReaderBlock.FACING);
        var target = reader.getBlockPos().relative(face);
        var values = BlockSnapshotReader.readComponents(level, target, face.getOpposite(), actor,
            CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot(), ids);
        if (values.fields().get("error") instanceof DataValue.ErrorValue error) return new DataValue.RecordValue(Map.of("error", error));
        return new DataValue.RecordValue(Map.of("game_time", integer(level.getGameTime()), "target", BlockSnapshotReader.position(target), "components", values));
    }
    private DataValue.RecordValue sampleRange(ServerPlayer actor, String component, ReadRange range) {
        var level = (ServerLevel)reader.getLevel();
        var face = reader.getBlockState().getValue(BlockReaderBlock.FACING);
        var target = reader.getBlockPos().relative(face);
        var value = BlockSnapshotReader.readRange(level, target, face.getOpposite(), actor,
            CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot(), component, range);
        if (value.fields().get("error") instanceof DataValue.ErrorValue) return value;
        var fields = new LinkedHashMap<>(value.fields());
        fields.put("game_time", integer(level.getGameTime()));
        fields.put("target", BlockSnapshotReader.position(target));
        return new DataValue.RecordValue(fields);
    }
    @Override protected void processReceived() {
        if (binding.isEmpty() || serial.diagnostic().isPresent() || Set.of("queued", "transmitting").contains(serial.status("s0").transmit())) return;
        var receive = serial.prepareReceive("s0");
        if (!text("received").equals(receive.result().field("status"))) {
            if (serial.status("s0").receive().frames() > 0) {
                var nativeFrame = serial.prepareReceive("s0", true);
                if (text("received").equals(nativeFrame.result().field("status"))) publish(nativeFrame, "unsupported_format");
                else last = "invalid_frame";
            }
            return;
        }
        try {
            var actor = actor(); boolean authorized = false;
            try {
                if (!actor.serverLevel().mayInteract(actor, reader.getBlockPos())) throw new IllegalArgumentException("access_denied");
                if (receive.source().isPresent()) { world(actor).source(binding.orElseThrow(), receive.source().orElseThrow()); authorized = true; }
            } catch (IllegalArgumentException denied) { /* Protocol returns denied, never reads the target. */ }
            var reply = new ReaderSamplingProtocol(identity, session, address(), components)
                .respond(authorized, (DataValue.RecordValue)receive.result().field("value"),
                    CBConfigs.SERVER.biocomputing.peripherals(CBConfigs.SERVER_SPEC.isLoaded()).snapshot(),
                    ids -> sample(actor, ids), (component, range) -> sampleRange(actor, component, range));
            if (reply.isEmpty()) { publish(receive, "not_addressed"); return; }
            var value = reply.orElseThrow();
            if (value.fields().get("value") instanceof DataValue.RecordValue snapshot && snapshot.fields().get("error") instanceof DataValue.ErrorValue error)
                value = ReaderSamplingProtocol.failure(value, error.code());
            var published = publishReply(receive, value);
            if (published.isPresent()) { last = published.orElseThrow(); reader.setChanged(); }
        } catch (IllegalArgumentException failure) { last = String.valueOf(failure.getMessage()); }
    }
    static Optional<String> publishReply(SerialDevice.Prepared receive, DataValue.RecordValue value) {
        DataValue.RecordValue reply = value;
        SerialDevice.Prepared exchange;
        try { exchange = receive.replying(reply); }
        catch (SerialRecordCodec.Failure tooLarge) {
            reply = ReaderSamplingProtocol.failure(value, tooLarge.code().name().toLowerCase(Locale.ROOT));
            try { exchange = receive.replying(reply); }
            catch (SerialRecordCodec.Failure unrepresentable) {
                // A short malformed command can fit where even its error reply cannot. Reject
                // that one frame with a visible local outcome; never spin/retry or clear followers.
                return receive.publish() ? Optional.of("reply_unrepresentable") : Optional.empty();
            }
        }
        return exchange.publish() ? Optional.of(((DataValue.TextValue)reply.field("code")).value()) : Optional.empty();
    }
    private void publish(SerialDevice.Prepared exchange, String code) { if (exchange.publish()) { last = code; reader.setChanged(); } }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (clientPacket) return;
        if (invalid != null) { tag.put(ROOT, invalid.copy()); return; }
        var root = new CompoundTag(); root.putInt("Version", 1); root.putUUID("Identity", identity); root.putLong("Revision", revision);
        binding.ifPresent(value -> { root.put("Binding", value.save()); root.putUUID("Grantor", grantor.getId()); root.putString("Name", grantor.getName());
            var ids = new ListTag(); components.stream().sorted().forEach(id -> ids.add(StringTag.valueOf(id))); root.put("Components", ids); });
        var archive = serial.write(); if (archive != null) root.put("Serial", archive); tag.put(ROOT, root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        resetActivity(); binding = Optional.empty(); components = Set.of(); grantor = null; revision = 0;
        withdrawing = true; withdrawalTick = Long.MIN_VALUE;
        identity = UUID.randomUUID(); session = UUID.randomUUID(); serial.read(null); last = "idle";
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null; if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            var keys = new HashSet<>(Set.of("Version", "Identity", "Revision"));
            if (root.contains("Serial")) keys.add("Serial");
            if (root.contains("Binding")) keys.addAll(Set.of("Binding", "Grantor", "Name", "Components"));
            if (!root.getAllKeys().equals(keys) || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1
                || !root.hasUUID("Identity") || !root.contains("Revision", Tag.TAG_LONG) || root.getLong("Revision") < 0) return;
            Optional<SerialControlAccess.Binding> selected = Optional.empty(); GameProfile profile = null; var ids = new HashSet<String>();
            if (root.contains("Binding")) {
                if (root.getLong("Revision") == 0 || !root.contains("Serial") || !root.hasUUID("Grantor")
                    || !root.contains("Name", Tag.TAG_STRING) || root.getString("Name").isBlank() || root.getString("Name").length() > 16
                    || !(root.get("Components") instanceof ListTag list) || list.isEmpty() || list.getElementType() != Tag.TAG_STRING) return;
                for (Tag item : list) {
                    var name = item.getAsString(); var id = net.minecraft.resources.ResourceLocation.tryParse(name);
                    if (id == null || !id.toString().equals(name) || !ids.add(name)) return;
                }
                selected = Optional.of(SerialControlAccess.Binding.load(root.get("Binding")));
                profile = new GameProfile(root.getUUID("Grantor"), root.getString("Name"));
            }
            identity = root.getUUID("Identity"); revision = root.getLong("Revision"); binding = selected; grantor = profile;
            components = Set.copyOf(ids); serial.read(root.get("Serial")); invalid = null;
        } catch (IllegalArgumentException malformed) { /* Preserve exact archive; remain closed. */ }
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
