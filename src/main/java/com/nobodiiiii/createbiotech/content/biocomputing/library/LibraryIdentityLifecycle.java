package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/**
 * One physical Library's identity lifecycle, independent of recipes, routing and book contents.
 * The host remains the exact arbiter owner for compatibility with existing movement adapters.
 * Callers load durable server leases before admitting a world host; restoring values alone
 * never grants ownership. Server-thread transitions keep legacy withdrawal and generic serving
 * separate: a retained marker is needed to withdraw publications, but cannot serve storage.
 */
public final class LibraryIdentityLifecycle {
    private final Object owner;
    private UUID id = UUID.randomUUID();
    private UUID movementToken = UUID.randomUUID();
    @Nullable private MinecraftServer claimedServer;
    @Nullable private UUID claimedId;
    private boolean retainedForMovement;
    private boolean initialized;

    public LibraryIdentityLifecycle(Object owner) { this.owner = Objects.requireNonNull(owner, "owner"); }

    public UUID id() { return id; }
    public UUID movementToken() { return movementToken; }
    public boolean canAcceptPlacement() { return !initialized && claimedId == null; }

    /** Local ownership marker, including the withdrawal interval of a transferred movement lease. */
    public boolean hasClaim(@Nullable MinecraftServer server) {
        return server != null && claimedServer == server && id.equals(claimedId);
    }

    public boolean ownsCurrentIdentity() { return hasClaim(claimedServer); }

    public boolean isServing(@Nullable MinecraftServer server) {
        return hasClaim(server) && !retainedForMovement;
    }

    public boolean claimPlacement(MinecraftServer server, Optional<UUID> preservedId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(preservedId, "preservedId");
        if (!canAcceptPlacement()) return false;
        UUID next;
        if (preservedId.isPresent()) {
            next = preservedId.orElseThrow();
            if (!LibraryIdentityClaims.claimLibraryIdentity(server, next, owner)) return false;
        } else {
            var allocated = LibraryIdentityClaims.claimFreshLibraryIdentity(server, id, owner);
            if (allocated.isEmpty()) return false;
            next = allocated.orElseThrow();
        }
        id = next;
        adopt(server);
        return true;
    }

    public void initialize(@Nullable MinecraftServer server) {
        initialized = true;
        if (server == null) return;
        if (claimedServer != null && (claimedServer != server || !id.equals(claimedId))) release();
        LibraryIdentityClaims.activateLibraryIdentity(server, id, owner, () -> adopt(server), this::clearClaim);
    }

    public void invalidate() {
        initialized = false;
        if (retainedForMovement) clearClaim();
        else release();
    }

    void initialize(@Nullable MinecraftServer server, Runnable activation, Runnable rollback) {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(rollback, "rollback");
        initialized = true;
        if (server == null) return;
        if (claimedServer != null && (claimedServer != server || !id.equals(claimedId))) release();
        LibraryIdentityClaims.activateLibraryIdentity(server, id, owner, () -> {
            adopt(server);
            activation.run();
        }, () -> {
            try { rollback.run(); }
            finally { clearClaim(); }
        });
    }

    void invalidate(Runnable withdrawal, Consumer<MinecraftServer> unregister) {
        Objects.requireNonNull(withdrawal, "withdrawal");
        Objects.requireNonNull(unregister, "unregister");
        MinecraftServer server = claimedServer;
        UUID identity = claimedId;
        initialized = false;
        try {
            if (server == null || identity == null) return;
            if (retainedForMovement) {
                try { withdrawal.run(); }
                finally { unregister.accept(server); }
            } else {
                LibraryIdentityClaims.retireLibraryIdentity(server, identity, owner,
                    withdrawal, () -> unregister.accept(server));
            }
        } finally { clearClaim(); }
    }

    public boolean retainForMovement(Object movementOwner) {
        Objects.requireNonNull(movementOwner, "movementOwner");
        if (claimedServer == null || claimedId == null) return false;
        boolean retained = LibraryIdentityClaims.transferLibraryIdentityToMovementLease(
            claimedServer, claimedId, owner, movementOwner, movementToken);
        // A refused second transfer cannot revoke the earlier successful handoff.
        if (retained) retainedForMovement = true;
        return retained;
    }

    /** The exact Create owner hands off before normal first-tick registration. */
    public boolean acceptMovementLease(MinecraftServer server, Object movementOwner) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(movementOwner, "movementOwner");
        if (isServing(server)) return true;
        return LibraryIdentityClaims.activateLibraryIdentityFromMovementOwner(server, id,
            movementOwner, owner, movementToken, () -> adopt(server), this::clearClaim);
    }

    public void release() {
        MinecraftServer server = claimedServer;
        UUID identity = claimedId;
        clearClaim();
        if (server != null && identity != null)
            LibraryIdentityClaims.releaseLibraryIdentity(server, identity, owner);
    }

    /** Called only after the physical host validates its whole saved envelope. Does not claim. */
    public void restore(UUID identity, UUID token) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(token, "token");
        id = identity;
        movementToken = token;
    }

    /** A client projection is not a server ownership claim or a movement token update. */
    public void restoreClientId(UUID identity) { id = Objects.requireNonNull(identity, "identity"); }

    private void adopt(MinecraftServer server) {
        claimedServer = server;
        claimedId = id;
        retainedForMovement = false;
    }

    private void clearClaim() {
        claimedServer = null;
        claimedId = null;
        retainedForMovement = false;
    }
}
