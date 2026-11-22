package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionStructureTest {
    @Test void literalHistoricalRevisionKeepsMixedProfilesAndAnUnprofiledMember() throws Exception {
        var historical = fixture();
        var loaded = ProductionStructureCodec.loadRevision(historical).orElseThrow();
        var members = loaded.structure().layout().members();
        assertEquals(4,members.get(0).profile().processSlots());
        assertEquals(3,members.get(1).profile().processSlots());
        assertNull(members.get(2).profile());
        assertEquals(historical,ProductionStructureCodec.saveRevision(loaded));
        historical.getCompound("Snapshot").getCompound("Bounds").putInt("MaxX",99);
        historical.getCompound("Snapshot").getLongArray("CasingPositions")[0]=999;
        assertEquals(2,loaded.structure().layout().bounds().maxX());
        assertEquals(26,loaded.structure().casingPositions().size());
        assertTrue(loaded.structure().casingPositions().contains(BlockPos.ZERO));
    }

    @Test void geometryCopiesMutableCasingAndChunkCollections() throws Exception {
        var original = structure(); var casing = new LinkedHashSet<>(original.casingPositions());
        casing.remove(BlockPos.ZERO); var mutable = new BlockPos.MutableBlockPos(0,0,0); casing.add(mutable);
        var chunks = new LinkedHashSet<>(original.containingChunks());
        var copied = new ProductionStructure(original.layout(),casing,chunks);
        mutable.set(9,9,9); casing.clear(); chunks.clear();
        assertEquals(original,copied);
        assertThrows(UnsupportedOperationException.class,() -> copied.casingPositions().clear());
        assertThrows(UnsupportedOperationException.class,() -> copied.containingChunks().clear());
    }

    @Test void onlyOuterShellIsRequiredButMissingFacesAndWrongChunksAreRejected() throws Exception {
        var original = structure();
        assertFalse(original.casingPositions().contains(new BlockPos(1,1,1)));
        var withInternal = new LinkedHashSet<>(original.casingPositions()); withInternal.add(new BlockPos(1,1,1));
        assertEquals(27,new ProductionStructure(original.layout(),withInternal,original.containingChunks()).casingPositions().size());
        var missing = new LinkedHashSet<>(original.casingPositions()); missing.remove(new BlockPos(0,0,1));
        assertThrows(IllegalArgumentException.class,() -> new ProductionStructure(original.layout(),missing,original.containingChunks()));
        assertThrows(IllegalArgumentException.class,() -> new ProductionStructure(original.layout(),original.casingPositions(),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> new ProductionStructure(original.layout(),original.casingPositions(),Set.of(new ChunkPos(1,0))));
    }

    @Test void structurePanelsMustBeOnTheShellAndUuidSorted() throws Exception {
        var original = structure(); var base = original.layout(); var panel = base.panels().getFirst();
        var internal = new Panel(panel.panelId(),new Address(Level.OVERWORLD,null,new BlockPos(1,1,1)),panel.facing(),panel.baseMaterial());
        var lowerId = new Panel(new UUID(0,4),new Address(Level.OVERWORLD,null,new BlockPos(2,1,0)),panel.facing(),panel.baseMaterial());
        for (var panels : List.of(List.of(internal),List.of(panel,lowerId))) {
            var layout = new ProductionLayout(base.bounds(),base.members(),panels);
            assertThrows(IllegalArgumentException.class,() -> new ProductionStructure(layout,original.casingPositions(),original.containingChunks()));
        }
    }

    @Test void malformedListsAndAddressesCannotBeNormalizedToAnEmptyStructure() throws Exception {
        var snapshot = fixture().getCompound("Snapshot");
        var wrongList = snapshot.copy(); var strings = new ListTag(); strings.add(StringTag.valueOf("not a panel")); wrongList.put("Panels",strings);
        var extra = snapshot.copy(); extra.getList("Panels",Tag.TAG_COMPOUND).getCompound(0).getCompound("Address").putInt("Extra",1);
        var missingProfile = snapshot.copy(); missingProfile.getList("Nodes",Tag.TAG_COMPOUND).getCompound(0).putString("Profile","invalid");
        var reversed = snapshot.copy(); reversed.getCompound("Bounds").putInt("MinX",2); reversed.getCompound("Bounds").putInt("MaxX",0);
        var nonCanonicalChunks = snapshot.copy(); nonCanonicalChunks.putLongArray("ContainingChunks",new long[]{0,0});
        for (var tag : List.of(wrongList,extra,missingProfile,reversed,nonCanonicalChunks))
            assertTrue(ProductionStructureCodec.loadSnapshot(tag).isEmpty());
    }

    @Test void revisionRetainsIdentityAndSaturatesWithoutInventingACoordinatorMember() throws Exception {
        var original = structure(); var id = new UUID(1,2); var absentCoordinator = new UUID(9,9);
        var record = new ProductionStructure.Revision(id,Long.MAX_VALUE-1,new UUID(0,1),original);
        var next = record.revise(absentCoordinator,original).orElseThrow();
        assertEquals(id,next.structureId()); assertEquals(absentCoordinator,next.coordinatorId());
        assertEquals(Long.MAX_VALUE,next.revision()); assertTrue(next.revise(absentCoordinator,original).isEmpty());
        assertEquals(next,ProductionStructureCodec.loadRevision(ProductionStructureCodec.saveRevision(next)).orElseThrow());
        var wrongType = ProductionStructureCodec.saveRevision(record); wrongType.putInt("Revision",7);
        assertTrue(ProductionStructureCodec.loadRevision(wrongType).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> new ProductionStructure.Revision(id,-1,absentCoordinator,original));
    }

    @Test void boundedGeometryIterationDoesNotWrapMaximumAbsoluteCoordinate() {
        int min = Integer.MAX_VALUE-2;
        var bounds = new Bounds(min,0,0,Integer.MAX_VALUE,2,2);
        var layout = new ProductionLayout(bounds,List.of(new Member(new UUID(0,1),
            new Address(Level.OVERWORLD,null,new BlockPos(min+1,1,1)),null)),List.of());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),() ->
            assertEquals(26,new ProductionStructure(layout,shell(min),ProductionStructure.chunksFor(bounds)).casingPositions().size()));
    }

    private static ProductionStructure structure() throws Exception {
        return ProductionStructureCodec.loadRevision(fixture()).orElseThrow().structure();
    }
    private static CompoundTag fixture() throws Exception {
        // Independent historical field layout; no call to the writer under test.
        var tag = TagParser.parseTag("""
            {Version:1,StructureMemberId:[I;0,1,0,2],Revision:7L,CoordinatorId:[I;0,0,0,1],Snapshot:{Version:2,
              Bounds:{MinX:0,MinY:0,MinZ:0,MaxX:2,MaxY:2,MaxZ:2},
              Nodes:[{ComputerId:[I;0,0,0,1],Address:{Dimension:"minecraft:overworld",Pos:4097L},
                       Profile:{Version:1,Profession:"LIBRARIAN",ProcessSlots:4,Capabilities:[],ListenerRangeBonus:0}},
                     {ComputerId:[I;0,0,0,2],Address:{Dimension:"minecraft:overworld",Pos:549755817985L},
                       Profile:{Version:1,Profession:"FLETCHER",ProcessSlots:3,Capabilities:[],ListenerRangeBonus:0}},
                     {ComputerId:[I;0,0,0,3],Address:{Dimension:"minecraft:overworld",Pos:274877911041L}}],
              Panels:[{PanelId:[I;0,0,0,9],Address:{Dimension:"minecraft:overworld",Pos:1L},Facing:"west",BaseMaterial:"opaque"}],
              ContainingChunks:[L;0L]}}
            """);
        tag.getCompound("Snapshot").putLongArray("CasingPositions",shell(0).stream().mapToLong(BlockPos::asLong).sorted().toArray());
        return tag;
    }
    private static Set<BlockPos> shell(int minX) {
        var result = new LinkedHashSet<BlockPos>();
        for (int x=0;x<3;x++) for (int y=0;y<3;y++) for (int z=0;z<3;z++)
            if (x==0 || x==2 || y==0 || y==2 || z==0 || z==2) result.add(new BlockPos(minX+x,y,z));
        return result;
    }
}
