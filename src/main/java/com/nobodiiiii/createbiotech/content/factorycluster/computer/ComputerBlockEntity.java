package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class ComputerBlockEntity extends SmartBlockEntity {
	private static final int VERSION = 1;
	private static final String ROOT = "ComputerData";
	private static final String CLIENT_ROOT = "ComputerClientState";
	private static final Set<String> ALLOWED = Set.of("Version", "ComputerId", "Resident", "Profile",
		"Structure", "Binding", "BindingInvalid", "Epoch", "Faults");
	private static final Set<String> BINDING_REQUIRED = Set.of("Version", "ClusterId", "Revision",
		"LogisticsBindings");
	private static final Set<String> AUTHORITY_KEYS = Set.of("Type", "MemberId");
	private static final Set<String> LOGISTICS_KEYS = Set.of("Id", "Alias");
	public final ComputerCoordinatorMember coordinatorMember;

	@Nullable private UUID computerId = UUID.randomUUID();
	private ItemStack residentSnapshot = ItemStack.EMPTY;
	@Nullable private ComputerProfile installedProfile;
	@Nullable private ComputerStructureRecord structureRecord;
	@Nullable private ClusterBinding bindingState;
	private boolean bindingStateValid = true;
	@Nullable private ClusterEpoch epoch;
	private Set<EpochFault> faults = Set.of();
	private boolean persistenceAvailable = true;
	private boolean topologyDirty;
	private long topologyVersion;
	@Nullable private ComputerScanLease scanLease;
	private ComputerAvailabilityReason availabilityReason = ComputerAvailabilityReason.NOT_READY;
	private ComputerDisplayState displayState = ComputerDisplayState.IDLE;
	private ComputerClientState clientState = emptyClientState();
	@Nullable private Tag opaqueComputerData;
	private final Map<String, Tag> rawChildren = new LinkedHashMap<>();
	private final Set<String> missingChildren = new HashSet<>();
	private boolean coordinatorMemberPublished;
	@Nullable private ComputerCoordinatorMember coordinatorPublication;

	public ComputerBlockEntity(BlockPos pos, BlockState state) {
		this(CBBlockEntityTypes.COMPUTER.get(), pos, state);
	}

	ComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		coordinatorMember = new ComputerCoordinatorMember(this);
	}

	@Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
	@Override
	public void invalidate() {
		detachScanLease();
		invalidateCoordinatorPublication();
		super.invalidate();
	}
	@Override
	public void tick() {
		super.tick();
		if (!(level instanceof ServerLevel serverLevel) || level.isClientSide) return;
		scheduledTopologyTick(level.getGameTime(),
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}

	void scheduledTopologyTick(long now, ComputerTopologyController.WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		ComputerScanLease.tick(this, now, world, limits);
	}

	void markTopologyDirty() {
		topologyDirty = true;
		if (scanLease != null) scanLease.markDirty();
	}

	@Nullable ComputerScanLease scanLease() { return scanLease; }
	void attachScanLease(ComputerScanLease lease) { scanLease = lease; }
	void clearScanLease(ComputerScanLease lease) {
		if (scanLease == lease) scanLease = null;
	}
	void consumeTopologyDirty() { topologyDirty = false; }
	private void detachScanLease() {
		ComputerScanLease lease = scanLease;
		if (lease != null) lease.detach(this);
		scanLease = null;
	}

	public Optional<UUID> computerId() { return Optional.ofNullable(computerId); }
	public Optional<ComputerCoordinatorMember> publishedCoordinatorMember() {
		return coordinatorMemberPublished ? Optional.of(coordinatorMember) : Optional.empty();
	}
	public Optional<UUID> computerStructureMemberId() {
		return structureRecord == null ? Optional.empty()
			: Optional.of(structureRecord.computerStructureMemberId());
	}
	public Optional<ComputerProfile> installedProfile() { return Optional.ofNullable(installedProfile); }
	public Optional<ComputerStructureSnapshot> currentStructureSnapshot() {
		return structureRecord == null ? Optional.empty() : Optional.of(structureRecord.snapshot());
	}
	Optional<ComputerStructureRecord> currentStructureRecord() { return Optional.ofNullable(structureRecord); }
	public Optional<ClusterEpoch> epoch() { return Optional.ofNullable(epoch); }
	public Set<EpochFault> latchedEpochFaults() { return Set.copyOf(faults); }
	public ComputerAvailabilityReason availabilityReason() { return availabilityReason; }
	public boolean structureOnline() {
		return availabilityReason == ComputerAvailabilityReason.NONE
			|| availabilityReason == ComputerAvailabilityReason.FROZEN_MEMBER_MISSING
			|| availabilityReason == ComputerAvailabilityReason.AUTHORITY_OFFLINE;
	}
	public boolean coordinatorOnline() {
		return availabilityReason == ComputerAvailabilityReason.NONE
			|| availabilityReason == ComputerAvailabilityReason.FROZEN_MEMBER_MISSING;
	}
	public List<ComputerNodeView> currentNodes() {
		return ComputerTopologyController.currentNodes(this);
	}
	public List<ComputerNodeView> frozenNodes() {
		return ComputerTopologyController.frozenNodes(this);
	}
	public List<ComputerNodeView> pendingNodes() {
		return ComputerTopologyController.pendingNodes(this);
	}
	public boolean requiresReform() {
		return faults.contains(EpochFault.WIDTH) || faults.contains(EpochFault.DEPTH);
	}
	public ComputerClusterView clusterView() {
		return new ComputerClusterView(availabilityReason, structureOnline(), coordinatorOnline(),
			epoch != null, requiresReform(), currentNodes(), frozenNodes(), pendingNodes(), faults);
	}

	public EpochStartResult startEpoch(UUID clusterId) {
		Objects.requireNonNull(clusterId, "clusterId");
		if (!(level instanceof ServerLevel serverLevel)) return EpochStartResult.NOT_READY;
		return ComputerTopologyController.startEpoch(this, clusterId,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}

	public FaultLatchResult latchEpochFault(UUID expectedEpochId, EpochFault fault) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(fault, "fault");
		if (!(level instanceof ServerLevel serverLevel)) return FaultLatchResult.IDENTITY_INVALID;
		return ComputerTopologyController.latchEpochFault(this, expectedEpochId, fault,
			ComputerTopologyController.realWorld(serverLevel));
	}

	public EpochCloseResult closeIdleEpoch(UUID expectedEpochId, EpochQuiescence quiescence) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(quiescence, "quiescence");
		if (epoch == null) return EpochCloseResult.NO_EPOCH;
		if (!epoch.epochId().equals(expectedEpochId)) return EpochCloseResult.EPOCH_MISMATCH;
		if (!(level instanceof ServerLevel serverLevel)) return EpochCloseResult.PARTIAL_UNLOADED;
		return ComputerTopologyController.closeIdleEpoch(this, expectedEpochId, quiescence,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}

	public ReformResult stopAllAndReform(UUID expectedEpochId, EpochReformControl control) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(control, "control");
		if (epoch == null) return ReformResult.NO_EPOCH;
		if (!epoch.epochId().equals(expectedEpochId)) return ReformResult.EPOCH_MISMATCH;
		if (!(level instanceof ServerLevel serverLevel)) return ReformResult.PARTIAL_UNLOADED;
		return ComputerTopologyController.stopAllAndReform(this, expectedEpochId, control,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}
	ClusterBinding bindingState() { return bindingState; }
	boolean bindingStateValid() { return bindingStateValid; }
	boolean persistenceAvailable() { return persistenceAvailable; }
	boolean topologyDirty() { return topologyDirty; }
	ComputerClientState clientState() { return clientState; }

	ItemStack residentSnapshot() { return residentSnapshot.copy(); }
	@Nullable Tag rawResidentTag() {
		Tag raw = rawChildren.get("Resident");
		if (raw == null && opaqueComputerData instanceof CompoundTag opaque)
			raw = opaque.get("Resident");
		return raw == null ? null : raw.copy();
	}
	boolean hasResidentSource() {
		return !residentSnapshot.isEmpty() || rawChildren.containsKey("Resident")
			|| opaqueComputerData instanceof CompoundTag opaque && opaque.contains("Resident");
	}

	void installResidentSnapshot(ItemStack snapshot, ComputerProfile profile) {
		invalidateCoordinatorPublication();
		residentSnapshot = snapshot.copy();
		installedProfile = profile;
		rawChildren.remove("Resident");
		rawChildren.remove("Profile");
		persistenceAvailable = validateCrossChildren();
		topologyVersion++;
		setChanged();
	}

	void installRawResident(Tag rawResident) {
		invalidateCoordinatorPublication();
		residentSnapshot = ItemStack.EMPTY;
		installedProfile = null;
		rawChildren.put("Resident", rawResident.copy());
		persistenceAvailable = false;
		topologyVersion++;
		setChanged();
	}

	void clearResidentSource() {
		invalidateCoordinatorPublication();
		residentSnapshot = ItemStack.EMPTY;
		installedProfile = null;
		rawChildren.remove("Resident");
		rawChildren.remove("Profile");
		persistenceAvailable = validateCrossChildren();
		topologyVersion++;
		setChanged();
		sendData();
	}

	void applyTopologyState(@Nullable ComputerStructureRecord record,
		@Nullable ClusterBinding binding, boolean bindingValid, @Nullable ClusterEpoch epoch,
		Set<EpochFault> faults) {
		stageTopologyState(record, binding, bindingValid, epoch, faults);
		setChanged();
	}

	void stageTopologyState(@Nullable ComputerStructureRecord record,
		@Nullable ClusterBinding binding, boolean bindingValid, @Nullable ClusterEpoch epoch,
		Set<EpochFault> faults) {
		if (coordinatorMemberPublished && (record == null
			|| structureRecord == null
			|| !record.computerStructureMemberId()
				.equals(structureRecord.computerStructureMemberId())
			|| !record.coordinatorId().equals(computerId)
			|| !Objects.equals(bindingState, binding)))
			withdrawCoordinatorMember();
		this.structureRecord = record;
		this.bindingState = binding;
		this.bindingStateValid = bindingValid;
		this.epoch = epoch;
		this.faults = Set.copyOf(faults);
		rawChildren.keySet().removeAll(Set.of("Structure", "Binding", "Epoch", "Faults",
			"BindingInvalid"));
		missingChildren.removeAll(Set.of("Structure", "Binding", "Epoch", "Faults",
			"BindingInvalid"));
		persistenceAvailable = validateCrossChildren();
		topologyVersion++;
	}

	void publishTopologyChange() {
		setChanged();
		sendData();
	}

	void stagePreparedBinding(ClusterBinding prepared) {
		bindingState = Objects.requireNonNull(prepared, "prepared");
		bindingStateValid = true;
		rawChildren.remove("Binding");
		rawChildren.remove("BindingInvalid");
		missingChildren.remove("Binding");
		missingChildren.remove("BindingInvalid");
		persistenceAvailable = validateCrossChildren();
		topologyVersion++;
	}

	void publishPreparedBinding() {
		setChanged();
		sendData();
	}

	void publishCoordinatorMember(List<ComputerBlockEntity> replicas, Set<UUID> requiredIds) {
		if (computerId == null || structureRecord == null
			|| !structureRecord.coordinatorId().equals(computerId)) return;
		coordinatorMember.acceptReplicaSet(replicas, requiredIds);
		if (coordinatorMemberPublished) return;
		coordinatorMemberPublished = true;
		MinecraftServer server = coordinatorServer();
		if (server != null) ClusterMemberIndex.register(server, coordinatorMember);
	}

	void withdrawCoordinatorMember() {
		if (!coordinatorMemberPublished) return;
		MinecraftServer server = coordinatorServer();
		if (server != null) ClusterMemberIndex.unregister(server, coordinatorMember);
		coordinatorMemberPublished = false;
		coordinatorMember.clearReplicaSet();
	}

	private void invalidateCoordinatorPublication() {
		ComputerCoordinatorMember publication = coordinatorPublication;
		if (publication != null) publication.replicaInvalidated(this);
		withdrawCoordinatorMember();
	}

	boolean isCoordinatorMemberPublished() { return coordinatorMemberPublished; }
	void attachCoordinatorPublication(ComputerCoordinatorMember publication) {
		if (coordinatorPublication != null && coordinatorPublication != publication)
			coordinatorPublication.replicaInvalidated(this);
		coordinatorPublication = publication;
	}
	void detachCoordinatorPublication(ComputerCoordinatorMember publication) {
		if (coordinatorPublication == publication) coordinatorPublication = null;
	}
	boolean isAttachedTo(ComputerCoordinatorMember publication) {
		return coordinatorPublication == publication;
	}

	@Nullable
	MinecraftServer coordinatorServer() {
		return level == null || level.isClientSide ? null : level.getServer();
	}

	SpaceAddress coordinatorMemberAddress() {
		return SpaceAddress.capture(Objects.requireNonNull(level, "Computer has no level"),
			worldPosition);
	}

	TopologyState topologyState() {
		return new TopologyState(computerId, installedProfile, structureRecord, bindingState,
			bindingStateValid, epoch, faults, persistenceAvailable, topologyVersion);
	}

	boolean topologyMatches(TopologyState expected) {
		return Objects.equals(computerId, expected.computerId())
			&& Objects.equals(installedProfile, expected.profile())
			&& Objects.equals(structureRecord, expected.record())
			&& Objects.equals(bindingState, expected.binding())
			&& bindingStateValid == expected.bindingValid()
			&& Objects.equals(epoch, expected.epoch())
			&& faults.equals(expected.faults())
			&& persistenceAvailable == expected.persistenceAvailable()
			&& topologyVersion == expected.version();
	}

	void setAvailabilityReason(ComputerAvailabilityReason reason) {
		availabilityReason = Objects.requireNonNull(reason, "reason");
	}

	record TopologyState(@Nullable UUID computerId, @Nullable ComputerProfile profile,
		@Nullable ComputerStructureRecord record, @Nullable ClusterBinding binding,
		boolean bindingValid, @Nullable ClusterEpoch epoch, Set<EpochFault> faults,
		boolean persistenceAvailable, long version) {
		TopologyState {
			faults = Set.copyOf(faults);
		}
	}

	public enum EpochStartResult {
		STARTED, NOT_COORDINATOR, NOT_READY, BINDING_UNAVAILABLE, ALREADY_ACTIVE,
		REQUIRES_REFORM
	}
	public enum FaultLatchResult {
		LATCHED, ALREADY_LATCHED, NO_EPOCH, EPOCH_MISMATCH, IDENTITY_INVALID
	}
	public enum EpochCloseResult {
		CLOSED, NO_EPOCH, EPOCH_MISMATCH, REQUIRES_REFORM, ROOTS_REMAIN,
		RUNTIME_NOT_QUIESCENT, PARTIAL_UNLOADED, SPACE_UNCERTAIN, STRUCTURE_INVALID,
		FROZEN_MEMBER_MISSING, IDENTITY_CONFLICT, PROFILE_NOT_READY, PERSISTENCE_INVALID
	}
	public enum ReformResult {
		REFORMED, NO_EPOCH, EPOCH_MISMATCH, STOP_FAILED, ROOTS_REMAIN,
		RUNTIME_NOT_QUIESCENT, PARTIAL_UNLOADED, SPACE_UNCERTAIN, STRUCTURE_INVALID,
		IDENTITY_CONFLICT, PROFILE_NOT_READY, PERSISTENCE_INVALID
	}

	void setDisplayState(ComputerDisplayState displayState) {
		this.displayState = java.util.Objects.requireNonNull(displayState, "displayState");
		clientState = currentClientState();
	}

	public ComputerInstallResult installResident(ServerPlayer player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		return installResident(held, new RealInstallOps(player.serverLevel()));
	}

	ComputerInstallResult installResident(ItemStack held, InstallOps operations) {
		if (!operations.isBox(held)) return ComputerInstallResult.NOT_BOX;
		if (!operations.hasCapturedEntity(held)) return ComputerInstallResult.EMPTY_BOX;
		if (hasResidentSource()) return ComputerInstallResult.OCCUPIED;
		if (computerId == null || opaqueComputerData != null
			|| rawChildren.containsKey("Version") || rawChildren.containsKey("ComputerId")
			|| missingChildren.contains("Version") || missingChildren.contains("ComputerId"))
			return ComputerInstallResult.INVALID_IDENTITY;
		if (!persistenceAvailable) return ComputerInstallResult.ACTIVE_TOPOLOGY;

		ItemStack copied = held.copy();
		InstallInspection inspection = operations.inspect(copied);
		if (inspection == null || inspection.result() != ComputerInstallResult.SUCCESS)
			return inspection == null ? ComputerInstallResult.INVALID_IDENTITY : inspection.result();
		if (!installationAllowedByTopology()) return ComputerInstallResult.ACTIVE_TOPOLOGY;

		InstallCandidate candidate = inspection.candidate();
		invalidateCoordinatorPublication();
		residentSnapshot = copied;
		installedProfile = candidate.profile();
		rawChildren.remove("Resident");
		rawChildren.remove("Profile");
		markTopologyDirty();
		persistenceAvailable = validateCrossChildren();
		topologyVersion++;
		setChanged();
		sendData();
		operations.clearCapturedEntity(held, this);
		return ComputerInstallResult.SUCCESS;
	}

	private boolean installationAllowedByTopology() {
		if (epoch == null) return true;
		if (structureRecord == null || computerId == null
			|| !structureRecord.computerStructureMemberId().equals(epoch.computerStructureMemberId())
			|| !structureRecord.coordinatorId().equals(epoch.coordinatorId())
			|| structureRecord.snapshot().node(computerId).isEmpty()) return false;
		return epoch.nodes().stream().noneMatch(node -> node.computerId().equals(computerId));
	}

	interface InstallOps {
		boolean isBox(ItemStack stack);
		boolean hasCapturedEntity(ItemStack stack);
		InstallInspection inspect(ItemStack copiedStack);
		void clearCapturedEntity(ItemStack heldStack, ComputerBlockEntity computer);
	}

	record InstallCandidate(UUID uuid, ResourceLocation type, ComputerProfile profile) {
		InstallCandidate {
			java.util.Objects.requireNonNull(uuid, "uuid");
			java.util.Objects.requireNonNull(type, "type");
			java.util.Objects.requireNonNull(profile, "profile");
		}
	}

	record InstallInspection(ComputerInstallResult result, @Nullable InstallCandidate candidate) {
		InstallInspection {
			if (result == ComputerInstallResult.SUCCESS && candidate == null)
				throw new IllegalArgumentException("success requires candidate");
			if (result != ComputerInstallResult.SUCCESS && candidate != null)
				throw new IllegalArgumentException("failure cannot carry candidate");
		}
		static InstallInspection success(InstallCandidate candidate) {
			return new InstallInspection(ComputerInstallResult.SUCCESS, candidate);
		}
		static InstallInspection failure(ComputerInstallResult result) {
			if (result == ComputerInstallResult.SUCCESS) throw new IllegalArgumentException("success");
			return new InstallInspection(result, null);
		}
	}

	private static final class RealInstallOps implements InstallOps {
		private final ServerLevel level;

		private RealInstallOps(ServerLevel level) { this.level = level; }
		@Override public boolean isBox(ItemStack stack) { return CapturedEntityBoxItem.isBox(stack); }
		@Override public boolean hasCapturedEntity(ItemStack stack) {
			return CapturedEntityBoxHelper.hasCapturedEntity(stack);
		}

		@Override
		public InstallInspection inspect(ItemStack copiedStack) {
			RawIdentity raw = rawIdentity(copiedStack);
			if (raw == null) return InstallInspection.failure(ComputerInstallResult.INVALID_IDENTITY);
			if (raw.passengers())
				return InstallInspection.failure(ComputerInstallResult.PASSENGERS_UNSUPPORTED);
			for (ServerLevel candidate : level.getServer().getAllLevels())
				if (candidate.getEntity(raw.uuid()) != null)
					return InstallInspection.failure(ComputerInstallResult.INVALID_IDENTITY);
			Entity decoded = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(copiedStack, level);
			if (decoded == null || !decoded.getUUID().equals(raw.uuid())
				|| decoded.getType() != raw.type())
				return InstallInspection.failure(ComputerInstallResult.INVALID_IDENTITY);
			Optional<ComputerProfile> profile = ComputerProfile.fromEntity(decoded,
				CBConfigs.SERVER.factoryCluster.wanderingTraderRangeBonus.get());
			if (profile.isEmpty()) return InstallInspection.failure(ComputerInstallResult.UNSUPPORTED_ENTITY);
			return InstallInspection.success(new InstallCandidate(raw.uuid(), raw.id(), profile.orElseThrow()));
		}

		@Override public void clearCapturedEntity(ItemStack heldStack, ComputerBlockEntity computer) {
			CapturedEntityBoxHelper.clearCapturedEntity(heldStack);
		}
	}

	private record RawIdentity(UUID uuid, ResourceLocation id, EntityType<?> type,
		boolean passengers) {}

	private static @Nullable RawIdentity rawIdentity(ItemStack stack) {
		Tag rawCaptured = CBItemData.getOrEmpty(stack).get("CapturedEntity");
		if (!(rawCaptured instanceof CompoundTag captured)
			|| !uuid(captured, "UUID")
			|| !has(captured, "id", Tag.TAG_STRING)) return null;
		ResourceLocation id = ResourceLocation.tryParse(captured.getString("id"));
		if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null;
		Tag passengers = captured.get("Passengers");
		if (passengers != null && !(passengers instanceof ListTag)) return null;
		return new RawIdentity(captured.getUUID("UUID"), id,
			BuiltInRegistries.ENTITY_TYPE.get(id), passengers instanceof ListTag list && !list.isEmpty());
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) {
			clientState = currentClientState();
			for (String key : List.copyOf(tag.getAllKeys())) tag.remove(key);
			tag.put(CLIENT_ROOT, clientState.save());
			return;
		}
		super.write(tag, registries, false);
		if (opaqueComputerData != null) {
			tag.put(ROOT, opaqueComputerData.copy());
			return;
		}
		CompoundTag data = new CompoundTag();
		putOrRaw(data, "Version", net.minecraft.nbt.IntTag.valueOf(VERSION));
		if (rawChildren.containsKey("ComputerId")) data.put("ComputerId", rawChildren.get("ComputerId").copy());
		else if (missingChildren.contains("ComputerId")) { /* preserve required-key absence */ }
		else if (computerId != null) data.putUUID("ComputerId", computerId);
		else data.put("ComputerId", StringTag.valueOf("invalid"));
		if (rawChildren.containsKey("Resident")) data.put("Resident", rawChildren.get("Resident").copy());
		else if (!residentSnapshot.isEmpty()) data.put("Resident", residentSnapshot.save(registries));
		if (rawChildren.containsKey("Profile")) data.put("Profile", rawChildren.get("Profile").copy());
		else if (installedProfile != null) data.put("Profile", installedProfile.save());
		if (rawChildren.containsKey("Structure")) data.put("Structure", rawChildren.get("Structure").copy());
		else if (structureRecord != null) data.put("Structure", structureRecord.save());
		if (rawChildren.containsKey("Binding")) data.put("Binding", rawChildren.get("Binding").copy());
		else if (bindingState != null) data.put("Binding", bindingState.save());
		if (rawChildren.containsKey("BindingInvalid"))
			data.put("BindingInvalid", rawChildren.get("BindingInvalid").copy());
		else if (missingChildren.contains("BindingInvalid")) { /* preserve required-key absence */ }
		else data.putBoolean("BindingInvalid", !bindingStateValid);
		if (rawChildren.containsKey("Epoch")) data.put("Epoch", rawChildren.get("Epoch").copy());
		else if (epoch != null) data.put("Epoch", epoch.save());
		if (rawChildren.containsKey("Faults")) data.put("Faults", rawChildren.get("Faults").copy());
		else if (missingChildren.contains("Faults")) { /* preserve required-key absence */ }
		else data.put("Faults", saveFaults(faults));
		tag.put(ROOT, data);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (clientPacket) {
			if (!tag.getAllKeys().equals(Set.of(CLIENT_ROOT))) return;
			Tag raw = tag.get(CLIENT_ROOT);
			if (!(raw instanceof CompoundTag compound)) return;
			ComputerClientState.load(compound).ifPresent(decoded -> clientState = decoded);
			return;
		}
		detachScanLease();
		invalidateCoordinatorPublication();
		super.read(tag, registries, false);
		resetForServerRead();
		Tag rawRoot = tag.get(ROOT);
		if (!(rawRoot instanceof CompoundTag data)) {
			if (rawRoot != null) opaqueComputerData = rawRoot.copy();
			persistenceAvailable = false;
			return;
		}
		if (!ALLOWED.containsAll(data.getAllKeys())) {
			opaqueComputerData = data.copy();
			persistenceAvailable = false;
			return;
		}

		boolean valid = decodeVersion(data);
		if (uuid(data, "ComputerId")) computerId = data.getUUID("ComputerId");
		else { retain(data, "ComputerId"); valid = false; }

		if (data.contains("Resident")) {
			if (has(data, "Resident", Tag.TAG_COMPOUND)) {
				try {
					ItemStack parsed = ItemStack.parseOptional(registries, data.getCompound("Resident"));
					RawIdentity identity = rawIdentity(parsed);
					if (!parsed.isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(parsed)
						&& identity != null && !identity.passengers()) residentSnapshot = parsed;
					else { retain(data, "Resident"); valid = false; }
				} catch (RuntimeException invalidStack) {
					retain(data, "Resident"); valid = false;
				}
			} else { retain(data, "Resident"); valid = false; }
		}

		if (data.contains("Profile")) {
			if (has(data, "Profile", Tag.TAG_COMPOUND)) {
				Optional<ComputerProfile> parsed = ComputerProfile.load(data.getCompound("Profile"));
				if (parsed.isPresent()) installedProfile = parsed.orElseThrow();
				else { retain(data, "Profile"); valid = false; }
			} else { retain(data, "Profile"); valid = false; }
		}

		if (data.contains("Structure")) {
			if (has(data, "Structure", Tag.TAG_COMPOUND)) {
				Optional<ComputerStructureRecord> parsed = ComputerStructureRecord.load(
					data.getCompound("Structure"));
				if (parsed.isPresent()) structureRecord = parsed.orElseThrow();
				else { retain(data, "Structure"); valid = false; }
			} else { retain(data, "Structure"); valid = false; }
		}

		if (data.contains("Binding")) {
			if (has(data, "Binding", Tag.TAG_COMPOUND) && strictBinding(data.getCompound("Binding"))) {
				Optional<ClusterBinding> parsed = ClusterBinding.tryLoad(data.getCompound("Binding"));
				if (parsed.isPresent()) bindingState = parsed.orElseThrow();
				else { retain(data, "Binding"); valid = false; }
			} else { retain(data, "Binding"); valid = false; }
		}

		if (has(data, "BindingInvalid", Tag.TAG_BYTE)
			&& canonicalBoolean(data.getByte("BindingInvalid"))) {
			bindingStateValid = !data.getBoolean("BindingInvalid");
		} else { retain(data, "BindingInvalid"); bindingStateValid = false; valid = false; }

		if (data.contains("Epoch")) {
			if (has(data, "Epoch", Tag.TAG_COMPOUND)) {
				Optional<ClusterEpoch> parsed = ClusterEpoch.load(data.getCompound("Epoch"));
				if (parsed.isPresent()) epoch = parsed.orElseThrow();
				else { retain(data, "Epoch"); valid = false; }
			} else { retain(data, "Epoch"); valid = false; }
		}

		Optional<Set<EpochFault>> parsedFaults = loadFaults(data.get("Faults"));
		if (parsedFaults.isPresent()) faults = parsedFaults.orElseThrow();
		else { retain(data, "Faults"); valid = false; }

		persistenceAvailable = valid && validateCrossChildren();
	}

	private void resetForServerRead() {
		computerId = null;
		residentSnapshot = ItemStack.EMPTY;
		installedProfile = null;
		structureRecord = null;
		bindingState = null;
		bindingStateValid = false;
		epoch = null;
		faults = Set.of();
		persistenceAvailable = false;
		topologyDirty = false;
		opaqueComputerData = null;
		rawChildren.clear();
		missingChildren.clear();
		topologyVersion++;
	}

	private boolean validateCrossChildren() {
		if (opaqueComputerData != null || computerId == null || !rawChildren.isEmpty()
			|| !missingChildren.isEmpty() || !bindingStateValid) return false;
		if (residentSnapshot.isEmpty() != (installedProfile == null)) return false;
		if (structureRecord != null) {
			if (structureRecord.snapshot().node(computerId).isEmpty()) return false;
			boolean coordinatorPresent = structureRecord.snapshot().node(structureRecord.coordinatorId()).isPresent();
			if (!coordinatorPresent && (epoch == null
				|| !epoch.coordinatorId().equals(structureRecord.coordinatorId()))) return false;
		}
		if (epoch != null) {
			if (structureRecord == null
				|| !epoch.computerStructureMemberId().equals(structureRecord.computerStructureMemberId())
				|| !epoch.coordinatorId().equals(structureRecord.coordinatorId())) return false;
			if (bindingState != null && !epoch.clusterId().equals(bindingState.clusterId())) return false;
		} else if (!faults.isEmpty()) return false;
		if (bindingState != null && bindingState.authority() != null
			&& bindingState.authority().type() == ClusterMemberType.COMPUTER_COORDINATOR
			&& (structureRecord == null || !bindingState.authority().memberId()
				.equals(structureRecord.computerStructureMemberId()))) return false;
		return true;
	}

	private ComputerClientState currentClientState() {
		if (!residentSnapshot.isEmpty() && installedProfile != null
			&& !rawChildren.containsKey("Resident") && !rawChildren.containsKey("Profile"))
			return new ComputerClientState(true, installedProfile.kind(), installedProfile.slots(),
				installedProfile.depth(), displayState);
		return emptyClientState();
	}

	private static ComputerClientState emptyClientState() {
		return new ComputerClientState(false, null, 0, 0, ComputerDisplayState.IDLE);
	}

	private boolean decodeVersion(CompoundTag data) {
		if (has(data, "Version", Tag.TAG_INT) && data.getInt("Version") == VERSION) return true;
		retain(data, "Version");
		return false;
	}

	private void retain(CompoundTag data, String key) {
		Tag value = data.get(key);
		if (value == null) missingChildren.add(key);
		else rawChildren.put(key, value.copy());
	}

	private void putOrRaw(CompoundTag data, String key, Tag normal) {
		if (missingChildren.contains(key)) return;
		Tag raw = rawChildren.get(key);
		data.put(key, raw == null ? normal : raw.copy());
	}

	private static ListTag saveFaults(Set<EpochFault> faults) {
		ListTag result = new ListTag();
		faults.stream().sorted().forEach(fault -> result.add(StringTag.valueOf(fault.name())));
		return result;
	}

	private static Optional<Set<EpochFault>> loadFaults(@Nullable Tag raw) {
		if (!(raw instanceof ListTag list)
			|| (!list.isEmpty() && list.getElementType() != Tag.TAG_STRING)) return Optional.empty();
		EnumSet<EpochFault> result = EnumSet.noneOf(EpochFault.class);
		try {
			for (Tag child : list) {
				if (!(child instanceof StringTag string)) return Optional.empty();
				if (!result.add(EpochFault.valueOf(string.getAsString()))) return Optional.empty();
			}
			return Optional.of(Set.copyOf(result));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	private static boolean strictBinding(CompoundTag tag) {
		Set<String> expected = new HashSet<>(BINDING_REQUIRED);
		if (tag.contains("Authority")) expected.add("Authority");
		if (!tag.getAllKeys().equals(expected) || !has(tag, "Version", Tag.TAG_INT)
			|| !uuid(tag, "ClusterId") || !has(tag, "Revision", Tag.TAG_LONG)
			|| !has(tag, "LogisticsBindings", Tag.TAG_LIST)) return false;
		if (tag.contains("Authority")) {
			if (!has(tag, "Authority", Tag.TAG_COMPOUND)) return false;
			CompoundTag authority = tag.getCompound("Authority");
			if (!authority.getAllKeys().equals(AUTHORITY_KEYS)
				|| !has(authority, "Type", Tag.TAG_STRING) || !uuid(authority, "MemberId")) return false;
		}
		ListTag bindings = (ListTag) tag.get("LogisticsBindings");
		if (bindings == null || (!bindings.isEmpty() && bindings.getElementType() != Tag.TAG_COMPOUND))
			return false;
		for (Tag child : bindings)
			if (!(child instanceof CompoundTag binding) || !binding.getAllKeys().equals(LOGISTICS_KEYS)
				|| !uuid(binding, "Id") || !has(binding, "Alias", Tag.TAG_STRING)) return false;
		Optional<ClusterBinding> decoded = ClusterBinding.tryLoad(tag);
		return decoded.isPresent() && decoded.orElseThrow().save().equals(tag);
	}

	private static boolean canonicalBoolean(byte value) { return value == 0 || value == 1; }
	private static boolean uuid(CompoundTag tag, String key) {
		return tag.get(key) instanceof IntArrayTag values && values.getAsIntArray().length == 4
			&& tag.hasUUID(key);
	}
	private static boolean has(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}

	byte[] serializedResident(HolderLookup.Provider registries) {
		return residentSnapshot.isEmpty() ? new byte[0]
			: residentSnapshot.save(registries).toString().getBytes(StandardCharsets.UTF_8);
	}
}
