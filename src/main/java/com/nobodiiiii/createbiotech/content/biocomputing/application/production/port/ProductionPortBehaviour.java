package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreateProductionReceipts;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Physical production service owner. No old engine, cluster binding, process slot, or Computer RAM. */
public final class ProductionPortBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<ProductionPortBehaviour> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingProductionPort";
    private final SerialDevice serial;
    private UUID identity = UUID.randomUUID(), session = UUID.randomUUID();
    private long revision;
    private UUID network;
    private String address = "";
    private GameProfile grantor;
    private Optional<SerialControlAccess.Binding> binding = Optional.empty();
    private Tag invalid;
    private boolean initialized, storageAttempted, withdrawing;
    private long withdrawalTick = Long.MIN_VALUE;
    private int activity;
    private String last = "idle", storageIssue = "";
    private ProductionOrderRepository.Lease journal;
    private ProductionOrderService orders;

    public ProductionPortBehaviour(SmartBlockEntity owner) {
        super(owner);
        serial = new SerialDevice(() -> config().productionSerial(loadedConfig()), this::hardwareAvailable,
            () -> config().serialCodec(loadedConfig()), owner::setChanged);
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig config() { return CBConfigs.SERVER.biocomputing; }
    private static boolean loadedConfig() { return CBConfigs.SERVER_SPEC.isLoaded(); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return serial; }
    public long revision() { return revision; }
    private Direction face() { return blockEntity.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING); }
    private boolean live() {
        if (blockEntity.isRemoved() || !(blockEntity.getLevel() instanceof ServerLevel level)
            || !level.shouldTickBlocksAt(blockEntity.getBlockPos()) || !level.getWorldBorder().isWithinBounds(blockEntity.getBlockPos())) return false;
        var p = blockEntity.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(p) == blockEntity;
    }
    private boolean hardwareAvailable() { return invalid == null && live(); }
    @Override protected boolean available() { return hardwareAvailable() && binding.isPresent() && !withdrawing; }
    private SerialControlAccess world(ServerPlayer actor) { return new SerialControlAccess((ServerLevel)blockEntity.getLevel(), blockEntity.getBlockPos(), actor); }
    public boolean mayManage(ServerPlayer actor) {
        return live() && actor.serverLevel() == blockEntity.getLevel() && actor.isAlive() && actor.mayBuild() && !actor.isSpectator()
            && actor.serverLevel().mayInteract(actor, blockEntity.getBlockPos())
            && actor.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(blockEntity.getBlockPos())) <= 64
            && (network == null || Create.LOGISTICS.mayAdministrate(network, actor));
    }
    public List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        if (!mayManage(actor)) throw new IllegalArgumentException("access_denied");
        if (invalid != null) throw new IllegalArgumentException("invalid_configuration");
        return world(actor).discover();
    }
    public String configure(ServerPlayer actor, long expected, Optional<SerialControlAccess.Binding> next, String route) {
        if (!mayManage(actor)) return "access_denied";
        if (invalid != null) return "invalid_configuration";
        if (revision != expected) return "changed";
        if (next.isPresent() && revision == Long.MAX_VALUE) return "version_exhausted";
        UUID selected = network; String normalized = address;
        if (next.isPresent()) {
            normalized = RecipeAddressRules.requireNormalized(route);
            for (var stack : actor.getHandSlots()) if (stack.getItem() instanceof LogisticallyLinkedBlockItem) {
                var held = LogisticallyLinkedBlockItem.networkFromStack(stack);
                if (held != null) { selected = held; break; }
            }
            if (selected == null) return "network_required";
            if (!Create.LOGISTICS.mayAdministrate(selected, actor)) return "access_denied";
            world(actor).verify(next.orElseThrow());
            if (serial.diagnostic().isPresent()) return serial.diagnostic().orElseThrow();
            if (!openStorage(true)) return storageIssue;
        }
        String issue = null;
        try { serial.configure("s0", face(), next.isPresent()); }
        catch (IllegalArgumentException failure) { if (next.isPresent()) throw failure; issue = failure.getMessage(); serial.unload(); }
        withdraw(); binding = next; network = selected; address = normalized;
        if (next.isPresent()) grantor = actor.getGameProfile();
        if (revision < Long.MAX_VALUE) revision++;
        session = UUID.randomUUID(); last = issue == null ? "configured" : issue;
        blockEntity.setChanged(); return "configured";
    }
    private ServerPlayer actor() {
        var level = (ServerLevel)blockEntity.getLevel();
        var online = level.getServer().getPlayerList().getPlayer(grantor.getId());
        if (online != null && online.serverLevel() == level) return online;
        var offline = FakePlayerFactory.get(level, grantor);
        offline.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(blockEntity.getBlockPos())); return offline;
    }
    private boolean authorized(UUID source) {
        if (!hardwareAvailable() || network == null || grantor == null || !binding.map(b -> b.computer().equals(source)).orElse(false)) return false;
        var actor = actor();
        return actor.serverLevel().getServer().isSameThread() && actor.serverLevel().mayInteract(actor, blockEntity.getBlockPos())
            && Create.LOGISTICS.mayAdministrate(network, actor);
    }
    private final Backend backend = new ProductionPackagingBackend(
        source -> authorized(source) ? Optional.of(new ProductionPackagingBackend.Route(network, address, revision)) : Optional.empty(),
        () -> blockEntity.getLevel().registryAccess(), order -> true);
    private boolean openStorage(boolean explicit) {
        if (journal != null && orders != null) return true;
        if (storageAttempted && !explicit) return false;
        storageAttempted = true;
        ProductionOrderRepository.Lease opened = null;
        try {
            if (!initialized && !explicit) { storageIssue = "not_initialized"; return false; }
            opened = CreateProductionReceipts.get(((ServerLevel)blockEntity.getLevel()).getServer()).repository()
                .attach(identity, blockEntity, !initialized);
            initialized = true; blockEntity.setChanged();
            var service = new ProductionOrderService(opened, backend, config().productionOrders(loadedConfig()));
            journal = opened; orders = service; opened = null;
            storageIssue = ""; blockEntity.setChanged(); return true;
        } catch (RuntimeException failure) { storageIssue = "storage_unavailable"; return false; }
        finally { if (opened != null) try { opened.close(); } catch (RuntimeException failure) { storageIssue = "storage_unavailable"; } }
    }
    private void closeStorage() {
        if (journal != null) try { journal.close(); } catch (RuntimeException failure) { storageIssue = "storage_unavailable"; }
        journal = null; orders = null; storageAttempted = false;
    }
    private void withdraw() { serial.unload(); resetActivity(); withdrawing = true; withdrawalTick = Long.MIN_VALUE; }
    @Override public void unload() {
        closeStorage(); session = UUID.randomUUID(); activity = 0; blockEntity.setChanged(); super.unload();
    }
    @Override public void tick() {
        if (hardwareAvailable() && initialized && ++activity >= config().productionInterval(loadedConfig())) {
            activity = 0;
            if (openStorage(false)) {
                var result = orders.advance();
                if (!Set.of("idle", "waiting").contains(result.code())) last = result.order().map(Order::diagnostic).orElse(result.code());
            }
        }
        if (withdrawing && blockEntity.getLevel() instanceof ServerLevel level) {
            if (withdrawalTick == Long.MIN_VALUE) withdrawalTick = level.getGameTime();
            if (withdrawalTick == level.getGameTime()) { resetActivity(); return; }
            withdrawing = false;
        }
        if (available() && serial.diagnostic().isEmpty()) {
            var port = serial.view().ports().get("s0");
            if (port != null && port.side().isPresent() && port.side().orElseThrow() != face()) {
                try { serial.configure("s0", face(), true); }
                catch (IllegalArgumentException failure) {
                    last = failure.getMessage(); resetActivity(); serial.unload(); return;
                }
            }
        }
        super.tick();
    }
    private DataValue.RecordValue target() {
        var p = blockEntity.getBlockPos(); return new DataValue.RecordValue(Map.of("x", integer(p.getX()), "y", integer(p.getY()), "z", integer(p.getZ())));
    }
    public DataValue.RecordValue status() {
        var wire = serial.status("s0"); DataValue count = initialized || invalid != null ? DataValue.NullValue.INSTANCE : integer(0);
        if (hardwareAvailable() && initialized && openStorage(false)) try { count = integer(journal.load().orders().size()); }
        catch (RuntimeException failure) { storageIssue = "storage_unavailable"; }
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("revision", integer(revision)); fields.put("device", text(identity.toString())); fields.put("address", text(address));
        fields.put("network", text(network == null ? "" : network.toString())); fields.put("orders", count);
        fields.put("binding", binding.<DataValue>map(SerialControlAccess.Binding::summary).orElse(DataValue.NullValue.INSTANCE));
        fields.put("last", text(last)); fields.put("connection", text(binding.isEmpty() ? "closed" : wire.connection()));
        fields.put("transmit", text(wire.transmit())); fields.put("failure", text(wire.failure())); fields.put("frames", integer(wire.receive().frames()));
        fields.put("diagnostic", text(invalid != null ? "invalid_configuration" : !storageIssue.isEmpty() ? storageIssue : serial.diagnostic().orElse("")));
        return new DataValue.RecordValue(fields);
    }
    @Override protected void processReceived() {
        if (binding.isEmpty() || serial.diagnostic().isPresent() || Set.of("queued", "transmitting").contains(serial.status("s0").transmit())) return;
        var receive = serial.prepareReceive("s0");
        if (!text("received").equals(receive.result().field("status"))) {
            if (serial.status("s0").receive().frames() > 0) {
                var nativeFrame = serial.prepareReceive("s0", true);
                if (text("received").equals(nativeFrame.result().field("status")) && nativeFrame.publish()) last = "unsupported_format";
            }
            return;
        }
        UUID source = null;
        try {
            if (receive.source().isPresent()) {
                var candidate = world(actor()).source(binding.orElseThrow(), receive.source().orElseThrow());
                if (authorized(candidate)) source = candidate;
            }
        } catch (RuntimeException denied) { /* Foreign/unreachable source has no authority. */ }
        try {
            var frame = (DataValue.RecordValue)receive.result().field("value");
            var reply = openStorage(false)
                ? new ProductionPortProtocol(identity, session, revision, target(), address, orders).respond(source, frame)
                : ProductionPortProtocol.unavailable(source, target(), frame, storageIssue);
            if (reply.isEmpty()) { if (receive.publish()) last = "not_addressed"; return; }
            if (receive.replying(reply.orElseThrow()).publish()) { last = ((DataValue.TextValue)reply.orElseThrow().field("code")).value(); blockEntity.setChanged(); }
        } catch (SerialRecordCodec.Failure tooLarge) {
            // A request may already be durable even if no acknowledgement fits. Never repeat it
            // under a new identity or turn delivery failure into proof of no material operation.
            if (receive.publish()) { last = "reply_capacity"; blockEntity.setChanged(); }
        } catch (IllegalArgumentException failure) { last = failure.getMessage(); }
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (clientPacket) return;
        if (invalid != null) { tag.put(ROOT, invalid.copy()); return; }
        var root = new CompoundTag(); root.putInt("Version", 1); root.putUUID("Identity", identity); root.putLong("Revision", revision);
        root.putBoolean("Initialized", initialized); root.putString("Address", address);
        if (network != null) root.putUUID("Network", network);
        if (grantor != null) { root.putUUID("Grantor", grantor.getId()); root.putString("GrantorName", grantor.getName()); }
        binding.ifPresent(value -> root.put("Binding", value.save()));
        var saved = serial.write(); if (saved != null) root.put("Serial", saved); tag.put(ROOT, root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        closeStorage(); resetActivity(); binding = Optional.empty(); network = null; address = ""; grantor = null;
        revision = 0; initialized = false; session = UUID.randomUUID(); serial.read(null); activity = 0;
        storageIssue = ""; last = "idle"; withdrawing = false; withdrawalTick = Long.MIN_VALUE;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            var keys = new HashSet<>(Set.of("Version", "Identity", "Revision", "Initialized", "Address"));
            for (String key : List.of("Network", "Grantor", "GrantorName", "Binding", "Serial")) if (root.contains(key)) keys.add(key);
            if (!root.getAllKeys().equals(keys) || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1
                || !root.hasUUID("Identity") || !root.contains("Revision", Tag.TAG_LONG) || root.getLong("Revision") < 0
                || !root.contains("Initialized", Tag.TAG_BYTE) || root.getByte("Initialized") < 0 || root.getByte("Initialized") > 1
                || !root.contains("Address", Tag.TAG_STRING)
                || root.contains("Grantor") != root.contains("GrantorName")
                || root.contains("GrantorName") && !root.contains("GrantorName", Tag.TAG_STRING)) return;
            var selected = root.contains("Binding") ? Optional.of(SerialControlAccess.Binding.load(root.get("Binding"))) : Optional.<SerialControlAccess.Binding>empty();
            var net = root.contains("Network") ? root.getUUID("Network") : null;
            var profile = root.contains("Grantor") ? new GameProfile(root.getUUID("Grantor"), root.getString("GrantorName")) : null;
            String route = root.getString("Address");
            if (net != null && !RecipeAddressRules.requireNormalized(route).equals(route)) return;
            if (net == null && (!route.isEmpty() || profile != null)) return;
            if (selected.isPresent() && (net == null || profile == null || !root.getBoolean("Initialized") || root.getLong("Revision") == 0 || !root.contains("Serial"))) return;
            serial.read(root.get("Serial")); identity = root.getUUID("Identity"); revision = root.getLong("Revision");
            initialized = root.getBoolean("Initialized"); network = net; address = route; grantor = profile; binding = selected; invalid = null;
        } catch (IllegalArgumentException failure) { /* Preserve unknown/corrupt owner archive; do not initialize an empty replacement. */ }
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value == null ? "provider_failure" : value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
