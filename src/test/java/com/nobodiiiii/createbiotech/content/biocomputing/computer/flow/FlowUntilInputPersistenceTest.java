package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowUntilInputPersistenceTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);

    @Test void slotInvocationCannotBeDroppedRetargetedOrAttachedOutsideItsCalculation() {
        var template = FlowUntilInputTest.until(FlowUntilInputTest.expression("condition", "done"));
        var archive = template.expressions(); var old = archive.program().entries().get("condition").graph();
        var outputs = new LinkedHashMap<>(old.outputs()); outputs.put("other_done", "done");
        var entry = new ProgramDefinition.Entry(new ComputationGraph(old.inputs(), old.nodes(), outputs), List.of(), List.of());
        var entries = new LinkedHashMap<>(archive.program().entries()); entries.put("condition", entry); entries.put("alternate", entry);
        var expressions = new ProgramArchive(new ProgramDefinition(archive.program().graph(), Map.of(), List.of(), List.of(), entries), archive.modules());
        var program = new FlowProgram(template.inputs(), template.steps(), expressions);
        var runtime = FlowRepeatTest.runtime(program, 3); var initial = runtime.checkpoint();
        var checkpoints = new ArrayList<FlowRuntime.Checkpoint>();
        for (int i = 0; i < 50; i++) {
            runtime.advance(1); var state = runtime.checkpoint();
            if (i == 0) checkpoints.add(state);
            if (state.expression().filter(pure -> pure.frames().getFirst() instanceof MachineCheckpoint.CommitState).isPresent()) {
                checkpoints.add(state); break;
            }
        }
        assertEquals(2, checkpoints.size());
        for (var state : checkpoints) {
            FlowRuntime.restore(program, state, FlowUntilInputTest.LIMITS);
            for (var source : List.of(new FlowInput.Expression("alternate", "done"), new FlowInput.Expression("condition", "other_done"))) {
                var steps = new ArrayList<>(program.steps()); steps.set(0, new FlowProgram.Until("until", source));
                var changed = new FlowProgram(program.inputs(), steps, expressions);
                assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                    () -> FlowRuntime.restore(changed, state, FlowUntilInputTest.LIMITS)).code());
            }
            var missing = withInvocation(state, Optional.empty());
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> FlowRuntime.restore(program, missing, FlowUntilInputTest.LIMITS)).code());
            var misplaced = withInvocation(initial, state.inputInvocation());
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> FlowRuntime.restore(program, misplaced, FlowUntilInputTest.LIMITS)).code());
        }
        var computing = checkpoints.getFirst();
        var evaluateSteps = new ArrayList<>(program.steps());
        evaluateSteps.set(0, new FlowProgram.Evaluate("until", "condition")); evaluateSteps.removeLast();
        var evaluate = new FlowProgram(program.inputs(), evaluateSteps, expressions);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(evaluate, computing, FlowUntilInputTest.LIMITS)).code());
    }

    @Test void invocationCopiesAreChargedAtExactCapacityAndReleasedOnSuccessOrFailure() {
        var program = FlowUntilInputTest.until(FlowUntilInputTest.expression("condition", "done"));
        var probe = FlowRepeatTest.runtime(program, 0); var initial = probe.checkpoint();
        long peak = 0; FlowRuntime.Checkpoint peakState = null;
        for (int i = 0; i < 50 && probe.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            probe.advance(1); var state = probe.checkpoint();
            if (state.inputInvocation().isEmpty()) continue;
            var invocation = state.inputInvocation().orElseThrow();
            assertEquals(initial.locals(), invocation.inputs(), "Both parameters are copied, not just the selected result");
            assertEquals(withInvocation(state, Optional.empty()).controlUnits() + 1 + "input_invocation".length() + invocation.value().storageUnits(), state.controlUnits());
            long total = state.controlUnits() + state.expression().orElseThrow().arenaUnits();
            if (total > peak) { peak = total; peakState = state; }
        }
        assertNotNull(peakState); assertEquals(FlowRuntime.Status.COMPLETE, probe.checkpoint().status());
        assertTrue(probe.checkpoint().inputInvocation().isEmpty());
        final var savedPeak = peakState;
        var shortLimits = new ResourceLimits(1000, peak - 1, 1);
        assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, savedPeak, shortLimits));
        for (long memory : List.of(peak - 1, peak)) {
            var limits = new ResourceLimits(1000, memory, 1);
            var runtime = new FlowRuntime(initial.computer(), initial.flow(), program, initial.locals(), limits);
            for (int i = 0; i < 50 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
                runtime.advance(1);
                var saved = FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, runtime.checkpoint()), ARCHIVE), ARCHIVE);
                runtime = FlowRuntime.restore(saved.program(), saved.state(), limits); runtime.load();
            }
            assertEquals(memory == peak ? FlowRuntime.Status.COMPLETE : FlowRuntime.Status.FAILED, runtime.checkpoint().status());
            assertTrue(runtime.checkpoint().inputInvocation().isEmpty());
            if (memory < peak) assertEquals(ComputationException.Code.MEMORY_LIMIT, runtime.checkpoint().diagnostic().orElseThrow().code());
        }
    }

    private static FlowRuntime.Checkpoint withInvocation(FlowRuntime.Checkpoint state, Optional<FlowInput.Invocation> invocation) {
        return new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), state.work(), state.paused(),
            state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), invocation);
    }

    @Test void conditionCommitFrameStillRejectsChangedFrozenArguments() {
        var program = FlowUntilInputTest.until(FlowUntilInputTest.expression("condition", "done"));
        var runtime = FlowRepeatTest.runtime(program, 3);
        for (int i = 0; i < 50; i++) {
            runtime.advance(1);
            if (runtime.checkpoint().expression().filter(pure -> pure.frames().getFirst() instanceof MachineCheckpoint.CommitState).isPresent()) break;
        }
        var state = runtime.checkpoint();
        assertEquals(0, state.cursor());
        assertInstanceOf(MachineCheckpoint.CommitState.class, state.expression().orElseThrow().frames().getFirst());
        var encoded = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, state), ARCHIVE), ARCHIVE);
        var data = new LinkedHashMap<>(encoded.fields());
        var continuation = new LinkedHashMap<>(((DataValue.RecordValue)encoded.field("state")).fields());
        var locals = new LinkedHashMap<>(state.locals().fields()); locals.put("count", new DataValue.IntegerValue(3));
        continuation.put("locals", new DataValue.RecordValue(locals)); data.put("state", new DataValue.RecordValue(continuation));
        var changed = FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(data), ARCHIVE), ARCHIVE);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(changed.program(), changed.state(), FlowUntilInputTest.LIMITS)).code());
    }

    @Test void restoredConditionCannotChangeItsFrozenParametersOrAttachToALiteralHead() {
        var program = FlowUntilInputTest.until(FlowUntilInputTest.expression("condition", "done"));
        var runtime = FlowRepeatTest.runtime(program, 3); runtime.advance(1); var state = runtime.checkpoint();
        var locals = new LinkedHashMap<>(state.locals().fields()); locals.put("count", new DataValue.IntegerValue(1));
        var changed = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), new DataValue.RecordValue(locals),
            state.work(), state.paused(), state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), state.inputInvocation());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(program, changed, FlowUntilInputTest.LIMITS)).code());
        var other = FlowUntilInputTest.until(FlowUntilInputTest.literal(new DataValue.BooleanValue(true)));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(other, state, FlowUntilInputTest.LIMITS)).code());
    }

    @Test void inputLoopsRequireNewFormatsAndRestoreAtEveryPausedInstructionAndPureSubframe() {
        for (var program : List.of(FlowUntilInputTest.until(FlowUntilInputTest.expression("condition", "done")),
                FlowUntilInputTest.counted(FlowUntilInputTest.expression("times", "times")),
                FlowUntilInputTest.until(FlowUntilInputTest.literal(new DataValue.BooleanValue(true))))) {
            var runtime = FlowRepeatTest.runtime(program, 3); var initial = runtime.checkpoint().locals();
            var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
                Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial)));
            var encoded = ProgramDocumentCodec.encode(document);
            assertEquals(new DataValue.IntegerValue(9), encoded.field("version"));
            assertEquals(document, ProgramDocumentCodec.decode(encoded));
            var oldDoc = new LinkedHashMap<>(encoded.fields()); oldDoc.put("version", new DataValue.IntegerValue(8));
            assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(oldDoc)));
            boolean inPure = false;
            for (int i = 0; i < 200 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
                runtime.advanceRound(Map.of("p0", 0)); inPure |= runtime.checkpoint().status() == FlowRuntime.Status.COMPUTING;
                runtime.setPaused(true); var state = runtime.checkpoint();
                runtime.unload(); runtime.elapseTick(); assertEquals(0, runtime.advance(1)); assertEquals(state, runtime.checkpoint());
                var saved = new FlowNbtCodec.Snapshot(program, state);
                var tag = FlowNbtCodec.encode(saved, ARCHIVE);
                var data = (DataValue.RecordValue)ValueNbtCodec.decode(tag, ARCHIVE);
                assertEquals(new DataValue.IntegerValue(FlowProgram.hasSignals(program.steps()) ? 20 : 8), data.field("version"));
                assertEquals(saved, FlowNbtCodec.decode(tag, ARCHIVE));
                var old = new LinkedHashMap<>(data.fields()); old.put("version", new DataValue.IntegerValue(7));
                assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(old), ARCHIVE), ARCHIVE));
                var owner = new FlowBlockHost.Saved(program, initial, state);
                var ownerTag = FlowBlockNbtCodec.encode(owner, ARCHIVE);
                assertEquals(FlowProgram.hasSignals(program.steps()) ? 10 : 7, ownerTag.getInt(FlowBlockNbtCodec.MARKER));
                assertEquals(owner, FlowBlockNbtCodec.decode(ownerTag, ARCHIVE));
                ownerTag.putInt(FlowBlockNbtCodec.MARKER, 6);
                assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(ownerTag, ARCHIVE));
                var decoded = FlowNbtCodec.decode(tag, ARCHIVE);
                runtime = FlowRuntime.restore(decoded.program(), decoded.state(), FlowUntilInputTest.LIMITS);
                assertEquals(0, runtime.advance(1)); runtime.load(); runtime.elapseTick();
                assertEquals(state, runtime.checkpoint()); assertEquals(0, runtime.advance(1)); runtime.setPaused(false);
            }
            assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
            if (program.steps().getFirst() instanceof FlowProgram.Until until && until.condition() instanceof FlowInput.Literal)
                assertEquals(new DataValue.IntegerValue(0), runtime.checkpoint().locals().field("count"));
            else {
                assertTrue(inPure); assertEquals(new DataValue.IntegerValue(3), runtime.checkpoint().locals().field("count"));
            }
        }
    }
}
