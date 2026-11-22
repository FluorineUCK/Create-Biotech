package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.HistoricalProductionHost;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionScanCohort;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Temporary stateless host/world ports. No scheduling state or scan/rebuild algorithm lives here. */
final class ProductionScanAccess {
    private ProductionScanAccess() {}
    private static final ProductionScanCohort.Hosts<HistoricalProductionHost> HOSTS = new ProductionScanCohort.Hosts<>() {
        public ProductionScanCohort.State<HistoricalProductionHost> state(HistoricalProductionHost host) { return host.production().productionScanState(); }
        public UUID id(HistoricalProductionHost host) { return host.computerId().orElse(null); }
        public BlockPos position(HistoricalProductionHost host) { return host.getBlockPos(); }
        public ProductionStructure.Revision revision(HistoricalProductionHost host) {
            return host.production().currentStructureRecord().orElse(null);
        }
    };

    static void tick(ProductionComputer caller, long now, ComputerTopologyController.WorldAccess world,
        ComputerStructureScanner.Limits limits) {
        java.util.Objects.requireNonNull(world); java.util.Objects.requireNonNull(limits);
        ProductionScanCohort.tick(caller.owner(), now, ProductionComputer.TOPOLOGY_SCAN_INTERVAL_TICKS, HOSTS,
            new ProductionScanCohort.World<HistoricalProductionHost, ComputerAvailabilityReason>() {
                public boolean isChunkLoaded(ChunkPos chunk) { return world.isChunkLoaded(chunk); }
                public boolean isLoaded(BlockPos pos) { return world.isLoaded(pos); }
                public boolean sameSpace(BlockPos origin, BlockPos pos) { return world.sameSpace(origin, pos); }
                public ProductionLayout.Address address(BlockPos pos) {
                    return world.address(pos);
                }
                public HistoricalProductionHost loadedComputer(BlockPos pos) { var application = world.loadedComputer(pos); return application == null ? null : application.owner(); }
                public ProductionScanCohort.Refresh<HistoricalProductionHost, ComputerAvailabilityReason> refresh(HistoricalProductionHost owner) {
                    var outcome = ComputerTopologyController.refresh(owner.production(), world, limits);
                    return new ProductionScanCohort.Refresh<>(outcome.members().stream().map(ProductionComputer::owner).toList(), outcome.committed(), outcome.reason());
                }
                public void withdrawPartial(List<HistoricalProductionHost> members) {
                    ComputerTopologyController.withdrawPersistedLeaseService(members.stream().map(HistoricalProductionHost::production).toList(), ComputerAvailabilityReason.PARTIAL_UNLOADED, world);
                }
                public void fanFailure(List<HistoricalProductionHost> members, ComputerAvailabilityReason reason) {
                    ComputerTopologyController.fanAvailability(members.stream().map(HistoricalProductionHost::production).toList(), reason);
                }
            });
    }
}
