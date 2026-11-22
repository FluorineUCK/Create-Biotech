package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable token-gated ownership for Library cores captured inside Create contraptions.
 *
 * <p>The central overworld data is deliberately loaded before any physical Library claim.
 * This keeps an unloaded or not-yet-deserialized contraption authoritative across a restart;
 * the contraption's own NBT carries the same pair so its eventual load can reconcile it.</p>
 */
public final class LibraryMovementLeases extends SavedData {
    /** Existing durable representation cap, not player storage capacity or throughput. */
    public static final int MAX_LEASES = 16_384;
	private static final String DATA_NAME = "create_biotech_pattern_core_movement_leases";
	private static final String LEASES = "Leases";
	private static final String LIBRARY_ID = "LibraryId";
	private static final String TOKEN = "Token";
	private static final String FAIL_CLOSED = "FailClosed";
	private static final Map<MinecraftServer, LibraryMovementLeases> LOADED =
		new WeakHashMap<>();

	private final Map<UUID, UUID> tokensByLibrary = new LinkedHashMap<>();
	private final Map<UUID, UUID> librariesByToken = new LinkedHashMap<>();
	private boolean failClosed;

	public LibraryMovementLeases() {}

	public static LibraryMovementLeases get(MinecraftServer server) {
		Objects.requireNonNull(server, "server");
		synchronized (LOADED) {
			LibraryMovementLeases loaded = LOADED.get(server);
			if (loaded != null)
				return loaded;
			LibraryMovementLeases data = server.overworld().getDataStorage()
				.computeIfAbsent(new SavedData.Factory<>(
					LibraryMovementLeases::new,
					LibraryMovementLeases::load), DATA_NAME);
			LOADED.put(server, data);
			return data;
		}
	}

	static @Nullable LibraryMovementLeases loaded(MinecraftServer server) {
		synchronized (LOADED) {
			return LOADED.get(server);
		}
	}

	/** Reserves a one-to-one Library/token pair; the exact pair is idempotent. */
	public synchronized boolean reserve(UUID libraryId, UUID token) {
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(token, "token");
		if (failClosed)
			return false;
		UUID existingToken = tokensByLibrary.get(libraryId);
		UUID existingLibrary = librariesByToken.get(token);
		if (existingToken != null || existingLibrary != null)
			return token.equals(existingToken) && libraryId.equals(existingLibrary);
		if (tokensByLibrary.size() >= MAX_LEASES) {
			enterFailClosed();
			return false;
		}
		tokensByLibrary.put(libraryId, token);
		librariesByToken.put(token, libraryId);
		setDirty();
		return true;
	}

	/** Returns true exactly when this durable owner rejects the supplied claim token. */
	public synchronized boolean blocksClaim(UUID libraryId, @Nullable UUID token) {
		Objects.requireNonNull(libraryId, "libraryId");
		if (failClosed)
			return true;
		UUID retainedToken = tokensByLibrary.get(libraryId);
		return retainedToken != null && !retainedToken.equals(token);
	}

	public synchronized boolean matches(UUID libraryId, UUID token) {
		return !failClosed && token.equals(tokensByLibrary.get(libraryId));
	}

	/** Releases only the exact durable owner, making disposal safe after a successful handoff. */
	public synchronized boolean release(UUID libraryId, UUID token) {
		Objects.requireNonNull(libraryId, "libraryId");
		Objects.requireNonNull(token, "token");
		if (failClosed)
			return false;
		if (!token.equals(tokensByLibrary.get(libraryId)))
			return false;
		tokensByLibrary.remove(libraryId);
		librariesByToken.remove(token);
		setDirty();
		return true;
	}

	public synchronized int leaseCount() {
		return tokensByLibrary.size();
	}

	/** A global refusal is not a UUID collision that fresh allocation can retry past. */
	synchronized boolean acceptsClaims() {
		return !failClosed;
	}

	@Override
	public synchronized CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		if (tokensByLibrary.size() > MAX_LEASES)
			enterFailClosed();
		ListTag leases = new ListTag();
		if (failClosed)
			root.putBoolean(FAIL_CLOSED, true);
		else {
			root.remove(FAIL_CLOSED);
			tokensByLibrary.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
				.forEach(entry -> {
					CompoundTag lease = new CompoundTag();
					lease.putUUID(LIBRARY_ID, entry.getKey());
					lease.putUUID(TOKEN, entry.getValue());
					leases.add(lease);
				});
		}
		root.put(LEASES, leases);
		return root;
	}

	public static LibraryMovementLeases load(CompoundTag root,
		HolderLookup.Provider registries) {
		return load(root, registries, MAX_LEASES);
	}

	public static LibraryMovementLeases load(CompoundTag root,
		HolderLookup.Provider registries, int maxEntries) {
		LibraryMovementLeases data = new LibraryMovementLeases();
		if (root.contains(FAIL_CLOSED) && (!root.contains(FAIL_CLOSED, Tag.TAG_BYTE)
			|| root.getByte(FAIL_CLOSED) != 0 && root.getByte(FAIL_CLOSED) != 1)) {
			data.enterFailClosed();
			return data;
		}
		if (root.getBoolean(FAIL_CLOSED)) {
			data.failClosed = true;
			data.setDirty(false);
			return data;
		}
		Tag rawLeases = root.get(LEASES);
		if (maxEntries < 0 || rawLeases != null && !(rawLeases instanceof ListTag)) {
			data.enterFailClosed();
			return data;
		}
		ListTag leases = rawLeases instanceof ListTag list ? list : new ListTag();
		if (leases.size() > Math.min(maxEntries,
			MAX_LEASES)
			|| !leases.isEmpty() && leases.getElementType() != Tag.TAG_COMPOUND) {
			data.enterFailClosed();
			return data;
		}
		for (Tag raw : leases) {
			CompoundTag lease = (CompoundTag) raw;
			if (!lease.getAllKeys().equals(java.util.Set.of(LIBRARY_ID, TOKEN))
				|| !lease.hasUUID(LIBRARY_ID) || !lease.hasUUID(TOKEN)) {
				data.enterFailClosed();
				return data;
			}
			if (!data.reserve(lease.getUUID(LIBRARY_ID), lease.getUUID(TOKEN))) {
				data.enterFailClosed();
				return data;
			}
		}
		data.setDirty(false);
		return data;
	}

	/** Collapses unrepresentable authority into one durable global rejection state. */
	private void enterFailClosed() {
		failClosed = true;
		tokensByLibrary.clear();
		librariesByToken.clear();
		setDirty();
	}
}
