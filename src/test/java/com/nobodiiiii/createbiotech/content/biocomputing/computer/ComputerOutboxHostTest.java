package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerOutboxTest.sender;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerOutboxHostTest {
    @Test void pollingNeverRunsAnEventAndAcknowledgementIsDurableBeforeReturning() {
        var dirty = new AtomicInteger(); var reference = new ComputerBlockHost[1];
        var host = reference[0] = new ComputerBlockHost(() -> {
            assertEquals(reference[0].snapshot().orElseThrow(), ComputerNbtCodec.decode((CompoundTag) reference[0].write(), SAVE_LIMITS));
            dirty.incrementAndGet();
        });
        assertTrue(host.outbound(LIMITS, true).isEmpty());
        host.install(sender(2), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(EMPTY, LIMITS, true); var before = host.write(); int changes = dirty.get();
        for (int i = 0; i < 5; i++) assertTrue(host.outbound(LIMITS, true).isEmpty());
        assertEquals(before, host.write()); assertEquals(changes, dirty.get());
        finish(host); var first = host.outbound(LIMITS, true).orElseThrow();
        before = host.write(); changes = dirty.get();
        for (int i = 0; i < 5; i++) assertEquals(first, host.outbound(LIMITS, true).orElseThrow());
        assertEquals(before, host.write()); assertEquals(changes, dirty.get());
        assertTrue(host.acknowledge(first.eventId(), 0, LIMITS, true));
        assertEquals(changes + 1, dirty.get());
        assertFalse(host.acknowledge(first.eventId(), 0, LIMITS, true)); assertEquals(changes + 1, dirty.get());
        var restored = new ComputerBlockHost(() -> fail("Read-only load must not dirty")); restored.read(host.write());
        assertEquals(1, restored.outbound(LIMITS, true).orElseThrow().ordinal());
        assertEquals(first.eventId(), restored.outbound(LIMITS, true).orElseThrow().eventId());
    }

    @Test void pausedOrUnavailableOwnersDoNotOfferMessagesToAutomaticTransport() {
        var host = installed(); host.begin(EMPTY, LIMITS, true); finish(host);
        var message = host.outbound(LIMITS, true).orElseThrow();
        host.setPaused(true, LIMITS, true); var saved = host.write();
        assertTrue(host.outbound(LIMITS, true).isEmpty());
        assertFalse(host.acknowledge(message.eventId(), 0, LIMITS, true)); assertEquals(saved, host.write());
        host.setPaused(false, LIMITS, true); saved = host.write();
        assertTrue(host.outbound(LIMITS, false).isEmpty());
        assertThrows(ComputerBlockHost.HostException.class, () -> host.acknowledge(message.eventId(), 0, LIMITS, false));
        assertEquals(saved, host.write());
        assertEquals(message, host.outbound(LIMITS, true).orElseThrow());
    }

    @Test void unsaveableSliceCannotPublishSpeculativeOutboxOrRam() {
        var host = installed(); host.begin(EMPTY, LIMITS, true);
        var before = host.snapshot().orElseThrow(); var saved = (CompoundTag) host.write();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000,
            ValueNbtCodec.decode(saved, SAVE_LIMITS).storageUnits())));
        host.tick(tight, true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        assertEquals(before, host.snapshot().orElseThrow()); assertEquals(saved, host.write());
        assertTrue(host.outbound(tight, true).isEmpty());
        host.tick(LIMITS, true); finish(host);
        assertEquals(integer(1), host.outbound(LIMITS, true).orElseThrow().payload().field("count"));
    }

    @Test void restoredPendingOutboxDoesNotDisappearOnAnIdleHostTick() {
        var host = installed(); host.begin(EMPTY, LIMITS, true); finish(host);
        var message = host.outbound(LIMITS, true).orElseThrow(); var saved = host.write();
        var restored = new ComputerBlockHost(() -> fail("Idle tick must not mutate a committed outbox")); restored.read(saved);
        restored.tick(LIMITS, true);
        assertEquals(saved, restored.write()); assertEquals(message, restored.outbound(LIMITS, true).orElseThrow());
    }

    @Test void unsaveableFinalCommitRollsBackBothMemoryAndMessageVisibility() {
        var text = new DataValue.TextValue("x".repeat(2000));
        var graph = new ComputationGraph(Map.of(), Map.of(
            "text", new ComputationGraph.Constant(text),
            "name", new ComputationGraph.Constant(new DataValue.TextValue("receiver")),
            "payload", new ComputationGraph.MakeRecord(Map.of("text", "text"))),
            Map.of("text", "text", "endpoint", "name", "payload", "payload"));
        var program = new ProgramArchive(new ProgramDefinition(graph,
            Map.of("text", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue(""))),
            List.of(new ProgramDefinition.Write("text", "text")),
            List.of(new ProgramDefinition.Send("endpoint", "payload"))), Map.of());
        var host = new ComputerBlockHost(() -> {}); host.install(program, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(EMPTY, LIMITS, true);
        for (int i = 0; i < 100; i++) {
            var frames = host.snapshot().orElseThrow().machine().frames();
            if (frames.size() == 1 && frames.getFirst() instanceof MachineCheckpoint.CommitState commit && commit.cursor() == 1) break;
            assertTrue(i < 99); host.step(LIMITS, true);
        }
        var before = host.snapshot().orElseThrow(); var saved = (CompoundTag) host.write();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000,
            ValueNbtCodec.decode(saved, SAVE_LIMITS).storageUnits())));
        // The final commit replaces small RAM with the large value, growing the persisted snapshot.
        var failure = assertThrows(ComputerBlockHost.HostException.class, () -> host.step(tight, true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, failure.code());
        assertEquals(before, host.snapshot().orElseThrow()); assertEquals(saved, host.write());
        assertTrue(host.outbound(tight, true).isEmpty());
        host.step(LIMITS, true);
        assertEquals(text, host.snapshot().orElseThrow().machine().memory().field("text"));
        assertEquals(text, host.outbound(LIMITS, true).orElseThrow().payload().field("text"));
    }

    private static ComputerBlockHost installed() {
        var host = new ComputerBlockHost(() -> {}); host.install(sender(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); return host;
    }
    private static void finish(ComputerBlockHost host) {
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.step(LIMITS, true);
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
    }
}
