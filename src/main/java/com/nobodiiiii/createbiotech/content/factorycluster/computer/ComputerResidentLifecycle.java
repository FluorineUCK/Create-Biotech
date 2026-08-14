package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import dev.ryanhcode.sable.companion.SubLevelAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Transaction core shared by controlled player breaks and forced replacement removal. */
final class ComputerResidentLifecycle {
	private static final ThreadLocal<Set<RemovalKey>> ACTIVE_REMOVALS =
		ThreadLocal.withInitial(HashSet::new);
	private static final List<Direction> RELEASE_DIRECTIONS = List.of(Direction.UP, Direction.NORTH,
		Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN);

	private ComputerResidentLifecycle() {}

	enum RemovalDisposition { CALL_SUPER, RESTORED }

	record ResidentIdentity(UUID uuid, ResourceLocation type) {
		ResidentIdentity {
			Objects.requireNonNull(uuid, "uuid");
			Objects.requireNonNull(type, "type");
		}
	}

	interface ResidentHandle {
		UUID uuid();
		ResourceLocation type();
		Vec3 position();
		AABB bounds();
		void moveTo(Vec3 target);
		void setHealthToOne();
	}

	interface SpawnOps {
		@Nullable ResidentIdentity inspect(ItemStack snapshot);
		@Nullable ResidentHandle findLoaded(UUID uuid);
		@Nullable ResidentHandle decode(ItemStack snapshot);
		boolean isLoaded(BlockPos pos);
		boolean sameSpace(BlockPos first, BlockPos second);
		Vec3 project(Position local);
		boolean noCollision(ResidentHandle resident, AABB movedBounds, Vec3 target);
		boolean addResident(ResidentHandle resident);
		boolean confirmResident(ResidentIdentity identity);
		void discard(ResidentHandle resident);
		boolean addRecovery(ItemStack snapshot, byte[] serializedSnapshot, Vec3 target);
		void rollbackRecovery();
		boolean emitComputerLoot(Vec3 target);
		void rollbackComputerLoot();
	}

	interface RestoreOps {
		void clearSource();
		boolean commitFinalRemoval();
		boolean restore(CompoundTag fullServerNbt);
	}

	@FunctionalInterface
	interface StationaryRemoval {
		RemovalDisposition remove();
	}

	@FunctionalInterface
	interface ControlledRemoval {
		boolean remove();
	}

	record RemovalInput(BlockPos computerPos, ItemStack snapshot, byte[] serializedSnapshot,
		@Nullable Tag rawResident, CompoundTag fullServerNbt) {
		RemovalInput {
			computerPos = Objects.requireNonNull(computerPos, "computerPos").immutable();
			snapshot = Objects.requireNonNull(snapshot, "snapshot").copy();
			serializedSnapshot = Objects.requireNonNull(serializedSnapshot, "serializedSnapshot").clone();
			rawResident = rawResident == null ? null : rawResident.copy();
			fullServerNbt = Objects.requireNonNull(fullServerNbt, "fullServerNbt").copy();
		}

		@Override public ItemStack snapshot() { return snapshot.copy(); }
		@Override public byte[] serializedSnapshot() { return serializedSnapshot.clone(); }
		@Override public Tag rawResident() { return rawResident == null ? null : rawResident.copy(); }
		@Override public CompoundTag fullServerNbt() { return fullServerNbt.copy(); }
	}

	static RemovalDisposition runRemovalEntry(boolean isMoving, StationaryRemoval stationaryRemoval) {
		return isMoving ? RemovalDisposition.CALL_SUPER : stationaryRemoval.remove();
	}

