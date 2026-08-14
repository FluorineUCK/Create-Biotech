package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class PatternStorageCoreLifecycle {
	private static final int REPLACE_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	private static final ThreadLocal<Set<RemovalKey>> ACTIVE_REMOVALS =
		ThreadLocal.withInitial(HashSet::new);

	private PatternStorageCoreLifecycle() {}

	enum RemovalDisposition { CALL_SUPER, RESTORED }

	@FunctionalInterface
	interface SpawnSink {
		boolean add(Entity entity);
	}

	interface ForcedRemovalOps {
		boolean tryReleaseEntity();
		boolean tryDropRecoveryBox();
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

	static RemovalDisposition onRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, boolean isMoving, SpawnSink spawnSink) {
		return runRemovalEntry(isMoving,
			() -> onStationaryRemove(state, level, pos, newState, spawnSink));
	}

	private static RemovalDisposition onStationaryRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, SpawnSink spawnSink) {
		if (level.isClientSide || state.is(newState.getBlock())
			|| !(state.getBlock() instanceof PatternStorageCoreBlock))
			return RemovalDisposition.CALL_SUPER;

		BlockPos anchor = state.getValue(PatternStorageCoreBlock.HALF) == DoubleBlockHalf.LOWER
			? pos.immutable() : pos.below().immutable();
		if (isRemovalActive(level, anchor))
			return RemovalDisposition.CALL_SUPER;
		if (!(level.getBlockEntity(anchor) instanceof PatternStorageCoreBlockEntity oldLower))
			return RemovalDisposition.CALL_SUPER;

		ItemStack originalSnapshot = oldLower.snapshot().copy();
		BlockState lowerState = state.getValue(PatternStorageCoreBlock.HALF) == DoubleBlockHalf.LOWER
			? state : level.getBlockState(anchor);
		if (!PatternStorageCoreBlock.isHalf(lowerState, DoubleBlockHalf.LOWER))
			return RemovalDisposition.CALL_SUPER;
		BlockState upperState = lowerState.setValue(PatternStorageCoreBlock.HALF,
			DoubleBlockHalf.UPPER);

		if (originalSnapshot.isEmpty()) {
			withRemovalGuard(level, anchor, () -> removeCounterpart(level, anchor, pos));
			return RemovalDisposition.CALL_SUPER;
		}

		Entity librarian = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(originalSnapshot, level);
		return runForcedRemoval(new ForcedRemovalOps() {
			@Override
			public boolean tryReleaseEntity() {
				return librarian != null && moveToSafeRelease(level, anchor, librarian)
					&& spawnSink.add(librarian);
			}

			@Override
			public boolean tryDropRecoveryBox() {
				ItemEntity recovery = new ItemEntity(level, anchor.getX() + 0.5,
					anchor.getY() + 0.5, anchor.getZ() + 0.5, originalSnapshot.copy());
				return spawnSink.add(recovery);
			}

			@Override
			public void commitSuccessfulRelease() {
				withRemovalGuard(level, anchor, () -> {
					oldLower.clearSnapshot();
					removeCounterpart(level, anchor, pos);
				});
			}

			@Override
			public RemovalDisposition restoreAfterDoubleFailure() {
				return PatternStorageCoreLifecycle.restoreAfterDoubleFailure(level, anchor,
					lowerState, upperState, originalSnapshot);
			}
		});
	}

	static boolean controlledPlayerBreak(Level level, BlockPos anyPart, @Nullable Player player,
		ItemStack tool, SpawnSink spawnSink) {
		BlockPos anchor = resolveAnchor(level, anyPart);
		PatternStorageCoreBlockEntity lower = lower(level, anchor);
		if (lower == null)
			return false;
		PlayerBreakOutput requiredOutput = playerBreakOutput(level, anchor, lower, player, tool);
		Entity librarian = prepareSafeLibrarian(level, anchor, lower);
		return runControlledRemoval(new ControlledRemovalOps() {
			@Override
			public boolean emitRequiredOutput() {
				return requiredOutput != null && requiredOutput.emit(spawnSink);
			}

			@Override
			public boolean tryReleaseEntity() {
				return librarian != null && spawnSink.add(librarian);
			}

			@Override
			public void rollbackPreparedOutput() {
				requiredOutput.rollback();
			}

			@Override
			public void markPendingSafeRelease() {
				lower.markPendingSafeRelease();
			}

			@Override
			public void commitSuccessfulRelease() {
				withRemovalGuard(level, anchor, () -> {
					lower.clearSnapshot();
					removeCoreHalf(level, anchor.above());
					removeCoreHalf(level, anchor);
				});
			}
		});
	}

	static boolean controlledWrench(Level level, BlockPos anyPart, SpawnSink spawnSink) {
		BlockPos anchor = resolveAnchor(level, anyPart);
		PatternStorageCoreBlockEntity lower = lower(level, anchor);
		if (lower == null)
			return false;
		Entity librarian = prepareSafeLibrarian(level, anchor, lower);
		BlockState lowerState = level.getBlockState(anchor);
		BlockState lectern = Blocks.LECTERN.defaultBlockState()
			.setValue(net.minecraft.world.level.block.LecternBlock.FACING,
				lowerState.getValue(PatternStorageCoreBlock.FACING));
		return runControlledRemoval(new ControlledRemovalOps() {
			@Override
			public boolean emitRequiredOutput() {
				return true;
			}

			@Override
			public boolean tryReleaseEntity() {
				return librarian != null && spawnSink.add(librarian);
			}

			@Override
			public void rollbackPreparedOutput() {}

			@Override
			public void markPendingSafeRelease() {
				lower.markPendingSafeRelease();
			}

			@Override
			public void commitSuccessfulRelease() {
				withRemovalGuard(level, anchor, () -> {
					lower.clearSnapshot();
					removeCoreHalf(level, anchor.above());
					level.setBlock(anchor, lectern, REPLACE_FLAGS);
				});
			}
		});
	}

	static RemovalDisposition runForcedRemoval(ForcedRemovalOps operations) {
		if (operations.tryReleaseEntity()) {
			operations.commitSuccessfulRelease();
			return RemovalDisposition.CALL_SUPER;
		}
		if (operations.tryDropRecoveryBox()) {
			operations.commitSuccessfulRelease();
			return RemovalDisposition.CALL_SUPER;
		}
		return operations.restoreAfterDoubleFailure();
	}

	static boolean runControlledRemoval(ControlledRemovalOps operations) {
		if (!operations.emitRequiredOutput()) {
			operations.markPendingSafeRelease();
			return false;
		}
		if (!operations.tryReleaseEntity()) {
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

	private static @Nullable PlayerBreakOutput playerBreakOutput(Level level, BlockPos anchor,
		PatternStorageCoreBlockEntity lower, @Nullable Player player, ItemStack tool) {
		if (!(level instanceof ServerLevel serverLevel) || player == null)
			return null;
		if (player.isCreative())
			return new PlayerBreakOutput(List.of());
		BlockState lowerState = level.getBlockState(anchor);
		if (!PatternStorageCoreBlock.isHalf(lowerState, DoubleBlockHalf.LOWER))
			return null;
		List<ItemEntity> drops = Block.getDrops(lowerState, serverLevel, anchor, lower, player, tool)
			.stream()
			.filter(stack -> !stack.isEmpty())
			.map(stack -> new ItemEntity(level, anchor.getX() + 0.5, anchor.getY() + 0.5,
				anchor.getZ() + 0.5, stack))
			.toList();
		return new PlayerBreakOutput(drops);
	}

	private static final class PlayerBreakOutput {
		private final List<ItemEntity> drops;
		private final List<ItemEntity> emitted = new ArrayList<>();

		private PlayerBreakOutput(List<ItemEntity> drops) {
			this.drops = drops;
		}

		private boolean emit(SpawnSink spawnSink) {
			for (ItemEntity drop : drops) {
				if (!spawnSink.add(drop)) {
					rollback();
					return false;
				}
				emitted.add(drop);
			}
			return true;
		}

		private void rollback() {
			emitted.forEach(Entity::discard);
			emitted.clear();
		}
	}

	private static @Nullable Entity prepareSafeLibrarian(Level level, BlockPos anchor,
		PatternStorageCoreBlockEntity lower) {
		ItemStack snapshot = lower.snapshot();
		if (snapshot.isEmpty())
			return null;
		Entity librarian = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(snapshot, level);
		return librarian != null && moveToSafeRelease(level, anchor, librarian) ? librarian : null;
	}

	private static boolean moveToSafeRelease(Level level, BlockPos anchor, Entity entity) {
		for (BlockPos candidate : releaseCandidates(anchor)) {
			if (!level.getWorldBorder().isWithinBounds(candidate))
				continue;
			Vec3 target = Vec3.atBottomCenterOf(candidate);
			AABB movedBounds = entity.getBoundingBox().move(target.subtract(entity.position()));
			if (!level.noCollision(entity, movedBounds))
				continue;
			entity.moveTo(target.x, target.y, target.z, entity.getYRot(), entity.getXRot());
			return true;
		}
		return false;
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
		BlockState lowerState, BlockState upperState, ItemStack originalSnapshot) {
		return withRemovalGuard(level, anchor, () -> {
			level.removeBlockEntity(anchor);
			if (PatternStorageCoreBlock.isHalf(level.getBlockState(anchor), DoubleBlockHalf.LOWER))
				level.setBlock(anchor, Blocks.AIR.defaultBlockState(), REPLACE_FLAGS);
			if (PatternStorageCoreBlock.isHalf(level.getBlockState(anchor.above()), DoubleBlockHalf.UPPER))
				level.setBlock(anchor.above(), Blocks.AIR.defaultBlockState(), REPLACE_FLAGS);
			boolean lowerPlaced = level.setBlock(anchor, lowerState, REPLACE_FLAGS);
			boolean upperPlaced = lowerPlaced
				&& level.setBlock(anchor.above(), upperState, REPLACE_FLAGS);
			if (!(level.getBlockEntity(anchor) instanceof PatternStorageCoreBlockEntity restored))
				return RemovalDisposition.CALL_SUPER;
			restored.installLibrarianSnapshot(originalSnapshot.copy());
			boolean complete = upperPlaced && PatternStorageCoreBlock.isComplete(
				level.getBlockState(anchor), level.getBlockState(anchor.above()));
			return complete ? RemovalDisposition.RESTORED : RemovalDisposition.CALL_SUPER;
		});
	}

	private static void removeCounterpart(Level level, BlockPos anchor, BlockPos initiatingPos) {
		if (!initiatingPos.equals(anchor.above()))
			removeCoreHalf(level, anchor.above());
		if (!initiatingPos.equals(anchor))
			removeCoreHalf(level, anchor);
	}

	private static void removeCoreHalf(Level level, BlockPos pos) {
		if (level.getBlockState(pos).getBlock() instanceof PatternStorageCoreBlock)
			CBMultiBlockLifecycle.removeSilently(level, pos);
	}

	private static BlockPos resolveAnchor(Level level, BlockPos anyPart) {
		BlockState state = level.getBlockState(anyPart);
		if (state.getBlock() instanceof PatternStorageCoreBlock)
			return state.getValue(PatternStorageCoreBlock.HALF) == DoubleBlockHalf.LOWER
				? anyPart.immutable() : anyPart.below().immutable();
		return anyPart.immutable();
	}

	private static @Nullable PatternStorageCoreBlockEntity lower(Level level, BlockPos anchor) {
		return level.getBlockEntity(anchor) instanceof PatternStorageCoreBlockEntity lower ? lower : null;
	}

	static boolean isRemovalActive(Object levelIdentity, BlockPos anchor) {
		Set<RemovalKey> active = ACTIVE_REMOVALS.get();
		boolean present = active.contains(new RemovalKey(levelIdentity, anchor));
		if (active.isEmpty())
			ACTIVE_REMOVALS.remove();
		return present;
	}

	static void withRemovalGuard(Object levelIdentity, BlockPos anchor, Runnable mutation) {
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
