package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComputerFlowBehaviourTest {
    @Test void coldParserMismatchWithoutEditorMetadataStillExposesReceiveSourceButCannotResetIt() {
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        var entity = net.minecraft.resources.ResourceLocation.parse("minecraft:villager");
        var parser = config.select(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
            entity, net.minecraft.resources.ResourceLocation.parse("minecraft:cleric"), 5), false);
        var ordinary = config.select(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
            entity, net.minecraft.resources.ResourceLocation.parse("minecraft:mason"), 5), false);
        var key = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key("receive", 1);
        var flow = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of(), Map.of("same", "same"))),
            new FlowProgram.Procedure("definition", key, Map.of(), Map.of("same", DataType.Scalar.BOOLEAN),
                Map.of("same", "same"), FlowBlockRules.NONE,
                Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(
                    "REG got SERIAL_RECEIVE\nREG same BOOLEAN\nSERIAL.RECEIVE got s0\nVALUE.EQUALS same got got"))),
            new FlowProgram.EndProcedure("return"))).withAssembly();
        var initial = new DataValue.RecordValue(Map.of());
        var block = new ComputerBehaviourTest.Harness(); block.selection = parser;
        var host = block.getBehaviour(ComputerBehaviour.TYPE);
        host.installFlow(flow, initial);
        host.serialDevice().configure("s0", net.minecraft.core.Direction.EAST, true);
        var codec = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.serialCodec(false);
        host.serialDevice().attachments().getFirst().receiver().offer(
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(
                new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(4))), codec));
        for (int i = 0; host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().serial().isEmpty(); i++) {
            assertTrue(i < 30); host.step();
        }
        var saved = new CompoundTag(); host.write(saved, RegistryAccess.EMPTY, false);
        assertFalse(saved.contains(ComputerBehaviour.EDITOR_ROOT), "Compatibility installation has no editor metadata");
        var coldBlock = new ComputerBehaviourTest.Harness(); coldBlock.selection = ordinary;
        var cold = coldBlock.getBehaviour(ComputerBehaviour.TYPE); cold.read(saved, RegistryAccess.EMPTY, false);
        var source = cold.document().orElseThrow();
        assertEquals(flow, source.flow().orElseThrow().program(source.archive()));
        assertEquals(initial, source.flow().orElseThrow().initial());
        assertTrue(cold.flowSnapshot().isEmpty(), "Viewing source does not validate the receive checkpoint");
        assertThrows(ComputerBlockHost.HostException.class, cold::reset);
        assertThrows(ComputerBlockHost.HostException.class, cold::step);
        var after = new CompoundTag(); cold.write(after, RegistryAccess.EMPTY, false);
        assertEquals(saved, after, "Source viewing must preserve owner and serial evidence");
    }
    @Test void coldParserMismatchKeepsThePublicDocumentEditableAndAllowsExplicitBaseProgramRepair() throws Exception {
        var config = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        var parser = config.select(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
            net.minecraft.resources.ResourceLocation.parse("minecraft:villager"), net.minecraft.resources.ResourceLocation.parse("minecraft:cleric"), 5), false);
        var ordinary = config.select(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity(
            net.minecraft.resources.ResourceLocation.parse("minecraft:villager"), net.minecraft.resources.ResourceLocation.parse("minecraft:mason"), 5), false);
        var block = new ComputerBehaviourTest.Harness(); block.selection = parser;
        var behaviour = block.getBehaviour(ComputerBehaviour.TYPE);
        var document = com.nobodiiiii.createbiotech.content.biocomputing.editor.ReaderOutputDocumentTest.load("d");
        behaviour.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); behaviour.step();
        var bytes = new CompoundTag(); behaviour.write(bytes, RegistryAccess.EMPTY, false);
        var coldBlock = new ComputerBehaviourTest.Harness(); coldBlock.selection = ordinary;
        var cold = coldBlock.getBehaviour(ComputerBehaviour.TYPE); cold.read(bytes, RegistryAccess.EMPTY, false);
        assertEquals(document, cold.document().orElseThrow());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, cold.diagnostic().orElseThrow().code());
        var after = new CompoundTag(); cold.write(after, RegistryAccess.EMPTY, false); assertEquals(bytes, after);
        assertThrows(ComputerBlockHost.HostException.class, cold::step);
        cold.reset();
        cold.installFlow(new FlowProgram(Map.of(), List.of()).withAssembly(), new DataValue.RecordValue(Map.of()));
        assertTrue(cold.diagnostic().isEmpty());
    }

    @Test void coldHardwareMismatchKeepsEditableDocumentAndRequiresExplicitResetBeforeRepair() {
        var block = new ComputerBehaviourTest.Harness(); var host = block.getBehaviour(ComputerBehaviour.TYPE);
        var flow = com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingFlowFixture.calculator();
        var document = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument(flow.expressions(),
            Map.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument.entryGraphId("sum"),
                Map.of("sum", new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument.Position(7, 9))),
            Optional.empty(), Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody(flow.inputs(), flow.steps(), ComputerFlowHostTest.INPUTS)));
        host.installDocument(document, ComputerRuntime.MemoryPolicy.RESET); host.setPaused(false); host.tick();
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; i++) {
            assertTrue(i < 50); host.tick();
        }
        var old = host.flowSnapshot().orElseThrow(); var tag = new CompoundTag(); host.write(tag, RegistryAccess.EMPTY, false);
        var restoredBlock = new ComputerBehaviourTest.Harness(); var selected = restoredBlock.selection; var limits = selected.limits();
        restoredBlock.selection = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection(selected.status(), selected.resident(), selected.profile(),
            ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(limits.hardware(), limits.archive(), limits.mailbox(),
                new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("x1")))));
        var restored = restoredBlock.getBehaviour(ComputerBehaviour.TYPE); restored.read(tag, RegistryAccess.EMPTY, false);
        assertEquals(document, restored.document().orElseThrow()); assertEquals(old, restored.flowSnapshot().orElseThrow());
        assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, restored.diagnostic().orElseThrow().code());
        var retained = new CompoundTag(); restored.write(retained, RegistryAccess.EMPTY, false); assertEquals(tag, retained);
        assertThrows(ComputerBlockHost.HostException.class, restored::step);
        var fixed = new FlowProgram(flow.inputs(), List.of(new FlowProgram.Write("send", "x1", "packet")));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.class,
            () -> restored.installFlow(fixed, ComputerFlowHostTest.INPUTS));
        restored.reset(); assertTrue(restored.flowSnapshot().orElseThrow().state().paused());
        assertEquals(0, restored.flowSnapshot().orElseThrow().state().work());
        restored.installFlow(fixed, ComputerFlowHostTest.INPUTS); assertTrue(restored.diagnostic().isEmpty());
        assertEquals(fixed, restored.flowSnapshot().orElseThrow().program());
    }
    @Test void installedProgramDoesNotCreateOrRemovePhysicalPinObservations() {
        var host = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        host.installFlow(ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
        host.configureEndpoint(new EndpointConfiguration("computer", true)); host.setPaused(false); host.tick();
        assertEquals(XBusPortState.Mode.WRITE, host.xbusPort("computer", "x0").orElseThrow().mode());
        assertEquals(XBusPortState.Mode.IDLE, host.xbusPort("computer", "x1").orElseThrow().mode(), "Unused hardware pin still exists");
        assertTrue(host.xbusPort("computer", "x9").isEmpty());
        assertTrue(host.xbusPort("closed", "x1").isEmpty());
    }
    @BeforeAll static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }
    @Test void explicitInstallationReplacesLegacyOwnerButDoesNotRunOrExposePrivateState() {
        var block = new ComputerBehaviourTest.Harness(); var host = block.getBehaviour(ComputerBehaviour.TYPE);
        host.install(ComputerRuntimeTest.counter(1), ComputerRuntime.MemoryPolicy.RESET);
        var id = host.communicationIdentity(); var revision = host.programRevision();
        host.installFlow(ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
        assertTrue(host.snapshot().isEmpty());
        var document = host.document().orElseThrow();
        assertEquals(ComputerFlowHostTest.writer(), document.flow().orElseThrow().program(document.archive()));
        assertEquals(ComputerFlowHostTest.INPUTS, document.flow().orElseThrow().initial());
        assertEquals(id, host.communicationIdentity()); assertTrue(host.programRevision() > revision);
        assertTrue(host.xbusPort("computer", "x0").isEmpty());
        host.configureEndpoint(new EndpointConfiguration("computer", true));
        assertEquals(XBusPortState.Mode.PAUSED, host.xbusPort("computer", "x0").orElseThrow().mode());
        assertTrue(host.xbusPort("wrong", "x0").isEmpty()); assertTrue(host.xbusPort("computer", "undeclared").isEmpty());
        host.setPaused(false);
        assertEquals(XBusPortState.Mode.IDLE, host.xbusPort("computer", "x0").orElseThrow().mode());
        block.tick();
        assertEquals(XBusPortState.Mode.WRITE, host.xbusPort("computer", "x0").orElseThrow().mode());
        var state = host.flowSnapshot().orElseThrow();
        assertEquals(AdmissionStatus.UNSUPPORTED, host.receive(ComputerMailboxTest.message(UUID.randomUUID(), 0, 7)));
        assertEquals(state, host.flowSnapshot().orElseThrow());
        var saved = new CompoundTag(); host.write(saved, RegistryAccess.EMPTY, false);
        assertFalse(saved.contains(ComputerBehaviour.EDITOR_ROOT));
        var client = saved.copy(); host.write(client, RegistryAccess.EMPTY, true);
        assertFalse(client.contains(ComputerBehaviour.ROOT));
        var restored = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        restored.read(saved, RegistryAccess.EMPTY, false);
        assertEquals(XBusPortState.Mode.WRITE, restored.xbusPort("computer", "x0").orElseThrow().mode());
        assertEquals(state, restored.flowSnapshot().orElseThrow());
        restored.disableEndpoint(); assertTrue(restored.xbusPort("computer", "x0").isEmpty());
    }
    @Test void sequentialCommunicationInspectorDoesNotReportNoProgramOrAnImaginaryInbox() {
        var host = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        host.installFlow(ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
        var status = (DataValue.RecordValue) host.communicationStatus().field("communication");
        assertEquals(new DataValue.BooleanValue(true), status.field("program_installed"));
        assertFalse(status.fields().containsKey("inbox_limit"));
        // Flow presentation is public even when this document has no configured layout.
        var before = host.flowSnapshot(); var terminal = host.terminalStatus();
        assertEquals(DataValue.NullValue.INSTANCE, terminal.field("terminal"));
        assertEquals(new DataValue.TextValue("READY"), terminal.field("phase"));
        assertEquals(before, host.flowSnapshot(), "Inspecting does not run the Flow");
    }
    @Test void trustedPairRevalidatesOpenPinsAndContinuationsAndDoesNotAdvertiseUnretiredFlowsAsQuiescent() {
        var writer = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        var reader = new ComputerBehaviourTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        writer.installFlow(ComputerFlowHostTest.writer(), ComputerFlowHostTest.INPUTS);
        reader.installFlow(ComputerXBusExchangeTest.reader(), ComputerXBusExchangeTest.EMPTY);
        writer.configureEndpoint(new EndpointConfiguration("writer", true)); reader.configureEndpoint(new EndpointConfiguration("reader", true));
        writer.setPaused(false); reader.setPaused(false); writer.tick(); reader.tick();
        var left = writer.xbusPort("writer", "x0").orElseThrow(); var right = reader.xbusPort("reader", "x1").orElseThrow();
        reader.disableEndpoint();
        assertEquals(XBusExchange.Status.CLOSED, writer.exchangeXBus("writer", left, reader, "reader", right));
        reader.configureEndpoint(new EndpointConfiguration("reader", true));
        reader.setPaused(true);
        assertEquals(XBusExchange.Status.STALE, writer.exchangeXBus("writer", left, reader, "reader", right));
        reader.setPaused(false);
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchangeXBus("writer", left, reader, "reader", right));
        assertEquals(ComputerFlowHostTest.INPUTS, reader.flowSnapshot().orElseThrow().state().locals());
        assertFalse(writer.communicationQuiescent()); assertFalse(reader.communicationQuiescent());
        assertEquals(XBusExchange.Status.STALE, writer.exchangeXBus("writer", left, reader, "reader", right));
    }
}
