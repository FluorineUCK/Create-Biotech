package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionTopologyReconcilerTest {
    private static final UUID A = new UUID(0,2), B = new UUID(0,3), JOINER = new UUID(0,1);
    private static final UUID STRUCTURE = new UUID(1,1), GROUP = new UUID(1,2);
    private static final ProductionResourceProfile LIBRARIAN = profile(ProductionProfession.LIBRARIAN,3);
    private static final ProductionResourceProfile FLETCHER = profile(ProductionProfession.FLETCHER,2);
    private static final ProductionResourceProfile JUNIOR = profile(ProductionProfession.LIBRARIAN,1);
    private enum Fault { WIDTH, DEPTH }

    @Test void lowerIdJoinerDoesNotReplaceHeterogeneousFrozenMembersOrTheirCoordinator() {
        var old = structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var epoch = ProductionEpoch.freeze(GROUP,STRUCTURE,old.layout());
        var record = new ProductionStructure.Revision(STRUCTURE,7,A,old);
        var binding = new ProductionBinding(GROUP,4,null,List.of());
        var current = structure(member(JOINER,1,JUNIOR),member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var result = reconcile(current,true,STRUCTURE,List.of(
            fresh(JOINER),replica(A,record,binding,epoch),replica(B,record,binding,epoch)),noEvidenceNeeded());

        assertTrue(result.accepted()); assertEquals(Status.NONE,result.status());
        assertEquals(epoch,result.epoch()); assertEquals(A,result.record().coordinatorId());
        assertEquals(8,result.record().revision()); assertEquals(current,result.record().structure());
        assertEquals(List.of(LIBRARIAN,FLETCHER),result.epoch().layout().members().stream().map(Member::profile).toList());
        assertEquals(List.of(JUNIOR,LIBRARIAN,FLETCHER),result.record().structure().layout().members().stream().map(Member::profile).toList());
        assertEquals(binding,result.binding()); assertTrue(result.faults().isEmpty());
    }

    @Test void firstFormationKeepsEmptyProfileAndUsesOnlyTheProvidedIdentity() {
        var current=structure(member(A,0,LIBRARIAN),member(B,2,null));
        var evidence=new Evidence() {
            public UUID newStructureId() { return STRUCTURE; }
            public Proof proveReelection(ProductionStructure.Revision old, ProductionStructure now, UUID id) {
                throw new AssertionError("Initial formation has no previous coordinator");
            }
        };
        var decision=reconcile(current,false,null,List.of(fresh(A),fresh(B)),evidence);
        assertTrue(decision.accepted()); assertEquals(Status.NOT_READY,decision.status());
        assertEquals(0,decision.record().revision()); assertEquals(STRUCTURE,decision.record().structureId());
        assertNull(decision.record().structure().layout().members().get(1).profile()); assertNull(decision.epoch());
        assertFailure(Status.IDENTITY_CONFLICT,reconcile(current,false,GROUP,List.of(fresh(A),fresh(B)),evidence));
    }

    @Test void equalRevisionDivergenceAndNewerReplicasNeverReplaceTheAuthority() {
        var current=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var record=new ProductionStructure.Revision(STRUCTURE,7,A,current);
        var binding=new ProductionBinding(GROUP,4,null,List.of());
        var casing=new LinkedHashSet<>(current.casingPositions()); casing.add(new BlockPos(1,1,1));
        var different=new ProductionStructure(current.layout(),casing,current.containingChunks());
        for (var competing : List.of(new ProductionStructure.Revision(STRUCTURE,7,A,different),
            new ProductionStructure.Revision(STRUCTURE,8,A,current),new ProductionStructure.Revision(GROUP,7,A,current))) {
            assertFailure(Status.IDENTITY_CONFLICT,reconcile(current,true,STRUCTURE,List.of(
                replica(A,record,binding,null),replica(B,competing,binding,null)),noEvidenceNeeded()));
        }
        for (var competing : List.of(new ProductionBinding(GROUP,5,null,List.of()),
            new ProductionBinding(GROUP,4,null,List.of(STRUCTURE)),new ProductionBinding(STRUCTURE,4,null,List.of()))) {
            assertFailure(Status.IDENTITY_CONFLICT,reconcile(current,true,STRUCTURE,List.of(
                replica(A,record,binding,null),replica(B,record,competing,null)),noEvidenceNeeded()));
        }
        assertTrue(reconcile(current,true,STRUCTURE,List.of(replica(A,record,binding,null),
            replica(B,new ProductionStructure.Revision(STRUCTURE,6,A,current),new ProductionBinding(GROUP,3,null,List.of()),null)),
            noEvidenceNeeded()).accepted());
    }

    @Test void missingFrozenMemberIsExplicitButChangedProfileAndMissingCoordinatorAreRejected() {
        var old=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var record=new ProductionStructure.Revision(STRUCTURE,7,A,old);
        var epoch=ProductionEpoch.freeze(GROUP,STRUCTURE,old.layout());
        var binding=new ProductionBinding(GROUP,4,null,List.of());
        var missing=reconcile(structure(member(A,0,LIBRARIAN)),true,STRUCTURE,List.of(replica(A,record,binding,epoch)),noEvidenceNeeded());
        assertTrue(missing.accepted()); assertEquals(Status.FROZEN_MEMBER_MISSING,missing.status());
        assertEquals(epoch,missing.epoch()); assertEquals(A,missing.record().coordinatorId());
        assertFailure(Status.PERSISTENCE_INVALID,reconcile(structure(member(A,0,LIBRARIAN),member(B,2,JUNIOR)),true,
            STRUCTURE,List.of(replica(A,record,binding,epoch),replica(B,record,binding,epoch)),noEvidenceNeeded()));
        assertFailure(Status.COORDINATOR_MISSING,reconcile(structure(member(B,2,FLETCHER)),true,
            STRUCTURE,List.of(replica(B,record,binding,epoch)),noEvidenceNeeded()));
        var emptyJoiner=structure(member(JOINER,1,null),member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var pending=reconcile(emptyJoiner,false,STRUCTURE,List.of(fresh(JOINER),replica(A,record,binding,epoch),
            replica(B,record,binding,epoch)),noEvidenceNeeded());
        assertTrue(pending.accepted()); assertEquals(Status.NONE,pending.status()); assertEquals(epoch,pending.epoch());
    }

    @Test void inactiveReelectionRequiresExplicitLoadedAndIdentityEvidence() {
        var old=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var record=new ProductionStructure.Revision(STRUCTURE,7,A,old);
        var current=structure(member(B,2,FLETCHER));
        var binding=new ProductionBinding(GROUP,4,null,List.of());
        for (var proof : Proof.values()) {
            int[] calls={0};
            var evidence=new Evidence() {
                public UUID newStructureId() { throw new AssertionError("Retain structure identity"); }
                public Proof proveReelection(ProductionStructure.Revision before, ProductionStructure now, UUID id) {
                    calls[0]++; assertEquals(record,before); assertEquals(current,now); assertEquals(B,id); return proof;
                }
            };
            var decision=reconcile(current,true,STRUCTURE,List.of(replica(B,record,binding,null)),evidence);
            assertEquals(1,calls[0]); assertEquals(7,record.revision());
            if (proof==Proof.READY) {
                assertTrue(decision.accepted()); assertEquals(8,decision.record().revision());
                assertEquals(B,decision.record().coordinatorId()); assertEquals(binding,decision.binding());
            } else assertFailure(proof==Proof.PARTIAL_UNLOADED ? Status.PARTIAL_UNLOADED : Status.IDENTITY_CONFLICT,decision);
        }
    }

    @Test void faultUnionUsesIndependentValueCopiesAndRejectsOrphansAndDivergentEpochs() {
        var current=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var record=new ProductionStructure.Revision(STRUCTURE,7,A,current);
        var binding=new ProductionBinding(GROUP,4,null,List.of());
        var epoch=ProductionEpoch.freeze(GROUP,STRUCTURE,current.layout());
        var source=EnumSet.of(Fault.WIDTH);
        var a=new Replica<>(A,true,true,record,binding,epoch,source); source.clear();
        var b=new Replica<>(B,true,true,record,binding,epoch,Set.of(Fault.DEPTH));
        var result=reconcile(current,true,STRUCTURE,List.of(a,b),noEvidenceNeeded());
        assertTrue(result.accepted()); assertEquals(Set.of(Fault.WIDTH,Fault.DEPTH),result.faults());
        assertEquals(Set.of(Fault.WIDTH),a.faults());
        assertThrows(UnsupportedOperationException.class,() -> result.faults().clear());
        assertFailure(Status.PERSISTENCE_INVALID,reconcile(current,true,STRUCTURE,List.of(
            new Replica<>(A,true,true,record,binding,null,Set.of(Fault.WIDTH)),replica(B,record,binding,null)),noEvidenceNeeded()));
        assertFailure(Status.PERSISTENCE_INVALID,reconcile(current,true,STRUCTURE,List.of(a,
            replica(B,record,binding,ProductionEpoch.freeze(GROUP,STRUCTURE,current.layout()))),noEvidenceNeeded()));
    }

    @Test void saturatedRevisionCanBeReusedButCannotAdmitANewMember() {
        var current=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var record=new ProductionStructure.Revision(STRUCTURE,Long.MAX_VALUE,A,current);
        var binding=new ProductionBinding(GROUP,4,null,List.of());
        var epoch=ProductionEpoch.freeze(GROUP,STRUCTURE,current.layout());
        var stable=List.of(replica(A,record,binding,epoch),replica(B,record,binding,epoch));
        var same=reconcile(current,true,STRUCTURE,stable,noEvidenceNeeded());
        assertTrue(same.accepted()); assertEquals(record,same.record());
        var joined=structure(member(JOINER,1,JUNIOR),member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        var replicas=new ArrayList<>(stable); replicas.add(fresh(JOINER));
        assertFailure(Status.PERSISTENCE_INVALID,reconcile(joined,true,STRUCTURE,replicas,noEvidenceNeeded()));
        assertEquals(Long.MAX_VALUE,record.revision()); assertEquals(2,record.structure().layout().members().size());
    }

    @Test void incompleteDuplicateAndUnavailableReplicaCapturesCannotAuthorizeADecision() {
        var current=structure(member(A,0,LIBRARIAN),member(B,2,FLETCHER));
        for (var invalid : List.of(List.of(fresh(A)),List.of(fresh(A),fresh(A)),List.of(fresh(A),fresh(JOINER)),
            List.of(fresh(A),new Replica<Fault>(B,false,true,null,null,null,Set.of())),
            List.of(fresh(A),new Replica<Fault>(B,true,false,null,null,null,Set.of()))))
            assertFailure(Status.IDENTITY_CONFLICT,reconcile(current,true,null,invalid,noEvidenceNeeded()));
    }

    private static void assertFailure(Status status, Decision<?> decision) {
        assertFalse(decision.accepted()); assertEquals(status,decision.status());
        assertNull(decision.record()); assertNull(decision.binding()); assertNull(decision.epoch()); assertTrue(decision.faults().isEmpty());
    }
    private static Replica<Fault> fresh(UUID id) { return replica(id,null,null,null); }
    private static Replica<Fault> replica(UUID id, ProductionStructure.Revision record, ProductionBinding binding, ProductionEpoch epoch) {
        return new Replica<>(id,true,true,record,binding,epoch,Set.of());
    }
    private static Evidence noEvidenceNeeded() {
        return new Evidence() {
            public UUID newStructureId() { throw new AssertionError("Existing structures retain their identity"); }
            public Proof proveReelection(ProductionStructure.Revision old, ProductionStructure current, UUID coordinator) {
                throw new AssertionError("An active epoch cannot reelect its coordinator");
            }
        };
    }
    private static ProductionResourceProfile profile(ProductionProfession profession, int level) {
        return ProductionResourceProfile.forProfession(profession,level,0).orElseThrow();
    }
    private static Member member(UUID id, int x, ProductionResourceProfile profile) {
        return new Member(id,new Address(Level.OVERWORLD,null,new BlockPos(x,1,1)),profile);
    }
    private static ProductionStructure structure(Member... members) {
        var bounds = new Bounds(0,0,0,2,2,2);
        var casing = new LinkedHashSet<BlockPos>();
        for (int x=0;x<3;x++) for (int y=0;y<3;y++) for (int z=0;z<3;z++)
            if (x==0 || x==2 || y==0 || y==2 || z==0 || z==2) casing.add(new BlockPos(x,y,z));
        return new ProductionStructure(new ProductionLayout(bounds,List.of(members),List.of()),casing,ProductionStructure.chunksFor(bounds));
    }
}
