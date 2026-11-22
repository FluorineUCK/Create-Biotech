package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusBroadcastOwnerTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class XBusSharedReadabilityTest {
    static FlowProgram program(boolean result) {
        return new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("probe", "x0", result ? Optional.of("ready") : Optional.empty()),
            new FlowProgram.Read("read", "x0", "received", PACKET),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("packet", PACKET), Map.of("packet", "received")),
            new FlowProgram.AwaitReadable("child_probe", "x0"),
            new FlowProgram.Read("child_read", "x0", "received", PACKET), new FlowProgram.EndProcedure("return")));
    }
    static XBusNetwork.Pin pin(ComputerBlockHost host) { return new XBusNetwork.Pin(host.flowSnapshot().orElseThrow().state().computer(), "x0"); }
    static XBusAccess.Network network(List<ComputerBlockHost> hosts, XBusHistorySavedData history) {
        return XBusAccess.network(hosts.stream().map(h -> access(h, history)).toList());
    }
    @Test void sharedProbesDoNotConsumeOtherDataReadsOrAllocateAPayloadReceipt() {
        var writer = writer(42); var probes = install(program(false), EMPTY); probes.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        var wrongType = new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT));
        var data = install(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("data", "x0", "packet", wrongType))), EMPTY);
        var hosts = List.of(writer, probes, data); hosts.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var beforeWriter = writer.write(); var beforeData = data.write(); var history = new XBusHistorySavedData();
        assertTrue(history.observe(hosts.stream().map(h -> h.flowSnapshot().orElseThrow()).toList(), 10, 100).isEmpty());
        var beforeHistory = history.save(new CompoundTag(), null);
        assertEquals(XBusExchange.Status.READABLE, network(hosts, history).exchange(pin(probes), pin(writer)));
        assertEquals(beforeWriter, writer.write()); assertEquals(beforeData, data.write());
        assertEquals(beforeHistory, history.save(new CompoundTag(), null));
        assertTrue(probes.flowSnapshot().orElseThrow().pinClaims().entries().isEmpty());
        assertTrue(probes.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(probes.flowSnapshot(), restore(probes.write()).flowSnapshot());
        assertEquals(XBusExchange.Status.TYPE_MISMATCH, network(hosts, history).exchange(pin(data), pin(writer)),
            "Only an actual data receive validates the payload type");
        assertEquals(beforeWriter, writer.write()); assertEquals(beforeData, data.write());
    }
    @Test void restoredSharedProbesWakeBeforeTheirRecordedReadsButCannotConsumeANewWriter() {
        var writer = writer(42); var probes = install(program(false), EMPTY); var child = probes.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        var original = List.of(writer, probes); original.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var waiting = probes.write(); var history = new XBusHistorySavedData();
        assertEquals(XBusExchange.Status.READABLE, network(original, history).exchange(pin(probes), pin(writer)));
        for (long tick = 6; tick < 10; tick++) probes.tick(tick, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, network(original, history).exchange(pin(probes), pin(writer)));
        var completed = probes.flowSnapshot(); var finishedWriter = writer.write();
        probes.read(waiting); probes.setPaused(false, LIMITS, true);
        // Load a pre-completion-proof journal: legacy probes must still reach their real repair read.
        var legacy = history.save(new CompoundTag(), null); legacy.putInt("Version", 7);
        legacy.getList("Claims", 10).forEach(tag -> ((CompoundTag) tag).remove("Settled"));
        history = XBusHistorySavedData.load(legacy, null);
        var later = writer(99); waitOnPin(later); var laterSave = later.write(); var hosts = List.of(writer, probes, later);
        var captured = network(hosts, history);
        assertEquals(Optional.of(XBusExchange.Status.RECOVERY_REQUIRED), captured.inspect().rejection());
        assertEquals(XBusExchange.Status.READABLE, captured.exchange(pin(probes), pin(later)));
        assertEquals(finishedWriter, writer.write()); assertEquals(laterSave, later.write());
        assertTrue(probes.flowSnapshot().orElseThrow().receipts().isEmpty());
        for (long tick = 10; tick < 14; tick++) probes.tick(tick, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.RECOVERY_REQUIRED, network(hosts, history).exchange(pin(probes), pin(later)));
        assertEquals(XBusExchange.Status.EXCHANGED, network(hosts, history).exchange(pin(probes), pin(writer)));
        assertEquals(completed, probes.flowSnapshot()); assertEquals(packet(42), probes.flowTask(child).orElseThrow().locals().field("received"));
        assertEquals(finishedWriter, writer.write()); assertEquals(laterSave, later.write());
    }
    @Test void oneProbesArchiveLimitPreventsPartialWakeAndLeavesTheWriterWaiting() {
        var writer = writer(42); var a = install(program(true), EMPTY); var b = install(program(true), EMPTY);
        var hosts = List.of(writer, a, b); hosts.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var before = hosts.stream().map(ComputerBlockHost::write).toList();
        var bindings = hosts.stream().map(XBusBroadcastOwnerTest::binding).toList();
        assertEquals(XBusExchange.Status.READABLE, ComputerBlockHost.prepareReadableGroup(bindings, bindings.getFirst()).commit());
        long units = ValueNbtCodec.decode(((CompoundTag)b.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        assertTrue(ValueNbtCodec.decode(((CompoundTag)before.get(2)).getCompound("Data"), LIMITS.archive()).storageUnits() < units);
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units - 1),
            LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots()));
        var restored = before.stream().map(XBusBroadcastOwnerTest::restore).toList();
        var limited = List.of(binding(restored.get(0)), binding(restored.get(1)), new ComputerBlockHost.XBusBinding(restored.get(2), "x0", tight, true));
        assertEquals(XBusExchange.Status.ARCHIVE_LIMIT, ComputerBlockHost.prepareReadableGroup(limited, limited.getFirst()).commit());
        assertEquals(before, restored.stream().map(ComputerBlockHost::write).toList());
    }
    @Test void changedOrUnloadedParticipantInvalidatesPreparedReadinessWithoutPartiallyWakingPeers() {
        for (boolean unload : List.of(false, true)) {
            var writer = writer(42); var a = install(program(false), EMPTY); var b = install(program(false), EMPTY);
            var hosts = List.of(writer, a, b); hosts.forEach(XBusBroadcastOwnerTest::waitOnPin);
            var bindings = hosts.stream().map(XBusBroadcastOwnerTest::binding).toList();
            var prepared = ComputerBlockHost.prepareReadableGroup(bindings, bindings.getFirst());
            assertEquals(XBusExchange.Status.READABLE, prepared.status()); assertTrue(prepared.receipt().isEmpty());
            if (unload) b.unload(); else b.setPaused(true, LIMITS, true);
            var before = hosts.stream().map(ComputerBlockHost::write).toList();
            assertEquals(unload ? XBusExchange.Status.UNAVAILABLE : XBusExchange.Status.STALE, prepared.commit());
            assertEquals(before, hosts.stream().map(ComputerBlockHost::write).toList());
        }
    }
    @Test void dirtyNotificationFailureOccursOnlyAfterAllProbesArePublishedAndNeverDirtiesTheWriter() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean(); var observed = new ArrayList<Integer>();
        var a = new ComputerBlockHost(() -> { if (armed.get()) { observed.add(1); throw new ArchiveException(ArchiveException.Code.LIMIT_EXCEEDED, "published"); } });
        var b = new ComputerBlockHost(() -> { if (armed.get()) observed.add(2); });
        for (var host : List.of(a, b)) { host.installFlow(program(false), EMPTY, LIMITS, true); host.setPaused(false, LIMITS, true); }
        var writer = writer(42); var hosts = List.of(writer, a, b); hosts.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var beforeWriter = writer.write(); var captured = network(hosts, new XBusHistorySavedData()); armed.set(true);
        assertEquals("published", assertThrows(ArchiveException.class, () -> captured.exchange(pin(a), pin(writer))).getMessage());
        assertEquals(Set.of(1, 2), new HashSet<>(observed)); assertEquals(2, observed.size());
        for (var host : List.of(a, b)) {
            assertEquals(FlowRuntime.Status.READY, host.flowSnapshot().orElseThrow().state().status());
            assertEquals(1, host.flowSnapshot().orElseThrow().state().cursor());
            assertTrue(host.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertEquals(host.flowSnapshot(), restore(host.write()).flowSnapshot());
        }
        assertEquals(beforeWriter, writer.write());
        assertEquals(XBusExchange.Status.STALE, captured.exchange(pin(a), pin(writer)));
        assertEquals(2, observed.size());
    }
}
