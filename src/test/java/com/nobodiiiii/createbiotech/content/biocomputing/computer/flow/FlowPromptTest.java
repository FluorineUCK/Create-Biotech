package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowPromptTest {
    private static final ModuleDefinition.Key CHILD = new ModuleDefinition.Key("ask_child", 1);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    private static FlowProgram program() {
        var text = DataType.Scalar.TEXT;
        var expression = new ProgramDefinition.Entry(new ComputationGraph(Map.of(), Map.of("answer", new ComputationGraph.Answer()),
            Map.of("value", "answer")), List.of(), List.of());
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("answer", expression)), Map.of());
        return new FlowProgram(Map.of(), List.of(
            new FlowProgram.Ask("ask_main", new FlowInput.Literal(new DataValue.TextValue("First"))),
            new FlowProgram.Evaluate("read_answer", "answer", new FlowCallBindings(Map.of(), Map.of("value", "main_answer"))),
            new FlowProgram.Procedure("definition", CHILD, Map.of(), Map.of("result", text), Map.of("result", "received"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(                "REG question TEXT\nREG received TEXT\nCONST question \"Second\"\nTERMINAL.ASK question\nTERMINAL.ANSWER received\nRET"))),
            new FlowProgram.EndProcedure("end")), archive).withAssembly();
    }

    @Test void ownerQueuesOnlyAskingTasksAndRejectsAnOldQuestionAfterColdRestore() {
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program(), EMPTY, LIMITS, true);
        for (int i = 0; owner.terminalQuestion().isEmpty(); i++) {
            assertTrue(i < 8); owner.step(LIMITS, true);
        }
        var first = owner.terminalQuestion().orElseThrow();
        assertEquals(new DataValue.TextValue("First"), first.text());
        var child = owner.startFlowTask(CHILD, EMPTY, LIMITS, true);
        for (int i = 0; owner.flowTask(child).orElseThrow().status() != FlowRuntime.Status.WAITING_ANSWER; i++) {
            assertTrue(i < 8); owner.step(LIMITS, true);
        }
        assertEquals(FlowRuntime.Status.WAITING_ANSWER, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(first, owner.terminalQuestion().orElseThrow());
        assertEquals(2, owner.flowSnapshot().orElseThrow().prompts().pending().size());
        var restored = FlowAssemblyTasksTest.restored(owner);
        assertEquals(first, restored.terminalQuestion().orElseThrow());
        assertThrows(ComputationException.class, () -> restored.answerTerminal(first.token(), "paused", LIMITS, true));
        restored.setPaused(false, LIMITS, true);
        long work = restored.flowSnapshot().orElseThrow().state().work();
        restored.answerTerminal(first.token(), "alpha", LIMITS, true);
        assertEquals(work, restored.flowSnapshot().orElseThrow().state().work(), "Admission already paid the wait instruction");
        var second = restored.terminalQuestion().orElseThrow();
        assertEquals(new DataValue.TextValue("Second"), second.text());
        assertNotEquals(first.token(), second.token());
        assertThrows(IllegalArgumentException.class, () -> restored.answerTerminal(first.token(), "stale", LIMITS, true));
        assertEquals(second, restored.terminalQuestion().orElseThrow());
        for (int tick = 1; tick <= 12; tick++) restored.tick(tick, LIMITS, true, Map.of());
        assertEquals(new DataValue.TextValue("alpha"), restored.flowSnapshot().orElseThrow().state().locals().field("main_answer"));
        assertEquals(FlowRuntime.Status.WAITING_ANSWER, restored.flowTask(child).orElseThrow().status());
        restored.answerTerminal(second.token(), "beta", LIMITS, true);
        for (int tick = 13; tick <= 25; tick++) restored.tick(tick, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, restored.flowTask(child).orElseThrow().status());
        assertEquals(new DataValue.TextValue("beta"), restored.flowTask(child).orElseThrow().locals().field("result"));
        assertTrue(restored.terminalQuestion().isEmpty());
        assertEquals(new DataValue.TextValue("beta"), restored.flowSnapshot().orElseThrow().prompts().answer());
    }
}
