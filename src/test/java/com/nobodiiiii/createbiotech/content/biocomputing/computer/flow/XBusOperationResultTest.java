package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusFlowTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class XBusOperationResultTest {
    @Test void ordinaryFieldAndIfBlocksHandleBothOutcomesAcrossEverySavedContinuation() {
        var inspect = new ComputationGraph(Map.of("result", XBusResult.readType(PACKET)), Map.of(
            "input", new ComputationGraph.Input("result"), "ok", new ComputationGraph.Field("input", "ok")), Map.of("ok", "ok"));
        var program = new FlowProgram(Map.of("yes", DataType.Scalar.INTEGER, "no", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Read("read", "x1", "result", PACKET, true), new FlowProgram.Evaluate("inspect", "inspect"),
            new FlowProgram.If("if", "ok"), new FlowProgram.SignalWrite("success", "p0", "yes"),
            new FlowProgram.Otherwise("else"), new FlowProgram.SignalWrite("failure", "p0", "no"), new FlowProgram.EndIf("end")),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(
                new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
                Map.of("inspect", new ProgramDefinition.Entry(inspect, List.of(), List.of()))), Map.of()));
        for (boolean success : List.of(false, true)) {
            var flow = runtime(program, new DataValue.RecordValue(Map.of("yes", new DataValue.IntegerValue(1), "no", new DataValue.IntegerValue(0))));
            flow.advance(1);
            if (success) { var sender = writer(); sender.advance(1); assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, flow).commit()); }
            else assertTrue(flow.rejectPausedXBus());
            for (int i=0; flow.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
                assertTrue(i < 40); flow = FlowArchiveTest.roundTrip(flow); flow.advance(1);
            }
            assertEquals(Map.of("p0", success ? 1 : 0), flow.checkpoint().signals().outputs());
            assertEquals(new DataValue.BooleanValue(success), flow.checkpoint().locals().field("ok"));
        }
    }
    @Test void legacyInstructionsFailExplicitlyButNeverAdvanceAPausedOrUnloadedLocalCpu() {
        for (var step : List.<FlowProgram.Step>of(new FlowProgram.Read("read", "x1", "input", PACKET),
                new FlowProgram.Write("write", "x1", "packet"), new FlowProgram.AwaitReadable("await", "x1"))) {
            var flow = runtime(new FlowProgram(Map.of("packet", PACKET), List.of(step)), new DataValue.RecordValue(Map.of("packet", packet(12))));
            assertFalse(flow.rejectPausedXBus()); flow.advance(1); flow.setPaused(true);
            var paused = flow.checkpoint(); assertFalse(flow.rejectPausedXBus()); assertEquals(paused, flow.checkpoint());
            flow.setPaused(false); flow.unload(); var unloaded = flow.checkpoint();
            assertFalse(flow.rejectPausedXBus()); assertEquals(unloaded, flow.checkpoint()); flow.load();
            assertTrue(flow.rejectPausedXBus());
            assertEquals(FlowRuntime.Status.FAILED, flow.checkpoint().status());
            assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, flow.checkpoint().diagnostic().orElseThrow().code());
            assertEquals(step.sourceId(), flow.checkpoint().diagnostic().orElseThrow().location());
            assertEquals(0, flow.checkpoint().cursor()); assertEquals(1, flow.checkpoint().work());
            assertEquals(flow.checkpoint(), FlowArchiveTest.roundTrip(flow).checkpoint());
        }
    }
    @Test void resultStorageIsChargedAndACapacityRejectionDoesNotPublishAHalfResult() {
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "result", PACKET, true)));
        var flow = runtime(program, EMPTY); flow.advance(1); var before = flow.checkpoint();
        var usage = program.expressions().compile(LIMITS).usage();
        long memory = before.controlUnits() + usage.constantUnits() + 2 * usage.initialMemoryUnits();
        var constrained = FlowRuntime.restore(program, before, new ResourceLimits(LIMITS.programUnits(), memory, 1)); constrained.load();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class, constrained::rejectPausedXBus).code());
        assertEquals(before, constrained.checkpoint());
    }
    @Test void successfulResultKeepsWirePayloadAndReceiptSemanticsSeparate() {
        var sender = runtime(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet", Optional.of("sent")))), new DataValue.RecordValue(Map.of("packet", packet(12))));
        var receiver = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x1", Optional.of("ready")),
            new FlowProgram.Read("receive", "x1", "result", PACKET, true))), EMPTY);
        sender.advance(1); receiver.advance(1);
        assertEquals(XBusExchange.Status.READABLE, exchange(sender, receiver).commit());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, sender.checkpoint().status());
        assertEquals(new DataValue.BooleanValue(true), ((DataValue.RecordValue)receiver.checkpoint().locals().field("ready")).field("ok"));
        receiver.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, receiver).commit());
        var result = (DataValue.RecordValue)receiver.checkpoint().locals().field("result");
        assertEquals(packet(12), result.field("value"));
        assertEquals(DataValue.NullValue.INSTANCE, result.field("error"));
        assertEquals(new DataValue.BooleanValue(true), ((DataValue.RecordValue)sender.checkpoint().locals().field("sent")).field("ok"));
        assertEquals(packet(12), sender.checkpoint().locals().field("packet"));
    }
    @Test void explicitReadResultFinishesWithTypedErrorInsteadOfInventingPayload() {
        var type = (DataType.RecordType) DataType.typeOf(packet(12));
        var flow = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "result", type, true))), EMPTY);
        flow.advance(1);
        assertEquals(FlowRuntime.Status.WAITING_READ, flow.checkpoint().status());
        assertTrue(flow.rejectPausedXBus());
        assertEquals(FlowRuntime.Status.COMPLETE, flow.checkpoint().status());
        assertEquals(1, flow.checkpoint().work(), "Completing an entered operation is not another CPU instruction");
        var result = (DataValue.RecordValue) flow.checkpoint().locals().field("result");
        assertEquals(new DataValue.BooleanValue(false), result.field("ok"));
        assertEquals(DataValue.NullValue.INSTANCE, result.field("value"));
        assertEquals("xbus_peer_paused", ((DataValue.ErrorValue) result.field("error")).code());
        var saved = flow.checkpoint();
        assertFalse(flow.rejectPausedXBus());
        assertEquals(saved, flow.checkpoint());
        assertEquals(saved, FlowRuntime.restore(flow.program(), saved, LIMITS).checkpoint());
    }
}
