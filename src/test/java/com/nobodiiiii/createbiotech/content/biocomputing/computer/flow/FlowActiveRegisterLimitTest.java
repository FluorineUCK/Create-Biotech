package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowActiveRegisterLimitTest {
    @Test void onlyOccupiedSlotsOfTheActiveFrameCountAndOverflowDoesNotPublish() {
        var src = new StringBuilder();
        for(int i=0;i<40;i++)src.append("REG r").append(i).append(" INTEGER\n");
        src.append("REG answer INTEGER\n");
        for(int i=0;i<9;i++)src.append("CONST r").append(i).append(" ").append(i).append('\n');
        src.append("MOV answer r8\nRET");
        var program=FlowAuthoredAssemblyTest.program(src.toString());
        var values=new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(7)));
        var high=new ResourceLimits(2000,100000,1,20);
        var run=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),program,values,high);
        boolean saved=false;
        for(int i=0;i<100 && run.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++) {
            var before=run.checkpoint();
            run.advance(1);
            assertNotEquals(FlowRuntime.Status.FAILED,run.checkpoint().status(),()->"Even the largest live frame in this test should fit 20 slots");
            var regs=run.checkpoint().assembly().orElseThrow();
            int old=before.assembly().orElseThrow().registers().size();
            if(!saved && old>=4 && regs.registers().size()>old && !regs.calls().orElseThrow().frames().isEmpty()) {
                assertFalse(regs.calls().orElseThrow().frames().getFirst().registers().isEmpty());
                var tightLimit=new ResourceLimits(2000,100000,1,old);
                var cp=before;
                var oversize=run.checkpoint();
                assertThrows(ComputationException.class,()->FlowRuntime.restore(program,oversize,tightLimit));
                var tight=FlowRuntime.restore(program,cp,tightLimit);
                tight.load(); tight.advance(1);
                assertEquals(FlowRuntime.Status.FAILED,tight.checkpoint().status());
                assertEquals(cp.assembly().orElseThrow().registers(),tight.checkpoint().assembly().orElseThrow().registers());
                assertTrue(tight.checkpoint().diagnostic().orElseThrow().message().contains("registers"));
                run=FlowRuntime.restore(program,cp,high); run.load();
                saved=true;
            }
        }
        assertTrue(saved); assertEquals(FlowRuntime.Status.COMPLETE,run.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(8),run.checkpoint().locals().field("answer"));
    }
}
