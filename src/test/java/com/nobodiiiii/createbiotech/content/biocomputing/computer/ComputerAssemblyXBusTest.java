package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyXBusTest {
    private static final DataType.RecordType TYPE = new DataType.RecordType(Map.of("number", DataType.Scalar.INTEGER));
    private static final DataValue.RecordValue PAYLOAD = new DataValue.RecordValue(Map.of("number", new DataValue.IntegerValue(42)));
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void eitherOwnersRamLimitRejectsConsumptionWithoutLosingTheWaitingRegisters(boolean writerLimited) {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Write("send", "x0", "payload"))), new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE))), empty());
        start(writer); start(reader); var beforeWriter = writer.write(); var beforeReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        var completed = (writerLimited ? writer : reader).flowSnapshot().orElseThrow();
        long units = completed.initialInputs().storageUnits() + completed.state().controlUnits() + completed.control().storageUnits()
            + completed.receipts().stream().mapToLong(r -> r.value().storageUnits()).sum();
        writer = restore(beforeWriter); reader = restore(beforeReader);
        var tight = LIMITS.withMemoryUnits(units - 1);
        assertEquals(XBusExchange.Status.MEMORY_LIMIT, writer.exchange("x0", writerLimited ? tight : LIMITS, true,
            reader, "x0", writerLimited ? LIMITS : tight, true));
        assertEquals(beforeWriter, writer.write()); assertEquals(beforeReader, reader.write());
        assertTrue(writer.loaded()); assertTrue(reader.loaded());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"flow_version", "owner_version", "status", "operation", "work", "image", "early_output"})
    void incompatibleOrForgedWaitsRetainTheRawSaveAndNeverFallback(String mutation) {
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE))), empty()); start(reader);
        var raw = (CompoundTag)reader.write(); assertEquals(24, raw.getInt(FlowBlockNbtCodec.MARKER));
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        assertEquals(new DataValue.IntegerValue(25), flow.field("version"));
        switch (mutation) {
            case "flow_version" -> flow = patch(flow, "version", new DataValue.IntegerValue(24));
            case "owner_version" -> raw.putInt(FlowBlockNbtCodec.MARKER, 23);
            case "status" -> state = patch(state, "status", new DataValue.TextValue("WAITING_WRITE"));
            case "operation" -> state = patch(state, "operation", new DataValue.IntegerValue(99));
            case "work" -> state = patch(state, "work", new DataValue.IntegerValue(0));
            case "image" -> state = patch(state, "assembly", patch((DataValue.RecordValue)state.field("assembly"), "image", new DataValue.TextValue("0".repeat(64))));
            case "early_output" -> {
                var registers = (DataValue.RecordValue)state.field("assembly");
                state = patch(state, "assembly", patch(registers, "registers", new DataValue.RecordValue(Map.of("0", PAYLOAD))));
                state = patch(state, "locals", new DataValue.RecordValue(Map.of("received", PAYLOAD)));
            }
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", state)), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(raw, restored.write());
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void oneOldSideRepairsFromOriginalReceiptWithoutExecutingOrRedelivering(boolean oldReader) {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Write("send", "x0", "payload"))),
            new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE))), empty());
        start(writer); start(reader); var beforeWriter = writer.write(); var beforeReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        var completedWriter = writer.flowSnapshot(); var completedReader = reader.flowSnapshot();
        writer = restore(oldReader ? writer.write() : beforeWriter); reader = restore(oldReader ? beforeReader : reader.write());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        assertEquals(completedWriter, writer.flowSnapshot()); assertEquals(completedReader, reader.flowSnapshot());
        assertNotEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
    }
    @Test void readableProbeDoesNotConsumeAndNextReadUsesANewerPersistedIdentity() {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Write("send", "x0", "payload"))),
            new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x0", Optional.of("ready")),
            new FlowProgram.Read("receive", "x0", "received", TYPE, true))), empty());
        start(writer); start(reader); var heldWriter = writer.write(); long firstOperation = reader.flowSnapshot().orElseThrow().state().operation();
        assertEquals(XBusExchange.Status.READABLE, exchange(writer, reader));
        assertEquals(heldWriter, writer.write()); assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(XBusResult.value(null, false, false), reader.flowSnapshot().orElseThrow().state().locals().field("ready"));
        assertTrue(reader.flowSnapshot().orElseThrow().state().operation() > firstOperation);
        reader = restore(reader.write()); reader.tick(LIMITS, true);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        assertEquals(XBusResult.value(PAYLOAD, true, false), reader.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(2, reader.flowSnapshot().orElseThrow().state().work());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void pausedPeerFailureOrResultPreservesRegisterContinuationWithoutFakePayload(boolean result) {
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE, result))), empty());
        start(reader); var registers = reader.flowSnapshot().orElseThrow().state().assembly();
        assertEquals(XBusExchange.Status.PAUSED, reader.rejectPausedXBus("x0", LIMITS));
        var done = reader.flowSnapshot().orElseThrow();
        if (result) assertEquals(XBusResult.value(null, true, true), done.state().locals().field("received"));
        else {
            assertEquals(FlowRuntime.Status.FAILED, done.state().status()); assertEquals(registers, done.state().assembly());
            assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, done.state().diagnostic().orElseThrow().code());
            assertEquals(empty(), done.state().locals());
        }
        assertEquals(1, done.state().work()); assertTrue(done.receipts().isEmpty()); assertTrue(done.pinClaims().entries().isEmpty());
        assertEquals(done, restore(reader.write()).flowSnapshot().orElseThrow());
    }
    @Test void archiveLimitRejectsBothSidesBeforeConsumingAndCanResumeTheSameWait() {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Write("send", "x0", "payload"))),
            new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE))), empty());
        start(writer); start(reader); var oldWriter = writer.write(); var oldReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader)); var expected = reader.flowSnapshot();
        long units = ValueNbtCodec.decode(((CompoundTag)reader.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units - 1)));
        writer = restore(oldWriter); reader = restore(oldReader);
        assertEquals(XBusExchange.Status.ARCHIVE_LIMIT, writer.exchange("x0", LIMITS, true, reader, "x0", tight, true));
        assertEquals(oldWriter, writer.write()); assertEquals(oldReader, reader.write());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader)); assertEquals(expected, reader.flowSnapshot());
    }
    @Test void repeatedSourceWaitsHaveDistinctOperationsAndRecoverAtEveryInstructionBoundary() {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Write("send", "x0", "payload"), new FlowProgram.EndForever("end"))), new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("receive", "x0", "received", TYPE), new FlowProgram.EndForever("end"))), empty());
        writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true);
        long previous = -1;
        for (int iteration = 0; iteration < 4; iteration++) {
            for (int step = 0; step < 5 && writer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; step++) {
                writer.tick(LIMITS, true); reader.tick(LIMITS, true);
                writer = restore(writer.write()); reader = restore(reader.write());
            }
            long operation = writer.flowSnapshot().orElseThrow().state().operation(); assertTrue(operation > previous); previous = operation;
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            writer = restore(writer.write()); reader = restore(reader.write());
            assertEquals(PAYLOAD, reader.flowSnapshot().orElseThrow().state().locals().field("received"));
        }
        assertEquals(4, reader.flowSnapshot().orElseThrow().receipts().size());
    }
    @Test void twoAssemblyOwnersWaitRestoreAndConsumeExactlyOneRendezvous() {
        var writer = installed(new FlowProgram(Map.of("payload", TYPE), List.of(new FlowProgram.Write("send", "x0", "payload"))),
            new DataValue.RecordValue(Map.of("payload", PAYLOAD)));
        var reader = installed(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received", TYPE))), empty());
        start(writer); start(reader);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowSnapshot().orElseThrow().state().status());
        var waiting = reader.flowSnapshot().orElseThrow();
        reader.tick(LIMITS, true); assertEquals(waiting, reader.flowSnapshot().orElseThrow());
        writer = restored(writer); reader = restored(reader);
        assertEquals(waiting, reader.flowSnapshot().orElseThrow());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        var sent = writer.flowSnapshot().orElseThrow(); var received = reader.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, sent.state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, received.state().status());
        assertEquals(PAYLOAD, received.state().locals().field("received"));
        assertEquals(1, sent.state().work()); assertEquals(1, received.state().work());
        assertEquals(1, sent.state().assembly().orElseThrow().pc());
        assertEquals(1, received.state().assembly().orElseThrow().pc());
        assertTrue(received.state().expression().isEmpty());
        assertEquals(sent, restored(writer).flowSnapshot().orElseThrow());
        assertEquals(received, restored(reader).flowSnapshot().orElseThrow());
        assertNotEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        assertEquals(PAYLOAD, reader.flowSnapshot().orElseThrow().state().locals().field("received"));
    }
    private static DataValue.RecordValue empty() { return new DataValue.RecordValue(Map.of()); }
    private static ComputerBlockHost installed(FlowProgram program, DataValue.RecordValue inputs) {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program.withAssembly(), inputs, LIMITS, true); return host;
    }
    private static void start(ComputerBlockHost host) { host.setPaused(false, LIMITS, true); host.tick(LIMITS, true); }
    private static ComputerBlockHost restored(ComputerBlockHost host) {
        return restore(host.write());
    }
    private static ComputerBlockHost restore(Tag raw) {
        var next = new ComputerBlockHost(() -> {}); next.read(raw); next.synchronize(LIMITS, true);
        assertTrue(next.loaded(), () -> next.diagnostic().toString()); return next;
    }
    private static XBusExchange.Status exchange(ComputerBlockHost writer, ComputerBlockHost reader) {
        return writer.exchange("x0", LIMITS, true, reader, "x0", LIMITS, true);
    }
}
