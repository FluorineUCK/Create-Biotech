package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ComputerBlockEntityTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final UUID LOCAL = UUID.fromString("20000000-0000-0000-0000-000000000002");
	private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID MEMBER = UUID.fromString("30000000-0000-0000-0000-000000000003");
	private static final ComputerProfile PROFILE = ComputerProfile.forKind(NodeKind.LIBRARIAN, 3, 0)
		.orElseThrow();

	@Test
	void occupiedActiveAndUncertainTopologyRejectionsRemainByteIdentical() {
		ComputerBlockEntity occupied = computer();
		occupied.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "resident"), PROFILE);
		assertRejectedUnchanged(occupied, Probe.success());

		ComputerBlockEntity frozen = computer();
		Topology topology = frozenTopology(frozen.computerId().orElseThrow());
		frozen.applyTopologyState(topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH));
		assertRejectedUnchanged(frozen, Probe.success());

		CompoundTag malformed = save(computer(), false);
		malformed.getCompound("ComputerData").putString("ComputerId", "uncertain");
		ComputerBlockEntity uncertain = computer();
		uncertain.read(malformed, REGISTRIES, false);
		assertRejectedUnchanged(uncertain, Probe.success());
	}

	@Test
	void pendingHotAddAcceptsOnceWithoutChangingEpochMembershipOrderOrLatches() {
		ComputerBlockEntity computer = computer();
		Topology topology = topology(computer.computerId().orElseThrow(), true);
		computer.applyTopologyState(topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH, EpochFault.DEPTH));
		CompoundTag epochBefore = topology.epoch.save();
		Set<EpochFault> faultsBefore = computer.latchedEpochFaults();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "pending");

		assertEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, Probe.success()));
		assertTrue(computer.topologyDirty());
		assertEquals(epochBefore, computer.epoch().orElseThrow().save());
		assertEquals(faultsBefore, computer.latchedEpochFaults());
		ItemStack second = capturedBox(LOCAL, "minecraft:villager", "second");
		Tag heldBefore = second.save(REGISTRIES);
		CompoundTag serverBefore = save(computer, false);
		assertEquals(ComputerInstallResult.OCCUPIED,
			computer.installResident(second, Probe.success()));
		assertEquals(heldBefore, second.save(REGISTRIES));
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void validInstallCommitsSnapshotAndStrictProfileBeforeClearingHeldBoxAndNeverRecomputesProfile() {
		ComputerBlockEntity computer = computer();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "Ada");
		Probe probe = Probe.success();

		assertEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, probe));
		assertEquals(List.of("inspect", "clear"), probe.events);
		assertTrue(probe.committedBeforeClear);
		assertEquals(PROFILE, computer.installedProfile().orElseThrow());
		assertFalse(CBItemData.getOrEmpty(held).contains("CapturedEntity"));

		CompoundTag saved = save(computer, false);
		saved.getCompound("ComputerData").getCompound("Resident")
			.getCompound("components").putString("sensitive", "do-not-recompute");
		ComputerBlockEntity restored = computer();
		restored.read(saved, REGISTRIES, false);
		assertEquals(PROFILE, restored.installedProfile().orElseThrow());
	}

	@Test
	void newIdentityIsStableButMalformedPersistedIdentityRemainsRawAndAbsent() {
		ComputerBlockEntity computer = computer();
		UUID initial = computer.computerId().orElseThrow();
		assertEquals(initial, computer.computerId().orElseThrow());

		CompoundTag malformed = save(computer, false);
		malformed.getCompound("ComputerData").putString("ComputerId", "not-an-int-array");
		ComputerBlockEntity restored = computer();
		restored.read(malformed, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertEquals(StringTag.valueOf("not-an-int-array"),
			save(restored, false).getCompound("ComputerData").get("ComputerId"));
	}

	@Test
	void missingRequiredIdentityDoesNotEraseValidTopologyAndRemainsMissingOnResave() {
		ComputerBlockEntity source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), false);
		source.applyTopologyState(topology.record, topology.binding, true, null, Set.of());
		CompoundTag malformed = save(source, false);
		malformed.getCompound("ComputerData").remove("ComputerId");

		ComputerBlockEntity restored = computer();
		restored.read(malformed, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertTrue(restored.currentStructureRecord().isPresent());
		assertEquals(topology.binding, restored.bindingState());
		assertFalse(save(restored, false).getCompound("ComputerData").contains("ComputerId"));
	}

	@Test
	void strictServerRoundTripCoversResidentProfileStructureBindingEpochAndFaults() {
		ComputerBlockEntity source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), true);
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "round-trip"), PROFILE);
		source.applyTopologyState(topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH, EpochFault.DEPTH));

		CompoundTag saved = save(source, false);
		ComputerBlockEntity restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertAll(
			() -> assertEquals(source.computerId(), restored.computerId()),
			() -> assertEquals(source.installedProfile(), restored.installedProfile()),
			() -> assertEquals(source.currentStructureRecord(), restored.currentStructureRecord()),
			() -> assertEquals(source.bindingState(), restored.bindingState()),
			() -> assertEquals(source.bindingStateValid(), restored.bindingStateValid()),
			() -> assertEquals(source.epoch(), restored.epoch()),
			() -> assertEquals(source.latchedEpochFaults(), restored.latchedEpochFaults()),
			() -> assertEquals(saved, save(restored, false)));
	}

	@Test
	void absentCoordinatorRecordRequiresMatchingFrozenEpochWhileChildrenRemainRecoverable() {
		ComputerBlockEntity source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), true);
		ComputerStructureSnapshot currentWithoutCoordinator = snapshot(List.of(
			new ComputerStructureNode(source.computerId().orElseThrow(), address(BlockPos.ZERO), PROFILE)));
		ComputerStructureRecord missing = new ComputerStructureRecord(MEMBER, 7,
			topology.epoch.coordinatorId(), currentWithoutCoordinator);
		source.applyTopologyState(missing, topology.binding, true, topology.epoch, Set.of());
		CompoundTag valid = save(source, false);

		ComputerBlockEntity accepted = computer();
		accepted.read(valid, REGISTRIES, false);
		assertTrue(accepted.persistenceAvailable());

		CompoundTag noEpoch = valid.copy();
		noEpoch.getCompound("ComputerData").remove("Epoch");
		ComputerBlockEntity rejected = computer();
		rejected.read(noEpoch, REGISTRIES, false);
		assertFalse(rejected.persistenceAvailable());
		assertEquals(missing, rejected.currentStructureRecord().orElseThrow());
		assertEquals(valid.getCompound("ComputerData").get("Structure"),
			save(rejected, false).getCompound("ComputerData").get("Structure"));
	}

	@Test
	void corruptChildDoesNotEraseValidSiblingsAndIsResavedLosslesslyWithoutProfileRebuild() {
		ComputerBlockEntity source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), false);
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "raw"), PROFILE);
		source.applyTopologyState(topology.record, topology.binding, true, null, Set.of());
		CompoundTag corrupt = save(source, false);
		CompoundTag rawProfile = new CompoundTag();
		rawProfile.putString("Kind", "broken-but-recoverable");
		corrupt.getCompound("ComputerData").put("Profile", rawProfile);

		ComputerBlockEntity restored = computer();
		restored.read(corrupt, REGISTRIES, false);

		assertFalse(restored.persistenceAvailable());
		assertTrue(restored.computerId().isPresent());
		assertTrue(restored.currentStructureRecord().isPresent());
		assertTrue(restored.installedProfile().isEmpty());
		assertFalse(restored.residentSnapshot().isEmpty());
		assertEquals(rawProfile, save(restored, false).getCompound("ComputerData").get("Profile"));
	}

	@Test
	void unknownComputerRootIsOpaqueAndItsResidentCanOnlyTriggerLosslessRetention() {
		ComputerBlockEntity source = computer();
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "opaque"), PROFILE);
		CompoundTag saved = save(source, false);
		CompoundTag original = saved.getCompound("ComputerData").copy();
		original.putString("FutureUnknownRootKey", "preserve-exactly");
		saved.put("ComputerData", original.copy());

		ComputerBlockEntity restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertFalse(restored.persistenceAvailable());
		assertTrue(restored.hasResidentSource());
		assertEquals(original.get("Resident"), restored.rawResidentTag());
		assertEquals(original, save(restored, false).get("ComputerData"));
	}

	@Test
	void persistedPassengerResidentAndInvalidBindingFlagRemainRawAndUnavailable() {
		ComputerBlockEntity source = computer();
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "passenger"), PROFILE);
		CompoundTag saved = save(source, false);
		CompoundTag captured = CBItemData.getOrEmpty(ItemStack.parseOptional(REGISTRIES,
			saved.getCompound("ComputerData").getCompound("Resident"))).getCompound("CapturedEntity");
		captured.put("Passengers", new ListTag() {{ add(new CompoundTag()); }});
		ItemStack passenger = capturedBox(LOCAL, "minecraft:villager", "passenger");
		CompoundTag passengerData = CBItemData.getOrEmpty(passenger);
		passengerData.getCompound("CapturedEntity").put("Passengers", captured.get("Passengers").copy());
		CBItemData.set(passenger, passengerData);
		saved.getCompound("ComputerData").put("Resident", passenger.save(REGISTRIES));
		saved.getCompound("ComputerData").putBoolean("BindingInvalid", true);

		ComputerBlockEntity restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertFalse(restored.persistenceAvailable());
		assertTrue(restored.residentSnapshot().isEmpty());
		assertEquals(saved.getCompound("ComputerData").get("Resident"), restored.rawResidentTag());
		assertFalse(restored.bindingStateValid());
	}

	@Test
	void serverAndClientWritesAreStrictlyDisjoint() {
		ComputerBlockEntity computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "projection"), PROFILE);
		computer.setDisplayState(ComputerDisplayState.RUN);

		CompoundTag server = save(computer, false);
		CompoundTag client = save(computer, true);

		assertEquals(Set.of("ComputerData"), server.getAllKeys());
		assertFalse(server.contains("ComputerClientState"));
		assertEquals(Set.of("ComputerClientState"), client.getAllKeys());
		assertFalse(client.contains("ComputerData"));
		assertEquals(ComputerDisplayState.RUN,
			ComputerClientState.load(client.getCompound("ComputerClientState")).orElseThrow().displayState());
	}

	@Test
	void malformedClientProjectionIsIgnoredAtomicallyAndCannotAlterServerFields() {
		ComputerBlockEntity computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server"), PROFILE);
		CompoundTag serverBefore = save(computer, false);
		ComputerClientState clientBefore = computer.clientState();
		CompoundTag hostile = serverBefore.copy();
		hostile.put("ComputerClientState", new CompoundTag());

		computer.read(hostile, REGISTRIES, true);

		assertEquals(clientBefore, computer.clientState());
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void validClientProjectionWithExtraRootKeyIsRejectedAtomically() {
		ComputerBlockEntity computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server-root"), PROFILE);
		CompoundTag serverBefore = save(computer, false);
		ComputerClientState clientBefore = computer.clientState();
		CompoundTag hostile = new CompoundTag();
		hostile.put("ComputerClientState", new ComputerClientState(true, PROFILE.kind(),
			PROFILE.slots(), PROFILE.depth(), ComputerDisplayState.RUN).save());
		hostile.putString("ExtraRootKey", "must-reject-whole-packet");

		computer.read(hostile, REGISTRIES, true);

		assertEquals(clientBefore, computer.clientState());
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void clientWriteReplacesPreexistingRootWithExactProjection() {
		ComputerBlockEntity computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server-only"),
			PROFILE);
		CompoundTag reusedRoot = new CompoundTag();
		reusedRoot.putString("ComputerData", "must-not-survive-client-write");
		reusedRoot.putString("ExtraRootKey", "must-not-survive-client-write");

		computer.write(reusedRoot, REGISTRIES, true);

		assertEquals(Set.of("ComputerClientState"), reusedRoot.getAllKeys());
		assertTrue(ComputerClientState.load(reusedRoot.getCompound("ComputerClientState")).isPresent());
	}

	@Test
	void clientProjectionRecursivelyExcludesResidentTopologyAndUniqueSensitiveStrings() {
		String sensitive = "unique-secret-trade-and-name-8021";
		ComputerBlockEntity computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", sensitive), PROFILE);
		Topology topology = topology(computer.computerId().orElseThrow(), true);
		computer.applyTopologyState(topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH));

		CompoundTag client = save(computer, true);
		Set<String> keys = new HashSet<>();
		List<String> strings = new ArrayList<>();
		collect(client, keys, strings);
		Set<String> forbidden = Set.of("CapturedEntity", "UUID", "ComputerId", "CoordinatorId",
			"EpochId", "Offers", "Recipes", "Brain", "Inventory", "NoAI", "DespawnDelay",
			"ClusterId", "LogisticsBindings", "Authority", "Revision", "StructureMemberId",
			"Epoch", "Nodes", "Address", "Frames", "Mailboxes");

		assertTrue(java.util.Collections.disjoint(keys, forbidden));
		assertFalse(strings.contains(sensitive));
	}

	@Test
	void structureRecordAndClientProjectionRejectUnknownKeysWrongTypesAndOutOfRangeValues() {
		ComputerStructureRecord record = topology(LOCAL, false).record;
		assertEquals(record, ComputerStructureRecord.load(record.save()).orElseThrow());
		CompoundTag extra = record.save();
		extra.putInt("Extra", 1);
		assertTrue(ComputerStructureRecord.load(extra).isEmpty());
		CompoundTag negative = record.save();
		negative.putLong("Revision", -1);
		assertTrue(ComputerStructureRecord.load(negative).isEmpty());

		ComputerClientState valid = new ComputerClientState(true, NodeKind.LIBRARIAN, 16, 4,
			ComputerDisplayState.SLP);
		assertEquals(valid, ComputerClientState.load(valid.save()).orElseThrow());
		CompoundTag malformed = valid.save();
		malformed.putByte("RenderResident", (byte) 2);
		assertTrue(ComputerClientState.load(malformed).isEmpty());
		CompoundTag impossible = valid.save();
		impossible.putInt("Slots", 15);
		assertTrue(ComputerClientState.load(impossible).isEmpty());
	}

	private static void assertRejectedUnchanged(ComputerBlockEntity computer,
		ComputerBlockEntity.InstallOps probe) {
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "unchanged");
		Tag heldBefore = held.save(REGISTRIES);
		CompoundTag serverBefore = save(computer, false);
		assertNotEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, probe));
		assertEquals(heldBefore, held.save(REGISTRIES));
		assertEquals(serverBefore, save(computer, false));
	}

	private static CompoundTag save(ComputerBlockEntity computer, boolean client) {
		CompoundTag tag = new CompoundTag();
		computer.write(tag, REGISTRIES, client);
		return tag;
	}

	private static ComputerBlockEntity computer() {
		bootstrap();
		return new ComputerBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
	}

	private static ItemStack capturedBox(UUID uuid, String type, String sensitive) {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag captured = new CompoundTag();
		captured.putString("id", type);
		captured.putUUID("UUID", uuid);
		captured.putString("CustomName", sensitive);
		captured.putInt("DespawnDelay", 1200);
		captured.put("Offers", new CompoundTag());
		captured.put("Brain", new CompoundTag());
		captured.put("Inventory", new ListTag());
		captured.putBoolean("NoAI", true);
		CompoundTag itemData = new CompoundTag();
		itemData.put("CapturedEntity", captured);
		CBItemData.set(stack, itemData);
		return stack;
	}

	private static Topology topology(UUID localId, boolean epoch) {
		ComputerStructureSnapshot current = snapshot(List.of(
			new ComputerStructureNode(OTHER, address(new BlockPos(1, 1, 1)), PROFILE),
			new ComputerStructureNode(localId, address(BlockPos.ZERO), epoch ? null : PROFILE)));
		ComputerStructureSnapshot frozen = snapshot(List.of(
			new ComputerStructureNode(OTHER, address(new BlockPos(1, 1, 1)), PROFILE)));
		ClusterEpoch active = epoch ? ClusterEpoch.freeze(UUID.randomUUID(), MEMBER, frozen) : null;
		UUID coordinator = active == null ? OTHER : active.coordinatorId();
		ComputerStructureRecord record = new ComputerStructureRecord(MEMBER, 7, coordinator, current);
		ClusterBinding binding = new ClusterBinding(active == null ? UUID.randomUUID() : active.clusterId(), 4,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, MEMBER),
			List.of(new LogisticsBinding(UUID.randomUUID(), "network")));
		return new Topology(record, binding, active);
	}

	private static Topology frozenTopology(UUID localId) {
		ComputerStructureSnapshot current = snapshot(List.of(
			new ComputerStructureNode(OTHER, address(new BlockPos(1, 1, 1)), PROFILE),
			new ComputerStructureNode(localId, address(BlockPos.ZERO), PROFILE)));
		ClusterEpoch active = ClusterEpoch.freeze(UUID.randomUUID(), MEMBER, current);
		ComputerStructureRecord record = new ComputerStructureRecord(MEMBER, 7,
			active.coordinatorId(), current);
		ClusterBinding binding = new ClusterBinding(active.clusterId(), 4,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, MEMBER), List.of());
		return new Topology(record, binding, active);
	}

	private static ComputerStructureSnapshot snapshot(List<ComputerStructureNode> nodes) {
		List<ComputerStructureNode> sorted = nodes.stream()
			.sorted(java.util.Comparator.comparing(ComputerStructureNode::computerId)).toList();
		Set<BlockPos> casing = new HashSet<>();
		for (int x = 0; x <= 2; x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2)
						casing.add(new BlockPos(x, y, z));
		return new ComputerStructureSnapshot(new BoundingBox(0, 0, 0, 2, 2, 2), sorted,
			casing, Set.of(new ChunkPos(0, 0)));
	}

	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(Level.OVERWORLD, null, pos);
	}

	private static void collect(Tag tag, Set<String> keys, List<String> strings) {
		if (tag instanceof CompoundTag compound) {
			for (String key : compound.getAllKeys()) {
				keys.add(key);
				collect(compound.get(key), keys, strings);
			}
		} else if (tag instanceof ListTag list) {
			list.forEach(child -> collect(child, keys, strings));
		} else if (tag instanceof StringTag string) {
			strings.add(string.getAsString());
		}
	}

	private static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Topology(ComputerStructureRecord record, ClusterBinding binding,
		ClusterEpoch epoch) {}

	private static final class Probe implements ComputerBlockEntity.InstallOps {
		private final boolean box;
		private final boolean captured;
		private final ComputerBlockEntity.InstallInspection inspection;
		private final List<String> events = new ArrayList<>();
		private ComputerBlockEntity owner;
		private boolean committedBeforeClear;

		private Probe(boolean box, boolean captured,
			ComputerBlockEntity.InstallInspection inspection) {
			this.box = box;
			this.captured = captured;
			this.inspection = inspection;
		}

		static Probe success() {
			return new Probe(true, true, ComputerBlockEntity.InstallInspection.success(
				new ComputerBlockEntity.InstallCandidate(LOCAL,
					ResourceLocation.withDefaultNamespace("villager"), PROFILE)));
		}

		@Override public boolean isBox(ItemStack stack) { return box; }
		@Override public boolean hasCapturedEntity(ItemStack stack) { return captured; }

		@Override
		public ComputerBlockEntity.InstallInspection inspect(ItemStack copiedStack) {
			events.add("inspect");
			return inspection;
		}

		@Override
		public void clearCapturedEntity(ItemStack heldStack, ComputerBlockEntity computer) {
			owner = computer;
			committedBeforeClear = !computer.residentSnapshot().isEmpty()
				&& computer.installedProfile().equals(java.util.Optional.of(PROFILE));
			events.add("clear");
			com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper
				.clearCapturedEntity(heldStack);
		}
	}
}
