package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryIdentityClaims;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryMovementLeases;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Bridges Pattern Core claims to Create's real pre-remove contraption boundary. */
public final class LibraryContraptionMovement {
	private static final String LEASES = "CreateBiotechPatternCoreLeases";
	private static final String LIBRARY_ID = "LibraryId";
	private static final String TOKEN = "Token";

	private LibraryContraptionMovement() {}

	/**
	 * Wrap target for {@code Contraption.removeBlocksFromWorld}: lookup and capture happen before
	 * the original {@code Level.removeBlockEntity}, whose invalidation would otherwise release it.
	 */
	public static void removeBlockEntityWithLease(Contraption contraption, Level level,
		BlockPos pos, Runnable originalRemoval) {
		Objects.requireNonNull(contraption, "contraption");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(originalRemoval, "originalRemoval");
		if (level.isClientSide || !(contraption instanceof LibraryMovementLeaseOwner owner)) {
			originalRemoval.run();
			return;
		}
		removeBlockEntityWithLease(new RemovalAccess() {
			@Override
			public @Nullable LibraryBlockEntity lowerCore() {
				BlockEntity blockEntity = level.getBlockEntity(pos);
				return blockEntity instanceof LibraryBlockEntity core ? core : null;
			}

			@Override
			public boolean captureLease(LibraryBlockEntity core) {
				MinecraftServer server = level.getServer();
				if (server == null)
					return false;
				LibraryMovementLeases.get(server);
				UUID libraryId = core.memberId();
				UUID token = core.movementClaimToken();
				if (!owner.createBiotech$addPatternCoreLease(libraryId, token))
					return false;
				if (core.retainLibraryIdentityForMovement(owner))
					return true;
				owner.createBiotech$removePatternCoreLease(libraryId, token);
				return false;
			}

			@Override
			public void removeBlockEntity() {
				originalRemoval.run();
			}
		});
	}

	/** Package-visible seam that tests the same lookup/capture/remove ordering used by the mixin. */
	static void removeBlockEntityWithLease(RemovalAccess access) {
		Objects.requireNonNull(access, "access");
		LibraryBlockEntity core = access.lowerCore();
		if (core != null) {
			try {
				access.captureLease(core);
			} catch (RuntimeException ignored) {
				// Create must still remove the captured block; invalidate then performs normal release.
			}
		}
		access.removeBlockEntity();
	}

