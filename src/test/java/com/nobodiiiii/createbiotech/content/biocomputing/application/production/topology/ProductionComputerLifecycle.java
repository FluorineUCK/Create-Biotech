package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.UUID;

/** Internal production lifetime/fence state. No world, program, RAM, hardware or publication callback is retained. */
public final class ProductionComputerLifecycle {
    private UUID ownerToken = UUID.randomUUID();
    private long generation;
    private boolean suppressed;

    public UUID ownerToken() { return ownerToken; }
    public long generation() { return generation; }
    public boolean available() { return generation != Long.MAX_VALUE; }
    /** Eligibility alone does not authorize publication; the host must still prove current topology/authority. */
    public boolean eligible() { return !suppressed && available(); }

    /** The final MAX-1 -> MAX transition succeeds; later attempts never wrap the durable fence. */
    public boolean advance() {
        if (!available()) { suppressed = true; return false; }
        generation++;
        return true;
    }

    public void suppressUntilStructureAccepted() {
        if (suppressed) return;
        suppressed = true;
        advance();
    }

    /** Called only after the host accepts a structure; generation exhaustion cannot be repaired this way. */
    public void acceptStructure() { if (available()) suppressed = false; }

    /** A router's retired-generation floor only raises the fence and never repairs a broken shell. */
    public boolean ensureGeneration(long floor) {
        requireGeneration(floor);
        if (generation >= floor) return false;
        generation = floor;
        if (!available()) suppressed = true;
        return true;
    }

    /** The host must retire the old exact registration before calling this on server deserialization. */
    public void resetForRead() {
        ownerToken = UUID.randomUUID();
        generation = 0;
        suppressed = false;
    }

    /** Archive-only restoration after reset, not a live route-generation update or a program operation. */
    public void restoreGeneration(long saved) {
        requireGeneration(saved);
        generation = saved;
    }

    private static void requireGeneration(long value) {
        if (value < 0) throw new IllegalArgumentException("negative production topology generation");
    }
}
