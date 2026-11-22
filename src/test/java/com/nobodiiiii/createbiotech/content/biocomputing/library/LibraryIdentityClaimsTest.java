package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryIdentityClaimsTest {
    @Test
    void freshAllocationClaimsTheAvailablePreferredIdentityWithoutSharingIt() {
        Object server = new Object(), owner = new Object();
        UUID preferred = UUID.randomUUID();
        UUID allocated = LibraryIdentityClaims.claimFreshLibraryIdentity(server, preferred, owner).orElseThrow();
        try {
            assertEquals(preferred, allocated);
            assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, allocated, owner));
            assertFalse(LibraryIdentityClaims.claimLibraryIdentity(server, allocated, new Object()));
        } finally { LibraryIdentityClaims.releaseLibraryIdentity(server, allocated, owner); }
    }

    @Test
    void freshAllocationDoesNotStealALivePreferredIdentity() {
        Object server = new Object(), existing = new Object(), fresh = new Object();
        UUID preferred = UUID.randomUUID();
        assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, preferred, existing));
        UUID allocated = LibraryIdentityClaims.claimFreshLibraryIdentity(server, preferred, fresh).orElseThrow();
        try {
            assertNotEquals(preferred, allocated);
            assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, preferred, existing));
            assertFalse(LibraryIdentityClaims.claimLibraryIdentity(server, allocated, existing));
        } finally {
            LibraryIdentityClaims.releaseLibraryIdentity(server, preferred, existing);
            LibraryIdentityClaims.releaseLibraryIdentity(server, allocated, fresh);
        }
    }

    @Test
    void freshAllocationAvoidsADetachedMovementIdentityWithoutDisposingIt() {
        Object server = new Object(), movement = new Object(), fresh = new Object();
        UUID preferred = UUID.randomUUID(), token = UUID.randomUUID();
        assertTrue(LibraryIdentityClaims.claimLibraryIdentity(server, preferred, movement));
        assertTrue(LibraryIdentityClaims.retainLibraryIdentityForMovement(server, preferred, movement, token));
        assertTrue(LibraryIdentityClaims.detachMovementLeaseOwner(server, preferred, movement, token));
        UUID allocated = LibraryIdentityClaims.claimFreshLibraryIdentity(server, preferred, fresh).orElseThrow();
        try {
            assertNotEquals(preferred, allocated);
            assertFalse(LibraryIdentityClaims.claimLibraryIdentity(server, preferred, fresh));
            assertTrue(LibraryIdentityClaims.restoreProcessLocalMovementLease(server, preferred, movement, token));
            assertTrue(LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server, preferred,
                movement, movement, token, () -> {}, () -> {}));
        } finally {
            LibraryIdentityClaims.releaseLibraryIdentity(server, preferred, movement);
            LibraryIdentityClaims.releaseLibraryIdentity(server, allocated, fresh);
        }
    }
}
