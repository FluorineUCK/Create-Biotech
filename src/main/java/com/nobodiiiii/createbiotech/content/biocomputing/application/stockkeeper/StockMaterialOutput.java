package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreateProductionReceipts;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Extension state only. StockApplicationBehaviour owns the one physical wire and proves authority. */
final class StockMaterialOutput {
    static final String ROOT = "BiocomputingStockMaterials";
    private final StockApplicationBehaviour host;
    private final StockMaterialAuthority authority = new StockMaterialAuthority();
    private StockMaterialGrant grant;
    private UUID session = UUID.randomUUID(), archivedDevice;
    private Tag retained;
    private boolean invalid, attachAttempted, storageUnavailable, suspended;
    private int activity;
    private ProductionOrderRepository.Lease lease;
    private ProductionOrderService orders;

    StockMaterialOutput(StockApplicationBehaviour host) { this.host = host; }

    // One order namespace for one physical slot, even when the first block NBT save was lost.
    static UUID deviceAt(ResourceLocation dimension, BlockPos pos) {
        String key = "create_biotech/stock-material-output/1/" + dimension + "/" + pos.asLong();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
    private UUID device() {
        return deviceAt(((ServerLevel)host.output().getLevel()).dimension().location(), host.output().getBlockPos());
    }
    private boolean archiveMatches() {
        return !invalid && (archivedDevice == null || archivedDevice.equals(device()));
    }
    UUID session() { return session; }
    StockMaterialGrant grant() {
        if (suspended || grant == null || storageUnavailable || lease == null || !archiveMatches()) return null;
        try { return lease.matchesMaterialGrant(grant.revision()) ? grant : null; }
        catch (RuntimeException unavailable) { storageUnavailable = true; return null; }
    }
    String address() { return grant == null ? "" : grant.address(); }
    String status() {
        if (!archiveMatches()) return "invalid_configuration";
        if (suspended) return "material_revocation_pending";
        if (grant == null) return storageUnavailable ? "material_storage_unavailable" : "closed";
        if (!open(false)) return "material_storage_unavailable";
        return grant() == null ? "grant_changed" : host.materialStatus(grant);
    }

    String configure(String route, GameProfile grantor, SerialControlAccess.Binding source, UUID installation, StockLinkTarget target) {
        if (!archiveMatches()) return "invalid_configuration";
        final String normalized;
        try { normalized = StockMaterialGrant.route(route); }
        catch (IllegalArgumentException invalidRoute) { return "invalid_material_address"; }
        if (!open(true)) return "material_storage_unavailable";
        try {
            // Validate the complete block witness before publishing a new durable grant revision.
            new StockMaterialGrant(1, normalized, grantor, source, installation, target);
            long revision = lease.publishMaterialGrant(OptionalLong.empty());
            grant = new StockMaterialGrant(revision, normalized, grantor, source, installation, target);
            suspended = false;
            archivedDevice = device();
            session = UUID.randomUUID();
            host.output().setChanged();
            return "materials_granted";
        } catch (RuntimeException unavailable) {
            storageUnavailable = true;
            return "material_storage_unavailable";
        }
    }
    /** Return an error rather than acknowledging a revoke that did not become durable. */
    String revoke() {
        if (grant == null) return null; // Legacy/catalogue-only output has no material authority to withdraw.
        // Stop local use even when the durable revoke cannot be acknowledged. Keep the
        // old witness for an explicit retry, and never resume just because storage recovers.
        suspended = true;
        host.output().setChanged();
        if (!archiveMatches()) return "invalid_configuration";
        if (!open(true)) return "material_storage_unavailable"; // archived device prevents first creation
        try { lease.publishMaterialGrant(OptionalLong.of(grant.revision())); }
        catch (RuntimeException unavailable) { storageUnavailable = true; return "material_storage_unavailable"; }
        grant = null;
        suspended = false;
        session = UUID.randomUUID();
        close();
        host.output().setChanged();
        return null;
    }

    private boolean open(boolean explicit) {
        if (orders != null && !storageUnavailable) return true;
        if (!archiveMatches() || !explicit && (attachAttempted || grant == null)) return false;
        close();
        attachAttempted = true;
        try {
            var level = (ServerLevel)host.output().getLevel();
            lease = CreateProductionReceipts.get(level.getServer()).repository()
                .attachMaterialOutput(device(), host.output(), explicit && archivedDevice == null).orElseThrow();
            var backend = new ProductionPackagingBackend(authority::route, host::materialAuthority, level::registryAccess, authority::inScope);
            orders = new ProductionOrderService(lease, backend, CBConfigs.SERVER.biocomputing.productionOrders(CBConfigs.SERVER_SPEC.isLoaded()));
            storageUnavailable = false;
            return true;
        } catch (RuntimeException unavailable) {
            if (lease != null) { lease.close(); lease = null; }
            storageUnavailable = true;
            return false;
        }
    }
    private void close() {
        if (lease != null) lease.close();
        lease = null; orders = null; attachAttempted = false; activity = 0;
    }
    void unload() { close(); session = UUID.randomUUID(); }
    void tick() {
        if (suspended || grant == null || ++activity < CBConfigs.SERVER.biocomputing.productionInterval(CBConfigs.SERVER_SPEC.isLoaded())) return;
        activity = 0;
        if (open(false)) {
            UUID source = grant.source().computer();
            authority.with(source, () -> host.materialAuthority(source), orders::advance, null);
        }
    }
    Optional<DataValue.RecordValue> respond(Optional<SharedSerialChannel.Port> sender, DataValue.RecordValue target, DataValue.RecordValue frame) {
        var denied = ProductionPortProtocol.unavailable(null, target, frame, "denied");
        if (denied.isEmpty() || suspended || grant == null || sender.filter(grant.source().source()::equals).isEmpty()) return denied;
        UUID source = grant.source().computer();
        if (!open(false)) return ProductionPortProtocol.unavailable(source, target, frame, "storage_unavailable");
        return authority.with(source, () -> host.materialAuthority(source, sender.orElseThrow()),
            () -> new ProductionPortProtocol(device(), session, grant.revision(), target, grant.address(), orders).respond(source, frame), denied);
    }

    void read(Tag raw) {
        unload(); invalid = false; storageUnavailable = false; suspended = false; archivedDevice = null; grant = null;
        retained = raw == null ? null : raw.copy();
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("invalid_archive");
            var keys = new HashSet<>(Set.of("Version", "Device"));
            if (tag.contains("Grant")) keys.add("Grant");
            if (tag.contains("Suspended")) keys.add("Suspended");
            if (!tag.getAllKeys().equals(keys)
                || tag.contains("Suspended") && (!tag.contains("Suspended", Tag.TAG_BYTE)
                    || tag.getByte("Suspended") < 0 || tag.getByte("Suspended") > 1)
                || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 2 || !tag.hasUUID("Device"))
                throw new IllegalArgumentException("invalid_archive");
            grant = tag.contains("Grant") ? StockMaterialGrant.load(tag.get("Grant")) : null;
            archivedDevice = tag.getUUID("Device");
            suspended = tag.getBoolean("Suspended");
        } catch (IllegalArgumentException failure) { invalid = true; }
    }
    Tag save() {
        if (invalid) return retained.copy();
        if (archivedDevice == null) return null;
        var tag = new CompoundTag(); tag.putInt("Version", 2); tag.putUUID("Device", archivedDevice);
        if (grant != null) tag.put("Grant", grant.save());
        if (suspended) tag.putBoolean("Suspended", true);
        return tag;
    }
}
