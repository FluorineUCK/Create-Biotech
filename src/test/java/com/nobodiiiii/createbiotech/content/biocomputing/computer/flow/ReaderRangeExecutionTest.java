package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public ISA documents on three independent owners, with real serial encoding and rendezvous history. */
class ReaderRangeExecutionTest {
    @ParameterizedTest @ValueSource(strings = {"page5", "page7_restore", "denied", "component_denied", "stale_session"})
    void publicRangeDocumentUsesFiniteReplyAndPreservesTwoStageReturns(String mode) throws Exception {
        var config = NativeLibraryOwnersTest.CONFIG;
        var fixture=InventoryReadSafetyTest.rangeFixture();int offset=mode.equals("page7_restore")?7:5;long[] peaks=new long[3];long taskWork=0;
        var reference=ReaderRangeDocuments.build();
        var la = NativeLibraryOwnersTest.limits("cleric", 5); var lb = NativeLibraryOwnersTest.limits("cleric", 5); var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        var wireB = NativeLibraryOwnersTest.device(lb);
        var wireReader = new SerialDevice(() -> config.readerSerial(false), () -> true, () -> config.serialCodec(false), () -> {});
        wireB.configure("s0", Direction.EAST, true); wireReader.configure("s0", Direction.WEST, true);
        var draft = new ProgramWorkspace(ReaderDriverDocumentTest.load("range_a")); var body = draft.flow().orElseThrow(); var address = BlockSnapshotReader.position(fixture.reader());
        var component = new DataValue.TextValue(BuiltinReadComponents.INVENTORY_ITEM_TAGS);
        var initial = record("target", address, "component", component,"offset",offset,"count",2);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), initial, body.canvas(), body.inline(), body.execution())));
        assertEquals(FlowXBus.image(reference.flow().orElseThrow().program(reference.archive())).code(),FlowXBus.image(draft.snapshot().flow().orElseThrow().program(draft.snapshot().archive())).code(),"Changing page inputs never changes installed image");
        assertEquals(FlowXBus.image(reference.flow().orElseThrow().program(reference.archive())).begin(reference.flow().orElseThrow().initial()).image(),FlowXBus.image(draft.snapshot().flow().orElseThrow().program(draft.snapshot().archive())).begin(initial).image(),"Input page changes preserve frozen image identity");
        var a = NativeLibraryOwnersTest.install(draft.snapshot(), la, null);
        var b = NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("b"), lb, wireB);
        var c = NativeLibraryOwnersTest.install(ReaderDriverDocumentTest.load("c"), lc, null);
        a.setPaused(false, la, true); b.setPaused(false, lb, true); c.setPaused(false, lc, true);
        UUID identity = UUID.randomUUID(), session = UUID.randomUUID();
        var devices = Map.of(fixture.reader().west(2), wireB, fixture.reader(), wireReader);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(fixture.reader().west()) ? CableDiscovery.Kind.CABLE :
                devices.containsKey(p) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        };
        var network = new SerialNetwork(); var history = new XBusHistorySavedData();
        var commands = new ArrayList<DataValue.RecordValue>(); var correlations = new HashSet<DataValue>(); var captures = new AtomicInteger();
        boolean restored = false;
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
                var protocol = new ReaderSamplingProtocol(identity, session, address, mode.equals("component_denied") ? Set.of("create_biotech:block") : Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
                var reply = protocol.respond(!mode.equals("denied"), packet,new ReadLimits(4096,32768,65536), ids -> {
                    fail("Range image must never call full capture");return null;
                },(id,range)->{captures.incrementAndGet();assertEquals(BuiltinReadComponents.INVENTORY_ITEM_TAGS,id);return fixture.capture(id,range);}).orElseThrow();
                int encoded=SerialRecordCodec.encode(reply,config.serialCodec(false)).length;
                assertTrue(encoded<=4096,"Full envelope must fit real Reader port");
                assertTrue(received.replying(reply).publish(),"Actual atomic Prepared.replying, not unbounded host reply queue");
                System.out.println("RANGE_REPLY mode="+mode+" operation="+packet.field("operation")+" bytes="+encoded);
                if (mode.equals("stale_session") && commands.size() == 1) session = UUID.randomUUID();
            }
            for (var owner : List.of(a, b, c)) {
                assertTrue(owner.loaded(), owner.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status(), owner.flowSnapshot().orElseThrow().state().diagnostic().toString());
            }
            for(var context:b.flowSnapshot().orElseThrow().tasks().contexts())taskWork=Math.max(taskWork,context.work());
            peaks[0]=Math.max(peaks[0],NativeLibraryServiceSerialTest.units(a.flowSnapshot().orElseThrow(),la));peaks[1]=Math.max(peaks[1],NativeLibraryServiceSerialTest.units(b.flowSnapshot().orElseThrow(),lb));peaks[2]=Math.max(peaks[2],NativeLibraryServiceSerialTest.units(c.flowSnapshot().orElseThrow(),lc));
            assertTrue(NativeLibraryServiceSerialTest.units(a.flowSnapshot().orElseThrow(), la) <= la.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(b.flowSnapshot().orElseThrow(), lb) <= lb.hardware().memoryUnits());
            assertTrue(NativeLibraryServiceSerialTest.units(c.flowSnapshot().orElseThrow(), lc) <= lc.hardware().memoryUnits());
            if (mode.equals("page7_restore") && commands.size()==2 && !restored && b.flowSnapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(progress -> progress.receiving()).isPresent())) {
                a = NativeLibraryOwnersTest.reload(a, la, null); b = NativeLibraryOwnersTest.reload(b, lb, wireB); c = NativeLibraryOwnersTest.reload(c, lc, null);
                history = XBusHistorySavedData.load(history.save(new CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY); restored = true;
            }
            if (a.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) break;
            if (tick == 7999) fail("Sampler stalled: " + NativeLibraryOwnersTest.summary(a) + " B " + NativeLibraryOwnersTest.summary(b) + " C " + NativeLibraryOwnersTest.summary(c));
        }
        boolean success = Set.of("page5", "page7_restore").contains(mode); var result = a.flowSnapshot().orElseThrow().state().locals();
        assertEquals(new DataValue.BooleanValue(success), result.field("ok"));
        var reply = (DataValue.RecordValue)result.field("reply"); assertEquals(new DataValue.TextValue(success ? "ok" : mode), reply.field("code"));
        assertEquals(success ? 1 : 0, captures.get()); assertEquals(mode.equals("denied") ? 1 : 2, commands.size());
        assertEquals(new DataValue.TextValue("status"), commands.getFirst().field("operation"));
        if (commands.size() == 2) assertEquals(new DataValue.TextValue("sample_range"), commands.get(1).field("operation"));
        if(success){
            var value=(DataValue.RecordValue)reply.field("value");var range=(DataValue.RecordValue)value.field("range");
            assertEquals(new DataValue.IntegerValue(offset),range.field("offset"));assertEquals(new DataValue.IntegerValue(2),range.field("count"));assertEquals(new DataValue.IntegerValue(27),range.field("total"));assertEquals(new DataValue.IntegerValue(offset+2),range.field("next"));
            var rows=(DataValue.ListValue)((DataValue.RecordValue)value.field("components")).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS);
            assertEquals(new DataValue.IntegerValue(offset),((DataValue.RecordValue)rows.values().getFirst()).field("slot"));
            assertEquals(new DataValue.TextValue(offset==5?"minecraft:stone":"minecraft:air"),((DataValue.RecordValue)rows.values().getFirst()).field("item"));
            assertEquals(List.of(offset,offset+1),fixture.slots());
        }else assertTrue(fixture.slots().isEmpty());
        assertEquals(7,fixture.seedCount());
        System.out.println("RANGE_OWNER mode="+mode+" committedPeaks="+Arrays.toString(peaks)+" maxB_taskWork="+taskWork+" A_work="+a.flowSnapshot().orElseThrow().state().work()+" B_work="+b.flowSnapshot().orElseThrow().state().work()+" C_work="+c.flowSnapshot().orElseThrow().state().work());
        if (mode.equals("page7_restore")) assertTrue(restored, "Restore an actual active receive continuation");
        assertEquals(initial, a.flowSnapshot().orElseThrow().initialInputs());
    }
}
