package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerXBusExchangeTest {
    @Test void completedBranchJumpRepairsEitherPeersOlderWaitWithoutReplayingSkippedArm() {
        for (boolean savedWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            var inputs = Map.<String, DataType>of("ok", DataType.Scalar.BOOLEAN, "packet", PACKET);
            var initial = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(true), "packet", INPUTS.field("packet")));
            start(writer, new FlowProgram(inputs, List.of(new FlowProgram.If("if", "ok"), new FlowProgram.Write("write", "x0", "packet"),
                new FlowProgram.Otherwise("else"), new FlowProgram.AwaitReadable("skip", "x1"), new FlowProgram.EndIf("end"))), initial);
            start(reader, new FlowProgram(inputs, List.of(new FlowProgram.If("if", "ok"), new FlowProgram.Read("read", "x1", "packet", PACKET),
                new FlowProgram.Otherwise("else"), new FlowProgram.AwaitReadable("skip", "x0"), new FlowProgram.EndIf("end"))), initial);
            writer.tick(LIMITS, true); reader.tick(LIMITS, true);
            var beforeWriter = writer.write(); var beforeReader = reader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            writer.tick(LIMITS, true); reader.tick(LIMITS, true);
            assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
            assertEquals(FlowRuntime.Status.COMPLETE, reader.flowSnapshot().orElseThrow().state().status());
            var recoveredWriter = restore(savedWriter ? writer.write() : beforeWriter);
            var recoveredReader = restore(savedWriter ? beforeReader : reader.write());
            var advanced = savedWriter ? recoveredWriter.write() : recoveredReader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(recoveredWriter, recoveredReader));
            assertEquals(advanced, savedWriter ? recoveredWriter.write() : recoveredReader.write());
            recoveredWriter.tick(LIMITS, true); recoveredReader.tick(LIMITS, true);
            assertEquals(writer.flowSnapshot(), recoveredWriter.flowSnapshot());
            assertEquals(reader.flowSnapshot(), recoveredReader.flowSnapshot());
            assertEquals(XBusExchange.Status.NOT_READY, exchange(recoveredWriter, recoveredReader));
        }
    }
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    static FlowProgram reader() { return new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET))); }
    static void start(ComputerBlockHost host, FlowProgram program, DataValue.RecordValue inputs) {
        host.installFlow(program, inputs, LIMITS, true); host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
    }
    static XBusExchange.Status exchange(ComputerBlockHost writer, ComputerBlockHost reader) {
        return writer.exchange("x0", LIMITS, true, reader, "x1", LIMITS, true);
    }
    static ComputerBlockHost restore(net.minecraft.nbt.Tag tag) {
        var host = new ComputerBlockHost(() -> {}); host.read(tag); host.tick(LIMITS, true); return host;
    }
    @Test void eitherCompletedSideCanRepairItsPeersOlderWaitingSaveWithoutRepeatingItsOwnOperation() {
        for (boolean savedWriter : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            var beforeWriter = writer.write(); var beforeReader = reader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
            var recoveredWriter = restore(savedWriter ? writer.write() : beforeWriter);
            var recoveredReader = restore(savedWriter ? beforeReader : reader.write());
            var advanced = savedWriter ? recoveredWriter.write() : recoveredReader.write();
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(recoveredWriter, recoveredReader));
            assertEquals(advanced, savedWriter ? recoveredWriter.write() : recoveredReader.write());
            assertEquals(INPUTS, recoveredReader.flowSnapshot().orElseThrow().state().locals());
            assertEquals(FlowRuntime.Status.COMPLETE, recoveredWriter.flowSnapshot().orElseThrow().state().status());
            assertEquals(XBusExchange.Status.NOT_READY, exchange(recoveredWriter, recoveredReader));
        }
    }
    @Test void replacingAConsumedFlowStillRequiresExplicitResetAndRetirement() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); exchange(writer, reader);
        var saved = writer.write();
        assertEquals(ComputationException.Code.BUSY, assertThrows(ComputationException.class,
            () -> writer.installFlow(writer(), INPUTS, LIMITS, true)).code());
        assertEquals(saved, writer.write()); assertTrue(writer.loaded()); assertTrue(writer.diagnostic().isEmpty());
        writer.reset(LIMITS, true); assertTrue(writer.flowSnapshot().orElseThrow().receipts().isEmpty());
        writer.installFlow(writer(), INPUTS, LIMITS, true); assertTrue(writer.loaded());
    }
    @Test void eitherHostsMemoryOrArchiveLimitRejectsTheWholePairWithoutSuspendingOwnedWork() {
        for (boolean writerLimited : List.of(true, false)) for (boolean archiveLimited : List.of(true, false)) {
            var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
            start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
            var left = writer.write(); var right = reader.write(); var limited = writerLimited ? writer : reader;
            var saved = limited.flowSnapshot().orElseThrow();
            long encoded = ValueNbtCodec.decode(((CompoundTag) limited.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
            var tight = archiveLimited ? new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, encoded))
                : new ComputerHostLimits(new ResourceLimits(1000, saved.initialInputs().storageUnits() + saved.state().controlUnits() + 2, 1), LIMITS.archive());
            var result = writer.exchange("x0", writerLimited ? tight : LIMITS, true, reader, "x1", writerLimited ? LIMITS : tight, true);
            assertEquals(archiveLimited ? XBusExchange.Status.ARCHIVE_LIMIT : XBusExchange.Status.MEMORY_LIMIT, result);
            assertEquals(left, writer.write()); assertEquals(right, reader.write()); assertTrue(writer.loaded()); assertTrue(reader.loaded());
            assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        }
    }
    @Test void readableWakeHasNoConsumptionReceiptAndTheWriterWaitsUntilTheLaterRead() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS);
        start(reader, new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wake", "x1"),
            new FlowProgram.Read("read", "x1", "packet", PACKET))), EMPTY);
        var beforeWriter = writer.write();
        assertEquals(XBusExchange.Status.READABLE, exchange(writer, reader)); assertEquals(beforeWriter, writer.write());
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(XBusExchange.Status.NOT_READY, exchange(writer, reader));
        reader.tick(LIMITS, true); assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        assertEquals(INPUTS, reader.flowSnapshot().orElseThrow().state().locals()); assertEquals(1, reader.flowSnapshot().orElseThrow().receipts().size());
    }
    @Test void rejectedPinsPauseUnloadAndTypesNeverConsumeEitherSide() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); var left = writer.write(); var right = reader.write();
        assertEquals(XBusExchange.Status.PORT_MISMATCH, writer.exchange("wrong", LIMITS, true, reader, "x1", LIMITS, true));
        assertEquals(XBusExchange.Status.UNAVAILABLE, writer.exchange("x0", LIMITS, true, reader, "x1", LIMITS, false));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
        reader.setPaused(true, LIMITS, true); assertEquals(XBusExchange.Status.PAUSED, exchange(writer, reader));
        reader.reset(LIMITS, true);
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet",
            new DataType.RecordType(Map.of("value", DataType.Scalar.BOOLEAN))))), EMPTY, LIMITS, true);
        reader.setPaused(false, LIMITS, true); reader.tick(LIMITS, true); right = reader.write();
        assertEquals(XBusExchange.Status.TYPE_MISMATCH, exchange(writer, reader));
        assertEquals(left, writer.write()); assertEquals(right, reader.write());
    }
    @Test void retainedHistoryRepairsTwoOlderOperationsInOrderWithoutExecutingThePeersSuccessors() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("first", "x0", "packet"),
            new FlowProgram.Write("second", "x0", "packet"))), INPUTS);
        start(reader, new FlowProgram(Map.of(), List.of(new FlowProgram.Read("first", "x1", "packet", PACKET),
            new FlowProgram.Read("second", "x1", "packet", PACKET))), EMPTY);
        var oldWriter = writer.write(); exchange(writer, reader); writer.tick(LIMITS, true); reader.tick(LIMITS, true);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(writer, reader));
        var finished = reader.write(); var recovering = restore(oldWriter);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(recovering, reader));
        assertEquals(XBusExchange.Status.NOT_READY, exchange(recovering, reader)); // Must execute the second write itself.
        recovering.tick(LIMITS, true); assertEquals(XBusExchange.Status.EXCHANGED, exchange(recovering, reader));
        assertEquals(finished, reader.write());
        // ValueNbtCodec preserves sharing, not canonical node numbers; compare the entire decoded state.
        assertEquals(writer.flowSnapshot(), recovering.flowSnapshot());
    }
    @Test void versionOneStillLoadsButDuplicateReceiptPositionsAreRejectedAndPreserved() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
        var old = (CompoundTag) writer.write(); var fields = new LinkedHashMap<>(((DataValue.RecordValue) ValueNbtCodec.decode(old.getCompound("Data"), LIMITS.archive())).fields());
        fields.remove("receipts"); fields.remove("control"); old.putInt(FlowBlockNbtCodec.MARKER, 1); old.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()));
        var upgraded = restore(old); assertEquals(writer.flowSnapshot(), upgraded.flowSnapshot()); assertEquals(old, upgraded.write());
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(upgraded, reader));
        var saved = upgraded.flowSnapshot().orElseThrow(); var receipt = saved.receipts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), saved.state(), List.of(receipt, receipt)));
        var raw = (CompoundTag) upgraded.write(); fields = new LinkedHashMap<>(((DataValue.RecordValue) ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive())).fields());
        fields.put("receipts", new DataValue.ListValue(List.of(receipt.value(), receipt.value())));
        raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()));
        var invalid = restore(raw); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, invalid.diagnostic().orElseThrow().code()); assertEquals(raw, invalid.write());
    }
    @Test void incompatiblePeerHistoriesDoNotRewriteAnAlreadyCompletedPair() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); exchange(writer, reader);
        var saved = reader.flowSnapshot().orElseThrow(); var receipt = saved.receipts().getFirst(); var pos = receipt.writer();
        var altered = new XBusReceipt(new XBusReceipt.Position(pos.computer(), pos.flow(), pos.cursor(), "different"), receipt.reader(), receipt.payload());
        var conflicting = restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), saved.state(), List.of(altered)), LIMITS.archive()));
        var left = writer.write(); var right = conflicting.write();
        assertEquals(XBusExchange.Status.HISTORY_CONFLICT, exchange(writer, conflicting));
        assertEquals(left, writer.write()); assertEquals(right, conflicting.write());
    }
    @Test void receiptsDoNotMigrateToNewFlowIdentitiesAndAreChargedAgainAfterHardwareDowngrade() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY); var oldReader = reader.write(); exchange(writer, reader);
        var restarted = restore(oldReader); restarted.reset(LIMITS, true); restarted.setPaused(false, LIMITS, true); restarted.tick(LIMITS, true);
        var left = writer.write(); var right = restarted.write();
        assertEquals(XBusExchange.Status.NOT_READY, exchange(writer, restarted));
        assertEquals(left, writer.write()); assertEquals(right, restarted.write());
        var saved = writer.flowSnapshot().orElseThrow();
        var tight = new ComputerHostLimits(new ResourceLimits(1000, saved.initialInputs().storageUnits() + saved.state().controlUnits() + 2, 1), LIMITS.archive());
        writer.tick(tight, true); assertFalse(writer.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, writer.diagnostic().orElseThrow().code());
        assertEquals(left, writer.write()); writer.tick(LIMITS, true); assertTrue(writer.loaded()); assertEquals(saved, writer.flowSnapshot().orElseThrow());
    }
    @Test void twoPhysicalOwnersClaimingTheSameComputerIdentityAreRejectedWithoutThrowingOrPublishing() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        start(writer, writer(), INPUTS); start(reader, reader(), EMPTY);
        var saved = reader.flowSnapshot().orElseThrow(); var state = saved.state();
        var duplicateId = new FlowRuntime.Checkpoint(writer.flowSnapshot().orElseThrow().state().computer(), state.flow(), state.cursor(),
            state.status(), state.locals(), state.work(), state.paused());
        var duplicate = restore(FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), duplicateId), LIMITS.archive()));
        var left = writer.write(); var right = duplicate.write();
        assertEquals(XBusExchange.Status.STALE, exchange(writer, duplicate));
        assertEquals(left, writer.write()); assertEquals(right, duplicate.write());
    }
    @Test void actualWaitingHostsPublishBothSaveImagesBeforeAnyDirtyCallbackAndNeverRepeatTheRead() {
        var hosts = new ComputerBlockHost[2]; var inspect = new boolean[1]; var dirty = new int[1];
        Runnable changed = () -> {
            if (!inspect[0]) return;
            for (var host : hosts) assertEquals(FlowRuntime.Status.COMPLETE, host.flowSnapshot().orElseThrow().state().status());
            dirty[0]++;
        };
        hosts[0] = new ComputerBlockHost(changed); hosts[1] = new ComputerBlockHost(changed);
        start(hosts[0], writer(), INPUTS); start(hosts[1], reader(), EMPTY); inspect[0] = true;
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(hosts[0], hosts[1]));
        assertEquals(INPUTS, hosts[1].flowSnapshot().orElseThrow().state().locals()); assertEquals(2, dirty[0]);
        var left = hosts[0].write(); var right = hosts[1].write();
        assertEquals(XBusExchange.Status.NOT_READY, exchange(hosts[0], hosts[1]));
        assertEquals(left, hosts[0].write()); assertEquals(right, hosts[1].write()); assertEquals(2, dirty[0]);
    }
}
