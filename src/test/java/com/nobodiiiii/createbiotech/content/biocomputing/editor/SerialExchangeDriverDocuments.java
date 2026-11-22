package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Public software transport, not another device protocol or a privileged host service. */
public class SerialExchangeDriverDocuments {
    static ComputerHostLimits witch(){
        return new BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder()).select(
            new ResidentHardwareIdentity(net.minecraft.resources.ResourceLocation.withDefaultNamespace("witch"),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("none"),1),false).limits();
    }
    // Compatibility fixture for the already published monolithic program on explicitly equipped servers.
    static ComputerHostLimits legacyEquippedParser(){
        var p=StockApplicationDriverDocuments.hardware();
        return new ComputerHostLimits(p.hardware(),p.archive(),p.mailbox(),p.pins(),witch().serial(),p.taskSlots(),p.isa1a(),p.isa1b());
    }
    @Test void theDefaultParserCannotInstallTheWiredWorker() throws Exception {
        var doc=build("serial_exchange_uart");
        var denied=assertThrows(FlowPins.Unavailable.class,
            ()->PreparedProgram.prepare(doc,StockApplicationDriverDocuments.hardware()));
        assertTrue(denied.getMessage().contains("s0"));
    }
    @Test void splitDocumentsFitReleaseResidents() throws Exception {
        for(var stem:List.of("serial_exchange_logic","serial_exchange_uart")){
            var doc=build(stem);
            var h=stem.endsWith("logic")?StockApplicationDriverDocuments.hardware():witch();
            System.out.println(stem+" "+doc.flow().orElseThrow().program(doc.archive()).usage(new ResourceLimits(10000,1000000,1000)));
            PreparedProgram.prepare(doc,h);
            var bytes=ProgramDocumentWire.encode(doc,h.archive());
            for(var dir:List.of("examples/biocomputing","src/main/resources/assets/create_biotech/biocomputing/examples")){
                var file=Path.of(dir,stem+".bcp");
                if("1".equals(System.getenv("GENERATE_SPLIT_EXCHANGE")))Files.writeString(file,Base64.getEncoder().encodeToString(bytes)+"\n");
                assertEquals(doc,ProgramDocumentWire.decode(Base64.getMimeDecoder().decode(Files.readString(file)),h.archive()));
            }
        }
    }
    static final DataType.RecordType TARGET = new DataType.RecordType(Map.of("x",INTEGER,"y",INTEGER,"z",INTEGER));
    public static ProgramDocument build() throws Exception { return build("serial_exchange"); }
    static ProgramDocument build(String stem) throws Exception {
        boolean uart=stem.equals("serial_exchange_uart");
        Map<String,DataType> inputs=uart?Map.of():Map.of("poll_ticks",INTEGER,"poll_limit",INTEGER);
        var key=new ModuleDefinition.Key("exchange",1);
        var source=Files.readString(Path.of("examples/biocomputing",stem+".asm"));
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("serve",key,new FlowCallBindings(uart?Map.of():Map.of("poll_ticks","poll_ticks","poll_limit","poll_limit"),Map.of())),
            new FlowProgram.Procedure("exchange_definition",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("exchange_end"))).withAssembly();
        var draft=new ProgramWorkspace();
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,uart?record():record("poll_ticks",20,"poll_limit",80))));
        return draft.snapshot();
    }
    public static ProgramDocument probe(String service) throws Exception {
        var inputs=Map.<String,DataType>of("service",TEXT,"target",TARGET);
        var outputs=Map.<String,DataType>of("reply",ANY);
        var key=new ModuleDefinition.Key("probe",1);
        var source=Files.readString(Path.of("examples/biocomputing/serial_exchange_probe.asm"));
        var program=new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("query",key,new FlowCallBindings(Map.of("service","service","target","target"),Map.of("reply","reply"))),
            new FlowProgram.Procedure("probe_definition",key,inputs,outputs,Map.of("reply","reply"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("probe_end"))).withAssembly();
        var draft=new ProgramWorkspace();
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,record("service",service,"target",record("x",2,"y",0,"z",0)))));
        draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return draft.snapshot();
    }
    @Test void editableExamplesFitHardwareAndArePublishedWithoutAnAlternateExecutionPath() throws Exception {
        var limits=legacyEquippedParser();
        for(var entry:Map.of("serial_exchange",build(),"serial_exchange_probe",probe("reader")).entrySet()) {
            PreparedProgram.prepare(entry.getValue(),limits);
            System.out.println(entry.getKey()+" "+entry.getValue().flow().orElseThrow().program(entry.getValue().archive()).usage(limits.hardware()));
            var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",entry.getKey()+".bcp");
            var doc=Path.of("examples/biocomputing",entry.getKey()+".bcp");
            if("1".equals(System.getenv("GENERATE_SERIAL_EXCHANGE"))) {
                var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(entry.getValue(),limits.archive()))+"\n";
                Files.writeString(asset,encoded); Files.writeString(doc,encoded);
            } else {
                assertEquals(Files.readString(doc),Files.readString(asset));
                assertEquals(entry.getValue(),ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }
    @Test void splitUartAndUnportedClericValidateReaderStockAndProductionReplies() throws Exception { exerciseTransport(true); }
    @Test void oneInstalledTransportHandlesReaderStockAndMaterialRepliesWithoutProtocolSpecificHostCode() throws Exception { exerciseTransport(false); }
    private void exerciseTransport(boolean split) throws Exception {
        var document=split?build("serial_exchange_logic"):build();
        var limits=split?StockApplicationDriverDocuments.hardware():legacyEquippedParser();
        PreparedProgram.prepare(document,limits);
        System.out.println("SERIAL_EXCHANGE_DRIVER " + document.flow().orElseThrow().program(document.archive()).usage(limits.hardware()));
        var wire=new Wire();
        var gateway=split?new ComputerBlockHost(()->{}):new ComputerBlockHost(()->{},pin->{},wire.source);
        var uart=new ComputerBlockHost(()->{},pin->{},wire.source);var hw=witch();
        if(split){
            assertTrue(limits.serial().ports().isEmpty());
            uart.installProgram(PreparedProgram.prepare(build("serial_exchange_uart"),hw),ComputerRuntime.MemoryPolicy.RESET,hw,true);
            uart.setPaused(false,hw,true);
        }
        gateway.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        gateway.setPaused(false,limits,true);
        long tick=0;
        for(String family:List.of("reader","stock_application","production")) {
            DataValue.RecordValue reply=null;
            var queued=new ArrayDeque<DataValue.RecordValue>();
            var caller=new ComputerBlockHost(()->{});
            caller.installProgram(PreparedProgram.prepare(probe(family),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            caller.setPaused(false,limits,true);
            int commands=0;
            for(int n=0;n<15000;n++) {
                gateway.tick(++tick,limits,true,Map.of("x0",signal(caller,"x0"))); caller.tick(tick,limits,true,Map.of());
                gateway.exchange("x0",limits,true,caller,"x0",limits,true);
                if(split){
                    uart.tick(tick,hw,true,Map.of("x0",signal(gateway,"x1")));
                    gateway.exchange("x1",limits,true,uart,"x0",hw,true);
                    assertNotEquals(FlowRuntime.Status.FAILED,uart.flowSnapshot().orElseThrow().state().status(),
                        ()->uart.flowSnapshot().orElseThrow().state().diagnostic().toString());
                }
                wire.tick();
                var sent=wire.peer.poll("s0");
                if(sent.isPresent()) {
                    var packet=(DataValue.RecordValue)sent.orElseThrow(); commands++;
                    assertEquals(new DataValue.TextValue(family+"_command"),packet.field("kind"));
                    assertEquals(new DataValue.IntegerValue(1),packet.field("version"));
                    assertEquals(record("x",2,"y",0,"z",0),packet.field("target"));
                    assertEquals(new DataValue.TextValue("status"),packet.field("operation"));
                    assertEquals(record(),packet.field("arguments"));
                    assertInstanceOf(DataValue.TextValue.class,packet.field("request"));
                    reply=record("kind",family+"_reply","version",1,"target",packet.field("target"),"request",packet.field("request"),
                        "code","ok","data",new DataValue.NbtValue(10,record("typed",new DataValue.NbtValue(1,new DataValue.IntegerValue(7)))));
                    for(var mismatch:List.of(record("kind","unrelated_reply"),record("version",2),record("target",record("x",9,"y",0,"z",0)),record("request","old"))) {
                        var fields=new LinkedHashMap<>(reply.fields()); fields.putAll(mismatch.fields()); queued.add(new DataValue.RecordValue(fields));
                    }
                    queued.add(reply);
                }
                if(commands>0 && !queued.isEmpty() && !Set.of("queued","transmitting").contains(wire.peer.status("s0").transmit())
                    && wire.source.status("s0").receive().frames()==0) assertEquals("queued",wire.peer.send("s0",queued.remove()));
                var state=caller.flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED,gateway.flowSnapshot().orElseThrow().state().status(), () -> gateway.flowSnapshot().orElseThrow().state().diagnostic().toString());
                if(state.status()==FlowRuntime.Status.COMPLETE) break;
            }
            var state=caller.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
            assertNotNull(reply);
            assertEquals(reply,state.locals().field("reply"),"Wrong protocol, version, target and old correlation cannot complete a call");
            assertEquals(1,commands,"Software transport never retransmits the command");
            assertTrue(queued.isEmpty());
        }
    }
    @Test void anUnansweredCommandReportsTimeoutWithoutAutomaticallyRepeatingIt() throws Exception {
        var limits=legacyEquippedParser(); var wire=new Wire();
        var gateway=new ComputerBlockHost(()->{},pin->{},wire.source);
        var caller=new ComputerBlockHost(()->{});
        gateway.installProgram(PreparedProgram.prepare(build(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        caller.installProgram(PreparedProgram.prepare(probe("production"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        gateway.setPaused(false,limits,true); caller.setPaused(false,limits,true);
        int commands=0;
        for(int tick=1;tick<=15000;tick++) {
            gateway.tick(tick,limits,true,Map.of("x0",signal(caller,"x0"))); caller.tick(tick,limits,true,Map.of());
            gateway.exchange("x0",limits,true,caller,"x0",limits,true); wire.tick();
            if(wire.peer.poll("s0").isPresent()) commands++;
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) break;
        }
        var state=caller.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
        assertEquals(record("code","receive_timeout"),state.locals().field("reply"));
        assertEquals(1,commands,"An ambiguous timeout must never silently repeat a side effect");
    }
    @Test void thePublicTransportCanQueryAndSetTheFluidFilterOnTheSameGateway() throws Exception {
        var limits=legacyEquippedParser();
        var wire=new Wire();
        var gateway=new ComputerBlockHost(()->{},pin->{},wire.source);
        var caller=new ComputerBlockHost(()->{});
        var draft=new ProgramWorkspace();
        var filter=VirtualFilterProgramDocuments.build(false);
        var address=record("x",-1580899,"y",-58,"z",-8589998);
        var rule=record("domain","fluid","mode","allow","matchers",
            new DataValue.ListValue(List.of(record("kind","id","name","minecraft:lava"))));
        draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(filter.flow().orElseThrow().program(filter.archive()),
            record("target",address,"rule",rule,"apply",true))));
        gateway.installProgram(PreparedProgram.prepare(build(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        caller.installProgram(PreparedProgram.prepare(draft.snapshot(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        gateway.setPaused(false,limits,true);
        caller.setPaused(false,limits,true);

        var token=record("target_id","12345678-1234-1234-1234-123456789abc","session","9abcdef0-1234-1234-1234-123456789abc",
            "revision",0,"native_occupied",false,"has_virtual_rule",false);
        var applied=record("target_id",token.field("target_id"),"session",token.field("session"),
            "revision",1,"native_occupied",false,"has_virtual_rule",true);
        var operations=new ArrayList<DataValue>();
        for(int tick=1;tick<=15000;tick++) {
            gateway.tick(tick,limits,true,Map.of("x0",signal(caller,"x0")));
            caller.tick(tick,limits,true,Map.of());
            gateway.exchange("x0",limits,true,caller,"x0",limits,true);
            wire.tick();
            wire.peer.poll("s0").ifPresent(value -> {
                var packet=assertInstanceOf(DataValue.RecordValue.class,value);
                assertEquals(new DataValue.TextValue("virtual_filter_command"),packet.field("kind"));
                assertEquals(address,packet.field("target"));
                var operation=packet.field("operation");
                operations.add(operation);
                boolean query=operation.equals(new DataValue.TextValue("status"));
                assertEquals(query?record():record("state",token,"rule",rule),packet.field("arguments"));
                assertEquals("queued",wire.peer.send("s0",record("kind","virtual_filter_reply","version",1,"target",address,
                    "request",packet.field("request"),"code",query?"ok":"applied","state",query?token:applied)));
            });
            assertTrue(gateway.diagnostic().isEmpty(),()->"Gateway is unavailable: "+gateway.diagnostic());
            var exchange=gateway.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED,exchange.status(),exchange.diagnostic().toString());
            if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) break;
        }
        var state=caller.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
        assertEquals(List.of(new DataValue.TextValue("status"),new DataValue.TextValue("set")),operations);
        var reply=assertInstanceOf(DataValue.RecordValue.class,state.locals().field("reply"));
        assertEquals(new DataValue.TextValue("applied"),reply.field("code"));
        assertEquals(applied,reply.field("state"));
    }
    /** Frozen writer-occupancy sample normally supplied by the physical Cluster. Never invent readiness. */
    static int signal(ComputerBlockHost peer,String pin) {
        var saved=peer.flowSnapshot().orElseThrow();
        return !saved.state().paused() && saved.state().status()==FlowRuntime.Status.WAITING_WRITE
            && FlowXBus.pin(saved.program(),saved.state()).equals(pin)?15:0;
    }
    static final class Wire implements CableDiscovery.World {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits=witch();
        final SerialRecordCodec.Limits codec=new SerialRecordCodec.Limits(131072,16777216,limits.archive());
        final SerialDevice source=new SerialDevice(limits::serial,()->true,()->codec,()->{});
        final SerialDevice peer=new SerialDevice(limits::serial,()->true,()->codec,()->{});
        final SerialNetwork network=new SerialNetwork();
        final Map<BlockPos,SerialDevice> devices=Map.of(BlockPos.ZERO,source,new BlockPos(2,0,0),peer);
        Wire(){source.configure("s0",Direction.EAST,true);peer.configure("s0",Direction.WEST,true);}
        public boolean loaded(BlockPos p){return true;}
        public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(new BlockPos(1,0,0))?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}
        void tick(){network.tick(this,devices,new CommunicationNetworkLimits(64,4),new SharedSerialChannel.Limits(4,131072,1024,32));}
    }
}
