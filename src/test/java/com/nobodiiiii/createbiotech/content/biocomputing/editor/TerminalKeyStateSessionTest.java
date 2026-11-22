package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
class TerminalKeyStateSessionTest {
    static final DataValue.RecordValue EMPTY=new DataValue.RecordValue(Map.of());
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"valid","view","revision","consumed","foreign_release","replay","release_after_consume"})
    void samplesRequireCurrentObservationAndCannotConsumeOrForgeOtherMenusState(String scenario) {
        var harness=new TerminalBehaviourTest.Harness();var computer=harness.getBehaviour(ComputerBehaviour.TYPE);
        var graph=new ComputationGraph(Map.of(),Map.of("key",new ComputationGraph.KeyDown("space")),Map.of("held","key"));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("query",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var p=new FlowProgram(Map.of(),List.of(new FlowProgram.Evaluate("read","query")),archive).withAssembly();
        computer.installDocument(new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,EMPTY))),ComputerRuntime.MemoryPolicy.RESET);
        computer.setPaused(false);var nonce=UUID.randomUUID();var session=new ComputerControlSession(nonce);long revision=computer.programRevision();
        session.apply(nonce,1,revision,ComputerControlSession.Action.READ_TERMINAL,()->EMPTY,computer).orElseThrow();
        var before=computer.flowSnapshot().orElseThrow();
        if(scenario.equals("consumed")) session.apply(nonce,2,revision,ComputerControlSession.Action.TERMINAL_KEY,()->new TerminalKeyPress(1,"a").encode(),computer);
        session.updateKeyState(1,scenario.equals("revision")?revision-1:revision,sample(scenario.equals("view")?2:1,TerminalKey.bit("space")),computer);
        if(scenario.equals("foreign_release"))new ComputerControlSession(UUID.randomUUID()).updateKeyState(1,revision,sample(0,0),computer);
        if(scenario.equals("replay")){session.updateKeyState(2,revision,sample(1,0),computer);session.updateKeyState(1,revision,sample(1,TerminalKey.bit("space")),computer);}
        if(scenario.equals("release_after_consume")){session.invalidateTerminalPublication();session.updateKeyState(2,revision,sample(1,0),computer);}
        assertEquals(before,computer.flowSnapshot().orElseThrow(),"Physical samples are not program checkpoints or instructions");
        for(int i=0;computer.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<20);computer.tick();}
        assertEquals(new DataValue.BooleanValue(Set.of("valid","foreign_release").contains(scenario)),computer.flowSnapshot().orElseThrow().state().locals().field("held"));
    }
    @org.junit.jupiter.api.Test void missedReleaseExpiresAndInvalidBitsDoNotRenewLease() {
        var keyboard=new TerminalKeyboard();var source=UUID.randomUUID();keyboard.update(source,TerminalKey.bit("a"));
        assertThrows(IllegalArgumentException.class,()->keyboard.update(source,Long.MIN_VALUE));
        for(int i=0;i<TerminalKeyboard.REFRESH_TICKS*3-1;i++)keyboard.tick();assertNotEquals(0,keyboard.sample());
        keyboard.tick();assertEquals(0,keyboard.sample());
    }
    static DataValue.RecordValue sample(long view,long bits){return new DataValue.RecordValue(Map.of("view",new DataValue.IntegerValue(view),"bits",new DataValue.IntegerValue(bits)));}
}
