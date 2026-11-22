package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowAssemblyCapabilitiesTest {
    private static FlowProgram program(int version, String text) {
        return new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("body", new ModuleDefinition.Key("p", 1), Map.of(), Map.of(), Map.of(),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(version, text))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    @Test void repeatedCapabilityInspectionDoesNotReparseAnInstalledSource() {
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var p = program(AssemblySource.VERSION, "REG v INTEGER\nCONST v 0\n" + "ADD_INTEGER v v v\n".repeat(90)
            + "SERIAL.RECEIVE frame s0\nVALUE.CHECK result v\nREQUIRE_VALUE v optional\nRECORD.MAKE result\nXBUS.WRITE x0 result\nCORRELATION id\n");
        for (int i=0;i<30;i++) inspect(p);
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        for(int i=0;i<1000;i++) assertEquals(7, inspect(p));
        long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
        // Implementation regression bound, not Computer RAM/cycle balance or a wall-clock assertion.
        assertTrue(bytes < 4*1024*1024, "Repeated fixed metadata inspection allocated " + bytes + " bytes");
    }
    private static int inspect(FlowProgram p) {
        int n=0;
        if(p.usesSerialAssembly())n++; if(p.usesReceiveAssembly())n++; if(p.usesValueEqualityAssembly())n++;
        if(p.usesRequiredValueAssembly())n++; if(p.usesConstructionAssembly())n++; if(p.usesXBusAssembly())n++;
        if(FlowProgram.hasCorrelation(p.steps()))n++;
        return n;
    }
    @Test void featuresUseOnlyInstructionHeadsNotLiteralTextCommentsOrLabels() {
        { int version=AssemblySource.VERSION;
            var p=program(version, " ; SERIAL.RECEIVE frame s0\r\nCONST text \"VALUE.CHECK; XBUS.WRITE \\n CORRELATION\"\n"
                + "SERIAL.RECEIVE:\nSERIAL.RECEIVEX a b\nTYPE RECORD.MAKE RECORD field INTEGER\nREG REQUIRE_VALUE INTEGER\n");
            assertEquals(0,inspect(p));
            var changed=program(version,p.steps().stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast)
                .findFirst().orElseThrow().assembly().orElseThrow().text()+"\tSERIAL.RECEIVE frame s0 ; note\n");
            assertTrue(changed.usesReceiveAssembly()); assertFalse(p.usesReceiveAssembly());
        }
    }
    @Test void sourceValueIdentityAndCapabilityFlagsDoNotReinterpretVersionsOrExecution() {
        var a=new AssemblySource("XBUS.READ value x0\n");
        var b=new AssemblySource("XBUS.READ value x0\n");
        assertEquals(a,b); assertEquals(a.hashCode(),b.hashCode()); assertNotEquals(a,new AssemblySource("RET"));
        record PreviousValueShape(int version, String text) {}
        assertEquals(new PreviousValueShape(a.version(),a.text()).hashCode(),a.hashCode());
        assertEquals("AssemblySource[version="+AssemblySource.VERSION+", text="+a.text()+"]",a.toString());
        var p=program(AssemblySource.VERSION,a.text()); assertTrue(p.usesXBusAssembly());
        var legacy=new FlowProgram(p.inputs(),p.steps(),p.expressions()); assertFalse(legacy.usesXBusAssembly());
        assertEquals(a,new AssemblySource(a.version(),a.text()));
    }
    @Test void allAssemblerLineSeparatorsAndSourceVersionsRetainTheSameCapabilityAndWireData() {
        for(int version : List.of(AssemblySource.VERSION)) for(var separator:List.of("\n","\r","\r\n","\u000b","\f","\u0085","\u2028","\u2029")) {
            String text=String.join(separator,"REG text TEXT","CONST text \"SERIAL.RECEIVE; VALUE.CHECK\"",
                "XBUS.READ:\t; label, not a read", "SERIAL.RECEIVEX a b", "VALUE.EQUALS_ABSENT v v", "\tSERIAL.RECEIVE frame s0 ; comment",
                "VALUE.CHECK result v", "REQUIRE_VALUE v optional", "RECORD.MAKE result", "XBUS.WRITE x0 result", "CORRELATION id");
            var p=program(version,text);
            var wire=FlowProgramCodec.encode(p);
            assertEquals(7,inspect(p));
            for(var opcode:List.of("SERIAL.RECEIVE","VALUE.CHECK","REQUIRE_VALUE","RECORD.MAKE","XBUS.WRITE","CORRELATION"))
                assertTrue(FlowProgram.authoredOpcode(p.steps(),java.util.regex.Pattern.quote(opcode)));
            assertEquals(wire,FlowProgramCodec.encode(p)); // derived metadata cannot enter the archive
            var restored=FlowProgramCodec.decode(wire);
            assertEquals(p,restored);
            assertEquals(7,inspect(restored));
            assertEquals(wire,FlowProgramCodec.encode(restored));
        }
    }
    @Test void instructionBoundariesFollowAsciiWhitespaceAndExistingTrimRules() {
        for(var separator:List.of("\u001c","\u00a0","\u2003")) {
            var p=program(AssemblySource.VERSION,"SERIAL.RECEIVE"+separator+"frame s0");
            assertFalse(p.usesReceiveAssembly());
            assertFalse(FlowProgram.authoredOpcode(p.steps(),"SERIAL\\.RECEIVE"));
        }
        for(var prefix:List.of("\u001c"," \t")) {
            var p=program(AssemblySource.VERSION,prefix+"SERIAL.RECEIVE\tframe s0");
            assertTrue(p.usesReceiveAssembly());
            assertTrue(FlowProgram.authoredOpcode(p.steps(),"SERIAL\\.RECEIVE"));
        }
    }
}
