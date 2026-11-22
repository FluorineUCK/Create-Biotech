package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

/** Public ISA + actual finite serial/mechanical/book component. Not a physical-world or GUI claim. */
class NativeClusterJournalMediaTest {
    @BeforeAll static void bootstrap() throws Exception { NativeLibraryTerminalSessionTest.bootstrap(); }

    private static java.io.InputStream resource(net.minecraft.resources.ResourceLocation id) {
        return NativeClusterJournalMediaTest.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
    }

    private static ProgramDocument preview(List<ProgramExampleCatalog.Example> catalog, String role, ComputerHostLimits limits) {
        String id = switch (role) {
            case "caller", "gateway" -> "cluster_serial_" + role;
            case "b", "c" -> "library_service_" + role;
            default -> "cluster_journal_" + role;
        };
        var example = catalog.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(example, NativeClusterJournalMediaTest::resource, limits.archive());
    }

    @Test void gatewayStartedOnADisconnectedLineReportsItAndDoesNotRetryOnConnection() {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var wire = NativeLibraryOwnersTest.device(limits);
        wire.configure("s0", Direction.WEST, true);
        var gateway = NativeLibraryOwnersTest.install(preview(ProgramExampleCatalog.load(NativeClusterJournalMediaTest::resource),
            "gateway", limits), limits, wire);
        gateway.setPaused(false, limits, true);
        for (int i = 0; i < 100 && gateway.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++)
            gateway.tick(limits, true);
        var stopped = gateway.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, stopped.status());
        var outcome = (DataValue.RecordValue)stopped.locals().field("outcome");
        assertEquals(new DataValue.BooleanValue(false), outcome.field("ok"));
        assertEquals(new DataValue.TextValue("disconnected"), ((DataValue.RecordValue)outcome.field("v")).field("connection"));
        var caller = NativeLibraryOwnersTest.device(limits);
        caller.configure("s0", Direction.EAST, true);
        new Segment(caller, wire).tick();
        assertEquals("ready", wire.status("s0").connection());
        gateway.tick(limits, true);
        assertEquals(stopped, gateway.flowSnapshot().orElseThrow().state());
        assertEquals(0, caller.status("s0").receive().frames());
    }

    @Test void repeatedExternalTextIsSavedReadBackAndRestoredBeforeTheWrittenReplyWithoutResettingServices() {
        run(false);
    }
    @Test void readOnlyBookReturnsAnApplicationFailureInsteadOfTheComputedValueOrAnInventedSave() {
        run(true);
    }

    private static void run(boolean readonly) {
        var roles = List.of("caller", "gateway", "worker", "preparer", "coordinator", "adapter", "b", "c");
        var limits = new LinkedHashMap<String, ComputerHostLimits>();
        var owners = new LinkedHashMap<String, ComputerBlockHost>();
        var devices = new LinkedHashMap<String, SerialDevice>();
        var catalog = ProgramExampleCatalog.load(NativeClusterJournalMediaTest::resource);
        for (String role : roles) {
            var l = NativeLibraryOwnersTest.limits("cleric", 5);
            limits.put(role, l);
            SerialDevice serial = Set.of("caller", "gateway", "b").contains(role) ? NativeLibraryOwnersTest.device(l) : null;
            if (serial != null) { serial.configure("s0", role.equals("gateway") ? Direction.WEST : Direction.EAST, true); devices.put(role, serial); }
            ProgramDocument document = preview(catalog, role, l);
            var host = NativeLibraryOwnersTest.install(document, l, serial);
            assertTrue(host.flowSnapshot().orElseThrow().state().paused());
            owners.put(role, host);
        }
        var library = NativeLibraryOwnersTest.device(limits.get("b")); library.configure("s0", Direction.WEST, true);
        var outside = new Segment(devices.get("caller"), devices.get("gateway"));
        var diskWire = new Segment(devices.get("b"), library);
        // This is the prewired player station, not a race against the world's first cable scan.
        outside.tick(); diskWire.tick();
        for (var port : devices.values()) assertEquals("ready", port.status("s0").connection());
        assertEquals("ready", library.status("s0").connection());
        owners.forEach((role, host) -> host.setPaused(false, limits.get(role), true));
        var links = List.of(new Link("gateway", "x0", "worker", "x0"), new Link("worker", "x1", "preparer", "x0"),
            new Link("preparer", "x1", "coordinator", "x1"), new Link("coordinator", "x0", "adapter", "x0"), new Link("adapter", "x1", "b", "x0"), new Link("b", "x1", "c", "x0"));
        var history = new XBusHistorySavedData();
        var drive = NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        var source = new NativeLibraryTerminalExecutionTest.Media(readonly, drive.archive(), NativeLibraryOwnersTest.CONFIG.libraryBookBytes(false));
        var original = source.media.read(source.book);
        var head = LibraryReadHead.State.empty();
        var epoch = UUID.randomUUID(); var device = UUID.randomUUID();
        var requests = new HashSet<DataValue>(); var operations = new ArrayList<String>();
        var identities = roles.stream().map(r -> owners.get(r).flowSnapshot().orElseThrow().state().flow()).toList();
        boolean restored = false;
        DataValue previousCorrelation = null;
        for (int round = 0; round < (readonly ? 1 : 2); round++) {
        if (round > 0) {
            owners.get("caller").reset(limits.get("caller"), true);
            owners.get("caller").setPaused(false, limits.get("caller"), true);
        }
        boolean finished = false;
        for (int tick = 0; tick < 18000; tick++) {
            for (String role : roles) {
                var signals = new LinkedHashMap<String, Integer>();
                for (var link : links) {
                    if (link.left.equals(role)) signals.put(link.lp, NativeLibraryStartupTest.status(owners.get(role), link.lp, limits.get(role), owners.get(link.right), link.rp, limits.get(link.right)));
                    if (link.right.equals(role)) signals.put(link.rp, NativeLibraryStartupTest.status(owners.get(role), link.rp, limits.get(role), owners.get(link.left), link.lp, limits.get(link.left)));
                }
                owners.get(role).tick(limits.get(role), true, signals);
            }
            if (!readonly && !restored && source.commits == 1 && owners.get("b").flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(t ->
                    t.status() == FlowRuntime.Status.WAITING_WRITE && t.locals().fields().get("outcome") instanceof DataValue.RecordValue outcome
                        && outcome.field("value") instanceof DataValue.RecordValue reply && new DataValue.TextValue("written").equals(reply.fields().get("code")))) {
                var savedHistory = history.save(new CompoundTag(), RegistryAccess.EMPTY);
                for (String role : roles) owners.put(role, NativeLibraryOwnersTest.reload(owners.get(role), limits.get(role), devices.get(role)));
                history = XBusHistorySavedData.load(savedHistory, RegistryAccess.EMPTY);
                var encoded = LibraryReadHeadCodec.encode(head, drive); head = LibraryReadHeadCodec.decode(encoded, drive);
                assertEquals(encoded, LibraryReadHeadCodec.encode(head, drive));
                source.book = source.book.copy(); restored = true;
            }
            for (var link : links) NativeLibraryServiceSerialTest.exchange(owners.get(link.left), link.lp, limits.get(link.left), owners.get(link.right), link.rp, limits.get(link.right), history);
            outside.tick(); diskWire.tick();
            if (head.writing().isPresent() && head.writing().orElseThrow().admission().verdict().isEmpty()) head = LibraryReadHead.withWrite(head, head.writing().orElseThrow().seal(false));
            var next = LibraryReadHead.prepareTick(head, source, drive); next.commit().run(); head = next.next();
            if (library.status("s0").receive().frames() > 0 && !Set.of("queued", "transmitting").contains(library.status("s0").transmit())) {
                var received = library.prepareReceive("s0");
                var command = LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));
                assertTrue(requests.add(command.request()), "Commands are correlated, not silently replayed"); operations.add(command.operation());
                DataValue.RecordValue response;
                try {
                    var transition = command.apply(head, source, drive, new LibraryDriveCommand.Admission(device, new LibraryWriteBatch(epoch, round * 18000L + tick)));
                    if (command.operation().equals("ack") && head.writing().isPresent()) source.releaseWrite(head.track().orElseThrow(), head.slot(), head.writing().orElseThrow().operation());
                    head = transition.state(); response = transition.reply();
                } catch (com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess.Failure failure) {
                    response = command.reply(head.sequence(), failure.code(), DataValue.NullValue.INSTANCE);
                }
                assertTrue(received.publish()); assertEquals("queued", library.send("s0", response));
            }
            for (String role : roles) {
                var host = owners.get(role); assertTrue(host.loaded(), role + ": " + host.diagnostic());
                var state = host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED, state.state().status(), role + ": " + state.state().diagnostic());
                for (var child : state.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED, child.status(), role + ": " + child.diagnostic());
            }
            if (owners.get("caller").flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE
                && roles.stream().filter(r -> !r.equals("caller") && !r.equals("gateway")).allMatch(r -> owners.get(r).flowSnapshot().orElseThrow().tasks().contexts().isEmpty())) {
                finished = true; System.out.println("journal readonly=" + readonly + " ticks=" + tick + " commands=" + operations.size()); break;
            }
        }
        assertTrue(finished, () -> owners.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue().flowSnapshot().orElseThrow()).toList().toString());
        var transmission = (DataValue.RecordValue)owners.get("caller").flowSnapshot().orElseThrow().state().locals().field("outcome");
        assertEquals(new DataValue.BooleanValue(true), transmission.field("ok"));
        var frame = (DataValue.RecordValue)transmission.field("v");
        assertNotEquals(previousCorrelation, frame.field("r")); previousCorrelation = frame.field("r");
        var business = (DataValue.RecordValue)frame.field("v");
        assertEquals(new DataValue.BooleanValue(!readonly), business.field("ok"));
        if (readonly) {
            assertEquals(original, source.media.read(source.book)); assertEquals(0, source.commits);
            assertEquals(new DataValue.TextValue("readonly"), ((DataValue.RecordValue)business.field("value")).field("code"));
        } else {
            var readback = (DataValue.RecordValue)business.field("value");
            assertEquals(new DataValue.IntegerValue(7), readback.field("value"));
            assertEquals(new DataValue.IntegerValue(7), source.media.read(source.book).get("reading").value());
            assertEquals(round + 1, source.commits); assertEquals(round + 1, source.releases); assertEquals((round + 1) * 5, head.sequence());
            assertTrue(head.result().isEmpty()); assertEquals(round + 1, Collections.frequency(operations, "write"));
            assertEquals(round + 1, Collections.frequency(operations, "read"));
            assertTrue(restored, "A real written reply must be retained across host/head/history reconstruction");
            for (int i = 1; i < roles.size(); i++) {
                var saved = owners.get(roles.get(i)).flowSnapshot().orElseThrow();
                assertEquals(identities.get(i), saved.state().flow(), "Service owners were not reset or reinstalled");
                assertTrue(saved.receipts().isEmpty(), roles.get(i) + " keeps no completed payload receipts");
            }
        }
        }
    }
    private record Link(String left, String lp, String right, String rp) {}
    private static final class Segment {
        final SerialNetwork network = new SerialNetwork();
        final Map<BlockPos, SerialDevice> devices;
        final CableDiscovery.World world = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos) { return true; }
            public CableDiscovery.Cell cell(BlockPos pos) { return new CableDiscovery.Cell(pos.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        };
        Segment(SerialDevice a, SerialDevice b) { devices = Map.of(BlockPos.ZERO, a, new BlockPos(2,0,0), b); }
        void tick() { network.tick(world, devices, NativeLibraryOwnersTest.CONFIG.communication(false), NativeLibraryOwnersTest.CONFIG.serialMedium(false)); }
    }
}
