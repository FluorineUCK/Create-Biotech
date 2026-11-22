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

class FlowManagedXBusTest {
    private static final ModuleDefinition.Key COPY = new ModuleDefinition.Key("copy", 1);
    private static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    private static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1),
        FlowArchiveTest.ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3));
    private static DataValue.RecordValue value(long value) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value)));
    }
    private static FlowProgram reader() {
        return new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", PACKET),
            new FlowProgram.Procedure("definition", COPY, PACKET.fields(), Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("child_wait", "value"), new FlowProgram.EndProcedure("return")));
    }
    private static ComputerBlockHost install(FlowProgram program, DataValue.RecordValue inputs) {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, inputs, LIMITS, true);
        owner.setPaused(false, LIMITS, true); return owner;
    }
    private static ComputerBlockHost writer() {
        return install(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", value(42))));
    }
    private static FlowRuntime.Checkpoint main(ComputerBlockHost owner) { return owner.flowSnapshot().orElseThrow().state(); }
    private static XBusExchange.Status exchange(ComputerBlockHost reader, ComputerBlockHost writer) {
        return reader.exchange("x0", LIMITS, true, writer, "x0", LIMITS, true);
    }

    @Test void waitingMainCanRunATaskAndExchangeWithoutReplacingItsResultOrCreatingAnotherBudget() {
        var reader = install(reader(), EMPTY); reader.tick(1, LIMITS, true, Map.of());
        var waiting = main(reader); assertEquals(FlowRuntime.Status.WAITING_READ, waiting.status());
        UUID task = reader.startFlowTask(COPY, value(0), LIMITS, true);
        assertEquals(0, reader.advanceFlowRound(1, Map.of(), LIMITS, true), "Admission cannot renew this Computer's allowance");
        reader.tick(2, LIMITS, true, Map.of()); reader.tick(3, LIMITS, true, Map.of());
        var completed = reader.flowTask(task).orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, completed.status()); assertEquals(waiting, main(reader));
        var writer = writer(); writer.tick(3, LIMITS, true, Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        assertEquals(value(42), main(reader).locals().field("received"));
        assertEquals(completed, reader.flowTask(task).orElseThrow(), "The XBus successor save must retain other tasks");
        var readerId = main(reader).computer();
        assertEquals(new FlowControl.Frontier(task, 0, -1, false), writer.flowSnapshot().orElseThrow().control().peers().get(readerId).slots().get(1),
            "An actual handoff observes bounded peer task identities instead of treating every task UUID as a new Computer");
        var savedWriter = writer.write(); var restoredWriter = restore(savedWriter);
        assertEquals(writer.flowSnapshot(), restoredWriter.flowSnapshot());
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size());
        reader.claimFlowTask(task, LIMITS, true);
        assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size(), "Releasing a UI result cannot erase physical handoff evidence");
        writer.reset(LIMITS, true); var history = writer.flowSnapshot().orElseThrow().control();
        var originalProgram = writer.flowSnapshot().orElseThrow().program();
        var initialInputs = writer.flowSnapshot().orElseThrow().initialInputs();
        writer.install(writer.flowSnapshot().orElseThrow().program().expressions(),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var event = (net.minecraft.nbt.CompoundTag) writer.write();
        assertEquals(3, event.getInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.EventHostNbtCodec.MARKER));
        assertEquals(history, com.nobodiiiii.createbiotech.content.biocomputing.persistence.EventHostNbtCodec.decode(event, LIMITS.archive()).control());
        var oldFormat = event.copy(); oldFormat.putInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.EventHostNbtCodec.MARKER, 2);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> com.nobodiiiii.createbiotech.content.biocomputing.persistence.EventHostNbtCodec.decode(oldFormat, LIMITS.archive()));
        var reloadedEvent = restore(event); reloadedEvent.installFlow(originalProgram, initialInputs, LIMITS, true);
        assertEquals(history.peers(), reloadedEvent.flowSnapshot().orElseThrow().control().peers());
        assertEquals(history.generation() + 1, reloadedEvent.flowSnapshot().orElseThrow().control().generation());
    }

    private static ComputerBlockHost restore(net.minecraft.nbt.Tag saved) {
        var owner = new ComputerBlockHost(() -> {}); owner.read(saved); owner.setPaused(false, LIMITS, true); return owner;
    }
    @Test void handoffChargesPeerTaskWitnessesBeforePublishingEitherSide() {
        var reader = install(reader(), EMPTY); var writer = writer();
        reader.tick(1, LIMITS, true, Map.of()); writer.tick(1, LIMITS, true, Map.of());
        reader.startFlowTask(COPY, value(0), LIMITS, true);
        var readerBefore = reader.write(); var writerBefore = writer.write();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        var completed = writer.flowSnapshot().orElseThrow();
        long memory = completed.initialInputs().storageUnits() + completed.control().storageUnits()
            + completed.receipts().getFirst().value().storageUnits()
            + FlowRuntime.restore(completed.program(), completed.state(), LIMITS.hardware()).retainedUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, memory - 1, 1), LIMITS.archive(), LIMITS.mailbox(),
            LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots()));
        var oldReader = restore(readerBefore); var oldWriter = restore(writerBefore);
        assertEquals(XBusExchange.Status.MEMORY_LIMIT, oldWriter.exchange("x0", tight, true, oldReader, "x0", LIMITS, true));
        assertEquals(readerBefore, oldReader.write()); assertEquals(writerBefore, oldWriter.write());
        assertTrue(oldReader.loaded()); assertTrue(oldWriter.loaded());
        assertEquals(XBusExchange.Status.EXCHANGED, oldWriter.exchange("x0", LIMITS, true, oldReader, "x0", LIMITS, true));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eitherOlderHandoffSaveCanRecoverWithoutReplayingOrErasingTheRetainedTask(boolean oldReader) {
        var reader = install(reader(), EMPTY); var writer = writer();
        reader.tick(1, LIMITS, true, Map.of()); writer.tick(1, LIMITS, true, Map.of());
        var id = reader.startFlowTask(COPY, value(0), LIMITS, true);
        reader.tick(2, LIMITS, true, Map.of()); reader.tick(3, LIMITS, true, Map.of());
        var beforeReader = reader.write(); var beforeWriter = writer.write();
        var task = reader.flowTask(id).orElseThrow();
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(reader, writer));
        var expectedReader = reader.flowSnapshot().orElseThrow(); var expectedWriter = writer.flowSnapshot().orElseThrow();
        var recoveredReader = restore(oldReader ? beforeReader : reader.write());
        var recoveredWriter = restore(oldReader ? writer.write() : beforeWriter);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(recoveredReader, recoveredWriter));
        assertEquals(expectedReader, recoveredReader.flowSnapshot().orElseThrow());
        assertEquals(expectedWriter, recoveredWriter.flowSnapshot().orElseThrow());
        assertEquals(task, recoveredReader.flowTask(id).orElseThrow());
        assertEquals(XBusExchange.Status.NOT_READY, exchange(recoveredReader, recoveredWriter));
        recoveredReader.claimFlowTask(id, LIMITS, true);
        assertEquals(expectedReader.receipts(), recoveredReader.flowSnapshot().orElseThrow().receipts());
    }

    @Test void pauseAndUnavailablePeerLeaveTheWaitingMainAndIndependentTaskUntouched() {
        var reader = install(reader(), EMPTY); var writer = writer();
        reader.tick(1, LIMITS, true, Map.of()); writer.tick(1, LIMITS, true, Map.of());
        var id = reader.startFlowTask(COPY, value(5), LIMITS, true); reader.tick(2, LIMITS, true, Map.of());
        reader.setPaused(true, LIMITS, true); var paused = reader.write();
        reader.tick(100, LIMITS, true, Map.of()); assertEquals(paused, reader.write());
        assertEquals(XBusExchange.Status.PAUSED, exchange(reader, writer)); assertEquals(paused, reader.write());
        var restored = restore(paused); var before = restored.flowSnapshot().orElseThrow();
        assertEquals(XBusExchange.Status.UNAVAILABLE, restored.exchange("x0", LIMITS, true, writer, "x0", LIMITS, false));
        assertEquals(before, restored.flowSnapshot().orElseThrow());
        restored.tick(1000, LIMITS, true, Map.of());
        assertEquals(4, restored.flowTask(id).orElseThrow().sleepTicks(), "No unloaded-time catch-up or remote clock");
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(restored, writer));
        assertEquals(4, restored.flowTask(id).orElseThrow().sleepTicks());
    }

    @Test void nestedPinTaskKeepsItsExplicitBindingsSeparateFromTheMainAndPureTask() {
        var helper = new ModuleDefinition.Key("helper", 1); var blocked = new ModuleDefinition.Key("blocked", 1);
        var program = new FlowProgram(PACKET.fields(), List.of(
            new FlowProgram.Invoke("main_call", COPY, new FlowCallBindings(Map.of("value", "value"), Map.of("result", "main_result"))),
            new FlowProgram.Read("main_read", "x0", "packet", PACKET),
            new FlowProgram.Procedure("copy", COPY, PACKET.fields(), Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "copy_result")),
            new FlowProgram.Invoke("nested_pure", helper, new FlowCallBindings(Map.of("value", "value"), Map.of("value", "copy_result"))),
            new FlowProgram.EndProcedure("copy_end"),
            new FlowProgram.Procedure("helper", helper, PACKET.fields(), PACKET.fields(), Map.of("value", "value")),
            new FlowProgram.EndProcedure("helper_end"),
            new FlowProgram.Procedure("blocked", blocked, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Invoke("nested_pin", new ModuleDefinition.Key("pin", 1), new FlowCallBindings(Map.of(), Map.of())),
            new FlowProgram.EndProcedure("blocked_end"),
            new FlowProgram.Procedure("pin", new ModuleDefinition.Key("pin", 1), Map.of(), Map.of(), Map.of()),
            new FlowProgram.AwaitReadable("pin_wait", "x1"), new FlowProgram.EndProcedure("pin_end")));
        var owner = install(program, value(7));
        var child = owner.startFlowTask(COPY, value(42), LIMITS, true);
        var pinTask = owner.startFlowTask(blocked, EMPTY, LIMITS, true);
        assertTrue(owner.loaded());
        for (int tick = 1; tick < 30; tick++) owner.tick(tick, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.WAITING_READ, main(owner).status());
        assertEquals(new DataValue.IntegerValue(7), main(owner).locals().field("main_result"));
        assertEquals(new DataValue.IntegerValue(42), owner.flowTask(child).orElseThrow().locals().field("result"));
        assertEquals(FlowRuntime.Status.WAITING_READABLE, owner.flowTask(pinTask).orElseThrow().status());
        assertEquals(owner.flowSnapshot(), restore(owner.write()).flowSnapshot());
    }
}
