package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.HOST;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class XBusOccupancyHistoryTest {
    @Test void aPausedCancelledWriterDoesNotBlockFreshTrafficAndFailsOnlyWhenItsOwnTaskResumes() {
        var f = new Fixture(); f.network().selectWriter(f.pins());
        var c = f.hosts.get(2); c.setPaused(true, HOST, true); var paused = f.saved().get(2).state();
        f.hosts.getFirst().reset(HOST, true); f.hosts.getFirst().setPaused(false, HOST, true); f.hosts.getFirst().step(HOST, true);
        assertEquals(RETIRED, f.network().settleRetired());
        assertEquals(paused, f.saved().get(2).state(), "Cancellation may persist metadata but cannot execute a paused task");
        var savedC = c.write(); var disk = f.disk();
        c.read(savedC); c.setPaused(true, HOST, true); f.history = XBusHistorySavedData.load(disk, null);
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
        assertEquals(paused, f.saved().get(2).state()); assertTrue(f.saved().get(2).receipts().isEmpty());
        c.setPaused(false, HOST, true);
        assertEquals(RETIRED, f.network().settleRetired());
        var failed = f.saved().get(2);
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.XBUS_OPERATION_RETIRED, failed.state().diagnostic().orElseThrow().code());
        assertEquals(paused.work(), failed.state().work()); assertEquals(paused.locals(), failed.state().locals());
        assertTrue(failed.receipts().isEmpty());
    }
    @Test void aSingleRecoveredOwnerRebuildsTheWholeCancelledCohortWithoutInventingConsumption() {
        var f = new Fixture(4); var empty = f.disk();
        f.network().selectWriter(f.pins());
        f.hosts.getFirst().reset(HOST, true);
        assertEquals(RETIRED, f.network().settleRetired());
        var c = f.saved().get(2); var d = f.saved().get(3);
        assertEquals(FlowRuntime.Status.FAILED, c.state().status());
        var recovered = XBusHistorySavedData.load(empty, null);
        assertTrue(recovered.observe(List.of(c), 10, 100).isEmpty());
        var encoded = recovered.save(new CompoundTag(), null);
        recovered = XBusHistorySavedData.load(encoded, null);
        assertTrue(recovered.tracks(d.state().computer()), "One loaded owner must preserve the identity of the unloaded competitor");
        assertEquals(encoded, recovered.save(new CompoundTag(), null));
        assertTrue(recovered.observe(List.of(d), 10, 100).isEmpty());
        assertTrue(encoded.getList("Claims", 10).isEmpty(), "A cancellation proves retirement, not data delivery");
        assertTrue(c.receipts().isEmpty()); assertTrue(d.receipts().isEmpty());
    }
    @Test void resettingOnlyTheHolderSettlesTheOldCompetitorAndAllowsANewRendezvousWithoutADeliveryReceipt() {
        var f = new Fixture(); f.network().selectWriter(f.pins()); var before = f.saved().get(2);
        var a = f.hosts.getFirst(); a.reset(HOST, true); a.setPaused(false, HOST, true); a.step(HOST, true);
        assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins()).rejection());
        var failed = f.saved().get(2);
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals("XBUS_OPERATION_RETIRED", failed.state().diagnostic().orElseThrow().code().name());
        assertEquals(before.state().locals(), failed.state().locals()); assertEquals(before.state().work(), failed.state().work());
        assertTrue(failed.receipts().isEmpty(), "Cancellation is not a consumption or delivery receipt");
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
    }
    @Test void resettingTheCancelledOwnerCompactsProofWithoutMakingAnOlderHolderSaveValidAgain() {
        var f = new Fixture(); f.network().selectWriter(f.pins()); var oldA = f.hosts.getFirst().write();
        f.hosts.getFirst().reset(HOST, true); f.hosts.getFirst().setPaused(false, HOST, true); f.hosts.getFirst().step(HOST, true);
        assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins()).rejection());
        f.hosts.get(2).reset(HOST, true);
        assertTrue(f.saved().get(2).cancellations().isEmpty(), "Retired local operation releases its bounded cohort but retains remote retirement proof");
        assertTrue(f.saved().get(2).control().peers().get(f.pin(0).computer()).generation() > 0);
        var c = f.hosts.get(2).write(); f.hosts.get(2).read(c); f.hosts.get(2).setPaused(false, HOST, true);
        f.hosts.getFirst().read(oldA); f.hosts.getFirst().setPaused(false, HOST, true);
        assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins()).rejection());
        assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
    }
    @Test void aCompetingFlowMayStartAnotherWriteAfterAPausedResultWithoutLosingEitherOperationIdentity() {
        var f = new Fixture(); var competitor = f.hosts.get(2);
        competitor.reset(HOST, true);
        competitor.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(
            new FlowProgram.Write("first", "x0", "packet", Optional.of("result")),
            new FlowProgram.Write("second", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", packet(99))), HOST, true);
        competitor.setPaused(false, HOST, true); competitor.step(HOST, true);
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        var first = f.saved().get(2).state();
        f.hosts.getFirst().setPaused(true, HOST, true);
        assertEquals(PAUSED, f.network().rejectPaused(f.pin(2)));
        competitor.step(HOST, true);
        var second = f.saved().get(2).state();
        assertEquals(first.flow(), second.flow()); assertTrue(second.operation() > first.operation());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, second.status());
        f.hosts.getFirst().setPaused(false, HOST, true);
        var before = f.disk(); var owners = f.saved(); f.limit = 5;
        assertEquals(Optional.of(ARCHIVE_LIMIT), f.network().selectWriter(f.pins().reversed()).rejection());
        assertEquals(before, f.disk()); assertEquals(owners, f.saved());
        f.limit = 6;
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        var held = f.disk(); f.history = XBusHistorySavedData.load(held, null);
        assertEquals(held, f.disk());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
        assertEquals(FlowRuntime.Status.COMPLETE, f.saved().get(2).state().status());
        assertTrue(f.disk().getList("Occupancies", 10).isEmpty());
    }
    @Test void explicitlyResettingEveryParticipantEndsTheOldOccupancyAndAllowsAFreshRendezvous() {
        var f = new Fixture(); f.network().selectWriter(f.pins());
        for (var host : f.hosts) { host.reset(HOST, true); host.setPaused(false, HOST, true); host.step(HOST, true); }
        var selection = f.network().selectWriter(f.pins());
        assertTrue(selection.rejection().isEmpty(), "All previous operation identities are retired; an old occupancy cannot permanently lock the original wire: " + selection);
        assertEquals(Optional.of(f.pin(0)), selection.writer());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
    }
    @Test void aCorruptCompetingWriterIdentityIsPreservedAsUnreadableRatherThanCreatingAnImmortalOccupancy() {
        var f = new Fixture(); f.network().selectWriter(f.pins()); var bad = f.disk();
        var writers = bad.getList("Occupancies", 10).getCompound(0).getList("Writers", 10);
        var competitor = writers.stream().map(tag -> (CompoundTag)tag)
            .filter(tag -> tag.getUUID("Computer").equals(f.pin(2).computer())).findFirst().orElseThrow();
        competitor.putUUID("Flow", UUID.randomUUID());
        f.history = XBusHistorySavedData.load(bad, null);
        var owners = f.saved();
        assertEquals(Optional.of(UNAVAILABLE), f.network().selectWriter(f.pins()).rejection());
        assertEquals(0, f.network().statusCode(f.pin(1)));
        assertEquals(bad, f.disk()); assertEquals(owners, f.saved());
    }
    @Test void onlyRetiringTheFirstWriterDoesNotPromoteItsDiscardedCompetitorEvenAfterReload() {
        var f = new Fixture(); f.network().selectWriter(f.pins());
        var waiting = f.saved().get(2); var host = f.hosts.getFirst();
        host.reset(HOST, true); host.setPaused(false, HOST, true); host.step(HOST, true);
        assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins().reversed()).rejection());
        var held = f.disk(); f.history = XBusHistorySavedData.load(held, null);
        assertEquals(held, f.disk());
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        assertEquals(NOT_READY, f.network().exchange(f.pin(1), f.pin(2)));
        assertEquals(FlowRuntime.Status.FAILED, f.saved().get(2).state().status());
        assertEquals(waiting.state().locals(), f.saved().get(2).state().locals());
        assertTrue(f.saved().get(2).receipts().isEmpty());
        assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
    }
    @Test void retiringAllRecordedWritersDoesNotRequireResettingTheReaderAndFencesAnOlderWriterSave() {
        var f = new Fixture(); f.network().selectWriter(f.pins());
        var reader = f.saved().get(1); var oldWriter = f.hosts.get(2).write();
        for (int i : List.of(0, 2)) {
            var host = f.hosts.get(i); host.reset(HOST, true); host.setPaused(false, HOST, true); host.step(HOST, true);
        }
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        assertEquals(reader, f.saved().get(1));
        var newWriter = f.hosts.get(2).write(); var held = f.disk();
        f.hosts.get(2).read(oldWriter); f.hosts.get(2).setPaused(false, HOST, true);
        f.history = XBusHistorySavedData.load(held, null);
        assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins()).rejection());
        assertEquals(reader, f.saved().get(1)); assertEquals(held, f.disk());
        f.hosts.get(2).read(newWriter); f.hosts.get(2).setPaused(false, HOST, true);
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
    }
    @Test void legacyOccupancyCanResumeItsOriginalHolderWithoutInventingACompleteHistoricalParticipantSet() {
        var f = new Fixture(); f.network().selectWriter(f.pins()); var legacy = f.disk();
        legacy.putInt("Version", 4);
        var hold = legacy.getList("Occupancies", 10).getCompound(0); hold.remove("Writers"); hold.remove("Complete");
        f.history = XBusHistorySavedData.load(legacy, null);
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        assertFalse(f.disk().getList("Occupancies", 10).getCompound(0).getBoolean("Complete"));
        // A schema-4 save cannot establish who joined before its original holder disappeared.
        f.history = XBusHistorySavedData.load(legacy, null);
        for (int i : List.of(0, 2)) {
            var host = f.hosts.get(i); host.reset(HOST, true); host.setPaused(false, HOST, true); host.step(HOST, true);
        }
        assertEquals(Optional.of(RECOVERY_REQUIRED), f.network().selectWriter(f.pins()).rejection());
        assertFalse(f.disk().getList("Occupancies", 10).getCompound(0).getBoolean("Complete"));
        assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
    }
    @Test void samplingALegacyBusAfterACompetingWriteFinishedCannotAuthorizeItsOlderSaveToSendAgain() {
        var f = new Fixture(); var c = f.hosts.get(2);
        c.reset(HOST, true);
        c.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet", Optional.of("result")))),
            new DataValue.RecordValue(Map.of("packet", packet(99))), HOST, true);
        c.setPaused(false, HOST, true); c.step(HOST, true);
        f.network().selectWriter(f.pins()); var oldC = c.write(); var legacy = f.disk();
        legacy.putInt("Version", 4);
        var hold = legacy.getList("Occupancies", 10).getCompound(0); hold.remove("Writers"); hold.remove("Complete");
        f.history = XBusHistorySavedData.load(legacy, null);
        f.hosts.getFirst().setPaused(true, HOST, true);
        assertEquals(PAUSED, f.network().rejectPaused(f.pin(2)));
        assertEquals(FlowRuntime.Status.COMPLETE, f.saved().get(2).state().status());
        f.hosts.getFirst().setPaused(false, HOST, true);
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        f.hosts.getFirst().reset(HOST, true);
        assertTrue(f.history.observe(f.saved(), 10, 100).isEmpty());
        var retired = f.disk(); f.history = XBusHistorySavedData.load(retired, null);
        c.read(oldC); c.setPaused(false, HOST, true);
        assertEquals(Optional.of(RECOVERY_REQUIRED), f.network().selectWriter(f.pins().reversed()).rejection());
        assertEquals(RECOVERY_REQUIRED, f.network().exchange(f.pin(1), f.pin(2)));
        assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
    }
    @Test void aMainFlowWaitingOnAnotherPhysicalPinCannotOccupyThisBus() {
        var f = new Fixture(); var other = new XBusNetwork.Pin(f.pin(0).computer(), "x1");
        var access = new XBusAccess(f.hosts.getFirst(), XBusPortState.of(f.saved().getFirst(), HOST.pins(), "x1").orElseThrow(), () -> HOST, Optional::empty)
            .historical(f.history, () -> 10, () -> f.limit);
        var network = XBusAccess.network(List.of(access, f.access(1), f.access(2)));
        assertEquals(Optional.of(f.pin(2)), network.selectWriter(List.of(other, f.pin(1), f.pin(2))).writer());
    }
    @Test void admissionIsBoundedReadOnlyObservationCannotAdmitAndARejectedReservationRestoresTheExactOccupancy() {
        var f = new Fixture(); var snapshots = f.saved();
        var pristine = f.disk(); assertEquals(15, f.network().statusCode(f.pin(1))); assertEquals(pristine, f.disk());
        assertTrue(f.network().inspect().rejection().isEmpty()); var enrolled = f.disk();
        f.limit = 3;
        assertEquals(Optional.of(ARCHIVE_LIMIT), f.network().selectWriter(f.pins()).rejection());
        assertEquals(enrolled, f.disk()); assertEquals(snapshots, f.saved());
        f.limit = 5;
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        var held = f.disk(); assertEquals(5, held.getInt("Version"));
        f.history = XBusHistorySavedData.load(held, null); assertEquals(held, f.disk());
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins().reversed()).writer());
        assertEquals(held, f.disk());
        var a = snapshots.get(0); var b = snapshots.get(1); var c = snapshots.get(2);
        var receipt = new XBusReceipt(a.position(a.state(), "x0"), b.position(b.state(), "x0"), packet(12),
            List.of(c.position(c.state(), "x0")), List.of());
        var reservation = f.history.reserve(receipt, snapshots, 10, f.limit);
        assertTrue(reservation.rejection().isEmpty(), "Occupancy converts to a consumption fence within the same budget");
        assertTrue(f.disk().getList("Occupancies", 10).isEmpty());
        reservation.finish(NOT_READY); assertEquals(held, f.disk()); assertEquals(snapshots, f.saved());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertTrue(f.disk().getList("Occupancies", 10).isEmpty());
    }

    @Test void corruptDowngradedOrOverlappingOccupanciesArePreservedAndNeverTreatedAsAnEmptyBus() {
        var f = new Fixture(); f.network().selectWriter(f.pins()); var good = f.disk();
        var version = good.copy(); version.putInt("Version", 3);
        var duplicate = good.copy(); var pins = duplicate.getList("Occupancies", 10).getCompound(0).getList("Pins", 10); pins.add(pins.getFirst().copy());
        var overlap = good.copy(); var holds = overlap.getList("Occupancies", 10); holds.add(holds.getFirst().copy());
        var unknown = good.copy(); unknown.getList("Occupancies", 10).getCompound(0).getCompound("Writer").putUUID("Flow", UUID.randomUUID());
        for (var bad : List.of(version, duplicate, overlap, unknown)) {
            f.history = XBusHistorySavedData.load(bad, null);
            assertEquals(Optional.of(UNAVAILABLE), f.network().selectWriter(f.pins()).rejection());
            assertEquals(0, f.network().statusCode(f.pin(1)));
            assertEquals(bad, f.disk());
        }
    }
    @Test void aLegacyPairCannotBypassTheFullAdmittedComponentOrPromoteItsLaterWriter() {
        var f = new Fixture(); assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        var before = f.saved(); var journal = f.disk();
        assertEquals(RECOVERY_REQUIRED, f.access(2).exchange(f.access(1)), "A pair cannot discard the admitted original writer");
        assertEquals(before, f.saved()); assertEquals(journal, f.disk());
        assertEquals(NOT_READY, f.network().exchange(f.pin(1), f.pin(2)));
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
    }
    static DataValue.RecordValue packet(int value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static class Fixture {
        XBusHistorySavedData history = new XBusHistorySavedData(); int limit = 100;
        final List<ComputerBlockHost> hosts;
        Fixture() { this(3); }
        Fixture(int count) { this(count, i -> () -> {}); }
        Fixture(int count, java.util.function.IntFunction<Runnable> notifications) {
            this(count, notifications, false);
        }
        Fixture(int count, java.util.function.IntFunction<Runnable> notifications, boolean assembly) {
            hosts = java.util.stream.IntStream.range(0, count).mapToObj(i -> new ComputerBlockHost(notifications.apply(i))).toList();
            for (int i = 0; i < hosts.size(); i++) {
                var host = hosts.get(i);
                var program = i == 1 ? new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", PACKET)))
                    : new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet")));
                var inputs = i == 1 ? new DataValue.RecordValue(Map.of()) : new DataValue.RecordValue(Map.of("packet", packet(i == 0 ? 12 : 99)));
                host.installFlow(assembly ? program.withAssembly() : program, inputs, HOST, true); host.setPaused(false, HOST, true); host.step(HOST, true);
            }
        }
        List<FlowBlockHost.Saved> saved() { return hosts.stream().map(h -> h.flowSnapshot().orElseThrow()).toList(); }
        CompoundTag disk() { return history.save(new CompoundTag(), null); }
        XBusNetwork.Pin pin(int i) { return new XBusNetwork.Pin(saved().get(i).state().computer(), "x0"); }
        List<XBusNetwork.Pin> pins() { return java.util.stream.IntStream.range(0, hosts.size()).mapToObj(this::pin).toList(); }
        XBusAccess access(int i) {
            return new XBusAccess(hosts.get(i), XBusPortState.of(saved().get(i), HOST.pins(), "x0").orElseThrow(), () -> HOST, Optional::empty)
                .historical(history, () -> 10, () -> limit);
        }
        XBusAccess.Network network() { return XBusAccess.network(java.util.stream.IntStream.range(0, hosts.size()).mapToObj(this::access).toList()); }
    }
}
