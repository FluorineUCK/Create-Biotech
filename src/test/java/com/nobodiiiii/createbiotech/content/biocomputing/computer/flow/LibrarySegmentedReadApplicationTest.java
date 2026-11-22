package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Real finite wire, Library read head and book component; not client/player evidence. */
class LibrarySegmentedReadApplicationTest {
    @BeforeAll static void bootstrap() throws Exception {NativeLibraryTerminalSessionTest.bootstrap();}
    @Test void publicReaderReassemblesOriginalOrderFromPhysicalMediaAndClosesAfterRecovery() throws Exception {
        readExistingLedger(false);
    }
    @Test void applicationStartupUsesTheSharedSegmentedServiceForAnExistingLargeLedger() throws Exception {
        readExistingLedger(true);
    }
    private static String documentRole(String role,boolean startup) {
        if(!startup)return role;
        return switch(role){case "read_coordinator"->"session_read";case "session"->"session_coordinator";
            case "read_decode"->"session_read_decode";case "buffer","slice"->"session_"+role;
            case "read_collect"->"archive_read_collect";default->role;};
    }
    private void readExistingLedger(boolean startup) throws Exception {
        var limits=NativeLibraryOwnersTest.limits("cleric",5);
        var drive=NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        var media=new NativeLibraryTerminalExecutionTest.Media(false,drive.archive(),NativeLibraryOwnersTest.CONFIG.libraryBookBytes(false));
        var order=com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.snapshot(
            net.minecraft.nbt.TagParser.parseTag("{ordered_stacks:{entries:[{item_stack:{id:\"minecraft:paper\",count:2,components:{\"!minecraft:rarity\":{}}},count:7}]},ordered_crafts:[]}"),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Limits(65536,limits.archive().maxValueUnits(),limits.archive()));
        var headValue=record("kind","native_order","sequence",1,"player",UUID.randomUUID().toString(),"address","alternate-supply","order",order);
        var scope=record("source",UUID.randomUUID().toString(),"device",UUID.randomUUID().toString(),"revision",7,
            "target",record("x",2,"y",0,"z",4),"address","alternate-supply");
        var value=new DataValue.ListValue(List.of(record("s",record("d",UUID.randomUUID().toString(),"h",headValue,"r",scope),"j",UUID.randomUUID().toString()+":1")));
        media.book=media.media.put(media.book,"reading",new LibraryEntry(LibraryEntry.Kind.DATA,value));
        var originalBook=media.book.copy();
        var wire=NativeLibraryOwnersTest.device(limits);wire.configure("s0",Direction.EAST,true);
        var library=NativeLibraryOwnersTest.device(limits);library.configure("s0",Direction.WEST,true);
        var devices=Map.of(BlockPos.ZERO,wire,new BlockPos(2,0,0),library);
        var world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){
            return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0))?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
        var roles=new ArrayList<>(List.of("read_coordinator","read_decode","read_collect","read_verify","read_stream","read_adapter","b","c"));
        if(startup)roles.addAll(List.of("session","buffer","slice","pump","compact_reply"));
        var oversized=new ArrayList<String>();
        for(var role:roles) {
            var document=Set.of("b","c").contains(role)?NativeLibraryServiceDocuments.build(role):LibrarySegmentedWriteDocuments.build(documentRole(role,startup));
            var usage=document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8));
            System.out.println("READ_ADMISSION "+role+" "+usage);
            if(usage.programUnits()>limits.hardware().programUnits())oversized.add(role+"="+usage.programUnits());
        }
        assertTrue(oversized.isEmpty(),"Public workers exceed default code capacity: "+oversized);
        if(startup) {
            var store=LibrarySegmentedWriteDocuments.build("session_store");
            System.out.println("SESSION_STORE_ADMISSION "+store.flow().orElseThrow().program(store.archive()).usage(new ResourceLimits(4096,65536,8)));
            PreparedProgram.prepare(store,limits);
        }
        var hosts=new LinkedHashMap<String,ComputerBlockHost>();
        for(var role:roles) {
            var document=Set.of("b","c").contains(role)?NativeLibraryServiceDocuments.build(role):LibrarySegmentedWriteDocuments.build(documentRole(role,startup));
            var body=document.flow().orElseThrow();var initial=new TreeMap<>(body.initial().fields());
            if(role.equals("read_adapter"))initial.put("target",record("x",2,"y",0,"z",0));
            var draft=new ProgramWorkspace(document);draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(initial),body.canvas(),body.inline(),body.execution())));
            document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(draft.snapshot(),limits.archive()),limits.archive());
            System.out.println("READ_PUBLIC "+role+" "+body.program(document.archive()).usage(limits.hardware()));
            var host=new ComputerBlockHost(()->{},p->{},role.equals("b")?wire:null);
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);host.setPaused(false,limits,true);hosts.put(role,host);
        }
        record Pin(String role,String pin) {}
        record Wire(Pin a,Pin b) {}
        var links=new ArrayList<Wire>();
        var signalLinks=new ArrayList<Wire>();
        if(startup) {
            var caller=new ComputerBlockHost(()->{});
            caller.installFlow(new FlowProgram(Map.of("selected",DataType.Scalar.INTEGER,"kick",new DataType.RecordType(Map.of("v",DataType.Scalar.INTEGER))),List.of(
                new FlowProgram.SignalWrite("select","p0","selected"),new FlowProgram.Write("start","x0","kick"),
                new FlowProgram.Read("finished","x0","reply",NativeLibraryReadDocuments.OUTCOME))).withAssembly(),record("selected",1,"kick",record("v",0)),limits,true);
            caller.setPaused(false,limits,true);hosts.put("caller",caller);roles.add("caller");
            for(var pair:List.of(new String[]{"caller","x0","read_coordinator","x0"},new String[]{"read_coordinator","x1","session","x1"},
                new String[]{"session","x1","read_decode","x0"},new String[]{"session","x0","buffer","x0"},new String[]{"buffer","x1","slice","x0"},
                new String[]{"slice","x1","pump","x0"},
                new String[]{"pump","x1","compact_reply","x0"},new String[]{"read_decode","x1","read_collect","x0"},
                new String[]{"read_collect","x1","read_verify","x0"},new String[]{"read_verify","x1","read_stream","x0"},
                new String[]{"read_stream","x1","compact_reply","x0"},new String[]{"compact_reply","x1","read_adapter","x0"},
                new String[]{"read_adapter","x1","b","x0"},new String[]{"b","x1","c","x0"}))links.add(new Wire(new Pin(pair[0],pair[1]),new Pin(pair[2],pair[3])));
            signalLinks.add(new Wire(new Pin("caller","p0"),new Pin("read_coordinator","p0")));
            signalLinks.add(new Wire(new Pin("session","p0"),new Pin("read_coordinator","p1")));
            signalLinks.add(new Wire(new Pin("session","p0"),new Pin("read_decode","p0")));
        } else {
        links.add(new Wire(new Pin("read_coordinator","x0"),new Pin("read_adapter","x0")));
        links.add(new Wire(new Pin("read_coordinator","x1"),new Pin("read_decode","x0")));
        for(int i=1;i<roles.size()-1;i++)links.add(new Wire(new Pin(roles.get(i),"x1"),new Pin(roles.get(i+1),"x0")));
        }
        var groups=new ArrayList<Set<Pin>>();
        for(var link:links){var group=new LinkedHashSet<>(List.of(link.a,link.b));for(var it=groups.iterator();it.hasNext();){var old=it.next();if(!Collections.disjoint(old,group)){group.addAll(old);it.remove();}}groups.add(group);}
        var network=new SerialNetwork();var head=LibraryReadHead.State.empty();var operations=new HashMap<String,Integer>();
        boolean restored=false;int segments=0;DataValue expectedVersion=null;
        for(int tick=0;tick<45000;tick++) {
            var signals=new HashMap<String,Map<String,Integer>>();roles.forEach(r->signals.put(r,new HashMap<>()));
            for(var group:groups) {
                var bus=XBusAccess.network(group.stream().map(p->NativeLibraryStartupTest.access(hosts.get(p.role),p.pin,limits)).toList());
                for(var p:group)signals.get(p.role).put(p.pin,bus.statusCode(NativeLibraryStartupTest.pin(hosts.get(p.role),p.pin)));
            }
            var pins=new LinkedHashMap<SimpleIoNetwork.Pin,SimpleIoNetwork.Port>();var signalWires=new ArrayList<SimpleIoNetwork.Wire>();
            for(var link:signalLinks) {
                for(var p:List.of(link.a,link.b)) {var saved=hosts.get(p.role).flowSnapshot().orElseThrow().state();var outputs=saved.signals().outputs();
                    pins.put(new SimpleIoNetwork.Pin(saved.computer(),p.pin),outputs.containsKey(p.pin)?SimpleIoNetwork.Port.output(outputs.get(p.pin)):SimpleIoNetwork.Port.input());}
                signalWires.add(new SimpleIoNetwork.Wire(new SimpleIoNetwork.Pin(hosts.get(link.a.role).flowSnapshot().orElseThrow().state().computer(),link.a.pin),
                    new SimpleIoNetwork.Pin(hosts.get(link.b.role).flowSnapshot().orElseThrow().state().computer(),link.b.pin)));
            }
            var signalFrame=SimpleIoNetwork.sample(pins,signalWires);
            hosts.forEach((role,host)->{signals.get(role).putAll(signalFrame.inputs(host.flowSnapshot().orElseThrow().state().computer()));host.tick(limits,true,signals.get(role));});
            for(var group:groups) {
                var bus=XBusAccess.network(group.stream().map(p->NativeLibraryStartupTest.access(hosts.get(p.role),p.pin,limits)).toList());
                for(var link:links)if(group.contains(link.a))bus.exchange(NativeLibraryStartupTest.pin(hosts.get(link.a.role),link.a.pin),NativeLibraryStartupTest.pin(hosts.get(link.b.role),link.b.pin));
            }
            network.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false));
            var advance=LibraryReadHead.prepareTick(head,media,drive);advance.commit().run();head=advance.next();
            if(library.status("s0").receive().frames()>0&&!Set.of("queued","transmitting").contains(library.status("s0").transmit())) {
                var received=library.prepareReceive("s0");var cmd=LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));
                operations.merge(cmd.operation(),1,Integer::sum);DataValue.RecordValue reply;
                try{var next=cmd.apply(head,media,drive);head=next.state();reply=next.reply();}
                catch(LibraryBookAccess.Failure failure){reply=cmd.reply(head.sequence(),failure.code(),DataValue.NullValue.INSTANCE);}
                if(cmd.operation().equals("read_info")&&reply.field("code").equals(new DataValue.TextValue("read_ready")))expectedVersion=((DataValue.RecordValue)reply.field("value")).field("version");
                if(cmd.operation().equals("read_chunk"))segments++;
                assertTrue(received.publish());assertEquals("queued",library.send("s0",reply));
            }
            if(segments==1&&!restored) {
                head=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(head,drive),drive);
                for(var role:List.of("read_decode","read_collect","read_verify","read_stream"))hosts.put(role,NativeLibraryOwnersTest.reload(hosts.get(role),limits,null));
                if(startup)for(var role:List.of("session","read_coordinator"))hosts.put(role,NativeLibraryOwnersTest.reload(hosts.get(role),limits,null));
                restored=true;
            }
            for(var entry:hosts.entrySet()) {
                assertTrue(entry.getValue().loaded(),()->entry.getKey()+entry.getValue().diagnostic());
                var snapshot=entry.getValue().flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,snapshot.state().status(),entry.getKey()+snapshot.state().diagnostic());
                for(var task:snapshot.tasks().contexts())assertNotEquals(FlowRuntime.Status.FAILED,task.status(),entry.getKey()+task.diagnostic());
            }
            var state=hosts.get("read_coordinator").flowSnapshot().orElseThrow().state();
            if(state.status()==FlowRuntime.Status.COMPLETE) {
                var result=(DataValue.RecordValue)state.locals().field("result");
                assertEquals(new DataValue.BooleanValue(true),result.field("ok"),()->EditorValueFormat.format(result));
                var snapshot=(DataValue.RecordValue)result.field("value");assertEquals(value,snapshot.field("value"));
                assertEquals(new DataValue.TextValue("DATA"),snapshot.field("kind"));assertNotNull(expectedVersion);assertEquals(expectedVersion,snapshot.field("version"));
                assertTrue(restored);assertTrue(segments>1);assertEquals(1,operations.get("read_begin"));assertEquals(1,operations.get("read_close"));
                assertEquals(0,media.commits);assertTrue(net.minecraft.world.item.ItemStack.matches(originalBook,media.book));
                assertTrue(head.result().isEmpty());assertTrue(head.reading().isEmpty(),"Actual read_close must release the device snapshot");
                System.out.println("READ_COMPLETE chunks="+segments+" operations="+operations+" ticks="+tick);return;
            }
            if(tick%3000==0)System.out.println("READ_PROGRESS tick="+tick+" operations="+operations+" states="+hosts.entrySet().stream().map(e->e.getKey()+":"+e.getValue().diagnostic()).toList());
        }
        fail("Segmented reader did not finish: "+operations);
    }
}
