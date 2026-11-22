package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.LibrarySegmentedPumpLifecycleTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Public application composition through real ISA/serial/book components. Not player or native extraction evidence. */
class StockScopedArchiveApplicationTest {
    @Test void segmentedDriverCommitsTheOriginalScopedOrderBeforeAnyDispatch() throws Exception {
        var rig=new Rig(true,true);
        rig.runUntilCommit=true;
        rig.run(true);
        assertEquals(1,rig.media.commits); assertEquals(0,rig.submits); assertEquals(0,rig.dispatches);
        assertTrue(rig.segmentRestored); assertTrue(rig.chunks>1);
        var rows=(DataValue.ListValue)rig.media.media.read(rig.media.book).get("reading").value();
        var row=(DataValue.RecordValue)rows.values().getFirst(); var source=(DataValue.RecordValue)row.field("s");
        assertEquals(rig.original.field("h"),source.field("h")); assertEquals(rig.original.field("d"),source.field("d"));
        assertEquals(new DataValue.TextValue(ADDRESS),((DataValue.RecordValue)source.field("r")).field("address"));
        assertEquals(1,rig.stock.inputs().size(),"Commit alone must not acknowledge the stock order");
        var begin=rig.firstBegin;assertNotNull(begin);var header=(DataValue.RecordValue)begin.data();
        var unsplit=record("kind","library_command","target",begin.target(),"request",begin.request(),"operation","write","sequence",begin.sequence(),
            "data",record("op","put","volume",header.field("volume"),"entry",header.field("entry"),"expected",header.field("expected"),"kind",header.field("kind"),"value",rows));
        int unsplitBytes=LibrarySegmentedWriteDocuments.encodedSize(unsplit,rig.limits.archive());
        System.out.println("ORIGINAL_UNSPLIT_WRITE_BYTES="+unsplitBytes+" chunks="+rig.chunks);
        assertTrue(unsplitBytes>4096,"The original complete order must still exceed one physical frame");
    }
    @Test void segmentedArchiveReadsBackOriginalOrderBeforeDispatchAndAcknowledgement() throws Exception {
        var rig=new Rig(true,true,true);
        assertEquals(new DataValue.TextValue("acknowledged"),rig.run(true).field("code"));
        assertTrue(rig.segmentRestored);assertTrue(rig.readRestored);assertTrue(rig.finishRestored);
        assertEquals(1,rig.media.commits);assertTrue(rig.chunks>1);assertTrue(rig.readChunks>1);assertEquals(1,rig.readCloses);
        assertEquals(1,rig.submits);assertEquals(1,rig.preparations);assertEquals(1,rig.dispatches);
        assertEquals(1,rig.acknowledgements);assertTrue(rig.stock.inputs().isEmpty());assertTrue(rig.head.reading().isEmpty());
    }
    @Test void sharedSegmentedServiceReadsWritesAndVerifiesBeforeDispatchAndAcknowledgement() throws Exception {
        var rig=new Rig(true,true,true,true);
        var decoderIdentity=rig.hosts.get("read_decode").flowSnapshot().orElseThrow().state().flow();
        var coordinatorIdentity=rig.hosts.get("coordinator").flowSnapshot().orElseThrow().state().flow();
        assertEquals(new DataValue.TextValue("acknowledged"),rig.run(true).field("code"));
        assertTrue(rig.segmentRestored);assertTrue(rig.readRestored);assertTrue(rig.finishRestored);
        assertEquals(1,rig.media.commits);assertTrue(rig.chunks>1);assertTrue(rig.postCommitReadChunks>1);
        assertEquals(2,rig.readCloses,"Startup and write verification must each close their actual Library read session");
        assertEquals(0,rig.reads,"The application must not fall back to an unsplit whole-value read");
        assertEquals(1,rig.submits);assertEquals(1,rig.preparations);assertEquals(1,rig.dispatches);
        assertEquals(1,rig.acknowledgements);assertTrue(rig.stock.inputs().isEmpty());assertTrue(rig.head.reading().isEmpty());
        var decoded=rig.hosts.get("read_decode").flowSnapshot().orElseThrow();
        assertEquals(decoderIdentity,decoded.state().flow());assertEquals(0,decoded.control().generation());
        assertTrue(decoded.receipts().isEmpty(),"Both read transactions must retire decoded payload receipts without resetting the Computer");
        assertTrue(decoded.tasks().slots().entries().getFirst().incarnation()>=1,"The physical reader must reuse a finite task slot");
        var coordinator=rig.hosts.get("coordinator").flowSnapshot().orElseThrow();
        assertEquals(coordinatorIdentity,coordinator.state().flow());assertEquals(0,coordinator.control().generation());
        assertTrue(coordinator.receipts().isEmpty(),"Startup and write sessions must retire coordinator payload receipts without resetting the host");
        assertEquals(2,coordinator.tasks().slots().entries().size(),"One supervisor and one reused worker, not an unbounded task per order");
        assertTrue(coordinator.tasks().slots().entries().stream().anyMatch(s->s.incarnation()>=1));
    }
    private static final String ADDRESS = "alternate-supply";
    @Test void libraryDeviceCommitsTheCompleteTwoRowLedgerWithinDefaultBuffers() throws Exception {
        var rig = new Rig(true, true, true, true);
        var candidate = twoRowCandidate(rig);
        var original = rig.media.book.copy();
        var encoded = SerialRecordCodec.encode(record("value", candidate),
            new SerialRecordCodec.Limits(131072,131072,rig.drive.archive()));
        assertEquals(5495, encoded.length, "Use the full original two-row ledger, including item components");
        var state = new LibraryReadHead.State(2, Optional.of(new LibraryTrackAddress(1,0,0)), 2,
            Optional.empty(), Optional.empty());
        var admission = new LibraryDriveCommand.Admission(rig.librarian,new LibraryWriteBatch(rig.epoch,1));
        state = new LibraryDriveCommand(rig.libraryTarget,new DataValue.TextValue("begin"),"write_begin",2,
            record("volume","plant","entry","reading","expected",rig.media.media.stat(rig.media.book,"reading").field("version"),
                "kind","DATA","format","typed_record_v1","bytes",encoded.length)).apply(state,rig.media,rig.drive,admission).state();
        for (int offset=0; offset<encoded.length; offset+=80) {
            var part = new ArrayList<DataValue>();
            for(int i=offset; i<Math.min(offset+80,encoded.length); i++) part.add(new DataValue.IntegerValue(encoded[i]));
            var at = offset; var before = state;
            state = assertDoesNotThrow(() -> new LibraryDriveCommand(rig.libraryTarget,new DataValue.TextValue("chunk"),"write_chunk",3,
                record("offset",at,"bytes",new DataValue.NbtValue(7,new DataValue.ListValue(part))))
                .apply(before,rig.media,rig.drive,admission).state(), "Chunk at " + offset);
            while(state.transfer().orElseThrow().remaining()>0) state=LibraryReadHead.prepareTick(state,rig.media,rig.drive).next();
            if(offset==800) state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,rig.drive),rig.drive);
            assertTrue(net.minecraft.world.item.ItemStack.matches(original,rig.media.book));
        }
        state = new LibraryDriveCommand(rig.libraryTarget,new DataValue.TextValue("commit"),"write_commit",3,DataValue.NullValue.INSTANCE)
            .apply(state,rig.media,rig.drive,admission).state();
        System.out.println("LEDGER_DEVICE bytes="+encoded.length+" buffer="+rig.drive.bufferBytes()
            +" decoderReservation="+state.transfer().orElseThrow().commit().orElseThrow().reservedBytes());
        long steps=0; boolean restored=false;
        for(int tick=0; tick<20000 && state.result().isEmpty(); tick++) {
            if(state.transfer().flatMap(LibraryWriteTransfer::commit).isPresent()) {
                steps=state.transfer().orElseThrow().commit().orElseThrow().steps();
                if(!restored && steps>500) {
                    state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,rig.drive),rig.drive);restored=true;
                }
            }
            if(state.writing().isPresent()&&state.writing().orElseThrow().admission().verdict().isEmpty())
                state=LibraryReadHead.withWrite(state,state.writing().orElseThrow().seal(false));
            var next=LibraryReadHead.prepareTick(state,rig.media,rig.drive);
            next.commit().run();state=next.next();
        }
        assertEquals("written",state.result().orElseThrow().code(),"Decoder steps before result: "+steps);
        assertTrue(restored);assertEquals(1,rig.media.commits);
        assertEquals(candidate,rig.media.media.read(rig.media.book).get("reading").value());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={8192,12288})
    void publishedDecoderHandlesTheTwoRowLedgerWithoutHidingTheChildFailure(long workspaceUnits) throws Exception {
        var rig=new Rig(true,true,true,true);var candidate=twoRowCandidate(rig);
        var encoded=SerialRecordCodec.encode(record("value",candidate),new SerialRecordCodec.Limits(131072,131072,rig.drive.archive()));
        assertEquals(5495,encoded.length);
        var parts=new ArrayList<DataValue>();
        for(int offset=0;offset<encoded.length;offset+=80) {
            var bytes=new ArrayList<DataValue>();
            for(int i=offset;i<Math.min(offset+80,encoded.length);i++)bytes.add(new DataValue.IntegerValue(encoded[i]));
            parts.add(new DataValue.NbtValue(7,new DataValue.ListValue(bytes)));
        }
        var version=rig.media.media.stat(rig.media.book,"reading").field("version");
        var context=record("r",record("o","download","d",DataValue.NullValue.INSTANCE,"e","download","l",false),"s",7,
            "d",record("bytes",encoded.length,"format","typed_record_v1","kind","DATA","version",version));
        var contextType=new DataType.RecordType(Map.of("r",NativeLibraryReadDocuments.ROW,"s",DataType.Scalar.ANY,"d",DataType.Scalar.ANY));
        var caller=install(peer(Map.of("context",contextType),record("context",context),false,"""
            TYPE Result RECORD ok BOOLEAN value ANY
            REG result Result
            XBUS.WRITE x0 context
            XBUS.READ result x0
            RET
            """),rig.limits);
        var requestType=new DataType.RecordType(Map.of("o",DataType.Scalar.BOOLEAN,"p",new DataType.ListType(DataType.Scalar.NBT),
            "k",DataType.Scalar.TEXT,"v",DataType.Scalar.ANY));
        var collector=install(peer(Map.of("request",requestType),record("request",record("o",true,"p",new DataValue.ListValue(parts),"k","DATA","v",version)),true,"""
            TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
            TYPE Context RECORD r Row s ANY d ANY
            REG context Context
            XBUS.READ context x0
            XBUS.WRITE x0 request
            RET
            """),rig.limits);
        var decoderDocument=LibrarySegmentedWriteDocuments.build("session_read_decode");
        assertEquals(new DataValue.IntegerValue(12288),decoderDocument.flow().orElseThrow().initial().field("workspace"));
        var body=decoderDocument.flow().orElseThrow();
        var initial=new LinkedHashMap<>(body.initial().fields());initial.put("workspace",new DataValue.IntegerValue(workspaceUnits));
        var draft=new ProgramWorkspace(decoderDocument);
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(initial),body.canvas(),body.inline(),body.execution())));
        decoderDocument=draft.snapshot();
        var decoder=install(decoderDocument,rig.limits);
        boolean restored=false;
        for(int tick=0;tick<10000;tick++) {
            for(var host:List.of(caller,collector,decoder)) {
                var before=host.flowSnapshot().orElseThrow();
                host.tick(tick,rig.limits,true,Map.of("p0",1));
                var saved=host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),()->"Root: "+saved.state().diagnostic()
                    +" outcome="+ComputerValueDisplay.lines(saved.state().locals().fields().getOrDefault("result",DataValue.NullValue.INSTANCE),1000)
                    +" prior tasks="+before.tasks().contexts().stream().map(t->t.assembly().map(a->"pc="+a.pc()+" units="+t.controlUnits()+" registers="+a.registers().entrySet().stream()
                        .map(e->e.getKey()+":"+e.getValue().storageUnits()).toList())).toList()
                    +" prior receipts="+before.receipts().stream().mapToLong(r->r.value().storageUnits()).sum());
                for(var task:saved.tasks().contexts())assertNotEquals(FlowRuntime.Status.FAILED,task.status(),task.diagnostic().toString());
            }
            decoder.exchange("x0",rig.limits,true,caller,"x0",rig.limits,true);
            decoder.exchange("x1",rig.limits,true,collector,"x0",rig.limits,true);
            if(!restored && decoder.flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(t->t.assembly()
                .flatMap(a->a.build()).filter(b->new DataValue.TextValue("value_decode").equals(b.value().fields().get("kind"))).isPresent())) {
                decoder=NativeLibraryOwnersTest.reload(decoder,rig.limits,null);restored=true;
            }
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) {
                var result=caller.flowSnapshot().orElseThrow().state().locals().field("result");
                var expected=workspaceUnits==8192?record("ok",false,"value",record("code","decode_limit","value",DataValue.NullValue.INSTANCE))
                    :record("ok",true,"value",record("kind","DATA","version",version,"value",candidate));
                assertEquals(expected,result,
                    ()->ComputerValueDisplay.lines(result,1000).toString());
                assertTrue(restored,"Restore the actual partially decoded operation, not only its input");
                return;
            }
        }
        fail("Bounded isolated decode did not finish: "+decoder.diagnostic());
    }
    private static DataValue.ListValue twoRowCandidate(Rig rig) {
        var oldScope = record("source", UUID.randomUUID().toString(), "device", UUID.randomUUID().toString(),
            "revision", 7, "target", rig.target, "address", ADDRESS);
        var old = record("s", record("d", UUID.randomUUID().toString(), "h", rig.original.field("h"), "r", oldScope),
            "j", UUID.randomUUID().toString() + ":1");
        rig.media.book = rig.media.media.put(rig.media.book, "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.ListValue(List.of(old))));
        var scope = record("source", rig.sender().toString(), "device", rig.output.toString(),
            "revision", 7, "target", rig.target, "address", ADDRESS);
        var row = record("s", record("d", rig.original.field("d"), "h", rig.original.field("h"), "r", scope),
            "j", UUID.randomUUID().toString() + ":10");
        // The existing row came through the book codec, not a shared Java subtree of the new order.
        var persistedOld = ((DataValue.ListValue)rig.media.media.read(rig.media.book).get("reading").value()).values().getFirst();
        return new DataValue.ListValue(List.of(persistedOld, row));
    }
    @Test void existingLargeLedgerKeepsItsOriginalRowWhenAnotherScopedOrderIsArchived() throws Exception {
        var rig = new Rig(true, true, true, true);
        var oldScope = record("source", UUID.randomUUID().toString(), "device", UUID.randomUUID().toString(),
            "revision", 7, "target", rig.target, "address", ADDRESS);
        var old = record("s", record("d", UUID.randomUUID().toString(), "h", rig.original.field("h"), "r", oldScope),
            "j", UUID.randomUUID().toString() + ":1");
        rig.previousEntries = List.of(old);
        rig.media.book = rig.media.media.put(rig.media.book, "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.ListValue(rig.previousEntries)));
        var originalVersion = rig.media.media.stat(rig.media.book, "reading").field("version");
        var coordinator = rig.hosts.get("coordinator").flowSnapshot().orElseThrow().state().flow();
        assertEquals(new DataValue.TextValue("acknowledged"), rig.run(true).field("code"));
        var stored = (DataValue.ListValue)rig.media.media.read(rig.media.book).get("reading").value();
        assertEquals(2, stored.values().size()); assertEquals(old, stored.values().getFirst());
        assertEquals(originalVersion, ((DataValue.RecordValue)rig.firstBegin.data()).field("expected"));
        assertNotEquals(originalVersion, rig.media.media.stat(rig.media.book, "reading").field("version"));
        assertEquals(1, rig.media.commits); assertEquals(1, rig.dispatches); assertEquals(1, rig.acknowledgements);
        assertEquals(2, rig.readCloses); assertEquals(0, rig.reads);
        assertTrue(rig.readChunks > rig.postCommitReadChunks, "The initial nonempty ledger also uses the segmented reader");
        assertTrue(rig.segmentRestored); assertTrue(rig.readRestored); assertTrue(rig.finishRestored);
        var saved = rig.hosts.get("coordinator").flowSnapshot().orElseThrow();
        assertEquals(coordinator, saved.state().flow()); assertEquals(0, saved.control().generation());
        assertTrue(saved.receipts().isEmpty()); assertEquals(2, saved.tasks().slots().entries().size());
    }
    @BeforeAll static void bootstrap() throws Exception { NativeLibraryTerminalSessionTest.bootstrap(); }

    @Test void liveHandoffPreservesScopeThroughDeliveryPollingAndCurrentSessionAcknowledgement() throws Exception {
        var rig = new Rig(true);
        assertEquals(new DataValue.TextValue("acknowledged"),rig.run(true).field("code"));
        assertTrue(rig.restored); assertTrue(rig.finishRestored);
        assertEquals(1,rig.submits);assertEquals(1,rig.preparations);assertEquals(1,rig.dispatches);
        assertEquals(2,rig.queries);assertEquals(1,rig.acknowledgements);assertTrue(rig.stock.inputs().isEmpty());
        assertEquals(1,rig.media.commits);assertEquals(2,rig.reads);assertEquals(1,rig.saved.orders().size());
        var archived=(DataValue.RecordValue)((DataValue.ListValue)rig.media.media.read(rig.media.book).get("reading").value()).values().getFirst();
        var scope=((DataValue.RecordValue)archived.field("s")).field("r");
        assertEquals(scope,rig.queriedScope,"Completion never substitutes newly sampled routing information");
    }

    @Test void archiveBeforeSubmitSurvivesRestoreAndResetButNeverRebindsAnOldOrderToANewDispatchScope() throws Exception {
        var rig = new Rig();
        assertEquals(new DataValue.TextValue("accepted"), rig.run(true).field("code"));
        var job = rig.hosts.get("submit").flowSnapshot().orElseThrow().state().locals().field("job");
        assertTrue(rig.restored); assertEquals(1, rig.media.commits); assertEquals(1, rig.preparations);
        assertEquals(1, rig.dispatches); assertEquals(1, rig.saved.orders().size());
        var book = rig.media.book.copy();

        // Explicitly restart application workers, not the communication identity or the journal.
        rig.session = UUID.randomUUID(); rig.resetWorkers();
        assertEquals(new DataValue.TextValue("existing"), rig.run(false).field("code"));
        assertEquals(job, rig.hosts.get("submit").flowSnapshot().orElseThrow().state().locals().field("job"));
        assertEquals(1, rig.media.commits); assertEquals(1, rig.preparations); assertEquals(1, rig.dispatches);
        assertTrue(net.minecraft.world.item.ItemStack.matches(book, rig.media.book));

        rig.output = UUID.randomUUID(); rig.resetWorkers();
        assertEquals(record("c", "source_conflict", "j", ""), rig.run(false));
        assertEquals(2, rig.submits, "Changed scope must stop before any material request");
        assertEquals(1, rig.saved.orders().size()); assertEquals(1, rig.preparations); assertEquals(1, rig.dispatches);
        assertEquals(1, rig.media.commits); assertTrue(net.minecraft.world.item.ItemStack.matches(book, rig.media.book));
        assertEquals(4, rig.reads, "Startup on each run plus exactly one verified write readback");
    }

    private record End(String name, String pin) {}
    private record Link(End left, End right) {
        Link(String l, String lp, String r, String rp) { this(new End(l,lp), new End(r,rp)); }
    }
    private static final class Rig {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits = NativeLibraryOwnersTest.limits("cleric",5);
        final com.nobodiiiii.createbiotech.content.biocomputing.library.drive.LibraryDriveLimits drive = NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        final NativeLibraryTerminalExecutionTest.Media media = new NativeLibraryTerminalExecutionTest.Media(false,drive.archive(),NativeLibraryOwnersTest.CONFIG.libraryBookBytes(false));
        final Map<String,ComputerBlockHost> hosts = new LinkedHashMap<>();
        final SerialDevice bWire=NativeLibraryOwnersTest.device(limits), library=NativeLibraryOwnersTest.device(limits),
            gatewayWire=NativeLibraryOwnersTest.device(limits), production=NativeLibraryOwnersTest.device(limits);
        final DataValue.RecordValue libraryTarget=record("x",2,"y",0,"z",0), target=record("x",2,"y",0,"z",4);
        DataValue.RecordValue original = record("d","stock-output", "h", record("kind","native_order","sequence",7,
            "player","player-id","address",ADDRESS,"order",new DataValue.NbtValue(10,record())));
        final List<Link> links = new ArrayList<>(List.of(new Link("submit","x0","packet","x0"), new Link("packet","x1","gateway","x0"),
            new Link("submit","x1","archive","x0"), new Link("archive","x1","read","x0"),new Link("archive","x1","check","x0"),
            new Link("check","x1","identity","x0"),new Link("identity","x1","store","x0"),new Link("store","x1","coordinator","x1"),
            new Link("coordinator","x0","adapter","x0"),new Link("read","x1","adapter","x0"),
            new Link("adapter","x1","b","x0"),new Link("b","x1","c","x0")));
        final List<Set<End>> groups = new ArrayList<>();
        final List<Link> signals = new ArrayList<>(List.of(new Link("submit","p0","archive","p1"),
            new Link("archive","p0","read","p0"),new Link("archive","p0","check","p0")));
        final Map<BlockPos,SerialDevice> devices = new LinkedHashMap<>(Map.of(BlockPos.ZERO,bWire,new BlockPos(2,0,0),library,
            new BlockPos(0,0,4),gatewayWire,new BlockPos(2,0,4),production));
        final SerialDevice stockWire=NativeLibraryOwnersTest.device(limits);
        final DataValue.RecordValue stockTarget=record("x",1,"y",0,"z",5);
        final StockApplication.Limits stockLimits=new StockApplication.Limits(8,8,100000,1024,128);
        StockApplication.State stock; StockApplication.Access stockAccess;
        final boolean live;
        DataValue queriedScope; int queries,acknowledgements; boolean finishRestored;
        List<DataValue> previousEntries = List.of();
        final CableDiscovery.World world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) {
                boolean cable=p.equals(new BlockPos(1,0,0))||p.equals(new BlockPos(1,0,4));
                return new CableDiscovery.Cell(cable?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);
            }
        };
        final SerialNetwork network = new SerialNetwork();
        final Map<Link,XBusExchange.Status> exchanges=new LinkedHashMap<>();
        final Map<Link,Integer> resourceWaits=new HashMap<>();
        LibraryReadHead.State head = LibraryReadHead.State.empty();
        final UUID librarian=UUID.randomUUID(), epoch=UUID.randomUUID();
        UUID output=UUID.randomUUID(), session=UUID.randomUUID();
        Snapshot saved=Snapshot.empty(); int preparations,dispatches,submits,reads; long tick; boolean restored;
        final ProductionOrderService orders = new ProductionOrderService(new Journal() {
            public Snapshot load() { return saved; }
            public boolean commit(long expected,Snapshot next) { if(saved.revision()!=expected)return false; saved=next;return true; }
        },new Backend() {
            public boolean authorized(UUID sender) { return sender.equals(sender()); }
            public boolean supports(String extension,int version) { return extension.equals(live?"create.packaging":"test")&&version==1; }
            public Preparation prepare(UUID sender,DataValue.RecordValue data) { preparations++;
                if(live)assertEquals(record("items",new DataValue.ListValue(List.of(record("item","minecraft:paper","count",7,
                    "components",new DataValue.NbtValue(10,record("!minecraft:rarity",new DataValue.NbtValue(10,record()))))))),data);
                return Preparation.ready(live
                ?new Plan("create.packaging",1,List.of(record("network",epoch.toString(),"address",ADDRESS,"revision",1,"data",data)))
                :new Plan("test",1,List.of(data))); }
            public Dispatch dispatch(Operation operation,DataValue.RecordValue data) { dispatches++;return Dispatch.accepted(record("fixture",true)); }
            public Observation observe(Operation operation,DataValue.RecordValue receipt) { return live&&queries>=1?Observation.complete(record("fixture",true)):Observation.pending(); }
        },new Limits(8,4,65536));

        Rig() throws Exception {this(false);}
        boolean runUntilCommit, segmentRestored, readRestored; final boolean combined, sharedSession; int chunks, readChunks, postCommitReadChunks, readCloses; LibraryDriveCommand firstBegin;
        Rig(boolean live) throws Exception {this(live,false);}
        Rig(boolean live,boolean segmented) throws Exception {this(live,segmented,false);}
        Rig(boolean live,boolean segmented,boolean combined) throws Exception {
            this(live,segmented,combined,false);
        }
        Rig(boolean live,boolean segmented,boolean combined,boolean sharedSession) throws Exception {
            this.live=live;this.combined=combined;this.sharedSession=sharedSession;
            media.book=media.media.put(media.book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.ListValue(List.of())));
            bWire.configure("s0",Direction.EAST,true); gatewayWire.configure("s0",Direction.EAST,true);
            library.configure("s0",Direction.WEST,true);production.configure("s0",Direction.WEST,true);
            var roles=new ArrayList<>(List.of("submit","archive","read","check","identity","store","coordinator","adapter","b","c","packet","gateway"));
            if(segmented) {
                roles.addAll(List.of("buffer","pump"));
                links.remove(new Link("coordinator","x0","adapter","x0"));
                links.addAll(List.of(new Link("coordinator","x0","buffer","x0"),new Link("buffer","x1","pump","x0"),new Link("pump","x1","adapter","x0")));
            }
            if(combined) {
                var oversized=new ArrayList<String>();
                for(var name:List.of("compact_reply","archive_store","archive_coordinator","archive_read_decode","archive_read_collect")) {
                    var doc=LibrarySegmentedWriteDocuments.build(name);var usage=doc.flow().orElseThrow().program(doc.archive()).usage(new ResourceLimits(4096,65536,8));
                    System.out.println("ARCHIVE_ADMISSION "+name+" "+usage);
                    if(usage.programUnits()>limits.hardware().programUnits())oversized.add(name+"="+usage.programUnits());
                }
                assertTrue(oversized.isEmpty(),"Explicit archive workers exceed hardware: "+oversized);
                roles.addAll(List.of("compact_reply","read_decode","read_collect","read_verify","read_stream"));
                links.addAll(List.of(new Link("coordinator","x1","read_decode","x0"),new Link("read_decode","x1","read_collect","x0"),
                    new Link("read_collect","x1","read_verify","x0"),new Link("read_verify","x1","read_stream","x0"),new Link("read_stream","x1","adapter","x0")));
                signals.addAll(List.of(new Link("coordinator","p0","store","p0"),new Link("coordinator","p0","read_decode","p0")));
                var adapterInput=new End("adapter","x0");var projectedInput=new End("compact_reply","x0");
                links.replaceAll(link->new Link(link.left.equals(adapterInput)?projectedInput:link.left,link.right.equals(adapterInput)?projectedInput:link.right));
                links.add(new Link("compact_reply","x1","adapter","x0"));
            }
            if(sharedSession) {
                roles.add("slice");
                links.remove(new Link("buffer","x1","pump","x0"));
                links.addAll(List.of(new Link("buffer","x1","slice","x0"),new Link("slice","x1","pump","x0")));
                links.remove(new Link("read","x1","compact_reply","x0"));
                links.add(new Link("read","x1","coordinator","x1"));
                signals.add(new Link("coordinator","p0","read","p1"));
            }
            if(live)roles.addAll(List.of("handoff","finish","guard","ack_current","ack_exchange","preparer","plain_order","item"));
            for(var role:roles) {
                var document = sharedSession&&role.equals("slice")?LibrarySegmentedWriteDocuments.build("step_slice")
                    :sharedSession&&Set.of("read_stream","read_verify").contains(role)?LibrarySegmentedWriteDocuments.build("step_"+role)
                    :sharedSession&&role.equals("compact_reply")?LibrarySegmentedWriteDocuments.build("range_reply")
                    :sharedSession&&Set.of("store","coordinator","read","buffer","read_decode").contains(role)?LibrarySegmentedWriteDocuments.build("session_"+role)
                    :sharedSession&&role.equals("pump")?LibrarySegmentedWriteDocuments.build("step_pump")
                    :combined&&Set.of("store","coordinator","read_decode","read_collect").contains(role)?LibrarySegmentedWriteDocuments.build("archive_"+role)
                    :combined&&role.equals("compact_reply")?LibrarySegmentedWriteDocuments.build("compact_reply")
                    :combined&&role.equals("adapter")?LibrarySegmentedWriteDocuments.build("read_adapter")
                    :combined&&Set.of("read_verify","read_stream").contains(role)?LibrarySegmentedWriteDocuments.build(role)
                    :segmented&&Set.of("store","buffer","pump","adapter","coordinator").contains(role)?LibrarySegmentedWriteDocuments.build(role)
                    :role.equals("submit")&&live?StockScopedArchiveDocuments.build("live")
                    :role.equals("preparer")?StockNativeSupplyDocuments.build("prepare")
                    :role.equals("plain_order")?StockNativeSupplyDocuments.build(role)
                    :role.equals("item")?StockNativeMaterialDocuments.build(role)
                    :StockScopedArchiveDocuments.ROLES.contains(role)?StockScopedArchiveDocuments.build(role)
                    :Set.of("guard","ack_current","ack_exchange").contains(role)?ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(
                        java.nio.file.Path.of("examples/biocomputing/stock_native_"+role+".bcp")).strip()),limits.archive())
                    : role.equals("store")?StockNativeArchiveDocuments.build()
                    : role.equals("packet")?StockNativeSupplyDocuments.build("packet_scoped")
                    : role.equals("gateway")?SerialExchangeDriverDocuments.build()
                    : Set.of("b","c").contains(role)?NativeLibraryServiceDocuments.build(role):NativeClusterJournalDocuments.build(role);
                var body=document.flow().orElseThrow(); var fields=new LinkedHashMap<>(body.initial().fields());
                if(segmented&&Set.of("store","buffer","pump","adapter","coordinator").contains(role))System.out.println("SEGMENTED "+role+" "+body.program(document.archive()).usage(new ResourceLimits(4096,65536,64)));
                if(role.equals("submit")) { if(!live){fields.put("source",original);}fields.put("target",target); }
                if(role.equals("handoff"))fields.put("target",target);
                if(role.equals("finish")){fields.put("poll_ticks",new DataValue.IntegerValue(1));fields.put("poll_limit",new DataValue.IntegerValue(3));}
                if(role.equals("ack_exchange"))fields.put("output",stockTarget);
                if(role.equals("adapter"))fields.put("target",libraryTarget);
                var host=new ComputerBlockHost(()->{},p->{},role.equals("b")?bWire:role.equals("gateway")?gatewayWire:null);
                host.installFlow(body.program(document.archive()),new DataValue.RecordValue(fields),limits,true);
                host.setPaused(false,limits,true);hosts.put(role,host);
            }
            if(live) {
                var app=new StockApplication.Application(sender(),hosts.get("gateway").flowSnapshot().orElseThrow().state().flow());
                stockAccess=new StockApplication.Access(app,true,false);
                stock=StockApplication.bind(StockApplication.closed(UUID.randomUUID(),UUID.randomUUID()),app,UUID.randomUUID()).state();
                var player=UUID.randomUUID();var order=com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.snapshot(
                    net.minecraft.nbt.TagParser.parseTag("{ordered_stacks:{entries:[{item_stack:{id:\"minecraft:paper\",count:2,components:{\"!minecraft:rarity\":{}}},count:7}]},ordered_crafts:[]}"),
                    new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(65536,limits.archive().maxValueUnits(),limits.archive()));
                var request=StockApplication.Request.nativeOrder(stock.fence(),stock.nextSequence(),ADDRESS,order);
                var admission=StockApplication.confirm(stock,stockAccess,player,request,stockLimits);assertEquals("input_queued",admission.code());stock=admission.state();
                var source=record("state",record("device",stock.device().toString(),"session",stock.session().toString(),"revision",stock.revision()),
                    "head",record("kind","native_order","sequence",request.sequence(),"player",player.toString(),"address",ADDRESS,"order",order));
                original=record("d",stock.device().toString(),"h",source.field("head"));
                stock=StockApplicationCodec.load(StockApplicationCodec.save(stock),UUID.randomUUID());
                var view=new DataType.RecordType(Map.of("v",DataType.Scalar.ANY));
                var feeder=new ComputerBlockHost(()->{});
                // Only the read snapshot is supplied here. Existing public ISA performs ALL NBT/material conversion.
                feeder.installFlow(new FlowProgram(Map.of("view",view),List.of(new FlowProgram.Write("sampled_view","x0","view"))).withAssembly(),
                    record("view",record("v",record("value",record("components",record("create_biotech:stock",source))))),limits,true);
                feeder.setPaused(false,limits,true);hosts.put("sample",feeder);
                stockWire.configure("s0",Direction.NORTH,true);devices.put(new BlockPos(1,0,5),stockWire);
                links.addAll(List.of(new Link("preparer","x0","handoff","x0"),new Link("handoff","x1","submit","x1"),
                    new Link("sample","x0","preparer","x0"),new Link("preparer","x1","plain_order","x0"),new Link("plain_order","x1","item","x0"),
                    new Link("handoff","x1","finish","x0"),new Link("finish","x1","packet","x0"),new Link("finish","x0","guard","x0"),
                    new Link("guard","x1","ack_current","x0"),new Link("ack_current","x1","ack_exchange","x0"),new Link("ack_exchange","x1","gateway","x0")));
                signals.addAll(List.of(new Link("preparer","p0","handoff","p1"),new Link("submit","p1","handoff","p0"),
                    new Link("handoff","p0","finish","p0"),new Link("finish","p1","guard","p0")));
            }
            for(var link:links) {
                var merged=new LinkedHashSet<>(List.of(link.left,link.right));
                for(var it=groups.iterator();it.hasNext();) { var group=it.next();if(!Collections.disjoint(group,merged)){merged.addAll(group);it.remove();} }
                groups.add(merged);
            }
        }
        UUID sender() { return hosts.get("gateway").flowSnapshot().orElseThrow().state().computer(); }
        XBusAccess.Network bus(Set<End> group) {
            return XBusAccess.network(group.stream().map(e->NativeLibraryStartupTest.access(hosts.get(e.name),e.pin,limits)).toList());
        }
        int signal(String name,String pin) { return hosts.get(name).flowSnapshot().orElseThrow().state().signals().outputs().getOrDefault(pin,0); }
        SimpleIoNetwork.Pin signalPin(End end) { return new SimpleIoNetwork.Pin(hosts.get(end.name).flowSnapshot().orElseThrow().state().computer(),end.pin); }
        SimpleIoNetwork.Frame signalFrame() {
            var pins=new LinkedHashMap<SimpleIoNetwork.Pin,SimpleIoNetwork.Port>();var wires=new ArrayList<SimpleIoNetwork.Wire>();
            for(var wire:signals) {
                for(var e:List.of(wire.left,wire.right)) {
                    var outputs=hosts.get(e.name).flowSnapshot().orElseThrow().state().signals().outputs();
                    pins.put(signalPin(e),outputs.containsKey(e.pin)?SimpleIoNetwork.Port.output(outputs.get(e.pin)):SimpleIoNetwork.Port.input());
                }
                wires.add(new SimpleIoNetwork.Wire(signalPin(wire.left),signalPin(wire.right)));
            }
            return SimpleIoNetwork.sample(pins,wires);
        }
        void resetWorkers() {
            for(var name:List.of("submit","archive","read","check","identity","store")) {
                var host=hosts.get(name);
                if(host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE)continue;
                host.setPaused(true,limits,true);host.reset(limits,true);host.setPaused(false,limits,true);
            }
        }
        DataValue.RecordValue run(boolean restore) {
            // Observation window only, not a device deadline or CPU/rate increase. The two-row case
            // pays for its initial read, 69 write ranges and 69 verification ranges (>100000 ticks).
            for(int step=0;step<(combined?150000:runUntilCommit?60000:22000);step++) {
                tick++;
                var signals=new LinkedHashMap<String,Map<String,Integer>>();hosts.keySet().forEach(k->signals.put(k,new HashMap<>()));
                for(var group:groups) {var bus=bus(group);for(var e:group)signals.get(e.name).put(e.pin,bus.statusCode(NativeLibraryStartupTest.pin(hosts.get(e.name),e.pin)));}
                var frame=signalFrame();for(var e:hosts.entrySet())signals.get(e.getKey()).putAll(frame.inputs(e.getValue().flowSnapshot().orElseThrow().state().computer()));
                for(var e:hosts.entrySet())e.getValue().tick(tick,limits,true,signals.get(e.getKey()));
                for(var group:groups) {var bus=bus(group);for(var link:links)if(group.contains(link.left))
                    exchanges.put(link,bus.exchange(NativeLibraryStartupTest.pin(hosts.get(link.left.name),link.left.pin),NativeLibraryStartupTest.pin(hosts.get(link.right.name),link.right.pin)));}
                if(runUntilCommit||combined)for(var e:exchanges.entrySet()) {
                    int stalled=e.getValue()==XBusExchange.Status.MEMORY_LIMIT?resourceWaits.getOrDefault(e.getKey(),0)+1:0;
                    resourceWaits.put(e.getKey(),stalled);
                    assertTrue(stalled<1000,()->"Persistent resource wait at "+e.getKey()+": "+e.getValue()+" "+driverPositions());
                }
                network.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false));
                serviceLibrary();serviceProduction();if(live)serviceStock();if(!saved.orders().isEmpty())orders.advance();
                if((runUntilCommit||combined) && head.transfer().isPresent() && head.transfer().orElseThrow().received()>0 && !segmentRestored) {
                    assertEquals(0,media.commits);
                    head=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(head,drive),drive);
                    for(var name:List.of("buffer","pump"))hosts.put(name,NativeLibraryOwnersTest.reload(hosts.get(name),limits,null));
                    if(sharedSession)for(var name:List.of("slice","coordinator"))
                        hosts.put(name,NativeLibraryOwnersTest.reload(hosts.get(name),limits,null));
                    segmentRestored=true;
                }
                if(combined && postCommitReadChunks==1 && !readRestored) {
                    assertEquals(0,submits);assertEquals(1,stock.inputs().size());
                    head=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(head,drive),drive);
                    for(var name:List.of("read_decode","read_collect","read_verify","read_stream"))hosts.put(name,NativeLibraryOwnersTest.reload(hosts.get(name),limits,null));
                    if(sharedSession)hosts.put("coordinator",NativeLibraryOwnersTest.reload(hosts.get("coordinator"),limits,null));
                    readRestored=true;
                }
                for(var e:hosts.entrySet()) {
                    assertTrue(e.getValue().loaded(),()->"Host suspended: "+e.getKey()+" "+e.getValue().diagnostic()+" "+driverPositions());
                    var state=e.getValue().flowSnapshot().orElseThrow();
                    assertNotEquals(FlowRuntime.Status.FAILED,state.state().status(),()->e.getKey()+state.state().diagnostic()
                        +" task outcome="+ComputerValueDisplay.lines(state.state().locals().fields().getOrDefault("result",DataValue.NullValue.INSTANCE),1000)+" "+driverPositions());
                    for(var task:state.tasks().contexts())assertNotEquals(FlowRuntime.Status.FAILED,task.status(),()->e.getKey()+task.diagnostic()+" "+driverPositions());
                }
                if(runUntilCommit && media.commits==1)return record("code","written");
                if((runUntilCommit||combined) && step%2000==0)System.out.println("SEGMENTED_PROGRESS tick="+tick+" chunks="+chunks+" head="+head.transfer().map(t->t.received()+"/"+t.length())+" "+driverPositions()+" exchanges="+exchanges);
                if(live && hosts.get("submit").flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) {
                    var receipt=(DataValue.RecordValue)hosts.get("submit").flowSnapshot().orElseThrow().state().locals().field("reply");
                    assertTrue(receipt.fields().containsKey("phase"),()->"Supply stopped before admission: "+EditorValueFormat.format(receipt)
                        +"; archive result: "+EditorValueFormat.format(hosts.get("store").flowSnapshot().orElseThrow().state().locals()));
                }
                if(restore&&!restored&&media.commits==1) {
                    assertEquals(0,submits,"Actual commit is not yet verified readback");
                    hosts.put("submit",NativeLibraryOwnersTest.reload(hosts.get("submit"),limits,null));restored=true;
                }
                var state=hosts.get(live?"ack_current":"submit").flowSnapshot().orElseThrow().state();
                if(state.status()==FlowRuntime.Status.COMPLETE)return (DataValue.RecordValue)state.locals().field("reply");
            }
            fail("Bounded run did not finish: "+hosts.entrySet().stream().map(e->e.getKey()+"="+e.getValue().flowSnapshot().orElseThrow().state().status()).toList()+" reads="+reads+" commits="+media.commits+" submits="+submits+" chunks="+chunks+" readChunks="+readChunks+" postCommitReadChunks="+postCommitReadChunks+" readCloses="+readCloses+" "+driverPositions());
            throw new AssertionError();
        }
        String driverPositions() {
            return List.of("store","coordinator","buffer","slice","pump","read_decode","read_collect","read_verify","read_stream","adapter","b","c").stream().filter(hosts::containsKey).map(name->{
                var s=hosts.get(name).flowSnapshot().orElseThrow();
                long receiptUnits=s.receipts().stream().mapToLong(r->r.value().storageUnits()).sum();
                return name+":"+s.state().status()+" pc="+s.state().assembly().map(a->a.pc()).orElse(-1)+" slots="+s.tasks().slots().entries().size()+" receipts="+s.receipts().size()+" receiptUnits="+receiptUnits+" contextUnits="+(s.state().controlUnits()+s.tasks().contexts().stream().mapToLong(FlowRuntime.Checkpoint::controlUnits).sum())+" tasks="+s.tasks().contexts().stream().map(t->t.status()+"/"+t.assembly().map(a->a.pc()).orElse(-1)).toList();
            }).toList().toString();
        }
        boolean canReply(SerialDevice device) { return device.status("s0").receive().frames()>0&&!Set.of("queued","transmitting").contains(device.status("s0").transmit()); }
        void serviceLibrary() {
            if(head.writing().isPresent()&&head.writing().orElseThrow().admission().verdict().isEmpty())head=LibraryReadHead.withWrite(head,head.writing().orElseThrow().seal(false));
            var advance=LibraryReadHead.prepareTick(head,media,drive);advance.commit().run();head=advance.next();
            if(!canReply(library))return;
            var frame=library.prepareReceive("s0");assertEquals(Optional.of(bWire.attachments().getFirst().key()),frame.source());
            var command=LibraryDriveCommand.decode((DataValue.RecordValue)frame.result().field("value"));assertEquals(libraryTarget,command.target());
            if(command.operation().equals("write_chunk"))chunks++;
            if(command.operation().equals("write_begin")&&firstBegin==null)firstBegin=command;
            if(command.operation().equals("read"))reads++;
            if(command.operation().equals("read_chunk")){readChunks++;if(media.commits>0)postCommitReadChunks++;}
            if(command.operation().equals("read_close"))readCloses++;
            DataValue.RecordValue reply;
            try {
                var next=command.apply(head,media,drive,new LibraryDriveCommand.Admission(librarian,new LibraryWriteBatch(epoch,tick)));
                if(command.operation().equals("ack")&&head.writing().isPresent())media.releaseWrite(head.track().orElseThrow(),head.slot(),head.writing().orElseThrow().operation());
                head=next.state();reply=next.reply();
            }catch(LibraryBookAccess.Failure failure){reply=command.reply(head.sequence(),failure.code(),DataValue.NullValue.INSTANCE);}
            assertTrue(frame.publish());assertEquals("queued",library.send("s0",reply));
        }
        void serviceProduction() {
            if(!canReply(production))return;
            var frame=production.prepareReceive("s0");
            var command=(DataValue.RecordValue)frame.result().field("value");
            if(!command.field("kind").equals(new DataValue.TextValue("production_command"))){assertTrue(frame.publish());return;}
            assertEquals(Optional.of(gatewayWire.attachments().getFirst().key()),frame.source());
            if(command.field("operation").equals(new DataValue.TextValue("submit"))) {
                submits++;assertEquals(1,media.commits);if(combined){assertEquals(sharedSession?2:1,readCloses);assertTrue(head.reading().isEmpty());}assertTrue(head.result().isEmpty(),"Drive result must be explicitly claimed before business dispatch");
                var args=(DataValue.RecordValue)command.field("arguments");
                var entries=(DataValue.ListValue)media.media.read(media.book).get("reading").value();
                var expected = new ArrayList<>(previousEntries);
                expected.add(record("s",record("d",original.field("d"),"h",original.field("h"),"r",args.field("scope")),"j",args.field("order")));
                assertEquals(expected,entries.values());
            }
            if(live&&command.field("operation").equals(new DataValue.TextValue("order"))) {
                queries++;assertEquals(1,stock.inputs().size(),"Pending stock input survives every pre-ACK query");
                var args=(DataValue.RecordValue)command.field("arguments");queriedScope=args.field("scope");
                var row=(DataValue.RecordValue)((DataValue.ListValue)media.media.read(media.book).get("reading").value()).values().getLast();
                assertEquals(row.field("j"),args.field("order"));assertEquals(((DataValue.RecordValue)row.field("s")).field("r"),queriedScope);
            }
            var reply=new ProductionPortProtocol(output,session,7,target,ADDRESS,orders).respond(sender(),command).orElseThrow();
            assertTrue(frame.publish());assertEquals("queued",production.send("s0",reply));
            if(live&&queries==1&&!finishRestored) {hosts.put("finish",NativeLibraryOwnersTest.reload(hosts.get("finish"),limits,null));finishRestored=true;}
        }
        void serviceStock() {
            if(!canReply(stockWire))return;
            var frame=stockWire.prepareReceive("s0");
            var command=(DataValue.RecordValue)frame.result().field("value");
            if(!command.field("kind").equals(new DataValue.TextValue("stock_application_command"))){assertTrue(frame.publish());return;}
            assertEquals(Optional.of(gatewayWire.attachments().getFirst().key()),frame.source());
            var transition=StockApplicationProtocol.plan(stock,stockAccess,stockTarget,command,stockLimits);
            assertTrue(frame.publish());if(transition.isEmpty())return;
            if(command.field("operation").equals(new DataValue.TextValue("ack"))) {
                acknowledgements++;assertEquals(2,queries);assertTrue(saved.orders().values().stream().allMatch(o->o.phase()==Phase.COMPLETE));
                var args=(DataValue.RecordValue)command.field("arguments");assertEquals(new DataValue.TextValue(stock.session().toString()),args.field("session"));
            }
            stock=transition.orElseThrow().next();assertEquals("queued",stockWire.send("s0",transition.orElseThrow().reply()));
        }
    }
}
