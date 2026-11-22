package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusExchangeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerFlowRetirementTest {
    @Test void exhaustedGenerationRejectsResetWithoutPoisoningTheCurrentValidProgram() {
        var original = new ComputerBlockHost(() -> {}); start(original, writer(), INPUTS); var saved = original.flowSnapshot().orElseThrow();
        var host = restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), saved.state(), List.of(),
            new FlowControl(Long.MAX_VALUE, Map.of())), LIMITS.archive()));
        var raw = host.write();
        var error = assertThrows(ComputationException.class, () -> host.reset(LIMITS, true));
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, error.code());
        assertEquals(raw, host.write()); assertTrue(host.loaded()); assertTrue(host.diagnostic().isEmpty());
    }
    @Test void repeatedSaveResetAndNewExchangeKeepOnlyOneBoundedFrontierForTheSamePeer() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); long size = -1;
        for (int i = 0; i < 32; i++) {
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader)); writer.reset(LIMITS, true); reader.reset(LIMITS, true);
            var saved = writer.flowSnapshot().orElseThrow(); assertEquals(i + 1, saved.control().generation());
            assertEquals(1, saved.control().peers().size()); assertTrue(saved.receipts().isEmpty());
            if (size == -1) size = saved.control().storageUnits(); else assertEquals(size, saved.control().storageUnits());
            writer = restore(writer.write()); reader = restore(reader.write());
            writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true); writer.tick(LIMITS, true); reader.tick(LIMITS, true);
        }
    }
    @Test void failedResetCannotDiscardTheLastOwnedSaveAndCanBeRetriedAfterCapacityReturns() {
        var host = new ComputerBlockHost(() -> {}); start(host, writer(), INPUTS); var before = host.flowSnapshot(); var raw = host.write();
        var saved = before.orElseThrow();
        var tight = new ComputerHostLimits(new ResourceLimits(1000, saved.initialInputs().storageUnits() + saved.state().controlUnits() + 2, 1), LIMITS.archive());
        var error = assertThrows(ComputerBlockHost.HostException.class, () -> host.reset(tight, true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, error.code()); assertEquals(raw, host.write()); assertEquals(before, host.flowSnapshot());
        host.reset(LIMITS, true); assertTrue(host.flowSnapshot().orElseThrow().state().paused()); assertEquals(1, host.flowSnapshot().orElseThrow().control().generation());
    }
    @Test void eventModeChargesHistoryAndRollsBackAnUnencodableExecutionWithoutErasingIt() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); exchange(writer, reader); writer.reset(LIMITS, true);
        var control = writer.flowSnapshot().orElseThrow().control(); writer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var raw = (CompoundTag) writer.write(); var snapshot = writer.snapshot().orElseThrow(); var usage = snapshot.program().compile(LIMITS.hardware()).usage();
        long used = control.storageUnits() + usage.constantUnits() + usage.initialMemoryUnits() + snapshot.machine().memory().storageUnits();
        writer.tick(new ComputerHostLimits(new ResourceLimits(1000, used - 1, 1), LIMITS.archive()), true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, writer.diagnostic().orElseThrow().code()); assertEquals(raw, writer.write());
        long units = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive()).storageUnits();
        var tightArchive = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, units));
        assertThrows(ComputerBlockHost.HostException.class, () -> writer.begin(EMPTY, tightArchive, true)); assertEquals(raw, writer.write());
        assertEquals(control, EventHostNbtCodec.decode((CompoundTag) writer.write(), LIMITS.archive()).control());
        writer.begin(EMPTY, LIMITS, true);
        for (int i = 0; writer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 50); writer.tick(LIMITS, true);
        }
        assertEquals(control, EventHostNbtCodec.decode((CompoundTag) writer.write(), LIMITS.archive()).control());
    }
    @Test void versionTwoReceiptsMigrateToGenerationZeroAndRetireNormally() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); exchange(writer, reader);
        var saved = writer.flowSnapshot().orElseThrow(); var raw = (CompoundTag) writer.write();
        var fields = new LinkedHashMap<>(((DataValue.RecordValue) ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive())).fields()); fields.remove("control");
        var receipt = new LinkedHashMap<>(saved.receipts().getFirst().value().fields());
        for (var key : List.of("writer", "reader")) { var position = new LinkedHashMap<>(((DataValue.RecordValue) receipt.get(key)).fields()); position.remove("generation"); receipt.put(key, new DataValue.RecordValue(position)); }
        fields.put("receipts", new DataValue.ListValue(List.of(new DataValue.RecordValue(receipt))));
        raw.putInt(FlowBlockNbtCodec.MARKER, 2); raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()));
        var restored = restore(raw); assertEquals(saved, restored.flowSnapshot().orElseThrow()); assertEquals(raw, restored.write());
        restored.reset(LIMITS, true); assertEquals(1, restored.flowSnapshot().orElseThrow().control().generation()); assertEquals(3, ((CompoundTag) restored.write()).getInt(FlowBlockNbtCodec.MARKER));
    }
    @Test void malformedControlAndFutureEventHistoryStayOpaqueRatherThanResettingTheGeneration() {
        var host = new ComputerBlockHost(() -> {}); start(host, writer(), INPUTS); host.reset(LIMITS, true); var raw = (CompoundTag) host.write();
        var fields = new LinkedHashMap<>(((DataValue.RecordValue) ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive())).fields());
        var control = new LinkedHashMap<>(((DataValue.RecordValue) fields.get("control")).fields()); control.put("generation", new DataValue.IntegerValue(-1));
        fields.put("control", new DataValue.RecordValue(control)); raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()));
        var bad = restore(raw); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, bad.diagnostic().orElseThrow().code()); assertEquals(raw, bad.write());
        host.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); var future = (CompoundTag) host.write(); future.putInt(EventHostNbtCodec.MARKER, 4);
        var unknown = restore(future); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, unknown.diagnostic().orElseThrow().code()); assertEquals(future, unknown.write());
    }
    @Test void eventModeAndItsSaveCannotEraseGenerationOrRetirementBoundaries() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); var oldReader = reader.write(); exchange(writer, reader);
        writer.reset(LIMITS, true); var control = writer.flowSnapshot().orElseThrow().control();
        writer.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        var restored = restore(writer.write()); restored.installFlow(writer(), INPUTS, LIMITS, true);
        var continued = restored.flowSnapshot().orElseThrow().control();
        assertEquals(control.generation() + 1, continued.generation()); assertEquals(control.peers(), continued.peers());
        restored.setPaused(false, LIMITS, true); restored.tick(LIMITS, true);
        assertEquals(XBusExchange.Status.RETIRED, exchange(restored, restore(oldReader)));
    }
    @Test void explicitResetRestoresOnlyItsOwnInputsAndRejectsTheRetiredPeersOldSave() {
        for (boolean resetWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            var owner = resetWriter ? writer : reader; var other = resetWriter ? reader : writer; var oldPeer = other.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader)); var completed = owner.flowSnapshot().orElseThrow(); var peer = other.write();
            owner.reset(LIMITS, true); var reset = owner.flowSnapshot().orElseThrow();
            assertEquals(completed.state().computer(), reset.state().computer()); assertNotEquals(completed.state().flow(), reset.state().flow());
            assertEquals(resetWriter ? INPUTS : EMPTY, reset.state().locals()); assertEquals(0, reset.state().work()); assertTrue(reset.state().paused());
            assertTrue(reset.receipts().isEmpty()); assertEquals(peer, other.write());
            owner.setPaused(false, LIMITS, true); owner.tick(LIMITS, true); var waiting = owner.write();
            var stalePeer = restore(oldPeer); var old = stalePeer.write();
            assertEquals(XBusExchange.Status.RETIRED, resetWriter ? exchange(owner, stalePeer) : exchange(stalePeer, owner));
            assertEquals(waiting, owner.write()); assertEquals(old, stalePeer.write());
        }
    }
}
