package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Capture;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Domain;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Failure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import java.util.*;

/** Shared, call-scoped production world evidence. H/W are internal host and immutable version witnesses. */
public interface ProductionEpochAccess<H,W,F> {
    enum ResolveFailure { NONE, PARTIAL, SPACE, IDENTITY }
    record Resolved<H>(List<H> computers, Map<UUID,H> byId, ResolveFailure failure) {
        public Resolved { computers=List.copyOf(computers); byId=Map.copyOf(byId); Objects.requireNonNull(failure); }
    }
    W capture(H host);
    Replica<F> value(W witness);
    boolean exactCaller(H caller, W witness);
    Failure proveOld(H caller, ProductionStructure.Revision record, ProductionEpoch epoch);
    ProductionStructureScanner.ScanResult scan(H caller);
    Failure proveCurrent(H caller, ProductionStructure structure);
    Resolved<H> resolve(H caller, ProductionStructure structure);
    Capture<H,W> captureDomain(H caller, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current);
    boolean revalidateDomain(Capture<H,W> before, H caller, List<ProductionStructure.Revision> records,
        ProductionEpoch epoch, ProductionStructure current);
    /** Rechecks the original resolved hosts, their versions, the scan and the whole fault union. */
    boolean revalidateResolved(H caller, ProductionStructure current, ProductionEpoch epoch, Set<F> faults);

    static Domain oldDomain(ProductionStructure.Revision record, ProductionEpoch epoch) {
        return new Domain(List.of(record.structure().layout().bounds(),epoch.layout().bounds()),
            epoch.layout().members().stream().map(ProductionLayout.Member::address).toList());
    }
    static Domain currentDomain(ProductionStructure structure) {
        return new Domain(List.of(structure.layout().bounds()),List.of());
    }
    static Domain domain(List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) {
        var bounds=new ArrayList<ProductionLayout.Bounds>();
        records.forEach(record -> bounds.add(record.structure().layout().bounds()));
        bounds.add(epoch.layout().bounds()); bounds.add(current.layout().bounds());
        return new Domain(bounds,epoch.layout().members().stream().map(ProductionLayout.Member::address).toList());
    }
}
