package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XBusBroadcastOwnerTest {
    static final ModuleDefinition.Key RECEIVE = new ModuleDefinition.Key("receive", 1);
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1),
        FlowArchiveTest.ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 4));
    static DataValue.RecordValue packet(long value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }
    static ComputerBlockHost install(FlowProgram program, DataValue.RecordValue inputs) {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program, inputs, LIMITS, true);
        host.setPaused(false, LIMITS, true); return host;
    }
    static ComputerBlockHost reader() {
        return install(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main_read", "x0", "received", PACKET),
            new FlowProgram.Procedure("receive", RECEIVE, Map.of(), Map.of("packet", PACKET), Map.of("packet", "received")),
            new FlowProgram.Read("task_read", "x0", "received", PACKET), new FlowProgram.EndProcedure("return"))), EMPTY);
    }
    static ComputerBlockHost writer(long value) {
        return install(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", packet(value))));
    }
    static void waitOnPin(ComputerBlockHost host) { for (long tick = 1; tick <= 5; tick++) host.tick(tick, LIMITS, true, Map.of()); }
    static XBusExchange.Status exchange(ComputerBlockHost left, ComputerBlockHost right) {
        return left.exchange("x0", LIMITS, true, right, "x0", LIMITS, true);
    }
    static ComputerBlockHost restore(net.minecraft.nbt.Tag tag) {
        var host = new ComputerBlockHost(() -> {}); host.read(tag); host.setPaused(false, LIMITS, true); return host;
    }
    static ComputerBlockHost.XBusBinding binding(ComputerBlockHost host) {
        return new ComputerBlockHost.XBusBinding(host, "x0", LIMITS, true);
    }
    @Test void transferLogsExcludePassivePinsAndOwnersAlreadyFinishedBeforeRepair() {
        var notifications = new ArrayList<Integer>(); var hosts = new ArrayList<ComputerBlockHost>();
        for (int i = 0; i < 3; i++) {
            final int index = i; var host = new ComputerBlockHost(() -> {}, pin -> notifications.add(index));
            var program = i == 0 ? new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet")))
                : i == 1 ? new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", PACKET)))
                : new FlowProgram(Map.of(), List.of());
            host.installFlow(program, i == 0 ? new DataValue.RecordValue(Map.of("packet", packet(42))) : EMPTY, LIMITS, true);
            host.setPaused(false, LIMITS, true); waitOnPin(host); hosts.add(host);
        }
        var oldReader = hosts.get(1).write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(hosts.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(hosts.getFirst())));
        assertEquals(Set.of(0, 1), new HashSet<>(notifications)); assertEquals(2, notifications.size());
        notifications.clear(); hosts.get(1).read(oldReader); hosts.get(1).setPaused(false, LIMITS, true);
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(hosts.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(hosts.getFirst())));
        assertEquals(List.of(1), notifications, "Repair is not a fresh transfer by already completed or passive devices");
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oneWriteCompletesEveryAlreadyWaitingContextWithoutFifoOrAnotherSend(boolean assemblyWriter) {
        var reader = reader(); var first = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        var second = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); var writer = writer(42);
        if (assemblyWriter) {
            var saved = writer.flowSnapshot().orElseThrow(); writer.installFlow(saved.program().withAssembly(), saved.initialInputs(), LIMITS, true);
            writer.setPaused(false, LIMITS, true);
        }
        waitOnPin(reader); waitOnPin(writer);
        assertEquals(3, reader.flowSnapshot().orElseThrow().pinClaims().entries().size());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        assertEquals(packet(42), reader.flowSnapshot().orElseThrow().state().locals().field("received"));
        for (var id : List.of(first, second)) assertEquals(packet(42), reader.flowTask(id).orElseThrow().locals().field("received"));
        assertEquals(assemblyWriter ? 4 : 1, writer.flowSnapshot().orElseThrow().state().operation());
        assertEquals(reader.flowSnapshot(), restore(reader.write()).flowSnapshot());
        assertEquals(writer.flowSnapshot(), restore(writer.write()).flowSnapshot());
        assertEquals(XBusExchange.Status.NOT_READY, exchange(reader, writer));
    }
    @Test void pausedPeerEndsAllSamePinWaitsWithoutFabricatingAZeroOrAReceipt() {
        var reader = reader(); var child = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); var writer = writer(42);
        waitOnPin(reader); waitOnPin(writer); writer.setPaused(true, LIMITS, true); var beforeWriter = writer.write();
        var history = new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData();
        var network = com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess.network(List.of(access(reader, history), access(writer, history)));
        var pin = new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusNetwork.Pin(reader.flowSnapshot().orElseThrow().state().computer(), "x0");
        assertEquals(0, network.statusCode(pin));
        assertEquals(XBusExchange.Status.PAUSED, network.rejectPaused(pin));
        for (var state : List.of(reader.flowSnapshot().orElseThrow().state(), reader.flowTask(child).orElseThrow())) {
            assertEquals(FlowRuntime.Status.FAILED, state.status());
            assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, state.diagnostic().orElseThrow().code());
            assertEquals(EMPTY, state.locals());
        }
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertTrue(reader.flowSnapshot().orElseThrow().pinClaims().entries().isEmpty());
        assertEquals(beforeWriter, writer.write());
        assertEquals(reader.flowSnapshot(), restore(reader.write()).flowSnapshot());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialSaveRepairsOnlyTheFrozenRecipientsAndNeverFeedsALateTask(boolean oldReader) {
        var reader = reader(); var first = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); var writer = writer(42);
        waitOnPin(reader); waitOnPin(writer); var beforeReader = reader.write(); var beforeWriter = writer.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        var expectedReader = reader.flowSnapshot(); var expectedWriter = writer.flowSnapshot();
        var recoveredReader = restore(oldReader ? beforeReader : reader.write());
        var recoveredWriter = restore(oldReader ? writer.write() : beforeWriter);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(recoveredReader, recoveredWriter));
        assertEquals(expectedReader, recoveredReader.flowSnapshot()); assertEquals(expectedWriter, recoveredWriter.flowSnapshot());
        var late = recoveredReader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        for (long tick = 6; tick < 15; tick++) recoveredReader.tick(tick, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.NOT_READY, exchange(recoveredReader, recoveredWriter));
        assertEquals(FlowRuntime.Status.WAITING_READ, recoveredReader.flowTask(late).orElseThrow().status());
        assertEquals(packet(42), recoveredReader.flowTask(first).orElseThrow().locals().field("packet"));
    }
    @Test void claimingOneBroadcastResultKeepsTheOtherParticipantsEvidenceAndDoesNotFeedAReusedSlot() {
        var reader = reader(); var child = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); var writer = writer(42);
        waitOnPin(reader); waitOnPin(writer); var waitingWriter = writer.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        for (long tick = 6; tick < 15; tick++) reader.tick(tick, LIMITS, true, Map.of());
        reader.claimFlowTask(child, LIMITS, true);
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size(), "Main still owns its consumption evidence");
        var fresh = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        for (long tick = 15; tick < 20; tick++) reader.tick(tick, LIMITS, true, Map.of());
        reader = restore(reader.write()); var oldWriter = restore(waitingWriter);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, oldWriter));
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(fresh).orElseThrow().status());
        assertEquals(1, oldWriter.flowSnapshot().orElseThrow().state().operation());
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void threeComputersRecoverEveryMixedSaveWithoutChoosingANewCohort(int oldMask) {
        var writer = writer(42); var a = reader(); var b = reader();
        var original = List.of(writer, a, b); original.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var before = original.stream().map(ComputerBlockHost::write).toList();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(original.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        var expected = original.stream().map(ComputerBlockHost::flowSnapshot).toList();
        var restored = new ArrayList<ComputerBlockHost>();
        for (int i = 0; i < 3; i++) restored.add(restore((oldMask & 1 << i) != 0 ? before.get(i) : original.get(i).write()));
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(restored.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(restored.getFirst())));
        assertEquals(expected, restored.stream().map(ComputerBlockHost::flowSnapshot).toList());
        assertEquals(XBusExchange.Status.NOT_READY, ComputerBlockHost.exchangeGroup(restored.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(restored.getFirst())));
    }
    @Test void competingWritersCompleteTogetherWithoutDiscardFlagOrLaterTransmission() {
        var writer = writer(42); var loser = writer(99); var reader = reader();
        var all = List.of(writer, loser, reader); all.forEach(XBusBroadcastOwnerTest::waitOnPin);
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        assertEquals(packet(42), reader.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(FlowRuntime.Status.COMPLETE, loser.flowSnapshot().orElseThrow().state().status());
        assertEquals(Set.of("packet"), loser.flowSnapshot().orElseThrow().state().locals().fields().keySet());
        var late = reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        for (long tick = 6; tick < 12; tick++) reader.tick(tick, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.NOT_READY, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(loser)));
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(late).orElseThrow().status());
    }
    @Test void capacityOfEveryComputerIsCheckedBeforeAnyRecipientOrSenderIsPublished() {
        var writer = writer(42); var a = reader(); var b = reader();
        b.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        var all = List.of(writer, a, b); all.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var before = all.stream().map(ComputerBlockHost::write).toList();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        var save = b.flowSnapshot().orElseThrow();
        long memory = save.initialInputs().storageUnits() + save.control().storageUnits() + save.pinUnits() + save.tasks().metadataUnits()
            + save.receipts().stream().mapToLong(r -> r.value().storageUnits()).sum();
        var main = FlowRuntime.restore(save.program(), save.state(), LIMITS.hardware()); memory += main.retainedUnits();
        for (var state : save.tasks().contexts()) memory += FlowRuntime.restore(save.program(), state, LIMITS.hardware()).retainedUnits() - main.sharedUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, memory - 1, 1), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots()));
        var restored = before.stream().map(XBusBroadcastOwnerTest::restore).toList();
        var pins = List.of(binding(restored.get(0)), binding(restored.get(1)), new ComputerBlockHost.XBusBinding(restored.get(2), "x0", tight, true));
        assertEquals(XBusExchange.Status.MEMORY_LIMIT, ComputerBlockHost.exchangeGroup(pins, pins.getFirst()));
        assertEquals(before, restored.stream().map(ComputerBlockHost::write).toList());
        assertTrue(restored.stream().allMatch(ComputerBlockHost::loaded));
    }
    @Test void oppositeDirectionOnAnOwnedPinRejectsOnlyTheNewTaskRatherThanReversingOrQueuingIt() {
        var send = new ModuleDefinition.Key("send", 1);
        var host = install(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main_read", "x0", "received", PACKET),
            new FlowProgram.Procedure("sender", send, Map.of("packet", PACKET), Map.of(), Map.of()),
            new FlowProgram.Write("task_send", "x0", "packet"), new FlowProgram.EndProcedure("return"))), EMPTY);
        waitOnPin(host); var waiting = host.flowSnapshot().orElseThrow().state();
        var task = host.startFlowTask(send, new DataValue.RecordValue(Map.of("packet", packet(99))), LIMITS, true);
        host.tick(6, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.FAILED, host.flowTask(task).orElseThrow().status());
        assertEquals(waiting, host.flowSnapshot().orElseThrow().state());
        assertEquals(1, host.flowSnapshot().orElseThrow().pinClaims().entries().size());
        var writer = writer(42); waitOnPin(writer);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(host, writer));
        assertEquals(packet(42), host.flowSnapshot().orElseThrow().state().locals().field("received"));
    }
    @Test void physicalPairPathCannotTruncateACohortAndDimensionHistoryStoresEveryParticipant() {
        var reader = reader(); reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true); var writer = writer(42);
        waitOnPin(reader); waitOnPin(writer); var before = List.of(reader.write(), writer.write());
        var history = new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData();
        var a = access(reader, history); var b = access(writer, history);
        assertEquals(XBusExchange.Status.UNSUPPORTED, a.exchange(b));
        assertEquals(before, List.of(reader.write(), writer.write()));
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        assertEquals(Optional.empty(), history.observe(List.of(reader.flowSnapshot().orElseThrow(), writer.flowSnapshot().orElseThrow()), 10, 100));
        var saved = history.save(new net.minecraft.nbt.CompoundTag(), null);
        assertEquals(3, saved.getInt("Version"));
        assertEquals(1, saved.getList("Claims", 10).getCompound(0).getList("OtherReaders", 10).size());
    }
    static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess access(ComputerBlockHost host,
            com.nobodiiiii.createbiotech.content.biocomputing.network.XBusHistorySavedData history) {
        var observed = com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState.of(host.flowSnapshot().orElseThrow(), LIMITS.pins(), "x0").orElseThrow();
        return new com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess(host, observed, () -> LIMITS, Optional::empty)
            .historical(history, () -> 10, () -> 100);
    }
    @Test void resettingOneParticipantRetiresTheOldCohortRatherThanReplayingItsPayload() {
        var writer = writer(42); var a = reader(); var b = reader(); var all = List.of(writer, a, b);
        all.forEach(XBusBroadcastOwnerTest::waitOnPin); var beforeB = b.write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        writer.reset(LIMITS, true); var oldB = restore(beforeB);
        var before = List.of(writer.write(), a.write(), oldB.write());
        var bindings = List.of(binding(writer), binding(a), binding(oldB));
        assertEquals(XBusExchange.Status.RETIRED, ComputerBlockHost.exchangeGroup(bindings, bindings.getFirst()));
        assertEquals(before, List.of(writer.write(), a.write(), oldB.write()));
    }
    @Test void absentOriginalRecipientCannotBeReplacedDuringRecovery() {
        var writer = writer(42); var a = reader(); var b = reader(); var all = List.of(writer, a, b);
        all.forEach(XBusBroadcastOwnerTest::waitOnPin); var beforeWriter = writer.write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        var oldWriter = restore(beforeWriter); var late = reader(); waitOnPin(late);
        var partial = List.of(oldWriter, a, late); var before = partial.stream().map(ComputerBlockHost::write).toList();
        assertEquals(XBusExchange.Status.RECOVERY_REQUIRED, ComputerBlockHost.exchangeGroup(partial.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(oldWriter)));
        assertEquals(before, partial.stream().map(ComputerBlockHost::write).toList());
        var complete = List.of(oldWriter, a, b, late);
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(complete.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(oldWriter)));
        assertEquals(FlowRuntime.Status.WAITING_READ, late.flowSnapshot().orElseThrow().state().status());
    }
    @Test void groupSchemaRejectsDowngradesAndDuplicateParticipantsWithoutChangingOriginalSave() {
        var writer = writer(42); var reader = reader(); reader.startFlowTask(RECEIVE, EMPTY, LIMITS, true);
        waitOnPin(writer); waitOnPin(reader); assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        var tag = (net.minecraft.nbt.CompoundTag)reader.write();
        assertEquals(19, tag.getInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER));
        var old = tag.copy(); old.putInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER, 18);
        var rejected = new ComputerBlockHost(() -> {}); rejected.read(old); rejected.tick(1, LIMITS, true, Map.of());
        assertFalse(rejected.loaded()); assertEquals(old, rejected.write());
        var receipt = reader.flowSnapshot().orElseThrow().receipts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new XBusReceipt(receipt.writer(), receipt.reader(), receipt.payload(), List.of(), List.of(receipt.reader())));
        assertEquals(tag, reader.write());
    }
    @Test void pausedRecipientIsNotIncludedAndAnUnavailableParticipantCannotSilentlyDisappear() {
        var writer = writer(42); var a = reader(); var b = reader(); var all = List.of(writer, a, b);
        all.forEach(XBusBroadcastOwnerTest::waitOnPin); b.setPaused(true, LIMITS, true);
        var paused = b.write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        assertEquals(paused, b.write()); assertEquals(2, writer.flowSnapshot().orElseThrow().receipts().getFirst().positions().size());
        b.setPaused(false, LIMITS, true); assertEquals(FlowRuntime.Status.WAITING_READ, b.flowSnapshot().orElseThrow().state().status());
        var w2 = writer(99); waitOnPin(w2); var before = List.of(w2.write(), b.write());
        assertEquals(XBusExchange.Status.UNAVAILABLE, ComputerBlockHost.exchangeGroup(List.of(binding(w2),
            new ComputerBlockHost.XBusBinding(b, "x0", LIMITS, false)), binding(w2)));
        assertEquals(before, List.of(w2.write(), b.write()));
    }
    @Test void aRecipientTypeErrorRejectsAllCandidateSaves() {
        var writer = writer(42); var a = reader();
        var incompatible = new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT));
        var b = install(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", incompatible))), EMPTY);
        var all = List.of(writer, a, b); all.forEach(XBusBroadcastOwnerTest::waitOnPin);
        var before = all.stream().map(ComputerBlockHost::write).toList();
        assertEquals(XBusExchange.Status.TYPE_MISMATCH, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        assertEquals(before, all.stream().map(ComputerBlockHost::write).toList());
    }
    @Test void completedOldCohortDoesNotPermanentlyBlockNewTrafficAfterAWriterReset() {
        var writer = writer(42);
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("first", "x0", "first", PACKET),
            new FlowProgram.Read("second", "x0", "second", PACKET)));
        var a = install(program, EMPTY); var b = install(program, EMPTY); var all = List.of(writer, a, b);
        all.forEach(XBusBroadcastOwnerTest::waitOnPin);
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        writer.reset(LIMITS, true); writer.setPaused(false, LIMITS, true);
        for (var host : all) host.tick(6, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        assertEquals(packet(42), a.flowSnapshot().orElseThrow().state().locals().field("second"));
        assertEquals(packet(42), b.flowSnapshot().orElseThrow().state().locals().field("second"));
    }
    @Test void archiveLimitRejectsTheWholeCohortBeforeAnySaveChanges() {
        var writer = writer(42); var a = reader(); var b = reader(); var all = List.of(writer, a, b);
        all.forEach(XBusBroadcastOwnerTest::waitOnPin); var before = all.stream().map(ComputerBlockHost::write).toList();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerBlockHost.exchangeGroup(all.stream().map(XBusBroadcastOwnerTest::binding).toList(), binding(writer)));
        var tag = (net.minecraft.nbt.CompoundTag)b.write();
        long units = com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(tag.getCompound("Data"), LIMITS.archive()).storageUnits();
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units - 1);
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), archive, LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots()));
        var restored = before.stream().map(XBusBroadcastOwnerTest::restore).toList();
        var bindings = List.of(binding(restored.get(0)), binding(restored.get(1)), new ComputerBlockHost.XBusBinding(restored.get(2), "x0", tight, true));
        assertEquals(XBusExchange.Status.ARCHIVE_LIMIT, ComputerBlockHost.exchangeGroup(bindings, bindings.getFirst()));
        assertEquals(before, restored.stream().map(ComputerBlockHost::write).toList());
    }
    @Test void failingDirtyNotificationCannotLeaveOnlySomeOwnersPublishedOrCauseARetry() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var notified = new java.util.concurrent.atomic.AtomicInteger();
        var writer = new ComputerBlockHost(() -> { if (armed.get()) throw new IllegalStateException("after publication"); });
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", packet(42))), LIMITS, true); writer.setPaused(false, LIMITS, true);
        var a = new ComputerBlockHost(() -> { if (armed.get()) notified.incrementAndGet(); });
        var b = new ComputerBlockHost(() -> { if (armed.get()) notified.incrementAndGet(); });
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", PACKET)));
        for (var reader : List.of(a, b)) { reader.installFlow(program, EMPTY, LIMITS, true); reader.setPaused(false, LIMITS, true); }
        var all = List.of(writer, a, b); all.forEach(XBusBroadcastOwnerTest::waitOnPin); armed.set(true);
        var bindings = all.stream().map(XBusBroadcastOwnerTest::binding).toList();
        assertThrows(IllegalStateException.class, () -> ComputerBlockHost.exchangeGroup(bindings, bindings.getFirst()));
        assertEquals(2, notified.get());
        for (var reader : List.of(a, b)) assertEquals(packet(42), reader.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(XBusExchange.Status.NOT_READY, ComputerBlockHost.exchangeGroup(bindings, bindings.getFirst()));
    }
}
