package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import static org.junit.jupiter.api.Assertions.assertSame;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputerTestAccess.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
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
	private static final ProductionResourceProfile PROFILE = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 3, 0)
		.orElseThrow();

	@Test
	void wanderingTraderListenerConfigurationPersistsAndOrdinaryComputerCannotKeepIt() {
		TestComputer source = computer();
		ProductionResourceProfile trader = ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64)
			.orElseThrow();
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:wandering_trader", "Trader"), trader);
		ExternalListenerConfiguration empty = source.production().externalListenerConfiguration();
		ExternalListenerConfiguration configured = new ExternalListenerConfiguration(1,
			List.of(CommunicationFrequency.of("external")));

		assertFalse(source.production().compareAndSetExternalListenerConfiguration(empty, configured),
			"unformed Traders cannot persist listener configuration through the authoritative CAS");
		Topology formed = topology(source.computerId().orElseThrow(), true);
		applyTopologyState(source.production(), formed.record, formed.binding, true, formed.epoch, Set.of());
		setAvailability(source, ComputerAvailabilityReason.NONE);
		setPersistenceAvailable(source, true);
		assertTrue(source.production().canConfigureExternalListeners());
		assertTrue(source.production().compareAndSetExternalListenerConfiguration(empty, configured));
		TestComputer restored = computer();
		restored.read(save(source, false), REGISTRIES, false);
		assertEquals(configured, restored.production().externalListenerConfiguration());

		TestComputer ordinary = computer();
		ordinary.installResidentSnapshot(capturedBox(OTHER, "minecraft:villager", "Ordinary"), PROFILE);
		assertFalse(ordinary.production().compareAndSetExternalListenerConfiguration(
			ordinary.production().externalListenerConfiguration(), configured));
	}

	@Test
	void currentSchemaPersistsTheExactRadioEndpointGeneration() {
		TestComputer source = computer();
		source.production().ensureRadioEndpointGeneration(37);

		CompoundTag saved = save(source, false);
		assertEquals(37, saved.getCompound("ComputerData").getLong("TopologyVersion"));
		TestComputer restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertEquals(37, restored.production().radioEndpointGeneration(),
			"full block-entity reconstruction must retain the exact durable endpoint fence");
	}

	@Test
	void serverReadRotatesOnlyTheLoadedOwnerAndClientReadCannotChangeTheDurableFence() {
		TestComputer source = computer();
		source.production().ensureRadioEndpointGeneration(37);
		UUID firstOwner = source.production().listenerOwnerToken();
		var firstRegistration = source.production().radioTopologyRegistration();
		CompoundTag saved = save(source, false);
		source.read(save(source, true), REGISTRIES, true);
		assertEquals(firstOwner, source.production().listenerOwnerToken());
		assertEquals(firstRegistration, source.production().radioTopologyRegistration());
		assertEquals(37, source.production().radioEndpointGeneration());

		source.read(saved, REGISTRIES, false);
		UUID secondOwner = source.production().listenerOwnerToken();
		assertNotEquals(firstOwner, secondOwner);
		assertNotEquals(firstRegistration, source.production().radioTopologyRegistration());
		assertEquals(37, source.production().radioEndpointGeneration());
		assertEquals(saved.get("ComputerData"), save(source, false).get("ComputerData"));
		source.read(saved, REGISTRIES, false);
		assertNotEquals(secondOwner, source.production().listenerOwnerToken());
		assertEquals(37, source.production().radioEndpointGeneration());
		assertEquals(saved.get("ComputerData"), save(source, false).get("ComputerData"));
	}

	@Test
	void maxTopologyVersionNeverWrapsAndSuppressesRadioPublication() {
		TestComputer source = computer();
		source.production().ensureRadioEndpointGeneration(Long.MAX_VALUE);
		TestComputer restored = computer();
		restored.read(save(source, false), REGISTRIES, false);

		assertFalse(restored.production().topologyVersionAvailable());
		assertFalse(restored.production().radioTopologyEligible(),
			"a persisted exhausted topology fence must never remain radio-publishable");
		Topology topology = topology(restored.computerId().orElseThrow(), true);
		applyTopologyState(restored.production(), topology.record, topology.binding, true, topology.epoch, Set.of());
		restored.production().ensureRadioEndpointGeneration(Long.MAX_VALUE - 1);
		restored.clearResidentSource();
		assertFalse(restored.production().radioTopologyEligible(), "accepted structure or resident changes cannot reopen exhaustion");
		topologyDependencyChanged(restored.production());
		assertEquals(Long.MAX_VALUE, save(restored, false).getCompound("ComputerData")
			.getLong("TopologyVersion"));
	}

	@Test
	void legacyRadioFrequencyFieldFencesTheEntireCurrentComputerSchema() {
		TestComputer source = computer();
		CompoundTag malformed = save(source, false);
		malformed.getCompound("ComputerData").putInt("RadioFrequency", 7);

		TestComputer restored = computer();
		restored.read(malformed, REGISTRIES, false);

		assertFalse(persistenceAvailable(restored.production()));
		assertEquals(malformed.get("ComputerData"), save(restored, false).get("ComputerData"));
	}

	@Test
	void identicalTopologyRefreshDoesNotAdvanceTheWorldIntentFence() {
		TestComputer computer = computer();
		Topology topology = topology(computer.computerId().orElseThrow(), true);
		ProductionStructure.Revision decodedRecord = ProductionStructureCodec.loadRevision(
			ProductionStructureCodec.saveRevision(topology.record)).orElseThrow();
		ProductionEpoch decodedEpoch = ProductionEpochCodec.load(ProductionEpochCodec.save(topology.epoch)).orElseThrow();
		applyTopologyState(computer.production(), decodedRecord, topology.binding, true, decodedEpoch,
			Set.of());
		assertSame(decodedRecord, computer.production().archiveValues().structure());
		assertSame(decodedRecord.structure(), computer.production().currentStructureSnapshot().orElseThrow());
		assertSame(decodedEpoch, computer.production().epoch().orElseThrow());
		assertSame(decodedEpoch, computer.production().archiveValues().epoch());
		long stableFence = topologyState(computer.production()).version();

		applyTopologyState(computer.production(), topology.record, topology.binding, true, topology.epoch,
			Set.of());
		assertEquals(stableFence, topologyState(computer.production()).version(),
			"an unchanged scheduled scan must not stale every queued world intent");

		applyTopologyState(computer.production(), topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH));
		assertTrue(topologyState(computer.production()).version() > stableFence,
			"a real topology/fault transition must still advance the validation fence");
	}

	@Test
	void preparedBindingKeepsCapturedTopologyAndInvalidStagingCannotPartiallyReplaceIt() {
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "resident"), PROFILE);
		Topology formed = topology(computer.computerId().orElseThrow(), true);
		Set<EpochFault> input = new HashSet<>(Set.of(EpochFault.WIDTH));
		applyTopologyState(computer.production(), formed.record, formed.binding, true, formed.epoch, input);
		var before = topologyState(computer.production());
		input.clear();
		assertEquals(Set.of(EpochFault.WIDTH), computer.production().latchedEpochFaults());
		var replacement = new ClusterBinding(formed.binding.clusterId(), formed.binding.revision()+1,
			formed.binding.authority(), List.of(new LogisticsBinding(UUID.randomUUID())));
		stagePreparedBinding(computer.production(), replacement);
		assertEquals(formed.binding, before.binding());
		assertEquals(replacement, bindingState(computer.production()));
		assertEquals(before.record(), currentStructureRecord(computer.production()).orElseThrow());
		assertEquals(before.epoch(), computer.production().epoch().orElseThrow());
		assertEquals(before.faults(), computer.production().latchedEpochFaults());
		assertEquals(before.version()+1, topologyState(computer.production()).version());
		var staged = topologyState(computer.production());
		Set<EpochFault> invalid = new HashSet<>(); invalid.add(null);
		assertThrows(NullPointerException.class, () -> stageTopologyState(computer.production(), null,null,false,null,invalid));
		assertEquals(staged, topologyState(computer.production()));
		var saved = save(computer,false); var restored = computer(); restored.read(saved,REGISTRIES,false);
		assertEquals(staged, topologyState(restored.production()));
		assertEquals(saved.get("ComputerData"),save(restored,false).get("ComputerData"));
	}

	@Test
	void occupiedActiveAndUncertainTopologyRejectionsRemainByteIdentical() {
		TestComputer occupied = computer();
		occupied.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "resident"), PROFILE);
		assertRejectedUnchanged(occupied, Probe.success());

		TestComputer frozen = computer();
		Topology topology = frozenTopology(frozen.computerId().orElseThrow());
		applyTopologyState(frozen.production(), topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH));
		assertRejectedUnchanged(frozen, Probe.success());

		CompoundTag malformed = save(computer(), false);
		malformed.getCompound("ComputerData").putString("ComputerId", "uncertain");
		TestComputer uncertain = computer();
		uncertain.read(malformed, REGISTRIES, false);
		assertRejectedUnchanged(uncertain, Probe.success());
	}

	@Test
	void pendingHotAddAcceptsOnceWithoutChangingEpochMembershipOrderOrLatches() {
		TestComputer computer = computer();
		Topology topology = topology(computer.computerId().orElseThrow(), true);
		applyTopologyState(computer.production(), topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH, EpochFault.DEPTH));
		CompoundTag epochBefore = ProductionEpochCodec.save(topology.epoch);
		Set<EpochFault> faultsBefore = computer.production().latchedEpochFaults();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "pending");

		assertEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, Probe.success()));
		assertTrue(topologyDirty(computer.production()));
		assertEquals(epochBefore, ProductionEpochCodec.save(computer.production().epoch().orElseThrow()));
		assertEquals(faultsBefore, computer.production().latchedEpochFaults());
		ItemStack second = capturedBox(LOCAL, "minecraft:villager", "second");
		Tag heldBefore = second.save(REGISTRIES);
		CompoundTag serverBefore = save(computer, false);
		assertEquals(ComputerInstallResult.OCCUPIED,
			computer.installResident(second, Probe.success()));
		assertEquals(heldBefore, second.save(REGISTRIES));
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void physicalInstallationWithoutProductionMappingIsNotCorruptAfterReload() {
		TestComputer computer = computer();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "neutral");
		var profession = ResourceLocation.fromNamespaceAndPath("create_biotech", "unmapped_test_profession");
		var root = CBItemData.getOrEmpty(held);
		var villager = new CompoundTag(); villager.putString("profession", profession.toString()); villager.putInt("level", 2);
		root.getCompound("CapturedEntity").put("VillagerData", villager); CBItemData.set(held, root);
		var probe = new Probe(true, true, ComputerResidentInstallation.Inspection.success(
			new ComputerResidentInstallation.Candidate(LOCAL, new ResidentHardwareIdentity(
				ResourceLocation.withDefaultNamespace("villager"), profession, 2))));
		assertEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, probe));
		assertTrue(computer.production().installedProfile().isEmpty(), "No production authority is invented");
		assertFalse(computer.production().runtimePersistenceInvalid(), "A missing optional production application is not corrupt storage");
		var saved = save(computer, false);
		TestComputer restored = computer(); restored.read(saved, REGISTRIES, false);
		assertFalse(restored.production().runtimePersistenceInvalid());
		assertTrue(restored.production().installedProfile().isEmpty());
		assertEquals(saved, save(restored, false));
	}

	@Test
	void failedHandClearRestoresResidentAndHeldBox() {
		TestComputer computer = computer();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "rollback");
		CompoundTag before = save(computer, false);
		Tag handBefore = held.save(REGISTRIES);
		Probe probe = Probe.success();
		probe.owner = computer;
		probe.failClear = true;
		assertThrows(IllegalStateException.class, () -> computer.installResident(held, probe));
		assertEquals(handBefore, held.save(REGISTRIES), "Failed transfer must restore the filled box");
		assertEquals(before, save(computer, false), "Failed transfer must restore the whole host");
	}

	@Test
	void validInstallCommitsSnapshotAndStrictProfileBeforeClearingHeldBoxAndNeverRecomputesProfile() {
		TestComputer computer = computer();
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "Ada");
		Probe probe = Probe.success();
		probe.owner = computer;

		assertEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, probe));
		assertEquals(List.of("inspect", "clear"), probe.events);
		assertTrue(probe.committedBeforeClear);
		assertEquals(PROFILE, computer.production().installedProfile().orElseThrow());
		assertFalse(CBItemData.getOrEmpty(held).contains("CapturedEntity"));

		CompoundTag saved = save(computer, false);
		saved.getCompound("ComputerData").getCompound("Resident")
			.getCompound("components").putString("sensitive", "do-not-recompute");
		TestComputer restored = computer();
		restored.read(saved, REGISTRIES, false);
		assertEquals(PROFILE, restored.production().installedProfile().orElseThrow());
	}

	@Test
	void newIdentityIsStableButMalformedPersistedIdentityRemainsRawAndAbsent() {
		TestComputer computer = computer();
		UUID initial = computer.computerId().orElseThrow();
		assertEquals(initial, computer.computerId().orElseThrow());

		CompoundTag malformed = save(computer, false);
		malformed.getCompound("ComputerData").putString("ComputerId", "not-an-int-array");
		TestComputer restored = computer();
		restored.read(malformed, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertEquals(StringTag.valueOf("not-an-int-array"),
			save(restored, false).getCompound("ComputerData").get("ComputerId"));
	}

	@Test
	void missingRequiredIdentityDoesNotEraseValidTopologyAndRemainsMissingOnResave() {
		TestComputer source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), false);
		applyTopologyState(source.production(), topology.record, topology.binding, true, null, Set.of());
		CompoundTag malformed = save(source, false);
		malformed.getCompound("ComputerData").remove("ComputerId");

		TestComputer restored = computer();
		restored.read(malformed, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertTrue(currentStructureRecord(restored.production()).isPresent());
		assertEquals(topology.binding, bindingState(restored.production()));
		assertFalse(save(restored, false).getCompound("ComputerData").contains("ComputerId"));
	}

	@Test
	void strictServerRoundTripCoversResidentProfileStructureBindingEpochAndFaults() {
		TestComputer source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), true);
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "round-trip"), PROFILE);
		applyTopologyState(source.production(), topology.record, topology.binding, true, topology.epoch,
			Set.of(EpochFault.WIDTH, EpochFault.DEPTH));

		CompoundTag saved = save(source, false);
		TestComputer restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertAll(
			() -> assertEquals(source.computerId(), restored.computerId()),
			() -> assertEquals(source.production().installedProfile(), restored.production().installedProfile()),
			() -> assertEquals(currentStructureRecord(source.production()), currentStructureRecord(restored.production())),
			() -> assertEquals(bindingState(source.production()), bindingState(restored.production())),
			() -> assertEquals(bindingStateValid(source.production()), bindingStateValid(restored.production())),
			() -> assertEquals(source.production().epoch(), restored.production().epoch()),
			() -> assertEquals(source.production().latchedEpochFaults(), restored.production().latchedEpochFaults()),
			() -> assertEquals(saved, save(restored, false)));
	}

	@Test
	void missingExternalListenersRemainAbsentAcrossRepeatedSaveAndLoad() {
		TestComputer source = computer();
		CompoundTag saved = save(source, false);
		saved.getCompound("ComputerData").remove("ExternalListeners");
		for (int reload = 0; reload < 2; reload++) {
			TestComputer restored = computer();
			restored.read(saved, REGISTRIES, false);
			assertFalse(persistenceAvailable(restored.production()), "A missing required child is not a valid empty configuration");
			CompoundTag next = save(restored, false);
			assertFalse(next.getCompound("ComputerData").contains("ExternalListeners"),
				"Saving must not invent a default and silently repair the next load");
			assertEquals(saved, next);
			saved = next;
		}
	}

	@Test
	void impossibleEpochPanelsRemainRecoverableAcrossRepeatedHostLoads() {
		TestComputer source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), true);
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "epoch"), PROFILE);
		applyTopologyState(source.production(), topology.record, topology.binding, true, topology.epoch, Set.of());
		CompoundTag saved = save(source, false);
		CompoundTag panel = new CompoundTag(); panel.putUUID("PanelId", new UUID(1,1));
		panel.put("Address", new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0,1,1)).save());
		panel.putString("Facing", "west"); panel.putString("BaseMaterial", "transparent");
		CompoundTag second = panel.copy(); second.putUUID("PanelId", new UUID(1,2));
		ListTag panels = new ListTag(); panels.add(panel); panels.add(second);
		saved.getCompound("ComputerData").getCompound("Epoch").put("Panels", panels);
		for (int pass = 0; pass < 2; pass++) {
			TestComputer restored = computer(); restored.read(saved, REGISTRIES, false);
			assertTrue(restored.production().epoch().isEmpty(), "Invalid layout is not an accepted frozen epoch");
			assertTrue(restored.production().runtimePersistenceInvalid());
			CompoundTag next = save(restored, false);
			assertEquals(saved, next, "Keep the original invalid child and all unrelated data");
			saved = next;
		}
	}

	@Test
	void serverWriteReplacesTheWholeEnvelopeWithoutTouchingOtherBehaviourRoots() {
		TestComputer computer = computer();
		CompoundTag reused = save(computer, false);
		reused.getCompound("ComputerData").putString("RemovedChild", "must disappear");
		reused.getCompound("ComputerData").getCompound("OrchestrationRuntime").putString("RemovedRuntimeChild", "must disappear");
		reused.putString("UnrelatedBehaviour", "keep");
		computer.write(reused, REGISTRIES, false);
		assertEquals(save(computer, false).get("ComputerData"), reused.get("ComputerData"));
		assertEquals("keep", reused.getString("UnrelatedBehaviour"));
	}

	@Test
	void absentCoordinatorRecordRequiresMatchingFrozenEpochWhileChildrenRemainRecoverable() {
		TestComputer source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), true);
		ProductionStructure currentWithoutCoordinator = snapshot(List.of(
			new ProductionLayout.Member(source.computerId().orElseThrow(), address(BlockPos.ZERO), PROFILE)));
		ProductionStructure.Revision missing = new ProductionStructure.Revision(MEMBER, 7,
			topology.epoch.coordinatorId(), currentWithoutCoordinator);
		applyTopologyState(source.production(), missing, topology.binding, true, topology.epoch, Set.of());
		CompoundTag valid = save(source, false);

		TestComputer accepted = computer();
		accepted.read(valid, REGISTRIES, false);
		assertTrue(persistenceAvailable(accepted.production()));

		CompoundTag noEpoch = valid.copy();
		noEpoch.getCompound("ComputerData").remove("Epoch");
		TestComputer rejected = computer();
		rejected.read(noEpoch, REGISTRIES, false);
		assertFalse(persistenceAvailable(rejected.production()));
		assertEquals(missing, currentStructureRecord(rejected.production()).orElseThrow());
		assertEquals(valid.getCompound("ComputerData").get("Structure"),
			save(rejected, false).getCompound("ComputerData").get("Structure"));
	}

	@Test
	void invalidCurrentProfileDoesNotEraseValidSiblingsAndIsDiscarded() {
		TestComputer source = computer();
		Topology topology = topology(source.computerId().orElseThrow(), false);
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "raw"), PROFILE);
		applyTopologyState(source.production(), topology.record, topology.binding, true, null, Set.of());
		CompoundTag corrupt = save(source, false);
		CompoundTag rawProfile = new CompoundTag();
		rawProfile.putString("Profession", "broken-but-recoverable");
		corrupt.getCompound("ComputerData").put("Profile", rawProfile);

		TestComputer restored = computer();
		restored.read(corrupt, REGISTRIES, false);

		assertFalse(persistenceAvailable(restored.production()));
		assertTrue(restored.computerId().isPresent());
		assertTrue(currentStructureRecord(restored.production()).isPresent());
		assertTrue(restored.production().installedProfile().isEmpty());
		assertFalse(restored.residentSnapshot().isEmpty());
		assertFalse(save(restored, false).getCompound("ComputerData").contains("Profile"));
	}

	@Test
	void orchestrationCaptureCursorResetsWhenEpochMembershipShrinks() throws Exception {
		TestComputer computer = computer();
		Field owner = ProductionComputer.class.getDeclaredField("productionTick");
		owner.setAccessible(true);
		Object tick = owner.get(computer.production());
		Field cursor = tick.getClass().getDeclaredField("captureCursor");
		cursor.setAccessible(true); cursor.setInt(tick, 5);
		UUID epochId = UUID.randomUUID();
		assertEquals(0, prepareOrchestrationCapture(computer.production(), epochId, 6));
		cursor.setInt(tick, 5);
		assertEquals(0, prepareOrchestrationCapture(computer.production(), epochId, 2));
		assertEquals(0, cursor.getInt(tick));
	}

	@Test
	void unknownComputerRootIsOpaqueAndItsResidentCanOnlyTriggerLosslessRetention() {
		TestComputer source = computer();
		source.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "opaque"), PROFILE);
		CompoundTag saved = save(source, false);
		CompoundTag original = saved.getCompound("ComputerData").copy();
		original.putString("FutureUnknownRootKey", "preserve-exactly");
		saved.put("ComputerData", original.copy());

		TestComputer restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertTrue(restored.computerId().isEmpty());
		assertFalse(persistenceAvailable(restored.production()));
		assertTrue(restored.hasResidentSource());
		assertEquals(original.get("Resident"), restored.rawResidentTag());
		assertEquals(original, save(restored, false).get("ComputerData"));
	}

	@Test
	void persistedPassengerResidentAndInvalidBindingFlagRemainRawAndUnavailable() {
		TestComputer source = computer();
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

		TestComputer restored = computer();
		restored.read(saved, REGISTRIES, false);

		assertFalse(persistenceAvailable(restored.production()));
		assertTrue(restored.residentSnapshot().isEmpty());
		assertEquals(saved.getCompound("ComputerData").get("Resident"), restored.rawResidentTag());
		assertFalse(bindingStateValid(restored.production()));
	}

	@Test
	void serverAndClientWritesAreStrictlyDisjoint() {
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "projection"), PROFILE);
		computer.setDisplayState(ComputerDisplayState.RUN);

		CompoundTag server = save(computer, false);
		CompoundTag client = save(computer, true);

		assertEquals(Set.of("ComputerData"), server.getAllKeys());
		assertFalse(server.contains("ComputerClientState"));
		assertEquals(Set.of("ComputerClientState", "NormalGoggles"), client.getAllKeys());
		assertFalse(client.contains("ComputerData"));
		assertTrue(ComputerResidentView.load(client.getCompound("ComputerClientState")).isPresent());
		assertFalse(client.getCompound("ComputerClientState").contains("DisplayState"));
		assertFalse(client.getCompound("ComputerClientState").contains("ProcessSlots"));
		var diagnostics = com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState
			.load(client.getCompound("NormalGoggles")).orElseThrow();
		assertTrue(diagnostics.rows().stream().anyMatch(row -> row.key().equals("state")));
	}

	@Test
	void malformedClientProjectionIsIgnoredAtomicallyAndCannotAlterServerFields() {
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server"), PROFILE);
		CompoundTag serverBefore = save(computer, false);
		ComputerResidentView clientBefore = computer.residentView();
		CompoundTag hostile = serverBefore.copy();
		hostile.put("ComputerClientState", new CompoundTag());

		computer.read(hostile, REGISTRIES, true);

		assertEquals(clientBefore, computer.residentView());
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void validClientProjectionWithExtraRootKeyIsRejectedAtomically() {
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server-root"), PROFILE);
		CompoundTag serverBefore = save(computer, false);
		ComputerResidentView clientBefore = computer.residentView();
		CompoundTag hostile = new CompoundTag();
		hostile.put("ComputerClientState", new ComputerResidentView(new ResidentProjection(
			EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 3, null)).save());
		hostile.putString("ExtraRootKey", "must-reject-whole-packet");

		computer.read(hostile, REGISTRIES, true);

		assertEquals(clientBefore, computer.residentView());
		assertEquals(serverBefore, save(computer, false));
	}

	@Test
	void clientWriteReplacesPreexistingRootWithExactProjection() {
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "server-only"),
			PROFILE);
		CompoundTag reusedRoot = new CompoundTag();
		reusedRoot.putString("ComputerData", "must-not-survive-client-write");
		reusedRoot.putString("ExtraRootKey", "must-not-survive-client-write");

		computer.write(reusedRoot, REGISTRIES, true);

		assertEquals(Set.of("ComputerClientState", "NormalGoggles"), reusedRoot.getAllKeys());
		assertTrue(ComputerResidentView.load(reusedRoot.getCompound("ComputerClientState")).isPresent());
	}

	@Test
	void rejectedResidentPacketsCannotChangeEitherDiagnosticViewOrServerArchive() {
		var computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", "preserve"), PROFILE);
		var beforeServer = save(computer, false);
		var resident = new ComputerResidentView(new ResidentProjection(EntityType.VILLAGER, VillagerProfession.FLETCHER, 2, null));
		var normal = new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState(
			List.of(new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.Row("state", List.of("READY"))));
		var target = new com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleTarget(
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), LOCAL, 4, OTHER);
		var initial = new CompoundTag();
		initial.put("ComputerClientState", resident.save());
		initial.put("NormalGoggles", normal.save());
		initial.put("AdvancedGoggleTarget", target.save());
		computer.read(initial, REGISTRIES, true);
		assertEquals(resident, computer.residentView());
		assertEquals(normal, computer.normalGoggles());
		assertEquals(target, computer.advancedGoggleMetadata().clientTarget().orElseThrow());
		for (String rejection : List.of("unknown_root", "malformed_resident", "wrong_resident_type")) {
			var hostile = initial.copy();
			hostile.put("NormalGoggles", com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState.EMPTY.save());
			hostile.remove("AdvancedGoggleTarget");
			if (rejection.equals("unknown_root")) hostile.putString("Forbidden", "do not apply anything");
			else if (rejection.equals("malformed_resident")) hostile.put("ComputerClientState", new CompoundTag());
			else hostile.putString("ComputerClientState", "not a compound");
			computer.read(hostile, REGISTRIES, true);
			assertAll(rejection,
				() -> assertEquals(resident, computer.residentView()),
				() -> assertEquals(normal, computer.normalGoggles()),
				() -> assertEquals(java.util.Optional.of(target), computer.advancedGoggleMetadata().clientTarget()),
				() -> assertEquals(beforeServer, save(computer, false)));
		}
	}

	@Test
	void clientProjectionRecursivelyExcludesResidentTopologyAndUniqueSensitiveStrings() {
		String sensitive = "unique-secret-trade-and-name-8021";
		TestComputer computer = computer();
		computer.installResidentSnapshot(capturedBox(LOCAL, "minecraft:villager", sensitive), PROFILE);
		Topology topology = topology(computer.computerId().orElseThrow(), true);
		applyTopologyState(computer.production(), topology.record, topology.binding, true, topology.epoch,
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
		ProductionStructure.Revision record = topology(LOCAL, false).record;
		assertEquals(record, ProductionStructureCodec.loadRevision(ProductionStructureCodec.saveRevision(record)).orElseThrow());
		CompoundTag extra = ProductionStructureCodec.saveRevision(record);
		extra.putInt("Extra", 1);
		assertTrue(ProductionStructureCodec.loadRevision(extra).isEmpty());
		CompoundTag negative = ProductionStructureCodec.saveRevision(record);
		negative.putLong("Revision", -1);
		assertTrue(ProductionStructureCodec.loadRevision(negative).isEmpty());

		ComputerResidentView valid = new ComputerResidentView(new ResidentProjection(
			EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 3, null));
		assertEquals(valid, ComputerResidentView.load(valid.save()).orElseThrow());
		CompoundTag malformed = valid.save();
		malformed.putByte("Version", (byte) 2);
		assertTrue(ComputerResidentView.load(malformed).isEmpty());
		CompoundTag impossible = valid.save();
		impossible.getCompound("Resident").putInt("Level", 6);
		assertTrue(ComputerResidentView.load(impossible).isEmpty());
	}

	@Test
	void clientProjectionRetainsTraderAndZombieVillagerComputerFamilies() {
		bootstrap();
		ComputerResidentView trader = new ComputerResidentView(
			new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.NONE, 1, "Trader"));
		ComputerResidentView zombie = new ComputerResidentView(
			new ResidentProjection(EntityType.ZOMBIE_VILLAGER, VillagerProfession.CLERIC, 3, "Zombie"));

		assertEquals(trader, ComputerResidentView.load(trader.save()).orElseThrow());
		assertEquals(zombie, ComputerResidentView.load(zombie.save()).orElseThrow());
		assertThrows(IllegalArgumentException.class, () -> new ComputerResidentView(
			new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.NONE, 2, null)));
		assertThrows(IllegalArgumentException.class, () -> new ComputerResidentView(
			new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.CLERIC, 1, null)));
	}

	private static void assertRejectedUnchanged(TestComputer computer,
		ComputerResidentInstallation.Operations probe) {
		ItemStack held = capturedBox(LOCAL, "minecraft:villager", "unchanged");
		Tag heldBefore = held.save(REGISTRIES);
		CompoundTag serverBefore = save(computer, false);
		assertNotEquals(ComputerInstallResult.SUCCESS, computer.installResident(held, probe));
		assertEquals(heldBefore, held.save(REGISTRIES));
		assertEquals(serverBefore, save(computer, false));
	}

	private static void setAvailability(TestComputer computer,
		ComputerAvailabilityReason availability) {
		try {
			Field field = ProductionComputer.class.getDeclaredField("availabilityReason");
			field.setAccessible(true); field.set(computer.production(), availability);
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}
	private static void setPersistenceAvailable(TestComputer computer, boolean available) {
		try {
			Field field = ProductionComputer.class.getDeclaredField("persistenceAvailable");
			field.setAccessible(true); field.setBoolean(computer.production(), available);
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}
	private static CompoundTag save(TestComputer computer, boolean client) {
		CompoundTag tag = new CompoundTag();
		computer.write(tag, REGISTRIES, client);
		return tag;
	}

	private static TestComputer computer() {
		bootstrap();
		return new TestComputer(BlockEntityType.FURNACE, BlockPos.ZERO,
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
		ProductionStructure current = snapshot(List.of(
			new ProductionLayout.Member(OTHER, address(new BlockPos(1, 1, 1)), PROFILE),
			new ProductionLayout.Member(localId, address(BlockPos.ZERO), epoch ? null : PROFILE)));
		ProductionStructure frozen = snapshot(List.of(
			new ProductionLayout.Member(OTHER, address(new BlockPos(1, 1, 1)), PROFILE)));
		ProductionEpoch active = epoch ? ProductionEpoch.freeze(UUID.randomUUID(), MEMBER, frozen.layout()) : null;
		UUID coordinator = active == null ? OTHER : active.coordinatorId();
		ProductionStructure.Revision record = new ProductionStructure.Revision(MEMBER, 7, coordinator, current);
		ClusterBinding binding = new ClusterBinding(active == null ? UUID.randomUUID() : active.groupId(), 4,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, MEMBER),
			List.of(new LogisticsBinding(UUID.randomUUID())));
		return new Topology(record, binding, active);
	}

	private static Topology frozenTopology(UUID localId) {
		ProductionStructure current = snapshot(List.of(
			new ProductionLayout.Member(OTHER, address(new BlockPos(1, 1, 1)), PROFILE),
			new ProductionLayout.Member(localId, address(BlockPos.ZERO), PROFILE)));
		ProductionEpoch active = ProductionEpoch.freeze(UUID.randomUUID(), MEMBER, current.layout());
		ProductionStructure.Revision record = new ProductionStructure.Revision(MEMBER, 7,
			active.coordinatorId(), current);
		ClusterBinding binding = new ClusterBinding(active.groupId(), 4,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, MEMBER), List.of());
		return new Topology(record, binding, active);
	}

	private static ProductionStructure snapshot(List<ProductionLayout.Member> nodes) {
		List<ProductionLayout.Member> sorted = nodes.stream()
			.sorted(java.util.Comparator.comparing(ProductionLayout.Member::computerId)).toList();
		Set<BlockPos> casing = new HashSet<>();
		for (int x = 0; x <= 2; x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2)
						casing.add(new BlockPos(x, y, z));
		return new ProductionStructure(new ProductionLayout(productionBounds(new BoundingBox(0, 0, 0, 2, 2, 2)), sorted, List.of()), casing, Set.of(new ChunkPos(0, 0)));
	}

	private static ProductionLayout.Address address(BlockPos pos) {
		return new ProductionLayout.Address(Level.OVERWORLD, null, pos);
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

	private record Topology(ProductionStructure.Revision record, ClusterBinding binding,
		ProductionEpoch epoch) {}

	private static final class Probe implements ComputerResidentInstallation.Operations {
		private final boolean box;
		private final boolean captured;
		private final ComputerResidentInstallation.Inspection inspection;
		private final List<String> events = new ArrayList<>();
		private TestComputer owner;
		private boolean committedBeforeClear;
		private boolean failClear;

		private Probe(boolean box, boolean captured,
			ComputerResidentInstallation.Inspection inspection) {
			this.box = box;
			this.captured = captured;
			this.inspection = inspection;
		}

		static Probe success() {
			return new Probe(true, true, ComputerResidentInstallation.Inspection.success(
				new ComputerResidentInstallation.Candidate(LOCAL,
					new ResidentHardwareIdentity(ResourceLocation.withDefaultNamespace("villager"),
						ResourceLocation.withDefaultNamespace("librarian"), 3))));
		}

		@Override public boolean isBox(ItemStack stack) { return box; }
		@Override public boolean hasCapturedEntity(ItemStack stack) {
			return captured && com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper.hasCapturedEntity(stack);
		}

		@Override
		public ComputerResidentInstallation.Inspection inspect(ItemStack copiedStack) {
			events.add("inspect");
			return inspection;
		}

		@Override
		public void clearCapturedEntity(ItemStack heldStack) {
			committedBeforeClear = owner != null && !owner.residentSnapshot().isEmpty()
				&& owner.production().installedProfile().equals(java.util.Optional.of(PROFILE));
			events.add("clear");
			com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper
				.clearCapturedEntity(heldStack);
			if (failClear) throw new IllegalStateException("injected hand clear failure");
		}
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
