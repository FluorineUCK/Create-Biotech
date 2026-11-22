package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgramEditSessionTest {
    @Test void foreignSessionReplayAndStaleRevisionCannotInstall() {
        UUID nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce); var revision = new AtomicLong(4);
        java.util.function.Supplier<ComputerRuntime.InstallResult> install = () -> { revision.incrementAndGet(); return ComputerRuntime.InstallResult.APPLIED; };
        assertTrue(session.submit(UUID.randomUUID(), 1, 4, revision::get, install).isEmpty());
        assertEquals(4, revision.get());
        assertEquals("stale", session.submit(nonce, 1, 3, revision::get, install).orElseThrow().code());
        assertEquals(4, revision.get());
        assertTrue(session.submit(nonce, 1, 4, revision::get, install).isEmpty());
        assertEquals("applied", session.submit(nonce, 2, 4, revision::get, install).orElseThrow().code());
        assertEquals(5, revision.get());
        assertEquals("stale", session.submit(nonce, 3, 4, revision::get, install).orElseThrow().code());
        assertEquals(5, revision.get());
    }

    @Test void rejectionKeepsRevisionAndAllowsExplicitRetryButNotReplay() {
        UUID nonce = UUID.randomUUID(); var session = new ProgramEditSession(nonce);
        var rejected = session.submit(nonce, 1, 0, () -> 0, () -> { throw new IllegalArgumentException("invalid program"); }).orElseThrow();
        assertEquals("invalid", rejected.code()); assertEquals(0, rejected.revision());
        var retry = session.submit(nonce, 2, 0, () -> 0, () -> ComputerRuntime.InstallResult.QUEUED).orElseThrow();
        assertEquals("queued", retry.code());
    }
}
