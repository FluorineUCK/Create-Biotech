package com.nobodiiiii.createbiotech.content.biocomputing.persistence;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SortGraphTest {
    static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("sort_key", 3);
    static final ResourceLimits ROOMY = new ResourceLimits(1000, 1_000_000, 1);
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.ListValue items() { return new DataValue.ListValue(List.of(obj("name","a","rank",2),obj("name","b","rank",1),obj("name","c","rank",2))); }
    static DataValue.RecordValue empty() { return obj(); }

    /** Read the proposed canonical interchange rather than bypassing node registration with a test-only evaluator. */
    static ComputationGraph graph(boolean descending) {
        var base = new ComputationGraph(Map.of("items",new DataType.ListType(ANY)), Map.of(
            "items",new ComputationGraph.Input("items"),"offset",new ComputationGraph.Constant(integer(10)),
            "sorted",new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP,"items",KEY,"item",Map.of("offset","offset"),"key")),Map.of("result","sorted"));
        var encoded = record(GraphArchiveCodec.encode(base));
        var nodes = new LinkedHashMap<>(record(encoded.field("nodes")).fields());
        var sort = new LinkedHashMap<>(record(nodes.get("sorted")).fields());
        sort.put("kind",new DataValue.TextValue("sort"));sort.remove("mode");sort.put("descending",new DataValue.BooleanValue(descending));
        nodes.put("sorted",new DataValue.RecordValue(sort));
        var fields = new LinkedHashMap<>(encoded.fields());fields.put("nodes",new DataValue.RecordValue(nodes));
        return GraphArchiveCodec.decode(new DataValue.RecordValue(fields),9);
    }
    static ComputationGraph keyGraph() {
        return new ComputationGraph(Map.of("item",ANY,"offset",INTEGER), Map.of(
            "item",new ComputationGraph.Input("item"),"offset",new ComputationGraph.Input("offset"),
            "rank",new ComputationGraph.Field("item","rank"),
            "key",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","rank","right","offset"))),Map.of("key","key"));
    }
    static ProgramArchive archive(boolean descending) { return archive(graph(descending),keyGraph()); }
    static ProgramArchive archive(ComputationGraph graph,ComputationGraph key) {
        return new ProgramArchive(new ProgramDefinition(graph,Map.of("saved",new ProgramDefinition.Cell(new DataType.ListType(ANY),new DataValue.ListValue(List.of()))),
            List.of(new ProgramDefinition.Write("saved","result"))),Map.of(KEY,new ModuleDefinition(KEY,key)));
    }
    static DataValue.RecordValue input(DataValue items) { return obj("items",items); }
    static ComputationMachine run(ProgramArchive archive,DataValue items,ResourceLimits limits) {
        var machine=new ComputationMachine(archive.compile(limits));machine.begin(input(items));
        for(int guard=0;machine.report().status()==ComputationMachine.Status.RUNNING;guard++){assertTrue(guard<10000);machine.step();}
        return machine;
    }

    @Test void canonicalSortInvokesPinnedKeyWithNamedCapturesAndReturnsOriginalItems() {
        var source=items();
        for(boolean descending:new boolean[]{false,true}) {
            var program=archive(descending);var installed=program.compile(ROOMY);
            assertEquals(new DataType.ListType(ANY),installed.root().outputType().fields().get("result"));
            assertEquals(Set.of(KEY),ProgramCompiler.moduleReferences(program.program().graph()));
            var result=run(program,source,ROOMY);
            assertEquals(ComputationMachine.Status.SUCCEEDED,result.report().status());
            var expected=new DataValue.ListValue(descending?List.of(source.values().get(0),source.values().get(2),source.values().get(1)):
                List.of(source.values().get(1),source.values().get(0),source.values().get(2)));
            assertEquals(expected,result.memory().field("saved"));assertEquals(items(),source);
        }
    }

    @Test void everyHostSaveRestoresExactSubgraphAndMergeProgressWithoutReplayingWork() {
        var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY,ARCHIVE));var host=new ComputerBlockHost(()->{});
        host.install(archive(false),ComputerRuntime.MemoryPolicy.RESET,limits,true);host.begin(input(items()),limits,true);
        var phases=new HashSet<String>();int keyCalls=0;
        for(int guard=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;guard++) {
            assertTrue(guard<200);var before=host.snapshot().orElseThrow();
            for(var frame:before.machine().frames())phases.add(frame.getClass().getSimpleName());
            if(before.machine().frames().getLast() instanceof MachineCheckpoint.GraphState child
                &&child.module().equals(Optional.of(KEY))&&child.cursor()==0)keyCalls++;
            var saved=host.write();var restored=new ComputerBlockHost(()->{});restored.read(saved);
            assertEquals(before,restored.observe(limits,true));assertEquals(before,restored.observe(limits,true));
            restored.step(limits,true);host.step(limits,true);assertEquals(host.snapshot(),restored.snapshot());host=restored;
        }
        assertTrue(phases.containsAll(Set.of("GraphState","SortState","CommitState")));
        assertEquals(items().values().size(),keyCalls,"Each key subgraph must be called once, not once per comparison or restore");
        assertEquals(run(archive(false),items(),ROOMY).memory(),host.snapshot().orElseThrow().machine().memory());
    }

    @Test void keyModuleErrorsAndDynamicTypeFailureDoNotCommitMemory() {
        var before=new DataValue.ListValue(List.of());
        for(var source:List.of(new DataValue.ListValue(List.of(obj("wrong",1))),new DataValue.ListValue(List.of(obj("rank","not-number"))))) {
            var result=run(archive(false),source,ROOMY);
            assertEquals(ComputationMachine.Status.FAILED,result.report().status());assertEquals(before,result.memory().field("saved"));
            assertTrue(Set.of(MISSING_FIELD,TYPE_MISMATCH).contains(result.report().diagnostic().orElseThrow().code()));
        }
    }

    @Test void invalidKeyTypesPurityRecursionAndMissingPinnedVersionAreRejected() {
        var invalid=new ComputationGraph(keyGraph().inputs(),Map.of("key",new ComputationGraph.Constant(empty())),Map.of("key","key"));
        assertEquals(TYPE_MISMATCH,assertThrows(ComputationException.class,()->archive(graph(false),invalid).compile(ROOMY)).code());
        var impure=new ComputationGraph(keyGraph().inputs(),Map.of("key",new ComputationGraph.ReadMemory("saved")),Map.of("key","key"));
        assertEquals(INVALID_GRAPH,assertThrows(ComputationException.class,()->archive(graph(false),impure).compile(ROOMY)).code());
        var base=archive(false);
        assertEquals(UNKNOWN_MODULE,assertThrows(ComputationException.class,()->new ProgramArchive(base.program(),Map.of()).compile(ROOMY)).code());
        var recursive=new ComputationGraph(keyGraph().inputs(),Map.of("item",new ComputationGraph.Input("item"),"offset",new ComputationGraph.Input("offset"),
            "key",new ComputationGraph.Call(KEY,Map.of("item","item","offset","offset"))),Map.of("key","key"));
        assertEquals(RECURSIVE_MODULE,assertThrows(ComputationException.class,()->archive(graph(false),recursive).compile(ROOMY)).code());
    }

    @Test void pausedSingleStepAndMemoryLimitKeepOldStateUntilSuccessfulEventCompletion() {
        var installed=archive(false).compile(ROOMY);var machine=new ComputationMachine(installed);machine.begin(input(items()));machine.setPaused(true);
        assertEquals(0,machine.tick().workUnits());assertEquals(1,machine.step().workUnits());assertEquals(new DataValue.ListValue(List.of()),machine.memory().field("saved"));
        var full=run(archive(false),items(),ROOMY);
        var small=new ResourceLimits(ROOMY.programUnits(),full.report().peakMemoryUnits()-1,1);
        var failed=run(archive(false),items(),small);
        assertEquals(MEMORY_LIMIT,failed.report().diagnostic().orElseThrow().code());assertEquals(new DataValue.ListValue(List.of()),failed.memory().field("saved"));
    }

    @Test void programArchiveNineRoundTripsAndOldGraphVersionsRejectSort() {
        var program=archive(false);var encoded=ProgramArchiveCodec.encode(program);
        assertEquals(integer(12),encoded.field("version"));assertEquals(program,ProgramArchiveCodec.decode(encoded));
        var graph=GraphArchiveCodec.encode(program.program().graph());
        for(int v=1;v<9;v++) {
            int version=v;
            var error=assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(graph,version));
            assertEquals("Sort nodes are unavailable in this archive schema",error.getMessage());
        }
    }

    @Test void editorPortRebindingAndDocumentDependencyClosureRetainSortSemantics() {
        var program=archive(false);var sort=program.program().graph().nodes().get("sorted");
        assertEquals(Map.of("source","items","capture:offset","offset"),NodePorts.bindings(sort));
        var rebound=NodePorts.bind(sort,Map.of("source","changed","capture:offset","newOffset"));
        assertEquals(List.of("changed","newOffset"),rebound.dependencies());
        var root=new ModuleDefinition.Key("portable-sort",1);
        var modules=new LinkedHashMap<>(program.modules());modules.put(root,new ModuleDefinition(root,program.program().graph()));
        var document=new SubgraphDocument(root,modules,Map.of(ProgramDocument.graphId(root),Map.of("sorted",new ProgramDocument.Position(42,67))));
        assertEquals(Set.of(root,KEY),document.modules().keySet());
        assertEquals(document,SubgraphDocumentCodec.decode(SubgraphDocumentCodec.encode(document)));
        assertEquals(program,ProgramEditorWire.decode(ProgramEditorWire.encode(program,ARCHIVE),ARCHIVE));
        assertEquals(program,new ProgramDraft(program).snapshot());
    }

    @Test void checkpointCannotUnderreportKeysReturnedDataOrMergeBuffers() {
        var installed=archive(false).compile(ROOMY);var machine=new ComputationMachine(installed);machine.begin(input(items()));
        var phases=new HashSet<String>();
        for(int guard=0;machine.report().status()==ComputationMachine.Status.RUNNING;guard++) {
            assertTrue(guard<200);var cp=machine.checkpoint();
            if(cp.frames().stream().anyMatch(MachineCheckpoint.SortState.class::isInstance)) {
                long live=installed.usage().constantUnits()+installed.usage().initialMemoryUnits()+cp.memory().storageUnits();
                for(var frame:cp.frames()) {
                    if(frame instanceof MachineCheckpoint.GraphState graph) {
                        live+=graph.inputs().storageUnits();for(var value:graph.values().fields().values())live+=value.storageUnits();
                    } else if(frame instanceof MachineCheckpoint.SortState sort) {
                        live+=sort.keys().storageUnits();if(sort.returned().isPresent()){live+=sort.returned().orElseThrow().storageUnits();phases.add("return");}
                        if(sort.progress().isPresent()) {
                            var task=SortTask.restore(items(),sort.keys(),false,sort.progress().orElseThrow(),"sort");
                            live+=task.liveUnits();phases.add(((DataValue.TextValue)sort.progress().orElseThrow().field("phase")).value());
                        }
                    } else fail("Unexpected fixture frame");
                }
                var forged=new MachineCheckpoint(cp.memory(),cp.status(),cp.paused(),cp.workUnits(),live-1,cp.output(),cp.diagnostic(),cp.frames());
                assertEquals(INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->ComputationMachine.restore(installed,forged)).code());
            }
            machine.step();
        }
        assertTrue(phases.containsAll(Set.of("return","INITIALIZE","MERGE","EMIT","DONE")));
    }

    @Test void outerComputerVersionRejectsSortFramesIndependentlyOfNestedProgramVersion() {
        var runtime=new ComputerRuntime(UUID.randomUUID(),archive(false),ROOMY);runtime.begin(input(items()));
        while(runtime.snapshot().machine().frames().stream().noneMatch(MachineCheckpoint.SortState.class::isInstance))runtime.step();
        var data=record(ValueNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(),ARCHIVE),ARCHIVE));
        assertEquals(integer(10),data.field("version"));
        assertEquals(runtime.snapshot(),ComputerNbtCodec.decode(ComputerNbtCodec.encode(runtime.snapshot(),ARCHIVE),ARCHIVE));
        for(int version=1;version<9;version++) {
            var fields=new LinkedHashMap<>(data.fields());fields.put("version",integer(version));
            assertEquals("Sort frames are unavailable in this archive schema",assertThrows(ArchiveException.class,
                ()->ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),ARCHIVE),ARCHIVE)).getMessage());
            var machine=new LinkedHashMap<>(record(fields.get("machine")).fields());
            var frames=new ArrayList<>(list(machine.get("frames")));frames.removeLast();machine.put("frames",new DataValue.ListValue(frames));
            fields.put("machine",new DataValue.RecordValue(machine));
            assertDoesNotThrow(()->ComputerNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),ARCHIVE),ARCHIVE));
        }
    }

    @Test void oldProgramVersionsCannotSmuggleSortInModulesOrEventEntries() {
        var sort=GraphArchiveCodec.encode(graph(false));
        var plain=GraphArchiveCodec.encode(new ComputationGraph(Map.of(),Map.of("one",new ComputationGraph.Constant(integer(1))),Map.of("one","one")));
        var nil=new DataValue.ListValue(List.of());
        for(int version=1;version<9;version++) {
            var legal=obj("version",version,"graph",plain,"cells",empty(),"writes",nil,"sends",nil,"entries",empty(),"modules",nil);
            assertDoesNotThrow(()->ProgramArchiveCodec.decode(legal));
            for(String position:List.of("graph","modules","entries")) {
                if(position.equals("entries")&&version<3)continue;
                var fields=new LinkedHashMap<>(legal.fields());
                fields.put(position,switch(position) {
                    case "graph"->sort;
                    case "modules"->new DataValue.ListValue(List.of(obj("key",GraphArchiveCodec.key(KEY),"graph",sort)));
                    default->obj("event",obj("graph",sort,"writes",nil,"sends",nil));
                });
                assertEquals("Sort nodes are unavailable in this archive schema",assertThrows(ArchiveException.class,
                    ()->ProgramArchiveCodec.decode(new DataValue.RecordValue(fields))).getMessage());
            }
        }
    }

    @Test void dynamicUnsortableOrMixedKeysAbortOutgoingEffectsAsWellAsMemory() {
        var root=graph(false);var nodes=new LinkedHashMap<>(root.nodes());
        nodes.put("to",new ComputationGraph.Constant(new DataValue.TextValue("receiver")));
        nodes.put("payload",new ComputationGraph.MakeRecord(Map.of("result","sorted")));
        root=new ComputationGraph(root.inputs(),nodes,Map.of("result","sorted","to","to","payload","payload"));
        var program=new ProgramDefinition(root,archive(false).program().cells(),archive(false).program().writes(),List.of(new ProgramDefinition.Send("to","payload")));
        var key=new ComputationGraph(keyGraph().inputs(),Map.of("item",new ComputationGraph.Input("item"),"key",new ComputationGraph.Field("item","rank")),Map.of("key","key"));
        var archive=new ProgramArchive(program,Map.of(KEY,new ModuleDefinition(KEY,key)));
        var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY,ARCHIVE));
        for(var keys:List.of(List.<DataValue>of(DataValue.NullValue.INSTANCE),List.<DataValue>of(new DataValue.ErrorValue("x","x")),
            List.<DataValue>of(integer(1),new DataValue.TextValue("1")))) {
            var source=new DataValue.ListValue(keys.stream().map(value->(DataValue)obj("rank",value)).toList());
            var host=new ComputerBlockHost(()->{});host.install(archive,ComputerRuntime.MemoryPolicy.RESET,limits,true);host.begin(input(source),limits,true);
            for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<200);host.step(limits,true);}
            assertEquals(TYPE_MISMATCH,host.snapshot().orElseThrow().machine().diagnostic().orElseThrow().code());
            assertEquals(new DataValue.ListValue(List.of()),host.snapshot().orElseThrow().machine().memory().field("saved"));
            assertTrue(host.outbound(limits,true).isEmpty());
        }
    }

    @Test void sortingInsideReusableModuleRetainsStructuralOutputTypeAndRestoresNestedStack() {
        var type=new DataType.ListType(new DataType.RecordType(Map.of("name",TEXT,"rank",INTEGER)));
        var sortGraph=new ComputationGraph(Map.of("items",type),graph(false).nodes(),graph(false).outputs());
        var wrapper=new ModuleDefinition.Key("reusable_sort",1);
        var outer=new ComputationGraph(Map.of("items",type),Map.of("items",new ComputationGraph.Input("items"),
            "call",new ComputationGraph.Call(wrapper,Map.of("items","items")),"result",new ComputationGraph.Field("call","result")),Map.of("result","result"));
        var modules=new LinkedHashMap<>(archive(false).modules());modules.put(wrapper,new ModuleDefinition(wrapper,sortGraph));
        var program=new ProgramArchive(new ProgramDefinition(outer,Map.of(),List.of()),modules);var installed=program.compile(ROOMY);
        assertEquals(type,installed.root().outputType().fields().get("result"));
        var machine=new ComputationMachine(installed);machine.begin(input(items()));boolean nested=false;
        for(int guard=0;machine.report().status()==ComputationMachine.Status.RUNNING;guard++) {
            assertTrue(guard<200);var state=machine.checkpoint();
            nested|=state.frames().size()>=4;
            machine=ComputationMachine.restore(installed,state);machine.step();
        }
        assertTrue(nested);assertEquals(ComputationMachine.Status.SUCCEEDED,machine.report().status());
        assertEquals(run(archive(false),items(),ROOMY).report().output(),machine.report().output());
    }
}
