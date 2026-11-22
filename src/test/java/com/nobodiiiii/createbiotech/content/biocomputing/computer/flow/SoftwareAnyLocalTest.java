package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class SoftwareAnyLocalTest {
    static final Map<String, DataType> OUT = Map.of("integer", DataType.Scalar.INTEGER, "boolean", DataType.Scalar.BOOLEAN);
    static final Map<String, String> RETURNS = Map.of("integer", "number", "boolean", "flag");
    static final String SOURCE = """
        REG value ANY INTEGER BOOLEAN
        REG number INTEGER
        REG flag BOOLEAN
        REG payload INTEGER
        REG truth BOOLEAN
        REG one INTEGER
        REG limit INTEGER
        CONST number -1
        CONST flag false
        CONST payload 40
        CONST truth true
        CONST one 1
        CONST limit 42
        ANY.GET number value unset
        RET
        unset:
        ANY.SET value payload
        again:
        ANY.GET number value done
        ADD_INTEGER payload payload one
        ANY.SET value payload
        ANY.IS flag value INTEGER
        JFALSE flag failed
        SLEEP one
        EQUAL_INTEGER flag payload limit
        JFALSE flag again
        ANY.SET value truth
        ANY.GET number value done
        JMP failed
        done:
        ANY.GET flag value failed
        RET
        failed:
        CONST flag false
        RET""";
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);
    static final ComputerHostLimits LIMITS = new ComputerHostLimits(new ResourceLimits(4096, 100000, 1), ARCHIVE);
    static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("software_local", 1);
    static FlowProgram program(AssemblySource source) {
        return new FlowProgram(Map.of(), List.of(
            new FlowProgram.Invoke("call", KEY, new FlowCallBindings(Map.of(), Map.of("integer", "caller_number", "boolean", "caller_flag"))),
            new FlowProgram.Procedure("body", KEY, Map.of(), OUT, RETURNS, FlowBlockRules.NONE, Optional.of(source)),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    @Test void closedSoftwareLocalChangesVariantAndBranchesWithoutAdvancedIsa() {
        var source = new AssemblySource(SOURCE);
        var code = ProcedureAssembler.assemble(source, Map.of(), OUT, RETURNS, 65536, 4096).program();
        assertFalse(code.instructionFamilies().contains(InstructionFamily.ISA_1A));
        assertFalse(code.instructionFamilies().contains(InstructionFamily.ISA_1B));
        assertFalse(code.registerTypes().contains(DataType.Scalar.ANY));
        var done = run(program(source), LIMITS, false);
        assertEquals(new DataValue.IntegerValue(41), done.locals().field("caller_number"));
        assertEquals(new DataValue.BooleanValue(true), done.locals().field("caller_flag"));
        var continued = run(program(source), LIMITS, true);
        assertEquals(done.locals(), continued.locals());
        assertEquals(done.work(), continued.work());

        var concrete = program(new AssemblySource("REG number INTEGER\nREG flag BOOLEAN\nCONST number 41\nCONST flag true\nRET"));
        var direct = run(concrete, LIMITS, false);
        assertEquals(done.locals(), direct.locals());
        assertTrue(done.work() > direct.work());
        var usage = program(source).usage(LIMITS.hardware());
        assertTrue(usage.programUnits() > concrete.usage(LIMITS.hardware()).programUnits());
        assertTrue(usage.registers() > concrete.usage(LIMITS.hardware()).registers());
        assertEquals(usage.programUnits(), program(source).usage(new ResourceLimits(usage.programUnits(),100000,1)).programUnits());
        var codeFailure = assertThrows(ComputationException.class, () -> program(source).validate(new ResourceLimits(usage.programUnits()-1,100000,1)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, codeFailure.code());
        long low = 0, high = LIMITS.hardware().memoryUnits();
        while (low + 1 < high) {
            long middle = (high + low) / 2;
            if (fits(program(source), usage.programUnits(), middle)) high = middle; else low = middle;
        }
        assertTrue(fits(program(source), usage.programUnits(), high));
        assertFalse(fits(program(source), usage.programUnits(), high-1));
        System.out.printf("software code=%d constants=%d registers=%d work=%d RAM=%d; concrete code=%d work=%d%n",
            usage.programUnits(),usage.constantUnits(),usage.registers(),done.work(),high,concrete.usage(LIMITS.hardware()).programUnits(),direct.work());
    }

    @Test void nativeMoveOnlyReferenceRetainsItsHardwareGateAndOrdinaryCosts() {
        // This reference writes the same two payloads but has no unset/mismatch checks or loop.
        var nativeSource = new AssemblySource("""
            REG value ANY
            REG number INTEGER
            REG flag BOOLEAN
            CONST number 41
            CONST flag true
            MOV value number
            MOV value flag
            RET""");
        var nativeProgram = program(nativeSource);
        var denied = assertThrows(IllegalArgumentException.class, () -> run(nativeProgram, LIMITS, false));
        assertTrue(denied.getMessage().contains("ISA-1b"),denied.getMessage());
        var master = new ComputerHostLimits(LIMITS.hardware(),ARCHIVE,LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),LIMITS.taskSlots(),false,true);
        var done = run(nativeProgram,master,false);
        assertEquals(Map.of("caller_number",new DataValue.IntegerValue(41),"caller_flag",new DataValue.BooleanValue(true)),done.locals().fields());
        var nativeUsage = nativeProgram.usage(LIMITS.hardware());
        assertTrue(nativeUsage.programUnits() < program(new AssemblySource(SOURCE)).usage(LIMITS.hardware()).programUnits());
        System.out.printf("native move-only code=%d constants=%d registers=%d work=%d%n",
            nativeUsage.programUnits(),nativeUsage.constantUnits(),nativeUsage.registers(),done.work());
    }

    private static boolean fits(FlowProgram program, long words, long memory) {
        try { run(program, new ComputerHostLimits(new ResourceLimits(words,memory,1), ARCHIVE), false); return true; }
        catch (ComputationException e) { assertEquals(ComputationException.Code.MEMORY_LIMIT, e.code()); return false; }
    }

    @Test void badVariantsAndOperandsPointToOriginalSourceAndNativeAnyStillNeedsIsa1b() {
        for (String line : List.of("REG value ANY INTEGER INTEGER", "REG value ANY TEXT", "REG __any_0 INTEGER",
            "ANY.IS number value INTEGER", "ANY.GET number value absent", "MOV number value", "ANY.SET value value")) {
            String prefix = line.startsWith("REG") ? "REG number INTEGER\n" : "REG value ANY INTEGER BOOLEAN\nREG number INTEGER\nCONST number 0\n";
            var failure = assertThrows(ComputationException.class, () -> ProcedureAssembler.assemble(
                new AssemblySource(prefix+line),Map.of(),Map.of(),Map.of(),65536,4096));
            assertEquals(line.startsWith("REG")?"assembly/line 2":"assembly/line 4",failure.location(),failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new AssemblySource(7,"REG value ANY INTEGER BOOLEAN"));
        var dynamicSignature = assertThrows(ComputationException.class, () -> ProcedureAssembler.assemble(
            new AssemblySource("REG value ANY INTEGER BOOLEAN"),Map.of("arg",DataType.Scalar.ANY),Map.of(),Map.of(),65536,4096));
        assertEquals("assembly/line 1",dynamicSignature.location());
        for (int version : List.of(AssemblySource.VERSION)) {
            var image = ProcedureAssembler.assemble(new AssemblySource(version,"REG value ANY"),Map.of(),Map.of(),Map.of(),65536,4096).program();
            assertTrue(image.instructionFamilies().contains(InstructionFamily.ISA_1B));
        }
    }

    @Test void expandedBodyStillReportsDeviceEffectsAfterMacroPrefix() {
        var source = new AssemblySource("REG value ANY INTEGER BOOLEAN\nREG n INTEGER\nCONST n 0\nANY.SET value n\nANY.SET value n\nSIO.READ n p0\nRET");
        var declaration = new FlowProgram.Procedure("body", KEY, Map.of(), Map.of(), Map.of(),
            new FlowBlockRules(Set.of("signal_read"),Set.of()), Optional.of(source));
        var p = new FlowProgram(Map.of(), List.of(declaration,new FlowProgram.EndProcedure("end"))).withAssembly();
        var e = assertThrows(ComputationException.class, () -> p.validate(LIMITS.hardware()));
        assertTrue(e.getMessage().contains("Assembly body forbids signal_read"),e.getMessage());
    }

    @Test void faultAfterExtractionDoesNotPublishCallerResults() {
        var p = program(new AssemblySource(SOURCE.replace("ANY.GET flag value failed",
            "ANY.GET flag value failed\nCONST one 0\nDIVIDE_INTEGER number number one")));
        var state = finish(p, LIMITS, true);
        assertEquals(FlowRuntime.Status.FAILED, state.status());
        assertEquals(ComputationException.Code.DIVISION_BY_ZERO, state.diagnostic().orElseThrow().code());
        assertFalse(state.locals().fields().containsKey("caller_number"));
        assertFalse(state.locals().fields().containsKey("caller_flag"));
        assertEquals(new DataValue.IntegerValue(41), state.locals().field("number"));
    }

    private static FlowRuntime.Checkpoint run(FlowProgram program, ComputerHostLimits limits, boolean reload) {
        var state = finish(program, limits, reload);
        if (state.status() == FlowRuntime.Status.FAILED) {
            var error=state.diagnostic().orElseThrow();
            throw new ComputationException(error.code(),error.location(),error.message());
        }
        return state;
    }

    private static FlowRuntime.Checkpoint finish(FlowProgram program, ComputerHostLimits limits, boolean reload) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, EMPTY, limits, true);
        host.setPaused(false, limits, true);
        for (int i = 0; i < 200; i++) {
            host.tick(limits,true);
            var before = host.flowSnapshot().orElseThrow();
            var state = before.state();
            assertTrue(state.locals().fields().keySet().stream().noneMatch(name -> name.startsWith("__any_")));
            if (state.status() == FlowRuntime.Status.COMPLETE || state.status() == FlowRuntime.Status.FAILED) return state;
            if (reload) {
                var restored = new ComputerBlockHost(() -> {});
                restored.read(host.write());
                restored.setPaused(false,limits,true);
                assertEquals(before,restored.flowSnapshot().orElseThrow());
                host=restored;
            }
        }
        throw new AssertionError("program did not finish");
    }
}
