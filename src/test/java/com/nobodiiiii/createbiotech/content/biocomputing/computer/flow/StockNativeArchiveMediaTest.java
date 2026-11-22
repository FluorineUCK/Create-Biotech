package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

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

/** Actual book component, mechanical commands and finite serial; not physical player acceptance. */
class StockNativeArchiveMediaTest {
    @BeforeAll static void bootstrap() throws Exception { NativeLibraryTerminalSessionTest.bootstrap(); }
    @Test void archivesExactBindingAndKeepsTheOriginalVersionFenceAcrossWaitingRestore() throws Exception { run("write"); }
    @Test void changedBookVersionAndReadOnlyMediaNeverAuthorizeSupply() throws Exception { run("conflict"); run("readonly"); }
    @Test void changedReadbackNeverClaimsThatTheCandidateIsStillArchived() throws Exception { run("readback"); }
    @Test void businessIdentityIsPublishedOnlyAfterActualBookArchivalAndCanBeRecoveredOnNewComputers() throws Exception { run("identity"); }

    @Test void startupReadsThroughThePublicDriveBeforeArchivingAndPublishingIdentity() throws Exception { run("startup"); }

    @Test void missingStartupEntryIsNotAnEmptyLedgerAndCannotAllocateOrWrite() throws Exception { run("startup_missing"); }
    @Test void explicitEntryArgumentCreatesOnlyThatEntryAndNeverPromotesAStaleVersion() throws Exception { run("named_write"); run("named_conflict"); }
    @Test void namedEntryServiceAcceptsAnotherExplicitCallAfterWaitingRestore() throws Exception { run("named_repeat"); }

