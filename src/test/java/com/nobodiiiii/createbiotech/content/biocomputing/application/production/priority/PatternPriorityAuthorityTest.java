package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternPriorityAuthorityTest {
    private static final UUID OWNER = id(1), PROCESS = id(2), COMPUTER = id(3), LIBRARY = id(4), PATTERN = id(5);
    private static final ProductionResourceProfile PROFILE = ProductionResourceProfile
        .forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();

    @Test void copiedAuthorityRequiresExactOwnerLibraryPatternAndCatalog() {
        var source = active();
        var proof = PatternPriorityAuthority.fromAuthoritativeState(source);
        assertTrue(proof.authorizes(OWNER, LIBRARY, PATTERN, 7));
        assertFalse(proof.authorizes(id(9), LIBRARY, PATTERN, 7));
        assertFalse(proof.authorizes(OWNER, id(9), PATTERN, 7));
        assertFalse(proof.authorizes(OWNER, LIBRARY, id(9), 7));
        assertFalse(proof.authorizes(OWNER, LIBRARY, PATTERN, 8));
        source.reservations.clear();
        assertTrue(proof.authorizes(OWNER, LIBRARY, PATTERN, 7));
        assertFalse(PatternPriorityAuthority.fromAuthoritativeState(source).authorizes(OWNER, LIBRARY, PATTERN, 7));
        assertFalse(PatternPriorityAuthority.empty().authorizes(OWNER, LIBRARY, PATTERN, 7));
    }

    @Test void missingOrNonqualifyingReservationAndResidentNeverGrantPriority() {
        var otherProfile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 2, 0).orElseThrow();
        var nonCleric = ProductionResourceProfile.forProfession(ProductionProfession.FARMER, 1, 0).orElseThrow();
        for (var reservation : java.util.List.of(
                new PriorityReservationSource.Reservation(id(9), COMPUTER, PROFILE, true, true),
                new PriorityReservationSource.Reservation(PROCESS, id(9), PROFILE, true, true),
                new PriorityReservationSource.Reservation(PROCESS, COMPUTER, PROFILE, false, true),
                new PriorityReservationSource.Reservation(PROCESS, COMPUTER, PROFILE, true, false),
                new PriorityReservationSource.Reservation(PROCESS, COMPUTER, otherProfile, true, true))) {
            var source = active(); source.reservations.put(PROCESS, reservation); assertDenied(source);
        }
        for (var resident : java.util.List.of(
                new PriorityReservationSource.Resident(id(9), PROFILE, true),
                new PriorityReservationSource.Resident(COMPUTER, PROFILE, false),
                new PriorityReservationSource.Resident(COMPUTER, otherProfile, true))) {
            var source = active(); source.residents.put(COMPUTER, resident); assertDenied(source);
        }
        var missingResident = active(); missingResident.residents.clear(); assertDenied(missingResident);
        var missingProcess = active(); missingProcess.reservations.clear(); assertDenied(missingProcess);
        var wrongCapability = active();
        wrongCapability.residents.put(COMPUTER, new PriorityReservationSource.Resident(COMPUTER, nonCleric, true));
        wrongCapability.reservations.put(PROCESS, new PriorityReservationSource.Reservation(PROCESS, COMPUTER, nonCleric, true, true));
        assertDenied(wrongCapability);
    }

    @Test void malformedOrOverBoundSnapshotsFailInsteadOfPublishingPartialGrants() {
        var mismatched = active(); mismatched.associations.put(id(9), mismatched.associations.get(PROCESS));
        assertThrows(IllegalArgumentException.class, () -> PatternPriorityAuthority.fromAuthoritativeState(mismatched));
        var source = active(); source.associations.clear();
        for (int i = 0; i < PatternPriorityAuthority.MAX_LEASES; i++) {
            var process = id(100 + i);
            source.associations.put(process, new CoordinationAssociation(process, LIBRARY, PATTERN, 7));
            source.reservations.put(process, new PriorityReservationSource.Reservation(process, COMPUTER, PROFILE, true, true));
        }
        assertTrue(PatternPriorityAuthority.fromAuthoritativeState(source).authorizes(OWNER, LIBRARY, PATTERN, 7));
        source.associations.put(PROCESS, new CoordinationAssociation(PROCESS, LIBRARY, PATTERN, 7));
        assertThrows(IllegalStateException.class, () -> PatternPriorityAuthority.fromAuthoritativeState(source));
        assertThrows(IllegalArgumentException.class, () -> new CoordinationAssociation(PROCESS, LIBRARY, PATTERN, -1));
    }

    private static void assertDenied(Fixture source) {
        assertFalse(PatternPriorityAuthority.qualifies(source, source.associations.get(PROCESS)));
        assertFalse(PatternPriorityAuthority.fromAuthoritativeState(source).authorizes(OWNER, LIBRARY, PATTERN, 7));
    }

    private static Fixture active() {
        var source = new Fixture();
        source.associations.put(PROCESS, new CoordinationAssociation(PROCESS, LIBRARY, PATTERN, 7));
        source.reservations.put(PROCESS, new PriorityReservationSource.Reservation(PROCESS, COMPUTER, PROFILE, true, true));
        source.residents.put(COMPUTER, new PriorityReservationSource.Resident(COMPUTER, PROFILE, true));
        return source;
    }
    private static UUID id(long value) { return new UUID(0, value); }
    private static final class Fixture implements PriorityReservationSource {
        final Map<UUID, CoordinationAssociation> associations = new HashMap<>();
        final Map<UUID, Reservation> reservations = new HashMap<>();
        final Map<UUID, Resident> residents = new HashMap<>();
        public UUID clusterId() { return OWNER; }
        public Map<UUID, CoordinationAssociation> coordinationAssociations() { return associations; }
        public Reservation priorityReservation(UUID id) { return reservations.get(id); }
        public Resident priorityResident(UUID id) { return residents.get(id); }
    }
}
