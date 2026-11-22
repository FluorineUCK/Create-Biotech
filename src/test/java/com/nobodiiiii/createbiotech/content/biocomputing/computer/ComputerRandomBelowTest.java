package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerRandomBelowTest {
    @Test void authoredRandomBelowRejectsOldSourceAndMakesOnePaidAttemptAtATime() {
        var integer = Map.<String, DataType>of("bound", DataType.Scalar.INTEGER);
        String source = "REG draw INTEGER\nRAND.BELOW draw bound\nRET";
        var returns = Map.of("bound", "draw");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(12, source),
            integer, integer, returns, 4096, 64)).getMessage().contains("Unsupported assembler version"));
        var image = ProcedureAssembler.assemble(new AssemblySource(source), integer, integer, returns, 4096, 64)
            .program().forTask("local", 1);
        long bound = (1L << 62) + 1;
        var first = image.begin(new DataValue.RecordValue(Map.of("bound", new DataValue.IntegerValue(bound))));
        assertTrue(image.drawsRandom(first));
        var id = new UUID(0, 0);
        assertSame(first, image.tryRandom(first, id, 0), "The zero sample is in the incomplete residue class");
        assertDoesNotThrow(() -> image.validate(first, 1, false), "A paid rejection may be saved inside an authored procedure");
        var next = first;
        for (long work = 1; next == first && work < 64; work++) {
            next = image.tryRandom(first, id, work);
            assertEquals(next, image.tryRandom(first, id, work));
        }
        assertFalse(image.drawsRandom(next));
        var instruction = (RegisterProgram.RandomBelow)image.code().get(first.pc()).instruction();
        long draw = ((DataValue.IntegerValue)next.registers().get(instruction.to())).value();
        assertTrue(draw >= 0 && draw < bound);
    }

    @Test void invalidBoundsDoNotPublishADrawAndNumericPrimitiveDoesNotRequireParser() {
        var integer=Map.<String,DataType>of("bound",DataType.Scalar.INTEGER);
        var image=ProcedureAssembler.assemble(new AssemblySource("REG draw INTEGER\nRAND.BELOW draw bound\nRET"),
            integer,integer,Map.of("bound","draw"),4096,64).program().forTask("local",1);
        assertEquals(InstructionFamily.ISA_1,new RegisterProgram.RandomBelow(0,1).family());
        for(long bound:List.of(0L,-1L,Long.MIN_VALUE)) {
            var state=image.begin(new DataValue.RecordValue(Map.of("bound",new DataValue.IntegerValue(bound))));
            assertEquals(ComputationException.Code.INVALID_ARGUMENT,assertThrows(ComputationException.class,
                ()->image.tryRandom(state,new UUID(0,0),1)).code());
        }
    }
    @Test void authoredCallRetainsItsChoiceAcrossEveryComputerSaveBoundary() {
        var key=new ModuleDefinition.Key("draw",1);var types=Map.<String,DataType>of("bound",DataType.Scalar.INTEGER);
        var program=new FlowProgram(types,List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("bound","bound"),Map.of("result","answer"))),
            new FlowProgram.Procedure("definition",key,types,Map.of("result",DataType.Scalar.INTEGER),Map.of("result","draw"),
                FlowBlockRules.NONE,Optional.of(new AssemblySource("REG draw INTEGER\nRAND.BELOW draw bound\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var limits=ComputerListAppendTest.LIMITS;var host=new ComputerBlockHost(()->{});
        long bound=(1L<<62)+1;
        host.installFlow(program,new DataValue.RecordValue(Map.of("bound",new DataValue.IntegerValue(bound))),limits,true);
        boolean complete=false;
        for(int step=0;step<150;step++) {
            var before=host.flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),before.state().diagnostic().toString());
            if(before.state().status()==FlowRuntime.Status.COMPLETE) {
                long value=((DataValue.IntegerValue)before.state().locals().field("answer")).value();assertTrue(value>=0 && value<bound);
                complete=true;break;
            }
            var restored=new ComputerBlockHost(()->{});restored.read(host.write());restored.tick(limits,true);
            assertTrue(restored.loaded(),restored.diagnostic().toString());assertEquals(before,restored.flowSnapshot().orElseThrow());
            host.step(limits,true);restored.step(limits,true);assertEquals(host.flowSnapshot().orElseThrow(),restored.flowSnapshot().orElseThrow());
            assertEquals(before.state().work()+1,host.flowSnapshot().orElseThrow().state().work());host=restored;
        }
        assertTrue(complete);
    }
}
