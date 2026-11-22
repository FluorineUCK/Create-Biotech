package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class RequestReplyHostTest {
    private static final ComputerHostLimits LIMITS = EventEntryFixtures.LIMITS;
    @Test void archiveLimitRejectsStagedMetadataWithoutPublishingOrDirtying() {
        var dirty = new AtomicInteger(); var host = new ComputerBlockHost(dirty::incrementAndGet);
        host.install(client(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var before = host.snapshot().orElseThrow(); var raw = (CompoundTag)host.write(); int changes = dirty.get();
        var prospective = ComputerRuntime.restore(before, HARDWARE); prospective.load(); prospective.begin(input("query", 1));
        long stagedSize = ValueNbtCodec.decode(ComputerNbtCodec.encode(prospective.snapshot(), EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE).storageUnits();
        long capacity = stagedSize - prospective.snapshot().rpc().storageUnits();
        assertTrue(capacity >= ValueNbtCodec.decode(raw, EventEntryFixtures.ARCHIVE).storageUnits());
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000, capacity)));
        assertThrows(ComputerBlockHost.HostException.class, () -> host.begin(input("query", 1), tight, true));
        assertEquals(before, host.snapshot().orElseThrow()); assertEquals(raw, host.write()); assertEquals(changes, dirty.get());
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
    }
    @Test void durableHostsRoundTripEverySliceAndStaleReplyDoesNotDirty() {
        var dirty = new AtomicInteger(); var client = new ComputerBlockHost(dirty::incrementAndGet);
        client.install(client(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); client.begin(input("query", 6), LIMITS, true); finishHost(client);
        var request = client.outbound(LIMITS, true).orElseThrow(); var clientId = client.snapshot().orElseThrow().id();
        var server = new ComputerBlockHost(() -> {}); server.install(server(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        assertEquals(AdmissionStatus.ACCEPTED, server.receive(request(clientId, request, "client"), LIMITS, true));
        server.step(LIMITS, true);
        var reload = new ComputerBlockHost(() -> {}); reload.read(server.write()); reload.step(LIMITS, true); finishHost(reload);
        var response = reply(reload.snapshot().orElseThrow().id(), reload.outbound(LIMITS, true).orElseThrow());
        client.acknowledge(request.eventId(), request.ordinal(), LIMITS, true);
        assertEquals(AdmissionStatus.ACCEPTED, client.receive(response, LIMITS, true)); client.step(LIMITS, true); finishHost(client);
        assertEquals(new DataValue.IntegerValue(6), client.snapshot().orElseThrow().machine().memory().field("result"));
        var before = client.write(); int changes = dirty.get();
        assertEquals(AdmissionStatus.STALE_REPLY, client.receive(response, LIMITS, true)); assertEquals(before, client.write()); assertEquals(changes, dirty.get());
        assertEquals(client.snapshot().orElseThrow(), ComputerNbtCodec.decode((CompoundTag)client.write(), EventEntryFixtures.ARCHIVE));
    }
    @Test void hostMetadataBudgetRejectionPreservesLastDurableState() {
        var archive = client(); var usage = archive.compile(HARDWARE).usage();
        var hardware = new ResourceLimits(1000, usage.constantUnits() + usage.initialMemoryUnits() * 2 + input("query", 1).storageUnits(), 1);
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(hardware, EventEntryFixtures.ARCHIVE)); var host = new ComputerBlockHost(() -> {});
        host.install(archive, ComputerRuntime.MemoryPolicy.RESET, limits, true); var before = host.write();
        assertThrows(ComputationException.class, () -> host.begin(input("query", 1), limits, true)); assertEquals(before, host.write());
        assertTrue(host.snapshot().orElseThrow().rpc().pending().isEmpty());
    }
    private static void finishHost(ComputerBlockHost host) {
        for (int i = 0; i < 100 && host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) host.step(LIMITS, true);
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
    }
}
