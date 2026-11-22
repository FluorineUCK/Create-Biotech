package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

class Isa1aHardwareTest {
    @Test void declarationsCannotHideParserRequirementsBehindAliasesOrSignatures() {
        for (String declaration : List.of("TYPE R RECORD n INTEGER", "TYPE L LIST INTEGER\nTYPE R RECORD nested L\nREG unused R")) {
            var p=program(declaration+"\nEQUAL_INTEGER out a b", DataType.Scalar.BOOLEAN);
            var error=assertThrows(IllegalArgumentException.class,()->FlowPins.validate(p,hardware("mason").limits()));
            assertTrue(error.getMessage().contains("ISA-1a"));
            FlowPins.validate(p,hardware("cleric").limits());
        }
        var type=new DataType.RecordType(Map.of("nested",new DataType.OptionalType(new DataType.ListType(DataType.Scalar.INTEGER))));
        var key=new ModuleDefinition.Key("identity",1);
        var p=new FlowProgram(Map.of("value",type),List.of(
            new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("v","value"),Map.of("out","answer"))),
            new FlowProgram.Procedure("definition",key,Map.of("v",type),Map.of("out",type),Map.of("out","v"),FlowBlockRules.NONE,Optional.of(new AssemblySource("RET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        assertThrows(IllegalArgumentException.class,()->FlowPins.validate(p,hardware("mason").limits()));
        FlowPins.validate(p,hardware("cleric").limits());
    }
    private static final BiocomputingConfig CONFIG = new BiocomputingConfig(new ModConfigSpec.Builder());
    private static HardwareSelection hardware(String profession) {
        return CONFIG.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
            ResourceLocation.parse("minecraft:" + profession), 5), false);
    }
    private static FlowProgram program(String source, DataType result) {
        var key = new ModuleDefinition.Key("check", 1);
        return new FlowProgram(Map.of("a", DataType.Scalar.INTEGER, "b", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("a", "a", "b", "b"), Map.of("out", "answer"))),
            new FlowProgram.Procedure("definition", key, Map.of("a", DataType.Scalar.INTEGER, "b", DataType.Scalar.INTEGER),
                Map.of("out", result), Map.of("out", "out"), FlowBlockRules.NONE,
                Optional.of(new AssemblySource("REG out BOOLEAN\n" + source))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    private static DataValue.RecordValue inputs() {
        return new DataValue.RecordValue(Map.of("a", new DataValue.IntegerValue(1), "b", new DataValue.IntegerValue(1)));
    }

    @Test void ordinaryMachineCannotInstallAnAdvancedInstructionHiddenInACustomBlock() {
        var host = new ComputerBlockHost(() -> {});
        var before = host.write();
        var error = assertThrows(IllegalArgumentException.class, () -> host.installFlow(
            program("VALUE.EQUALS out a b", DataType.Scalar.BOOLEAN), inputs(), hardware("mason").limits(), true));
        assertTrue(error.getMessage().contains("ISA-1a"), error.getMessage());
        assertEquals(before, host.write(), "Rejected installation must not replace the machine");
    }

    @Test void clericIsTheOnlyDefaultParserAndOrdinaryNumericEqualityRemainsUsable() {
        assertEquals("parser", hardware("cleric").profile());
        for (String profession : List.of("mason", "librarian", "cartographer", "none", "nitwit")) {
            var host = new ComputerBlockHost(() -> {});
            var limits = hardware(profession).limits();
            host.installFlow(program("EQUAL_INTEGER out a b", DataType.Scalar.BOOLEAN), inputs(), limits, true);
            for (int i = 0; i < 50 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) host.step(limits, true);
            assertEquals(new DataValue.BooleanValue(true), host.flowSnapshot().orElseThrow().state().locals().field("answer"));
        }
    }

    @Test void losingTheParserSuspendsWithoutReplayingOrDiscardingTheCheckpoint() {
        var parser = hardware("cleric").limits(); var ordinary = hardware("mason").limits();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program("VALUE.EQUALS out a b", DataType.Scalar.BOOLEAN), inputs(), parser, true);
        host.step(parser, true);
        var checkpoint = host.write();
        host.tick(ordinary, true);
        assertFalse(host.loaded());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, host.diagnostic().orElseThrow().code());
        assertTrue(host.diagnostic().orElseThrow().message().contains("ISA-1a"));
        assertEquals(checkpoint, host.write());
        var restored = new ComputerBlockHost(() -> {}); restored.read(checkpoint); restored.tick(ordinary, true);
        assertFalse(restored.loaded()); assertEquals(checkpoint, restored.write());
        restored.tick(parser, true);
        assertTrue(restored.loaded()); assertEquals(checkpoint, restored.write());
        for (int i = 0; i < 50 && restored.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) restored.step(parser, true);
        assertEquals(new DataValue.BooleanValue(true), restored.flowSnapshot().orElseThrow().state().locals().field("answer"));
    }

    @Test void publicHardwareSummaryCarriesCapabilityWithoutGrantingItFromTheProgram() {
        for (String profession : List.of("cleric", "mason")) {
            var view = ComputerHardwareView.from(hardware(profession));
            var encoded = (DataValue.RecordValue)ComputerHardwareViewCodec.value(view);
            var effective = (DataValue.RecordValue)encoded.field("effective");
            assertEquals(new DataValue.BooleanValue(profession.equals("cleric")), effective.field("isa_1a"));
            assertEquals(view, ComputerHardwareViewCodec.read(encoded));
        }
    }

    private static ProgramArchive legacyArchive() {
        var graph = new ComputationGraph(Map.of(), Map.of("a", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "same", new ComputationGraph.ValueEquals("a", "a")), Map.of("out", "same"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
    }
    @Test void choosingTheCompatibilityBackendDoesNotBypassTheHardwareRequirement() {
        var host = new ComputerBlockHost(() -> {});
        var limits = hardware("mason").limits();
        assertThrows(IllegalArgumentException.class, () -> host.install(legacyArchive(), ComputerRuntime.MemoryPolicy.RESET, limits, true));
        var legacyFlow = new FlowProgram(Map.of(), List.of(), legacyArchive());
        assertThrows(IllegalArgumentException.class, () -> host.installFlow(legacyFlow, new DataValue.RecordValue(Map.of()), limits, true));
    }
    @Test void oldGraphSaveIsRetainedButCannotRunWithoutParserHardware() {
        var host = new ComputerBlockHost(() -> {});
        host.install(legacyArchive(), ComputerRuntime.MemoryPolicy.RESET, hardware("cleric").limits(), true);
        var saved = host.write();
        var restored = new ComputerBlockHost(() -> {}); restored.read(saved); restored.tick(hardware("mason").limits(), true);
        assertFalse(restored.loaded());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, restored.diagnostic().orElseThrow().code());
        assertEquals(saved, restored.write());
        restored.tick(hardware("cleric").limits(), true);
        assertTrue(restored.loaded());
    }

    @Test void coldNonDeviceCheckpointRemainsInspectableAndCanBeExplicitlyRepaired() {
        var host = new ComputerBlockHost(() -> {});
        var parser = hardware("cleric").limits(); var ordinary = hardware("mason").limits();
        host.installFlow(program("VALUE.EQUALS out a b", DataType.Scalar.BOOLEAN), inputs(), parser, true);
        host.step(parser, true);
        var checkpoint = host.write();
        var cold = new ComputerBlockHost(() -> {}); cold.read(checkpoint); cold.tick(ordinary, true);
        assertFalse(cold.loaded()); assertTrue(cold.flowSnapshot().isPresent(), "Validated state remains inspectable");
        assertEquals(checkpoint, cold.write());
        cold.reset(ordinary, true);
        cold.installFlow(program("EQUAL_INTEGER out a b", DataType.Scalar.BOOLEAN), inputs(), ordinary, true);
        assertTrue(cold.loaded());
    }
}
