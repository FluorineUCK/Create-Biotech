package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The same template placed by the editor must use the existing finite serial and authorization paths. */
class ScratchReaderExtensionTest {
    @org.junit.jupiter.api.Test void fixedCommandCannotMasqueradeAsAValueReporter() {
        var key = new ModuleDefinition.Key("cb_extension:create_biotech:reader:status", 1);
        var binding = ScratchExtensions.binding(key).orElseThrow();
        var definition = binding.template("fixed_reader");
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var arguments = new LinkedHashMap<String, String>();
        binding.command().defaults().forEach((name, value) -> {
            nodes.put(name, new ComputationGraph.Constant(value)); arguments.put(name, name);
        });
        nodes.put("call", new ComputationGraph.ProcedureValue(key, "request", arguments));
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", "call"));
        var step = new FlowProgram.Evaluate("reporter", "inline", new FlowCallBindings(Map.of(), Map.of("value", "request")));
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(400, 16), List.of(definition, new FlowProgram.EndProcedure("end_fixed_reader")))))
            .withExtensions(Map.of("create_biotech:reader", 1L));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(step), new DataValue.RecordValue(Map.of()),
            canvas, Map.of(step.sourceId(), graph), FlowProgram.Execution.REGISTER_V1)));
        var document = workspace.snapshot();
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)), "The bad draft remains repairable");
        var failure = assertThrows(IllegalArgumentException.class,
            () -> PreparedProgram.prepare(document, StockApplicationDriverDocuments.hardware()));
        assertTrue(failure.getMessage().contains("Invalid fixed Scratch reporter binding"));
    }
    @ParameterizedTest @ValueSource(strings={"status","sample"})
    void publishedCommandSendsOneOrdinaryFrameAndCannotGrantItsOwnReadAccess(String operation) {
        var key = new ModuleDefinition.Key("cb_extension:create_biotech:reader:"+operation,1);
        var binding = ScratchExtensions.binding(key).orElseThrow();
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly()); draft.setExtension("create_biotech:reader",1L);
        String source = draft.placeTemplate(binding.template("template"),new FlowDraft.Socket(null,0),new ProgramDocument.Position(16,40));
        var call = (FlowProgram.Invoke)draft.snapshot().steps().getFirst();
        var graph = draft.snapshot().inline().get(source); var nodes = new LinkedHashMap<>(graph.nodes());
        var device = UUID.randomUUID(); var session = UUID.randomUUID();
        var arguments = new LinkedHashMap<>(binding.command().defaults());
        arguments.put("x",new DataValue.IntegerValue(9));arguments.put("y",new DataValue.IntegerValue(64));arguments.put("z",new DataValue.IntegerValue(-2));
        if(operation.equals("sample")) {arguments.put("device",new DataValue.TextValue(device.toString()));arguments.put("session",new DataValue.TextValue(session.toString()));}
        arguments.forEach((port,value)->nodes.put(graph.outputs().get(port),new ComputationGraph.Constant(value)));
        draft.editInline(source,new ComputationGraph(graph.inputs(),nodes,graph.outputs()),FlowInlineValues.bindings(call));
        var current = (FlowProgram.Invoke)draft.snapshot().steps().getFirst();
        draft.replace(source,new FlowProgram.Invoke(source,key,new FlowCallBindings(Map.of(),Map.of("queued","queued","request","request")),current.operands()));
        var workspace = new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));var document = workspace.snapshot();
        var limits = StockApplicationDriverDocuments.hardware();
        var codec = new SerialRecordCodec.Limits(4096,1048576,limits.archive());
        var tx = new SerialDevice(limits::serial,()->true,()->codec,()->{});
        var rx = new SerialDevice(limits::serial,()->true,()->codec,()->{});
        tx.configure("s0",Direction.EAST,true);rx.configure("s0",Direction.WEST,true);
        var host = new ComputerBlockHost(()->{},pin->{},tx);
        host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        host.setPaused(false,limits,true);
        var devices = Map.of(BlockPos.ZERO,tx,new BlockPos(2,0,0),rx); var cable = new BlockPos(1,0,0);
        var world = new CableDiscovery.World() {
            public boolean loaded(BlockPos pos){return true;}
            public CableDiscovery.Cell cell(BlockPos pos){return new CableDiscovery.Cell(pos.equals(cable)?CableDiscovery.Kind.CABLE:devices.containsKey(pos)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}
        };
        var network = new SerialNetwork();var discovery = new CommunicationNetworkLimits(64,4);
        var channel = new SharedSerialChannel.Limits(2,4096,128,8);
        var received = new ArrayList<DataValue.RecordValue>(); boolean restored = false;
        for(int tick=0;tick<180;tick++) {
            network.tick(world,devices,discovery,channel);rx.poll("s0").ifPresent(received::add);host.tick(limits,true);
            var state = host.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
            if (!restored && state.status()==FlowRuntime.Status.COMPLETE) {
                var saved = host.write();host = new ComputerBlockHost(()->{},pin->{},tx);host.read(saved);host.tick(limits,true);restored=true;
            }
        }
        assertTrue(restored);assertEquals(1,received.size(),"No hidden polling or resend after owner restoration");
        var state = host.flowSnapshot().orElseThrow().state();assertTrue(state.work()>1,"The wrapper lowers to paid ordinary instructions");
        assertEquals(SerialResult.send("queued"),state.locals().field("queued"));
        var frame = received.getFirst();assertEquals(state.locals().field("request"),frame.field("request"));
        var address = new DataValue.RecordValue(Map.of("x",arguments.get("x"),"y",arguments.get("y"),"z",arguments.get("z")));
        var service = new ReaderSamplingProtocol(device,session,address,Set.of(RedstoneReadComponent.ID));
        assertEquals(new DataValue.TextValue("denied"),service.respond(false,frame,selected->{fail("A frame cannot grant read access");return null;}).orElseThrow().field("code"));
        var response=service.respond(true,frame,selected->{assertEquals(Set.of(RedstoneReadComponent.ID),selected);return new DataValue.RecordValue(Map.of("level",new DataValue.IntegerValue(7)));}).orElseThrow();
        assertEquals(new DataValue.TextValue("ok"),response.field("code"));
        assertEquals(operation.equals("sample"),response.fields().containsKey("value"));
    }
}
