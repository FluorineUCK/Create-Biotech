package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusFlowTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowRestoreTest {
    @Test void restorePureSubframeContinuesTheSameWorkWithoutReceivingOrStartingAgain() {
        var sender = writer(); var receiver = runtime(calculator(), EMPTY);
        sender.advance(1); receiver.advance(1); exchange(sender, receiver).commit();
        receiver.advance(1); receiver.advance(1);
        assertEquals(FlowRuntime.Status.COMPUTING, receiver.checkpoint().status());
        var saved = receiver.checkpoint();
        var restored = FlowRuntime.restore(receiver.program(), saved, LIMITS);
        assertEquals(saved, restored.checkpoint());
        assertFalse(restored.loaded()); assertEquals(0, restored.advance(1)); restored.load();
        for (int i = 0; receiver.checkpoint().status() == FlowRuntime.Status.COMPUTING; i++) {
            assertTrue(i < 50); receiver.advance(1); restored.advance(1);
            assertEquals(receiver.checkpoint(), restored.checkpoint());
            restored = FlowRuntime.restore(restored.program(), restored.checkpoint(), LIMITS); restored.load();
        }
        assertEquals(packet(19), restored.checkpoint().locals().field("out"));
        assertEquals("return", restored.sourceId().orElseThrow());
    }
    @Test void cannotForgeAReadWaitFromAnUnexecutedInstructionOrDifferentInstruction() {
        var runtime = reader(); var state = runtime.checkpoint();
        var forged = new FlowRuntime.Checkpoint(state.computer(), state.flow(), 0, FlowRuntime.Status.WAITING_READ,
            EMPTY, 0, false);
        assertInvalid(runtime.program(), forged);
        assertInvalid(runtime.program(), new FlowRuntime.Checkpoint(state.computer(), state.flow(), 0,
            FlowRuntime.Status.WAITING_WRITE, EMPTY, 1, false));
        assertInvalid(runtime.program(), new FlowRuntime.Checkpoint(state.computer(), state.flow(), 1,
            FlowRuntime.Status.COMPLETE, EMPTY, 1, false));
    }
    @Test void pureGraphFailureHasSourceAndDoesNotPublishPartialLocalsOrExecuteTheFollowingWrite() {
        var sender = runtime(writer().program(), new DataValue.RecordValue(Map.of("out", packet(Long.MAX_VALUE))));
        var receiver = runtime(calculator(), EMPTY);
        sender.advance(1); receiver.advance(1); exchange(sender, receiver).commit();
        var locals = receiver.checkpoint().locals();
        for (int i = 0; receiver.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
            assertTrue(i < 50); receiver.advance(1);
        }
        assertEquals(locals, receiver.checkpoint().locals());
        assertEquals("calculate", receiver.checkpoint().diagnostic().orElseThrow().location());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW, receiver.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(receiver.checkpoint(), FlowArchiveTest.roundTrip(receiver).checkpoint());
        assertEquals(0, receiver.advance(1));
    }
    @Test void expressionCannotHideMemoryWritesOrNetworkEffectsInsideAPureFlowStep() {
        var pure = calculator(); var old = pure.expressions().program();
        var impure = new ProgramDefinition(old.graph(), Map.of("hidden", new ProgramDefinition.Cell(DataType.Scalar.INTEGER,
            new DataValue.IntegerValue(0))), List.of(), List.of(), old.entries());
        var error = assertThrows(ComputationException.class, () -> runtime(new FlowProgram(pure.inputs(), pure.steps(),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(impure, Map.of())), EMPTY));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
    }
    private static void assertInvalid(FlowProgram program, FlowRuntime.Checkpoint state) {
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, state, LIMITS)).code());
    }
}
