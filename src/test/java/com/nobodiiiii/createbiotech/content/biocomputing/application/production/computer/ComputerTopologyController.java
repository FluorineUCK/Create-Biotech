package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyCommit;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionPanelPublication;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochStart;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochFaultLatch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochClose;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionQuiescence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochReform;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.foundation.block.CBMultiBlockLifecycle;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

final class ComputerTopologyController {
	private ComputerTopologyController() {}

	interface WorldAccess {
		ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits);
		boolean isLoaded(BlockPos pos);
		boolean isChunkLoaded(ChunkPos chunk);
		boolean sameSpace(BlockPos origin, BlockPos pos);
		@Nullable ProductionComputer loadedComputer(BlockPos pos);
		ProductionLayout.Address address(BlockPos pos);
		UUID newStructureMemberId();
		default ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) {
			return ClusterBindingService.BindingAccess.READY;
		}
		default boolean enforceFoundationPublication() { return false; }
		default @Nullable ProductionComputer resolveComputer(ProductionLayout.Address address) {
			return loadedComputer(address.localPos());
		}
		// Historical topology proof only. No current physical port attests to an old panel epoch.
		default @Nullable ProductionLayout.Panel productionPanel(BlockPos pos) { return null; }
		default boolean publishPanel(ProductionLayout.Panel panel,
			ClusterBinding binding, ProductionEpoch epoch) { return false; }
		default boolean panelPublishedFor(ProductionLayout.Panel panel, ProductionEpoch epoch) {
			return false;
		}
		default void withdrawPanel(ProductionLayout.Panel panel) {}
	}

	static WorldAccess realWorld(ServerLevel level) {
		return new RealWorldAccess(level);
	}

	static RefreshOutcome refresh(ProductionComputer owner, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(limits, "limits");
		if (!owner.persistenceAvailable() || owner.computerId().isEmpty()) {
			failAndWithdrawEpochPanels(owner, ComputerAvailabilityReason.IDENTITY_CONFLICT, world);
			return RefreshOutcome.failure(ComputerAvailabilityReason.IDENTITY_CONFLICT);
		}
		ProductionStructure.Revision known = owner.currentStructureRecord().orElse(null);
		if (known != null && !chunksLoaded(known.structure().containingChunks(), world)) {
			failAndWithdrawEpochPanels(owner, ComputerAvailabilityReason.PARTIAL_UNLOADED, world);
			return RefreshOutcome.failure(ComputerAvailabilityReason.PARTIAL_UNLOADED);
		}

		ComputerStructureScanner.ScanResult result = world.scan(owner.owner().getBlockPos(), limits);
		if (result.state() != ComputerStructureScanner.State.VALID
			&& result.state() != ComputerStructureScanner.State.VALID_NOT_READY) {
			ComputerAvailabilityReason reason = reasonFor(result.state());
			failAndWithdrawEpochPanels(owner, reason, world);
			return RefreshOutcome.failure(reason);
		}
		ProductionStructure snapshot = result.structure();
		if (snapshot == null) {
			failAndWithdrawEpochPanels(owner, ComputerAvailabilityReason.STRUCTURE_INVALID, world);
			return RefreshOutcome.failure(ComputerAvailabilityReason.STRUCTURE_INVALID);
		}
		ResolvedSnapshot resolved = resolveSnapshot(owner.owner().getBlockPos(), snapshot, world);
		if (resolved.failure() != ResolveFailure.NONE) {
			ComputerAvailabilityReason reason = resolved.failure() == ResolveFailure.PARTIAL
				? ComputerAvailabilityReason.PARTIAL_UNLOADED
				: resolved.failure() == ResolveFailure.IDENTITY
					? ComputerAvailabilityReason.IDENTITY_CONFLICT
					: ComputerAvailabilityReason.STRUCTURE_INVALID;
			failAndWithdrawEpochPanels(owner, reason, world);
			return new RefreshOutcome(resolved.computers(), snapshot, reason, false);
		}
		UUID provisional = snapshot.layout().members().getFirst().computerId();
		if (!owner.computerId().orElseThrow().equals(provisional)
			|| (resolved.byId().get(provisional) == null || resolved.byId().get(provisional).owner() != owner.owner()))
			return new RefreshOutcome(resolved.computers(), snapshot, null, false);

		Reconciliation reconciliation = reconcile(result, resolved);
		if (reconciliation.failureReason() != null) {
			setReasonAndWithdraw(resolved.computers(), reconciliation.failureReason());
			withdrawEpochPanels(owner, world);
			return new RefreshOutcome(resolved.computers(), snapshot,
					reconciliation.failureReason(), false);
		}
		var publication = commit(resolved, reconciliation.record(), reconciliation.binding(),
			reconciliation.epoch(), reconciliation.faults(), reconciliation.reason());
		var reason = publication == ProductionTopologyCommit.Outcome.RECOVERY_FAILED
			? ComputerAvailabilityReason.PERSISTENCE_INVALID : reconciliation.reason();
		return new RefreshOutcome(resolved.computers(), snapshot, reason,
			publication == ProductionTopologyCommit.Outcome.COMMITTED);
	}

	record RefreshOutcome(List<ProductionComputer> members,
		@Nullable ProductionStructure snapshot,
		@Nullable ComputerAvailabilityReason reason, boolean committed) {
		RefreshOutcome {
			members = List.copyOf(members);
		}

		static RefreshOutcome failure(ComputerAvailabilityReason reason) {
			return new RefreshOutcome(List.of(), null, reason, false);
		}
	}

	static void fanAvailability(List<ProductionComputer> members,
		ComputerAvailabilityReason reason) {
		setReasonAndWithdraw(members, reason);
	}

	static void withdrawPersistedLeaseService(List<ProductionComputer> members,
		ComputerAvailabilityReason reason, WorldAccess world) {
		Set<ProductionLayout.Panel> panels = new LinkedHashSet<>();
		for (ProductionComputer computer : members) {
			computer.withdrawCoordinatorMember();
			computer.setAvailabilityReason(reason);
			computer.epoch().ifPresent(epoch -> panels.addAll(epoch.layout().panels()));
		}
		panels.forEach(world::withdrawPanel);
	}

	private static Reconciliation reconcile(ComputerStructureScanner.ScanResult scan,
		ResolvedSnapshot resolved) {
		var snapshot = scan.structure();
		if (snapshot == null) return Reconciliation.failure(ComputerAvailabilityReason.STRUCTURE_INVALID);
		var replicas = resolved.computers().stream().map(ProductionComputer::topologyState)
			.map(ComputerTopologyController::productionReplica).toList();
		var decision = ProductionTopologyReconciler.reconcile(snapshot,
			scan.state() == ComputerStructureScanner.State.VALID, scan.observedStructureMemberId(), replicas,
			new ProductionTopologyReconciler.Evidence() {
				@Override public UUID newStructureId() { return resolved.world().newStructureMemberId(); }
				@Override public ProductionTopologyReconciler.Proof proveReelection(ProductionStructure.Revision previous,
					ProductionStructure current, UUID coordinator) {
										BlockPos seed = resolved.byId().get(coordinator).owner().getBlockPos();
					ProofFailure proof = proveInactiveReelectionDomain(seed, previous, current, resolved.world());
					if (proof == ProofFailure.PARTIAL) return ProductionTopologyReconciler.Proof.PARTIAL_UNLOADED;
					if (proof == ProofFailure.SPACE) return ProductionTopologyReconciler.Proof.IDENTITY_CONFLICT;
					var matches = computerIdPositionsInBounds(previous.coordinatorId(),
						List.of(previous.structure().layout().bounds(), current.layout().bounds()), resolved.world());
					var oldCoordinator = current.layout().members().stream().filter(member -> member.computerId().equals(previous.coordinatorId())).findFirst().orElse(null);
					if (oldCoordinator == null ? !matches.isEmpty() : matches.size() != 1
						|| !matches.getFirst().equals(oldCoordinator.address().localPos())
						|| !resolved.world().address(matches.getFirst()).equals(oldCoordinator.address()))
						return ProductionTopologyReconciler.Proof.IDENTITY_CONFLICT;
					return ProductionTopologyReconciler.Proof.READY;
				}
			});
		ComputerAvailabilityReason status = switch (decision.status()) {
			case NONE -> ComputerAvailabilityReason.NONE;
			case NOT_READY -> ComputerAvailabilityReason.NOT_READY;
			case IDENTITY_CONFLICT -> ComputerAvailabilityReason.IDENTITY_CONFLICT;
			case PERSISTENCE_INVALID -> ComputerAvailabilityReason.PERSISTENCE_INVALID;
			case COORDINATOR_MISSING -> ComputerAvailabilityReason.COORDINATOR_MISSING;
			case PARTIAL_UNLOADED -> ComputerAvailabilityReason.PARTIAL_UNLOADED;
			case STRUCTURE_INVALID -> ComputerAvailabilityReason.STRUCTURE_INVALID;
			case FROZEN_MEMBER_MISSING -> ComputerAvailabilityReason.FROZEN_MEMBER_MISSING;
		};
		if (!decision.accepted()) return Reconciliation.failure(status);
		return new Reconciliation(decision.record(),
			decision.binding() == null ? null : ClusterBinding.fromProduction(decision.binding()),
			decision.epoch(), decision.faults(), status, null);
	}

	private static ProductionTopologyReconciler.Replica<EpochFault> productionReplica(ProductionComputer.TopologyState state) {
		return new ProductionTopologyReconciler.Replica<>(state.computerId(), state.persistenceAvailable(), state.bindingValid(),
			state.record(),
			state.binding() == null ? null : state.binding().productionBinding(),
			state.epoch(), state.faults());
	}

	private static ProductionTopologyCommit.Outcome commit(ResolvedSnapshot resolved, ProductionStructure.Revision record,
		@Nullable ClusterBinding binding, @Nullable ProductionEpoch epoch, Set<EpochFault> faults,
		ComputerAvailabilityReason reason) {
		boolean publish = publicationReady(resolved, record, epoch, faults, reason);
		ProductionComputer elected = resolved.byId().get(record.coordinatorId());
		ProductionEpoch priorEpoch = resolved.states().stream().map(ProductionComputer.TopologyState::epoch)
			.filter(Objects::nonNull).findFirst().orElse(null);
		ClusterBinding priorBinding = resolved.states().stream().map(ProductionComputer.TopologyState::binding)
			.filter(Objects::nonNull).findFirst().orElse(null);
		List<ProductionLayout.Panel> priorPanels = priorEpoch == null ? List.of() : priorEpoch.layout().panels().stream()
			.filter(panel -> resolved.world().panelPublishedFor(panel, priorEpoch)).toList();
		var changes = new ArrayList<ProductionTopologyCommit.Replica>();
		for (int i = 0; i < resolved.computers().size(); i++) {
			var computer = resolved.computers().get(i);
			var before = resolved.states().get(i);
			var nextBinding = epoch != null || before.record() == null ? binding : before.binding();
			changes.add(prepareReplica(resolved, computer, before, computer != elected || !publish, () -> {
				computer.stageTopologyState(record, nextBinding, true, epoch, faults);
				computer.setAvailabilityReason(reason);
			}));
		}
		return ProductionTopologyCommit.commit(changes, new ProductionTopologyCommit.Publication() {
			@Override public boolean publish() {
				if (!publish || elected == null) return true;
				elected.publishCoordinatorMember(resolved.computers(), record.structure().layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
				updateFoundationAccess(resolved, elected, binding);
				return epoch == null || publishPanels(record.structure().layout().panels(), binding, epoch, resolved.world());
			}
			@Override public boolean restore() {
				return priorEpoch == null || priorBinding == null || publishPanels(priorPanels, priorBinding, priorEpoch, resolved.world());
			}
			@Override public void withdraw() {
				if (priorEpoch != null) priorEpoch.layout().panels().forEach(resolved.world()::withdrawPanel);
			}
		});
	}

	/** Narrow host adapter; ordering and recovery belong to the production application commit engine. */
	private static ProductionTopologyCommit.Replica prepareReplica(ResolvedSnapshot resolved,
		ProductionComputer computer, ProductionComputer.TopologyState before,
		boolean withdrawBeforeCommit, Runnable stage) {
		var beforeReason = computer.availabilityReason();
		return new ProductionTopologyCommit.Replica() {
			@Override public boolean matchesExpected() { return computer.topologyMatches(before); }
			@Override public boolean published() { return computer.isCoordinatorMemberPublished(); }
			@Override public boolean withdrawBeforeCommit() { return withdrawBeforeCommit; }
			@Override public void withdraw() { computer.withdrawCoordinatorMember(); }
			@Override public void stage() { stage.run(); }
			@Override public void restore() {
				computer.stageTopologyState(before.record(), before.binding(), before.bindingValid(), before.epoch(), before.faults());
				computer.setAvailabilityReason(beforeReason);
			}
			@Override public void changed() { computer.publishTopologyChange(); }
			@Override public void republish() {
				if (before.record() != null) computer.publishCoordinatorMember(resolved.computers(), before.record().structure().layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
			}
			@Override public void markUnavailable() { computer.setAvailabilityReason(ComputerAvailabilityReason.PERSISTENCE_INVALID); }
		};
	}

	private static boolean publishPanels(List<ProductionLayout.Panel> panels,
		@Nullable ClusterBinding binding, ProductionEpoch epoch, WorldAccess world) {
		// The port captures one binding/epoch for the entire batch; do not re-project the whole epoch per panel.
		return ProductionPanelPublication.publish(panels, binding == null ? null : binding.productionBinding(),
			new ProductionPanelPublication.Access() {
				@Override public boolean published(ProductionLayout.Panel panel) { return world.panelPublishedFor(panel, epoch); }
				@Override public boolean publish(ProductionLayout.Panel panel) { return world.publishPanel(panel, binding, epoch); }
				@Override public void withdraw(ProductionLayout.Panel panel) { world.withdrawPanel(panel); }
			});
	}

	private static void updateFoundationAccess(ResolvedSnapshot resolved,
		ProductionComputer elected, @Nullable ClusterBinding binding) {
		ClusterBindingService.BindingAccess access =
			resolved.world().bindingAccess(elected.coordinatorMember);
		boolean coherent = elected.coordinatorMember.updateBindingAccess(access);
		if (binding == null || access == ClusterBindingService.BindingAccess.READY && coherent)
			return;
		ComputerAvailabilityReason unavailable = access
			== ClusterBindingService.BindingAccess.CONFLICT
			|| access == ClusterBindingService.BindingAccess.READY && !coherent
				? ComputerAvailabilityReason.IDENTITY_CONFLICT
				: ComputerAvailabilityReason.AUTHORITY_OFFLINE;
		setReason(resolved.computers(), unavailable);
	}

	static ProductionComputer.EpochStartResult startEpoch(ProductionComputer caller,
		UUID clusterId, WorldAccess world, ComputerStructureScanner.Limits limits) {
		var result = ProductionEpochStart.start(caller.owner(), clusterId,
			new ProductionEpochStart.Access<HistoricalProductionHost, ProductionComputer.TopologyState, EpochFault>() {
				private ResolvedSnapshot resolved;
				@Override public ProductionEpochStart.Capture<ProductionComputer.TopologyState, EpochFault> capture(HistoricalProductionHost host) {
					var state = host.production().topologyState();
					return new ProductionEpochStart.Capture<>(state, productionReplica(state));
				}
				@Override public boolean notReady(HistoricalProductionHost host) { return host.production().availabilityReason() == ComputerAvailabilityReason.NOT_READY; }
				@Override public boolean coordinatorOnline(HistoricalProductionHost host) { return host.production().coordinatorOnline(); }
				@Override public boolean chunksLoaded(ProductionStructure structure) { return ComputerTopologyController.chunksLoaded(structure.containingChunks(), world); }
				@Override public ProductionStructureScanner.ScanResult scan(HistoricalProductionHost host) {
					var scan = world.scan(host.getBlockPos(), limits);
					return new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.valueOf(scan.state().name()),
						scan.structure(), scan.observedStructureMemberId());
				}
				@Override public ProductionEpochStart.Resolved<HistoricalProductionHost> resolve(HistoricalProductionHost host, ProductionStructure structure) {
					resolved = resolveSnapshot(host.getBlockPos(), structure, world);
					return new ProductionEpochStart.Resolved<>(physicalComputers(resolved), physicalById(resolved), resolved.failure() == ResolveFailure.NONE);
				}
				@Override public boolean enforcePublication() { return world.enforceFoundationPublication(); }
				@Override public boolean published(HistoricalProductionHost host) { return host.production().isCoordinatorMemberPublished(); }
				@Override public boolean refreshBindingAuthority(HistoricalProductionHost host) {
					var access = world.bindingAccess(host.production().coordinatorMember);
					return access == ClusterBindingService.BindingAccess.READY && host.production().coordinatorMember.updateBindingAccess(access);
				}
				@Override public ProductionTopologyCommit.Replica prepare(HistoricalProductionHost host,
					ProductionEpochStart.Capture<ProductionComputer.TopologyState, EpochFault> before,
					ProductionStructure.Revision record, ProductionBinding binding, ProductionEpoch epoch) {
					return prepareReplica(resolved, host.production(), before.witness(), false, () -> {
						host.production().stageTopologyState(record, ClusterBinding.fromProduction(binding),
							true, epoch, Set.of());
						host.production().setAvailabilityReason(ComputerAvailabilityReason.NONE);
					});
				}
				@Override public ProductionTopologyCommit.Publication publication(ProductionStructure structure,
					ProductionBinding binding, ProductionEpoch epoch) {
					return new ProductionTopologyCommit.Publication() {
						@Override public boolean publish() { return publishPanels(structure.layout().panels(),
							ClusterBinding.fromProduction(binding), epoch, world); }
						// An inactive epoch had no panels to restore; the batch publisher withdrew its own additions.
						@Override public boolean restore() { return true; }
						@Override public void withdraw() {}
					};
				}
			});
		return ProductionComputer.EpochStartResult.valueOf(result.name());
	}

	static ProductionComputer.FaultLatchResult latchEpochFault(ProductionComputer caller,
		UUID expectedEpochId, EpochFault fault, WorldAccess world) {
		var result = ProductionEpochFaultLatch.latch(caller.owner(), expectedEpochId, fault,
			new ProductionEpochFaultLatch.Access<HistoricalProductionHost, ProductionComputer.TopologyState, EpochFault>() {
				@Override public ProductionEpochFaultLatch.Capture<ProductionComputer.TopologyState, EpochFault> capture(HistoricalProductionHost host) {
					var state = host.production().topologyState();
					return new ProductionEpochFaultLatch.Capture<>(state, productionReplica(state));
				}
				@Override public BlockPos position(HistoricalProductionHost host) { return host.getBlockPos(); }
				@Override public boolean loaded(BlockPos pos) { return world.isLoaded(pos); }
				@Override public boolean sameSpace(HistoricalProductionHost host, BlockPos pos) { return world.sameSpace(host.getBlockPos(), pos); }
				@Override public HistoricalProductionHost computerAt(BlockPos pos) { return physicalComputerAt(world, pos); }
				@Override public ProductionLayout.Address address(BlockPos pos) {
					return world.address(pos);
				}
				@Override public boolean matches(HistoricalProductionHost host, ProductionComputer.TopologyState before) { return host.production().topologyMatches(before); }
				@Override public void stageFaults(HistoricalProductionHost host, Set<EpochFault> faults) {
					var state = host.production().topologyState();
					host.production().stageTopologyState(state.record(), state.binding(), state.bindingValid(), state.epoch(), faults);
				}
				@Override public boolean published(HistoricalProductionHost host) { return host.production().isCoordinatorMemberPublished(); }
				@Override public void withdraw(HistoricalProductionHost host) { host.production().withdrawCoordinatorMember(); }
				@Override public void withdrawPanel(ProductionLayout.Panel panel) { world.withdrawPanel(panel); }
				@Override public void changed(HistoricalProductionHost host) { host.production().publishTopologyChange(); }
			});
		return ProductionComputer.FaultLatchResult.valueOf(result.name());
	}

	static ProductionComputer.EpochCloseResult closeIdleEpoch(ProductionComputer caller,
		UUID expectedEpochId, ProductionQuiescence quiescence, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		return ProductionComputer.EpochCloseResult.valueOf(ProductionEpochClose.close(caller.owner(), expectedEpochId,
			Set.of(EpochFault.WIDTH, EpochFault.DEPTH), quiescence, new CloseAccess(world, limits)).name());
	}

	static ProductionComputer.ReformResult stopAllAndReform(ProductionComputer caller,
		UUID expectedEpochId, EpochReformControl control, WorldAccess world,
		ComputerStructureScanner.Limits limits) {
		return ProductionComputer.ReformResult.valueOf(ProductionEpochReform.reform(caller.owner(), expectedEpochId,
			control, new ReformAccess(world, limits)).name());
	}

	/** Per-invocation physical evidence adapter; never stored as program data or a shared runtime. */
	private static class EpochWorldAccess implements ProductionEpochAccess<HistoricalProductionHost, ProductionComputer.TopologyState, EpochFault> {
		final WorldAccess world;
		final ComputerStructureScanner.Limits limits;
		ResolvedSnapshot resolved;

		EpochWorldAccess(WorldAccess world, ComputerStructureScanner.Limits limits) { this.world = world; this.limits = limits; }
		@Override public ProductionComputer.TopologyState capture(HistoricalProductionHost host) { return host.production().topologyState(); }
		@Override public ProductionTopologyReconciler.Replica<EpochFault> value(ProductionComputer.TopologyState state) { return productionReplica(state); }
		@Override public boolean exactCaller(HistoricalProductionHost host, ProductionComputer.TopologyState state) {
			return exactLoadedCaller(host.production(), state, state.record(), world);
		}
		@Override public ProductionDomainProof.Failure proveOld(HistoricalProductionHost host, ProductionStructure.Revision record, ProductionEpoch epoch) {
			return ProductionDomainProof.prove(host.getBlockPos(), ProductionEpochAccess.oldDomain(record, epoch), proofWorld(world));
		}
		@Override public ProductionStructureScanner.ScanResult scan(HistoricalProductionHost host) {
			var scan = world.scan(host.getBlockPos(), limits);
			return new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.valueOf(scan.state().name()),
				scan.structure(), scan.observedStructureMemberId());
		}
		@Override public ProductionDomainProof.Failure proveCurrent(HistoricalProductionHost host, ProductionStructure current) {
			return ProductionDomainProof.prove(host.getBlockPos(), ProductionEpochAccess.currentDomain(current), proofWorld(world));
		}
		@Override public ProductionEpochAccess.Resolved<HistoricalProductionHost> resolve(HistoricalProductionHost host, ProductionStructure current) {
			resolved = resolveSnapshot(host.getBlockPos(), current, world);
			return new ProductionEpochAccess.Resolved<>(physicalComputers(resolved), physicalById(resolved), ProductionEpochAccess.ResolveFailure.valueOf(resolved.failure().name()));
		}
		@Override public ProductionDomainProof.Capture<HistoricalProductionHost, ProductionComputer.TopologyState> captureDomain(
			HistoricalProductionHost host, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) {
			return ProductionDomainProof.capture(host.getBlockPos(), ProductionEpochAccess.domain(records, epoch, current), proofWorld(world));
		}
		@Override public boolean revalidateDomain(ProductionDomainProof.Capture<HistoricalProductionHost, ProductionComputer.TopologyState> before,
			HistoricalProductionHost host, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) {
			return ProductionDomainProof.revalidate(before, host.getBlockPos(), ProductionEpochAccess.domain(records, epoch, current), proofWorld(world));
		}
		@Override public boolean revalidateResolved(HistoricalProductionHost host, ProductionStructure current, ProductionEpoch epoch, Set<EpochFault> faults) {
			return ComputerTopologyController.revalidateResolved(host.getBlockPos(), current,
				resolved, host.production(), world, limits, epoch, faults);
		}
	}

	private static final class CloseAccess extends EpochWorldAccess implements ProductionEpochClose.Access<HistoricalProductionHost, ProductionComputer.TopologyState, EpochFault> {
		CloseAccess(WorldAccess world, ComputerStructureScanner.Limits limits) { super(world, limits); }
		@Override public ProductionTopologyCommit.Replica prepare(HistoricalProductionHost host, ProductionComputer.TopologyState before,
			ProductionStructure.Revision record, boolean ready, boolean withdraw) {
			return prepareReplica(resolved, host.production(), before, withdraw, () -> {
				host.production().stageTopologyState(record, before.binding(), before.bindingValid(), null, Set.of());
				host.production().setAvailabilityReason(ready ? ComputerAvailabilityReason.NONE : ComputerAvailabilityReason.NOT_READY);
			});
		}
		@Override public ProductionTopologyCommit.Publication publication(ProductionStructure current, HistoricalProductionHost elected, boolean ready) {
			var snapshot = current;
			return new ProductionTopologyCommit.Publication() {
				@Override public boolean publish() {
					if (ready && elected != null) {
						elected.production().publishCoordinatorMember(resolved.computers(), snapshot.layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
						updateFoundationAccess(resolved, elected.production(), elected.production().bindingState());
					}
					snapshot.layout().panels().forEach(world::withdrawPanel);
					return true;
				}
				@Override public boolean restore() { return true; }
				@Override public void withdraw() {}
			};
		}
	}

	private static final class ReformAccess extends EpochWorldAccess implements ProductionEpochReform.Access<HistoricalProductionHost, ProductionComputer.TopologyState, EpochFault> {
		// Only the still-unmigrated binding boundary needs a transaction-local conversion.
		private final Map<ProductionBinding, ClusterBinding> bindingViews = new IdentityHashMap<>();
		ReformAccess(WorldAccess world, ComputerStructureScanner.Limits limits) { super(world, limits); }
		private ClusterBinding bindingView(ProductionBinding value) { return bindingViews.computeIfAbsent(value, ClusterBinding::fromProduction); }
		@Override public boolean panelPublished(ProductionLayout.Panel panel, ProductionEpoch epoch) {
			return world.panelPublishedFor(panel, epoch);
		}
		@Override public ProductionTopologyCommit.Replica prepare(HistoricalProductionHost host, ProductionComputer.TopologyState before,
			ProductionStructure.Revision record, ProductionBinding binding, ProductionEpoch replacement, boolean withdraw) {
			var legacyBinding = bindingView(binding);
			return prepareReplica(resolved, host.production(), before, withdraw, () -> {
				host.production().stageTopologyState(record, legacyBinding, true, replacement, Set.of());
				host.production().setAvailabilityReason(ComputerAvailabilityReason.NONE);
			});
		}
		@Override public ProductionTopologyCommit.Publication publication(ProductionStructure current, HistoricalProductionHost stagingOwner,
			ProductionBinding binding, ProductionEpoch previous, ProductionEpoch replacement, List<ProductionLayout.Panel> previouslyPublished) {
			var snapshot = current;
			var oldEpoch = previous;
			var nextEpoch = replacement;
			var legacyBinding = bindingView(binding);
			var oldPanels = previouslyPublished;
			return new ProductionTopologyCommit.Publication() {
				@Override public boolean publish() {
					stagingOwner.production().publishCoordinatorMember(resolved.computers(), snapshot.layout().members().stream().map(ProductionLayout.Member::computerId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
					oldEpoch.layout().panels().forEach(world::withdrawPanel);
					if (publishPanels(snapshot.layout().panels(), legacyBinding, nextEpoch, world)) return true;
					snapshot.layout().panels().forEach(world::withdrawPanel);
					return false;
				}
				@Override public boolean restore() { return publishPanels(oldPanels, legacyBinding, oldEpoch, world); }
				@Override public void withdraw() { oldEpoch.layout().panels().forEach(world::withdrawPanel); }
			};
		}
	}

	static List<ComputerNodeView> currentNodes(ProductionComputer computer) {
		ProductionStructure.Revision record = computer.currentStructureRecord().orElse(null);
		if (record == null) return List.of();
		Set<UUID> frozen = frozenIds(computer.epoch().orElse(null));
		List<ComputerNodeView> result = new ArrayList<>();
		for (ProductionLayout.Member node : record.structure().layout().members()) {
			boolean pending = computer.epoch().isPresent() && !frozen.contains(node.computerId());
			boolean online = nodeOnlineForReason(computer.availabilityReason())
				&& node.profile() != null && physicallyOnline(computer, node);
			result.add(new ComputerNodeView(node.computerId(), node.profile(), online, pending,
				record.coordinatorId().equals(node.computerId())));
		}
		return List.copyOf(result);
	}

	static List<ComputerNodeView> frozenNodes(ProductionComputer computer) {
		ProductionEpoch epoch = computer.epoch().orElse(null);
		ProductionStructure.Revision record = computer.currentStructureRecord().orElse(null);
		if (epoch == null || record == null) return List.of();
		List<ComputerNodeView> result = new ArrayList<>();
		for (ProductionLayout.Member node : epoch.layout().members()) {
			ProductionLayout.Member current = record.structure().layout().members().stream().filter(member -> member.computerId().equals(node.computerId())).findFirst().orElse(null);
			boolean exact = current != null && node.address().equals(current.address())
				&& node.profile().equals(current.profile());
			boolean online = nodeOnlineForReason(computer.availabilityReason()) && exact
				&& physicallyOnline(computer, current);
			result.add(new ComputerNodeView(node.computerId(), node.profile(), online, false,
				epoch.coordinatorId().equals(node.computerId())));
		}
		return List.copyOf(result);
	}

	static List<ComputerNodeView> pendingNodes(ProductionComputer computer) {
		return currentNodes(computer).stream().filter(ComputerNodeView::pending).toList();
	}

	private static boolean nodeOnlineForReason(ComputerAvailabilityReason reason) {
		return reason == ComputerAvailabilityReason.NONE
			|| reason == ComputerAvailabilityReason.FROZEN_MEMBER_MISSING
			|| reason == ComputerAvailabilityReason.AUTHORITY_OFFLINE;
	}

	private static boolean physicallyOnline(ProductionComputer owner,
		@Nullable ProductionLayout.Member node) {
		if (node == null || node.profile() == null) return false;
		if (!(owner.owner().getLevel() instanceof ServerLevel serverLevel)) return true;
		WorldAccess world = realWorld(serverLevel);
		BlockPos pos = node.address().localPos();
		if (!world.isLoaded(pos) || !world.sameSpace(owner.owner().getBlockPos(), pos)) return false;
		ProductionComputer current = world.loadedComputer(pos);
		return current != null && current.computerId().isPresent()
			&& current.computerId().orElseThrow().equals(node.computerId())
			&& Objects.equals(current.installedProfile().orElse(null), node.profile())
			&& world.address(pos).equals(node.address());
	}

	private static Set<UUID> frozenIds(@Nullable ProductionEpoch epoch) {
		if (epoch == null) return Set.of();
		Set<UUID> result = new LinkedHashSet<>();
		for (ProductionLayout.Member node : epoch.layout().members()) result.add(node.computerId());
		return Set.copyOf(result);
	}

	private static ComputerAvailabilityReason reasonFor(ComputerStructureScanner.State state) {
		return switch (state) {
			case PARTIAL_UNLOADED -> ComputerAvailabilityReason.PARTIAL_UNLOADED;
			case AMBIGUOUS -> ComputerAvailabilityReason.AMBIGUOUS;
			case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT ->
				ComputerAvailabilityReason.IDENTITY_CONFLICT;
			default -> ComputerAvailabilityReason.STRUCTURE_INVALID;
		};
	}

	private static void setReason(List<ProductionComputer> computers,
		ComputerAvailabilityReason reason) {
		for (ProductionComputer computer : computers) computer.setAvailabilityReason(reason);
	}

	private static void setReasonAndWithdraw(List<ProductionComputer> computers,
		ComputerAvailabilityReason reason) {
		for (ProductionComputer computer : computers) fail(computer, reason);
	}

	private static void fail(ProductionComputer computer, ComputerAvailabilityReason reason) {
		boolean activeCoordinator = computer.epoch().isPresent()
			&& computer.currentStructureRecord().filter(record -> computer.computerId()
				.filter(record.coordinatorId()::equals).isPresent()).isPresent();
		if (!activeCoordinator || reason != ComputerAvailabilityReason.PARTIAL_UNLOADED)
			computer.withdrawCoordinatorMember();
		computer.setAvailabilityReason(reason);
	}

	private static void failAndWithdrawEpochPanels(ProductionComputer computer,
		ComputerAvailabilityReason reason, WorldAccess world) {
		fail(computer, reason);
		withdrawEpochPanels(computer, world);
	}

	private static void withdrawEpochPanels(ProductionComputer computer, WorldAccess world) {
		computer.epoch().ifPresent(epoch -> epoch.layout().panels().forEach(world::withdrawPanel));
	}

	private static boolean publicationReady(ResolvedSnapshot resolved,
		ProductionStructure.Revision record, @Nullable ProductionEpoch epoch, Set<EpochFault> faults,
		ComputerAvailabilityReason reason) {
		if (!faults.isEmpty()) return false;
		ProductionComputer elected = resolved.byId().get(record.coordinatorId());
		if (elected == null || elected.computerId().filter(record.coordinatorId()::equals).isEmpty())
			return false;
		if (epoch == null)
			return reason == ComputerAvailabilityReason.NONE
				&& record.structure().layout().members().stream().allMatch(node -> node.profile() != null);
		if (reason != ComputerAvailabilityReason.NONE
			&& reason != ComputerAvailabilityReason.FROZEN_MEMBER_MISSING) return false;
		ProductionLayout.Member frozen = epoch.layout().members().stream()
			.filter(node -> node.computerId().equals(record.coordinatorId()))
			.findFirst().orElse(null);
		return frozen != null && elected.installedProfile().filter(frozen.profile()::equals).isPresent();
	}

	private static boolean chunksLoaded(Set<ChunkPos> chunks, WorldAccess world) {
		for (ChunkPos chunk : chunks) if (!world.isChunkLoaded(chunk)) return false;
		return true;
	}

	private static ResolvedSnapshot resolveSnapshot(BlockPos seed,
		ProductionStructure snapshot, WorldAccess world) {
		List<ProductionComputer> computers = new ArrayList<>();
		List<ProductionComputer.TopologyState> states = new ArrayList<>();
		Map<UUID, ProductionComputer> byId = new LinkedHashMap<>();
		if (!chunksLoaded(snapshot.containingChunks(), world))
			return ResolvedSnapshot.failure(world, ResolveFailure.PARTIAL);
		for (ProductionLayout.Member node : snapshot.layout().members()) {
			BlockPos pos = node.address().localPos();
			if (!world.isLoaded(pos)) return ResolvedSnapshot.failure(world, ResolveFailure.PARTIAL);
			if (!world.sameSpace(seed, pos)) return ResolvedSnapshot.failure(world, ResolveFailure.SPACE);
			ProductionComputer computer = world.loadedComputer(pos);
			if (computer == null || computer.computerId().isEmpty()
				|| !computer.computerId().orElseThrow().equals(node.computerId())
				|| !world.address(pos).equals(node.address())
				|| !computer.persistenceAvailable()
				|| !Objects.equals(computer.installedProfile().orElse(null), node.profile())
				|| byId.put(node.computerId(), computer) != null)
				return ResolvedSnapshot.failure(world, ResolveFailure.IDENTITY);
			computers.add(computer);
			states.add(computer.topologyState());
		}
		for (ProductionLayout.Panel node : snapshot.layout().panels()) {
			BlockPos pos = node.address().localPos();
			if (!world.isLoaded(pos)) return ResolvedSnapshot.failure(world, ResolveFailure.PARTIAL);
			if (!world.sameSpace(seed, pos)) return ResolvedSnapshot.failure(world, ResolveFailure.SPACE);
			if (!node.equals(world.productionPanel(pos)) || !world.address(pos).equals(node.address()))
				return ResolvedSnapshot.failure(world, ResolveFailure.IDENTITY);
		}
		return new ResolvedSnapshot(List.copyOf(computers), Map.copyOf(byId),
			List.copyOf(states), world, ResolveFailure.NONE);
	}

	private static ProofFailure proveInactiveReelectionDomain(BlockPos seed,
		ProductionStructure.Revision record, ProductionStructure current, WorldAccess world) {
		return ProofFailure.valueOf(ProductionDomainProof.prove(seed,new ProductionDomainProof.Domain(
			List.of(record.structure().layout().bounds(),current.layout().bounds()),List.of()),
			proofWorld(world)).name());
	}

	private static List<BlockPos> computerIdPositionsInBounds(UUID computerId,
		List<ProductionLayout.Bounds> bounds, WorldAccess world) {
		List<BlockPos> matches = new ArrayList<>();
		for (BlockPos pos : positions(bounds, List.of())) {
			ProductionComputer computer = world.loadedComputer(pos);
			if (computer != null && computer.computerId().filter(computerId::equals).isPresent())
				matches.add(pos);
		}
		return List.copyOf(matches);
	}

	private static boolean exactLoadedCaller(ProductionComputer caller,
		ProductionComputer.TopologyState state, ProductionStructure.Revision record,
		WorldAccess world) {
		UUID computerId = state.computerId();
		if (computerId == null) return false;
		ProductionLayout.Member persisted = record.structure().layout().members().stream().filter(member -> member.computerId().equals(computerId)).findFirst().orElse(null);
		if (persisted == null || !persisted.address().localPos().equals(caller.owner().getBlockPos()))
			return false;
		BlockPos pos = caller.owner().getBlockPos();
		return world.isLoaded(pos) && world.sameSpace(pos, pos)
			&& physicalComputerAt(world, pos) == caller.owner()
			&& world.address(pos).equals(persisted.address())
			&& caller.computerId().filter(computerId::equals).isPresent();
	}

	private static ProductionDomainProof.World<HistoricalProductionHost,ProductionComputer.TopologyState> proofWorld(WorldAccess world) {
		return new ProductionDomainProof.World<>() {
			public boolean isChunkLoaded(ChunkPos chunk) { return world.isChunkLoaded(chunk); }
			public boolean isLoaded(BlockPos pos) { return world.isLoaded(pos); }
			public boolean sameSpace(BlockPos seed,BlockPos pos) { return world.sameSpace(seed,pos); }
			public ProductionLayout.Address address(BlockPos pos) {
				return world.address(pos);
			}
			public HistoricalProductionHost loadedComputer(BlockPos pos) { return physicalComputerAt(world, pos); }
			public ProductionComputer.TopologyState state(HistoricalProductionHost computer) { return computer.production().topologyState(); }
			public boolean matches(HistoricalProductionHost computer,ProductionComputer.TopologyState state) { return computer.production().topologyMatches(state); }
		};
	}

    private static HistoricalProductionHost physicalComputerAt(WorldAccess world, BlockPos position) {
        var application = world.loadedComputer(position);
        return application == null ? null : application.owner();
    }
    private static List<HistoricalProductionHost> physicalComputers(ResolvedSnapshot resolved) {
        return resolved.computers().stream().map(ProductionComputer::owner).toList();
    }
    private static Map<UUID, HistoricalProductionHost> physicalById(ResolvedSnapshot resolved) {
        var result = new LinkedHashMap<UUID, HistoricalProductionHost>();
        resolved.byId().forEach((id, application) -> result.put(id, application.owner()));
        return result;
    }

	private static List<BlockPos> positions(List<ProductionLayout.Bounds> bounds, List<ProductionLayout.Address> addresses) {
		return ProductionDomainProof.positions(new ProductionDomainProof.Domain(
			bounds,
			addresses));
	}

	private static EnumSet<EpochFault> unionFaults(ResolvedSnapshot resolved, UUID epochId) {
		EnumSet<EpochFault> union = EnumSet.noneOf(EpochFault.class);
		for (ProductionComputer computer : resolved.computers())
			if (computer.epoch().isPresent()
				&& computer.epoch().orElseThrow().epochId().equals(epochId))
				union.addAll(computer.latchedEpochFaults());
		return union;
	}

	private static boolean revalidateResolved(BlockPos seed, ProductionStructure snapshot,
		ResolvedSnapshot original, ProductionComputer caller, WorldAccess world,
		ComputerStructureScanner.Limits limits, ProductionEpoch expectedEpoch,
		Set<EpochFault> faultUnion) {
		ComputerStructureScanner.ScanResult rescanned = world.scan(seed, limits);
		UUID structureId = original.states().stream()
			.map(ProductionComputer.TopologyState::record).filter(Objects::nonNull)
			.map(ProductionStructure.Revision::structureId).findFirst().orElse(null);
		if ((rescanned.state() != ComputerStructureScanner.State.VALID
			&& rescanned.state() != ComputerStructureScanner.State.VALID_NOT_READY)
			|| rescanned.structure() == null || !snapshot.equals(rescanned.structure())
			|| structureId == null || !structureId.equals(rescanned.observedStructureMemberId()))
			return false;
		ResolvedSnapshot current = resolveSnapshot(seed, snapshot, world);
		if (current.failure() != ResolveFailure.NONE
			|| current.computers().size() != original.computers().size()) return false;
		UUID callerId = caller.computerId().orElse(null);
		if (callerId == null || (current.byId().get(callerId) == null || current.byId().get(callerId).owner() != caller.owner())) return false;
		for (int i = 0; i < original.computers().size(); i++) {
			ProductionComputer before = original.computers().get(i);
			ProductionComputer now = current.computers().get(i);
			if (before.owner() != now.owner() || !before.topologyMatches(original.states().get(i))) return false;
			ProductionEpoch epoch = before.epoch().orElse(null);
			if (!expectedEpoch.equals(epoch)) return false;
			if (!faultUnion.containsAll(before.latchedEpochFaults())) return false;
		}
		return unionFaults(current, expectedEpoch.epochId()).equals(faultUnion);
	}

	private record Reconciliation(ProductionStructure.Revision record, @Nullable ClusterBinding binding,
		@Nullable ProductionEpoch epoch, Set<EpochFault> faults, ComputerAvailabilityReason reason,
		@Nullable ComputerAvailabilityReason failureReason) {
		static Reconciliation failure(ComputerAvailabilityReason reason) {
			return new Reconciliation(null, null, null, Set.of(), reason, reason);
		}
	}

	private record ResolvedSnapshot(List<ProductionComputer> computers,
		Map<UUID, ProductionComputer> byId, List<ProductionComputer.TopologyState> states,
		WorldAccess world, ResolveFailure failure) {
		static ResolvedSnapshot failure(WorldAccess world, ResolveFailure failure) {
			return new ResolvedSnapshot(List.of(), Map.of(), List.of(), world, failure);
		}
	}

	private enum ResolveFailure { NONE, PARTIAL, SPACE, IDENTITY }
	private enum ProofFailure { NONE, PARTIAL, SPACE }
	private static final class RealWorldAccess implements WorldAccess {
		private final ServerLevel level;
		private RealWorldAccess(ServerLevel level) { this.level = level; }

		@Override
		public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			return ComputerStructureScanner.scan(new ComputerWorldView(level, seed), seed, limits);
		}
		@Override public boolean isLoaded(BlockPos pos) {
			return CBMultiBlockLifecycle.isLoaded(level, pos);
		}
		@Override public boolean isChunkLoaded(ChunkPos chunk) {
			return level.hasChunk(chunk.x, chunk.z);
		}
		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) {
			return SubLevelCompat.sameSpace(level, origin, pos);
		}
		@Override public @Nullable ProductionComputer loadedComputer(BlockPos pos) {
			BlockEntity blockEntity = SubLevelCompat.getLoadedBlockEntity(level, pos);
			return blockEntity instanceof HistoricalProductionHost computer ? computer.production() : null;
		}
		@Override public ProductionLayout.Address address(BlockPos pos) { var address = SpaceAddress.capture(level, pos); return new ProductionLayout.Address(address.dimension(), address.subLevelId(), address.localPos()); }
		@Override public UUID newStructureMemberId() { return UUID.randomUUID(); }
		@Override public ClusterBindingService.BindingAccess bindingAccess(
			ComputerCoordinatorMember member) {
			return ClusterBindingService.bindingAccess(level.getServer(), member);
		}
		@Override public boolean enforceFoundationPublication() { return true; }
		@Override public @Nullable ProductionComputer resolveComputer(ProductionLayout.Address address) {
			BlockEntity blockEntity = new SpaceAddress(address.dimension(), address.subLevelId(), address.localPos()).resolveBlockEntity(level.getServer());
			return blockEntity instanceof HistoricalProductionHost computer ? computer.production() : null;
		}
	}
}
