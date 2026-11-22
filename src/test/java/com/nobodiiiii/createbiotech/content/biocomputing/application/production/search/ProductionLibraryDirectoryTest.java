package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionLibraryDirectoryTest {
    private static final UUID LOCAL = id(1), REMOTE = id(2);
    @Test void unavailableHighPriorityPeerRemainsKnownInsteadOfDisappearingFromSearchOrder() {
        var directory = new LibraryDirectory();
        var high = peer(8, 20, 1, 5, LibraryRegistration.LoadState.UNLOADED, LibraryRegistration.Reachability.REACHABLE, "A");
        var low = peer(9, 10, 1, 5, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE, "A");
        assertFalse(directory.hasRegistrations());
        assertTrue(directory.publish(low)); assertTrue(directory.publish(high));
        assertTrue(directory.hasRegistrations());
        assertEquals(List.of(high, low), directory.knownPeers());
        assertEquals(List.of(high, low), directory.visibleTo(LOCAL, CommunicationFrequency.of("A")));
        assertFalse(high.queryableBy(LOCAL, CommunicationFrequency.of("A")));
        assertTrue(low.queryableBy(LOCAL, CommunicationFrequency.of("A")));
        assertThrows(UnsupportedOperationException.class, () -> directory.knownPeers().clear());
    }

    static LibraryRegistration peer(long id, int priority, long formation, long generation,
            LibraryRegistration.LoadState loaded, LibraryRegistration.Reachability reachable, String frequency) {
        return new LibraryRegistration(id(id), REMOTE, new RecipeSourceLocation(Level.OVERWORLD, id(99), new BlockPos(4, 5, 6)),
            formation, CommunicationFrequency.of(frequency), priority, generation, loaded, reachable);
    }
    static UUID id(long value) { return new UUID(0, value); }

    @Test void formationPrecedesCatalogGenerationAndRejectedPublicationsDoNotAdvanceRevision() {
        var directory = new LibraryDirectory();
        var first = peer(8, 20, 2, 10, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE, "A");
        assertEquals(0, directory.publicationRevision(first.libraryId()));
        assertTrue(directory.find(first.libraryId()).isEmpty());
        assertTrue(directory.publish(first));
        long revision = directory.publicationRevision(first.libraryId());
        assertFalse(directory.publish(peer(8, 99, 1, Long.MAX_VALUE, first.loadState(), first.reachability(), "B")));
        assertFalse(directory.publish(peer(8, 99, 2, 9, first.loadState(), first.reachability(), "B")));
        assertEquals(revision, directory.publicationRevision(first.libraryId()));
        assertEquals(first, directory.find(first.libraryId()).orElseThrow());
        var unavailable = first.withLoadState(LibraryRegistration.LoadState.UNLOADED);
        assertTrue(directory.publish(unavailable), "same generation can publish availability changes");
        assertTrue(directory.publicationRevision(first.libraryId()) > revision);
        assertEquals(unavailable, directory.find(first.libraryId()).orElseThrow());
        revision = directory.publicationRevision(first.libraryId());
        var reformed = peer(8, 3, 3, 0, first.loadState(), first.reachability(), "B");
        assertTrue(directory.publish(reformed), "new formation may restart the catalog generation");
        assertTrue(directory.publicationRevision(first.libraryId()) > revision);
        assertEquals(reformed, directory.find(first.libraryId()).orElseThrow());
    }

    @Test void exactFrequencyVisibilityIsIndependentFromLoadAndRouteAvailability() {
        for (var loaded : LibraryRegistration.LoadState.values())
            for (var reachable : LibraryRegistration.Reachability.values())
                for (String published : List.of("A", " A ", "a", "", " "))
                    for (String requested : List.of("A", " A ", "a", "", " ")) {
                        var peer = peer(8, 20, 1, 7, loaded, reachable, published);
                        var frequency = CommunicationFrequency.of(requested);
                        boolean exactConfigured = !requested.isBlank() && requested.equals(published);
                        boolean available = loaded == LibraryRegistration.LoadState.LOADED;
                        assertTrue(peer.visibleTo(REMOTE, frequency));
                        assertEquals(available, peer.queryableBy(REMOTE, frequency));
                        assertEquals(available, peer.queryableBy(REMOTE));
                        assertEquals(exactConfigured, peer.visibleTo(LOCAL, frequency));
                        assertEquals(exactConfigured && available && reachable == LibraryRegistration.Reachability.REACHABLE,
                            peer.queryableBy(LOCAL, frequency));
                        assertFalse(peer.queryableBy(LOCAL), "one-argument lookup cannot grant cross-cluster access");
                    }
    }

    @Test void snapshotsAndSourcePositionsCannotBeChangedByLaterPublicationsOrCallerMutation() {
        var position = new BlockPos.MutableBlockPos(4, 5, 6);
        var first = new LibraryRegistration(id(8), REMOTE, new RecipeSourceLocation(Level.OVERWORLD, id(99), position),
            1, CommunicationFrequency.of("A"), 20, 7, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE);
        var directory = new LibraryDirectory(); directory.publish(first);
        var known = directory.knownPeers(); var visible = directory.visibleTo(LOCAL, first.frequency());
        position.set(90, 91, 92);
        directory.publish(first.withLoadState(LibraryRegistration.LoadState.UNLOADED));
        assertEquals(new BlockPos(4, 5, 6), first.address().localPos());
        assertEquals(List.of(first), known); assertEquals(List.of(first), visible);
        assertThrows(UnsupportedOperationException.class, () -> visible.clear());
        assertEquals(LibraryRegistration.LoadState.UNLOADED, directory.find(first.libraryId()).orElseThrow().loadState());
        assertEquals(LibraryRegistration.LoadState.LOADED, known.getFirst().loadState());
    }

    @Test void tiedPrioritiesUseIdentityOrderAndOffFrequencyPeersRemainKnown() {
        var first = peer(8, 20, 1, 7, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE, "A");
        var second = peer(9, 20, 1, 7, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE, "B");
        for (var order : List.of(List.of(first, second), List.of(second, first))) {
            var directory = new LibraryDirectory(); order.forEach(directory::publish);
            assertEquals(List.of(first, second), directory.knownPeers());
            assertEquals(List.of(first), directory.visibleTo(LOCAL, first.frequency()));
        }
    }
}
