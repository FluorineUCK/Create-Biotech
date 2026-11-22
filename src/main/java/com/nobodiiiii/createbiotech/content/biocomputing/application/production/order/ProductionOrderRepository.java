package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Server-owned journals outlive physical ports. A lease only owns the right to drive a port. */
public final class ProductionOrderRepository implements AutoCloseable {
    private final Path directory;
    private final ProductionOrderFileJournal.Limits limits;
    private final int capacity;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public ProductionOrderRepository(Path directory, ProductionOrderFileJournal.Limits limits, int capacity) {
        this.limits = Objects.requireNonNull(limits);
        if (capacity < 1) throw new IllegalArgumentException("invalid_repository_capacity");
        this.capacity = capacity;
        try {
            Files.createDirectories(directory);
            this.directory = directory.toRealPath();
            try (var children = Files.newDirectoryStream(this.directory)) {
                for (var child : children) {
                    final UUID id;
                    try { id = UUID.fromString(child.getFileName().toString()); }
                    catch (IllegalArgumentException unrelated) { continue; }
                    if (!id.toString().equals(child.getFileName().toString())) continue;
                    if (entries.size() >= capacity) throw new IllegalStateException("repository_capacity");
                    var entry = new Entry(); entries.put(id, entry);
                    try {
                        if (!Files.isDirectory(child, NOFOLLOW_LINKS)) throw new IOException("journal_directory_required");
                        entry.file = ProductionOrderFileJournal.open(child, limits);
                        entry.cached = entry.file.load();
                    } catch (IOException | RuntimeException unavailable) { entry.fail(); }
                }
            }
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("repository_unavailable", failure);
        }
    }

    public synchronized Lease attach(UUID id, Object owner, boolean initialize) {
        requireOpen(); Objects.requireNonNull(id); Objects.requireNonNull(owner);
        var entry = entries.get(id);
        if (initialize) {
            if (entry != null) throw new IllegalStateException("journal_already_initialized");
            if (entries.size() >= capacity) throw new IllegalStateException("repository_capacity");
            entry = new Entry(); entries.put(id, entry);
            try {
                var path = directory.resolve(id.toString());
                Files.createDirectory(path); // An existing/missing archive is never an empty new journal.
                entry.file = ProductionOrderFileJournal.initialize(path, limits);
                entry.cached = entry.file.load();
            } catch (IOException | RuntimeException failure) {
                entry.fail(); throw new IllegalStateException("journal_unavailable", failure);
            }
        }
        if (entry == null) throw new IllegalStateException("journal_missing");
        entry.requireAvailable();
        if (entry.lease != null) throw new IllegalStateException("journal_already_owned");
        return entry.lease = new Lease(entry, owner);
    }

    /** Material hosts have a stable physical key even when their block NBT never reached disk. */
    public synchronized Optional<Lease> attachMaterialOutput(UUID id, Object owner, boolean explicitCreate) {
        requireOpen();
        var existing = entries.get(id);
        if (existing == null) return explicitCreate ? Optional.of(attach(id, owner, true)) : Optional.empty();
        existing.requireAvailable();
        if (existing.cached.materialGrant().isEmpty() && !existing.cached.orders().isEmpty())
            throw new IllegalStateException("journal_not_material_output");
        return Optional.of(attach(id, owner, false));
    }

    /** Trusted native receipt observer only; never exposed as a program/world capability. */
    public synchronized Journal journal(UUID id) {
        requireOpen(); var entry = entries.get(id);
        if (entry == null) throw new IllegalStateException("journal_missing");
        entry.requireAvailable(); return entry;
    }
    public synchronized Map<UUID, Snapshot> snapshots() {
        requireOpen(); var result = new LinkedHashMap<UUID, Snapshot>();
        entries.forEach((id, entry) -> { if (entry.cached != null) result.put(id, entry.cached); });
        return Collections.unmodifiableMap(result);
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("repository_closed"); }

    private final class Entry implements Journal {
        private ProductionOrderFileJournal file;
        private Snapshot cached;
        private Lease lease;
        private void requireAvailable() {
            requireOpen(); if (file == null || cached == null) throw new IllegalStateException("journal_unavailable");
        }
        private void fail() { cached = null; }
        @Override public Snapshot load() {
            synchronized (ProductionOrderRepository.this) {
                requireAvailable();
                try { return cached = file.load(); }
                catch (RuntimeException failure) { fail(); throw failure; }
            }
        }
        @Override public boolean commit(long revision, Snapshot next) {
            synchronized (ProductionOrderRepository.this) {
                requireAvailable();
                try {
                    if (!file.commit(revision, next)) return false;
                    cached = next; return true;
                } catch (RuntimeException failure) { fail(); throw failure; }
            }
        }
    }
    public final class Lease implements Journal, AutoCloseable {
        private final Entry entry;
        // Identity, not equality: two physical hosts may never share an exclusive lease.
        private final Object owner;
        private Lease(Entry entry, Object owner) { this.entry = entry; this.owner = owner; }
        private void requireLease() {
            requireOpen(); if (entry.lease != this || entry.lease.owner != owner) throw new IllegalStateException("lease_closed");
        }
        @Override public Snapshot load() {
            synchronized (ProductionOrderRepository.this) { requireLease(); return entry.load(); }
        }
        @Override public boolean commit(long revision, Snapshot next) {
            synchronized (ProductionOrderRepository.this) { requireLease(); return entry.commit(revision, next); }
        }
        /** In-process witness. All order/receipt publications preserve this durable fence. */
        public boolean matchesMaterialGrant(long grant) {
            synchronized (ProductionOrderRepository.this) {
                requireLease(); entry.requireAvailable();
                return grant > 0 && entry.cached.materialGrant().orElse(0) == grant;
            }
        }
        /** A grant or matching revoke is durable before the host may acknowledge it. */
        public long publishMaterialGrant(OptionalLong revokeExpected) {
            synchronized (ProductionOrderRepository.this) {
                requireLease();
                var old = entry.load();
                if (revokeExpected.isPresent() && old.materialGrant().orElse(0) != revokeExpected.getAsLong())
                    return 0; // Stale block NBT cannot revoke a newer binding.
                if (old.revision() == Long.MAX_VALUE) throw new IllegalStateException("version_exhausted");
                long revision = old.revision() + 1;
                long grant = revokeExpected.isPresent() ? 0 : revision;
                if (!entry.commit(old.revision(), new Snapshot(revision, old.cursor(), old.orders(), OptionalLong.of(grant))))
                    throw new IllegalStateException("storage_changed");
                return grant;
            }
        }
        @Override public void close() {
            synchronized (ProductionOrderRepository.this) { if (entry.lease == this) entry.lease = null; }
        }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        RuntimeException failure = null;
        for (var entry : entries.values()) if (entry.file != null) try { entry.file.close(); }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
}
