package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;

class LibraryIdentityLifecycleTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

    @Test
    void restoredCopyCannotAcquireOrServeTheOriginalIdentity() {
        var server = server();
        var original = new LibraryIdentityLifecycle(new Object());
        var copy = new LibraryIdentityLifecycle(new Object());
        assertTrue(original.claimPlacement(server, Optional.empty()));
        copy.restore(original.id(), original.movementToken());
        try {
            assertFalse(copy.ownsCurrentIdentity());
            assertFalse(copy.claimPlacement(server, Optional.of(original.id())));
            copy.initialize(server, () -> fail("A copy cannot register"), () -> fail("No activation to roll back"));
            assertFalse(copy.isServing(server));
            assertTrue(original.isServing(server));
            assertFalse(original.claimPlacement(server, Optional.empty()));
        } finally { copy.release(); original.release(); }
    }

    @Test
    void activationFailureCleansCallbacksAndReleasesTheNewClaim() {
        var server = server();
        var identity = new LibraryIdentityLifecycle(new Object());
        var calls = new ArrayList<String>();
        var failure = new IllegalStateException("registration failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> identity.initialize(server, () -> {
            assertTrue(identity.hasClaim(server)); calls.add("activate"); throw failure;
        }, () -> { calls.add("rollback"); throw new IllegalArgumentException("rollback failed"); })));
        assertEquals(List.of("activate", "rollback"), calls);
        assertEquals(1, failure.getSuppressed().length);
        assertFalse(identity.ownsCurrentIdentity());
        assertFalse(identity.canAcceptPlacement());
        var replacement = new LibraryIdentityLifecycle(new Object());
        try { assertTrue(replacement.claimPlacement(server, Optional.of(identity.id()))); }
        finally { replacement.release(); }
    }

    @Test
    void withdrawalFailureStillUnregistersAndReleasesTheExactServerClaim() {
        var server = server();
        var identity = new LibraryIdentityLifecycle(new Object());
        assertTrue(identity.claimPlacement(server, Optional.empty()));
        var calls = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> identity.invalidate(() -> {
            assertTrue(identity.hasClaim(server)); calls.add("withdraw"); throw new IllegalStateException("withdraw");
        }, claimedServer -> { assertSame(server, claimedServer); calls.add("unregister"); }));
        assertEquals(List.of("withdraw", "unregister"), calls);
        assertFalse(identity.ownsCurrentIdentity());
        assertTrue(identity.canAcceptPlacement());
        var replacement = new LibraryIdentityLifecycle(new Object());
        try { assertTrue(replacement.claimPlacement(server, Optional.of(identity.id()))); }
        finally { replacement.release(); }
    }

    @Test
    void movementKeepsWithdrawalMarkerButOnlyExactOwnerCanRestoreStorage() {
        var server = server();
        var source = new LibraryIdentityLifecycle(new Object());
        var restored = new LibraryIdentityLifecycle(new Object());
        var movement = new Object();
        assertTrue(source.claimPlacement(server, Optional.empty()));
        restored.restore(source.id(), source.movementToken());
        assertTrue(source.retainForMovement(movement));
        assertFalse(source.retainForMovement(new Object()));
        assertFalse(source.isServing(server));
        source.invalidate(() -> {
            assertTrue(source.hasClaim(server), "Legacy withdrawal still sees its retained marker");
            assertFalse(source.isServing(server), "Withdrawal must not expose the storage service");
        }, claimedServer -> assertSame(server, claimedServer));
        try {
            assertFalse(restored.acceptMovementLease(server, new Object()));
            assertTrue(restored.acceptMovementLease(server, movement));
            assertTrue(restored.isServing(server));
            assertEquals(source.movementToken(), restored.movementToken());
            restored.initialize(server, () -> assertTrue(restored.isServing(server)), () -> fail("No rollback expected"));
        } finally { restored.release(); LibraryIdentityClaims.releaseLibraryIdentity(server, source.id(), movement); }
    }

    @Test
    void restoredIdentityReleasesPreviousClaimOnlyAtNextInitialization() {
        var server = server();
        var identity = new LibraryIdentityLifecycle(new Object());
        var other = new LibraryIdentityLifecycle(new Object());
        assertTrue(identity.claimPlacement(server, Optional.empty()));
        UUID previous = identity.id(), next = UUID.randomUUID(), token = UUID.randomUUID();
        identity.restore(next, token);
        try {
            assertFalse(identity.isServing(server));
            assertFalse(other.claimPlacement(server, Optional.of(previous)));
            identity.initialize(server, () -> {}, () -> fail("No rollback expected"));
            assertEquals(next, identity.id()); assertEquals(token, identity.movementToken());
            assertTrue(identity.isServing(server));
            assertTrue(other.claimPlacement(server, Optional.of(previous)));
        } finally { identity.release(); other.release(); }
    }

    @Test
    void changingServerDoesNotLeaveTheOldServerClaimBehind() {
        var oldServer = server(); var newServer = server();
        var identity = new LibraryIdentityLifecycle(new Object());
        var other = new LibraryIdentityLifecycle(new Object());
        assertTrue(identity.claimPlacement(oldServer, Optional.empty()));
        try {
            identity.initialize(newServer, () -> {}, () -> fail("No rollback expected"));
            assertFalse(identity.isServing(oldServer)); assertTrue(identity.isServing(newServer));
            assertTrue(other.claimPlacement(oldServer, Optional.of(identity.id())));
        } finally { identity.release(); other.release(); }
    }

    @Test
    void clientProjectionAndClientInitializationNeverAcquireServerOwnership() {
        var identity = new LibraryIdentityLifecycle(new Object());
        UUID token = identity.movementToken(), clientId = UUID.randomUUID();
        identity.restoreClientId(clientId);
        identity.initialize(null, () -> fail("No server activation"), () -> fail("No server rollback"));
        assertEquals(clientId, identity.id()); assertEquals(token, identity.movementToken());
        assertFalse(identity.ownsCurrentIdentity()); assertFalse(identity.canAcceptPlacement());
        identity.invalidate(() -> fail("No server withdrawal"), server -> fail("No server registration"));
        assertTrue(identity.canAcceptPlacement());
    }

    private static MinecraftServer server() {
        try {
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (MinecraftServer)((sun.misc.Unsafe)field.get(null)).allocateInstance(DedicatedServer.class);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
