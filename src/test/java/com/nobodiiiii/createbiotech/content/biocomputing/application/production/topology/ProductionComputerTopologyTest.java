package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionComputerTopologyTest {
    private static final UUID A=id(1), B=id(2), PENDING=id(3), STRUCTURE=id(4), GROUP=id(5);
    private static final Member FIRST = new Member(A,address(1,1,1),
        ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN,1,0).orElseThrow());
    private static final Member SECOND = new Member(B,address(2,1,1),
        ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER,2,0).orElseThrow());

    @Test void replacingBindingKeepsCapturedStateAndDistinctFrozenProfiles() {
        var owner = new ProductionComputerTopology<String>(); var base = fixture();
        var faults = new HashSet<>(Set.of("WIDTH"));
        owner.replace(new ProductionComputerTopology.State<>(base.structure(),base.binding(),true,base.epoch(),faults));
        var captured = owner.state(); faults.clear();
        var changed = new ProductionBinding(GROUP,9,base.binding().authority(),List.of(id(20)));
        owner.replaceBinding(changed);
        assertEquals(Set.of("WIDTH"),captured.faults());
        assertThrows(UnsupportedOperationException.class,() -> captured.faults().clear());
        assertEquals(base.binding(),captured.binding()); assertEquals(changed,owner.state().binding());
        assertEquals(captured.structure(),owner.state().structure()); assertEquals(captured.epoch(),owner.state().epoch());
        assertEquals(List.of(FIRST,SECOND),owner.state().epoch().layout().members());
        assertNotEquals(FIRST.profile(),SECOND.profile());
    }

    @Test void crossChildMismatchesRemainAvailableForDiagnosisButGrantNoProductionValidity() {
        var owner = new ProductionComputerTopology<String>(); var base = fixture();
        var foreignRecord = new ProductionStructure.Revision(id(99),0,A,base.structure().structure());
        var wrongCoordinator = new ProductionStructure.Revision(STRUCTURE,0,B,base.structure().structure());
        var wrongAuthority = new ProductionBinding(GROUP,0,new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR,id(99)),List.of());
        for (var invalid : List.of(
            new ProductionComputerTopology.State<>(base.structure(),base.binding(),false,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(null,base.binding(),true,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(foreignRecord,base.binding(),true,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(wrongCoordinator,base.binding(),true,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(base.structure(),wrongAuthority,true,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(base.structure(),new ProductionBinding(id(99),0,null,List.of()),true,base.epoch(),Set.<String>of()),
            new ProductionComputerTopology.State<>(base.structure(),base.binding(),true,null,Set.of("WIDTH")))) {
            owner.replace(invalid);
            assertSame(invalid,owner.state(), "Keep the candidate evidence, do not normalize it away");
            assertFalse(owner.validFor(A));
        }
        owner.replace(base); assertTrue(owner.validFor(A)); assertFalse(owner.validFor(null)); assertFalse(owner.validFor(id(99)));
        // Historical consistency permits no binding; actual transaction admission checks authorization separately.
        owner.replace(new ProductionComputerTopology.State<>(base.structure(),null,true,base.epoch(),Set.of()));
        assertTrue(owner.validFor(A));
    }

    @Test void pendingMemberInstallationDoesNotChangeFrozenProfilesOrRequireItsOwnProfile() {
        var base = fixture(); var owner = new ProductionComputerTopology<String>();
        var withPending = structure(List.of(FIRST,SECOND,new Member(PENDING,address(1,2,1),null)));
        owner.replace(new ProductionComputerTopology.State<>(new ProductionStructure.Revision(STRUCTURE,1,A,withPending),
            base.binding(),true,base.epoch(),Set.of()));
        assertTrue(owner.validFor(PENDING)); assertTrue(owner.allowsResidentInstallation(PENDING));
        assertFalse(owner.allowsResidentInstallation(A)); assertFalse(owner.allowsResidentInstallation(B));
        assertFalse(owner.allowsResidentInstallation(id(99)));
        assertEquals(List.of(FIRST,SECOND),owner.state().epoch().layout().members());
    }

    @Test void absentCoordinatorIsConsistentOnlyWhileTheSeparateFrozenEpochRetainsIt() {
        var owner = new ProductionComputerTopology<String>(); var base = fixture();
        var absent = new ProductionStructure.Revision(STRUCTURE,1,A,structure(List.of(SECOND)));
        owner.replace(new ProductionComputerTopology.State<>(absent,base.binding(),true,base.epoch(),Set.of()));
        assertTrue(owner.validFor(B)); assertFalse(owner.allowsResidentInstallation(B));
        owner.replace(new ProductionComputerTopology.State<>(absent,base.binding(),true,null,Set.of()));
        assertFalse(owner.validFor(B));
    }

    @Test void publicationInvalidationIsPreparedWithoutChangingTheOwnedState() {
        var owner = new ProductionComputerTopology<String>(); var base = fixture(); owner.replace(base);
        assertFalse(owner.invalidatesPublication(base,A));
        assertFalse(owner.invalidatesPublication(new ProductionComputerTopology.State<>(base.structure(),base.binding(),true,base.epoch(),Set.of("WIDTH")),A));
        assertTrue(owner.invalidatesPublication(base,B));
        assertTrue(owner.invalidatesPublication(ProductionComputerTopology.State.empty(true),A));
        var nextBinding = new ProductionBinding(GROUP,9,base.binding().authority(),List.of());
        assertTrue(owner.invalidatesPublication(new ProductionComputerTopology.State<>(base.structure(),nextBinding,true,base.epoch(),Set.of()),A));
        assertSame(base,owner.state());
    }

    @Test void invalidCandidatesCannotPartiallyMutateCurrentValues() {
        var owner = new ProductionComputerTopology<String>(); var base = fixture(); owner.replace(base);
        var badFaults = new HashSet<String>(); badFaults.add(null);
        assertThrows(NullPointerException.class,() -> owner.replace(new ProductionComputerTopology.State<>(null,null,false,null,badFaults)));
        assertThrows(NullPointerException.class,() -> owner.replaceBinding(null));
        assertThrows(NullPointerException.class,() -> owner.replace(null));
        assertSame(base,owner.state());
    }

    @Test void separateOwnersNeverShareMutableAttachmentsAndKeepUnformedSemantics() {
        var first = new ProductionComputerTopology<String>(); var second = new ProductionComputerTopology<String>();
        assertTrue(first.validFor(A)); assertTrue(first.allowsResidentInstallation(null));
        var base = fixture(); first.replace(base); second.replace(base);
        first.replaceBinding(new ProductionBinding(GROUP,7,null,List.of(id(20))));
        assertSame(base,second.state()); assertEquals(base.binding(),second.state().binding());
        first.replace(ProductionComputerTopology.State.empty(false));
        assertFalse(first.validFor(A)); assertTrue(second.validFor(B));
    }

    private static ProductionComputerTopology.State<String> fixture() {
        var structure = structure(List.of(FIRST,SECOND));
        var record = new ProductionStructure.Revision(STRUCTURE,0,A,structure);
        var binding = new ProductionBinding(GROUP,0,new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR,STRUCTURE),List.of());
        return new ProductionComputerTopology.State<>(record,binding,true,ProductionEpoch.freeze(GROUP,STRUCTURE,structure.layout()),Set.of());
    }
    private static ProductionStructure structure(List<Member> members) {
        var bounds = new Bounds(0,0,0,3,3,3); var casing = new HashSet<BlockPos>();
        for (int x=0;x<4;x++) for (int y=0;y<4;y++) for (int z=0;z<4;z++)
            if (x==0 || x==3 || y==0 || y==3 || z==0 || z==3) casing.add(new BlockPos(x,y,z));
        return new ProductionStructure(new ProductionLayout(bounds,members,List.of()),casing,ProductionStructure.chunksFor(bounds));
    }
    private static Address address(int x,int y,int z) { return new Address(Level.OVERWORLD,null,new BlockPos(x,y,z)); }
    private static UUID id(int value) { return new UUID(0,value); }
}
