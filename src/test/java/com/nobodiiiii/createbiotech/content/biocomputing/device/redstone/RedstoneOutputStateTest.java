package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedstoneOutputStateTest {
    private final UUID device = UUID.randomUUID(), source = UUID.randomUUID();
    private RedstoneOutputState.State open() {
        return RedstoneOutputState.grant(RedstoneOutputState.closed(device, UUID.randomUUID()), Optional.of(source), UUID.randomUUID());
    }
    private RedstoneOutputState.Update set(RedstoneOutputState.State state, int level) {
        return RedstoneOutputState.set(state, source, state.device(), state.session(), state.revision(), level);
    }

    @Test void closedDeviceCannotBeEnergizedAndTheFullAnalogRangeIsPreserved() {
        var closed = RedstoneOutputState.closed(device, UUID.randomUUID());
        assertEquals("closed", set(closed, 15).code());
        assertEquals(0, closed.applied());
        var state = open();
        for (int level = 0; level <= 15; level++) {
            var applied = set(state, level);
            assertEquals("applied", applied.code());
            assertEquals(level, applied.state().desired());
            assertEquals(level, applied.state().applied());
            state = applied.state();
        }
        for (int level : new int[]{-1, 16, Integer.MAX_VALUE}) {
            var rejected = set(state, level);
            assertEquals("out_of_range", rejected.code());
            assertSame(state, rejected.state());
        }
    }

    @Test void onlyTheGrantedPhysicalSourceAndCurrentDeviceSessionVersionMaySetOutput() {
        var state = open();
        assertEquals("denied", RedstoneOutputState.set(state, UUID.randomUUID(), device, state.session(), state.revision(), 15).code());
        assertEquals("device_changed", RedstoneOutputState.set(state, source, UUID.randomUUID(), state.session(), state.revision(), 15).code());
        assertEquals("stale_session", RedstoneOutputState.set(state, source, device, UUID.randomUUID(), state.revision(), 15).code());
        var applied = set(state, 9).state();
        var competing = RedstoneOutputState.set(applied, source, device, state.session(), state.revision(), 3);
        assertEquals("changed", competing.code());
        assertSame(applied, competing.state());
        assertEquals(9, competing.state().applied());
        assertFalse(set(applied, 9).signalChanged());
    }

    @Test void revocationClearsAppliedButKeepsDesiredAndCannotReuseAnEarlierSession() {
        var before = set(open(), 12).state();
        var revoked = RedstoneOutputState.grant(before, Optional.empty(), UUID.randomUUID());
        assertEquals(12, revoked.desired()); assertEquals(0, revoked.applied());
        assertEquals("closed", set(revoked, 12).code());
        var restored = RedstoneOutputState.grant(revoked, Optional.of(source), UUID.randomUUID());
        assertEquals(0, restored.applied());
        assertEquals("stale_session", RedstoneOutputState.set(restored, source, device, before.session(), restored.revision(), 12).code());
        assertEquals(12, set(restored, 12).state().applied());
    }

    @Test void saveLoadIsNotACommandAndOldFramesCannotReenergizeTheDevice() {
        var before = set(open(), 15).state();
        var saved = RedstoneOutputState.save(before);
        var loaded = RedstoneOutputState.load(saved, UUID.randomUUID());
        assertEquals(device, loaded.device()); assertEquals(Optional.of(source), loaded.controller());
        assertEquals(15, loaded.desired()); assertEquals(0, loaded.applied());
        assertEquals(before.revision(), loaded.revision());
        assertEquals("stale_session", RedstoneOutputState.set(loaded, source, device, before.session(), before.revision(), 15).code());
        assertEquals(0, loaded.applied());
        assertEquals(15, set(loaded, 15).state().applied());
        assertEquals(saved, RedstoneOutputState.save(before));
    }

    @Test void invalidArchivesAndSessionReuseAreRejectedRatherThanSilentlyOpened() {
        var state = open();
        assertThrows(IllegalArgumentException.class, () -> RedstoneOutputState.restart(state, state.session()));
        var archive = RedstoneOutputState.save(state);
        archive.putInt("Version", 99);
        var badVersion = archive;
        assertThrows(IllegalArgumentException.class, () -> RedstoneOutputState.load(badVersion, UUID.randomUUID()));
        archive = RedstoneOutputState.save(state); archive.putInt("Desired", 16);
        var invalid = archive;
        assertThrows(IllegalArgumentException.class, () -> RedstoneOutputState.load(invalid, UUID.randomUUID()));
    }

    @Test void anExhaustedCommandVersionMustNotPreventRevocationFromClearingPower() {
        var powered = new RedstoneOutputState.State(device, UUID.randomUUID(), Long.MAX_VALUE,
            Optional.of(source), 15, 15);
        assertEquals("version_exhausted", set(powered, 0).code());
        var revoked = RedstoneOutputState.grant(powered, Optional.empty(), UUID.randomUUID());
        assertEquals(0, revoked.applied());
        assertEquals(15, revoked.desired());
        assertTrue(revoked.controller().isEmpty());
        assertEquals(Long.MAX_VALUE, revoked.revision(), "Revocation does not wrap the exhausted counter");
        assertNotEquals(powered.session(), revoked.session());
        assertEquals("closed", set(revoked, 15).code());
        assertThrows(IllegalArgumentException.class,
            () -> RedstoneOutputState.grant(revoked, Optional.of(source), UUID.randomUUID()));
    }
}
