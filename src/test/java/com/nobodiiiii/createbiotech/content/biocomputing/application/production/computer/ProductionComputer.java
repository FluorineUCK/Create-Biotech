package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionScanCohort;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionShellMemberships;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionComputerLifecycle;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResidentProfiles;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.*;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerTick;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionEngineSchema;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionQuiescence;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionComputerTopology;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

/** The production application attached to one physical Computer; never a block entity. */
public final class ProductionComputer implements ExternalListenerService.ListenerComputer {
    private final HistoricalProductionHost owner;
    private final java.util.function.BooleanSupplier residentStorageValid;
    public HistoricalProductionHost owner() { return owner; }
	static final long TOPOLOGY_SCAN_INTERVAL_TICKS = 20;
	private static final ProductionShellMemberships<MinecraftServer, ProductionComputer> SHELL_MEMBERSHIPS =
		new ProductionShellMemberships<>();
	public final ComputerCoordinatorMember coordinatorMember;
	private final ProductionComputerRuntime<ClusterEngineState> runtime;

	@Nullable private ProductionResourceProfile installedProfile;
	private ExternalListenerConfiguration externalListenerConfiguration =
		ExternalListenerConfiguration.empty();
	private final ProductionComputerLifecycle productionLifecycle = new ProductionComputerLifecycle();
	private final ProductionComputerTopology<EpochFault> productionTopology = new ProductionComputerTopology<>();
	private boolean persistenceAvailable = true;
	private final ProductionScanCohort.State<HistoricalProductionHost> productionScan = new ProductionScanCohort.State<>();
	private final ProductionShellMemberships<MinecraftServer, ProductionComputer>.Registration shellMembership =
		SHELL_MEMBERSHIPS.registration(this);
	private ComputerAvailabilityReason availabilityReason = ComputerAvailabilityReason.NOT_READY;
	private ComputerDisplayState displayState = ComputerDisplayState.IDLE;
	private final ProductionComputerArchive<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
		ExternalListenerConfiguration, Set<EpochFault>> archive;
	private final ProductionComputerTick<ClusterEngineState, ProductionEffect> productionTick =
		new ProductionComputerTick<>(ClusterEngineState.MAX_PENDING_INTENTS);

    public ProductionComputer(HistoricalProductionHost owner,
        ProductionComputerArchive<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
            ExternalListenerConfiguration, Set<EpochFault>> archive,
        java.util.function.BooleanSupplier residentStorageValid) {
        this.owner = Objects.requireNonNull(owner);
        this.archive = Objects.requireNonNull(archive);
        this.residentStorageValid = Objects.requireNonNull(residentStorageValid);
        coordinatorMember = new ComputerCoordinatorMember(this);
        runtime = new ProductionComputerRuntime<>(ProductionEngineSchema.INSTANCE,
            () -> computerId().orElse(null), owner::setChanged);
    }

	/**
	 * A shell block is removed before its Computer block entities invalidate. Withdraw every frozen
	 * member whose accepted snapshot owns that block, so a physical radio sample cannot
	 * survive one topology scan after dismantling.
	 */
	public static void shellRemovalBegins(ServerLevel level, BlockPos casingPos) {
		Objects.requireNonNull(level, "level");
		SpaceAddress shell = SpaceAddress.capture(level, Objects.requireNonNull(casingPos, "casing pos"));
		for (ProductionComputer computer : SHELL_MEMBERSHIPS.members(level.getServer(), productionAddress(shell)))
			computer.beginShellRemoval(shell);
	}

	private void beginShellRemoval(SpaceAddress shell) {
		if (currentStructureSnapshot().filter(snapshot ->
			snapshot.casingPositions().contains(shell.localPos())).isEmpty()) return;
		productionLifecycle.suppressUntilStructureAccepted();
		FactoryOrchestrationServer.nativeComputerRetired(this);
	}

	/** A shell teardown fence remains closed until a subsequent accepted structure scan forms again. */
	public boolean radioTopologyEligible() {
		return productionLifecycle.eligible();
	}

	/** The durable topology fence is terminal at its representation maximum. */
	public boolean topologyVersionAvailable() { return productionLifecycle.available(); }

	/** Advances the radio authority fence without wrapping its durable representation. */
	private boolean advanceTopologyVersion() {
		return productionLifecycle.advance();
	}

	/** Ensures a direct lifecycle observation (including reload tests) publishes the shell index first. */
	public void refreshShellMembershipForLifecycle() {
		refreshShellMembership();
	}

