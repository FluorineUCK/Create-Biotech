package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerReceptionTest.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerReceptionHostTest {
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(HARDWARE, ARCHIVE);

    @Test void failedSaveOrChangedTargetNeverAcknowledgesOrSuspendsTheExistingOwner() {
        var host = new ComputerBlockHost(() -> {});
        host.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var id = host.snapshot().orElseThrow().id();
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        var before = (CompoundTag)host.write();
        assertEquals(ReceptionStatus.TARGET_CHANGED, host.receiveReady(UUID.randomUUID(), message, LIMITS, true));
        assertEquals(before, host.write());
        var tight = new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000,
            ValueNbtCodec.decode(before, ARCHIVE).storageUnits()));
        assertEquals(ReceptionStatus.ARCHIVE_LIMIT, host.receiveReady(id, message, tight, true));
        assertEquals(before, host.write()); assertTrue(host.loaded());
        assertEquals(ComputationMachine.Status.IDLE, host.snapshot().orElseThrow().machine().status());
        assertEquals(ReceptionStatus.RECEIVED, host.receiveReady(id, message, LIMITS, true));
    }

    @Test void handoffIsEncodedBeforeAcknowledgementAndRetryAfterReloadCannotRepeatIt() {
        var reference = new ComputerBlockHost[1];
        var host = reference[0] = new ComputerBlockHost(() -> assertEquals(reference[0].snapshot().orElseThrow(),
            ComputerNbtCodec.decode((CompoundTag)reference[0].write(), ARCHIVE)));
        host.install(receiver(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var id = host.snapshot().orElseThrow().id();
        var message = ComputerMailboxTest.message(UUID.randomUUID(), 0, 7);
        assertEquals(ReceptionStatus.RECEIVED, host.receiveReady(id, message, LIMITS, true));
        var before = host.write();
        assertEquals(ComputationMachine.Status.RUNNING, host.snapshot().orElseThrow().machine().status());
        assertEquals(0, host.snapshot().orElseThrow().machine().workUnits());
        var restored = new ComputerBlockHost(() -> {}); restored.read(before);
        assertEquals(ReceptionStatus.RECEIVED, restored.receiveReady(id, message, LIMITS, true));
        assertEquals(before, restored.write());
        for (int i=0; restored.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); restored.tick(LIMITS, true);
        }
        assertEquals(integer(7), restored.snapshot().orElseThrow().machine().memory().field("total"));
        assertEquals(ReceptionStatus.RECEIVED, restored.receiveReady(id, message, LIMITS, true));
        restored.tick(LIMITS, true);
        assertEquals(integer(7), restored.snapshot().orElseThrow().machine().memory().field("total"));
    }
}
