package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyCorrelationMatchTest {
    @Test void nativeCorrelationLoopsRestoreAndResetWithoutReusingTags() {
        var flow = new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Correlation("tag", "request"), new FlowProgram.EndForever("again"))).withAssembly();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(flow, new DataValue.RecordValue(Map.of()), LIMITS, true);
        host.step(LIMITS, true); host.step(LIMITS, true);
        var issued = host.flowSnapshot().orElseThrow();
        var tag = issued.state().locals().field("request");
        assertInstanceOf(DataValue.TextValue.class, tag);
        assertTrue(issued.state().assembly().isPresent());
        var saved = host.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved); restored.tick(LIMITS, true);
        assertTrue(restored.loaded()); assertEquals(issued, restored.flowSnapshot().orElseThrow());
        restored.step(LIMITS, true); restored.step(LIMITS, true); restored.step(LIMITS, true);
        assertNotEquals(tag, restored.flowSnapshot().orElseThrow().state().locals().field("request"));
        restored.reset(LIMITS, true); restored.step(LIMITS, true); restored.step(LIMITS, true);
        assertNotEquals(tag, restored.flowSnapshot().orElseThrow().state().locals().field("request"));
    }
    static FlowProgram equality(DataType left, DataType right) {
        var graph = new ComputationGraph(Map.of("left", left, "right", right), Map.of(
            "left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"),
            "equal", new ComputationGraph.ValueEquals("left", "right")), Map.of("value", "equal"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("compare", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("compare", "compare",
            new FlowCallBindings(Map.of("left", "left", "right", "right"), Map.of("value", "match")))), archive).withAssembly();
    }
    @Test void ordinaryTypedReplyComparisonDistinguishesCurrentStaleAndTypeMismatchedValues() {
        var type = new DataType.RecordType(Map.of("request", DataType.Scalar.TEXT));
        var current = new DataValue.RecordValue(Map.of("request", new DataValue.TextValue("run:1")));
        for (var reply : List.<DataValue>of(current, new DataValue.RecordValue(Map.of("request", new DataValue.TextValue("run:0"))),
                new DataValue.MapValue(Map.of("request", new DataValue.TextValue("run:1"))))) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(equality(type, DataType.Scalar.ANY), new DataValue.RecordValue(Map.of("left", current, "right", reply)), LIMITS, true);
            for (int step = 0; step < 150 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; step++) host.step(LIMITS, true);
            var done = host.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.COMPLETE, done.status());
            assertEquals(new DataValue.BooleanValue(reply instanceof DataValue.RecordValue r && r.equals(current)), done.locals().field("match"));
            assertEquals(current, done.locals().field("left")); assertEquals(reply, done.locals().field("right"));
        }
    }
    @Test void longTextComparisonRestoresEveryPaidPhaseWithoutPublishingEarly() {
        var input = new DataValue.TextValue("x".repeat(32));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(equality(DataType.Scalar.TEXT, DataType.Scalar.ANY),
            new DataValue.RecordValue(Map.of("left", input, "right", input)), LIMITS, true);
        int phases = 0;
        for (int i = 0; ; i++) {
            assertTrue(i < 60);
            var before = host.flowSnapshot().orElseThrow();
            var saved = host.write(); var restored = new ComputerBlockHost(() -> {});
            restored.read(saved); restored.tick(LIMITS, true);
            assertTrue(restored.loaded(), "Native comparison must restore with exact already-paid progress");
            assertEquals(before, restored.flowSnapshot().orElseThrow()); assertEquals(saved, restored.write());
            if (before.state().assembly().orElseThrow().comparison().isPresent()) {
                phases++; assertFalse(before.state().locals().fields().containsKey("match"));
            }
            host = restored;
            if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
            host.step(LIMITS, true);
        }
        assertEquals(35, phases);
        assertEquals(39, host.flowSnapshot().orElseThrow().state().work());
        assertEquals(new DataValue.BooleanValue(true), host.flowSnapshot().orElseThrow().state().locals().field("match"));
    }
    @Test void synchronousCallsAndManagedTasksKeepCorrelationAndPaidComparisonPrivate() {
        var key = new ModuleDefinition.Key("compare", 1);
        var base = equality(DataType.Scalar.TEXT, DataType.Scalar.ANY);
        var arguments = Map.<String,DataType>of("left",DataType.Scalar.TEXT,"right",DataType.Scalar.ANY);
        var returns = Map.<String,DataType>of("tag",DataType.Scalar.TEXT,"match",DataType.Scalar.BOOLEAN);
        var steps = List.<FlowProgram.Step>of(
            new FlowProgram.Invoke("first",key,new FlowCallBindings(Map.of("left","left","right","right"),Map.of("tag","first_tag","match","first_match"))),
            new FlowProgram.Invoke("second",key,new FlowCallBindings(Map.of("left","left","right","right"),Map.of("tag","second_tag","match","second_match"))),
            new FlowProgram.Sleep("idle","ticks"),
            new FlowProgram.Procedure("definition",key,arguments,returns,Map.of("tag","tag","match","match")),
            new FlowProgram.Correlation("tag","tag"), base.steps().getFirst(), new FlowProgram.EndProcedure("return"));
        var types = new LinkedHashMap<>(arguments); types.put("ticks",DataType.Scalar.INTEGER);
        var flow = new FlowProgram(types,steps,base.expressions()).withAssembly();
        var limits = ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3));
        var text = new DataValue.TextValue("x".repeat(32));
        var inputs = new DataValue.RecordValue(Map.of("left",text,"right",text));
        var mainInputs = new LinkedHashMap<>(inputs.fields()); mainInputs.put("ticks",new DataValue.IntegerValue(1000));
        var host = new ComputerBlockHost(() -> {}); host.installFlow(flow,new DataValue.RecordValue(mainInputs),limits,true);
        var a=host.startFlowTask(key,inputs,limits,true); var b=host.startFlowTask(key,inputs,limits,true);
        boolean privateComparison=false;
        for(int i=0;i<350;i++) {
            var before=host.flowSnapshot().orElseThrow();
            privateComparison |= before.state().assembly().orElseThrow().comparison().isPresent()
                && !before.state().assembly().orElseThrow().calls().orElseThrow().frames().isEmpty();
            var raw=host.write(); var restored=new ComputerBlockHost(() -> {});restored.read(raw);restored.tick(limits,true);
            assertTrue(restored.loaded(),()->restored.diagnostic().toString());assertEquals(before,restored.flowSnapshot().orElseThrow()); host=restored;
            if (before.state().status()==FlowRuntime.Status.SLEEPING && host.flowTask(a).orElseThrow().status()==FlowRuntime.Status.COMPLETE && host.flowTask(b).orElseThrow().status()==FlowRuntime.Status.COMPLETE)break;
            host.step(limits,true);
        }
        assertTrue(privateComparison);
        var main=host.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.SLEEPING,main.status());
        var resultA=host.flowTask(a).orElseThrow();var resultB=host.flowTask(b).orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE,resultA.status());assertEquals(FlowRuntime.Status.COMPLETE,resultB.status());
        assertEquals(resultA.work(),resultB.work());
        assertEquals(4,new HashSet<>(List.of(main.locals().field("first_tag"),main.locals().field("second_tag"),resultA.locals().field("tag"),resultB.locals().field("tag"))).size());
        for(var result:List.of(resultA,resultB))assertEquals(new DataValue.BooleanValue(true),result.locals().field("match"));
        assertEquals(new DataValue.BooleanValue(true),main.locals().field("first_match"));assertEquals(new DataValue.BooleanValue(true),main.locals().field("second_match"));
    }
    static DataValue.RecordValue patch(DataValue.RecordValue source,String field,DataValue value) {
        var fields=new LinkedHashMap<>(source.fields());fields.put(field,value);return new DataValue.RecordValue(fields);
    }
    static DataValue.RecordValue record(DataValue.RecordValue source,String field) {return (DataValue.RecordValue)source.field(field);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"paid","offset","source","pc","image","flow_version","owner_version","data_version","completed"})
    void tamperedOrOutOfContextComparisonRetainsTheRawOwnerWithoutRunning(String mutation) {
        var text=new DataValue.TextValue("x".repeat(32));var host=new ComputerBlockHost(() -> {});
        host.installFlow(equality(DataType.Scalar.TEXT,DataType.Scalar.ANY),new DataValue.RecordValue(Map.of("left",text,"right",text)),LIMITS,true);
        for(int i=0;i<12;i++)host.step(LIMITS,true);
        if(mutation.equals("completed"))while(host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().comparison().orElseThrow().paidWork()<35)host.step(LIMITS,true);
        var raw=(net.minecraft.nbt.CompoundTag)host.write();
        var owner=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),LIMITS.archive());
        var flow=record(owner,"flow");var state=record(flow,"state");var assembly=record(state,"assembly");var comparison=record(assembly,"comparison");
        switch(mutation) {
            case "paid" -> comparison=patch(comparison,"paid",new DataValue.IntegerValue(((DataValue.IntegerValue)comparison.field("paid")).value()+1));
            case "offset" -> {
                var progress=record(comparison,"progress");var frames=((DataValue.ListValue)progress.field("frames")).values();
                var frame=patch((DataValue.RecordValue)frames.getFirst(),"offset",new DataValue.IntegerValue(9));
                comparison=patch(comparison,"progress",patch(progress,"frames",new DataValue.ListValue(List.of(frame))));
            }
            case "source" -> assembly=patch(assembly,"registers",patch(record(assembly,"registers"),"2",new DataValue.TextValue("z".repeat(32))));
            case "pc" -> assembly=patch(assembly,"pc",new DataValue.IntegerValue(1));
            case "image" -> assembly=patch(assembly,"image",new DataValue.TextValue("0".repeat(64)));
            case "flow_version" -> flow=patch(flow,"version",new DataValue.IntegerValue(37));
            case "owner_version" -> raw.putInt(com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.MARKER,35);
            case "data_version" -> assembly=patch(assembly,"data_version",new DataValue.IntegerValue(5));
            case "completed" -> comparison=patch(comparison,"progress",patch(record(comparison,"progress"),"unequal",new DataValue.BooleanValue(true)));
            default -> throw new AssertionError(mutation);
        }
        assembly=patch(assembly,"comparison",comparison);state=patch(state,"assembly",assembly);flow=patch(flow,"state",state);owner=patch(owner,"flow",flow);
        raw.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(owner,LIMITS.archive()));
        var restored=new ComputerBlockHost(() -> {});restored.read(raw);restored.tick(LIMITS,true);
        assertFalse(restored.loaded());assertEquals(raw,restored.write());assertTrue(restored.diagnostic().isPresent());
    }
    @Test void comparisonWorkingRamAndArchiveRejectAtomicallyWithoutChangingSourcesOrDestination() {
        var base=equality(DataType.Scalar.TEXT,DataType.Scalar.ANY);var types=new LinkedHashMap<>(base.inputs());types.put("match",DataType.Scalar.BOOLEAN);
        var flow=new FlowProgram(types,base.steps(),base.expressions()).withAssembly();
        var text=new DataValue.TextValue("x".repeat(64));
        var inputs=new DataValue.RecordValue(Map.of("left",text,"right",text,"match",new DataValue.BooleanValue(false)));
        var host=new ComputerBlockHost(() -> {});host.installFlow(flow,inputs,LIMITS,true);host.step(LIMITS,true);host.step(LIMITS,true);
        var before=host.flowSnapshot().orElseThrow();var rawBefore=host.write();
        long retained=before.state().controlUnits()+flow.usage(LIMITS.hardware()).constantUnits();
        var tight=new ResourceLimits(1000,retained,1);
        var runtime=FlowRuntime.restore(flow,before.state(),tight);runtime.load();runtime.setPaused(false);runtime.advance(1);
        assertEquals(FlowRuntime.Status.FAILED,runtime.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,runtime.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(before.state().assembly(),runtime.checkpoint().assembly());assertEquals(inputs,runtime.checkpoint().locals());
        host.step(LIMITS,true);var after=host.flowSnapshot().orElseThrow();
        var archiveData=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(((net.minecraft.nbt.CompoundTag)host.write()).getCompound("Data"),LIMITS.archive());
        long archivePeak=archiveData.storageUnits()+after.state().assembly().orElseThrow().comparison().orElseThrow().ownedUnits();
        var archive=ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS.hardware(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,archivePeak-1)));
        var rejected=new ComputerBlockHost(() -> {});rejected.read(rawBefore);assertTrue(rejected.synchronize(archive,true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT,assertThrows(ComputerBlockHost.HostException.class,()->rejected.step(archive,true)).code());
        assertEquals(rawBefore,rejected.write());rejected.step(LIMITS,true);assertEquals(after,rejected.flowSnapshot().orElseThrow());
        var programTight=ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(flow.usage(LIMITS.hardware()).programUnits()-1,100000,1),LIMITS.archive()));
        var original=rejected.write();assertThrows(RuntimeException.class,()->rejected.installFlow(flow,inputs,programTight,true));assertEquals(original,rejected.write());
    }
    @Test void typedTraversalCoversNestedContainersNbtNullErrorsAndScalarDistinctions() {
        var one=new DataValue.IntegerValue(1);var key="𝄞".repeat(16);
        var named=new LinkedHashMap<String,DataValue>();named.put("a",new DataValue.ListValue(List.of(one,new DataValue.TextValue(key))));
        named.put("b",new DataValue.MapValue(Map.of(key,new DataValue.ErrorValue("code","message"))));named.put("c",DataValue.NullValue.INSTANCE);
        var reversed=new LinkedHashMap<String,DataValue>();reversed.put("c",named.get("c"));reversed.put("b",named.get("b"));reversed.put("a",named.get("a"));
        var pairs=List.of(List.<DataValue>of(new DataValue.RecordValue(named),new DataValue.RecordValue(reversed)),
            List.<DataValue>of(new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("a",new DataValue.NbtValue(3,one)))),new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("a",new DataValue.NbtValue(3,one))))),
            List.<DataValue>of(new DataValue.NbtValue(1,one),new DataValue.NbtValue(3,one)),
            List.<DataValue>of(one,new DataValue.TextValue("1")),List.<DataValue>of(one,new DataValue.DecimalValue(1)),
            List.<DataValue>of(new DataValue.DecimalValue(0),new DataValue.DecimalValue(-0.0)),
            List.<DataValue>of(new DataValue.RecordValue(named),new DataValue.MapValue(named)),
            List.<DataValue>of(new DataValue.ListValue(List.of(one,new DataValue.TextValue("1"))),new DataValue.ListValue(List.of(new DataValue.TextValue("1"),one))),
            List.<DataValue>of(new DataValue.ErrorValue(key,"a"),new DataValue.ErrorValue(key,"b")),
            List.<DataValue>of(DataValue.NullValue.INSTANCE,DataValue.NullValue.INSTANCE));
        for(var pair:pairs) {
            var host=new ComputerBlockHost(() -> {});host.installFlow(equality(DataType.Scalar.ANY,DataType.Scalar.ANY),new DataValue.RecordValue(Map.of("left",pair.getFirst(),"right",pair.getLast())),LIMITS,true);
            for(int i=0;host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;i++) {
                assertTrue(i<1000);host.step(LIMITS,true);
                var before=host.flowSnapshot().orElseThrow();var raw=host.write();var restored=new ComputerBlockHost(() -> {});restored.read(raw);restored.tick(LIMITS,true);
                assertTrue(restored.loaded(),()->restored.diagnostic().toString());assertEquals(before,restored.flowSnapshot().orElseThrow());host=restored;
            }
            var done=host.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
            assertEquals(new DataValue.BooleanValue(pair.getFirst().equals(pair.getLast())),done.locals().field("match"));
            assertEquals(pair.getFirst(),done.locals().field("left"));assertEquals(pair.getLast(),done.locals().field("right"));
        }
    }
    @Test void longerInputChargesRealUtf16WorkUnderTheExistingEightCycleCpuBudget() {
        for(int length:List.of(1,64,1024)) {
            var limits=ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(256,65536,8),LIMITS.archive()));
            var text=new DataValue.TextValue("x".repeat(length));var flow=equality(DataType.Scalar.TEXT,DataType.Scalar.ANY);
            assertEquals(13,flow.usage(limits.hardware()).programUnits());
            var host=new ComputerBlockHost(() -> {});host.installFlow(flow,new DataValue.RecordValue(Map.of("left",text,"right",text)),limits,true);host.setPaused(false,limits,true);
            for(int tick=1;host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;tick++) {
                assertTrue(tick<150);long work=host.flowSnapshot().orElseThrow().state().work();host.tick(tick,limits,true,Map.of());
                var after=host.flowSnapshot().orElseThrow().state();assertTrue(after.work()-work<=8);
                long paid=after.work();host.tick(tick,limits,true,Map.of());assertEquals(paid,host.flowSnapshot().orElseThrow().state().work());
            }
            assertEquals(length+7,host.flowSnapshot().orElseThrow().state().work());
            assertEquals(new DataValue.BooleanValue(true),host.flowSnapshot().orElseThrow().state().locals().field("match"));
        }
    }
    @Test void coldComparisonValidationAndStandaloneArchiveChargeTheirTemporaryFrames() {
        var text=new DataValue.TextValue("x".repeat(64));var flow=equality(DataType.Scalar.TEXT,DataType.Scalar.ANY);
        var host=new ComputerBlockHost(() -> {});host.installFlow(flow,new DataValue.RecordValue(Map.of("left",text,"right",text)),LIMITS,true);
        for(int i=0;i<12;i++)host.step(LIMITS,true);
        var snapshot=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.Snapshot(flow,host.flowSnapshot().orElseThrow().state());
        var encoded=com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.encode(snapshot,LIMITS.archive());
        var restored=com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.decode(encoded,LIMITS.archive());
        long retained=restored.state().controlUnits()+flow.usage(LIMITS.hardware()).constantUnits();
        assertEquals(ComputationException.Code.MEMORY_LIMIT,assertThrows(ComputationException.class,
            ()->FlowRuntime.restore(flow,restored.state(),new ResourceLimits(1000,retained+8,1))).code());
        assertEquals(snapshot.state(),restored.state());
        long units=com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(encoded,LIMITS.archive()).storageUnits();
        var wireOnly=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,units);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            ()->com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec.encode(snapshot,wireOnly));
    }
    @Test void ordinaryTagRecordsAndTypedMatchesDriveBothNativeBranchPathsWithSourcePreservation() {
        var targetType=new DataType.RecordType(Map.of("volume",DataType.Scalar.TEXT));
        var recordType=new DataType.RecordType(Map.of("request",DataType.Scalar.TEXT,"target",targetType));
        var graph=new ComputationGraph(Map.of("request",DataType.Scalar.TEXT,"target",targetType),Map.of(
            "request",new ComputationGraph.Input("request"),"target",new ComputationGraph.Input("target"),
            "record",new ComputationGraph.MakeRecord(Map.of("request","request","target","target"))),Map.of("value","record"));
        var entries=new LinkedHashMap<>(equality(recordType,DataType.Scalar.ANY).expressions().program().entries());
        entries.put("build",new ProgramDefinition.Entry(graph,List.of(),List.of()));
        for(int value:List.of(0,1))entries.put("constant"+value,new ProgramDefinition.Entry(new ComputationGraph(Map.of(),
            Map.of("value",new ComputationGraph.Constant(new DataValue.IntegerValue(value))),Map.of("value","value")),List.of(),List.of()));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),entries),Map.of());
        var steps=new ArrayList<FlowProgram.Step>();steps.add(new FlowProgram.Correlation("first","first"));steps.add(new FlowProgram.Correlation("second","second"));
        for(String name:List.of("current","stale"))steps.add(new FlowProgram.Evaluate("build_"+name,"build",new FlowCallBindings(Map.of("request",name.equals("current")?"first":"second","target","target"),Map.of("value",name))));
        for(String name:List.of("match","old")) {
            steps.add(new FlowProgram.If(name,new FlowInput.Expression("compare","value",Map.of("left","current","right",name.equals("match")?"current":"stale"))));
            steps.add(new FlowProgram.Evaluate(name+"_yes","constant1",new FlowCallBindings(Map.of(),Map.of("value",name+"_branch"))));
            steps.add(new FlowProgram.Otherwise(name+"_else"));
            steps.add(new FlowProgram.Evaluate(name+"_no","constant0",new FlowCallBindings(Map.of(),Map.of("value",name+"_branch"))));steps.add(new FlowProgram.EndIf(name+"_end"));
        }
        var flow=new FlowProgram(Map.of("target",targetType),steps,archive).withAssembly();
        assertTrue(flow.usage(LIMITS.hardware()).programUnits()<256);
        var target=new DataValue.RecordValue(Map.of("volume",new DataValue.TextValue("data")));
        var host=new ComputerBlockHost(() -> {});host.installFlow(flow,new DataValue.RecordValue(Map.of("target",target)),LIMITS,true);
        var visited=new HashSet<String>();var comparisonSources=new HashSet<String>();
        for(int i=0;host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;i++) {
            assertTrue(i<700);var state=host.flowSnapshot().orElseThrow().state();String source=steps.get(state.cursor()).sourceId();visited.add(source);
            if(state.assembly().orElseThrow().comparison().isPresent())comparisonSources.add(source);host.step(LIMITS,true);
        }
        var done=host.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
        assertEquals(Set.of("match","old"),comparisonSources);assertTrue(visited.containsAll(Set.of("match_yes","old_no")));
        assertFalse(visited.contains("match_no"));assertFalse(visited.contains("old_yes"));
        assertEquals(new DataValue.IntegerValue(1),done.locals().field("match_branch"));assertEquals(new DataValue.IntegerValue(0),done.locals().field("old_branch"));
        assertEquals(new DataValue.RecordValue(Map.of("request",done.locals().field("first"),"target",target)),done.locals().field("current"));
        assertEquals(new DataValue.RecordValue(Map.of("request",done.locals().field("second"),"target",target)),done.locals().field("stale"));
    }
    @Test void exhaustedCorrelationOrdinalFailsWithoutWrappingOrPublishing() {
        var flow=new FlowProgram(Map.of("request",DataType.Scalar.TEXT),List.of(new FlowProgram.Forever("loop"),new FlowProgram.Correlation("tag","request"),new FlowProgram.EndForever("again"))).withAssembly();
        var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),flow,new DataValue.RecordValue(Map.of("request",new DataValue.TextValue("keep"))),LIMITS.hardware());runtime.advance(1);
        var before=runtime.checkpoint();var exhausted=new FlowRuntime.Checkpoint(before.computer(),before.flow(),before.cursor(),before.status(),before.locals(),Long.MAX_VALUE-1,
            before.paused(),before.expression(),before.diagnostic(),before.sleepTicks(),before.signals(),Long.MAX_VALUE-1,before.repeats(),before.inputInvocation(),before.calls(),before.entry(),before.assembly());
        var restored=FlowRuntime.restore(flow,exhausted,LIMITS.hardware());restored.load();assertEquals(1,restored.advance(1));
        assertEquals(FlowRuntime.Status.FAILED,restored.checkpoint().status());assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW,restored.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(before.assembly(),restored.checkpoint().assembly());assertEquals(before.locals(),restored.checkpoint().locals());assertEquals(0,restored.advance(1));
    }
}
