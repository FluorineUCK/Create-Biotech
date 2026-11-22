package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionEpochTest {
    private static final UUID GROUP = new UUID(1,1), STRUCTURE = new UUID(1,2), SPACE = new UUID(1,3);
    private static final UUID A = new UUID(0,1), B = new UUID(0,2), PANEL = new UUID(0,3);
    private static final ProductionResourceProfile LIBRARIAN = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN,1,0).orElseThrow();
    private static final ProductionResourceProfile FLETCHER = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER,2,0).orElseThrow();

    @Test void blankDimensionsCannotBecomeAValidCommonSpaceOnRestore() {
        var saved = ProductionEpochCodec.save(ProductionEpoch.freeze(GROUP,STRUCTURE,layout(0)));
        saved.getCompound("CoordinatorAddress").putString("Dimension", "");
        for (var raw : saved.getList("Nodes",Tag.TAG_COMPOUND))
            ((net.minecraft.nbt.CompoundTag)raw).getCompound("Address").putString("Dimension", "");
        for (var raw : saved.getList("Panels",Tag.TAG_COMPOUND))
            ((net.minecraft.nbt.CompoundTag)raw).getCompound("Address").putString("Dimension", "");
        assertTrue(ProductionEpochCodec.load(saved).isEmpty());
    }

    @Test void relocationKeepsDistinctFrozenProfilesAndDoesNotAdoptPendingMembers() {
        var original = ProductionEpoch.freeze(GROUP,STRUCTURE,layout(0));
        var shifted = layout(17); var members = new ArrayList<>(shifted.members());
        members.add(new Member(new UUID(0,9),address(18,2,2),null));
        var moved = original.relocate(new ProductionLayout(shifted.bounds(),members,shifted.panels()));
        assertEquals(original.epochId(),moved.epochId()); assertEquals(GROUP,moved.groupId());
        assertEquals(STRUCTURE,moved.structureId()); assertEquals(A,moved.coordinatorId());
        assertEquals(List.of(A,B),moved.layout().members().stream().map(Member::computerId).toList());
        assertEquals(List.of(LIBRARIAN,FLETCHER),moved.layout().members().stream().map(Member::profile).toList());
        assertEquals(address(18,1,1),moved.coordinatorAddress());
        assertEquals(layout(0),original.layout()); assertEquals(shifted,moved.layout());
    }

    @Test void layoutCopiesMutableInputsWithoutHoldingWorldObjects() {
        var position = new BlockPos.MutableBlockPos(1,1,1);
        var members = new ArrayList<>(layout(0).members());
        members.set(0,new Member(A,new Address(Level.OVERWORLD,SPACE,position),LIBRARIAN));
        var panels = new ArrayList<>(layout(0).panels());
        var original = new ProductionLayout(layout(0).bounds(),members,panels);
        position.set(99,99,99); members.clear(); panels.clear();
        assertEquals(layout(0),original);
        assertThrows(UnsupportedOperationException.class,() -> original.members().clear());
        assertThrows(UnsupportedOperationException.class,() -> original.panels().clear());
    }

    @Test void changedFrozenProfileSpaceOrPanelCannotRelocate() {
        var original = ProductionEpoch.freeze(GROUP,STRUCTURE,layout(0));
        var base = layout(0); var first = base.members().getFirst(); var second = base.members().get(1);
        var changedProfile = new ProductionLayout(base.bounds(),List.of(first,new Member(B,second.address(),LIBRARIAN)),base.panels());
        var otherSpace = new ProductionLayout(base.bounds(),base.members().stream().map(m -> new Member(m.computerId(),
            new Address(Level.NETHER,null,m.address().localPos()),m.profile())).toList(),base.panels().stream().map(p ->
            new Panel(p.panelId(),new Address(Level.NETHER,null,p.address().localPos()),p.facing(),p.baseMaterial())).toList());
        var wrongFace = new ProductionLayout(base.bounds(),base.members(),List.of(new Panel(PANEL,address(0,1,1),Direction.EAST,ComputerShellMaterial.TRANSPARENT)));
        var wrongMaterial = new ProductionLayout(base.bounds(),base.members(),List.of(new Panel(PANEL,address(0,1,1),Direction.WEST,ComputerShellMaterial.OPAQUE)));
        var missing = new ProductionLayout(base.bounds(),List.of(first),base.panels());
        for (var changed : List.of(changedProfile,otherSpace,wrongFace,wrongMaterial,missing))
            assertThrows(IllegalArgumentException.class,() -> original.relocate(changed));
    }

    @Test void unprofiledObservedMemberCannotBecomeAFrozenReservation() {
        var base = layout(0); var members = new ArrayList<>(base.members());
        members.set(1,new Member(B,members.get(1).address(),null));
        var pending = new ProductionLayout(base.bounds(),members,base.panels());
        assertThrows(IllegalArgumentException.class,() -> ProductionEpoch.freeze(GROUP,STRUCTURE,pending));
    }

    @Test void historicalV2FixtureRoundTripsWithoutRenamingOrAddingFields() throws Exception {
        // Literal historical representation, independent of the writer under test.
        var historical = net.minecraft.nbt.TagParser.parseTag("""
            {Version:2,EpochId:[I;0,2,0,3],ClusterId:[I;0,1,0,1],StructureMemberId:[I;0,1,0,2],CoordinatorId:[I;0,0,0,1],
             CoordinatorAddress:{Dimension:"minecraft:overworld",SubLevel:[I;0,1,0,3],Pos:274877911041L},
             Bounds:{MinX:0,MinY:0,MinZ:0,MaxX:3,MaxY:4,MaxZ:5},
             Nodes:[{ComputerId:[I;0,0,0,1],Address:{Dimension:"minecraft:overworld",SubLevel:[I;0,1,0,3],Pos:274877911041L},
                     Profile:{Version:1,Profession:"LIBRARIAN",ProcessSlots:4,Capabilities:[],ListenerRangeBonus:0}}],
             Panels:[{PanelId:[I;0,0,0,3],Address:{Dimension:"minecraft:overworld",SubLevel:[I;0,1,0,3],Pos:4097L},Facing:"west",BaseMaterial:"transparent"}]}
            """);
        var decoded = ProductionEpochCodec.load(historical).orElseThrow();
        assertEquals(address(1,1,1),decoded.coordinatorAddress());
        assertEquals(LIBRARIAN,decoded.layout().members().getFirst().profile());
        assertEquals(historical,ProductionEpochCodec.save(decoded));
        historical.getCompound("Bounds").putInt("MinX",999);
        assertEquals(0,decoded.layout().bounds().minX());
    }

    @Test void codecRejectsReversedBoundsAndExactTypeViolations() {
        var original = ProductionEpochCodec.save(ProductionEpoch.freeze(GROUP,STRUCTURE,layout(0)));
        var reversed = original.copy(); reversed.getCompound("Bounds").putInt("MinX",3); reversed.getCompound("Bounds").putInt("MaxX",0);
        var numericCoercion = original.copy(); numericCoercion.getCompound("CoordinatorAddress").putInt("Pos",0);
        var extra = original.copy(); extra.getList("Panels",Tag.TAG_COMPOUND).getCompound(0).putInt("Extra",1);
        for (var malformed : List.of(reversed,numericCoercion,extra)) assertTrue(ProductionEpochCodec.load(malformed).isEmpty());
    }
    private static ProductionLayout layout(int x) {
        return new ProductionLayout(new Bounds(x,0,0,x+3,4,5), List.of(
            new Member(A, address(x+1,1,1),LIBRARIAN), new Member(B,address(x+2,1,1),FLETCHER)),
            List.of(new Panel(PANEL,address(x,1,1),Direction.WEST,ComputerShellMaterial.TRANSPARENT)));
    }
    private static Address address(int x, int y, int z) { return new Address(Level.OVERWORLD,SPACE,new BlockPos(x,y,z)); }
}
