package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
class FlowHeldKeyTest {
    static final DataValue.RecordValue EMPTY=new DataValue.RecordValue(Map.of());
    static final ComputerHostLimits LIMITS=new ComputerHostLimits(new ResourceLimits(4096,64000,1),FlowArchiveTest.ARCHIVE,
        MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,3);
    static FlowProgram program(FlowProgram.Compilation policy) {
        var key=new ModuleDefinition.Key("held_key",1);
        return new FlowProgram(Map.of(),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of("held","answer"))),
            new FlowProgram.Procedure("method",key,Map.of(),Map.of("held",DataType.Scalar.BOOLEAN),Map.of("held","held"),FlowBlockRules.NONE,
                Optional.of(new AssemblySource("REG held BOOLEAN\nTERMINAL.KEY.DOWN held space\nRET"))),new FlowProgram.EndProcedure("end")),
            new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of()),Map.of()),FlowProgram.Execution.REGISTER_V1,policy);
    }
    static ComputerBlockHost installed(FlowProgram.Compilation policy) {
        var owner=new ComputerBlockHost(()->{});owner.installFlow(program(policy),EMPTY,LIMITS,true);owner.setPaused(false,LIMITS,true);return owner;
    }
    static void finish(ComputerBlockHost owner,UUID input) {
        for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<40,()->owner.flowSnapshot().toString());
            if(input!=null)owner.updateTerminalKeys(input,TerminalKey.bit("space"));
            long before=owner.flowSnapshot().orElseThrow().state().work();owner.tick(LIMITS,true);
            assertTrue(owner.flowSnapshot().orElseThrow().state().work()-before<=1);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void typedInstructionIsPaidAndKeyboardIsNotPersisted(FlowProgram.Compilation policy) {
        var owner=installed(policy);var source=UUID.randomUUID();owner.updateTerminalKeys(source,TerminalKey.bit("space"));
        var saved=owner.write();var restored=new ComputerBlockHost(()->{});restored.read(saved);restored.tick(LIMITS,true);
        restored.setPaused(false,LIMITS,true);finish(restored,null);
        assertEquals(new DataValue.BooleanValue(false),restored.flowSnapshot().orElseThrow().state().locals().field("answer"));
        finish(owner,source);assertEquals(new DataValue.BooleanValue(true),owner.flowSnapshot().orElseThrow().state().locals().field("answer"));
        assertEquals(owner.flowSnapshot().orElseThrow().state().work(),restored.flowSnapshot().orElseThrow().state().work());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void inlineReporterCompilesToSamePaidInstructionAndRoundTrips(FlowProgram.Compilation policy) {
        var graph=new ComputationGraph(Map.of(),Map.of("key",new ComputationGraph.KeyDown("space")),Map.of("held","key"));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),
            Map.of("query",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var p=new FlowProgram(Map.of(),List.of(new FlowProgram.Evaluate("read","query")),archive,FlowProgram.Execution.REGISTER_V1,policy);
        var doc=new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,EMPTY)));
        assertEquals(doc,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc)));
        var owner=new ComputerBlockHost(()->{});owner.installFlow(p,EMPTY,LIMITS,true);owner.setPaused(false,LIMITS,true);
        finish(owner,UUID.randomUUID());assertEquals(new DataValue.BooleanValue(true),owner.flowSnapshot().orElseThrow().state().locals().field("held"));
    }
    @org.junit.jupiter.api.Test void olderAssemblyCanOpenButCannotUseTheV21KeyOpcode() {
        assertFalse(AssemblySource.supported(21));
        assertThrows(IllegalArgumentException.class,()->new AssemblySource(21,"KEY.READ result space"));
        var graph=new ComputationGraph(Map.of(),Map.of("key",new ComputationGraph.KeyDown("space")),Map.of("held","key"));
        var encoded=com.nobodiiiii.createbiotech.content.biocomputing.persistence.GraphArchiveCodec.encode(graph);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            ()->com.nobodiiiii.createbiotech.content.biocomputing.persistence.GraphArchiveCodec.decode(encoded,26));
    }
    @org.junit.jupiter.api.Test void pauseAndUnloadClearSamplesAndForeignReleaseCannotClearOwner() {
        for(String action:List.of("pause","unload","foreign")) {
            var owner=installed(FlowProgram.Compilation.ORIGINAL);var source=UUID.randomUUID();owner.updateTerminalKeys(source,TerminalKey.bit("space"));
            if(action.equals("pause")){owner.setPaused(true,LIMITS,true);owner.setPaused(false,LIMITS,true);}
            if(action.equals("unload")){owner.unload();owner.tick(LIMITS,true);owner.setPaused(false,LIMITS,true);}
            if(action.equals("foreign"))owner.updateTerminalKeys(UUID.randomUUID(),0);
            finish(owner,null);assertEquals(new DataValue.BooleanValue(action.equals("foreign")),owner.flowSnapshot().orElseThrow().state().locals().field("answer"));
        }
    }
}
