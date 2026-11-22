package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResidentCapture;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipeLibraryDisplayTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        assertNotNull(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE.getKey(VillagerType.SWAMP));
    }

    @Test void physicalCapturePreservesResidentAndPartialDiagnosticsWithinHistoricalWireBounds() {
        var resident = resident();
        var small = new LibraryStructureScanner.Snapshot(List.of(BlockPos.ZERO, BlockPos.ZERO.above(), BlockPos.ZERO.east()),
            List.of(BlockPos.ZERO.above()), List.of(BlockPos.ZERO.east()), BlockPos.ZERO, new BlockPos(1, 1, 0));
        var ordinary = RecipeLibraryClientView.capture(resident, LibraryStructureScanner.State.PARTIAL, true, small, 64, 2);
        assertEquals(new RecipeLibraryClientView(true, resident.type(), 4, resident.customName(),
            LibraryStructureScanner.State.PARTIAL, true, 3, 1, 1, 64, 2), ordinary);
        assertEquals(resident, ordinary.residentView());

        var many = java.util.stream.IntStream.range(0, 1100).mapToObj(i -> new BlockPos(i, 0, 0)).toList();
        var large = new LibraryStructureScanner.Snapshot(many, many, many, BlockPos.ZERO, new BlockPos(1099, 0, 0));
        var bounded = RecipeLibraryClientView.capture(resident, LibraryStructureScanner.State.TOO_LARGE, false,
            large, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1024, bounded.logicalMembers()); assertEquals(1024, bounded.ordinaryBookshelves());
        assertEquals(1024, bounded.chiseledBookshelves()); assertEquals(10000, bounded.searchBudget());
        assertEquals(1024, bounded.queueCount()); assertEquals(1100, large.members().size());
        assertEquals(bounded, RecipeLibraryClientView.load(bounded.save()).orElseThrow());
        var empty = RecipeLibraryClientView.EMPTY;
        assertEquals(empty, RecipeLibraryClientView.capture(empty.residentView(), LibraryStructureScanner.State.UNFORMED,
            false, null, 0, 1));
        assertFalse(empty.residentView().valid());
        assertThrows(IllegalArgumentException.class, () -> RecipeLibraryClientView.capture(resident,
            LibraryStructureScanner.State.VALID, false, null, -1, 1));
    }

    @Test void hostsOwnIndependentViewsWithoutAliasingPacketInputOrOutput() {
        var a = new RecipeLibraryDisplay(Set.of(), Set.of());
        var b = new RecipeLibraryDisplay(Set.of(), Set.of());
        var identity = UUID.randomUUID(); var expected = view();
        var input = packet(identity, expected);
        assertEquals(Optional.of(identity), a.readPacket(input));
        input.getCompound(RecipeLibraryDisplay.VIEW).putInt("LibrarianLevel", 1);
        a.view().save().putString("CustomName", "not the accepted value");
        assertEquals(expected, a.view()); assertEquals(RecipeLibraryClientView.EMPTY, b.view());
        var before = a.view();
        a.replace(RecipeLibraryClientView.EMPTY); assertEquals(RecipeLibraryClientView.EMPTY, a.view());
        a.replace(before); assertEquals(expected, a.view());
        assertThrows(NullPointerException.class, () -> a.replace(null)); assertEquals(expected, a.view());
    }

    @Test void rejectedEnvelopeIdentityAndProjectionLeaveTheAcceptedViewUntouched() {
        var display = new RecipeLibraryDisplay(Set.of(), Set.of());
        var id = UUID.randomUUID(); var expected = view();
        assertEquals(Optional.of(id), display.readPacket(packet(id, expected)));
        var wrongId = packet(id, expected); wrongId.putString(RecipeLibraryDisplay.ID, id.toString());
        var shortId = packet(id, expected); shortId.putIntArray(RecipeLibraryDisplay.ID, new int[] {1, 2, 3});
        var wrongType = packet(id, expected); wrongType.putString(RecipeLibraryDisplay.VIEW, "not a view");
        var badView = packet(id, expected); badView.getCompound(RecipeLibraryDisplay.VIEW).putInt("QueueCount", -1);
        var wrongBoolean = packet(id, expected); wrongBoolean.getCompound(RecipeLibraryDisplay.VIEW).putByte("RenderLibrarian", (byte) 2);
        var wrongState = packet(id, expected); wrongState.getCompound(RecipeLibraryDisplay.VIEW).putString("StructureState", "UNKNOWN");
        var extra = packet(id, expected); extra.putString("PatternData", "private server data");
        for (var rejected : Arrays.asList(null, new CompoundTag(), wrongId, shortId, wrongType, badView, wrongBoolean, wrongState, extra)) {
            assertTrue(display.readPacket(rejected).isEmpty()); assertEquals(expected, display.view());
        }
    }

    @Test void extensionDeclarationIsDefensivelyCopiedAndDecodingRemainsTheHostsResponsibility() {
        var required = new HashSet<>(Set.of("Status")); var optional = new HashSet<>(Set.of("Target"));
        var display = new RecipeLibraryDisplay(required, optional);
        required.clear(); optional.add("Unexpected");
        var id = UUID.randomUUID(); var input = packet(id, view());
        assertTrue(display.readPacket(input).isEmpty());
        input.putString("Status", "host decodes this");
        assertEquals(Optional.of(id), display.readPacket(input));
        input.putString("Target", "host decodes this too");
        assertEquals(Optional.of(id), display.readPacket(input));
        input.putString("Unexpected", "not in the constructed schema");
        assertTrue(display.readPacket(input).isEmpty()); assertEquals(view(), display.view());
        for (String reserved : List.of(RecipeLibraryDisplay.ID, RecipeLibraryDisplay.VIEW, " ")) {
            assertThrows(IllegalArgumentException.class, () -> new RecipeLibraryDisplay(Set.of(reserved), Set.of()));
            assertThrows(IllegalArgumentException.class, () -> new RecipeLibraryDisplay(Set.of(), Set.of(reserved)));
        }
        assertThrows(IllegalArgumentException.class, () -> new RecipeLibraryDisplay(Set.of("Status"), Set.of("Status")));
    }

    @Test void freshWriteDropsAuthoritativeRootsAndStaleExtensionsWithoutReplacingAcceptedView() {
        var display = new RecipeLibraryDisplay(Set.of("Status"), Set.of("Target"));
        var identity = UUID.randomUUID(); var current = view();
        var target = new CompoundTag(); target.putString("PatternData", "authoritative archive");
        target.putString("Status", "stale status"); target.putString("Target", "stale target");
        display.writeFresh(target, identity, current);
        assertEquals(Set.of(RecipeLibraryDisplay.ID, RecipeLibraryDisplay.VIEW), target.getAllKeys());
        assertEquals(identity, target.getUUID(RecipeLibraryDisplay.ID));
        assertEquals(current, RecipeLibraryClientView.load(target.getCompound(RecipeLibraryDisplay.VIEW)).orElseThrow());
        assertEquals(RecipeLibraryClientView.EMPTY, display.view());
        assertTrue(display.readPacket(target).isEmpty());
        target.put("Status", new CompoundTag());
        assertEquals(Optional.of(identity), display.readPacket(target)); assertEquals(current, display.view());
    }

    private static LibraryResidentCapture.Projection resident() {
        return new LibraryResidentCapture.Projection(true, ResourceLocation.withDefaultNamespace("swamp"), 4, "Curator");
    }
    private static RecipeLibraryClientView view() {
        return RecipeLibraryClientView.capture(resident(), LibraryStructureScanner.State.VALID, false, null, 64, 2);
    }
    private static CompoundTag packet(UUID identity, RecipeLibraryClientView view) {
        var packet = new CompoundTag(); packet.putUUID(RecipeLibraryDisplay.ID, identity);
        packet.put(RecipeLibraryDisplay.VIEW, view.save()); return packet;
    }
}
