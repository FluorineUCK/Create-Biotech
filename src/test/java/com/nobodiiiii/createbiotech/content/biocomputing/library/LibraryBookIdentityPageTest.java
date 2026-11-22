package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import org.junit.jupiter.api.*;

class LibraryBookIdentityPageTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    final LibraryBookMedia media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
    LibraryBookAccess access(LibraryBookAccessTest.View view) { return new LibraryBookAccess(view, media, LibraryBookVolumesTest.VOLUMES); }
    String identity(ItemStack book) { return ((DataValue.TextValue) media.revision(book).field("medium")).value(); }

    @Test void identityIsNotABrokenRecipeOrAFreeRecipePage() {
        var view = new LibraryBookAccessTest.View(); view.book = new ItemStack(Items.WRITABLE_BOOK);
        var access = access(view); access.renameVolume(access.list().getFirst(), "日志");
        var text = view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw();
        var registries = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var source = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey(
            new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation(
                net.minecraft.world.level.Level.OVERWORLD, null, net.minecraft.core.BlockPos.ZERO), 0, 0);
        var parser = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser(registries);
        assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult.Blank.class, parser.parse(source, text));
        assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult.Invalid.class, parser.parse(source, text + "\n{broken recipe}"));
        assertEquals("conflict", com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeBookPages
            .createPageJson(view.book, source, "{}", registries).resultKey(), "Metadata is not a writable empty recipe slot");
    }

    @Test void signedBookShowsIdentityWhilePreservingTitleAuthorGenerationAndFilteredPages() {
        var view = new LibraryBookAccessTest.View(); view.book = new ItemStack(Items.WRITTEN_BOOK);
        var original = new WrittenBookContent(new Filterable<>("original title", Optional.of("filtered title")), "author", 2,
            List.of(new Filterable<>(Component.literal("original text"), Optional.of(Component.literal("filtered text")))), true);
        view.book.set(DataComponents.WRITTEN_BOOK_CONTENT, original); var before = view.book.copy();
        var access = access(view); var handle = access.renameVolume(access.list().getFirst(), "日志");
        var content = view.book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        assertEquals(2, content.pages().size());
        assertEquals(original.pages(), content.pages().subList(0, 1));
        assertEquals(original.title(), content.title()); assertEquals(original.author(), content.author());
        assertEquals(original.generation(), content.generation()); assertEquals(original.resolved(), content.resolved());
        assertTrue(content.pages().getLast().raw().getString().contains(identity(view.book)));
        assertNull(view.book.get(DataComponents.WRITABLE_BOOK_CONTENT));
        access.renameVolume(handle, "日志");
        assertEquals(content, view.book.get(DataComponents.WRITTEN_BOOK_CONTENT));
        assertEquals(original, before.get(DataComponents.WRITTEN_BOOK_CONTENT));
    }

    @Test void existingMediumIsOnlyDescribedOnExplicitEditWithoutChangingDataOrVersion() {
        var view = new LibraryBookAccessTest.View();
        view.book = media.put(new ItemStack(Items.WRITABLE_BOOK), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(42)));
        var before = view.book.copy(); var access = access(view); var handle = access.list().getFirst();
        access.read(handle); access.stat(handle, "reading"); access.revision(handle);
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes);
        access.renameVolume(handle, "日志");
        assertEquals(before.get(LibraryBookMediaTest.COMPONENT), view.book.get(LibraryBookMediaTest.COMPONENT));
        assertEquals(1, view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size());
        assertTrue(view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw().contains(identity(before)));
    }

    @Test void blankPageIsUsedButFilteredTextAndOtherPagesAreNotOverwritten() {
        var view = new LibraryBookAccessTest.View();
        var hidden = new Filterable<>("", Optional.of("preserve filtered text"));
        var original = List.of(hidden, Filterable.passThrough(" "), Filterable.passThrough("recipe text"));
        view.book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(original));
        var access = access(view); access.renameVolume(access.list().getFirst(), "日志");
        var pages = view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages();
        assertEquals(3, pages.size()); assertEquals(hidden, pages.getFirst()); assertEquals(original.getLast(), pages.getLast());
        assertTrue(pages.get(1).raw().contains(identity(view.book)));
    }

    @Test void fullWritableBookRefusesWholeEditWithoutPartialDataOrNameChanges() {
        var view = new LibraryBookAccessTest.View();
        view.book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(Collections.nCopies(WritableBookContent.MAX_PAGES, Filterable.passThrough("keep"))));
        var before = view.book.copy(); var access = access(view); var handle = access.list().getFirst();
        assertEquals("identity_page_full", assertThrows(LibraryBookAccess.Failure.class, () -> access.renameVolume(handle, "日志")).code());
        assertEquals("identity_page_full", assertThrows(LibraryBookAccess.Failure.class, () -> access.put(handle, "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(42)))).code());
        assertEquals(0, view.writes); assertTrue(ItemStack.matches(before, view.book));
    }

    @Test void copiedIdentityTextDoesNotClaimTheOtherBooksDataOrIdentity() {
        var source = media.put(new ItemStack(Items.WRITABLE_BOOK), "private", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(7)));
        source = media.describe(source);
        var view = new LibraryBookAccessTest.View(); view.book = new ItemStack(Items.WRITABLE_BOOK);
        view.book.set(DataComponents.WRITABLE_BOOK_CONTENT, source.get(DataComponents.WRITABLE_BOOK_CONTENT));
        assertTrue(media.read(view.book).isEmpty());
        var access = access(view); access.renameVolume(access.list().getFirst(), "copy");
        assertNotEquals(identity(source), identity(view.book)); assertTrue(media.read(view.book).isEmpty());
        assertEquals(source.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst(), view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst());
        assertTrue(view.book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getLast().raw().contains(identity(view.book)));
    }

    @Test void rejectedDataCapacityDoesNotLeaveAnIdentityPageBehind() {
        var view = new LibraryBookAccessTest.View(); var before = view.book.copy();
        var bounded = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS, 1);
        var access = new LibraryBookAccess(view, bounded, LibraryBookVolumesTest.VOLUMES);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> access.put(access.list().getFirst(), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(42))));
        assertTrue(ItemStack.matches(before, view.book)); assertEquals(0, view.writes);
    }

    static void assertOriginalPagesPreserved(ItemStack before, ItemStack after) {
        var original = before.get(DataComponents.WRITABLE_BOOK_CONTENT).pages();
        var current = after.get(DataComponents.WRITABLE_BOOK_CONTENT).pages();
        assertEquals(original.size() + 1, current.size()); assertEquals(original, current.subList(0, original.size()));
        assertTrue(LibraryBookIdentityPage.isIdentityPage(current.getLast().raw()));
    }
}
