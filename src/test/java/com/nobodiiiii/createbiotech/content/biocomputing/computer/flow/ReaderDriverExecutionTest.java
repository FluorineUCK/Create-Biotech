package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSamplingProtocol;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public ISA documents on three independent owners, with real serial encoding and rendezvous history. */
class ReaderDriverExecutionTest {
    @ParameterizedTest @ValueSource(strings = {"normal", "restore", "denied", "component_denied", "stale_session"})
    void queryThenOneSamplePreservesFailuresAndNeverRetries(String mode) throws Exception {
        run(mode, false);
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "restore", "component_denied", "stale_session"})
    void scratchComponentRunsTheSamePublicDriverWithoutManualSessionValues(String mode) throws Exception {
        run(mode, true);
    }

    private static void run(String mode, boolean singleComponent) throws Exception {
        var config = NativeLibraryOwnersTest.CONFIG;
        var la = NativeLibraryOwnersTest.limits("cleric", 5); var lb = NativeLibraryOwnersTest.limits("cleric", 5); var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        var wireB = NativeLibraryOwnersTest.device(lb);
        var wireReader = new SerialDevice(() -> config.readerSerial(false), () -> true, () -> config.serialCodec(false), () -> {});
        wireB.configure("s0", Direction.EAST, true); wireReader.configure("s0", Direction.WEST, true);
        var draft = new ProgramWorkspace(singleComponent ? ReaderDriverDocumentTest.load("component_a") : ReaderDriverDocumentTest.load("a")); var body = draft.flow().orElseThrow(); var address = record("x", 2, "y", 0, "z", 0);
        var components = new DataValue.ListValue(List.of(new DataValue.TextValue("create_biotech:redstone")));
        var initial = singleComponent ? record("target", address) : record("target", address, "components", components);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), initial, body.canvas(), body.inline(), body.execution())));
        var a = NativeLibraryOwnersTest.install(draft.snapshot(), la, null);
        var b = NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("b"), lb, wireB);
        var c = NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("c"), lc, null);
        a.setPaused(false, la, true); b.setPaused(false, lb, true); c.setPaused(false, lc, true);
        UUID identity = UUID.randomUUID(), session = UUID.randomUUID();
        var devices = Map.of(BlockPos.ZERO, wireB, new BlockPos(2, 0, 0), wireReader);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE :
                devices.containsKey(p) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        };
        var network = new SerialNetwork(); var history = new XBusHistorySavedData();
        var commands = new ArrayList<DataValue.RecordValue>(); var correlations = new HashSet<DataValue>(); var captures = new AtomicInteger();
        var replies = new ArrayDeque<DataValue.RecordValue>(); boolean restored = false;
        for (int tick = 0; tick < 8000; tick++) {
            var bs = Map.of("x0", NativeLibraryStartupTest.status(b, "x0", lb, a, "x0", la));
            var cs = Map.of("x0", NativeLibraryStartupTest.status(c, "x0", lc, b, "x1", lb));
            a.tick(la, true); b.tick(lb, true, bs); c.tick(lc, true, cs);
            NativeLibraryServiceSerialTest.exchange(a, "x0", la, b, "x0", lb, history);
            NativeLibraryServiceSerialTest.exchange(b, "x1", lb, c, "x0", lc, history);
            network.tick(world, devices, config.communication(false), config.serialMedium(false));
            if (wireReader.status("s0").receive().frames() > 0) {
                var received = wireReader.prepareReceive("s0"); var packet = (DataValue.RecordValue)received.result().field("value");
                assertTrue(correlations.add(packet.field("request")), "Each outgoing request has a fresh correlation"); commands.add(packet);
                var protocol = new ReaderSamplingProtocol(identity, session, address, mode.equals("component_denied") ? Set.of("create_biotech:block") : Set.of("create_biotech:redstone"));
                var reply = protocol.respond(!mode.equals("denied"), packet, ids -> {
                    captures.incrementAndGet(); assertEquals(Set.of("create_biotech:redstone"), ids);
                    return record("game_time", 91, "target", record("x", 3, "y", 0, "z", 0), "components", record("create_biotech:redstone", record("signal", 15)));
                }).orElseThrow();
                assertTrue(received.publish());
                if (mode.equals("restore")) {
                    replies.add(record("unrelated", true));
                    for (var replacement : Map.of("kind", new DataValue.TextValue("redstone_reply"), "request", new DataValue.TextValue("old-call"), "version", new DataValue.IntegerValue(2)).entrySet()) {
                        var old = new LinkedHashMap<>(reply.fields()); old.put(replacement.getKey(), replacement.getValue()); replies.add(new DataValue.RecordValue(old));
                    }
                }
                replies.add(reply);
                if (mode.equals("stale_session") && commands.size() == 1) session = UUID.randomUUID();
            }
            if (!replies.isEmpty() && wireB.status("s0").receive().frames() == 0 && !Set.of("queued", "transmitting").contains(wireReader.status("s0").transmit()))
                assertEquals("queued", wireReader.send("s0", replies.removeFirst()));
            for (var owner : List.of(a, b, c)) {
                assertTrue(owner.loaded(), owner.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status(), owner.flowSnapshot().orElseThrow().state().diagnostic().toString());
            }
            assertTrue(NativeLibraryServiceSerialTest.units(a.flowSnapshot().orElseThrow(), la) <= la.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(b.flowSnapshot().orElseThrow(), lb) <= lb.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(c.flowSnapshot().orElseThrow(), lc) <= lc.hardware().memoryUnits());
            if (mode.equals("restore") && !restored && b.flowSnapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(progress -> progress.receiving()).isPresent())) {
                a = NativeLibraryOwnersTest.reload(a, la, null); b = NativeLibraryOwnersTest.reload(b, lb, wireB); c = NativeLibraryOwnersTest.reload(c, lc, null);
                history = XBusHistorySavedData.load(history.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY); restored = true;
            }
            if (a.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) break;
            if (tick == 7999) fail("Sampler stalled: " + NativeLibraryOwnersTest.summary(a) + " B " + NativeLibraryOwnersTest.summary(b) + " C " + NativeLibraryOwnersTest.summary(c));
        }
        boolean success = Set.of("normal", "restore").contains(mode); var result = a.flowSnapshot().orElseThrow().state().locals();
        assertEquals(new DataValue.BooleanValue(success), result.field("ok"));
        var reply = (DataValue.RecordValue)result.field("reply"); assertEquals(new DataValue.TextValue(success ? "ok" : mode), reply.field("code"));
        assertEquals(success ? 1 : 0, captures.get()); assertEquals(mode.equals("denied") ? 1 : 2, commands.size());
        assertEquals(new DataValue.TextValue("status"), commands.getFirst().field("operation"));
        if (commands.size() == 2) assertEquals(new DataValue.TextValue("sample"), commands.get(1).field("operation"));
        if (success) assertEquals(new DataValue.IntegerValue(15), ((DataValue.RecordValue)((DataValue.RecordValue)((DataValue.RecordValue)reply.field("value")).field("components")).field("create_biotech:redstone")).field("signal"));
        if (mode.equals("restore")) assertTrue(restored, "Restore an actual active receive continuation");
        assertEquals(initial, a.flowSnapshot().orElseThrow().initialInputs());
    }
}
