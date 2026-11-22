package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionComputerTopology;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/** Temporary physical/registration adapter; production coordination state and decisions belong to the application. */
public final class ComputerCoordinatorMember implements ClusterMember {
    private final ProductionComputer owner;
    private final ProductionCoordinator<ProductionComputer> coordination;

    ComputerCoordinatorMember(ProductionComputer owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        coordination = new ProductionCoordinator<>(owner, new ProductionCoordinator.Access<>() {
            @Override public ProductionCoordinator.MemberState capture(ProductionComputer host) {
                var state = host.topologyState();
                return new ProductionCoordinator.MemberState(state.computerId(), state.profile(), state.persistenceAvailable(),
                    new ProductionComputerTopology.State<>(state.record(),
                        state.binding() == null ? null : state.binding().productionBinding(), state.bindingValid(),
                        state.epoch(), state.faults()));
            }
            @Override public ProductionCoordinator<ProductionComputer> coordinator(ProductionComputer host) {
                return host.coordinatorMember.coordination;
            }
            @Override public ProductionLayout.Address address(ProductionComputer host) {
                var address = host.coordinatorMemberAddress();
                return new ProductionLayout.Address(address.dimension(), address.subLevelId(), address.localPos());
            }
            @Override public boolean authoritativeLoaded(ProductionComputer host) { return host.isAuthoritativeLoadedInstance(); }
            @Override public void register(ProductionComputer host) {
                var server = host.coordinatorServer();
                if (server != null) ClusterMemberIndex.register(server, host.coordinatorMember);
            }
            @Override public void unregister(ProductionComputer host) {
                var server = host.coordinatorServer();
                if (server != null) ClusterMemberIndex.unregister(server, host.coordinatorMember);
            }
            @Override public void dependencyChanged(ProductionComputer host) { host.topologyDependencyChanged(); }
            @Override public void stageBinding(List<ProductionComputer> hosts, ProductionBinding binding) {
                if (hosts.isEmpty()) return;
                var legacy = ClusterBinding.fromProduction(binding);
                hosts.forEach(host -> host.stagePreparedBinding(legacy));
            }
            @Override public void publishBinding(ProductionComputer host) { host.publishPreparedBinding(); }
            @Override public void indexedMutation(Runnable mutation) {
                var server = owner.coordinatorServer();
                if (server == null) mutation.run();
                else ClusterMemberIndex.rebind(server, ComputerCoordinatorMember.this, mutation);
            }
        });
    }

    @Override public UUID memberId() { return owner.computerStructureMemberId().orElseThrow(); }
    public UUID coordinatorComputerId() { return owner.computerId().orElseThrow(); }
    /** Exact formed-epoch evidence consumed by server-authoritative production commands. */
    public boolean hasExactEpoch(UUID clusterId, UUID epochId) { return owner.hasExactEpoch(clusterId, epochId); }
    public boolean coordinatorOnline() { return owner.coordinatorOnline(); }
    public void topologyDependencyChanged() { owner.topologyDependencyChanged(); }
    public void primaryFrequencyDependencyChanged() { coordination.refreshLoadedReplicas(); }
    @Override public @Nullable ClusterBinding bindingState() { return owner.bindingState(); }
    @Override public boolean hasValidBindingState() { return owner.bindingStateValid(); }
    @Override public ClusterMemberType memberType() { return ClusterMemberType.COMPUTER_COORDINATOR; }
    @Override public SpaceAddress memberAddress() { return owner.coordinatorMemberAddress(); }
    @Override public boolean canRebind() { return coordination.canRebind(); }
    @Override public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) { return prepare(proposed, false); }
    @Override public ClusterBindingPreparation prepareLogisticsBinding(ClusterBinding proposed) { return prepare(proposed, true); }
    private ClusterBindingPreparation prepare(@Nullable ClusterBinding proposed, boolean logisticsOnly) {
        return ClusterBindingPreparation.valueOf(coordination.prepare(proposed == null ? null : proposed.productionBinding(), logisticsOnly).name());
    }
    /** Consume the exact accepted state; the existing transaction's commit contract does not rescan or revalidate. */
    @Override public void commitClusterBinding(ClusterBinding prepared) { coordination.commit(); }
    void publish(List<ProductionComputer> exactReplicas, Set<UUID> requiredIds) { coordination.publish(exactReplicas, requiredIds); }
    void withdraw() { coordination.withdraw(); }
    void invalidatePublication() { coordination.invalidatePublication(); }
    boolean isPublished() { return coordination.isPublished(); }
    boolean isAttachedTo(ComputerCoordinatorMember publication) { return coordination.isAttachedTo(publication.coordination); }
    boolean updateBindingAccess(ClusterBindingService.BindingAccess access) {
        return coordination.updateAccess(ProductionCoordinator.Availability.valueOf(Objects.requireNonNull(access).name()));
    }
}
