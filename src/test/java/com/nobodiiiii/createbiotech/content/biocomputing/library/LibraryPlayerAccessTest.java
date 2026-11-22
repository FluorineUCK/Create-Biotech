package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryPermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryPlayerAccessTest {
    @Test void authorizedLocalPlayerCanEditWithoutALegacyHostPolicy() {
        assertTrue(LibraryPlayerAccess.editPermission(true, true, false, () -> true),
            "New Library access must independently admit an authorized local edit");
    }

    @Test void actorRestrictionsFailBeforeConsultingTheApplication() {
        java.util.function.BooleanSupplier unexpected = () -> fail("Rejected actor cannot consult application grants");
        assertFalse(LibraryPlayerAccess.editPermission(false, true, false, unexpected));
        assertFalse(LibraryPlayerAccess.editPermission(true, false, false, unexpected));
        assertFalse(LibraryPlayerAccess.editPermission(true, true, true, unexpected));
    }

    @Test void applicationRevocationIsObservedRatherThanCached() {
        var grant = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertTrue(LibraryPlayerAccess.editPermission(true, true, false, grant::get));
        grant.set(false);
        assertFalse(LibraryPlayerAccess.editPermission(true, true, false, grant::get));
    }

    @Test void productionRestrictionRequiresEveryBoundNetworksAdministration() {
        UUID allowed = UUID.randomUUID(), denied = UUID.randomUUID();
        assertTrue(ProductionLibraryPermissions.mayEdit(true, List.of(allowed), allowed::equals));
        assertFalse(ProductionLibraryPermissions.mayEdit(true, List.of(allowed, denied), allowed::equals));
        assertTrue(ProductionLibraryPermissions.mayEdit(true, List.of(), network -> fail("Unbound local Library needs no network")));
    }

    @Test void invalidProductionBindingIsNotAnUnboundGrant() {
        assertFalse(ProductionLibraryPermissions.mayEdit(false, List.of(), network -> true));
        assertFalse(ProductionLibraryPermissions.mayEdit(false, List.of(UUID.randomUUID()),
            network -> fail("Invalid binding cannot consult external network grants")));
    }
}
