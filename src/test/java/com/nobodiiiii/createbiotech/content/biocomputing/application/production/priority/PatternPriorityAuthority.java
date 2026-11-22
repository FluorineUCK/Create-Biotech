package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternPriorityAccess;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded private qualifications copied from a coherent authoritative production snapshot. */
public final class PatternPriorityAuthority implements PatternPriorityAccess {
    public static final int MAX_LEASES = 256;
    private final Set<Grant> grants;

    private PatternPriorityAuthority(Set<Grant> grants) { this.grants = Set.copyOf(grants); }
    public static PatternPriorityAuthority empty() { return new PatternPriorityAuthority(Set.of()); }

    public static PatternPriorityAuthority fromAuthoritativeState(PriorityReservationSource source) {
        Objects.requireNonNull(source, "source");
        UUID owner = Objects.requireNonNull(source.clusterId(), "owner");
        var associations = source.coordinationAssociations();
        if (associations.size() > MAX_LEASES) throw new IllegalStateException("coordination snapshot exceeds bound");
        var grants = new HashSet<Grant>();
        for (var entry : associations.entrySet()) {
            var association = entry.getValue();
            if (!entry.getKey().equals(association.processId()))
                throw new IllegalArgumentException("coordination association index mismatch");
            if (qualifies(source, association))
                grants.add(new Grant(owner, association.libraryId(), association.patternId(), association.catalogGeneration()));
        }
        return new PatternPriorityAuthority(grants);
    }

    /** Qualification only. Explicit association admission must also validate its intended goal pattern. */
    public static boolean qualifies(PriorityReservationSource source, CoordinationAssociation association) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(association, "association");
        var process = source.priorityReservation(association.processId());
        if (process == null || !process.processId().equals(association.processId())
            || !process.priorityLease() || !process.runnable()) return false;
        var resident = source.priorityResident(process.owningComputerId());
        return resident != null && resident.loaded()
            && resident.computerId().equals(process.owningComputerId())
            && resident.profile().equals(process.profile())
            && resident.profile().supports(ProductionCapability.ALCHEMIST);
    }

    @Override public boolean authorizes(UUID ownerClusterId, UUID libraryId, UUID patternId, long catalogGeneration) {
        return grants.contains(new Grant(ownerClusterId, libraryId, patternId, catalogGeneration));
    }

    private record Grant(UUID owner, UUID library, UUID pattern, long generation) {}
}