    private static void run(String scenario) throws Exception {
        var limits = NativeLibraryOwnersTest.limits("cleric", 5);
        var drive = NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        boolean startup=scenario.startsWith("startup");
        boolean named=scenario.startsWith("named_");
        String entry=named?"orders.part0":"reading";
        var media = new NativeLibraryTerminalExecutionTest.Media(scenario.equals("readonly"), drive.archive(), NativeLibraryOwnersTest.CONFIG.libraryBookBytes(false),entry);
        boolean conflict=scenario.endsWith("conflict");
        boolean repeat=scenario.equals("named_repeat");
        boolean success=scenario.equals("write")||scenario.equals("named_write")||repeat;
        boolean identity=scenario.equals("identity")||startup;
        if(identity)media.book=media.media.put(media.book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.ListValue(List.of())));
        var expected = named?media.media.stat(media.book,entry).field("version"):media.media.snapshot(media.book, "reading").value().field("version");
        // Another writer has changed the ledger after OUR read, but before this driver's fresh stat.
        if (conflict) media.book = media.media.put(media.book, entry, new LibraryEntry(LibraryEntry.Kind.DATA, record("other", "retained")));
        if(scenario.equals("startup_missing"))media.book=media.media.delete(media.book,"reading");
        var before = media.book.copy();
        var nbt = new DataValue.NbtValue(10, record("tag", new DataValue.NbtValue(1, new DataValue.IntegerValue(3))));
        var binding = record("source", record("device", "stock-output", "sequence", 7, "order", nbt), "job", "stable-business-7");
        var originalSource=record("d","stock-output","h",record("kind","native_order","sequence",7,"player","player-id","address","workshop","order",nbt));
        var hosts = new LinkedHashMap<String,ComputerBlockHost>();
        var wire = NativeLibraryOwnersTest.device(limits); wire.configure("s0", Direction.EAST, true);
        var library = NativeLibraryOwnersTest.device(limits); library.configure("s0", Direction.WEST, true);
        var roles=new ArrayList<>(List.of("store", "coordinator", "adapter", "b", "c"));
        if(identity)roles.addAll(List.of("check","identity"));
        if(startup)roles.add("reader");
        for (String role : roles) {
            var document = named&&role.equals("store")?LibraryEntryDocuments.store()
                :named&&role.equals("coordinator")?LibraryEntryDocuments.coordinator()
                :role.equals("reader") ? StockNativeArchiveDocuments.reader()
                : role.equals("store") ? StockNativeArchiveDocuments.build()
                : Set.of("check","identity").contains(role) ? StockNativeArchiveDocuments.ledger(role)
                : Set.of("b", "c").contains(role) ? NativeLibraryServiceDocuments.build(role) : NativeClusterJournalDocuments.build(role);
            var host = NativeLibraryOwnersTest.install(document, limits, role.equals("b") ? wire : null);
            host.setPaused(false, limits, true); hosts.put(role, host);
        }
        var caller = new ComputerBlockHost(() -> {});
        var bindingReply=new DataType.RecordType(Map.of("c",DataType.Scalar.TEXT,"j",DataType.Scalar.TEXT));
        var request=named?record("entry",entry,"v",binding,"expected",expected):startup?record():identity?record("s",originalSource,"book",media.media.snapshot(media.book,"reading").value()):record("v",binding,"expected",expected);
        if(startup) caller=NativeLibraryOwnersTest.install(startupCaller(originalSource),limits,null);
        else caller.installFlow(new FlowProgram(Map.of("request", named?LibraryEntryDocuments.REQUEST:identity?StockNativeArchiveDocuments.LEDGER_REQUEST:StockNativeArchiveDocuments.REQUEST), List.of(
            new FlowProgram.Write("submit", "x0", "request"), new FlowProgram.Read("receipt", "x0", "result", identity?bindingReply:NativeLibraryReadDocuments.OUTCOME))).withAssembly(),
            record("request", request), limits, true);
        caller.setPaused(false, limits, true); hosts.put("caller", caller);
        var links = new ArrayList<>(List.of(new Link("caller","x0",startup?"reader":identity?"check":"store","x0"), new Link("store","x1","coordinator","x1"),
            new Link("coordinator","x0","adapter","x0"), new Link("adapter","x1","b","x0"), new Link("b","x1","c","x0")));
        if(identity)links.addAll(List.of(new Link("check","x1","identity","x0"),new Link("identity","x1","store","x0")));
        if(startup)links.addAll(List.of(new Link("caller","x1","check","x0"),new Link("reader","x1","adapter","x0")));
        var devices = Map.of(BlockPos.ZERO, wire, new BlockPos(2,0,0), library);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos p) { return true; }
            public CableDiscovery.Cell cell(BlockPos p) { return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0)) ? CableDiscovery.Kind.CABLE : devices.containsKey(p) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        };
        var network = new SerialNetwork(); var head = LibraryReadHead.State.empty();
        var device = UUID.randomUUID(); var epoch = UUID.randomUUID(); boolean restored = false; boolean readRestored=false; int writes = 0; int reads = 0;
        for (int tick=1; tick<=22000; tick++) {
            for (var e : hosts.entrySet()) {
                var signals = new HashMap<String,Integer>();
                for (var link : links) {
                    if (link.left.equals(e.getKey())) signals.put(link.lp, NativeLibraryStartupTest.status(e.getValue(),link.lp,limits,hosts.get(link.right),link.rp,limits));
                    if (link.right.equals(e.getKey())) signals.put(link.rp, NativeLibraryStartupTest.status(e.getValue(),link.rp,limits,hosts.get(link.left),link.lp,limits));
                }
                if(startup&&Set.of("reader","coordinator","adapter").contains(e.getKey())) {
                    String pin=e.getKey().equals("reader")?"x1":"x0";
                    signals.put(pin,driveBus(hosts,limits).statusCode(NativeLibraryStartupTest.pin(e.getValue(),pin)));
                }
                e.getValue().tick(tick,limits,true,signals);
            }
            for (var link : links) {
                if(startup&&link.right.equals("adapter"))
                    driveBus(hosts,limits).exchange(NativeLibraryStartupTest.pin(hosts.get(link.left),link.lp),NativeLibraryStartupTest.pin(hosts.get(link.right),link.rp));
                else hosts.get(link.left).exchange(link.lp,limits,true,hosts.get(link.right),link.rp,limits,true);
            }
            network.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false));
            if (head.writing().isPresent() && head.writing().orElseThrow().admission().verdict().isEmpty())
                head=LibraryReadHead.withWrite(head,head.writing().orElseThrow().seal(false));
            var advanced=LibraryReadHead.prepareTick(head,media,drive); advanced.commit().run(); head=advanced.next();
            if (library.status("s0").receive().frames()>0 && !Set.of("queued","transmitting").contains(library.status("s0").transmit())) {
                var received = library.prepareReceive("s0"); var command = LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));
                if (command.operation().equals("write")) writes++;
                if (command.operation().equals("read")) reads++;
                if (scenario.equals("readback") && command.operation().equals("read"))
                    media.book=media.media.put(media.book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,record("other","changed_after_write")));
                DataValue.RecordValue response;
                try {
                    var transition = command.apply(head,media,drive,new LibraryDriveCommand.Admission(device,new LibraryWriteBatch(epoch,tick)));
                    if (command.operation().equals("ack") && head.writing().isPresent()) media.releaseWrite(head.track().orElseThrow(),head.slot(),head.writing().orElseThrow().operation());
                    head=transition.state(); response=transition.reply();
                } catch (LibraryBookAccess.Failure failure) { response=command.reply(head.sequence(),failure.code(),DataValue.NullValue.INSTANCE); }
                assertTrue(received.publish()); assertEquals("queued",library.send("s0",response));
                if(startup&&!readRestored&&command.operation().equals("read")&&response.field("code").equals(new DataValue.TextValue("accepted"))) {
                    hosts.put("reader",NativeLibraryOwnersTest.reload(hosts.get("reader"),limits,null));readRestored=true;
                    assertEquals(0,media.commits,"Read precedes identity allocation and archive write");
                }
            }
            for (var e : hosts.entrySet()) {
                var saved=e.getValue().flowSnapshot().orElseThrow(); assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),e.getKey()+saved.state().diagnostic());
                for(var task:saved.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED,task.status(),e.getKey()+task.diagnostic());
            }
            if (!restored && media.commits==1) {
                assertNotEquals(FlowRuntime.Status.COMPLETE,hosts.get("caller").flowSnapshot().orElseThrow().state().status(),"Admission/write is not verified archival");
                hosts.put("store",NativeLibraryOwnersTest.reload(hosts.get("store"),limits,null)); restored=true;
                if(named)hosts.put("coordinator",NativeLibraryOwnersTest.reload(hosts.get("coordinator"),limits,null));
                // The common fixture loads while paused; it does not grant an extra execution budget.
            }
            if(hosts.get("caller").flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) {
                if(repeat&&media.commits==1) {
                    var completed=hosts.get("caller").flowSnapshot().orElseThrow();
                    var first=(DataValue.RecordValue)completed.state().locals().field("result");
                    assertEquals(new DataValue.BooleanValue(true),first.field("ok"));
                    var nextVersion=((DataValue.RecordValue)first.field("value")).field("version");
                    var next=new ComputerBlockHost(()->{});
                    next.installFlow(completed.program(),record("request",record("entry",entry,"v",binding,"expected",nextVersion)),limits,true);
                    next.setPaused(false,limits,true);hosts.put("caller",next);
                    continue; // A new caller operation, not a driver retry or a service reinstallation.
                }
                break;
            }
        }
        var end=hosts.get("caller").flowSnapshot().orElseThrow().state(); assertEquals(FlowRuntime.Status.COMPLETE,end.status());
        var result=(DataValue.RecordValue)end.locals().field("result");
        if(scenario.equals("startup_missing")) {
            assertEquals(record("c","archive_read_failed","j",""),result);assertEquals(1,reads);assertEquals(0,writes);assertEquals(0,media.commits);
            assertTrue(net.minecraft.world.item.ItemStack.matches(before,media.book));
            var failure=(DataValue.RecordValue)hosts.get("reader").flowSnapshot().orElseThrow().state().locals().field("result");
            assertEquals(new DataValue.BooleanValue(false),failure.field("ok"));
            assertEquals(new DataValue.TextValue("missing_entry"),((DataValue.RecordValue)failure.field("value")).field("code"));
            assertEquals(FlowRuntime.Status.WAITING_READ,hosts.get("identity").flowSnapshot().orElseThrow().state().status());
            return;
        }
        if(identity) {
            if(startup) {assertTrue(readRestored);assertEquals(2,reads,"Exactly one real startup read plus the post-write readback, no replay on restore");}
            assertEquals(new DataValue.TextValue("bound"),result.field("c"));assertTrue(restored);assertEquals(1,media.commits);assertEquals(1,writes);
            var entries=(DataValue.ListValue)media.media.read(media.book).get("reading").value();
            assertEquals(List.of(record("s",originalSource,"j",result.field("j"))),entries.values());assertTrue(head.result().isEmpty());
            recoverOnNewComputers(media,originalSource,result.field("j"));return;
        }
        assertEquals(new DataValue.BooleanValue(success),result.field("ok"));
        assertEquals(repeat?2:1,writes,"Only explicit caller operations; no implicit retry with a newly queried version");
        if(named)assertEquals(media.media.read(before).get("reading"),media.media.read(media.book).get("reading"),"The old fixed entry is not the requested destination");
        if(success) {assertTrue(restored);assertEquals(repeat?2:1,media.commits);assertEquals(binding,media.media.read(media.book).get(entry).value());assertTrue(head.result().isEmpty());}
        else if(scenario.equals("readback")) {assertEquals(1,media.commits);assertEquals(new DataValue.TextValue("archive_readback_mismatch"),result.field("value"));}
        else {assertEquals(0,media.commits);assertTrue(net.minecraft.world.item.ItemStack.matches(before,media.book));}
    }
    /** One real shared pin group, not two independent links whose status overwrites the other. */
    private static XBusAccess.Network driveBus(Map<String,ComputerBlockHost> hosts,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits) {
        return XBusAccess.network(List.of(NativeLibraryStartupTest.access(hosts.get("reader"),"x1",limits),
            NativeLibraryStartupTest.access(hosts.get("coordinator"),"x0",limits),NativeLibraryStartupTest.access(hosts.get("adapter"),"x0",limits)));
    }
    /** Ordinary ISA caller: only the source is supplied, never a book snapshot or version. */
    private static ProgramDocument startupCaller(DataValue.RecordValue source) {
        var inputType=StockNativeArchiveDocuments.LEDGER_REQUEST.fields().get("s");
        var replyType=new DataType.RecordType(Map.of("c",DataType.Scalar.TEXT,"j",DataType.Scalar.TEXT));
        var inputs=Map.of("source",inputType);var outputs=Map.<String,DataType>of("result",replyType);
        var key=new ModuleDefinition.Key("startup",1);var returns=Map.of("result","result");
        var code="""
            TYPE Kick RECORD v INTEGER
            TYPE Read RECORD ok BOOLEAN value ANY
            TYPE Request RECORD s ANY book ANY
            TYPE Reply RECORD c TEXT j TEXT
            REG kick Kick
            REG loaded Read
            REG request Request
            REG result Reply
            REG book ANY
            REG ok BOOLEAN
            CONST kick {"v":0}
            CONST result {"c":"archive_read_failed","j":""}
            XBUS.WRITE x0 kick
            XBUS.READ loaded x0
            FIELD ok loaded ok
            JFALSE ok done
            FIELD book loaded value
            RECORD.MAKE request s source book book
            XBUS.WRITE x1 request
            XBUS.READ result x1
            done:
            """;
        var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(Map.of("source","source"),returns)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(code))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,record("source",source))));return workspace.snapshot();
    }
    private static void recoverOnNewComputers(NativeLibraryTerminalExecutionTest.Media media,DataValue.RecordValue source,DataValue job) throws Exception {
        var limits=NativeLibraryOwnersTest.limits("cleric",5);
        var check=NativeLibraryOwnersTest.install(StockNativeArchiveDocuments.ledger("check"),limits,null);
        var bind=NativeLibraryOwnersTest.install(StockNativeArchiveDocuments.ledger(),limits,null);
        var caller=new ComputerBlockHost(()->{});var replyType=new DataType.RecordType(Map.of("c",DataType.Scalar.TEXT,"j",DataType.Scalar.TEXT));
        caller.installFlow(new FlowProgram(Map.of("request",StockNativeArchiveDocuments.LEDGER_REQUEST),List.of(new FlowProgram.Write("send","x0","request"),
            new FlowProgram.Read("reply","x0","reply",replyType))).withAssembly(),
            record("request",record("s",source,"book",media.media.snapshot(media.book,"reading").value())),limits,true);
        for(var h:List.of(check,bind,caller))h.setPaused(false,limits,true);
        for(int tick=1;tick<10000;tick++) {
            for(var h:List.of(check,bind,caller))h.tick(tick,limits,true,Map.of());
            caller.exchange("x0",limits,true,check,"x0",limits,true);check.exchange("x1",limits,true,bind,"x0",limits,true);
            // No writer exists: reallocation or any attempted rewrite cannot complete this call.
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
        }
        var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
        assertEquals(record("c","existing","j",job),done.locals().field("reply"));assertEquals(1,media.commits);
    }
    private record Link(String left,String lp,String right,String rp) {}
}
