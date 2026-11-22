package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import dev.ryanhcode.sable.companion.SubLevelAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Physical dual-part removal and recovery; independent of recipe and production state. */
public final class LibraryRemoval {
	private static final int REPLACE_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	private static final ThreadLocal<Set<RemovalKey>> ACTIVE_REMOVALS =
		ThreadLocal.withInitial(HashSet::new);

	private LibraryRemoval() {}


	public enum RemovalDisposition { CALL_SUPER, RESTORED }

	@FunctionalInterface
	public interface SpawnSink {
		boolean add(Entity entity);
	}

	interface ForcedRemovalOps {
		boolean emitRequiredOutput();
		boolean tryReleaseEntity();
		boolean tryDropRecoveryBox();
		void rollbackPreparedOutput();
		void commitSuccessfulRelease();
		RemovalDisposition restoreAfterDoubleFailure();
	}

	interface ControlledRemovalOps {
		boolean emitRequiredOutput();
		boolean tryReleaseEntity();
		void rollbackPreparedOutput();
		void markPendingSafeRelease();
		void commitSuccessfulRelease();
	}

	@FunctionalInterface
	interface StationaryRemoval {
		RemovalDisposition remove();
	}

	public static RemovalDisposition onRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, boolean isMoving, SpawnSink spawnSink) {
		return runRemovalEntry(isMoving,
			() -> onStationaryRemove(state, level, pos, newState, spawnSink));
	}

	private static RemovalDisposition onStationaryRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, SpawnSink spawnSink) {
		if (level.isClientSide || sameLogicalPart(state, newState)
			|| !(state.getBlock() instanceof LibraryCoreBlock))
			return RemovalDisposition.CALL_SUPER;

		BlockPos anchor = state.getValue(LibraryCoreBlock.HALF) == DoubleBlockHalf.LOWER
			? pos.immutable() : pos.below().immutable();
		if (isRemovalActive(level, anchor))
			return RemovalDisposition.CALL_SUPER;
		BlockEntity originalHost = level.getBlockEntity(anchor);
		if (!(originalHost instanceof LibraryRemovalHost oldLower))
			return RemovalDisposition.CALL_SUPER;
		return com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.duringRemoval(originalHost,
			() -> LibraryServiceBehaviour.duringRemoval(originalHost,
				() -> removeStationarySnapshot(state, level, pos, anchor, originalHost, oldLower, spawnSink)));
	}

	private static RemovalDisposition removeStationarySnapshot(BlockState state, Level level, BlockPos pos,
		BlockPos anchor, BlockEntity originalHost, LibraryRemovalHost oldLower, SpawnSink spawnSink) {
		LibraryRemovalHost.State removal = oldLower.captureLibraryRemoval();
		ItemStack originalSnapshot = removal.resident();
		CompoundTag originalCoreData = originalHost.saveWithoutMetadata(level.registryAccess());
		ItemStack configuredCore = removal.coreItem();
		BlockState lowerState = state.getValue(LibraryCoreBlock.HALF) == DoubleBlockHalf.LOWER
			? state : level.getBlockState(anchor);
		if (!LibraryCoreBlock.isHalf(lowerState, DoubleBlockHalf.LOWER))
			return RemovalDisposition.CALL_SUPER;
		BlockState upperState = lowerState.setValue(LibraryCoreBlock.HALF,
			DoubleBlockHalf.UPPER);
		if (configuredCore.isEmpty())
			return restoreAfterDoubleFailure(level, anchor, lowerState, upperState,
				originalCoreData);
		if (removal.unreadableResident())
			return restoreAfterDoubleFailure(level, anchor, lowerState, upperState,
				originalCoreData);

		Entity librarian = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(originalSnapshot, level);
		PreparedCoreOutput coreOutput = new PreparedCoreOutput(level, anchor, configuredCore);
		boolean residentReleaseRequired = !originalSnapshot.isEmpty();
		return runForcedRemoval(new ForcedRemovalOps() {
			@Override
			public boolean emitRequiredOutput() {
				return coreOutput.emit(spawnSink);
			}

			@Override
			public boolean tryReleaseEntity() {
				return librarian != null && moveToSafeRelease(level, anchor, librarian)
					&& spawnSink.add(librarian) && !librarian.isRemoved();
			}

			@Override
			public boolean tryDropRecoveryBox() {
				Vec3 recoveryPos = entityWorldPosition(level, anchor, Vec3.atCenterOf(anchor));
				ItemEntity recovery = new RecoveryItemEntity(level, recoveryPos.x,
					recoveryPos.y, recoveryPos.z, originalSnapshot.copy());
				return spawnSink.add(recovery) && !recovery.isRemoved();
			}

			@Override
			public void rollbackPreparedOutput() {
				coreOutput.rollback();
			}

			@Override
			public void commitSuccessfulRelease() {
				withRemovalGuard(level, anchor, () -> {
					oldLower.completeLibraryResidentRelease();
					removeCounterpart(level, anchor, pos);
				});
			}

			@Override
			public RemovalDisposition restoreAfterDoubleFailure() {
				return LibraryRemoval.restoreAfterDoubleFailure(level, anchor,
					lowerState, upperState, originalCoreData);
			}
		}, residentReleaseRequired);
	}

	/** Survival and creative both recover exactly one claiming core. */
	public static boolean controlledPlayerBreak(Level level, BlockPos anyPart,
		@Nullable Player player, SpawnSink spawnSink) {
		return player != null && controlledWrench(level, anyPart, spawnSink);
	}

	public static boolean controlledWrench(Level level, BlockPos anyPart, SpawnSink spawnSink) {
		if (!(level instanceof ServerLevel)) return false;
		BlockPos anchor = resolveAnchor(level, anyPart);
		if (!LibraryCoreBlock.isHalf(level.getBlockState(anchor), DoubleBlockHalf.LOWER)
			|| !(level.getBlockEntity(anchor) instanceof LibraryRemovalHost lower))
			return false;
		return com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryControlBehaviour.duringRemoval(level.getBlockEntity(anchor),
			() -> LibraryServiceBehaviour.duringRemoval(level.getBlockEntity(anchor),
				() -> removeControlledSnapshot(level, anchor, lower, spawnSink)));
	}

	private static boolean removeControlledSnapshot(Level level, BlockPos anchor, LibraryRemovalHost lower, SpawnSink spawnSink) {
		LibraryRemovalHost.State removal = lower.captureLibraryRemoval();
		ItemStack configuredCore = removal.coreItem();
		if (configuredCore.isEmpty()) return false;
		PreparedCoreOutput coreOutput = new PreparedCoreOutput(level, anchor, configuredCore);
		boolean residentReleaseRequired = removal.residentRequired();
		Entity librarian = residentReleaseRequired && !removal.unreadableResident()
			? prepareSafeLibrarian(level, anchor, removal.resident()) : null;
		return runControlledRemoval(new ControlledRemovalOps() {
			@Override
			public boolean emitRequiredOutput() {
				return coreOutput.emit(spawnSink);
			}

			@Override
			public boolean tryReleaseEntity() {
				return librarian != null && spawnSink.add(librarian) && !librarian.isRemoved();
			}

			@Override
			public void rollbackPreparedOutput() { coreOutput.rollback(); }

			@Override
			public void markPendingSafeRelease() {
				lower.deferLibraryResidentRelease();
			}

			@Override
			public void commitSuccessfulRelease() {
				withRemovalGuard(level, anchor, () -> {
					lower.completeLibraryResidentRelease();
					removeCoreHalf(level, anchor.above());
					removeCoreHalf(level, anchor);
				});
			}
		}, residentReleaseRequired);
	}

	static RemovalDisposition runForcedRemoval(ForcedRemovalOps operations) {
		return runForcedRemoval(operations, true);
	}

	static RemovalDisposition runForcedRemoval(ForcedRemovalOps operations,
		boolean residentReleaseRequired) {
		if (!operations.emitRequiredOutput())
			return operations.restoreAfterDoubleFailure();
		if (!residentReleaseRequired) {
			operations.commitSuccessfulRelease();
			return RemovalDisposition.CALL_SUPER;
		}
		if (operations.tryReleaseEntity()) {
			operations.commitSuccessfulRelease();
			return RemovalDisposition.CALL_SUPER;
		}
		if (operations.tryDropRecoveryBox()) {
			operations.commitSuccessfulRelease();
			return RemovalDisposition.CALL_SUPER;
		}
		operations.rollbackPreparedOutput();
		return operations.restoreAfterDoubleFailure();
	}

	static boolean runControlledRemoval(ControlledRemovalOps operations) {
		return runControlledRemoval(operations, true);
	}

	static boolean runControlledRemoval(ControlledRemovalOps operations,
		boolean residentReleaseRequired) {
		if (!operations.emitRequiredOutput()) {
			if (residentReleaseRequired)
				operations.markPendingSafeRelease();
			return false;
		}
		if (residentReleaseRequired && !operations.tryReleaseEntity()) {
			operations.rollbackPreparedOutput();
			operations.markPendingSafeRelease();
			return false;
		}
		operations.commitSuccessfulRelease();
		return true;
	}

	static RemovalDisposition runRemovalEntry(boolean isMoving, StationaryRemoval stationaryRemoval) {
		return isMoving ? RemovalDisposition.CALL_SUPER : stationaryRemoval.remove();
	}

	private static final class PreparedCoreOutput {
		private final Level level;
		private final Vec3 target;
		private final ItemStack configuredCore;
		@Nullable private ItemEntity emitted;

		private PreparedCoreOutput(Level level, BlockPos anchor, ItemStack configuredCore) {
			this.level = Objects.requireNonNull(level, "level");
			target = entityWorldPosition(level, anchor, Vec3.atCenterOf(anchor));
			this.configuredCore = Objects.requireNonNull(configuredCore, "configuredCore")
				.copyWithCount(1);
		}

		private boolean emit(SpawnSink sink) {
			if (configuredCore.isEmpty() || emitted != null) return false;
			ItemEntity candidate = new ItemEntity(level, target.x, target.y, target.z,
				configuredCore.copy());
			if (!sink.add(candidate) || candidate.isRemoved()) {
				candidate.discard();
				return false;
			}
			emitted = candidate;
			return true;
		}

		private void rollback() {
			if (emitted != null) emitted.discard();
			emitted = null;
		}
	}

	/** Keeps the transactional recovery drop as an ItemEntity instead of NeoForge
	 * replacing the filled box during EntityJoinLevelEvent and reporting a failed add. */
	private static final class RecoveryItemEntity extends ItemEntity {
		private RecoveryItemEntity(Level level, double x, double y, double z, ItemStack stack) {
			super(level, x, y, z, stack);
		}
	}

	private static @Nullable Entity prepareSafeLibrarian(Level level, BlockPos anchor,
		ItemStack snapshot) {
		if (snapshot.isEmpty())
			return null;
		Entity librarian = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(snapshot, level);
		return librarian != null && moveToSafeRelease(level, anchor, librarian) ? librarian : null;
	}

	private static boolean moveToSafeRelease(Level level, BlockPos anchor, Entity entity) {
		for (BlockPos candidate : releaseCandidates(anchor)) {
			if (candidate.equals(anchor.above())
				&& LibraryCoreBlock.isHalf(level.getBlockState(candidate), DoubleBlockHalf.UPPER))
				continue;
			Vec3 target = entityWorldPosition(level, anchor, Vec3.atBottomCenterOf(candidate));
			if (!level.getWorldBorder().isWithinBounds(BlockPos.containing(target)))
				continue;
			AABB movedBounds = entity.getBoundingBox().move(target.subtract(entity.position()));
			if (!level.noCollision(entity, movedBounds))
				continue;
			entity.moveTo(target.x, target.y, target.z, entity.getYRot(), entity.getXRot());
			return true;
		}
		return false;
	}

	private static Vec3 entityWorldPosition(Level level, BlockPos spaceAnchor, Position localPosition) {
		return SubLevelCompat.toWorld(level, spaceAnchor, localPosition);
	}

	static Vec3 entityWorldPosition(@Nullable SubLevelAccess subLevel, Position localPosition) {
		return SubLevelCompat.toWorld(subLevel, localPosition);
	}

	static boolean sameLogicalPart(BlockState state, BlockState newState) {
		return state.getBlock() == newState.getBlock()
			&& (!(state.getBlock() instanceof LibraryCoreBlock)
				|| state.getValue(LibraryCoreBlock.HALF)
					== newState.getValue(LibraryCoreBlock.HALF));
	}

	private static List<BlockPos> releaseCandidates(BlockPos anchor) {
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(anchor.above());
		for (Direction direction : Direction.Plane.HORIZONTAL)
			candidates.add(anchor.relative(direction));
		candidates.add(anchor.below());
		for (int x = -2; x <= 2; x++)
			for (int z = -2; z <= 2; z++)
				if (Math.max(Math.abs(x), Math.abs(z)) == 2)
					candidates.add(anchor.offset(x, 0, z));
		return List.copyOf(candidates);
	}

	private static RemovalDisposition restoreAfterDoubleFailure(Level level, BlockPos anchor,
		BlockState lowerState, BlockState upperState, CompoundTag originalCoreData) {
		return withRemovalGuard(level, anchor, () -> {
			level.removeBlockEntity(anchor);
			if (LibraryCoreBlock.isHalf(level.getBlockState(anchor), DoubleBlockHalf.LOWER))
				level.setBlock(anchor, Blocks.AIR.defaultBlockState(), REPLACE_FLAGS);
			if (LibraryCoreBlock.isHalf(level.getBlockState(anchor.above()), DoubleBlockHalf.UPPER))
				level.setBlock(anchor.above(), Blocks.AIR.defaultBlockState(), REPLACE_FLAGS);
			boolean lowerPlaced = level.setBlock(anchor, lowerState, REPLACE_FLAGS);
			boolean upperPlaced = lowerPlaced
				&& level.setBlock(anchor.above(), upperState, REPLACE_FLAGS);
			BlockEntity restored = level.getBlockEntity(anchor);
			if (!(restored instanceof LibraryRemovalHost host))
				return RemovalDisposition.CALL_SUPER;
			restored.loadWithComponents(originalCoreData.copy(), level.registryAccess());
			boolean identityRestored = host.reclaimLibraryAfterRemoval();
			boolean complete = upperPlaced && LibraryCoreBlock.isComplete(
				level.getBlockState(anchor), level.getBlockState(anchor.above()));
			return complete && identityRestored
				? RemovalDisposition.RESTORED : RemovalDisposition.CALL_SUPER;
		});
	}

	private static void removeCounterpart(Level level, BlockPos anchor, BlockPos initiatingPos) {
		if (!initiatingPos.equals(anchor.above()))
			removeCoreHalf(level, anchor.above());
		if (!initiatingPos.equals(anchor))
			removeCoreHalf(level, anchor);
	}

	private static void removeCoreHalf(Level level, BlockPos pos) {
		if (level.getBlockState(pos).getBlock() instanceof LibraryCoreBlock)
			CBMultiBlockLifecycle.removeSilently(level, pos);
	}

	private static BlockPos resolveAnchor(Level level, BlockPos anyPart) {
		BlockState state = level.getBlockState(anyPart);
		if (state.getBlock() instanceof LibraryCoreBlock)
			return state.getValue(LibraryCoreBlock.HALF) == DoubleBlockHalf.LOWER
				? anyPart.immutable() : anyPart.below().immutable();
		return anyPart.immutable();
	}

	static boolean isRemovalActive(Object levelIdentity, BlockPos anchor) {
		Set<RemovalKey> active = ACTIVE_REMOVALS.get();
		boolean present = active.contains(new RemovalKey(levelIdentity, anchor));
		if (active.isEmpty())
			ACTIVE_REMOVALS.remove();
		return present;
	}

	public static void withRemovalGuard(Object levelIdentity, BlockPos anchor, Runnable mutation) {
		withRemovalGuard(levelIdentity, anchor, () -> {
			mutation.run();
			return null;
		});
	}

	static <T> T withRemovalGuard(Object levelIdentity, BlockPos anchor,
		java.util.function.Supplier<T> mutation) {
		RemovalKey key = new RemovalKey(levelIdentity, anchor);
		Set<RemovalKey> active = ACTIVE_REMOVALS.get();
		boolean added = active.add(key);
		try {
			return mutation.get();
		} finally {
			if (added)
				active.remove(key);
			if (active.isEmpty())
				ACTIVE_REMOVALS.remove();
		}
	}

	private static final class RemovalKey {
		private final Object levelIdentity;
		private final BlockPos anchor;

		private RemovalKey(Object levelIdentity, BlockPos anchor) {
			this.levelIdentity = levelIdentity;
			this.anchor = anchor.immutable();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof RemovalKey key
				&& levelIdentity == key.levelIdentity && anchor.equals(key.anchor);
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(levelIdentity) + anchor.hashCode();
		}
	}
}
