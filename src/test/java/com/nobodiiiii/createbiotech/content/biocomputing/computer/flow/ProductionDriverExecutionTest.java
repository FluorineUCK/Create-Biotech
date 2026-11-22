package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Public ISA, real bounded XBus and serial codecs; only the granted production backend is simulated. */
class ProductionDriverExecutionTest {
    private static final DataValue.RecordValue TARGET = record("x", 2, "y", 0, "z", 0);
    private static final String JOB = "player-order-1";
    private static final DataValue.ListValue ITEMS = new DataValue.ListValue(List.of(record("item", "minecraft:stone", "count", 1)));

    private static ProgramDocument load(String name) throws Exception {
        try (var stream = ProductionDriverExecutionTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/" + name + ".bcp")) {
            assertNotNull(stream);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(stream.readAllBytes()).strip()), SerialFlowHostTest.ARCHIVE);
        }
    }
    private static final class Device {
        private static final UUID SOURCE = new UUID(5, 6);
        Snapshot snapshot = Snapshot.empty();
        boolean allowed = true;
        int preparations, dispatches;
        final Journal journal = new Journal() {
            @Override public Snapshot load() { return snapshot; }
            @Override public boolean commit(long expected, Snapshot next) {
                if (snapshot.revision() != expected) return false;
                snapshot = next; return true;
            }
        };
        final ProductionOrderService service = new ProductionOrderService(journal, new Backend() {
            @Override public boolean authorized(UUID source) { return allowed && source.equals(SOURCE); }
            @Override public boolean supports(String extension, int version) { return extension.equals("test") && version == 1; }
            @Override public Preparation prepare(UUID source, DataValue.RecordValue request) {
                preparations++;
                assertEquals(Set.of("items"), request.fields().keySet());
                return Preparation.ready(new Plan("test", 1, List.of(request)));
            }
            @Override public Dispatch dispatch(Operation operation, DataValue.RecordValue command) {
                dispatches++;
                return Dispatch.accepted(record("native_order", 17));
            }
            @Override public Observation observe(Operation operation, DataValue.RecordValue receipt) { return Observation.pending(); }
        }, new Limits(8, 8, 65536));
        final ProductionPortProtocol protocol = new ProductionPortProtocol(new UUID(1, 2), new UUID(3, 4), 1, TARGET, service);
    }
    private record Run(DataValue.RecordValue reply, List<DataValue.RecordValue> commands) {}

    @Test void publicSubmitUsesAStableOrderAndRepeatDoesNotDispatchTwice() throws Exception {
        var device = new Device();
        var first = run(device, "production_submit_a", ITEMS, false);
        assertEquals(new DataValue.TextValue("accepted"), first.reply().field("code"));
        assertEquals(new DataValue.TextValue("queued"), first.reply().field("phase"));
        assertEquals(List.of(new DataValue.TextValue("status"), new DataValue.TextValue("submit")),
            first.commands().stream().map(command -> command.field("operation")).toList());
        assertEquals(new DataValue.TextValue(JOB), ((DataValue.RecordValue)first.commands().getLast().field("arguments")).field("order"));
        assertNotEquals(first.commands().getFirst().field("request"), first.commands().getLast().field("request"));
        var repeated = run(device, "production_submit_a", ITEMS, false);
        assertEquals(new DataValue.TextValue("existing"), repeated.reply().field("code"));
        assertEquals(new DataValue.TextValue(JOB), ((DataValue.RecordValue)repeated.commands().getLast().field("arguments")).field("order"));
        assertNotEquals(first.commands().getLast().field("request"), repeated.commands().getLast().field("request"));
        assertEquals(1, device.snapshot.orders().size());
        assertEquals(1, device.preparations);
        assertEquals(1, device.dispatches);
    }

    @Test void independentPollReturnsActualWaitingPhaseAndNeverClaimsCompletion() throws Exception {
        var device = new Device();
        run(device, "production_submit_a", ITEMS, false);
        var poll = run(device, "production_poll_a", ITEMS, false);
        assertEquals(1, poll.commands().size());
        assertEquals(new DataValue.TextValue("order"), poll.commands().getFirst().field("operation"));
        assertEquals(record("order", JOB), poll.commands().getFirst().field("arguments"));
        assertEquals(new DataValue.TextValue("waiting"), poll.reply().field("phase"));
        assertEquals(new DataValue.TextValue("accepted"), poll.reply().field("diagnostic"));
        assertEquals(1, device.dispatches);
    }

    @Test void deniedStatusNeverSubmitsOrAutomaticallyRequestsAuthorization() throws Exception {
        var device = new Device(); device.allowed = false;
        var result = run(device, "production_submit_a", ITEMS, false);
        assertEquals(new DataValue.TextValue("denied"), result.reply().field("code"));
        assertEquals(1, result.commands().size());
        assertEquals(new DataValue.TextValue("status"), result.commands().getFirst().field("operation"));
        assertEquals(0, device.preparations); assertEquals(0, device.dispatches);
    }

    @Test void reusingJobWithDifferentMaterialsReturnsConflictWithoutAnotherSubmissionAttempt() throws Exception {
        var device = new Device();
        run(device, "production_submit_a", ITEMS, false);
        var other = new DataValue.ListValue(List.of(record("item", "minecraft:stone", "count", 2)));
        var result = run(device, "production_submit_a", other, false);
        assertEquals(new DataValue.TextValue("request_conflict"), result.reply().field("code"));
        assertEquals(2, result.commands().size());
        assertEquals(1, device.preparations); assertEquals(1, device.dispatches);
    }

    @Test void matcherRejectsForeignAndOldRepliesAcrossSavedActiveDecoderWithoutResending() throws Exception {
        var device = new Device();
        var result = run(device, "production_submit_a", ITEMS, true);
        assertEquals(new DataValue.TextValue("accepted"), result.reply().field("code"));
        assertEquals(2, result.commands().size());
        assertEquals(1, device.preparations); assertEquals(1, device.dispatches);
    }

    @Test void repeatedStableOrderRejectsItsRealPreviousAcceptedReplyAndWaitsForExisting() throws Exception {
        var device = new Device();
        var first = run(device, "production_submit_a", ITEMS, false);
        assertEquals(new DataValue.TextValue("accepted"), first.reply().field("code"));
        var repeated = run(device, "production_submit_a", ITEMS, false, first.reply());
        var firstSubmit = first.commands().getLast();
        var repeatedSubmit = repeated.commands().getLast();
        assertEquals(new DataValue.TextValue("existing"), repeated.reply().field("code"),
            "C must discard the previous accepted reply even though its business order is identical");
        assertNotEquals(firstSubmit.field("request"), repeatedSubmit.field("request"),
            "Stable order identity must not reuse the previous wire reply correlation");
        assertEquals(new DataValue.TextValue(JOB), ((DataValue.RecordValue)firstSubmit.field("arguments")).field("order"));
        assertEquals(new DataValue.TextValue(JOB), ((DataValue.RecordValue)repeatedSubmit.field("arguments")).field("order"));
        assertEquals(repeatedSubmit.field("request"), repeated.reply().field("request"));
        assertEquals(1, device.snapshot.orders().size());
        assertEquals(1, device.preparations); assertEquals(1, device.dispatches);
    }

    private static Run run(Device device, String caller, DataValue.ListValue items, boolean restore) throws Exception {
        return run(device, caller, items, restore, null);
    }

    private static Run run(Device device, String caller, DataValue.ListValue items, boolean restore,
            DataValue.RecordValue previousSubmitReply) throws Exception {
        var config = NativeLibraryOwnersTest.CONFIG;
        var la = NativeLibraryOwnersTest.limits("cleric", 5);
        var lb = NativeLibraryOwnersTest.limits("cleric", 5);
        var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        // Existing 8x CPU setting does not increase code, RAM or serial capacity.
        assertEquals(new ResourceLimits(256, 65536, 64), la.hardware());
        assertEquals(la.hardware(), lb.hardware()); assertEquals(la.hardware(), lc.hardware());
        var wireB = NativeLibraryOwnersTest.device(lb);
        var wirePort = new SerialDevice(() -> config.productionSerial(false), () -> true, () -> config.serialCodec(false), () -> {});
        wireB.configure("s0", Direction.EAST, true); wirePort.configure("s0", Direction.WEST, true);
        var draft = new ProgramWorkspace(load(caller)); var body = draft.flow().orElseThrow();
        var initial = caller.equals("production_submit_a") ? record("target", TARGET, "job", JOB, "items", items) : record("target", TARGET, "job", JOB);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), initial, body.canvas(), body.inline(), body.execution())));
        var a = NativeLibraryOwnersTest.install(draft.snapshot(), la, null);
        var b = NativeLibraryOwnersTest.install(load("redstone_output_b"), lb, wireB);
        var c = NativeLibraryOwnersTest.install(load("production_c"), lc, null);
        a.setPaused(false, la, true); b.setPaused(false, lb, true); c.setPaused(false, lc, true);
        var devices = Map.of(BlockPos.ZERO, wireB, new BlockPos(2, 0, 0), wirePort);
        var world = new CableDiscovery.World() {
            @Override public boolean loaded(BlockPos position) { return true; }
            @Override public CableDiscovery.Cell cell(BlockPos position) {
                return new CableDiscovery.Cell(position.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE
                    : devices.containsKey(position) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
        };
        var network = new SerialNetwork(); var history = new XBusHistorySavedData();
        var commands = new ArrayList<DataValue.RecordValue>();
        var replies = new ArrayDeque<DataValue.RecordValue>();
        boolean restored = false;
        for (int tick = 0; tick < 8000; tick++) {
            var bs = Map.of("x0", NativeLibraryStartupTest.status(b, "x0", lb, a, "x0", la));
            var cs = Map.of("x0", NativeLibraryStartupTest.status(c, "x0", lc, b, "x1", lb));
            a.tick(la, true); b.tick(lb, true, bs); c.tick(lc, true, cs);
            NativeLibraryServiceSerialTest.exchange(a, "x0", la, b, "x0", lb, history);
            NativeLibraryServiceSerialTest.exchange(b, "x1", lb, c, "x0", lc, history);
            network.tick(world, devices, config.communication(false), config.serialMedium(false));
            if (wirePort.status("s0").receive().frames() > 0) {
                var received = wirePort.prepareReceive("s0");
                var packet = (DataValue.RecordValue)received.result().field("value");
                commands.add(packet);
                assertTrue(commands.size() <= 2, "No retry or automatic order polling");
                var reply = device.protocol.respond(device.allowed ? Device.SOURCE : null, packet).orElseThrow();
                assertTrue(received.publish());
                if (restore) {
                    replies.add(record("unrelated", true));
                    var old = new LinkedHashMap<>(reply.fields()); old.put("request", new DataValue.TextValue("old-run"));
                    replies.add(new DataValue.RecordValue(old));
                }
                if (previousSubmitReply != null && new DataValue.TextValue("submit").equals(packet.field("operation")))
                    replies.add(previousSubmitReply);
                replies.add(reply);
            }
            device.service.advance();
            if (!replies.isEmpty() && wireB.status("s0").receive().frames() == 0
                    && !Set.of("queued", "transmitting").contains(wirePort.status("s0").transmit()))
                assertEquals("queued", wirePort.send("s0", replies.removeFirst()));
            for (var owner : List.of(a, b, c)) {
                assertTrue(owner.loaded(), owner.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status(), NativeLibraryOwnersTest.summary(owner));
                assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().isPresent(), "Only public ISA executes");
            }
            assertTrue(NativeLibraryServiceSerialTest.units(a.flowSnapshot().orElseThrow(), la) <= la.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(b.flowSnapshot().orElseThrow(), lb) <= lb.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(c.flowSnapshot().orElseThrow(), lc) <= lc.hardware().memoryUnits());
            if (restore && !restored && b.flowSnapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(state -> state.assembly().flatMap(runtime -> runtime.serial()).filter(progress -> progress.receiving()).isPresent())) {
                a = NativeLibraryOwnersTest.reload(a, la, null); b = NativeLibraryOwnersTest.reload(b, lb, wireB); c = NativeLibraryOwnersTest.reload(c, lc, null);
                history = XBusHistorySavedData.load(history.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
                restored = true;
            }
            if (a.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) break;
            if (tick == 7999) fail("Driver stalled: " + NativeLibraryOwnersTest.summary(a) + " B " + NativeLibraryOwnersTest.summary(b) + " C " + NativeLibraryOwnersTest.summary(c));
        }
        if (restore) assertTrue(restored, "Restored an actual in-progress ISA serial decoder");
        assertEquals(initial, a.flowSnapshot().orElseThrow().initialInputs(), "No hidden mutation of the player's stable job or material list");
        return new Run((DataValue.RecordValue)a.flowSnapshot().orElseThrow().state().locals().field("reply"), List.copyOf(commands));
    }
}
