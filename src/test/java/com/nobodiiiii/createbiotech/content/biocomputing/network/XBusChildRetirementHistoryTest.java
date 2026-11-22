package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Distinguishes task retirement from slot reuse and from a surviving receipt repair. */
class XBusChildRetirementHistoryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void claimingOnlyOneSideFencesBothOldWaitingChildrenBeforeAnySlotReuse(boolean retireWriter) {
        var f = new Fixture();
        var waiting = f.snapshots();
        var oldWriter = f.writer.write(); var oldReader = f.reader.write();
        assertEquals(EXCHANGED, f.exchange());
        f.complete();
        var retiredHost = retireWriter ? f.writer : f.reader;
        retiredHost.claimFlowTask(retireWriter ? f.writeTask : f.readTask, f.limits, true);
        assertTrue(f.history.observe(f.snapshots(), 2, 16).isEmpty());
        var disk = f.disk();
        assertTrue(disk.getList("Claims", Tag.TAG_COMPOUND).isEmpty(), "Claim compacted; only retirement fences remain");
        var slot = retiredHost.flowSnapshot().orElseThrow().tasks().slots().entries().getFirst();
        assertEquals(0, slot.incarnation(), "No newer incarnation can hide a missing retirement check");
        assertTrue(slot.retired());
        var live = (retireWriter ? f.reader : f.writer).flowSnapshot().orElseThrow().tasks().slots().entries().getFirst();
        assertFalse(live.retired(), "The other task is still unclaimed; only its consumed operation is fenced");
        f.history = XBusHistorySavedData.load(disk, null);
        // Inspect separately: one denial must not mask a missing fence on the opposite side.
        for (var old : waiting) assertEquals(Optional.of(RETIRED), f.history.inspectKnown(List.of(old)).rejection());
        assertEquals(disk, f.disk(), "Status observation must not mutate the persisted history");
        f.restore(f.writer, oldWriter); f.restore(f.reader, oldReader);
        var before = List.of(f.writer.write(), f.reader.write());
        assertEquals(RETIRED, f.exchange());
        assertEquals(before, List.of(f.writer.write(), f.reader.write()));
        assertEquals(disk, f.disk());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anUnconfirmedLegacyChildStillRepairsItsOlderPeerFromTheActualReceipt(boolean restoreWriter) {
        var f = new Fixture();
        var old = (restoreWriter ? f.writer : f.reader).write();
        assertEquals(EXCHANGED, f.exchange());
        f.complete();
        var survivor = restoreWriter ? f.reader : f.writer;
        var unchanged = survivor.write();
        // A legacy version-7 save has no full-cohort completion proof; preserve its repair path.
        var disk = f.disk(); disk.putInt("Version", 7);
        disk.getList("Claims", Tag.TAG_COMPOUND).forEach(tag -> ((CompoundTag) tag).remove("Settled"));
        f.history = XBusHistorySavedData.load(disk, null);
        f.restore(restoreWriter ? f.writer : f.reader, old);
        var pending = f.history.inspect(f.snapshots(), 2, 16);
        assertEquals(Optional.of(RECOVERY_REQUIRED), pending.rejection());
        assertFalse(pending.recoveries().isEmpty(), "Actual peer receipt supplies recovery, not fabricated journal payload");
        assertEquals(EXCHANGED, f.exchange());
        assertEquals(unchanged, survivor.write(), "Repair cannot replay the surviving endpoint");
        f.complete();
        assertEquals(INPUTS, f.reader.claimFlowTask(f.readTask, f.limits, true).locals());
        assertEquals(1, f.reader.flowSnapshot().orElseThrow().tasks().slots().entries().size());
    }

    private static final class Fixture {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits =
            NativeLibraryOwnersTest.limits("cleric", 5);
        final ComputerBlockHost writer = new ComputerBlockHost(() -> {}), reader = new ComputerBlockHost(() -> {});
        final ModuleDefinition.Key key = new ModuleDefinition.Key("transfer", 1);
        final UUID writeTask, readTask;
        XBusHistorySavedData history = new XBusHistorySavedData();

        Fixture() {
            writeTask = install(writer, true); readTask = install(reader, false);
            for (int i = 0; i < 100 && (!FlowPinClaims.waiting(writer.flowTask(writeTask).orElseThrow())
                    || !FlowPinClaims.waiting(reader.flowTask(readTask).orElseThrow())); i++) {
                writer.tick(limits, true); reader.tick(limits, true);
            }
            assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowTask(writeTask).orElseThrow().status());
            assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowTask(readTask).orElseThrow().status());
        }
        UUID install(ComputerBlockHost host, boolean writing) {
            var inputs = writing ? Map.<String, DataType>of("packet", PACKET) : Map.<String, DataType>of();
            var outputs = writing ? Map.<String, DataType>of() : Map.<String, DataType>of("packet", PACKET);
            var returns = writing ? Map.<String, String>of() : Map.of("packet", "packet");
            host.installFlow(new FlowProgram(Map.of(), List.of(
                new FlowProgram.Procedure("transfer", key, inputs, outputs, returns),
                writing ? new FlowProgram.Write("write", "x0", "packet") : new FlowProgram.Read("read", "x0", "packet", PACKET),
                new FlowProgram.EndProcedure("return"))).withAssembly(), EMPTY, limits, true);
            host.setPaused(false, limits, true); host.tick(limits, true);
            return host.startFlowTask(key, writing ? INPUTS : EMPTY, limits, true);
        }
        void complete() {
            for (int i = 0; i < 100 && (writer.flowTask(writeTask).orElseThrow().status() != FlowRuntime.Status.COMPLETE
                    || reader.flowTask(readTask).orElseThrow().status() != FlowRuntime.Status.COMPLETE); i++) {
                writer.tick(limits, true); reader.tick(limits, true);
            }
            assertEquals(FlowRuntime.Status.COMPLETE, writer.flowTask(writeTask).orElseThrow().status());
            assertEquals(FlowRuntime.Status.COMPLETE, reader.flowTask(readTask).orElseThrow().status());
        }
        void restore(ComputerBlockHost host, Tag tag) {
            host.read(tag); host.tick(limits, true); assertTrue(host.loaded(), host.diagnostic().toString());
        }
        List<FlowBlockHost.Saved> snapshots() { return List.of(writer.flowSnapshot().orElseThrow(), reader.flowSnapshot().orElseThrow()); }
        CompoundTag disk() { return history.save(new CompoundTag(), null); }
        XBusAccess access(ComputerBlockHost host) {
            return new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(), limits.pins(), "x0").orElseThrow(),
                () -> limits, Optional::empty).historical(history, () -> 2, () -> 16);
        }
        XBusExchange.Status exchange() { return access(writer).exchange(access(reader)); }
    }
}
