package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;
import java.util.Set;

/** One direct slot. The native filter is never copied into, or modified by, this state. */
final class VirtualFilterSlot {
    private final Set<VirtualFilterRule.Domain> domains;
    private final boolean nativeOccupied;
    private final long version;
    private final Optional<Snapshot> snapshot;

    private record Snapshot(DataValue.RecordValue data, VirtualFilterRule compiled) {}
    record Change(VirtualFilterSlot slot, String code) {}

    private VirtualFilterSlot(Set<VirtualFilterRule.Domain> domains, boolean nativeOccupied, long version, Optional<Snapshot> snapshot) {
        this.domains = Set.copyOf(domains);
        this.nativeOccupied = nativeOccupied;
        this.version = version;
        this.snapshot = snapshot;
    }

    static VirtualFilterSlot empty(VirtualFilterRule.Domain domain, boolean nativeOccupied) {
        return empty(Set.of(domain), nativeOccupied);
    }

    static VirtualFilterSlot empty(Set<VirtualFilterRule.Domain> domains, boolean nativeOccupied) {
        return new VirtualFilterSlot(domains, nativeOccupied, 0, Optional.empty());
    }

    static VirtualFilterSlot restore(Set<VirtualFilterRule.Domain> domains, long version, Optional<DataValue.RecordValue> data) {
        if (version < 0 || version == 0 && data.isPresent()) throw new IllegalArgumentException("invalid_filter_archive");
        return new VirtualFilterSlot(domains, false, version, data.map(value -> {
            // A server datapack may be temporarily absent. Keep the snapshot but recheck its tag references before matching.
            var compiled = VirtualFilterRule.compile(value, VirtualFilterRule.MAX_MATCHERS, (d, k, id) -> true);
            if (!domains.contains(compiled.domain())) throw new IllegalArgumentException("invalid_filter_archive");
            return new Snapshot(value, compiled);
        }));
    }

    long version() { return version; }
    boolean nativeOccupied() { return nativeOccupied; }
    Optional<VirtualFilterRule> rule() { return snapshot.map(Snapshot::compiled); }
    Optional<DataValue.RecordValue> data() { return snapshot.map(Snapshot::data); }

    VirtualFilterSlot nativeChanged(boolean occupied) {
        if (nativeOccupied == occupied) return this;
        // Native interactions must still succeed when the virtual version space is exhausted.
        long next = version == Long.MAX_VALUE ? version : version + 1;
        return new VirtualFilterSlot(domains, occupied, next, Optional.empty());
    }

    Change set(long observed, DataValue.RecordValue data, int limit, VirtualFilterRule.Registry registry) {
        String conflict = check(observed);
        if (conflict != null) return new Change(this, conflict);
        try {
            var rule = VirtualFilterRule.compile(data, limit, registry);
            if (!domains.contains(rule.domain())) return new Change(this, "filter_domain_mismatch");
            return new Change(new VirtualFilterSlot(domains, false, version + 1, Optional.of(new Snapshot(data, rule))), "applied");
        } catch (IllegalArgumentException invalid) { return new Change(this, invalid.getMessage()); }
    }

    Change clear(long observed) {
        String conflict = check(observed);
        if (conflict != null) return new Change(this, conflict);
        return snapshot.isEmpty() ? new Change(this, "empty")
            : new Change(new VirtualFilterSlot(domains, false, version + 1, Optional.empty()), "cleared");
    }

    private String check(long observed) {
        if (nativeOccupied) return "filter_slot_occupied";
        if (observed != version) return "version_conflict";
        return version == Long.MAX_VALUE ? "version_exhausted" : null;
    }
}
