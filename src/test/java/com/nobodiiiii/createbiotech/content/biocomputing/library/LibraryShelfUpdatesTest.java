package com.nobodiiiii.createbiotech.content.biocomputing.library;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LibraryShelfUpdatesTest {
    @Test void onlyObservedPositionsInvalidateAndRepeatedEventsCoalesce() {
        var watch = new LibraryShelfUpdates.Watch();
        var shelf = new BlockPos(1, 2, 3);
        watch.observe(Set.of(shelf));
        assertFalse(watch.consume());
        watch.changed(new BlockPos(30, 2, 3)); assertFalse(watch.consume());
        watch.changed(shelf); watch.changed(shelf);
        assertTrue(watch.consume()); assertFalse(watch.consume());
    }
    @Test void replacingTopologyDropsOldPositionsAndChunkLossInvalidates() {
        var watch = new LibraryShelfUpdates.Watch();
        var old = new BlockPos(1, 2, 3); var current = new BlockPos(33, 2, 3);
        watch.observe(Set.of(old)); watch.observe(Set.of(current));
        watch.changed(old); assertFalse(watch.consume());
        watch.chunkChanged(0, 0); assertFalse(watch.consume());
        watch.chunkChanged(2, 0); assertTrue(watch.consume());
    }
}
