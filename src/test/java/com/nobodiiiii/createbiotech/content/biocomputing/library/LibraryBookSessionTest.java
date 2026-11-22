package com.nobodiiiii.createbiotech.content.biocomputing.library;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.*;

class LibraryBookSessionTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    @Test void addressProjectionUsesObservedPhysicalSelectionWithoutRereadingOrMutatingBooks() {
        var view = new LibraryBookAccessTest.View(); var session = session(view); session.refresh(); session.select(0);
        long revision = session.revision(); var before = view.book.copy();
        var address = new DataValue.RecordValue(Map.of("track", new LibraryTrackAddress(2, 0, 1).value(),
            "slot", new DataValue.IntegerValue(0)));
        var state = session.snapshot(null, handle -> {
            assertEquals(net.minecraft.core.BlockPos.ZERO, handle.shelf()); return address;
        });
        assertEquals(new DataValue.ListValue(List.of(address)), state.field("addresses"));
        assertEquals(revision, session.revision()); assertEquals(0, view.writes);
        assertTrue(net.minecraft.world.item.ItemStack.matches(before, view.book));
        session.refresh(); assertEquals(new DataValue.IntegerValue(-1), session.snapshot(null).field("selected"));
    }
    @Test void eventRefreshInvalidatesOldSelectionAndStaleWrites() {
        var view = new LibraryBookAccessTest.View();
        var session = session(view); session.refresh(); session.select(0);
        long old = session.revision();
        session.refresh();
        assertEquals("changed", session.mutate(old, false, "x", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
        assertEquals(0, view.writes);
        assertEquals("missing_medium", session.mutate(session.revision(), false, "x", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
    }
    @Test void oneSelectedBookWritesThenRefreshesItsSnapshot() {
        var view = new LibraryBookAccessTest.View(); var session = session(view);
        session.refresh(); session.select(0);
        assertEquals("ok", session.mutate(session.revision(), false, "x",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(42))));
        assertEquals(1, view.writes);
        assertEquals(new DataValue.IntegerValue(42), session.read("x").value());
        assertEquals("ok", session.mutate(session.revision(), true, "x", null));
        assertEquals(2, view.writes);
    }
    @Test void changedSlotCannotBeReadOrWrittenUntilExplicitlyReselected() {
        var view = new LibraryBookAccessTest.View(); var session = session(view);
        session.refresh(); session.select(0); view.book = net.minecraft.world.item.ItemStack.EMPTY;
        assertEquals("changed", assertThrows(LibraryBookAccess.Failure.class, () -> session.read("x")).code());
        assertEquals("changed", session.mutate(session.revision(), false, "x", new LibraryEntry(LibraryEntry.Kind.DATA, DataValue.NullValue.INSTANCE)));
        assertEquals(0, view.writes);
    }
    static LibraryBookSession session(LibraryBookAccessTest.View view) {
        return new LibraryBookSession(new LibraryBookAccess(view,
            new LibraryBookMedia(LibraryBookMediaTest.COMPONENT, LibraryBookMediaTest.LIMITS), LibraryBookVolumesTest.VOLUMES));
    }
}
