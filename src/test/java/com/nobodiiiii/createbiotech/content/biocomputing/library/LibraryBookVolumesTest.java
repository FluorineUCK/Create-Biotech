package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import net.minecraft.core.component.*;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;

class LibraryBookVolumesTest {
    static final DataComponentType<String> COMPONENT = DataComponentType.<String>builder().persistent(Codec.STRING).build();
    static final LibraryBookVolumes VOLUMES = new LibraryBookVolumes(COMPONENT);
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    @Test void legacyBooksRemainUnnamedAndNamingPreservesEveryOtherComponent() {
        var media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
        var book = media.put(LibraryBookMediaTest.book(), "reading", new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(43)));
        var before = book.copy();
        assertTrue(VOLUMES.name(book).isEmpty());
        var named = VOLUMES.rename(book, "日志");
        assertTrue(ItemStack.matches(before, book)); assertEquals("日志", VOLUMES.name(named).orElseThrow());
        named.remove(COMPONENT); assertTrue(ItemStack.matches(before, named));
    }
    @Test void nameIsExactAndOrdinaryCrudPreservesIt() {
        var media = new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS);
        var named = VOLUMES.rename(LibraryBookMediaTest.book(), " Log/A ");
        var changed = media.put(named, "reading", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE));
        assertEquals(" Log/A ", VOLUMES.name(changed).orElseThrow());
        assertEquals(" Log/A ", VOLUMES.name(media.delete(changed, "reading")).orElseThrow());
        assertEquals(" Log/A ", VOLUMES.name(named.copy()).orElseThrow());
    }
    @Test void rejectsBlankOversizedAndUnsupportedMediaWithoutMutation() {
        var book = LibraryBookMediaTest.book(); var before = book.copy();
        for (String invalid : new String[]{"", "  ", "a".repeat(129)})
            assertThrows(IllegalArgumentException.class, () -> VOLUMES.rename(book, invalid));
        assertTrue(ItemStack.matches(before, book));
        assertThrows(IllegalArgumentException.class, () -> VOLUMES.rename(new ItemStack(Items.PAPER), "paper"));
        assertEquals("a".repeat(128), VOLUMES.name(VOLUMES.rename(book, "a".repeat(128))).orElseThrow());
    }
    @Test void malformedNameIsNotReportedAsAnUnnamedBook() {
        var book = LibraryBookMediaTest.book(); book.set(COMPONENT, " ");
        var before = book.copy();
        assertEquals("invalid_volume", assertThrows(LibraryBookAccess.Failure.class, () -> VOLUMES.name(book)).code());
        assertTrue(ItemStack.matches(before, book));
    }
}
