package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/** Sole server-scoped identity arbiter for stationary and contraption-owned Libraries.
 * Activation callbacks belong to physical hosts; neither production state nor book data is shared here.
 */
public final class LibraryIdentityClaims {
    private static final Map<Object, Map<UUID, IdentityClaim>> LIBRARY_IDENTITY_CLAIMS = new WeakHashMap<>();
    private LibraryIdentityClaims() {}

	/** Allocates for an item without preserved identity; global refusal must terminate placement. */
	public static Optional<UUID> claimFreshLibraryIdentity(Object serverIdentity, UUID preferredId,
		Object owner) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(preferredId, "preferredId");
		Objects.requireNonNull(owner, "owner");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			UUID candidate = preferredId;
			while (true) {
				LibraryMovementLeases durable = durableLeases(serverIdentity);
				if (durable != null && !durable.acceptsClaims())
					return Optional.empty();
				if (claimLibraryIdentity(serverIdentity, candidate, owner))
					return Optional.of(candidate);
				candidate = UUID.randomUUID();
			}
		}
	}

	/** Claims a Library identity for exactly one active Core within one server. */
	public static boolean claimLibraryIdentity(Object serverIdentity, UUID libraryId, Object owner) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(owner, "owner");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			ClaimTransition transition = beginLibraryIdentityClaim(serverIdentity, libraryId,
				owner);
			if (transition == null)
				return false;
			completeLibraryIdentityClaim(transition);
			return true;
		}
	}

	/** Detaches the exact serialized carriage representation without releasing its durable lease. */
	public static boolean detachMovementLeaseOwner(Object serverIdentity, UUID libraryId,
		Object movementOwner, UUID movementToken) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(movementToken, "movementToken");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS.get(serverIdentity);
			if (serverClaims == null)
				return false;
			IdentityClaim existingClaim = serverClaims.get(libraryId);
			Object existing = existingClaim == null ? null : existingClaim.owner().get();
			if (existing != movementOwner
				|| !movementToken.equals(existingClaim.retainedMovementToken()))
				return false;
			serverClaims.put(libraryId, IdentityClaim.detached(movementToken));
			return true;
		}
	}

	/** Converts an exact live owner into a token-gated in-transit claim. */
	public static boolean retainLibraryIdentityForMovement(Object serverIdentity, UUID libraryId,
		Object owner, UUID movementToken) {
		return transferLibraryIdentityToMovementLease(serverIdentity, libraryId, owner, owner,
			movementToken);
	}

	/** Atomically hands an active Core claim to the durable contraption owner. */
	public static boolean transferLibraryIdentityToMovementLease(Object serverIdentity, UUID libraryId,
		Object owner, Object movementOwner, UUID movementToken) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(movementToken, "movementToken");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS.get(serverIdentity);
			if (serverClaims == null)
				return false;
			IdentityClaim existingClaim = serverClaims.get(libraryId);
			Object existing = existingClaim == null ? null : existingClaim.owner().get();
			if (existing != owner || existingClaim.retainedMovementToken() != null)
				return false;
			LibraryMovementLeases durable = durableLeases(serverIdentity);
			if (durable != null && !durable.reserve(libraryId, movementToken))
				return false;
			serverClaims.put(libraryId, IdentityClaim.retained(movementOwner, movementToken));
			return true;
		}
	}

	/** Reconstructs the process-local owner from the durable contraption/server pair. */
	public static boolean restoreMovementLease(MinecraftServer server, UUID libraryId,
		Object movementOwner, UUID movementToken) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(movementToken, "movementToken");
		LibraryMovementLeases durable = LibraryMovementLeases.get(server);
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			boolean alreadyDurable = durable.matches(libraryId, movementToken);
			if (!durable.reserve(libraryId, movementToken))
				return false;
			if (!restoreProcessLocalMovementLease(server, libraryId, movementOwner,
				movementToken)) {
				if (!alreadyDurable)
					durable.release(libraryId, movementToken);
				return false;
			}
			return true;
		}
	}

	/** Reconciles a serialized lease without allowing copied NBT to steal a live owner. */
	public static boolean restoreProcessLocalMovementLease(Object serverIdentity, UUID libraryId,
		Object movementOwner, UUID movementToken) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(movementToken, "movementToken");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS
				.computeIfAbsent(serverIdentity, ignored -> new HashMap<>());
			IdentityClaim existingClaim = serverClaims.get(libraryId);
			Object existing = existingClaim == null ? null : existingClaim.owner().get();
			if (existingClaim != null) {
				if (!movementToken.equals(existingClaim.retainedMovementToken()))
					return false;
				if (existing != null && existing != movementOwner)
					return false;
				if (existing == movementOwner)
					return true;
			}
			serverClaims.put(libraryId, IdentityClaim.retained(movementOwner, movementToken));
			return true;
		}
	}

	/** Disposes only the exact contraption-owned retained claim and durable record. */
	public static void releaseMovementLease(MinecraftServer server, UUID libraryId,
		Object movementOwner, UUID movementToken) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(movementToken, "movementToken");
		LibraryMovementLeases durable = LibraryMovementLeases.get(server);
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS.get(server);
			if (serverClaims == null) return;
			IdentityClaim existingClaim = serverClaims.get(libraryId);
			if (existingClaim == null || existingClaim.owner().get() != movementOwner
				|| !movementToken.equals(existingClaim.retainedMovementToken())) return;
			serverClaims.remove(libraryId);
			if (serverClaims.isEmpty()) LIBRARY_IDENTITY_CLAIMS.remove(server);
			durable.release(libraryId, movementToken);
		}
	}

	/** Releases a claim only when the exact claiming Core still owns it. */
	public static void releaseLibraryIdentity(Object serverIdentity, UUID libraryId, Object owner) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(owner, "owner");
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			Map<UUID, IdentityClaim> serverClaims =
				LIBRARY_IDENTITY_CLAIMS.get(serverIdentity);
			if (serverClaims == null)
				return;
			IdentityClaim existingClaim = serverClaims.get(libraryId);
			Object existing = existingClaim == null ? null : existingClaim.owner().get();
			if (existingClaim != null && (existing == owner || existing == null
				&& existingClaim.retainedMovementToken() == null))
				serverClaims.remove(libraryId);
			if (serverClaims.isEmpty())
				LIBRARY_IDENTITY_CLAIMS.remove(serverIdentity);
		}
	}

	private record IdentityClaim(WeakReference<Object> owner,
		@Nullable UUID retainedMovementToken) {
		private IdentityClaim {
			Objects.requireNonNull(owner, "owner reference");
		}

		private static IdentityClaim active(Object owner) {
			return new IdentityClaim(new WeakReference<>(owner), null);
		}

		private static IdentityClaim retained(Object owner, UUID movementToken) {
			return new IdentityClaim(new WeakReference<>(owner), movementToken);
		}

		private static IdentityClaim detached(UUID movementToken) {
			return new IdentityClaim(new WeakReference<>(null), movementToken);
		}
	}

	/** Acquires before activation and rolls back both activation and claim on failure. */
	public static boolean activateLibraryIdentity(Object serverIdentity, UUID libraryId, Object owner,
		Runnable activation, Runnable rollback) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(activation, "activation");
		Objects.requireNonNull(rollback, "rollback");
		ClaimTransition transition;
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			transition = beginLibraryIdentityClaim(serverIdentity, libraryId, owner);
			if (transition == null)
				return false;
		}
		return runIdentityActivation(transition, activation, rollback);
	}

	/** Only the exact live Contraption owner may authorize a retained Core handoff. */
	public static boolean activateLibraryIdentityFromMovementOwner(Object serverIdentity, UUID libraryId,
		Object movementOwner, Object owner, UUID movementToken, Runnable activation,
		Runnable rollback) {
		Objects.requireNonNull(serverIdentity, "serverIdentity");
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(movementOwner, "movementOwner");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(movementToken, "movementToken");
		Objects.requireNonNull(activation, "activation");
		Objects.requireNonNull(rollback, "rollback");
		ClaimTransition transition;
		synchronized (LIBRARY_IDENTITY_CLAIMS) {
			transition = beginMovementOwnerHandoff(serverIdentity, libraryId, movementOwner,
				owner, movementToken);
			if (transition == null)
				return false;
		}
		return runIdentityActivation(transition, activation, rollback);
	}

	private static boolean runIdentityActivation(ClaimTransition transition,
		Runnable activation, Runnable rollback) {
		try {
			activation.run();
			synchronized (LIBRARY_IDENTITY_CLAIMS) {
				completeLibraryIdentityClaim(transition);
			}
			return true;
		} catch (RuntimeException | Error failure) {
			try {
				rollback.run();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			} finally {
				synchronized (LIBRARY_IDENTITY_CLAIMS) {
					rollbackLibraryIdentityClaim(transition);
				}
			}
			throw failure;
		}
	}

	private static @Nullable ClaimTransition beginLibraryIdentityClaim(Object serverIdentity,
		UUID libraryId, Object owner) {
		LibraryMovementLeases durable = durableLeases(serverIdentity);
		if (durable != null && durable.blocksClaim(libraryId, null))
			return null;
		Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS
			.computeIfAbsent(serverIdentity, ignored -> new HashMap<>());
		IdentityClaim previous = serverClaims.get(libraryId);
		Object existing = previous == null ? null : previous.owner().get();
		if (previous != null && previous.retainedMovementToken() != null)
			return null;
		if (existing != null && existing != owner)
			return null;
		serverClaims.put(libraryId, IdentityClaim.active(owner));
		return new ClaimTransition(serverIdentity, libraryId, owner, previous, durable,
			null, false);
	}

	private static @Nullable ClaimTransition beginMovementOwnerHandoff(Object serverIdentity,
		UUID libraryId, Object movementOwner, Object owner, UUID movementToken) {
		LibraryMovementLeases durable = durableLeases(serverIdentity);
		if (durable != null && !durable.matches(libraryId, movementToken))
			return null;
		Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS.get(serverIdentity);
		if (serverClaims == null)
			return null;
		IdentityClaim previous = serverClaims.get(libraryId);
		Object existing = previous == null ? null : previous.owner().get();
		if (previous == null || existing != movementOwner
			|| !movementToken.equals(previous.retainedMovementToken()))
			return null;
		serverClaims.put(libraryId, IdentityClaim.active(owner));
		return new ClaimTransition(serverIdentity, libraryId, owner, previous, durable,
			movementToken, durable != null);
	}

	private static void completeLibraryIdentityClaim(ClaimTransition transition) {
		if (transition.durableHandoff())
			transition.durable().release(transition.libraryId(), transition.movementToken());
	}

	private static void rollbackLibraryIdentityClaim(ClaimTransition transition) {
		Map<UUID, IdentityClaim> serverClaims = LIBRARY_IDENTITY_CLAIMS
			.get(transition.serverIdentity());
		if (serverClaims == null)
			return;
		IdentityClaim current = serverClaims.get(transition.libraryId());
		if (current == null || current.owner().get() != transition.owner()
			|| current.retainedMovementToken() != null)
			return;
		if (transition.previous() == null)
			serverClaims.remove(transition.libraryId());
		else
			serverClaims.put(transition.libraryId(), transition.previous());
		if (serverClaims.isEmpty())
			LIBRARY_IDENTITY_CLAIMS.remove(transition.serverIdentity());
	}

	private static @Nullable LibraryMovementLeases durableLeases(Object serverIdentity) {
		return serverIdentity instanceof MinecraftServer server
			? LibraryMovementLeases.loaded(server) : null;
	}

	private record ClaimTransition(Object serverIdentity, UUID libraryId, Object owner,
		@Nullable IdentityClaim previous,
		@Nullable LibraryMovementLeases durable,
		@Nullable UUID movementToken, boolean durableHandoff) {}

	/** Runs every retirement stage and releases the claim even when withdrawal fails. */
	public static void retireLibraryIdentity(Object serverIdentity, UUID libraryId, Object owner,
		Runnable withdrawal, Runnable unregister) {
		Objects.requireNonNull(withdrawal, "withdrawal");
		Objects.requireNonNull(unregister, "unregister");
		try {
			withdrawal.run();
		} finally {
			try {
				unregister.run();
			} finally {
				releaseLibraryIdentity(serverIdentity, libraryId, owner);
			}
		}
	}

}
