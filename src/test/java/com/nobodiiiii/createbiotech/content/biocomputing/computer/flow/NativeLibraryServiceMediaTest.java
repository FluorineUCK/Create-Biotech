package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryServiceDocuments;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalTaskClaim;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerControlSession;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

/** Actual book component and mechanical core, not a registered world/player-path test. */
class NativeLibraryServiceMediaTest {
    @BeforeAll static void bootstrap() throws Exception { NativeLibraryTerminalSessionTest.bootstrap(); }

    @Test void repeatedTerminalWritesUseTheSameServiceOwnersAndRestoreBeforeReplyConsumption() throws Exception {
        var f = new NativeLibraryTerminalSessionTest.Fixture(); f.start();
        var main = f.computer.flowSnapshot().orElseThrow().state();
        var la = NativeLibraryOwnersTest.limits("cleric", 5); var lb = NativeLibraryOwnersTest.limits("cleric", 5);
        var lc = NativeLibraryOwnersTest.limits("cleric", 5); var ld = NativeLibraryOwnersTest.limits("cleric", 5);
        var wireB = NativeLibraryOwnersTest.device(lb); wireB.configure("s0", Direction.EAST, true);
        var wireL = NativeLibraryOwnersTest.device(lb); wireL.configure("s0", Direction.WEST, true);
        var b = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("b"), lb, wireB);
        var c = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("c"), lc, null);
        var d = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("d"), ld, null);
        b.setPaused(false, lb, true); c.setPaused(false, lc, true); d.setPaused(false, ld, true);
        var identities = List.of(b, c, d).stream().map(h -> h.flowSnapshot().orElseThrow().state().flow()).toList();
        var serial = new SerialNetwork(); var history = new XBusHistorySavedData();
        var posB = BlockPos.ZERO; var cable = new BlockPos(1, 0, 0); var posL = new BlockPos(2, 0, 0);
        var devices = Map.of(posB, wireB, posL, wireL);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) {
                return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
            }
        };
        var head = LibraryReadHead.State.empty(); var drive = NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        var source = new NativeLibraryTerminalExecutionTest.Media(false, drive.archive(), NativeLibraryOwnersTest.CONFIG.libraryBookBytes(false));
        var epoch = UUID.randomUUID(); var device = UUID.randomUUID(); var requests = new HashSet<DataValue>();
        var operations = new ArrayList<String>(); boolean restored = false; long ticks = 0;
        for (int round = 0; round < 2; round++) {
            String input = "continuous terminal value " + (93 + round);
            f.read(); assertEquals("ok", f.submit(input)); boolean finished = false;
            for (int step = 0; step < 14000; step++, ticks++) {
                f.owner.tick(la, true);
                b.tick(lb, true, Map.of("x0", NativeLibraryStartupTest.status(b, "x0", lb, f.owner, "x0", la)));
                c.tick(lc, true, Map.of("x0", NativeLibraryStartupTest.status(c, "x0", lc, b, "x1", lb)));
                d.tick(ld, true, Map.of("x0", NativeLibraryStartupTest.status(d, "x0", ld, f.owner, "x1", la)));
                // Persist at a real reply WRITE, after the book committed but before consumption/claim.
                if (!restored && source.commits == 1 && b.flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(t ->
                        t.status() == FlowRuntime.Status.WAITING_WRITE && t.locals().fields().get("outcome") instanceof DataValue.RecordValue outcome
                            && outcome.field("value") instanceof DataValue.RecordValue reply
                            && new DataValue.TextValue("written").equals(reply.fields().get("code")))) {
                    var disk = history.save(new CompoundTag(), RegistryAccess.EMPTY);
                    f.restore(); b = NativeLibraryOwnersTest.reload(b, lb, wireB);
                    c = NativeLibraryOwnersTest.reload(c, lc, null); d = NativeLibraryOwnersTest.reload(d, ld, null);
                    history = XBusHistorySavedData.load(disk, RegistryAccess.EMPTY);
                    var encoded = LibraryReadHeadCodec.encode(head, drive); head = LibraryReadHeadCodec.decode(encoded, drive);
                    assertEquals(encoded, LibraryReadHeadCodec.encode(head, drive)); source.book = source.book.copy(); restored = true;
                }
                NativeLibraryServiceSerialTest.exchange(f.owner, "x0", la, b, "x0", lb, history);
                NativeLibraryServiceSerialTest.exchange(b, "x1", lb, c, "x0", lc, history);
                NativeLibraryServiceSerialTest.exchange(f.owner, "x1", la, d, "x0", ld, history);
                serial.tick(world, devices, NativeLibraryOwnersTest.CONFIG.communication(false), NativeLibraryOwnersTest.CONFIG.serialMedium(false));
                if (head.writing().isPresent() && head.writing().orElseThrow().admission().verdict().isEmpty())
                    head = LibraryReadHead.withWrite(head, head.writing().orElseThrow().seal(false));
                var next = LibraryReadHead.prepareTick(head, source, drive); next.commit().run(); head = next.next();
                if (wireL.status("s0").receive().frames() > 0 && !Set.of("queued", "transmitting").contains(wireL.status("s0").transmit())) {
                    var received = wireL.prepareReceive("s0");
                    var command = LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));
                    assertTrue(requests.add(command.request()), "No implicit replay after restore"); operations.add(command.operation());
                    var transition = command.apply(head, source, drive, new LibraryDriveCommand.Admission(device, new LibraryWriteBatch(epoch, ticks)));
                    if (command.operation().equals("ack") && head.writing().isPresent())
                        source.releaseWrite(head.track().orElseThrow(), head.slot(), head.writing().orElseThrow().operation());
                    head = transition.state(); assertTrue(received.publish()); assertEquals("queued", wireL.send("s0", transition.reply()));
                }
                for (var host : List.of(f.owner, b, c, d)) {
                    assertTrue(host.loaded(), host.diagnostic().toString()); var saved = host.flowSnapshot().orElseThrow();
                    assertNotEquals(FlowRuntime.Status.FAILED, saved.state().status(), saved.state().diagnostic().toString());
                    for (var child : saved.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED, child.status(), child.diagnostic().toString());
                }
                assertEquals(main, f.computer.flowSnapshot().orElseThrow().state());
                if (f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status() == FlowRuntime.Status.COMPLETE
                        && List.of(b, c, d).stream().allMatch(h -> h.flowSnapshot().orElseThrow().tasks().contexts().isEmpty())) { finished = true; break; }
            }
            assertTrue(finished, "Complete without resetting/reinstalling any service owner");
            var result = f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().locals();
            assertEquals(new DataValue.BooleanValue(true), result.field("success")); assertSame(DataValue.NullValue.INSTANCE, result.field("error"));
            assertEquals(new DataValue.TextValue(input), ((DataValue.RecordValue)result.field("read_result")).field("value"));
            assertEquals(new DataValue.TextValue(input), source.media.read(source.book).get("reading").value());
            assertEquals(round + 1, source.commits); assertEquals(round + 1, source.releases); assertTrue(head.result().isEmpty());
            assertEquals(identities, List.of(b, c, d).stream().map(h -> h.flowSnapshot().orElseThrow().state().flow()).toList());
            for (var host : List.of(b, c, d)) {
                var saved = host.flowSnapshot().orElseThrow(); assertTrue(saved.receipts().isEmpty());
                assertEquals(1, saved.tasks().slots().entries().size()); assertTrue(saved.tasks().slots().entries().getFirst().retired());
            }
            var before = f.computer.flowSnapshot().orElseThrow(); f.read(); assertEquals(before, f.computer.flowSnapshot().orElseThrow());
            assertEquals("ok", f.apply(ComputerControlSession.Action.TERMINAL_CLAIM, new TerminalTaskClaim(f.intent, 0).encode()).code());
            assertTrue(f.computer.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        }
        assertTrue(restored); assertEquals(2, Collections.frequency(operations, "write")); assertEquals(2, Collections.frequency(operations, "read"));
        assertEquals(10, head.sequence());
        System.out.println("Two terminal submissions, unchanged B/C/D owners, two commits, restored before written reply consumption; ticks=" + ticks);
    }
}