	public static void writeLeases(LibraryMovementLeaseOwner owner, CompoundTag root) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(root, "root");
		ListTag leases = new ListTag();
		owner.createBiotech$patternCoreLeases().entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
			.forEach(entry -> {
				CompoundTag lease = new CompoundTag();
				lease.putUUID(LIBRARY_ID, entry.getKey());
				lease.putUUID(TOKEN, entry.getValue());
				leases.add(lease);
			});
		if (leases.isEmpty())
			root.remove(LEASES);
		else
			root.put(LEASES, leases);
	}

	public static void readAndRestoreLeases(Contraption contraption,
		LibraryMovementLeaseOwner owner, Level level, CompoundTag root) {
		Objects.requireNonNull(contraption, "contraption");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(root, "root");
		owner.createBiotech$clearPatternCoreLeases();
		if (level.isClientSide || level.getServer() == null)
			return;
		MinecraftServer server = level.getServer();
		LibraryMovementLeases.get(server);
		for (Map.Entry<UUID, UUID> lease :
			validatedSerializedLeases(contraption.getBlocks(), root).entrySet()) {
			UUID libraryId = lease.getKey();
			UUID token = lease.getValue();
			if (!owner.createBiotech$addPatternCoreLease(libraryId, token))
				continue;
			if (!LibraryIdentityClaims.restoreMovementLease(server, libraryId, owner, token))
				owner.createBiotech$removePatternCoreLease(libraryId, token);
		}
	}

	/** Fail-closed lease projection derived only from actual serialized lower Pattern Cores. */
	static Map<UUID, UUID> validatedSerializedLeases(
		Map<BlockPos, StructureBlockInfo> blocks, CompoundTag root) {
		Objects.requireNonNull(blocks, "blocks");
		Objects.requireNonNull(root, "root");
		if (blocks.size() > LibraryMovementLeases.MAX_LEASES)
			return Map.of();

		Set<LeasePair> serializedCores = new HashSet<>();
		for (StructureBlockInfo block : blocks.values()) {
			if (!(block.state().getBlock() instanceof LibraryCoreBlock)
				|| !block.state().hasProperty(LibraryCoreBlock.HALF)
				|| block.state().getValue(LibraryCoreBlock.HALF) != DoubleBlockHalf.LOWER
				|| block.nbt() == null)
				continue;
			CompoundTag data = block.nbt();
			if (!data.contains("PatternLibrary", Tag.TAG_COMPOUND))
				continue;
			CompoundTag pattern = data.getCompound("PatternLibrary");
			if (!pattern.hasUUID(LIBRARY_ID)
				|| !pattern.contains("ServerState", Tag.TAG_COMPOUND))
				continue;
			CompoundTag server = pattern.getCompound("ServerState");
			if (server.hasUUID("MovementClaimToken"))
				serializedCores.add(new LeasePair(pattern.getUUID(LIBRARY_ID),
					server.getUUID("MovementClaimToken")));
		}

		ListTag leases = root.getList(LEASES, Tag.TAG_COMPOUND);
		if (leases.size() > serializedCores.size()
			|| leases.size() > LibraryMovementLeases.MAX_LEASES)
			return Map.of();
		Map<UUID, UUID> validated = new LinkedHashMap<>();
		Set<UUID> claimedTokens = new HashSet<>();
		for (Tag raw : leases) {
			CompoundTag lease = (CompoundTag) raw;
			if (!lease.getAllKeys().equals(Set.of(LIBRARY_ID, TOKEN))
				|| !lease.hasUUID(LIBRARY_ID) || !lease.hasUUID(TOKEN))
				continue;
			UUID libraryId = lease.getUUID(LIBRARY_ID);
			UUID token = lease.getUUID(TOKEN);
			if (!serializedCores.contains(new LeasePair(libraryId, token)))
				continue;
			UUID previous = validated.putIfAbsent(libraryId, token);
			if (previous != null && !previous.equals(token) || !claimedTokens.add(token))
				return Map.of();
		}
		return Map.copyOf(validated);
	}

	/** Claims the restored BE before Create disposes the contraption and its remaining leases. */
	public static void claimReassembledCore(LibraryMovementLeaseOwner owner, Level level,
		@Nullable BlockEntity blockEntity) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(level, "level");
		if (level.isClientSide || level.getServer() == null
			|| !(blockEntity instanceof LibraryBlockEntity core))
			return;
		UUID libraryId = core.memberId();
		UUID token = core.movementClaimToken();
		if (!token.equals(owner.createBiotech$patternCoreLeases().get(libraryId)))
			return;
		LibraryMovementLeases.get(level.getServer());
		if (core.acceptMovementLeaseBeforeFirstTick(level.getServer(), owner))
			owner.createBiotech$removePatternCoreLease(libraryId, token);
	}

	/** Permanent entity disposal is the explicit, bounded cleanup for every owned lease. */
	public static void releaseAll(LibraryMovementLeaseOwner owner, Level level) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(level, "level");
		MinecraftServer server = level.getServer();
		if (!level.isClientSide && server != null)
			releaseAll(owner, (libraryId, token) -> LibraryIdentityClaims
				.releaseMovementLease(server, libraryId, owner, token));
		else
			owner.createBiotech$clearPatternCoreLeases();
	}

	/** Serialization already captured the lease list; detach only this retiring entity owner. */
	public static void detachAfterSerialization(LibraryMovementLeaseOwner owner, Level level) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(level, "level");
		MinecraftServer server = level.getServer();
		if (level.isClientSide || server == null)
			return;
		detachAfterSerialization(owner, (libraryId, token) -> LibraryIdentityClaims
			.detachMovementLeaseOwner(server, libraryId, owner, token));
	}

	static void detachAfterSerialization(LibraryMovementLeaseOwner owner,
		LeaseDetacher detacher) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(detacher, "detacher");
		for (Map.Entry<UUID, UUID> lease :
			new ArrayList<>(owner.createBiotech$patternCoreLeases().entrySet())) {
			if (detacher.detach(lease.getKey(), lease.getValue()))
				owner.createBiotech$removePatternCoreLease(lease.getKey(), lease.getValue());
		}
	}

	static void releaseAll(LibraryMovementLeaseOwner owner, LeaseReleaser releaser) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(releaser, "releaser");
		for (Map.Entry<UUID, UUID> lease :
			new ArrayList<>(owner.createBiotech$patternCoreLeases().entrySet()))
			releaser.release(lease.getKey(), lease.getValue());
		owner.createBiotech$clearPatternCoreLeases();
	}

	interface RemovalAccess {
		@Nullable LibraryBlockEntity lowerCore();
		boolean captureLease(LibraryBlockEntity core);
		void removeBlockEntity();
	}

	@FunctionalInterface
	interface LeaseDetacher {
		boolean detach(UUID libraryId, UUID token);
	}

	@FunctionalInterface
	interface LeaseReleaser {
		void release(UUID libraryId, UUID token);
	}

	private record LeasePair(UUID libraryId, UUID token) {}
}