	static RemovalDisposition onRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, boolean isMoving, @Nullable ComputerBlockEntity oldComputer) {
		return runRemovalEntry(isMoving, () -> onStationaryRemove(state, level, pos,
			newState, oldComputer));
	}

	private static RemovalDisposition onStationaryRemove(BlockState state, Level level, BlockPos pos,
		BlockState newState, @Nullable ComputerBlockEntity oldComputer) {
		if (level.isClientSide || state.getBlock() == newState.getBlock()
			|| !(state.getBlock() instanceof ComputerBlock) || oldComputer == null
			|| isRemovalActive(level, pos) || !oldComputer.hasResidentSource())
			return RemovalDisposition.CALL_SUPER;
		return runGuardedRemoval(level, pos, () -> {
			RemovalInput input = removalInput(level, pos, oldComputer);
			WorldRestoreOps restore = WorldRestoreOps.forForced(level, pos, state, newState,
				oldComputer, input.fullServerNbt());
			return runForcedRemoval(input, new WorldSpawnOps(level, pos, null), restore);
		});
	}

	static boolean controlledPlayerBreak(ServerLevel level, BlockPos pos, BlockState oldState,
		ComputerBlockEntity oldComputer, @Nullable Player player, ItemStack tool) {
		return runGuardedControlledBreak(level, pos, () -> {
			if (!oldComputer.hasResidentSource()) return false;
			RemovalInput input = removalInput(level, pos, oldComputer);
			PlayerBreakOutput output = PlayerBreakOutput.prepare(level, pos, oldState, oldComputer,
				player, tool);
			WorldRestoreOps restore = WorldRestoreOps.forControlled(level, pos, oldState,
				oldComputer, input.fullServerNbt());
			return runControlledBreak(input, new WorldSpawnOps(level, pos, output), restore);
		});
	}

	static boolean releaseBeforeExternalDestruction(Level level, BlockPos pos, BlockState oldState,
		ComputerBlockEntity oldComputer, ControlledRemoval destruction) {
		return runGuardedControlledBreak(level, pos, () -> {
			if (!oldComputer.hasResidentSource()) return destruction.remove();
			RemovalInput input = removalInput(level, pos, oldComputer);
			WorldSpawnOps spawns = new WorldSpawnOps(level, pos, null);
			WorldRestoreOps restore = WorldRestoreOps.forForced(level, pos, oldState,
				Blocks.AIR.defaultBlockState(), oldComputer, input.fullServerNbt());
			ReleaseResult release = attemptResidentOrRecovery(input, spawns);
			if (!release.confirmed()) {
				restore.restore(input.fullServerNbt());
				return false;
			}
			restore.clearSource();
			if (!destruction.remove() || !restore.commitFinalRemoval()) {
				release.rollback(spawns);
				restore.restore(input.fullServerNbt());
				return false;
			}
			release.commit();
			return true;
		});
	}

	static boolean suppressUnguardedOccupiedDrop(ComputerBlockEntity computer) {
		if (!computer.hasResidentSource()) return false;
		Level level = computer.getLevel();
		return level == null || !isRemovalActive(level, computer.getBlockPos());
	}

	private static RemovalInput removalInput(Level level, BlockPos pos,
		ComputerBlockEntity computer) {
		CompoundTag full = computer.saveWithoutMetadata(level.registryAccess());
		ItemStack snapshot = computer.residentSnapshot();
		return new RemovalInput(pos, snapshot, computer.serializedResident(level.registryAccess()),
			computer.rawResidentTag(), full);
	}

	static RemovalDisposition runGuardedRemoval(Object levelIdentity, BlockPos pos,
		StationaryRemoval stationaryRemoval) {
		return isRemovalActive(levelIdentity, pos) ? RemovalDisposition.CALL_SUPER
			: withRemovalGuard(levelIdentity, pos, stationaryRemoval::remove);
	}

	static boolean runGuardedControlledBreak(Object levelIdentity, BlockPos pos,
		ControlledRemoval controlledRemoval) {
		return !isRemovalActive(levelIdentity, pos)
			&& withRemovalGuard(levelIdentity, pos, controlledRemoval::remove);
	}

	static RemovalDisposition runForcedRemoval(RemovalInput input, SpawnOps spawns,
		RestoreOps restore) {
		ReleaseResult release = attemptResidentOrRecovery(input, spawns);
		if (!release.confirmed()) {
			restore.restore(input.fullServerNbt());
			return RemovalDisposition.RESTORED;
		}
		restore.clearSource();
		if (!restore.commitFinalRemoval()) {
			release.rollback(spawns);
			restore.restore(input.fullServerNbt());
			return RemovalDisposition.RESTORED;
		}
		release.commit();
		return RemovalDisposition.CALL_SUPER;
	}

	static boolean runControlledBreak(RemovalInput input, SpawnOps spawns, RestoreOps restore) {
		Vec3 lootTarget = spawns.project(Vec3.atCenterOf(input.computerPos()));
		if (!spawns.emitComputerLoot(lootTarget)) {
			spawns.rollbackComputerLoot();
			restore.restore(input.fullServerNbt());
			return false;
		}
		ReleaseResult release = attemptResidentOrRecovery(input, spawns);
		if (!release.confirmed()) {
			spawns.rollbackComputerLoot();
			restore.restore(input.fullServerNbt());
			return false;
		}
		restore.clearSource();
		if (!restore.commitFinalRemoval()) {
			release.rollback(spawns);
			spawns.rollbackComputerLoot();
			restore.restore(input.fullServerNbt());
			return false;
		}
		release.commit();
		return true;
	}

	private static ReleaseResult attemptResidentOrRecovery(RemovalInput input, SpawnOps spawns) {
		if (input.rawResident() != null || input.snapshot().isEmpty())
			return ReleaseResult.failed();
		ResidentIdentity expected = spawns.inspect(input.snapshot());
		if (expected == null) return ReleaseResult.failed();

		ResidentHandle loaded = spawns.findLoaded(expected.uuid());
		if (loaded != null) {
			if (matches(loaded, expected)) return ReleaseResult.existing(loaded);
			return recover(input, spawns);
		}

		ResidentHandle resident = spawns.decode(input.snapshot());
		if (resident != null && matches(resident, expected)) {
			boolean positioned = false;
			for (Direction direction : RELEASE_DIRECTIONS) {
				BlockPos localBlock = input.computerPos().relative(direction);
				if (!spawns.isLoaded(localBlock)
					|| !spawns.sameSpace(input.computerPos(), localBlock)) continue;
				Vec3 target = spawns.project(Vec3.atBottomCenterOf(localBlock));
				if (!collisionFree(spawns, resident, target)) continue;
				resident.moveTo(target);
				positioned = true;
				break;
			}
			if (!positioned) {
				Vec3 fallback = spawns.project(Vec3.atCenterOf(input.computerPos()));
				if (collisionFree(spawns, resident, fallback)) {
					resident.moveTo(fallback);
					positioned = true;
				}
			}
			if (positioned) {
				if (spawns.addResident(resident) && spawns.confirmResident(expected))
					return ReleaseResult.spawned(resident);
			}
			spawns.discard(resident);
		} else if (resident != null) spawns.discard(resident);
		return recover(input, spawns);
	}

	private static ReleaseResult recover(RemovalInput input, SpawnOps spawns) {
		Vec3 target = spawns.project(Vec3.atCenterOf(input.computerPos()));
		return spawns.addRecovery(input.snapshot(), input.serializedSnapshot(), target)
			? ReleaseResult.recovery() : ReleaseResult.failed();
	}

	private static boolean collisionFree(SpawnOps spawns, ResidentHandle resident, Vec3 target) {
		AABB moved = resident.bounds().move(target.subtract(resident.position()));
		return spawns.noCollision(resident, moved, target);
	}

	private static boolean matches(ResidentHandle resident, ResidentIdentity expected) {
		return expected.uuid().equals(resident.uuid()) && expected.type().equals(resident.type());
	}

	private record ReleaseResult(boolean confirmed, @Nullable ResidentHandle resident,
		boolean spawnedResident, boolean recoveryOutput) {
		private static ReleaseResult failed() { return new ReleaseResult(false, null, false, false); }
		private static ReleaseResult existing(ResidentHandle resident) {
			return new ReleaseResult(true, resident, false, false);
		}
		private static ReleaseResult spawned(ResidentHandle resident) {
			return new ReleaseResult(true, resident, true, false);
		}
		private static ReleaseResult recovery() {
			return new ReleaseResult(true, null, false, true);
		}
		private void commit() {
			if (resident != null) resident.setHealthToOne();
		}
		private void rollback(SpawnOps spawns) {
			if (spawnedResident && resident != null) spawns.discard(resident);
			if (recoveryOutput) spawns.rollbackRecovery();
		}
	}

	private static Vec3 entityWorldPosition(net.minecraft.world.level.Level level,
		BlockPos computerPos, Position localCenter) {
		return SubLevelCompat.toWorld(level, computerPos, localCenter);
	}

	private static final class EntityResident implements ResidentHandle {
		private final Entity entity;

		private EntityResident(Entity entity) { this.entity = entity; }
		@Override public UUID uuid() { return entity.getUUID(); }
		@Override public ResourceLocation type() { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()); }
		@Override public Vec3 position() { return entity.position(); }
		@Override public AABB bounds() { return entity.getBoundingBox(); }
		@Override public void moveTo(Vec3 target) {
			entity.moveTo(target.x, target.y, target.z, entity.getYRot(), entity.getXRot());
		}
		@Override public void setHealthToOne() {
			if (entity instanceof LivingEntity living) living.setHealth(1.0F);
		}
	}

	private static final class WorldSpawnOps implements SpawnOps {
		private final Level level;
		private final BlockPos computerPos;
		@Nullable private final PlayerBreakOutput output;
		@Nullable private RecoveryItemEntity recoveryOutput;

		private WorldSpawnOps(Level level, BlockPos computerPos,
			@Nullable PlayerBreakOutput output) {
			this.level = level;
			this.computerPos = computerPos.immutable();
			this.output = output;
		}

		@Override
		public @Nullable ResidentIdentity inspect(ItemStack snapshot) {
			Tag raw = CBItemData.getOrEmpty(snapshot).get("CapturedEntity");
			if (!(raw instanceof CompoundTag captured)
				|| !captured.contains("UUID", Tag.TAG_INT_ARRAY) || !captured.hasUUID("UUID")
				|| !captured.contains("id", Tag.TAG_STRING)) return null;
			ResourceLocation type = ResourceLocation.tryParse(captured.getString("id"));
			return type != null && BuiltInRegistries.ENTITY_TYPE.containsKey(type)
				? new ResidentIdentity(captured.getUUID("UUID"), type) : null;
		}

		@Override
		public @Nullable ResidentHandle findLoaded(UUID uuid) {
			if (!(level instanceof ServerLevel serverLevel)) return null;
			for (ServerLevel candidate : serverLevel.getServer().getAllLevels()) {
				Entity found = candidate.getEntity(uuid);
				if (found != null) return new EntityResident(found);
			}
			return null;
		}

		@Override
		public @Nullable ResidentHandle decode(ItemStack snapshot) {
			Entity decoded = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(snapshot, level);
			return decoded == null ? null : new EntityResident(decoded);
		}

		@Override public boolean isLoaded(BlockPos pos) { return CBMultiBlockLifecycle.isLoaded(level, pos); }
		@Override public boolean sameSpace(BlockPos first, BlockPos second) {
			return SubLevelCompat.sameSpace(level, first, second);
		}
		@Override public Vec3 project(Position local) {
			return entityWorldPosition(level, computerPos, local);
		}
		@Override public boolean noCollision(ResidentHandle resident, AABB movedBounds, Vec3 target) {
			return resident instanceof EntityResident wrapped && level.noCollision(wrapped.entity, movedBounds);
		}
		@Override public boolean addResident(ResidentHandle resident) {
			return resident instanceof EntityResident wrapped && level.addFreshEntity(wrapped.entity);
		}
		@Override public boolean confirmResident(ResidentIdentity identity) {
			ResidentHandle loaded = findLoaded(identity.uuid());
			return loaded != null && matches(loaded, identity);
		}
		@Override public void discard(ResidentHandle resident) {
			if (resident instanceof EntityResident wrapped) wrapped.entity.discard();
		}
		@Override public boolean addRecovery(ItemStack snapshot, byte[] serializedSnapshot, Vec3 target) {
			RecoveryItemEntity recovery = new RecoveryItemEntity(level, target.x, target.y, target.z,
				snapshot.copy());
			if (!level.addFreshEntity(recovery) || recovery.isRemoved()) {
				recovery.discard();
				return false;
			}
			recoveryOutput = recovery;
			return true;
		}
		@Override public void rollbackRecovery() {
			if (recoveryOutput != null) recoveryOutput.discard();
			recoveryOutput = null;
		}
		@Override public boolean emitComputerLoot(Vec3 target) {
			return output != null && output.emit(level, target);
		}
		@Override public void rollbackComputerLoot() {
			if (output != null) output.rollback();
		}
	}

	private static final class WorldRestoreOps implements RestoreOps {
		private final Level level;
		private final BlockPos pos;
		private final BlockState oldState;
		private final BlockState committedState;
		private final boolean placeCommittedState;
		private final ComputerBlockEntity original;
		private final CompoundTag expected;

		private WorldRestoreOps(Level level, BlockPos pos, BlockState oldState,
			BlockState committedState, boolean placeCommittedState,
			ComputerBlockEntity original, CompoundTag expected) {
			this.level = level;
			this.pos = pos.immutable();
			this.oldState = oldState;
			this.committedState = committedState;
			this.placeCommittedState = placeCommittedState;
			this.original = original;
			this.expected = expected.copy();
		}

		private static WorldRestoreOps forControlled(Level level, BlockPos pos,
			BlockState oldState, ComputerBlockEntity original, CompoundTag expected) {
			return new WorldRestoreOps(level, pos, oldState, Blocks.AIR.defaultBlockState(), true,
				original, expected);
		}

		private static WorldRestoreOps forForced(Level level, BlockPos pos, BlockState oldState,
			BlockState newState, ComputerBlockEntity original, CompoundTag expected) {
			return new WorldRestoreOps(level, pos, oldState, newState, false, original, expected);
		}

		@Override public void clearSource() { original.clearResidentSource(); }
		@Override public boolean commitFinalRemoval() {
			boolean changed = !placeCommittedState || level.setBlock(pos, committedState,
				Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			return changed && level.getBlockState(pos).equals(committedState)
				&& (!placeCommittedState
					|| !(level.getBlockEntity(pos) instanceof ComputerBlockEntity));
		}

		@Override
		public boolean restore(CompoundTag fullServerNbt) {
			return withRemovalGuard(level, pos, () -> {
				CompoundTag authoritative = fullServerNbt.copy();
				for (int attempt = 0; attempt < 2; attempt++)
					if (restoreOnce(authoritative)) return true;
				return false;
			});
		}

		private boolean restoreOnce(CompoundTag authoritative) {
			if (level.getBlockState(pos).equals(oldState)
				&& level.getBlockEntity(pos) instanceof ComputerBlockEntity current) {
				current.loadWithComponents(authoritative.copy(), level.registryAccess());
				return matchesExpected(current);
			}
			level.removeBlockEntity(pos);
			if (level.getBlockState(pos).equals(oldState))
				level.setBlock(pos, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			if (!level.getBlockState(pos).equals(oldState))
				level.setBlock(pos, oldState, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			if (!(level.getBlockEntity(pos) instanceof ComputerBlockEntity restored)) return false;
			restored.loadWithComponents(authoritative.copy(), level.registryAccess());
			return matchesExpected(restored);
		}

		private boolean matchesExpected(ComputerBlockEntity computer) {
			return level.getBlockState(pos).equals(oldState)
				&& computer.saveWithoutMetadata(level.registryAccess()).equals(expected);
		}
	}

	private static final class PlayerBreakOutput {
		private final List<ItemStack> stacks;
		private final List<ItemEntity> emitted = new java.util.ArrayList<>();

		private PlayerBreakOutput(List<ItemStack> stacks) { this.stacks = stacks; }

		private static @Nullable PlayerBreakOutput prepare(ServerLevel level, BlockPos pos,
			BlockState state, ComputerBlockEntity computer, @Nullable Player player, ItemStack tool) {
			if (player == null) return null;
			List<ItemStack> drops = player.isCreative() ? List.of()
				: Block.getDrops(state, level, pos, computer, player, tool).stream()
					.filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
			for (ItemStack stack : drops)
				if (stack.get(DataComponents.BLOCK_ENTITY_DATA) != null
					|| CapturedEntityBoxHelper.hasCapturedEntity(stack)) return null;
			return new PlayerBreakOutput(drops);
		}

		private boolean emit(Level level, Vec3 target) {
			for (ItemStack stack : stacks) {
				ItemEntity entity = new ItemEntity(level, target.x, target.y, target.z, stack.copy());
				if (!level.addFreshEntity(entity) || entity.isRemoved()) {
					entity.discard();
					rollback();
					return false;
				}
				emitted.add(entity);
			}
			return true;
		}

		private void rollback() {
			emitted.forEach(Entity::discard);
			emitted.clear();
		}
	}

	/** Prevents the captured-box item hook from replacing the dedicated recovery entity. */
	private static final class RecoveryItemEntity extends ItemEntity {
		private RecoveryItemEntity(Level level, double x, double y, double z, ItemStack stack) {
			super(level, x, y, z, stack);
		}
	}

	static Vec3 entityWorldPosition(@Nullable SubLevelAccess subLevel, Position localCenter) {
		return SubLevelCompat.toWorld(subLevel, localCenter);
	}

	static boolean isRemovalActive(Object levelIdentity, BlockPos pos) {
		Set<RemovalKey> active = ACTIVE_REMOVALS.get();
		boolean present = active.contains(new RemovalKey(levelIdentity, pos));
		if (active.isEmpty()) ACTIVE_REMOVALS.remove();
		return present;
	}

	static void withRemovalGuard(Object levelIdentity, BlockPos pos, Runnable mutation) {
		withRemovalGuard(levelIdentity, pos, () -> {
			mutation.run();
			return null;
		});
	}

	static <T> T withRemovalGuard(Object levelIdentity, BlockPos pos, Supplier<T> mutation) {
		Set<RemovalKey> active = ACTIVE_REMOVALS.get();
		RemovalKey key = new RemovalKey(levelIdentity, pos);
		boolean added = active.add(key);
		try {
			return mutation.get();
		} finally {
			if (added) active.remove(key);
			if (active.isEmpty()) ACTIVE_REMOVALS.remove();
		}
	}

	private static final class RemovalKey {
		private final Object levelIdentity;
		private final BlockPos pos;

		private RemovalKey(Object levelIdentity, BlockPos pos) {
			this.levelIdentity = levelIdentity;
			this.pos = pos.immutable();
		}

		@Override public boolean equals(Object other) {
			return other instanceof RemovalKey key && key.levelIdentity == levelIdentity
				&& key.pos.equals(pos);
		}

		@Override public int hashCode() {
			return 31 * System.identityHashCode(levelIdentity) + pos.hashCode();
		}
	}
}