	/** Read-only lifecycle evidence used by production topology acceptance. */
	public boolean hasIndexedShellMembership() {
		return shellMembership.indexed();
	}

	int prepareOrchestrationCapture(UUID epochId, int nodeCount) {
		return productionTick.prepareCapture(epochId, nodeCount);
	}

	void scheduledTopologyTick(long now, ComputerTopologyController.WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		ProductionScanAccess.tick(this, now, world, limits);
	}

	void markTopologyDirty() {
		productionScan.markDirty();
	}

	ProductionScanCohort.State<HistoricalProductionHost> productionScanState() { return productionScan; }
	private void detachScanLease() {
		productionScan.detach(owner);
	}

	public Optional<UUID> computerId() { return owner.computerId(); }
	public ProductionComputerRuntime<ClusterEngineState> runtime() { return runtime; }
	private @Nullable ProductionComputerRuntime.Scope productionRuntimeScope() {
		var epoch = productionTopology.state().epoch();
		return epoch == null ? null : new ProductionComputerRuntime.Scope(epoch.groupId(), epoch.epochId());
	}
	public boolean runtimePersistenceInvalid() { return !persistenceAvailable || !runtime.snapshot().persistenceValid(); }
	public Optional<ComputerCoordinatorMember> publishedCoordinatorMember() {
		return coordinatorMember.isPublished() ? Optional.of(coordinatorMember) : Optional.empty();
	}
	public Optional<UUID> computerStructureMemberId() {
		return structureRecord() == null ? Optional.empty()
			: Optional.of(structureRecord().structureId());
	}
	public Optional<ProductionResourceProfile> installedProfile() { return Optional.ofNullable(installedProfile); }
	@Override @Nullable public ProductionResourceProfile profile() { return installedProfile; }
	public ExternalListenerConfiguration externalListenerConfiguration() {
		return externalListenerConfiguration;
	}
	@Override public ExternalListenerConfiguration configuration() {
		return externalListenerConfiguration;
	}
	@Override public UUID id() { return computerId().orElseThrow(() ->
		new IllegalStateException("listener Computer has no identity")); }
	@Override public ExternalListenerService.ListenerOwner listenerOwner() {
		ProductionEpoch currentEpoch = epoch().orElseThrow(() ->
			new IllegalStateException("listener Computer is not formed"));
		return new ExternalListenerService.ListenerOwner(id(), currentEpoch.epochId(), productionLifecycle.ownerToken());
	}
	/** Loaded-instance fence retained through unform so stale unload callbacks cannot retire a successor. */
	public UUID listenerOwnerToken() { return productionLifecycle.ownerToken(); }
	/** Exact lifecycle owner for this loaded Computer's primary physical radio endpoint. */
	public RadioTopologyLifecycle.Registration radioTopologyRegistration() {
		return new RadioTopologyLifecycle.Registration(productionLifecycle.ownerToken(), productionLifecycle.ownerToken());
	}
	@Override public long publicationGeneration() { return radioEndpointGeneration(); }
	@Override public boolean listenerPersistenceAuthorized() { return canConfigureExternalListeners(); }
	/** Durable CAS seam used by the listener service; ordinary Computers never retain a list. */
	public boolean compareAndSetExternalListenerConfiguration(ExternalListenerConfiguration expected,
		ExternalListenerConfiguration next) {
		return compareAndSet(expected, next);
	}
	@Override
	public boolean compareAndSet(ExternalListenerConfiguration expected,
		ExternalListenerConfiguration next) {
		expected = Objects.requireNonNull(expected, "expected listener configuration");
		next = Objects.requireNonNull(next, "next listener configuration");
		if (!listenerPersistenceAuthorized() || installedProfile == null
			|| installedProfile.profession() != ProductionProfession.WANDERING_TRADER
			|| !externalListenerConfiguration.equals(expected)
			|| expected.revision() == Long.MAX_VALUE
			|| next.revision() != expected.revision() + 1
			|| !advanceTopologyVersion()) return false;
		externalListenerConfiguration = next;
		owner.setChanged();
		publishRadioLifecycle();
		return true;
	}
	/** Menu-facing CAS mutation; runtime composition owns subsequent active listener reconciliation. */
	public ExternalListenerService.EditResult replaceExternalListeners(long expectedRevision,
		List<CommunicationFrequency> frequencies) {
		return FactoryOrchestrationServer.replaceExternalListeners(this, expectedRevision, frequencies);
	}
	/** Only formed Wandering Trader Computers expose the dedicated listener editor. */
	public boolean canConfigureExternalListeners() {
		return installedProfile != null
			&& installedProfile.profession() == ProductionProfession.WANDERING_TRADER
			&& activeEpoch() != null && structureOnline() && persistenceAvailable;
	}
	public long radioEndpointGeneration() { return productionLifecycle.generation(); }
	/**
	 * Router endpoint generations are monotonic beyond a loaded block entity's persisted topology
	 * revision.  Reload must therefore raise the local fence before publishing, never replay a
	 * generation that the router has already retired.
	 */
	public void ensureRadioEndpointGeneration(long routerGeneration) {
		if (productionLifecycle.ensureGeneration(routerGeneration)) owner.setChanged();
	}
	public Optional<ProductionStructure> currentStructureSnapshot() {
		return structureRecord() == null ? Optional.empty() : Optional.of(structureRecord().structure());
	}
	Optional<ProductionStructure.Revision> currentStructureRecord() { return Optional.ofNullable(structureRecord()); }
	public Optional<ProductionEpoch> epoch() { return Optional.ofNullable(activeEpoch()); }
	/** Server command fences must match the formed Cluster epoch exactly. */
	public boolean hasExactEpoch(UUID clusterId, UUID epochId) {
		return activeEpoch() != null && activeEpoch().groupId().equals(Objects.requireNonNull(clusterId, "clusterId"))
			&& activeEpoch().epochId().equals(Objects.requireNonNull(epochId, "epochId"));
	}
	public Set<EpochFault> latchedEpochFaults() { return productionTopology.state().faults(); }
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
		return latchedEpochFaults().contains(EpochFault.WIDTH) || latchedEpochFaults().contains(EpochFault.DEPTH);
	}
	public ComputerClusterView clusterView() {
		return new ComputerClusterView(availabilityReason, structureOnline(), coordinatorOnline(),
			activeEpoch() != null, requiresReform(), currentNodes(), frozenNodes(), pendingNodes(), latchedEpochFaults());
	}

	public EpochStartResult startEpoch(UUID clusterId) {
		Objects.requireNonNull(clusterId, "clusterId");
		if (!(owner.getLevel() instanceof ServerLevel serverLevel)) return EpochStartResult.NOT_READY;
		return ComputerTopologyController.startEpoch(this, clusterId,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}

	public FaultLatchResult latchEpochFault(UUID expectedEpochId, EpochFault fault) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(fault, "fault");
		if (!(owner.getLevel() instanceof ServerLevel serverLevel)) return FaultLatchResult.IDENTITY_INVALID;
		return ComputerTopologyController.latchEpochFault(this, expectedEpochId, fault,
			ComputerTopologyController.realWorld(serverLevel));
	}

	public EpochCloseResult closeIdleEpoch(UUID expectedEpochId, ProductionQuiescence quiescence) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(quiescence, "quiescence");
		if (activeEpoch() == null) return EpochCloseResult.NO_EPOCH;
		if (!activeEpoch().epochId().equals(expectedEpochId)) return EpochCloseResult.EPOCH_MISMATCH;
		if (!(owner.getLevel() instanceof ServerLevel serverLevel)) return EpochCloseResult.PARTIAL_UNLOADED;
		return ComputerTopologyController.closeIdleEpoch(this, expectedEpochId, quiescence,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}

	public ReformResult stopAllAndReform(UUID expectedEpochId, EpochReformControl control) {
		Objects.requireNonNull(expectedEpochId, "expectedEpochId");
		Objects.requireNonNull(control, "control");
		if (activeEpoch() == null) return ReformResult.NO_EPOCH;
		if (!activeEpoch().epochId().equals(expectedEpochId)) return ReformResult.EPOCH_MISMATCH;
		if (!(owner.getLevel() instanceof ServerLevel serverLevel)) return ReformResult.PARTIAL_UNLOADED;
		return ComputerTopologyController.stopAllAndReform(this, expectedEpochId, control,
			ComputerTopologyController.realWorld(serverLevel),
			ComputerStructureScanner.Limits.fromConfig());
	}
	ClusterBinding bindingState() {
		var binding = productionTopology.state().binding();
		return binding == null ? null : ClusterBinding.fromProduction(binding);
	}
	boolean bindingStateValid() { return productionTopology.state().bindingValid(); }
	private @Nullable ProductionStructure.Revision structureRecord() { return productionTopology.state().structure(); }
	private @Nullable ProductionEpoch activeEpoch() { return productionTopology.state().epoch(); }
	private static ProductionComputerTopology.State<EpochFault> productionState(
		@Nullable ProductionStructure.Revision record, @Nullable ClusterBinding binding, boolean bindingValid,
		@Nullable ProductionEpoch epoch, Set<EpochFault> faults) {
		return new ProductionComputerTopology.State<>(record,
			binding==null ? null : binding.productionBinding(),bindingValid,epoch,faults);
	}
	boolean persistenceAvailable() { return persistenceAvailable; }
	boolean topologyDirty() { return productionScan.dirty(); }

	void applyTopologyState(@Nullable ProductionStructure.Revision record,
		@Nullable ClusterBinding binding, boolean bindingValid, @Nullable ProductionEpoch epoch,
		Set<EpochFault> faults) {
		stageTopologyState(record, binding, bindingValid, epoch, faults);
		owner.setChanged();
	}

	void stageTopologyState(@Nullable ProductionStructure.Revision record,
		@Nullable ClusterBinding binding, boolean bindingValid, @Nullable ProductionEpoch incomingEpoch,
		Set<EpochFault> incomingFaults) {
		var next = productionState(record,binding,bindingValid,incomingEpoch,incomingFaults);
		Set<String> topologyChildren = Set.of("Structure", "Binding", "Epoch", "Faults", "BindingInvalid");
		boolean changed = !productionTopology.state().equals(next)
			|| archive.recovery().containsAny(topologyChildren);
		if (coordinatorMember.isPublished() && changed && productionTopology.invalidatesPublication(next,owner.computerId().orElse(null)))
			withdrawCoordinatorMember();
		productionTopology.replace(next);
		if (record != null) productionLifecycle.acceptStructure();
		runtime.reconcile(productionRuntimeScope());
		archive.recovery().forget(topologyChildren);
		persistenceAvailable = validateCrossChildren();
		if (changed) advanceTopologyVersion();
		refreshShellMembership();
	}

	void publishTopologyChange() {
		owner.setChanged();
		owner.sendData();
		publishRadioLifecycle();
	}

	/** Re-publishes the existing primary Computer endpoint when a bound Core changes lifecycle. */
	void topologyDependencyChanged() {
		if (!isAuthoritativeLoadedInstance()) return;
		if (!advanceTopologyVersion()) return;
		owner.setChanged();
		publishRadioLifecycle();
	}

	void stagePreparedBinding(ClusterBinding prepared) {
		productionTopology.replaceBinding(Objects.requireNonNull(prepared, "prepared").productionBinding());
		archive.recovery().forget("Binding", "BindingInvalid");
		persistenceAvailable = validateCrossChildren();
		advanceTopologyVersion();
	}

	void publishPreparedBinding() {
		owner.setChanged();
		owner.sendData();
		publishRadioLifecycle();
	}

	void publishCoordinatorMember(List<ProductionComputer> replicas, Set<UUID> requiredIds) {
		coordinatorMember.publish(replicas, requiredIds);
	}

	void withdrawCoordinatorMember() {
		coordinatorMember.withdraw();
	}

	private void invalidateCoordinatorPublication() {
		coordinatorMember.invalidatePublication();
	}

	boolean isCoordinatorMemberPublished() { return coordinatorMember.isPublished(); }
	boolean isAttachedTo(ComputerCoordinatorMember publication) {
		return coordinatorMember.isAttachedTo(publication);
	}

	@Nullable
	MinecraftServer coordinatorServer() {
		return owner.getLevel() == null || owner.getLevel().isClientSide ? null : owner.getLevel().getServer();
	}

	SpaceAddress coordinatorMemberAddress() {
		return SpaceAddress.capture(Objects.requireNonNull(owner.getLevel(), "Computer has no level"),
			owner.getBlockPos());
	}

	TopologyState topologyState() {
		return new TopologyState(owner.computerId().orElse(null), installedProfile, structureRecord(), bindingState(),
			bindingStateValid(), activeEpoch(), latchedEpochFaults(), persistenceAvailable, productionLifecycle.generation());
	}

	boolean topologyMatches(TopologyState expected) {
		return Objects.equals(owner.computerId().orElse(null), expected.computerId())
			&& Objects.equals(installedProfile, expected.profile())
			&& Objects.equals(structureRecord(), expected.record())
			&& Objects.equals(bindingState(), expected.binding())
			&& bindingStateValid() == expected.bindingValid()
			&& Objects.equals(activeEpoch(), expected.epoch())
			&& latchedEpochFaults().equals(expected.faults())
			&& persistenceAvailable == expected.persistenceAvailable()
			&& productionLifecycle.generation() == expected.version();
	}

	void setAvailabilityReason(ComputerAvailabilityReason reason) {
		availabilityReason = Objects.requireNonNull(reason, "reason");
	}

	record TopologyState(@Nullable UUID computerId, @Nullable ProductionResourceProfile profile,
		@Nullable ProductionStructure.Revision record, @Nullable ClusterBinding binding,
		boolean bindingValid, @Nullable ProductionEpoch epoch, Set<EpochFault> faults,
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

	private void publishRadioLifecycle() {
		if (radioTopologyEligible() && owner.getLevel() != null && !owner.getLevel().isClientSide)
			FactoryOrchestrationServer.nativeComputerObserved(this);
	}

	private void refreshShellMembership() {
		if (!(owner.getLevel() instanceof ServerLevel serverLevel) || owner.getLevel().isClientSide) return;
		shellMembership.refresh(serverLevel.getServer(), productionLifecycle.generation(), () -> currentStructureSnapshot()
			.map(snapshot -> snapshot.casingPositions().stream()
				.map(casing -> productionAddress(SpaceAddress.capture(serverLevel, casing))).toList()).orElse(List.of()));
	}

	private void removeShellMembership() { shellMembership.clear(); }

	private static ProductionLayout.Address productionAddress(SpaceAddress address) {
		return new ProductionLayout.Address(address.dimension(), address.subLevelId(), address.localPos());
	}

	private boolean installationAllowedByTopology() {
		return productionTopology.allowsResidentInstallation(owner.computerId().orElse(null));
	}

	private boolean validateCrossChildren() {
		if (archive.recovery().hasProblems() || owner.computerId().orElse(null) == null || !bindingStateValid()) return false;
		// A physical resident can exist without a production application. An orphan production
		// profile is still invalid; production execution separately requires its frozen profile.
		if (!residentStorageValid.getAsBoolean() || installedProfile != null && owner.residentSnapshot().isEmpty()) return false;
		if ((installedProfile == null || installedProfile.profession()
			!= ProductionProfession.WANDERING_TRADER) && !externalListenerConfiguration.frequencies().isEmpty())
			return false;
		if (installedProfile != null && externalListenerConfiguration.frequencies().size()
			> installedProfile.processSlots()) return false;
		return productionTopology.validFor(owner.computerId().orElse(null));
	}

    public boolean isAuthoritativeLoadedInstance() { return owner.isAuthoritativeLoadedInstance(); }

    public void invalidate() {
        if (owner.getLevel() != null && !owner.getLevel().isClientSide)
            FactoryOrchestrationServer.nativeComputerUnloaded(this);
        removeShellMembership();
        detachScanLease();
        invalidateCoordinatorPublication();
    }

    /** Retire the old loaded owner before resetting its token or touching the saved envelope. */
    public void beforeServerRead() {
        FactoryOrchestrationServer.nativeComputerRetired(this);
        detachScanLease();
        invalidateCoordinatorPublication();
    }

    public void resetForServerRead(HolderLookup.Provider registries) {
        removeShellMembership();
        installedProfile = null;
        externalListenerConfiguration = ExternalListenerConfiguration.empty();
        productionLifecycle.resetForRead();
        productionTopology.replace(ProductionComputerTopology.State.empty(false));
        persistenceAvailable = false;
        productionScan.reset(owner);
        productionTick.reset();
        runtime.load(null, registries, null);
    }

    public ProductionComputerArchive.Values<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
        ExternalListenerConfiguration, Set<EpochFault>> archiveValues() {
        return new ProductionComputerArchive.Values<>(computerId().orElse(null), productionLifecycle.generation(),
            installedProfile, externalListenerConfiguration, structureRecord(), bindingState(), bindingStateValid(),
            activeEpoch(), latchedEpochFaults());
    }

    public Tag runtimeArchive(HolderLookup.Provider registries) {
        return archive.recovery().hasOpaque() ? new CompoundTag() : runtime.save(registries);
    }

    /** Called inside the host's single envelope commit, after resident and recovery have committed. */
    public void commitArchive(ProductionComputerArchive.Prepared<ProductionStructure.Revision, ClusterBinding, ProductionEpoch,
        ExternalListenerConfiguration, Set<EpochFault>> prepared, HolderLookup.Provider registries) {
        var values = prepared.values();
        productionLifecycle.restoreGeneration(values.topologyVersion());
        installedProfile = values.profile();
        externalListenerConfiguration = values.listeners();
        productionTopology.replace(productionState(values.structure(), values.binding(), values.bindingValid(), values.epoch(), values.faults()));
        runtime.load(prepared.runtime(), registries, productionRuntimeScope());
        runtime.reconcile(productionRuntimeScope());
        persistenceAvailable = prepared.valid() && validateCrossChildren() && runtime.snapshot().persistenceValid();
    }

    public boolean permitsResidentInstallation() { return persistenceAvailable && installationAllowedByTopology(); }

    public void beforeResidentRemoval() { invalidateCoordinatorPublication(); }
    public void residentRemoved() {
        installedProfile = null;
        externalListenerConfiguration = ExternalListenerConfiguration.empty();
        archive.recovery().forget("Profile");
        persistenceAvailable = validateCrossChildren();
        advanceTopologyVersion();
        owner.setChanged();
        owner.sendData();
        publishRadioLifecycle();
    }

    public ComputerResidentInstallation.Prepared prepareResidentInstallation(ComputerResidentInstallation.Candidate candidate) {
        var profile = ProductionResidentProfiles.fromHardwareIdentity(candidate.hardware(),
            FactoryRuntimeConfig.fromServerConfig().wanderingTraderListenerBonus()).orElse(null);
        var previousProfile = installedProfile;
        var previousListeners = externalListenerConfiguration;
        var previousRawProfile = archive.recovery().raw("Profile");
        boolean previousAvailable = persistenceAvailable;
        return new ComputerResidentInstallation.Prepared() {
            public void commit() {
                installedProfile = profile;
                externalListenerConfiguration = ExternalListenerConfiguration.empty();
                archive.recovery().forget("Profile");
                persistenceAvailable = validateCrossChildren();
            }
            public void rollback() {
                installedProfile = previousProfile;
                externalListenerConfiguration = previousListeners;
                archive.recovery().restoreRaw("Profile", previousRawProfile);
                persistenceAvailable = previousAvailable;
            }
            public void published() {
                invalidateCoordinatorPublication();
                markTopologyDirty();
                advanceTopologyVersion();
                owner.setChanged();
                owner.sendData();
                publishRadioLifecycle();
            }
        };
    }

	public FactoryGoggleState captureNormalGoggles() {
		FactoryGoggleCapture.requireServerThread(owner);
		var cluster=activeEpoch()==null?null:activeEpoch().groupId();
		var configuration=owner.getLevel() instanceof ServerLevel server && cluster!=null ? FactoryOrchestrationServer.communicationConfiguration(server.getServer(),cluster).orElse(null):null;
		var state=runtime.snapshot().state();
		int used=(int)state.processes().values().stream().filter(p->p.owningComputerId().equals(computerId().orElse(null)) && p.state()!=com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL).count();
		if(owner.getLevel() instanceof ServerLevel server && cluster!=null && computerId().orElse(null)!=null) used=FactoryOrchestrationServer.normalComputerUsedSlots(server.getServer(),cluster,computerId().orElse(null)).orElse(used);
		var resident=ComputerResidentView.fromSnapshot(owner.residentSnapshot(), owner.getLevel() == null ? net.minecraft.core.RegistryAccess.EMPTY : owner.getLevel().registryAccess()).resident();
		var projection=new FactoryGoggleProjection.Computer(FactoryGoggleCapture.shortId(cluster),configuration==null?"UNAVAILABLE":configuration.role().name(),
			structureOnline()?"FORMED":"UNFORMED",installedProfile==null?"NONE":installedProfile.profession().name(),resident==null?0:resident.level(),used,installedProfile==null?0:installedProfile.processSlots(),
			configuration==null?"":configuration.primaryFrequency().value(),externalListenerConfiguration.frequencies().stream().map(CommunicationFrequency::value).toList(),coordinatorOnline()?displayState.name():"UNAVAILABLE");
		var result=FactoryGoggleProjectionFormatter.project(projection);
		if(owner.getLevel() instanceof ServerLevel server && cluster!=null && computerId().orElse(null)!=null) result=FactoryOrchestrationServer.normalRadioState(server.getServer(),new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint.Key(cluster,computerId().orElse(null))).map(FactoryGoggleProjectionFormatter::project).map(result::append).orElse(result);
		return result;
	}
}
