package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;

class FlowTerminalKeyTest {
    private static final DataValue.RecordValue EMPTY=new DataValue.RecordValue(Map.of());
    private static FlowInput value(long n){return new FlowInput.Literal(new DataValue.IntegerValue(n));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void keyTaskSleepsAndRestoresWithoutReplayingOrChangingMain(FlowProgram.Compilation policy){
        var key=new ModuleDefinition.Key("key_task",1);
        var cells=Map.of("count",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0)));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),cells,List.of()),Map.of());
        var program=new FlowProgram(Map.of(),List.of(new FlowProgram.Sleep("main",value(500)),
            FlowProgram.Procedure.keyHat("key",key,"a"),new FlowProgram.Shared("count","count",value(1),true),
            new FlowProgram.Sleep("wait",value(40)),new FlowProgram.EndProcedure("end")),archive,FlowProgram.Execution.REGISTER_V1,policy);
        var document=new ProgramDocument(program.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(program,EMPTY)));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(4096,64000,1),FlowArchiveTest.ARCHIVE,
            MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,3));
        var owner=new ComputerBlockHost(()->{});owner.installFlow(program,EMPTY,limits,true);
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        owner.setPaused(false,limits,true);owner.tick(limits,true);
        var main=owner.flowSnapshot().orElseThrow().state();
        assertEquals("ok",owner.pressTerminalKey("a",limits,true));assertEquals(main,owner.flowSnapshot().orElseThrow().state());
        for(int i=0;i<16;i++)owner.tick(limits,true);
        assertEquals(new DataValue.IntegerValue(1),owner.flowSnapshot().orElseThrow().memory().cells().field("count"));
        assertEquals(FlowRuntime.Status.SLEEPING,owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status());
        owner.setPaused(true,limits,true);var saved=owner.write();
        var restored=new ComputerBlockHost(()->{});restored.read(saved);restored.tick(limits,true);
        assertEquals(owner.flowSnapshot(),restored.flowSnapshot(),()->restored.diagnostic().toString());
        restored.setPaused(false,limits,true);assertEquals("already_running",restored.pressTerminalKey("a",limits,true));
        for(int i=0;i<80;i++)restored.tick(limits,true);
        assertTrue(restored.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        assertEquals(new DataValue.IntegerValue(1),restored.flowSnapshot().orElseThrow().memory().cells().field("count"));
        assertEquals("ok",restored.pressTerminalKey("a",limits,true));
        for(int i=0;i<16;i++)restored.tick(limits,true);
        assertEquals(new DataValue.IntegerValue(2),restored.flowSnapshot().orElseThrow().memory().cells().field("count"));
    }
}
