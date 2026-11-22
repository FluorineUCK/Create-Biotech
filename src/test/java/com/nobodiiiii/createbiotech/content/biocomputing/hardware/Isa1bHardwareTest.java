package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

class Isa1bHardwareTest {
    private static final BiocomputingConfig CONFIG = new BiocomputingConfig(new ModConfigSpec.Builder());
    private static ComputerHostLimits hardware(String profession, int level) {
        return CONFIG.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
            ResourceLocation.parse("minecraft:" + profession), level), false).limits();
    }
    private static FlowProgram program(String declarations) {
        var key = new ModuleDefinition.Key("copy", 1);
        return new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("value", "value"), Map.of("out", "answer"))),
            new FlowProgram.Procedure("definition", key, Map.of("value", DataType.Scalar.INTEGER),
                Map.of("out", DataType.Scalar.INTEGER), Map.of("out", "value"), FlowBlockRules.NONE,
                Optional.of(new AssemblySource(declarations))), new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    private static DataValue.RecordValue inputs() {
        return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)));
    }

    @Test void explicitAnyRegisterRequiresMasterEvenWhenUnusedAndInsideACustomBlock() {
        for (String profession : List.of("mason", "cleric", "librarian")) {
            var host = new ComputerBlockHost(() -> {});
            var before = host.write();
            var error = assertThrows(IllegalArgumentException.class, () -> host.installFlow(
                program("REG dynamic ANY"), inputs(), hardware(profession, 4), true));
            assertTrue(error.getMessage().contains("ISA-1b"), error.getMessage());
            assertEquals(before, host.write());
        }
    }

    @Test void mastersOfDifferentProfessionsCanRunAnyWithoutGainingParserInstructions() {
        for (String profession : List.of("mason", "cleric", "librarian", "farmer", "armorer")) {
            var limits = hardware(profession, 5);
            var host = new ComputerBlockHost(() -> {});
            var program = program("REG dynamic ANY\nMOV dynamic value");
            assertTrue(FlowXBus.image(program).instructionFamilies().contains(InstructionFamily.ISA_1B));
            assertFalse(FlowXBus.image(program).instructionFamilies().contains(InstructionFamily.ISA_1A));
            host.installFlow(program, inputs(), limits, true);
            for (int i = 0; i < 80 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) host.step(limits, true);
            assertEquals(FlowRuntime.Status.COMPLETE, host.flowSnapshot().orElseThrow().state().status());
            assertEquals(new DataValue.IntegerValue(12), host.flowSnapshot().orElseThrow().state().locals().field("answer"));
            assertEquals(profession.equals("cleric"), limits.isa1a());
        }
    }

    @Test void typeAliasesCannotHideAnyButCommentsAndOpaqueNbtDoNotRequireMaster() {
        for(String declaration:List.of("TYPE unused OPTIONAL ANY", "TYPE inner RECORD payload ANY\nTYPE outer LIST inner")) {
            assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(program(declaration),hardware("cleric",4)));
            assertDoesNotThrow(()->FlowPins.validate(program(declaration),hardware("cleric",5)));
        }
        for (String declaration : List.of("TYPE many LIST ANY\nREG data many",
                "TYPE entry RECORD value ANY\nTYPE many LIST entry\nREG data many",
                "TYPE maybe OPTIONAL ANY\nREG data maybe", "TYPE many MAP ANY\nREG data many",
                "TYPE forged RECORD ok BOOLEAN status TEXT value ANY\nREG data forged")) {
            assertThrows(FlowPins.Unavailable.class, () -> FlowPins.validate(program(declaration), hardware("cleric", 4)));
        }
        for (String declaration : List.of("; REG unused ANY\nREG text TEXT\nCONST text \"ANY\"",
                "REG raw NBT", "REG envelope SERIAL_RECEIVE")) {
            var image = FlowXBus.image(program(declaration));
            assertFalse(image.instructionFamilies().contains(InstructionFamily.ISA_1B));
            assertDoesNotThrow(() -> FlowPins.validate(program(declaration), hardware("mason", 1)));
        }
    }

    @Test void losingMasterPreservesTheSameCheckpointAndRestoresWithNoFreeExecution() {
        var master = hardware("mason", 5); var novice = hardware("mason", 4);
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program("REG dynamic ANY\nMOV dynamic value"), inputs(), master, true);
        host.step(master, true);
        var checkpoint = host.write();
        host.tick(novice, true);
        assertFalse(host.loaded());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, host.diagnostic().orElseThrow().code());
        assertTrue(host.diagnostic().orElseThrow().message().contains("ISA-1b"));
        assertEquals(checkpoint, host.write());
        var cold = new ComputerBlockHost(() -> {}); cold.read(checkpoint); cold.tick(novice, true);
        assertFalse(cold.loaded()); assertEquals(checkpoint, cold.write());
        cold.tick(master, true);
        assertTrue(cold.loaded()); assertEquals(checkpoint, cold.write());
        for (int i = 0; i < 80 && cold.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) cold.step(master, true);
        assertEquals(new DataValue.IntegerValue(12), cold.flowSnapshot().orElseThrow().state().locals().field("answer"));
    }

    @Test void publicHardwareShowsIndependentMasterCapability() {
        for (String profession : List.of("cleric", "mason")) for (int level : List.of(1, 4, 5)) {
            var selection = CONFIG.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
                ResourceLocation.parse("minecraft:" + profession), level), false);
            var view = ComputerHardwareView.from(selection);
            var data = (DataValue.RecordValue)ComputerHardwareViewCodec.value(view);
            assertEquals(new DataValue.BooleanValue(level == 5), ((DataValue.RecordValue)data.field("effective")).field("isa_1b"));
            assertEquals(view, ComputerHardwareViewCodec.read(data));
        }
    }

    @Test void oldSourceVersionsAndUncalledTaskBodiesCannotEvadeTheGate() {
        for (int version : List.of(AssemblySource.VERSION)) {
            var key = new ModuleDefinition.Key("background", 1);
            var source = new FlowProgram(Map.of(), List.of(
                new FlowProgram.Procedure("definition", key, Map.of(), Map.of(), Map.of(), FlowBlockRules.NONE,
                    Optional.of(new AssemblySource(version, "REG dynamic ANY"))),
                new FlowProgram.EndProcedure("end"))).withAssembly();
            assertThrows(FlowPins.Unavailable.class, () -> FlowPins.validate(source, hardware("cleric", 4)));
            assertDoesNotThrow(() -> FlowPins.validate(source, hardware("mason", 5)));
            assertTrue(FlowXBus.image(source).forTask("background", 1).instructionFamilies().contains(InstructionFamily.ISA_1B));
        }
    }
}
