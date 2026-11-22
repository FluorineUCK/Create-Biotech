package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Server-lifetime native receipts, not an application scheduler or an implicit Computer. */
public final class CreateProductionReceipts implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CreateProductionReceipts.class);
    private static final Map<MinecraftServer, CreateProductionReceipts> SERVERS = new IdentityHashMap<>();
    private static final Runnable NONE = () -> {};
    private static final Backend NO_EFFECTS = new Backend() {
        public boolean authorized(UUID source) { return false; }
        public boolean supports(String extension, int version) { return false; }
        public Preparation prepare(UUID source, DataValue.RecordValue request) { throw new UnsupportedOperationException(); }
        public Dispatch dispatch(Operation operation, DataValue.RecordValue command) { throw new UnsupportedOperationException(); }
        public Observation observe(Operation operation, DataValue.RecordValue receipt) { throw new UnsupportedOperationException(); }
    };
    private final MinecraftServer server;
    private ProductionOrderRepository repository;
    private Map<UUID, Snapshot> indexed = Map.of();
    private Map<Integer, List<Candidate>> index = Map.of();
    private record Candidate(UUID device, Order order) {}
    private CreateProductionReceipts(MinecraftServer server) {
        this.server = server;
        try {
            repository = new ProductionOrderRepository(server.getWorldPath(LevelResource.ROOT).resolve("data/biocomputing/production"),
                config().productionArchive(loaded()), value(config().productionJournalDevices));
        } catch (RuntimeException failure) { LOG.error("Production journals unavailable; no empty replacement or dispatch", failure); }
    }
    public static void register(IEventBus bus) {
        bus.addListener((ServerStartingEvent event) -> get(event.getServer()));
        bus.addListener((ServerStoppedEvent event) -> {
            var receipts = SERVERS.remove(event.getServer());
            if (receipts != null) receipts.close();
        });
    }
    public static CreateProductionReceipts get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("server_thread_required");
        return SERVERS.computeIfAbsent(server, CreateProductionReceipts::new);
    }
    public ProductionOrderRepository repository() {
        if (repository == null) throw new IllegalStateException("storage_unavailable");
        return repository;
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig config() { return CBConfigs.SERVER.biocomputing; }
    private static boolean loaded() { return CBConfigs.SERVER_SPEC.isLoaded(); }
    private static int value(net.neoforged.neoforge.common.ModConfigSpec.IntValue value) { return loaded() ? value.get() : value.getDefault(); }
    private NativeDeliveryEvidence codec() {
        var archive = config().productionArchive(loaded());
        return new NativeDeliveryEvidence(server.registryAccess(), new NativeNbtCodec.Limits(archive.maxFileBytes(), archive.maxDecodeHeapBytes(), archive.values()),
            value(config().productionNativeRequestEntries), value(config().productionDeliveryFragments));
    }
    private ProductionOrderService service(UUID device) { return new ProductionOrderService(repository().journal(device), NO_EFFECTS, config().productionOrders(loaded())); }
    private void refreshIndex() {
        var snapshots = repository().snapshots();
        if (snapshots.equals(indexed)) return;
        var next = new HashMap<Integer, List<Candidate>>();
        snapshots.forEach((device, state) -> state.orders().values().forEach(order -> {
            if (!order.plan().extension().equals("create.packaging") || order.plan().version() != 1 || order.receipt().isEmpty()) return;
            try {
                for (int id : NativeDeliveryEvidence.orders(order).keySet())
                    next.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Candidate(device, order));
            } catch (RuntimeException invalid) { review(new Candidate(device, order), "invalid_native_receipt"); }
        }));
        next.replaceAll((id, candidates) -> List.copyOf(candidates)); index = Map.copyOf(next); indexed = snapshots;
    }
    /** Capture before native effects; the returned callback is run ONLY after non-simulated acceptance. */
    public static Runnable prepare(PackagerBlockEntity packager, ItemStack box) {
        if (!(packager.getLevel() instanceof ServerLevel level) || !PackageItem.isPackage(box) || !PackageItem.hasOrderData(box)) return NONE;
        var origin = packager.getBlockPos();
        var originChunk = level.getChunkSource().getChunkNow(origin.getX() >> 4, origin.getZ() >> 4);
        if (originChunk == null || originChunk.getBlockEntity(origin) != packager) return NONE;
        var owner = get(level.getServer());
        if (owner.repository == null) return NONE;
        owner.refreshIndex();
        var possible = owner.index.getOrDefault(PackageItem.getOrderId(box), List.of());
        if (possible.isEmpty()) return NONE;
        var networks = new HashSet<UUID>();
        for (var direction : Direction.values()) {
            var pos = packager.getBlockPos().relative(direction);
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && chunk.getBlockEntity(pos) instanceof PackagerLinkBlockEntity link
                && pos.relative(PackagerLinkBlock.getConnectedDirection(link.getBlockState()).getOpposite()).equals(origin)
                && link.getPackager() == packager && link.behaviour.freqId != null) networks.add(link.behaviour.freqId);
        }
        String address = PackageItem.getAddress(box);
        var candidates = possible.stream().filter(candidate -> {
            var command = candidate.order().plan().steps().get(candidate.order().step());
            return new DataValue.TextValue(address).equals(command.fields().get("address"))
                && networks.stream().anyMatch(network -> new DataValue.TextValue(network.toString()).equals(command.fields().get("network")));
        }).toList();
        if (candidates.isEmpty()) return NONE;
        if (candidates.size() != 1) return () -> candidates.forEach(candidate -> owner.review(candidate, "ambiguous_native_order"));
        var candidate = candidates.getFirst();
        if (candidate.order().phase() != Phase.WAITING) return NONE;
        try {
            var codec = owner.codec(); var fragment = codec.capture(box);
            return () -> owner.accept(candidate, fragment, codec);
        } catch (RuntimeException invalid) { return () -> owner.review(candidate, "invalid_delivery_evidence"); }
    }
    private void accept(Candidate candidate, DeliveryLedger.Fragment<com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey> fragment,
                        NativeDeliveryEvidence codec) {
        try {
            var current = repository().journal(candidate.device()).load().orders().get(candidate.order().key());
            if (!candidate.order().equals(current)) { review(candidate, "delivery_state_changed"); return; }
            var ledger = codec.restore(current); var outcome = ledger.accept(fragment);
            if (outcome == DeliveryLedger.Outcome.DUPLICATE) return;
            if (outcome != DeliveryLedger.Outcome.ACCEPTED) { review(candidate, "delivery_" + outcome.name().toLowerCase(Locale.ROOT)); return; }
            var result = service(candidate.device()).recordEvidence(current, codec.encode(ledger), ledger.complete() ? Phase.COMPLETE : Phase.WAITING,
                ledger.complete() ? "materials_delivered" : "materials_partially_delivered");
            if (!result.code().equals("progressed")) review(candidate, "delivery_" + result.code());
        } catch (RuntimeException failure) { review(candidate, "invalid_delivery_evidence"); }
    }
    private void review(Candidate candidate, String code) {
        if (candidate.order().phase() != Phase.WAITING) return;
        try {
            var result = service(candidate.device()).recordEvidence(candidate.order(), candidate.order().result(), Phase.REVIEW_REQUIRED, code);
            if (!result.code().equals("progressed")) LOG.error("Production delivery evidence unavailable: device={}, order={}, reason={}, save={}",
                candidate.device(), candidate.order().key(), code, result.code());
        } catch (RuntimeException failure) { LOG.error("Production delivery evidence unavailable: device={}, order={}", candidate.device(), candidate.order().key(), failure); }
    }
    @Override public void close() { if (repository != null) { repository.close(); repository = null; } }
}
