package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Recovery is editable software: refresh only session, never replace the original ACK identity. */
public class StockNativeRecoveryDocuments {
    static final List<String> ROLES = List.of("ack_exchange", "ack_current");
    static ProgramDocument build(String role) throws Exception {
        var inputs = role.equals("ack_exchange") ? Map.<String,DataType>of("output", SerialExchangeDriverDocuments.TARGET, "poll_ticks", INTEGER) : Map.<String,DataType>of();
        var outputs = role.equals("ack_exchange") ? Map.<String,DataType>of() : Map.<String,DataType>of("reply", ANY);
        var in = new TreeMap<String,String>(); inputs.keySet().forEach(k -> in.put(k,k));
        var out = new TreeMap<String,String>(); outputs.keySet().forEach(k -> out.put(k,k));
        var key = new ModuleDefinition.Key("run",1);
        var flow = new FlowProgram(inputs,List.of(
            new FlowProgram.Invoke("start",key,new FlowCallBindings(in,out)),
            new FlowProgram.Procedure("implementation",key,inputs,outputs,out,FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/stock_native_"+role+".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var draft = new ProgramWorkspace(); draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(flow,role.equals("ack_exchange") ? record("output",record("x",0,"y",0,"z",0),"poll_ticks",20) : record())));
        if (!outputs.isEmpty()) draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("reply")));
        return draft.snapshot();
    }

    @Test void refreshedSessionAcknowledgesOnlyTheOriginalInputAcrossWaitingRestore() throws Exception {
        run("reload"); run("delayed_start");
    }

    @Test void changedIdentityQueueAuthorityOrSessionNeverConfirmsAReplacement() throws Exception {
        for (var scenario : List.of("device", "revision", "head", "closed", "second_reload", "timeout", "ack_timeout", "unverified"))
            run(scenario);
    }

    @Test void recoveryProgramsFitExistingHardwareAndPublishExactEditableSources() throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        for (var role : ROLES) {
            var document = build(role);
            System.out.println("RECOVERY_" + role + " " + document.flow().orElseThrow().program(document.archive())
                .usage(new ResourceLimits(4096,65536,8)));
            PreparedProgram.prepare(document,limits);
            var name = "stock_native_" + role + ".bcp";
            var doc = Path.of("examples/biocomputing",name);
            var asset = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
            if ("1".equals(System.getenv("GENERATE_STOCK_NATIVE_RECOVERY"))) {
                var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive())) + "\n";
                Files.writeString(doc,encoded); Files.writeString(asset,encoded);
            } else {
                assertEquals(Files.readString(doc),Files.readString(asset));
                assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
            }
        }
    }

    private static void run(String scenario) throws Exception {
        var limits = StockApplicationDriverDocuments.hardware();
        var app = new StockApplication.Application(UUID.randomUUID(),UUID.randomUUID());
        var access = new StockApplication.Access(app,true,false);
        var capacity = new StockApplication.Limits(8,8,100000,1024,128);
        var original = StockApplication.bind(StockApplication.closed(UUID.randomUUID(),UUID.randomUUID()),app,UUID.randomUUID()).state();
        var format = limits.archive();
        var order = NativeNbtCodec.snapshot(net.minecraft.nbt.TagParser.parseTag("{ordered_stacks:{entries:[{item_stack:{id:\"minecraft:paper\",count:1,components:{\"!minecraft:rarity\":{}}},count:7}]},ordered_crafts:[]}"),
            new NativeNbtCodec.Limits(65536,format.maxValueUnits(),format));
        var player = UUID.randomUUID();
        var request = StockApplication.Request.nativeOrder(original.fence(),original.nextSequence(),"destination",order);
        var admission = StockApplication.confirm(original,access,player,request,capacity);
        assertEquals("input_queued",admission.code()); original = admission.state();
        var source = record("state",record("device",original.device().toString(),"session",original.session().toString(),"revision",original.revision()),
            "head",record("sequence",request.sequence(),"player",player.toString(),"address","destination","kind","native_order","order",order));
        var service = StockApplicationCodec.load(StockApplicationCodec.save(original),UUID.randomUUID());
        assertNotEquals(original.session(),service.session());
        if (scenario.equals("device")) {
            service = StockApplication.bind(StockApplication.closed(UUID.randomUUID(),UUID.randomUUID()),app,UUID.randomUUID()).state();
            service = StockApplication.confirm(service,access,player,StockApplication.Request.nativeOrder(service.fence(),service.nextSequence(),"destination",order),capacity).state();
        } else if (scenario.equals("revision")) {
            service = StockApplication.replace(service,access,service.fence(),List.of(),capacity).state();
        } else if (scenario.equals("head")) {
            service = StockApplication.ack(service,access,service.fence(),request.sequence()).state();
            service = StockApplication.confirm(service,access,player,StockApplication.Request.nativeOrder(service.fence(),service.nextSequence(),"new-destination",order),capacity).state();
        } else if (scenario.equals("closed")) {
            service = StockApplication.revoke(service,UUID.randomUUID());
        }
        var retainedInputs = service.inputs(); var watermark = service.nextSequence();
        var sampledSession = service.session();
        var wire = new SerialExchangeDriverDocuments.Wire();
        var gateway = new ComputerBlockHost(()->{},pin->{},wire.source);
        var renewal = new ComputerBlockHost(()->{});
        var ack = new ComputerBlockHost(()->{});
        var caller = new ComputerBlockHost(()->{});
        gateway.installProgram(PreparedProgram.prepare(SerialExchangeDriverDocuments.build(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        renewal.installProgram(PreparedProgram.prepare(build("ack_exchange"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        ack.installProgram(PreparedProgram.prepare(build("ack_current"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        var verified = new DataType.RecordType(Map.of("s",ANY,"j",TEXT,"r",ANY,"ok",BOOLEAN));
        var receipt = scenario.equals("unverified") ? record("code","delivery_not_verified")
            : record("code","ok","phase","complete","milestone","materials_delivered","order","original-job","address","destination");
        caller.installFlow(new FlowProgram(Map.of("input",verified),List.of(new FlowProgram.Write("send","x0","input"))).withAssembly(),
            record("input",record("s",source,"j","original-job","r",receipt,"ok",!scenario.equals("unverified"))),limits,true);
        for (var host : List.of(renewal,gateway)) host.setPaused(false,limits,true);
        if (!scenario.equals("delayed_start")) for (var host : List.of(caller,ack)) host.setPaused(false,limits,true);
        var operations = new ArrayList<String>(); var correlations = new HashSet<DataValue>(); boolean restored = false;
        var target = record("x",0,"y",0,"z",0);
        for (int tick=1; tick<20000; tick++) {
            if (scenario.equals("delayed_start") && tick==50) for (var host : List.of(caller,ack)) host.setPaused(false,limits,true);
            caller.tick(tick,limits,true,Map.of());
            renewal.tick(tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(ack,"x1")));
            ack.tick(tick,limits,true,Map.of());
            gateway.tick(tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(renewal,"x1")));
            ack.exchange("x0",limits,true,caller,"x0",limits,true);
            renewal.exchange("x0",limits,true,ack,"x1",limits,true);
            renewal.exchange("x1",limits,true,gateway,"x0",limits,true);
            wire.tick();
            var frame = wire.peer.poll("s0");
            if (frame.isPresent()) {
                var command = (DataValue.RecordValue)frame.orElseThrow();
                var op = ((DataValue.TextValue)command.field("operation")).value(); operations.add(op);
                assertTrue(correlations.add(command.field("request")),"Each operation has its own correlation");
                assertTrue(Set.of("status","ack").contains(op),"Recovery must never dispatch, republish or poll a different input");
                if (op.equals("ack")) assertEquals(record("device",original.device().toString(),"session",sampledSession.toString(),"revision",original.revision(),"sequence",request.sequence()),command.field("arguments"));
                var plan = StockApplicationProtocol.plan(service,access,target,command,capacity).orElseThrow(); service = plan.next();
                if (!scenario.equals("timeout") && !(scenario.equals("ack_timeout") && op.equals("ack")))
                    assertEquals("queued",wire.peer.send("s0",plan.reply()));
                if (op.equals("status")) {
                    var saved = renewal.write(); renewal = new ComputerBlockHost(()->{}); renewal.read(saved); renewal.setPaused(false,limits,true); restored = true;
                    if (scenario.equals("second_reload")) service = StockApplicationCodec.load(StockApplicationCodec.save(service),UUID.randomUUID());
                }
            }
            for (var host : List.of(renewal,ack,gateway)) { var s=host.flowSnapshot().orElseThrow().state(); assertNotEquals(FlowRuntime.Status.FAILED,s.status(),s.diagnostic().toString()); }
            if (ack.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) break;
        }
        var expectedCode = switch (scenario) {
            case "device" -> "device_changed";
            case "revision" -> "stale_revision";
            case "head" -> "stale_input";
            case "closed" -> "closed";
            case "second_reload" -> "stale_session";
            case "timeout", "ack_timeout" -> "receive_timeout";
            case "unverified" -> "delivery_not_verified";
            default -> "acknowledged";
        };
        assertEquals(scenario.equals("unverified") ? List.of() : Set.of("closed","timeout").contains(scenario)
            ? List.of("status") : List.of("status","ack"),operations,scenario);
        assertEquals(!scenario.equals("unverified"),restored);
        assertEquals(FlowRuntime.Status.COMPLETE,ack.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.TextValue(expectedCode),((DataValue.RecordValue)ack.flowSnapshot().orElseThrow().state().locals().field("reply")).field("code"),scenario);
        assertEquals(Set.of("reload","delayed_start","ack_timeout").contains(scenario) ? List.of() : retainedInputs,service.inputs(),scenario);
        assertEquals(watermark,service.nextSequence(),scenario);
    }
}

