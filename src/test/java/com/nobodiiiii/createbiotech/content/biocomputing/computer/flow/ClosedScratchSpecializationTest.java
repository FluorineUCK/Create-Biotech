package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClosedScratchSpecializationTest {
    static final ResourceLimits LIMITS = new ResourceLimits(4096,100000,1);
    static final ModuleDefinition.Key WRAP = new ModuleDefinition.Key("outer", 1);
    static final ModuleDefinition.Key INNER = new ModuleDefinition.Key("inner", 1);
    static final Map<String,DataType> PARAMS = Map.of("x", DataType.Scalar.ANY, "ticks", DataType.Scalar.INTEGER);
    static final Map<String,DataType> RETURNS = Map.of("value", DataType.Scalar.ANY, "ok", DataType.Scalar.BOOLEAN);
    static final Map<String,DataType> INPUTS = Map.of("number",DataType.Scalar.INTEGER,"flag",DataType.Scalar.BOOLEAN,"ticks",DataType.Scalar.INTEGER);
    static final DataValue.RecordValue INITIAL = new DataValue.RecordValue(Map.of("number",new DataValue.IntegerValue(37),
        "flag",new DataValue.BooleanValue(false),"ticks",new DataValue.IntegerValue(2)));

    static FlowProgram source() {
        var graph = new ComputationGraph(Map.of("operand",DataType.Scalar.ANY),Map.of(
            "input",new ComputationGraph.Input("operand"),"check",new ComputationGraph.ValueEquals("input","input")),Map.of("ok","check"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),
            Map.of("eq",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        return new FlowProgram(INPUTS,List.of(
            call("first",WRAP,"number","first_value","first_ok"),
            call("second",WRAP,"flag","second_value","second_ok"),
            call("third",WRAP,"number","third_value","third_ok"),
            new FlowProgram.Procedure("outer_def",WRAP,PARAMS,RETURNS,Map.of("value","nested","ok","checked")),
            call("nested_call",INNER,"x","nested","checked"),
            new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("inner_def",INNER,PARAMS,RETURNS,Map.of("value","x","ok","equal")),
            new FlowProgram.Evaluate("evaluate","eq",new FlowCallBindings(Map.of("operand","x"),Map.of("ok","equal"))),
            new FlowProgram.Sleep("sleep","ticks"),new FlowProgram.EndProcedure("inner_end")),archive).withAssembly();
    }

    private static FlowProgram.Invoke call(String id, ModuleDefinition.Key target, String value, String result, String ok) {
        return new FlowProgram.Invoke(id,target,new FlowCallBindings(Map.of("x",value,"ticks","ticks"),Map.of("value",result,"ok",ok)));
    }

    @Test void nestedCallUsesTwoConcreteInstancesAndReusesTheRepeatedType() {
        var source=source();
        var specialized=source.withClosedSpecialization();
        var image=FlowXBus.image(specialized);
        assertTrue(specialized.closedSignatureAt("nested_call",LIMITS));
        assertFalse(source.closedSignatureAt("nested_call",LIMITS));
        var rejected=assertThrows(ComputationException.class,()->specialized.usage(new ResourceLimits(11,100000,1)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT,rejected.code());
        assertTrue(rejected.getMessage().contains("routine overhead"),rejected.getMessage());
        assertEquals(4,image.procedures().size(),"two nested procedures × two proven argument vectors");
        assertFalse(image.registerTypes().contains(DataType.Scalar.ANY));
        assertFalse(image.instructionFamilies().contains(InstructionFamily.ISA_1B));
        assertEquals(source.steps(),specialized.steps(),"specialization must never rewrite stored source");
        assertEquals(Set.of(8),image.code().stream().filter(c->c.instruction() instanceof RegisterProgram.Sleep)
            .map(RegisterProgram.Code::source).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void leadingDefinitionsKeepTheMainInputSchema() {
        var source=source();
        var steps=new ArrayList<>(source.steps().subList(3,source.steps().size()));
        steps.addAll(source.steps().subList(0,3));
        var reordered=new FlowProgram(INPUTS,steps,source.expressions()).withClosedSpecialization();
        var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),reordered,INITIAL,LIMITS);
        assertEquals(INITIAL,runtime.checkpoint().locals());
        assertEquals(new DataType.RecordType(INPUTS),reordered.compile(LIMITS).locals().get(0));
    }

    @Test void definitionOnlyScratchDocumentCanStartWithDeclaredInitialValues() {
        var definition=new FlowProgram.Procedure("idle_def",INNER,Map.of("x",DataType.Scalar.ANY),
            Map.of("value",DataType.Scalar.ANY),Map.of("value","x"));
        var p=new FlowProgram(INPUTS,List.of(definition,new FlowProgram.EndProcedure("idle_end")))
            .withClosedSpecialization();
        var document=document(p,INITIAL);
        assertTrue(document.flow().orElseThrow().steps().isEmpty());
        // A dormant open signature keeps the native requirement; isolate startup schema here.
        var base=hardware(4096,100000,true);
        var master=new ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),
            base.serial(),base.taskSlots(),true,true);
        var result=finish(document,master,false);
        assertEquals(FlowRuntime.Status.COMPLETE,result.status());
        assertEquals(INITIAL,result.locals());
    }

    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000,1000000,1000000);
    private static ComputerHostLimits hardware(long code,long ram,boolean parser) {
        var base=new ComputerHostLimits(new ResourceLimits(code,ram,1),ARCHIVE);
        return new ComputerHostLimits(base.hardware(),ARCHIVE,base.mailbox(),base.pins(),base.serial(),base.taskSlots(),parser,false);
    }
    private static ProgramDocument document(FlowProgram source,DataValue.RecordValue initial) {
        return new ProgramDocument(source.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(source,initial)));
    }

    @Test void documentAndOwnerRestoreSamePolicyAndTypedResultsAtWorkOne() {
        var p=source().withClosedSpecialization();
        var saved=document(p,INITIAL);
        var wire=ProgramDocumentWire.decode(ProgramDocumentWire.encode(saved,ARCHIVE),ARCHIVE);
        assertEquals(saved,wire);
        var reconstructed=wire.flow().orElseThrow().program(wire.archive());
        assertEquals(p,reconstructed,"Same comparison used by ComputerBehaviour source association");
        assertEquals(p,FlowProgramCodec.decode(FlowProgramCodec.encode(p)));
        var noParser=assertThrows(IllegalArgumentException.class,()->PreparedProgram.prepare(wire,hardware(4096,100000,false)));
        assertTrue(noParser.getMessage().contains("ISA-1a"),noParser.getMessage());
        var uninterrupted=finish(wire,hardware(4096,100000,true),false);
        var reloaded=finish(wire,hardware(4096,100000,true),true);
        assertEquals(uninterrupted.locals(),reloaded.locals());
        assertEquals(uninterrupted.work(),reloaded.work());
        assertEquals(new DataValue.IntegerValue(37),reloaded.locals().field("first_value"));
        assertEquals(new DataValue.BooleanValue(false),reloaded.locals().field("second_value"));
        assertEquals(new DataValue.IntegerValue(37),reloaded.locals().field("third_value"));
        for(String call:List.of("first","second","third"))
            assertEquals(new DataValue.BooleanValue(true),reloaded.locals().field(call+"_ok"));
        var usage=p.usage(LIMITS);
        assertEquals(usage,p.usage(new ResourceLimits(usage.programUnits(),100000,1)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT,
            assertThrows(ComputationException.class,()->p.usage(new ResourceLimits(usage.programUnits()-1,100000,1))).code());
        long low=0,high=100000;
        while(low+1<high) {
            long middle=(low+high)/2;
            if(fits(wire,usage.programUnits(),middle)) high=middle; else low=middle;
        }
        assertTrue(fits(wire,usage.programUnits(),high));
        assertFalse(fits(wire,usage.programUnits(),high-1));
        System.out.printf("closed code=%d constants=%d registers=%d work=%d RAM=%d%n",
            usage.programUnits(),usage.constantUnits(),usage.registers(),reloaded.work(),high);
    }

    @Test void genericConstructorReturnsDifferentConcreteListTypesFromTheSameVisualBody() {
        var original=source();
        var definition=original.expressions().program();
        var raw=definition.entry("eq").graph();
        var nodes=new TreeMap<>(raw.nodes()); nodes.put("list",new ComputationGraph.MakeList(List.of("input")));
        var graph=new ComputationGraph(raw.inputs(),nodes,Map.of("ok","check","value","list"));
        var archive=new ProgramArchive(new ProgramDefinition(definition.graph(),Map.of(),List.of(),List.of(),
            Map.of("eq",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var steps=new ArrayList<>(original.steps());
        steps.set(6,new FlowProgram.Procedure("inner_def",INNER,PARAMS,RETURNS,Map.of("value","made","ok","equal")));
        steps.set(7,new FlowProgram.Evaluate("evaluate","eq",new FlowCallBindings(Map.of("operand","x"),Map.of("ok","equal","value","made"))));
        var specialized=new FlowProgram(original.inputs(),steps,archive).withClosedSpecialization();
        var result=finish(document(specialized,INITIAL),hardware(4096,100000,true),true);
        assertEquals(new DataValue.ListValue(List.of(INITIAL.field("number"))),result.locals().field("first_value"));
        assertEquals(new DataValue.ListValue(List.of(INITIAL.field("flag"))),result.locals().field("second_value"));
        var image=FlowXBus.image(specialized);
        assertTrue(image.registerTypes().containsAll(List.of(new DataType.ListType(DataType.Scalar.INTEGER),new DataType.ListType(DataType.Scalar.BOOLEAN))));
        assertFalse(image.registerTypes().contains(new DataType.ListType(DataType.Scalar.ANY)));
        assertTrue(image.instructionFamilies().contains(InstructionFamily.ISA_1A));
    }

    @Test void openDeclarationIsNotNarrowedFromInitialValueAndFaultDoesNotPublishReturn() {
        var p=source().withClosedSpecialization();
        var open=new TreeMap<>(p.inputs()); open.put("number",DataType.Scalar.ANY);
        var cannot=new FlowProgram(open,p.steps(),p.expressions(),p.execution(),p.compilation());
        var failure=assertThrows(ComputationException.class,()->PreparedProgram.prepare(document(cannot,INITIAL),hardware(4096,100000,true)));
        assertEquals("first",failure.location()); assertTrue(failure.getMessage().contains("proved INTEGER or BOOLEAN"));
        var task=assertThrows(ComputationException.class,()->p.compile(LIMITS,Optional.of(WRAP)));
        assertEquals("outer_def",task.location());
        assertTrue(task.getMessage().contains("does not provide a task entry ABI"));
        var values=new TreeMap<>(INITIAL.fields()); values.put("ticks",new DataValue.IntegerValue(-1));
        var failed=finish(document(p,new DataValue.RecordValue(values)),hardware(4096,100000,true),false);
        assertEquals(FlowRuntime.Status.FAILED,failed.status());
        assertEquals("sleep",failed.diagnostic().orElseThrow().location());
        assertFalse(failed.locals().fields().containsKey("first_value"));
        assertFalse(failed.locals().fields().containsKey("first_ok"));
    }

    @Test void oldPolicyHasNoNewEncodingFieldAndUnknownNewPolicyFailsClosed() {
        var original=source();
        var old=document(original,INITIAL);
        var encoded=ProgramDocumentCodec.encode(old);
        assertEquals(new DataValue.IntegerValue(23),encoded.field("version"));
        assertFalse(((DataValue.RecordValue)encoded.field("flow")).fields().containsKey("compilation"));
        assertEquals(FlowXBus.image(original).begin(INITIAL).image(),FlowXBus.image(FlowProgramCodec.decode(FlowProgramCodec.encode(original))).begin(INITIAL).image());
        assertNotEquals(FlowXBus.image(original).begin(INITIAL).image(),FlowXBus.image(original.withClosedSpecialization()).begin(INITIAL).image());
        var fresh=ProgramDocumentCodec.encode(document(original.withClosedSpecialization(),INITIAL));
        assertEquals(new DataValue.IntegerValue(32),fresh.field("version"));
        var fields=new TreeMap<>(((DataValue.RecordValue)fresh.field("flow")).fields());
        var root=new TreeMap<>(fresh.fields());
        root.put("version",new DataValue.IntegerValue(31));
        assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(root)));
        root.put("version",new DataValue.IntegerValue(32));
        fields.put("compilation",new DataValue.TextValue("unknown"));
        root.put("flow",new DataValue.RecordValue(fields));
        assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(root)));
        var installed=new TreeMap<>(((DataValue.RecordValue)FlowProgramCodec.encode(original.withClosedSpecialization())).fields());
        installed.put("compilation",new DataValue.TextValue("unknown"));
        assertThrows(ArchiveException.class,()->FlowProgramCodec.decode(new DataValue.RecordValue(installed)));
        installed.put("compilation",new DataValue.TextValue("closed_v1")); installed.remove("execution");
        assertThrows(ArchiveException.class,()->FlowProgramCodec.decode(new DataValue.RecordValue(installed)));
    }

    private static boolean fits(ProgramDocument source,long code,long ram) {
        try {
            var state=finish(source,hardware(code,ram,true),false);
            if(state.status()==FlowRuntime.Status.COMPLETE)return true;
            assertEquals(ComputationException.Code.MEMORY_LIMIT,state.diagnostic().orElseThrow().code());
            return false;
        } catch(ComputationException e) { assertEquals(ComputationException.Code.MEMORY_LIMIT,e.code()); return false; }
    }

    private static FlowRuntime.Checkpoint finish(ProgramDocument document,ComputerHostLimits limits,boolean reload) {
        var prepared=PreparedProgram.prepare(document,limits);
        var host=new ComputerBlockHost(()->{});
        host.installProgram(prepared,ComputerRuntime.MemoryPolicy.RESET,limits,true);
        host.setPaused(false,limits,true);
        int sleeps=0;
        for(int tick=0;tick<250;tick++) {
            host.tick(limits,true);
            var snapshot=host.flowSnapshot().orElseThrow(); var state=snapshot.state();
            if(state.status()==FlowRuntime.Status.SLEEPING && state.sleepTicks()==2) {
                sleeps++;
                assertEquals("sleep",snapshot.program().steps().get(state.cursor()).sourceId());
            }
            if(state.status()==FlowRuntime.Status.COMPLETE || state.status()==FlowRuntime.Status.FAILED) {
                if(reload) assertEquals(3,sleeps);
                return state;
            }
            if(reload) {
                var copy=new ComputerBlockHost(()->{});
                copy.read(host.write()); copy.setPaused(false,limits,true);
                assertEquals(snapshot,copy.flowSnapshot().orElseThrow());
                host=copy;
            }
        }
        throw new AssertionError("did not finish");
    }
}
