package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProductionComputerLifecycleTest {
    @Test void eachComputerKeepsItsOwnIdentityAndBrokenShellFenceUntilAccepted() {
        var a = new ProductionComputerLifecycle(); var b = new ProductionComputerLifecycle();
        var owner = a.ownerToken();
        assertNotEquals(owner, b.ownerToken()); assertTrue(a.eligible()); assertTrue(a.available());
        assertTrue(a.advance()); assertEquals(1, a.generation());
        a.suppressUntilStructureAccepted(); assertFalse(a.eligible()); assertEquals(2, a.generation());
        a.suppressUntilStructureAccepted(); assertEquals(2, a.generation());
        assertTrue(a.ensureGeneration(7)); assertEquals(7, a.generation()); assertFalse(a.eligible());
        a.acceptStructure(); assertTrue(a.eligible()); assertEquals(7, a.generation());
        assertEquals(owner, a.ownerToken()); assertEquals(0, b.generation()); assertTrue(b.eligible());
        a.resetForRead(); var reloadedOwner = a.ownerToken();
        assertNotEquals(owner, reloadedOwner); assertEquals(0, a.generation()); assertTrue(a.eligible());
        a.restoreGeneration(37); assertEquals(37, a.generation()); assertEquals(reloadedOwner, a.ownerToken());
    }

    @Test void generationFloorsNeverLowerTheFenceOrMutateIdentity() {
        var state = new ProductionComputerLifecycle(); var owner = state.ownerToken();
        assertFalse(state.ensureGeneration(0));
        assertTrue(state.ensureGeneration(37));
        assertFalse(state.ensureGeneration(36)); assertFalse(state.ensureGeneration(37));
        assertEquals(37, state.generation()); assertEquals(owner, state.ownerToken()); assertTrue(state.eligible());
        state.suppressUntilStructureAccepted();
        assertFalse(state.ensureGeneration(37)); assertEquals(38, state.generation()); assertFalse(state.eligible());
    }

    @Test void allWaysOfReachingTheDurableLimitAreTerminalUntilANewLoad() {
        for (int path = 0; path < 4; path++) {
            var state = new ProductionComputerLifecycle(); var owner = state.ownerToken();
            switch (path) {
                case 0 -> state.restoreGeneration(Long.MAX_VALUE);
                case 1 -> assertTrue(state.ensureGeneration(Long.MAX_VALUE));
                case 2 -> {
                    state.restoreGeneration(Long.MAX_VALUE - 1);
                    assertTrue(state.advance(), "The final representable transition preserves the host's existing contract");
                }
                case 3 -> {
                    state.restoreGeneration(Long.MAX_VALUE - 1);
                    state.suppressUntilStructureAccepted();
                }
            }
            assertFalse(state.available()); assertFalse(state.eligible());
            state.acceptStructure(); assertFalse(state.eligible());
            assertFalse(state.ensureGeneration(Long.MAX_VALUE - 1));
            assertFalse(state.ensureGeneration(Long.MAX_VALUE));
            assertFalse(state.advance()); assertFalse(state.advance());
            state.suppressUntilStructureAccepted(); state.acceptStructure();
            assertFalse(state.eligible()); assertEquals(Long.MAX_VALUE, state.generation());
            assertEquals(owner, state.ownerToken());
        }
    }

    @Test void invalidGenerationCannotPartiallyChangeAClosedLifetime() {
        var state = new ProductionComputerLifecycle(); state.ensureGeneration(9);
        state.suppressUntilStructureAccepted(); var owner = state.ownerToken();
        assertThrows(IllegalArgumentException.class, () -> state.ensureGeneration(-1));
        assertThrows(IllegalArgumentException.class, () -> state.restoreGeneration(-1));
        assertEquals(10, state.generation()); assertFalse(state.eligible()); assertEquals(owner, state.ownerToken());
        assertTrue(state.advance()); assertEquals(11, state.generation()); assertFalse(state.eligible());
        state.acceptStructure(); assertTrue(state.eligible()); assertEquals(11, state.generation());
        state.suppressUntilStructureAccepted(); state.suppressUntilStructureAccepted();
        assertEquals(12, state.generation()); assertFalse(state.eligible());
    }

    @Test void repeatedArchiveRestorationRotatesOnlyLoadedIdentityAndKeepsTheExactSavedFence() {
        var state = new ProductionComputerLifecycle(); state.ensureGeneration(Long.MAX_VALUE);
        var owner = state.ownerToken(); state.resetForRead();
        var nextOwner = state.ownerToken(); assertNotEquals(owner, nextOwner);
        assertEquals(0, state.generation()); assertTrue(state.eligible());
        state.restoreGeneration(37); assertEquals(37, state.generation()); assertEquals(nextOwner, state.ownerToken());
        state.suppressUntilStructureAccepted(); state.resetForRead();
        assertNotEquals(nextOwner, state.ownerToken()); assertTrue(state.eligible());
        state.restoreGeneration(37); assertEquals(37, state.generation());
        var rebuilt = new ProductionComputerLifecycle(); rebuilt.restoreGeneration(37);
        assertEquals(state.generation(), rebuilt.generation()); assertNotEquals(state.ownerToken(), rebuilt.ownerToken());
    }
}
