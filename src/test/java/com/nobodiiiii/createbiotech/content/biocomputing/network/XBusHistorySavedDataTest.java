package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XBusHistorySavedDataTest {
    @TempDir Path directory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void longRunningRootReleasesHistoryAcrossPeerSessionChanges(boolean resetWriter) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture();
        var writer = f.peers.get(f.a.target().position()).host;
        var reader = f.peers.get(f.b.target().position()).host;
        writer.reset(HOST, true); reader.reset(HOST, true);
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.EndForever("end"))).withAssembly(), INPUTS, HOST, true);
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("read", "x1", "packet", PACKET), new FlowProgram.EndForever("end"))).withAssembly(), EMPTY, HOST, true);
        var readerFlow = reader.flowSnapshot().orElseThrow().state().flow();
        writer.setPaused(false, HOST, true); reader.setPaused(false, HOST, true);
        for (int round = 0; round < 32; round++) {
            for (int i = 0; i < 24 && writer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; i++) writer.tick(HOST, true);
            for (int i = 0; i < 24 && reader.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_READ; i++) reader.tick(HOST, true);
            var network = XBusCableNetwork.capture(List.of(f.links.getFirst()), SCAN, f::resolve);
            assertEquals("ready", network.code());
            assertEquals(EXCHANGED, network.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
            var before = reader.flowSnapshot().orElseThrow();
            assertEquals(readerFlow, before.state().flow(), "The reader is one long-lived root, not disposable child tasks");
            assertTrue(before.receipts().size() <= 2, "Completed payload history cannot grow with the number of transfers");
            if (resetWriter) {
                writer.reset(HOST, true); writer.setPaused(false, HOST, true);
                assertTrue(f.history.observe(List.of(writer.flowSnapshot().orElseThrow()), 10, 100).isEmpty());
            }
            f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            assertTrue(reader.reclaimCompletedXBus(f.history::payloadDiscardable) > 0);
            assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertEquals(before.state(), reader.flowSnapshot().orElseThrow().state(), "Protocol retirement never resets or advances a program");
            if (round % 8 == 7) {
                reader.setPaused(true, HOST, true);
                var raw = reader.write(); reader.read(raw); reader.tick(HOST, true); assertTrue(reader.loaded());
                f.history = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
                f.peers.values().forEach(p -> p.history = f.history);
                assertEquals(raw, reader.write());
                reader.setPaused(false, HOST, true);
            }
        }
    }

    @Test void durableSessionRetirementReleasesPayloadAfterClaimCompaction() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        var writer = f.peers.get(f.a.target().position()).host;
        var old = writer.flowSnapshot().orElseThrow();
        var receipt = old.receipts().getFirst();
        var reader = f.peers.get(f.b.target().position()).host;
        var readerState = reader.flowSnapshot().orElseThrow().state();
        writer.reset(HOST, true);
        assertTrue(f.history.observe(List.of(writer.flowSnapshot().orElseThrow()), 10, 100).isEmpty());
        assertTrue(f.history.save(new CompoundTag(), null).getList("Claims", 10).isEmpty(),
            "A new session replaces the old claim with compact retirement frontiers");
        assertFalse(f.history.payloadDiscardable(receipt), "Unsaved retirement cannot authorize deleting recovery data");
        assertEquals(0, f.history.maintain(reader, HOST, true));
        var badParent = Files.writeString(directory.resolve("not-a-directory"), "test");
        f.history.save(badParent.resolve("history.dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertFalse(f.history.payloadDiscardable(receipt));
        assertEquals(0, f.history.maintain(reader, HOST, true));
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(f.history.payloadDiscardable(receipt),
            "Durable retirement must release historical payloads even when the exact claim was compacted away");
        assertEquals(1, f.history.maintain(reader, HOST, true));
        assertTrue(reader.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(readerState, reader.flowSnapshot().orElseThrow().state());
        var restored = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
        assertTrue(restored.payloadDiscardable(receipt));
        assertEquals(Optional.of(RETIRED), restored.inspectKnown(List.of(old)).rejection());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void actualHandoffCertifiesCompletionWithoutRequiringTaskRetirement(boolean cohort) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture();
        var links = cohort ? f.links : List.of(f.links.getFirst());
        var capture = XBusCableNetwork.capture(links, SCAN, f::resolve);
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var owner = f.peers.get(f.a.target().position()).host.flowSnapshot().orElseThrow();
        var receipt = owner.receipts().getFirst();
        assertEquals(cohort, receipt.grouped());
        assertFalse(f.history.payloadDiscardable(receipt), "Publication alone is not a durable retirement proof");
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(f.history.payloadDiscardable(receipt),
            "Normal handoff must certify its full cohort without claiming a task or manually certifying the journal");
        assertEquals(owner, f.peers.get(f.a.target().position()).host.flowSnapshot().orElseThrow());
        var restored = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
        assertTrue(restored.payloadDiscardable(receipt));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aThrownPublicationCallbackDoesNotCertifyTheTransfer(boolean cohort) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture();
        var reader = f.peers.get(f.b.target().position());
        var oldReader = reader.host.write();
        var links = cohort ? f.links : List.of(f.links.getFirst());
        var capture = XBusCableNetwork.capture(links, SCAN, f::resolve);
        f.peers.get(f.a.target().position()).onDirty = () -> { throw new IllegalStateException("save notification failed"); };
        assertThrows(IllegalStateException.class,
            () -> capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var owner = f.peers.get(f.a.target().position()).host.flowSnapshot().orElseThrow();
        assertFalse(owner.receipts().isEmpty(), "The uncertain published state must retain its recovery payload");
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertFalse(f.history.payloadDiscardable(owner.receipts().getFirst()));

        // The identity-only disk record still permits repairing the original older owner.
        f.history = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
        f.peers.values().forEach(p -> p.history = f.history);
        f.peers.get(f.a.target().position()).onDirty = () -> {};
        reader.host.read(oldReader); reader.host.tick(HOST, true);
        var repair = XBusCableNetwork.capture(links, SCAN, f::resolve);
        assertEquals(EXCHANGED, repair.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertFalse(f.history.payloadDiscardable(owner.receipts().getFirst()));
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(f.history.payloadDiscardable(owner.receipts().getFirst()),
            "A repaired complete cohort must be certified just like an original successful handoff");
    }

    @Test void deletingPayloadsRequiresCompleteCohortAndNeverRestoresAnOlderWait() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture();
        var before = f.peers.values().stream().map(p -> p.host.flowSnapshot().orElseThrow()).toList();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow()
            .exchange(f.pin(f.a), f.pin(f.b)));
        var consumed = f.peers.values().stream().map(p -> p.host.flowSnapshot().orElseThrow()).toList();
        var writer = consumed.getFirst();
        var receipt = writer.receipts().getFirst();
        assertTrue(receipt.grouped());
        assertFalse(f.history.certifyCompleted(receipt, List.of(writer, consumed.get(1))), "Never delete on a missing third peer");
        var mixed = List.of(writer, before.get(1), consumed.get(2));
        assertFalse(f.history.certifyCompleted(receipt, mixed), "One older peer still needs the payload to recover");
        assertTrue(f.history.certifyCompleted(receipt, consumed));
        assertFalse(f.history.payloadDiscardable(receipt), "An in-memory witness is not permission to delete");
        assertEquals(Optional.of(RECOVERY_REQUIRED), f.history.inspectKnown(mixed).rejection(),
            "A pending proof must not disable repair using the existing payload");
        var notDirectory = Files.writeString(directory.resolve("not-a-directory"), "test");
        f.history.save(notDirectory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertFalse(f.history.payloadDiscardable(receipt), "A failed write must leave all local payloads necessary");
        f.history.save(directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(f.history.payloadDiscardable(receipt), "The successful retry makes deletion eligible without a Computer reset");
        var persisted = f.history.save(new CompoundTag(), null);
        var restored = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
        assertEquals(persisted, restored.save(new CompoundTag(), null));
        assertTrue(restored.payloadDiscardable(receipt));
        // Looking up a writer operation is not proof that this particular cohort completed.
        var p = receipt.reader();
        var differentPin = new XBusReceipt.Position(p.computer(), p.flow(), p.cursor(), "other",
            p.generation(), p.operation(), p.slot(), p.incarnation(), p.instruction());
        assertFalse(restored.payloadDiscardable(new XBusReceipt(receipt.writer(), differentPin, receipt.payload(),
            receipt.otherWriters(), receipt.otherReaders())));
        assertFalse(restored.payloadDiscardable(new XBusReceipt(receipt.writer(), p, receipt.payload())),
            "The same pair without the other participants does not own the durable witness");
        var invalidPin = new XBusReceipt.Position(p.computer(), p.flow(), p.cursor(), " x1",
            p.generation(), p.operation(), p.slot(), p.incarnation(), p.instruction());
        assertThrows(IllegalArgumentException.class, () -> restored.payloadDiscardable(new XBusReceipt(
            receipt.writer(), invalidPin, receipt.payload(), receipt.otherWriters(), receipt.otherReaders())));
        assertFalse(persisted.getList("Claims", 10).getCompound(0).contains("Payload"));

        var cleaned = new FlowBlockHost.Saved(writer.program(), writer.initialInputs(), writer.state(), List.of(),
            writer.control(), writer.tasks(), writer.pinClaims(), writer.cancellations(), writer.memory());
        assertTrue(restored.inspectKnown(List.of(cleaned, consumed.get(1), consumed.get(2))).rejection().isEmpty(),
            "Discarding only the local records must not disturb newer operations");
        var stale = restored.inspectKnown(List.of(cleaned, before.get(1), consumed.get(2)));
        assertEquals(Optional.of(RETIRED), stale.rejection());
        assertTrue(stale.recoveries().isEmpty(), "An old program point may not request the deleted transfer again");
    }

    @Test void aFullCohortReservesEveryExtraIdentityAndRejectsMalformedOrDowngradedHistory() {
        var f = new XBusCableNetworkTest.Fixture();
        var owners = f.peers.values().stream().map(p -> p.host.flowSnapshot().orElseThrow()).toList();
        var writer = owners.getFirst(); var reader = owners.get(1); var otherWriter = owners.get(2);
        var receipt = new XBusReceipt(writer.position(writer.state(), "x0"), reader.position(reader.state(), "x1"),
            (DataValue.RecordValue)INPUTS.field("packet"), List.of(otherWriter.position(otherWriter.state(), "x0")), List.of());
        var history = new XBusHistorySavedData(); assertTrue(history.observe(owners, 3, 1).isEmpty());
        var before = history.save(new CompoundTag(), null);
        assertEquals(Optional.of(ARCHIVE_LIMIT), history.reserve(receipt, owners, 3, 1).rejection());
        assertEquals(before, history.save(new CompoundTag(), null), "Capacity refusal cannot leave a partial claim");
        var reservation = history.reserve(receipt, owners, 3, 2); assertTrue(reservation.rejection().isEmpty());
        var reserved = history.save(new CompoundTag(), null);
        assertEquals(3, reserved.getInt("Version"));
        assertEquals(Optional.of(RECOVERY_REQUIRED), XBusHistorySavedData.load(reserved, null).inspectKnown(owners).rejection());
        assertTrue(XBusHistorySavedData.load(reserved, null).inspectKnown(owners).recoveries().isEmpty(), "Identity-only reservation cannot invent the payload");
        assertEquals(reserved, XBusHistorySavedData.load(reserved, null).save(new CompoundTag(), null));
        reservation.finish(NOT_READY); assertEquals(before, history.save(new CompoundTag(), null));
        var duplicate = reserved.copy(); var claim = duplicate.getList("Claims", 10).getCompound(0);
        claim.getList("OtherWriters", 10).add(claim.getCompound("Writer").copy());
        var downgrade = reserved.copy(); downgrade.putInt("Version", 2);
        var wrongSlot = reserved.copy(); var extra = wrongSlot.getList("Claims", 10).getCompound(0).getList("OtherWriters", 10).getCompound(0);
        extra.putInt("Slot", 1); extra.putLong("Incarnation", 0);
        for (var bad : List.of(duplicate, downgrade, wrongSlot)) {
            var loaded = XBusHistorySavedData.load(bad, null);
            assertEquals(Optional.of(UNAVAILABLE), loaded.observe(owners, 3, 10));
            assertEquals(bad, loaded.save(new CompoundTag(), null), "Invalid evidence is preserved, never replaced with an empty journal");
        }
    }

    @Test void actualTaskRetirementAndReuseReachTheDimensionHistoryWithoutRetainingResultData() {
        var base = HOST;
        var limits = new ComputerHostLimits(base.hardware(), base.archive(),
            base.mailbox(), base.pins(), base.serial(), 2);
        var key = new ModuleDefinition.Key("copy", 1);
        var integer = DataType.Scalar.INTEGER;
        var program = new FlowProgram(Map.of("value", integer), List.of(
            new FlowProgram.Sleep("main", "value"),
            new FlowProgram.Procedure("definition", key, Map.of("value", integer), Map.of("result", integer), Map.of("result", "value")),
            new FlowProgram.EndProcedure("return")));
        var inputs = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(42)));
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program, inputs, limits, true); owner.step(limits, true);
        UUID first = owner.startFlowTask(key, inputs, limits, true); owner.step(limits, true);
        var history = new XBusHistorySavedData();
        assertTrue(history.observe(List.of(owner.flowSnapshot().orElseThrow()), 10, 10).isEmpty());
        var live = history.save(new CompoundTag(), null);
        var old = owner.flowSnapshot().orElseThrow();
        owner.claimFlowTask(first, limits, true);
        assertTrue(history.observe(List.of(owner.flowSnapshot().orElseThrow()), 10, 10).isEmpty());
        var retired = history.save(new CompoundTag(), null);
        assertNotEquals(live, retired, "The authoritative history must see retirement, not just the unchanged main flow");
        assertTrue(history.observe(List.of(old), 10, 10).isEmpty());
        assertEquals(retired, history.save(new CompoundTag(), null), "An older publication cannot resurrect a retired task");
        history = XBusHistorySavedData.load(retired, null);
        UUID second = owner.startFlowTask(key, inputs, limits, true);
        assertNotEquals(first, second);
        assertTrue(history.observe(List.of(owner.flowSnapshot().orElseThrow()), 10, 10).isEmpty());
        assertNotEquals(retired, history.save(new CompoundTag(), null));
        assertTrue(history.inspect(List.of(owner.flowSnapshot().orElseThrow()), 10, 10).rejection().isEmpty());
        var newest = history.save(new CompoundTag(), null);
        assertTrue(history.observe(List.of(old), 10, 0).isEmpty());
        assertEquals(newest, history.save(new CompoundTag(), null), "Lower configuration and old saves never evict newer slot witnesses");
        assertTrue(history.inspect(List.of(old), 10, 10).rejection().isEmpty(), "Unrelated pure task churn cannot falsely retire the unchanged main pin");
        assertEquals(newest, history.save(new CompoundTag(), null));
        var full = new XBusHistorySavedData(); var before = full.save(new CompoundTag(), null);
        assertEquals(Optional.of(ARCHIVE_LIMIT), full.observe(List.of(owner.flowSnapshot().orElseThrow()), 10, 0));
        assertEquals(before, full.save(new CompoundTag(), null), "Rejected history admission is atomic");
        assertTrue(full.observe(List.of(owner.flowSnapshot().orElseThrow()), 10, 1).isEmpty());

        var badBoolean = newest.copy(); childSlot(badBoolean).putByte("Retired", (byte) 2);
        var badGeneration = newest.copy(); childSlot(badGeneration).putLong("Incarnation", -1);
        var duplicate = newest.copy(); var slots = duplicate.getList("Frontiers", 10).getCompound(0).getList("Slots", 10); slots.add(slots.getLast().copy());
        var downgraded = newest.copy(); downgraded.putInt("Version", 1);
        for (var corrupt : List.of(badBoolean, badGeneration, duplicate, downgraded)) {
            var opaque = XBusHistorySavedData.load(corrupt, null);
            assertEquals(Optional.of(UNAVAILABLE), opaque.observe(List.of(), 10, 10)); assertEquals(corrupt, opaque.save(new CompoundTag(), null));
        }
    }
    private static CompoundTag childSlot(CompoundTag history) {
        return history.getList("Frontiers", 10).getCompound(0).getList("Slots", 10).getCompound(1);
    }

    @Test void retainedChildWitnessesAndRealTransferReservationsShareOneBoundedHistoryBudget() {
        var limits = new ComputerHostLimits(HOST.hardware(), HOST.archive(), HOST.mailbox(), HOST.pins(), HOST.serial(), 2, HOST.isa1a());
        var key = new ModuleDefinition.Key("copy", 1); var empty = new DataValue.RecordValue(Map.of());
        var receiver = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "packet", PACKET),
            new FlowProgram.Procedure("copy", key, Map.of(), Map.of(), Map.of()), new FlowProgram.EndProcedure("end")));
        var reader = new ComputerBlockHost(() -> {}); reader.installFlow(receiver, empty, limits, true);
        reader.setPaused(false, limits, true); reader.step(limits, true);
        reader.startFlowTask(key, empty, limits, true);
        var writer = new ComputerBlockHost(() -> {});
        writer.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("write", "x0", "packet"))), INPUTS, HOST, true);
        writer.setPaused(false, HOST, true); writer.step(HOST, true);
        var a = writer.flowSnapshot().orElseThrow(); var b = reader.flowSnapshot().orElseThrow();
        var history = new XBusHistorySavedData(); assertTrue(history.observe(List.of(a, b), 2, 1).isEmpty());
        var unchanged = history.save(new CompoundTag(), null);
        assertEquals(Optional.of(ARCHIVE_LIMIT), history.reserve(a, "x0", b, "x0", 2, 1).rejection());
        assertEquals(unchanged, history.save(new CompoundTag(), null));
        assertEquals(a, writer.flowSnapshot().orElseThrow()); assertEquals(b, reader.flowSnapshot().orElseThrow());
        var reservation = history.reserve(a, "x0", b, "x0", 2, 2); assertTrue(reservation.rejection().isEmpty());
        var status = writer.exchange("x0", HOST, true, reader, "x0", limits, true); assertEquals(EXCHANGED, status); reservation.finish(status);
        assertTrue(history.inspect(List.of(writer.flowSnapshot().orElseThrow(), reader.flowSnapshot().orElseThrow()), 2, 2).rejection().isEmpty());
        var disk = history.save(new CompoundTag(), null); var restored = XBusHistorySavedData.load(disk, null);
        assertTrue(restored.inspect(List.of(writer.flowSnapshot().orElseThrow(), reader.flowSnapshot().orElseThrow()), 2, 2).rejection().isEmpty());
        assertEquals(disk, restored.save(new CompoundTag(), null));
        reader.reset(limits, true);
        assertTrue(restored.observe(List.of(reader.flowSnapshot().orElseThrow()), 2, 0).isEmpty(), "Retirement compaction can free the existing bounded capacity");
        assertTrue(restored.save(new CompoundTag(), null).getList("Claims", 10).isEmpty());
        assertEquals(Optional.of(RETIRED), restored.inspect(List.of(a), 2, 2).rejection(), "Compaction still retires the consumed peer operation");
    }

    @Test void aHistoryPinMustFitTheExistingHardwareEncodingInsteadOfIntroducingAnUnboundedString() {
        var f = new XBusCableNetworkTest.Fixture();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var invalid = f.history.save(new CompoundTag(), null);
        invalid.getList("Claims", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getCompound("Writer")
            .putString("Pin", "x".repeat(com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.MAX_NAME + 1));
        var loaded = XBusHistorySavedData.load(invalid, null);
        assertEquals(Optional.of(UNAVAILABLE), loaded.observe(List.of(), 4096, 65536));
        assertEquals(invalid, loaded.save(new CompoundTag(), null));
    }

    @Test void diskReloadKeepsDeletedWireHistoryAndUnreadableFilesAreNeverAnEmptyReplacement() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var f = new XBusCableNetworkTest.Fixture(); var b = f.peers.get(f.b.target().position()); var oldReader = b.host.write();
        var storage = new DimensionDataStorage(directory.toFile(), null, null);
        f.history = XBusHistorySavedData.get(storage, directory); f.peers.values().forEach(p -> p.history = f.history);
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        storage.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var file = directory.resolve(XBusHistorySavedData.DATA_NAME + ".dat"); var good = Files.readAllBytes(file);
        var reloaded = new DimensionDataStorage(directory.toFile(), null, null);
        f.history = XBusHistorySavedData.get(reloaded, directory); f.peers.values().forEach(p -> p.history = f.history);
        b.host.read(oldReader); b.host.tick(HOST, true);
        f.unloaded.add(f.a.target().position()); f.cables.remove(new BlockPos(1, 0, 0)); var before = f.snapshots();
        assertEquals("retired", XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve).code(),
            "The durable full-cohort proof fences an older read even after the original wire disappears");
        assertEquals(before, f.snapshots());
        for (byte[] damaged : List.of(new byte[] {31, -117, 0, 7}, new byte[] {10, 0, 0, 1})) {
            Files.write(file, damaged);
            var badStorage = new DimensionDataStorage(directory.toFile(), null, null);
            f.history = XBusHistorySavedData.get(badStorage, directory); f.peers.values().forEach(p -> p.history = f.history);
            assertEquals("unavailable", XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve).code());
            assertEquals(before, f.snapshots());
            assertEquals(Optional.of(UNAVAILABLE), f.history.observe(List.of(b.host.flowSnapshot().orElseThrow()), 4096, 65536));
            f.history.setDirty(); badStorage.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            assertArrayEquals(damaged, Files.readAllBytes(file));
            assertSame(f.history, XBusHistorySavedData.get(badStorage, directory));
        }
        Files.write(file, good);
        f.history = XBusHistorySavedData.get(new DimensionDataStorage(directory.toFile(), null, null), directory);
        f.peers.values().forEach(p -> p.history = f.history);
        assertEquals("retired", XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve).code());
    }

    @Test void futureAndContradictoryTagsStayOpaqueAndNeverBecomeAnEmptyHistory() {
        var f = new XBusCableNetworkTest.Fixture();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var healthy = f.history.save(new CompoundTag(), null);
        var future = healthy.copy(); future.putInt("Version", 4); future.putString("Future", "preserve");
        var duplicate = healthy.copy(); var list = duplicate.getList("Claims", net.minecraft.nbt.Tag.TAG_COMPOUND);
        list.add(list.getFirst().copy());
        var wrongType = healthy.copy(); wrongType.putString("Frontiers", "not_a_list");
        for (var invalid : List.of(future, duplicate, wrongType)) {
            var history = XBusHistorySavedData.load(invalid, null);
            assertEquals(Optional.of(UNAVAILABLE), history.observe(List.of(), 4096, 65536));
            assertThrows(IllegalArgumentException.class, () -> history.tracks(f.a.target().computer()));
            assertEquals(invalid, history.save(new CompoundTag(), null));
        }
    }
}
