package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Coherent server-owned production snapshot, never player data or a world-object capability.
 * Kind/runnable/loaded are facts derived by the host from its actual reservation and member tables.
 * This interface and its facts have no node, packet, NBT or generic DataValue representation.
 */
public interface PriorityReservationSource {
    UUID clusterId();
    Map<UUID, CoordinationAssociation> coordinationAssociations();
    Reservation priorityReservation(UUID processId);
    Resident priorityResident(UUID computerId);

    record Reservation(UUID processId, UUID owningComputerId, ProductionResourceProfile profile,
        boolean priorityLease, boolean runnable) {
        public Reservation {
            Objects.requireNonNull(processId); Objects.requireNonNull(owningComputerId); Objects.requireNonNull(profile);
        }
    }
    record Resident(UUID computerId, ProductionResourceProfile profile, boolean loaded) {
        public Resident { Objects.requireNonNull(computerId); Objects.requireNonNull(profile); }
    }
}
