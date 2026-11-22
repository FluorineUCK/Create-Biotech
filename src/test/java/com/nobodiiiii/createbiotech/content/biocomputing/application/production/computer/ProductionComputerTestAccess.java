package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionComputerTopology;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import java.util.*;
public final class ProductionComputerTestAccess {
    private ProductionComputerTestAccess() {}
    public static void applyTopologyState(ProductionComputer c, ProductionStructure.Revision r, ClusterBinding b, boolean valid, ProductionEpoch e, Set<EpochFault> f) { c.applyTopologyState(r,b,valid,e,f); }
    public static void stageTopologyState(ProductionComputer c, ProductionStructure.Revision r, ClusterBinding b, boolean valid, ProductionEpoch e, Set<EpochFault> f) { c.stageTopologyState(r,b,valid,e,f); }
    /** Test-only identity observation: never route the assertion through the legacy binding projection. */
    public static ProductionComputerTopology.State<?> authoritativeTopology(ProductionComputer computer) {
        try {
            var field = ProductionComputer.class.getDeclaredField("productionTopology");
            field.setAccessible(true);
            return ProductionComputerTopology.class.cast(field.get(computer)).state();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
    public static TopologySnapshot topologyState(ProductionComputer c) { return new TopologySnapshot(c.topologyState()); }
    public record TopologySnapshot(ProductionComputer.TopologyState value) {
        public long version() { return value.version(); }
        public ProductionStructure.Revision record() { return value.record(); }
        public ClusterBinding binding() { return value.binding(); }
        public ProductionEpoch epoch() { return value.epoch(); }
        public Set<EpochFault> faults() { return value.faults(); }
    }
    public static boolean topologyDirty(ProductionComputer c) { return c.topologyDirty(); }
    public static Optional<ProductionStructure.Revision> currentStructureRecord(ProductionComputer c) { return c.currentStructureRecord(); }
    public static ClusterBinding bindingState(ProductionComputer c) { return c.bindingState(); }
    public static void stagePreparedBinding(ProductionComputer c, ClusterBinding b) { c.stagePreparedBinding(b); }
    public static void topologyDependencyChanged(ProductionComputer c) { c.topologyDependencyChanged(); }
    public static boolean persistenceAvailable(ProductionComputer c) { return c.persistenceAvailable(); }
    public static boolean bindingStateValid(ProductionComputer c) { return c.bindingStateValid(); }
    public static int prepareOrchestrationCapture(ProductionComputer c, UUID epoch, int nodes) { return c.prepareOrchestrationCapture(epoch,nodes); }
}
