package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Three real bounded ISA owners and actual serial codecs. The output responder is the pure device state, not a world fixture. */
class RedstoneDriverExecutionTest {
    private static ProgramDocument load(String role) throws Exception {
        try (var in = RedstoneDriverExecutionTest.class.getResourceAsStream(
                "/assets/create_biotech/biocomputing/examples/redstone_output_" + role + ".bcp")) {
            assertNotNull(in);
            return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).strip()), SerialFlowHostTest.ARCHIVE);
        }
    }
    @Test void publicDriverAppliesExactlyOneVersionedSetWithIndependentResources() throws Exception { run("normal"); }
    @Test void oldAndUnrelatedRepliesAreIgnoredAcrossSavedActiveCodec() throws Exception { run("restore"); }
    @Test void changedVersionReturnsFailureWithoutRetryingOrOverwriting() throws Exception { run("changed"); }
    @Test void deniedQueryDoesNotProduceASetCommand() throws Exception { run("denied"); }

    private static void run(String mode) throws Exception {
        var config = NativeLibraryOwnersTest.CONFIG;
        var la = NativeLibraryOwnersTest.limits("cleric", 5);
        var lb = NativeLibraryOwnersTest.limits("cleric", 5);
        var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        var wireB = NativeLibraryOwnersTest.device(lb);
        var wireOutput = new SerialDevice(() -> config.redstoneSerial(false), () -> true, () -> config.serialCodec(false), () -> {});
        wireB.configure("s0", Direction.EAST, true); wireOutput.configure("s0", Direction.WEST, true);
        var draft = new ProgramWorkspace(load("a")); var body = draft.flow().orElseThrow();
        var address = record("x", 2, "y", 0, "z", 0);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), record("target", address, "level", 9),
            body.canvas(), body.inline(), body.execution())));
        var a = NativeLibraryOwnersTest.install(draft.snapshot(), la, null);
        var b = NativeLibraryOwnersTest.install(load("b"), lb, wireB);
        var c = NativeLibraryOwnersTest.install(load("c"), lc, null);
        a.setPaused(false, la, true); b.setPaused(false, lb, true); c.setPaused(false, lc, true);
        UUID authorized = UUID.randomUUID(), source = mode.equals("denied") ? UUID.randomUUID() : authorized;
        var output = RedstoneOutputState.grant(RedstoneOutputState.closed(UUID.randomUUID(), UUID.randomUUID()), Optional.of(authorized), UUID.randomUUID());
        var devices = Map.of(BlockPos.ZERO, wireB, new BlockPos(2, 0, 0), wireOutput);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) {
                return new CableDiscovery.Cell(p.equals(new BlockPos(1, 0, 0)) ? CableDiscovery.Kind.CABLE :
                    devices.containsKey(p) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
        };
        var network = new SerialNetwork(); var history = new XBusHistorySavedData();
        var commands = new ArrayList<DataValue.RecordValue>(); var requests = new HashSet<DataValue>();
        var replies = new ArrayDeque<DataValue.RecordValue>(); boolean restored = false;
        for (int tick = 0; tick < 8000; tick++) {
            var bs = Map.of("x0", NativeLibraryStartupTest.status(b, "x0", lb, a, "x0", la));
            var cs = Map.of("x0", NativeLibraryStartupTest.status(c, "x0", lc, b, "x1", lb));
            a.tick(la, true); b.tick(lb, true, bs); c.tick(lc, true, cs);
            NativeLibraryServiceSerialTest.exchange(a, "x0", la, b, "x0", lb, history);
            NativeLibraryServiceSerialTest.exchange(b, "x1", lb, c, "x0", lc, history);
            network.tick(world, devices, config.communication(false), config.serialMedium(false));
            if (wireOutput.status("s0").receive().frames() > 0) {
                var received = wireOutput.prepareReceive("s0");
                var packet = (DataValue.RecordValue) received.result().field("value");
                assertTrue(requests.add(packet.field("request")), "No resend with an old correlation");
                commands.add(packet);
                var planned = RedstoneOutputProtocol.plan(output, source, address, packet).orElseThrow();
                assertTrue(received.publish()); output = planned.next();
                if (mode.equals("restore")) {
                    replies.add(record("unrelated", true));
                    var old = new LinkedHashMap<>(planned.reply().fields()); old.put("request", new DataValue.TextValue("old-run"));
                    replies.add(new DataValue.RecordValue(old));
                }
                replies.add(planned.reply());
                if (mode.equals("changed") && commands.size() == 1)
                    output = RedstoneOutputState.set(output, authorized, output.device(), output.session(), output.revision(), 3).state();
            }
            if (!replies.isEmpty() && wireB.status("s0").receive().frames() == 0
                    && !Set.of("queued", "transmitting").contains(wireOutput.status("s0").transmit()))
                assertEquals("queued", wireOutput.send("s0", replies.removeFirst()));
            for (var owner : List.of(a, b, c)) {
                assertTrue(owner.loaded(), owner.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status());
                assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().isPresent(), "Only ISA executes");
            }
            assertTrue(NativeLibraryServiceSerialTest.units(a.flowSnapshot().orElseThrow(), la) <= la.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(b.flowSnapshot().orElseThrow(), lb) <= lb.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(c.flowSnapshot().orElseThrow(), lc) <= lc.hardware().memoryUnits());
            if (mode.equals("restore") && !restored && b.flowSnapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(progress -> progress.receiving()).isPresent())) {
                a = NativeLibraryOwnersTest.reload(a, la, null); b = NativeLibraryOwnersTest.reload(b, lb, wireB); c = NativeLibraryOwnersTest.reload(c, lc, null);
                history = XBusHistorySavedData.load(history.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
                restored = true;
            }
            if (a.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) break;
            if (tick == 7999) fail("Driver stalled: " + NativeLibraryOwnersTest.summary(a) + " B " + NativeLibraryOwnersTest.summary(b) + " C " + NativeLibraryOwnersTest.summary(c));
        }
        boolean success = Set.of("normal", "restore").contains(mode);
        var result = a.flowSnapshot().orElseThrow().state().locals();
        assertEquals(new DataValue.BooleanValue(success), result.field("ok"));
        assertEquals(new DataValue.TextValue(success ? "applied" : mode), ((DataValue.RecordValue) result.field("reply")).field("code"));
        assertEquals(success ? 9 : mode.equals("changed") ? 3 : 0, output.applied());
        assertEquals(mode.equals("denied") ? 1 : 2, commands.size(), "Query once, then at most one set; business failure never retries");
        assertEquals(new DataValue.TextValue("status"), commands.getFirst().field("operation"));
        if (mode.equals("restore")) assertTrue(restored, "Saved an actual in-progress ISA receive");
        assertEquals(record("target", address, "level", 9), a.flowSnapshot().orElseThrow().initialInputs(), "Driver never modifies its source input");
    }
}
