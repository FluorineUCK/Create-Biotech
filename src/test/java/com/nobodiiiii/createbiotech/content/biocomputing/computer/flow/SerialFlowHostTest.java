package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SerialFlowHostTest {
    @Test void procedureSerialEffectsAndCallerFramesSurviveEachCommitWithoutConsumingTwice() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var bytes = SerialRecordCodec.encode(RECORD, new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE));
        device.attachments().getFirst().receiver().offer(bytes);
        var key = new ModuleDefinition.Key("send_and_receive", 1);
        var packet = DataType.typeOf(RECORD);
        var program = new FlowProgram(Map.of("packet", packet), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("packet", "packet"), Map.of("sent", "sent", "received", "answer"))),
            new FlowProgram.Procedure("definition", key, Map.of("packet", packet), Map.of("sent", SerialResult.SEND, "received", SerialResult.RECEIVE), Map.of("sent", "sent", "received", "received")),
            new FlowProgram.SerialSend("send", "s0", "packet", "sent"), new FlowProgram.SerialReceive("receive", "s0", "received"),
            new FlowProgram.EndProcedure("return")));
        var inputs = new DataValue.RecordValue(Map.of("packet", RECORD));
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {}, pin -> {}, device);
        // The compatibility procedure explicitly declares a receive result containing native ANY.
        // Ordinary serial hardware may send a concrete frame; it does not grant this signature.
        assertTrue(assertThrows(FlowPins.Unavailable.class, () -> FlowPins.validate(program, LIMITS))
            .getMessage().contains("ISA-1b"));
        var master = ParserHardwareFixture.withMasterParser(LIMITS);
        host.installFlow(program, inputs, master, true);
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertTrue(i < 10); long before = host.flowSnapshot().orElseThrow().state().work(); host.step(master, true);
            var snapshot = host.flowSnapshot().orElseThrow(); assertEquals(before + 1, snapshot.state().work());
            var saved = host.write(); var hardware = device.write(); host.unload(); device.read(hardware);
            host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {}, pin -> {}, device);
            host.read(saved); host.tick(master, true);
            assertEquals(snapshot, host.flowSnapshot().orElseThrow()); assertEquals(hardware, device.write());
        }
        var state = host.flowSnapshot().orElseThrow().state();
        assertEquals(SerialResult.send("queued"), state.locals().field("sent"));
        assertEquals(SerialResult.receive("received", RECORD), state.locals().field("answer"));
        assertTrue(state.calls().frames().isEmpty()); assertEquals(inputs.field("packet"), state.locals().field("packet"));
        // Admission is not emission. The device's existing reload rule interrupts unsent bytes; no call retry is implied.
        assertEquals("interrupted", device.status("s0").transmit()); assertEquals("reloaded", device.status("s0").failure());
        assertEquals(0, device.status("s0").receive().frames());
    }
    @Test void faultClaimIsVersionedAndCannotBeSmuggledIntoOldReadOnlyDocumentsOrCheckpoints() {
        var workspace = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        var body = new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(Map.of(),
            List.of(new FlowProgram.SerialStatus("claim", "s0", "status", true)), new DataValue.RecordValue(Map.of()));
        workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(15), encoded.field("version"));
        var old = new LinkedHashMap<>(encoded.fields()); old.put("version", new DataValue.IntegerValue(14));
        assertThrows(ArchiveException.class, () -> com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), body.program(document.archive()), body.initial(), LIMITS, FlowControl.INITIAL, device());
        var owner = host.write(); assertEquals(14, owner.getInt(FlowBlockNbtCodec.MARKER));
        var olderOwner = owner.copy(); olderOwner.putInt(FlowBlockNbtCodec.MARKER, 13);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(olderOwner, ARCHIVE));
        var saved = host.snapshot().orElseThrow();
        var flow = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(saved.program(), saved.state()), ARCHIVE), ARCHIVE);
        assertEquals(new DataValue.IntegerValue(15), flow.field("version"));
        var olderFlow = new LinkedHashMap<>(flow.fields()); olderFlow.put("version", new DataValue.IntegerValue(14));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(olderFlow), ARCHIVE), ARCHIVE));
        var restored = new FlowBlockHost(() -> {}, owner, device()); assertTrue(restored.synchronize(LIMITS, true));
        assertEquals(saved, restored.snapshot().orElseThrow());
        assertEquals(0, restored.advanceRound(Map.of()), "Loading a paused claim never executes it");
    }
    @Test void explicitFaultClaimCopiesThenClearsOnlyLocalFlagsWhileOrdinaryStatusAndPauseKeepThem() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var receiver = device.attachments().getFirst().receiver();
        var bytes = SerialRecordCodec.encode(RECORD, new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE));
        receiver.offer(bytes); receiver.offer(bytes); receiver.offer(bytes);
        var before = device.write(); var observed = SerialResult.status(device.status("s0"));
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialStatus("view", "s0", "view"),
            new FlowProgram.SerialStatus("claim", "s0", "claimed", true)));
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program, new DataValue.RecordValue(Map.of()), LIMITS, FlowControl.INITIAL, device);
        assertEquals(0, host.advanceRound(Map.of())); assertEquals(before, device.write());
        host.setPaused(false); assertEquals(1, host.advanceRound(Map.of()));
        assertEquals(observed, host.snapshot().orElseThrow().state().locals().field("view"));
        assertEquals(before, device.write(), "Ordinary status must never acknowledge errors");
        assertEquals(1, host.advanceRound(Map.of()));
        assertTrue(receiver.status().faults().isEmpty(), "Explicit claim must release the local error latch");
        assertEquals(observed, host.snapshot().orElseThrow().state().locals().field("claimed"));
        assertEquals(2, receiver.status().frames()); assertArrayEquals(bytes, receiver.peek().orElseThrow());
        assertEquals("idle", device.status("s0").transmit());
        assertEquals(2, host.snapshot().orElseThrow().state().work());
        var completed = host.write(); device.read(device.write());
        var restored = new FlowBlockHost(() -> {}, completed, device); assertTrue(restored.synchronize(LIMITS, true));
        device.attachments().getFirst().receiver().offer(bytes); // A later incident, not part of the already claimed snapshot.
        var later = device.write(); assertEquals(0, restored.advanceRound(Map.of()));
        assertEquals(later, device.write(), "Reload cannot acknowledge a new incident by replaying an old instruction");
        assertEquals(completed, restored.write());
    }
    @Test void unsaveableFaultClaimKeepsBothFaultFlagsAndContinuationForAnExplicitLaterAttempt() {
        for (boolean archiveLimit : List.of(false, true)) {
            var device = device(); device.configure("s0", Direction.EAST, true);
            device.attachments().getFirst().receiver().offer(new byte[0]);
            var inputs = new DataValue.RecordValue(Map.of());
            var program = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialStatus("claim", "s0", "observed", true)));
            var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program, inputs, LIMITS, FlowControl.INITIAL, device);
            host.setPaused(false); var saved = host.snapshot().orElseThrow();
            long units = saved.state().controlUnits() + inputs.storageUnits() + saved.control().storageUnits() + 2;
            long encoded = ValueNbtCodec.decode(host.write().getCompound("Data"), ARCHIVE).storageUnits();
            var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, archiveLimit ? 1000000 : units + 1, 8),
                archiveLimit ? new ArchiveLimits(100000, 1000000, encoded + 1) : ARCHIVE, LIMITS.mailbox(), LIMITS.pins(), SERIAL));
            assertTrue(host.synchronize(tight, true)); var before = host.write(); var hardwareBefore = device.write();
            assertEquals(0, host.advanceRound(Map.of())); assertEquals(before, host.write()); assertEquals(hardwareBefore, device.write());
            assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
            assertTrue(host.synchronize(LIMITS, true)); assertEquals(1, host.advanceRound(Map.of()));
            assertTrue(device.status("s0").receive().faults().isEmpty());
            assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("empty_payload"))),
                ((DataValue.RecordValue)host.snapshot().orElseThrow().state().locals().field("observed")).field("receive_faults"));
        }
    }
    @Test void nativeReceiveSurvivesPauseSaveAndDocumentRoundTripAndPublishesOnce() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var value = com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.parse(
            com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat.Kind.NBT, "{n:12s,samples:[B;1b,2b]}");
        var bounds = new NativeNbtCodec.Limits(4096, 1048576, ARCHIVE);
        device.attachments().getFirst().receiver().offer(NativeNbtCodec.encode((DataValue.NbtValue)value, bounds));
        var workspace = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        var body = new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(Map.of(),
            List.of(new FlowProgram.SerialReceive("read", "s0", "packet", true)), new DataValue.RecordValue(Map.of()));
        workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(14), encoded.field("version"));
        assertEquals(document, com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(encoded));
        var old = new LinkedHashMap<>(encoded.fields()); old.put("version", new DataValue.IntegerValue(13));
        assertThrows(ArchiveException.class, () -> com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(old)));
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), body.program(document.archive()), body.initial(), LIMITS, FlowControl.INITIAL, device);
        assertEquals(0, host.advanceRound(Map.of())); assertEquals(1, device.status("s0").receive().frames());
        var saved = host.write(); var savedDevice = device.write();
        assertEquals(13, saved.getInt(FlowBlockNbtCodec.MARKER));
        var downgraded = saved.copy(); downgraded.putInt(FlowBlockNbtCodec.MARKER, 12);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(downgraded, ARCHIVE));
        device.read(savedDevice); host = new FlowBlockHost(() -> {}, saved, device);
        assertTrue(host.synchronize(LIMITS, true)); host.setPaused(false);
        assertEquals(1, host.advanceRound(Map.of()));
        assertEquals(SerialResult.receive("received", value), host.snapshot().orElseThrow().state().locals().field("packet"));
        assertEquals(0, device.status("s0").receive().frames());
        var complete = host.write(); host = new FlowBlockHost(() -> {}, complete, device);
        assertTrue(host.synchronize(LIMITS, true)); assertEquals(0, host.advanceRound(Map.of()));
        assertEquals(complete, host.write());
    }
    @Test void localXBusConversionIsExplicitAndOnlyContinuesTheSerialOwner() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var source = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of("packet", DataType.typeOf(RECORD)),
            List.of(new FlowProgram.Write("write", "x0", "packet"))), new DataValue.RecordValue(Map.of("packet", RECORD)), LIMITS);
        var converter = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of(), List.of(
            new FlowProgram.Read("read", "x1", "packet", (DataType.RecordType)DataType.typeOf(RECORD)),
            new FlowProgram.SerialSend("send", "s0", "packet", "sent"))), new DataValue.RecordValue(Map.of()), LIMITS, FlowControl.INITIAL, device);
        source.setPaused(false); converter.setPaused(false);
        assertEquals(1, source.advanceRound(Map.of())); assertEquals(1, converter.advanceRound(Map.of()));
        assertEquals("idle", device.status("s0").transmit());
        assertEquals(XBusExchange.Status.EXCHANGED, source.exchange("x0", converter, "x1"));
        assertEquals("idle", device.status("s0").transmit(), "XBus receipt is not a hidden serial forwarding instruction");
        var sourceSave = source.write(); var localSave = converter.write();
        converter = new FlowBlockHost(() -> {}, localSave, device); assertTrue(converter.synchronize(LIMITS, true));
        assertEquals(1, converter.advanceRound(Map.of()));
        assertEquals(SerialResult.send("queued"), converter.snapshot().orElseThrow().state().locals().field("sent"));
        assertEquals(sourceSave, source.write(), "Forwarding does not borrow source RAM or its instruction budget");
        assertEquals("queued", device.status("s0").transmit());
        assertEquals(0, converter.advanceRound(Map.of()), "A finished operation cannot enqueue again");
    }
    @Test void serialDocumentsAndContinuationsRejectForgedOlderSchemasWithoutExecuting() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var body = new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(Map.of(),
            List.of(new FlowProgram.SerialReceive("read", "s0", "packet")), new DataValue.RecordValue(Map.of()));
        var workspace = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        workspace.setFlow(Optional.of(body)); var document = workspace.snapshot();
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(11), encoded.field("version"));
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", new DataValue.IntegerValue(10));
        assertThrows(ArchiveException.class, () -> com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), body.program(document.archive()), body.initial(), LIMITS, FlowControl.INITIAL, device);
        var saved = host.snapshot().orElseThrow(); var owner = host.write();
        assertEquals(11, owner.getInt(FlowBlockNbtCodec.MARKER)); owner.putInt(FlowBlockNbtCodec.MARKER, 10);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(owner, ARCHIVE));
        var snapshot = ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(saved.program(), saved.state()), ARCHIVE), ARCHIVE);
        var downgraded = new LinkedHashMap<>(((DataValue.RecordValue)snapshot).fields());
        assertEquals(new DataValue.IntegerValue(12), downgraded.get("version")); downgraded.put("version", new DataValue.IntegerValue(11));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(downgraded), ARCHIVE), ARCHIVE));
        assertEquals("idle", device.status("s0").transmit()); assertEquals(0, saved.state().work());
    }
    @Test void unsaveableOrOversizedContinuationNeverConsumesOrQueuesAFrame() {
        for (boolean receiving : List.of(false, true)) for (boolean archiveLimit : List.of(false, true)) {
            var device = device(); device.configure("s0", Direction.EAST, true);
            var inputs = receiving ? new DataValue.RecordValue(Map.of()) : new DataValue.RecordValue(Map.of("packet", RECORD));
            var program = receiving ? new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "result")))
                : new FlowProgram(Map.of("packet", DataType.typeOf(RECORD)), List.of(new FlowProgram.SerialSend("send", "s0", "packet", "result")));
            if (receiving) device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE)));
            var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program, inputs, LIMITS, FlowControl.INITIAL, device);
            host.setPaused(false); var snapshot = host.snapshot().orElseThrow();
            long base = snapshot.state().controlUnits() + inputs.storageUnits() + snapshot.control().storageUnits() + 2;
            long savedUnits = ValueNbtCodec.decode(host.write().getCompound("Data"), ARCHIVE).storageUnits();
            var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, archiveLimit ? 1000000 : base + 1, 8),
                archiveLimit ? new ArchiveLimits(100000, 1000000, savedUnits + 1) : ARCHIVE, LIMITS.mailbox(), LIMITS.pins(), SERIAL));
            assertTrue(host.synchronize(tight, true)); var before = host.write(); var beforeDevice = device.write();
            assertEquals(0, host.advanceRound(Map.of()));
            assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
            assertEquals(before, host.write()); assertEquals(beforeDevice, device.write());
            assertTrue(host.synchronize(LIMITS, true)); assertEquals(1, host.advanceRound(Map.of()));
            assertEquals(0, host.advanceRound(Map.of()));
            assertEquals(0, device.status("s0").receive().frames());
            assertEquals(receiving ? "idle" : "queued", device.status("s0").transmit());
        }
    }
    @Test void failedDirtyCallbackCannotReplayAnAlreadyPublishedReceiveAndClosedPortCanDrainCompleteFrames() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        device.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(RECORD, new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE)));
        device.configure("s0", Direction.EAST, false);
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialReceive("receive", "s0", "result")));
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var host = FlowBlockHost.installed(() -> { if (fail.get()) throw new IllegalStateException("dirty failed"); }, UUID.randomUUID(),
            program, new DataValue.RecordValue(Map.of()), LIMITS, FlowControl.INITIAL, device);
        host.setPaused(false); fail.set(true);
        assertThrows(IllegalStateException.class, () -> host.advanceRound(Map.of()));
        assertEquals(FlowRuntime.Status.COMPLETE, host.snapshot().orElseThrow().state().status());
        assertEquals(SerialResult.receive("received", RECORD), host.snapshot().orElseThrow().state().locals().field("result"));
        assertEquals(0, device.status("s0").receive().frames());
        var restored = new FlowBlockHost(() -> {}, host.write(), device); assertTrue(restored.synchronize(LIMITS, true));
        assertEquals(0, restored.advanceRound(Map.of()));
    }
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 10000000);
    static final SerialHardware SERIAL = new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 2, 8192)));
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 1000000, 8), ARCHIVE,
        com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SERIAL));
    static final DataValue.RecordValue RECORD = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37)));
    static SerialDevice device() { return new SerialDevice(() -> SERIAL, () -> true,
        () -> new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE), () -> {}); }
    @Test void publicSerialStepsConsumeOwnBudgetWithoutWaitingForPhysicalEmissionOrBusinessReply() {
        var device = device(); device.configure("s0", Direction.EAST, true);
        var program = new FlowProgram(Map.of("packet", DataType.typeOf(RECORD)), List.of(
            new FlowProgram.SerialSend("send", "s0", "packet", "sent"),
            new FlowProgram.SerialStatus("state", "s0", "status"),
            new FlowProgram.SerialReceive("receive", "s0", "received")));
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("packet", RECORD)), LIMITS, FlowControl.INITIAL, device);
        assertEquals(0, host.advanceRound(Map.of())); assertEquals("idle", device.status("s0").transmit());
        host.step(Map.of()); assertEquals("queued", device.status("s0").transmit());
        assertTrue(host.snapshot().orElseThrow().state().paused()); assertEquals(1, host.snapshot().orElseThrow().state().work());
        var saved = host.write(); var restored = new FlowBlockHost(() -> {}, saved, device);
        assertTrue(restored.synchronize(LIMITS, true)); restored.setPaused(false);
        assertEquals(1, restored.advanceRound(Map.of())); assertEquals(1, restored.advanceRound(Map.of()));
        var state = restored.snapshot().orElseThrow().state(); assertEquals(FlowRuntime.Status.COMPLETE, state.status());
        assertEquals(3, state.work()); assertEquals(SerialResult.send("queued"), state.locals().field("sent"));
        assertEquals(SerialResult.receive("empty", null), state.locals().field("received"));
        assertEquals("queued", device.status("s0").transmit());
    }
}
