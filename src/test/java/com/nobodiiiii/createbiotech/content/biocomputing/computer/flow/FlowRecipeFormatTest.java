package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowRecipeFormatTest {
    static final ComputerHostLimits LIMITS=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(512,65536,1),FoundationRecipeTest.LIMITS));
    static final DataType.RecordType OUTCOME=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN,"value",new DataType.OptionalType(DataType.Semantic.RECIPE)));
    static final String CHECK="""
        TYPE R FORMAT recipe 1
        TYPE maybe OPTIONAL R
        TYPE outcome RECORD ok BOOLEAN value maybe
        REG checked outcome
        FMT.CHECK checked reading recipe 1
        """;
    static FlowProgram program(String source, boolean project){
        var key=new ModuleDefinition.Key("fmt_recipe",1);
        var outputs=project?Map.<String,DataType>of("checked",OUTCOME,"projected",DataType.Scalar.ANY):Map.<String,DataType>of("checked",OUTCOME);
        return new FlowProgram(Map.of("reading",DataType.Scalar.ANY),List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("reading","reading"),project?Map.of("checked","checked","projected","projected"):Map.of("checked","checked"))),
            new FlowProgram.Procedure("definition",key,Map.of("reading",DataType.Scalar.ANY),outputs,project?Map.of("checked","checked","projected","projected"):Map.of("checked","checked"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    static FlowProgram roundtrip(){return program(CHECK+"""
        REG nominal maybe
        REG projected ANY
        FIELD nominal checked value
        FMT.PROJECT projected nominal recipe 1
        """,true);}
    @Test void publicAuthoredCallPacksGenuineRecipeAndProjectsImmutableRawData(){
        var raw=FoundationRecipeTest.projection(false);
        var host=new ComputerBlockHost(()->{});
        host.installFlow(roundtrip(),new DataValue.RecordValue(Map.of("reading",raw)),LIMITS,true);
        int steps=0;
        while(host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE){
            assertTrue(++steps<2000);host.step(LIMITS,true);
            assertNotEquals(FlowRuntime.Status.FAILED,host.flowSnapshot().orElseThrow().state().status(),host.flowSnapshot().orElseThrow().state().diagnostic().toString());
        }
        var locals=host.flowSnapshot().orElseThrow().state().locals();
        var checked=(DataValue.RecordValue)locals.field("checked");
        assertEquals(new DataValue.BooleanValue(true),checked.field("ok"));
        assertInstanceOf(DataValue.RecipeValue.class,checked.field("value"));
        assertEquals(raw,locals.field("projected"));assertEquals(raw,locals.field("reading"));
        assertEquals(raw,((DataValue.RecipeValue)checked.field("value")).projection());
        assertTrue(steps>64,"Validation characters and members are actual paid work");
    }
    @Test void everyValidationBoundaryPausesSavesReloadsAndPreservesCallerCostAndAtomicPublication(){
        for(DataValue raw:List.of(FoundationRecipeTest.projection(false),FoundationRecipeTest.projection(true),new DataValue.RecordValue(Map.of()),new DataValue.TextValue("not a recipe"))){
            var host=new ComputerBlockHost(()->{});
            host.installFlow(program(CHECK,false),new DataValue.RecordValue(Map.of("reading",raw)),LIMITS,true);
            int restored=0,steps=0;boolean completedProof=false;
            while(host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE){
                assertTrue(++steps<2000);host.step(LIMITS,true);
                var saved=host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),saved.state().diagnostic().toString());
                if(saved.state().assembly().flatMap(RegisterState::comparison).isEmpty())continue;
                assertEquals(1,saved.state().assembly().orElseThrow().calls().orElseThrow().frames().size());
                assertFalse(saved.state().locals().fields().containsKey("checked"));
                var progress=(DataValue.RecordValue)saved.state().assembly().orElseThrow().comparison().orElseThrow().value().field("progress");
                if(((DataValue.BooleanValue)progress.field("started")).value()&&((DataValue.ListValue)progress.field("frames")).values().isEmpty())completedProof=true;
                var old=host.write();var next=new ComputerBlockHost(()->{});next.read(old);next.tick(LIMITS,true);
                assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(old,next.write());assertEquals(saved,next.flowSnapshot().orElseThrow());
                host=next;restored++;
            }
            assertTrue(restored>=2);assertTrue(completedProof);
            var result=(DataValue.RecordValue)host.flowSnapshot().orElseThrow().state().locals().field("checked");
            boolean valid=raw instanceof DataValue.RecordValue r&&r.fields().containsKey("pattern_id");
            assertEquals(new DataValue.BooleanValue(valid),result.field("ok"));
        }
    }
    @Test void masterQualificationIncludesOpaqueProjectColdRevalidationAndNoNewParserEntitlement(){
        // Roundtrip uses FIELD independently and therefore still requires ISA-1a as well as Master.
        var basic=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),1,true,false);
        var masterOnly=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),1,false,true);
        assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(roundtrip(),basic));
        assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(roundtrip(),masterOnly));
        var onlyCheck=program(CHECK,false);
        // The authored result RECORD (not the nominal format itself) requires parser hardware.
        assertTrue(assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(onlyCheck,masterOnly)).getMessage().contains("type outcome"));
        assertDoesNotThrow(()->FlowPins.validate(onlyCheck,LIMITS));
        var owner=new ComputerBlockHost(()->{});var before=owner.write();
        assertThrows(FlowPins.Unavailable.class,()->owner.installFlow(onlyCheck,new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(false))),basic,true));
        assertEquals(before,owner.write());
        owner.installFlow(onlyCheck,new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(false))),LIMITS,true);owner.step(LIMITS,true);
        var archive=owner.write();var cold=new ComputerBlockHost(()->{});cold.read(archive);cold.tick(basic,true);
        assertFalse(cold.loaded());assertEquals(archive,cold.write());cold.tick(masterOnly,true);assertFalse(cold.loaded());assertEquals(archive,cold.write());cold.tick(LIMITS,true);assertTrue(cold.loaded(),cold.diagnostic().toString());
    }
    @Test void formatCustomBlockCannotBypassEvaluateNestingRestrictions(){
        var base=program(CHECK,false);var steps=new ArrayList<>(base.steps());var p=(FlowProgram.Procedure)steps.get(1);
        steps.set(1,new FlowProgram.Procedure(p.sourceId(),p.key(),p.inputs(),p.outputs(),p.returns(),new FlowBlockRules(Set.of("evaluate"),Set.of()),p.assembly()));
        var host=new ComputerBlockHost(()->{});
        assertThrows(ComputationException.class,()->host.installFlow(new FlowProgram(base.inputs(),steps).withAssembly(),new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(false))),LIMITS,true));
    }
    @Test void childTaskChecksRecipeWhileCallerSleepsAndRestoresPrivateContinuation(){
        var base=program(CHECK,false);
        var steps=new ArrayList<FlowProgram.Step>();steps.add(new FlowProgram.Sleep("sleep","delay"));steps.addAll(base.steps().subList(1,3));
        var flow=new FlowProgram(Map.of("delay",DataType.Scalar.INTEGER),steps).withAssembly();
        var limits=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3,true,true);
        var host=new ComputerBlockHost(()->{});host.installFlow(flow,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(1000))),limits,true);host.step(limits,true);
        var task=host.startFlowTask(new ModuleDefinition.Key("fmt_recipe",1),new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(true))),limits,true);
        int stepsTaken=0,restored=0;
        while(host.flowTask(task).orElseThrow().status()!=FlowRuntime.Status.COMPLETE){
            assertTrue(++stepsTaken<2000);host.step(limits,true);
            assertNotEquals(FlowRuntime.Status.FAILED,host.flowTask(task).orElseThrow().status(),host.flowTask(task).orElseThrow().diagnostic().toString());
            if(host.flowTask(task).orElseThrow().assembly().flatMap(RegisterState::comparison).isPresent()&&stepsTaken%23==0){
                var old=host.write();var next=new ComputerBlockHost(()->{});next.read(old);next.tick(limits,true);
                assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(old,next.write());host=next;restored++;
            }
        }
        assertTrue(restored>2);assertEquals(FlowRuntime.Status.SLEEPING,host.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.BooleanValue(true),((DataValue.RecordValue)host.flowTask(task).orElseThrow().locals().field("checked")).field("ok"));
    }
    @Test void v5PublicDocumentRoundtripKeepsExplicitVersionAndOldSourcesStayPinned(){
        var flow=roundtrip();
        var document=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument(flow.expressions(),Map.of(),Optional.empty(),
            Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.fromProgram(flow,new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(false))))));
        var encoded=com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(document);
        var decoded=com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(encoded);
        assertEquals(document,decoded);
        assertEquals(AssemblySource.VERSION,((FlowProgram.Procedure)decoded.flow().orElseThrow().program(decoded.archive()).steps().get(1)).assembly().orElseThrow().version());
        assertEquals(AssemblySource.VERSION,new AssemblySource("REG x INTEGER").version());
    }
    @Test void formatCursorEnvelopeVersionCannotBeForgedOrDowngraded(){
        var flow=program(CHECK,false);var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),flow,
            new DataValue.RecordValue(Map.of("reading",FoundationRecipeTest.projection(false))),LIMITS.hardware());
        while(runtime.checkpoint().assembly().orElseThrow().comparison().isEmpty())runtime.advance(1);
        var encoded=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(flow,runtime.checkpoint()),LIMITS.archive());
        var root=(DataValue.RecordValue)ValueNbtCodec.decode(encoded,LIMITS.archive());
        var state=(DataValue.RecordValue)root.field("state");var assembly=(DataValue.RecordValue)state.field("assembly");
        var cursor=(DataValue.RecordValue)assembly.field("comparison");
        assertEquals(new DataValue.IntegerValue(4),cursor.field("version"));
        for(int version:List.of(1,2,3,5)){
            var corrupted=patch(root,"state",patch(state,"assembly",patch(assembly,"comparison",patch(cursor,"version",new DataValue.IntegerValue(version)))));
            assertThrows(RuntimeException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(corrupted,LIMITS.archive()),LIMITS.archive()));
        }
    }
    @Test void projectOnlyUsesItsOwnOrdinaryContinuationWithoutInventingComparisonCapability(){
        var key=new ModuleDefinition.Key("project_only",1);
        var flow=new FlowProgram(Map.of("reading",DataType.Scalar.ANY),List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("reading","reading"),Map.of("out","projected"))),
            new FlowProgram.Procedure("definition",key,Map.of("reading",DataType.Scalar.ANY),Map.of("out",DataType.Scalar.ANY),Map.of("out","out"),
                FlowBlockRules.NONE,Optional.of(new AssemblySource("REG out ANY\nFMT.PROJECT out reading recipe 1"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var master=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),1,false,true);
        var raw=FoundationRecipeTest.projection(false);
        var owner=new ComputerBlockHost(()->{});owner.installFlow(flow,new DataValue.RecordValue(Map.of("reading",new DataValue.RecipeValue("recipe",1,raw))),master,true);
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){
            assertTrue(i<100);var archived=owner.write();var next=new ComputerBlockHost(()->{});next.read(archived);next.tick(master,true);
            assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(archived,next.write());owner=next;owner.step(master,true);
        }
        assertEquals(raw,owner.flowSnapshot().orElseThrow().state().locals().field("projected"));
        assertFalse(FlowXBus.image(flow).instructionFamilies().contains(InstructionFamily.ISA_1A));
    }
    static DataValue.RecordValue patch(DataValue.RecordValue value,String field,DataValue replacement){
        var fields=new LinkedHashMap<>(value.fields());fields.put(field,replacement);return new DataValue.RecordValue(fields);
    }
}
