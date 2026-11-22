package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.component.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import org.junit.jupiter.api.*;

class LibraryBookMediaTest {
    static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 100000);
    static final DataComponentType<CustomData> COMPONENT = DataComponentType.<CustomData>builder().persistent(CustomData.CODEC).build();
    final LibraryBookMedia media = new LibraryBookMedia(COMPONENT, LIMITS);

    @BeforeAll static void bootstrap() throws Exception {
        var type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    @Test void componentMutationPreservesPagesOtherComponentsAndSource() {
        var book = book();
        var before = book.copy();
        var entry = new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.RecordValue(Map.of(
            "count", new DataValue.IntegerValue(42), "name", new DataValue.TextValue("温度"))));
        var written = media.put(book, "reading", entry);
        assertTrue(ItemStack.matches(book, before));
        assertNull(book.get(COMPONENT));
        assertEquals(before.get(DataComponents.WRITABLE_BOOK_CONTENT), written.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertEquals(before.get(DataComponents.CUSTOM_DATA), written.get(DataComponents.CUSTOM_DATA));
        assertEquals(entry, media.read(written).get("reading"));
        assertEquals(Map.of(), media.read(book));
        assertThrows(UnsupportedOperationException.class, () -> media.read(written).clear());
    }
    @Test void everyValueKindSurvivesTheComponentCodec() {
        var value = new DataValue.ListValue(List.of(new DataValue.BooleanValue(true), new DataValue.IntegerValue(Long.MAX_VALUE),
            new DataValue.DecimalValue(0.25), new DataValue.TextValue("\uD800"), DataValue.NullValue.INSTANCE,
            new DataValue.ErrorValue("missing", "field"), new DataValue.MapValue(Map.of("x", new DataValue.IntegerValue(1)))));
        var written = media.put(book(), "values", new LibraryEntry(LibraryEntry.Kind.DATA, value));
        var encoded = CustomData.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, written.get(COMPONENT)).getOrThrow();
        var loaded = book(); loaded.set(COMPONENT, CustomData.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow());
        assertEquals(value, media.read(loaded).get("values").value());
    }
    @Test void replacingDeletingAndMovingOwnOnlyTheDedicatedRegion() {
        var first = media.put(book(), "a", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(1)));
        var moved = first.copy();
        var second = media.put(first, "a", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(2)));
        assertEquals(new DataValue.IntegerValue(1), media.read(moved).get("a").value());
        var deleted = media.delete(second, "a");
        assertTrue(media.read(deleted).isEmpty());
        assertEquals(first.get(DataComponents.WRITABLE_BOOK_CONTENT), deleted.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertThrows(IllegalArgumentException.class, () -> media.delete(deleted, "a"));
    }
    @Test void invalidOrUnknownComponentsAreNotEmptyBooksAndAreNeverOverwritten() {
        var book = book(); var tag = new net.minecraft.nbt.CompoundTag(); tag.putInt("Version", 999);
        book.set(COMPONENT, CustomData.of(tag)); var before = book.copy();
        assertThrows(ArchiveException.class, () -> media.read(book));
        assertThrows(ArchiveException.class, () -> media.put(book, "a", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
        assertTrue(ItemStack.matches(before, book));
    }
    @Test void rejectsNonBooksBlankNamesAndCapacityWithoutSourceMutation() {
        assertThrows(IllegalArgumentException.class, () -> media.read(new ItemStack(Items.PAPER)));
        assertThrows(IllegalArgumentException.class, () -> media.put(book(), " ", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
        var book = book(); var before = book.copy();
        var small = new LibraryBookMedia(COMPONENT, new ArchiveLimits(10, 1, 4));
        assertThrows(ArchiveException.class, () -> small.put(book, "a", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("full"))));
        assertTrue(ItemStack.matches(before, book));
    }
    @Test void completeProgramAndPinnedSubgraphKeepLayoutsWithoutInstalling() {
        var key = new ModuleDefinition.Key("leaf", 7);
        var graph = new ComputationGraph(Map.of(), Map.of("n", new ComputationGraph.Constant(new DataValue.IntegerValue(11))), Map.of("result", "n"));
        var module = new ModuleDefinition(key, graph);
        var subgraph = new SubgraphDocument(key, Map.of(key, module),
            Map.of(ProgramDocument.graphId(key), Map.of("n", new ProgramDocument.Position(20, 30))));
        var program = new ProgramDocument(new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
            Map.of("root", Map.of("n", new ProgramDocument.Position(40, 50))));
        var written = media.put(book(), "program", new LibraryEntry(LibraryEntry.Kind.PROGRAM, ProgramDocumentCodec.encode(program)));
        written = media.put(written, "graph", new LibraryEntry(LibraryEntry.Kind.SUBGRAPH, SubgraphDocumentCodec.encode(subgraph)));
        assertEquals(program, ProgramDocumentCodec.decode(media.read(written).get("program").value()));
        assertEquals(subgraph, SubgraphDocumentCodec.decode(media.read(written).get("graph").value()));
    }
    @Test void documentKindsCannotSmuggleIncompleteOrUnversionedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryEntry(LibraryEntry.Kind.PROGRAM, DataValue.NullValue.INSTANCE));
        assertThrows(IllegalArgumentException.class, () -> new LibraryEntry(LibraryEntry.Kind.SUBGRAPH, new DataValue.RecordValue(Map.of())));
    }
    @Test void wholeBookByteCapacityAppliesToAccumulatedEntriesAndDoesNotPartiallyWrite() {
        var bounded = new LibraryBookMedia(COMPONENT, LIMITS, 1800);
        var first = bounded.put(book(), "a", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("x".repeat(250))));
        var before = first.copy();
        assertThrows(ArchiveException.class, () -> bounded.put(first, "b",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.TextValue("x".repeat(500)))));
        assertTrue(ItemStack.matches(before, first));
        assertEquals(Set.of("a"), bounded.read(first).keySet());
    }
    static ItemStack book() {
        var book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
            net.minecraft.server.network.Filterable.passThrough("original text"),
            net.minecraft.server.network.Filterable.passThrough("{\"version\":2,\"type\":\"standard\"}"))));
        var custom = new net.minecraft.nbt.CompoundTag(); custom.putString("unrelated", "keep");
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(custom)); return book;
    }
}
