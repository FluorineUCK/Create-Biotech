package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowTimerTest {
    private static final ModuleDefinition.Key CHILD = new ModuleDefinition.Key("timer_task", 1);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    private static FlowProgram program() {
        var decimal = DataType.Scalar.DECIMAL;
        var expression = new ProgramDefinition.Entry(new ComputationGraph(Map.of(), Map.of("timer", new ComputationGraph.Timer()),
            Map.of("value", "timer")), List.of(), List.of());
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("elapsed", expression)), Map.of());
        return new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("wait", "ticks"),
            new FlowProgram.Evaluate("main_read", "elapsed", new FlowCallBindings(Map.of(), Map.of("value", "elapsed"))),
            new FlowProgram.Procedure("definition", CHILD, Map.of(), Map.of("seconds", decimal), Map.of("seconds", "after"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(                "REG before DECIMAL\nREG after DECIMAL\nTIMER.READ before\nTIMER.RESET\nTIMER.READ after\nRET"))),
            new FlowProgram.EndProcedure("end")), archive).withAssembly();
    }

    @Test void timerIsSharedAcrossTasksButNotComputersAndSurvivesColdPause() {
        var owner = install();
        var peer = install();
        owner.step(LIMITS, true); // Admit main Sleep, but do not advance its clock.
        assertEquals(0, saved(owner).timerTicks());
        owner.setPaused(false, LIMITS, true);
        for (long tick : List.of(1L, 2L, 3L)) {
            assertTrue(owner.beginFlowTick(tick, LIMITS, true));
            assertFalse(owner.beginFlowTick(tick, LIMITS, true));
        }
        assertEquals(3, saved(owner).timerTicks());
        assertEquals(0, saved(peer).timerTicks());
        owner.setPaused(true, LIMITS, true);
        owner.tick(4, LIMITS, true, Map.of());
        assertEquals(3, saved(owner).timerTicks());
        owner.unload();
        owner.tick(20_000, LIMITS, false, Map.of());
        owner = FlowAssemblyTasksTest.restored(owner);
        assertEquals(3, saved(owner).timerTicks());

        var task = owner.startFlowTask(CHILD, EMPTY, LIMITS, true);
        owner.step(LIMITS, true);
        assertEquals(new DataValue.DecimalValue(0.15), owner.flowTask(task).orElseThrow().locals().field("before"));
        assertEquals(3, saved(owner).timerTicks());
        owner.step(LIMITS, true);
        assertEquals(0, saved(owner).timerTicks()); // Reset and advancing the child's PC are one publication.
        owner = FlowAssemblyTasksTest.restored(owner);
        owner.step(LIMITS, true);
        assertEquals(new DataValue.DecimalValue(0), owner.flowTask(task).orElseThrow().locals().field("after"));
        for (int i = 0; owner.flowTask(task).orElseThrow().status() == FlowRuntime.Status.READY; i++) {
            assertTrue(i < 8, "Child should return through the authored epilogue");
            owner.step(LIMITS, true);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(task).orElseThrow().status());
        assertEquals(FlowRuntime.Status.SLEEPING, saved(owner).state().status());
        assertEquals(1, saved(owner).state().sleepTicks());
        owner.step(LIMITS, true); // No runnable tasks; step advances exactly one sleeping tick.
        assertEquals(1, saved(owner).timerTicks());
        owner.setPaused(false, LIMITS, true);
        for (long tick = 20_001; tick <= 20_010 && saved(owner).state().status() != FlowRuntime.Status.COMPLETE; tick++)
            owner.tick(tick, LIMITS, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, saved(owner).state().status());
        assertTrue(((DataValue.DecimalValue) saved(owner).state().locals().field("elapsed")).value() < 1);
    }

    @Test void restartingAProgramWithoutEdgeHatsResetsItsActivityTimer() {
        var owner=install();
        owner.setPaused(false,LIMITS,true);
        assertTrue(owner.beginFlowTick(1,LIMITS,true));
        assertEquals(1,saved(owner).timerTicks());
        var oldRun=saved(owner).state().flow();
        owner.reset(LIMITS,true);
        assertNotEquals(oldRun,saved(owner).state().flow());
        assertTrue(saved(owner).state().paused());
        assertEquals(0,saved(owner).timerTicks(),"Explicit restart must clear the same activity clock with or without edge hats");
        var loaded=FlowAssemblyTasksTest.restored(owner);
        assertEquals(0,saved(loaded).timerTicks());
    }

    @Test void timerEdgeIsArmedBeforePauseAndFiresOnceAfterResidentResumes() {
        var zero=new DataValue.IntegerValue(0);
        var one=new DataValue.IntegerValue(1);
        var condition=new ComputationGraph(Map.of(),Map.of(
            "timer",new ComputationGraph.Timer(),
            "threshold",new ComputationGraph.Constant(new DataValue.DecimalValue(0.5)),
            "edge",new ComputationGraph.Scalar(ScalarOperation.SAMPLE_RISING_DECIMAL,Map.of("current","timer","threshold","threshold"))
        ), Map.of("triggered","edge"));
        var key=new ModuleDefinition.Key("clock_watch",1);
        var handler=new ModuleDefinition.Key("clock_handler",1);
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("hits",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,zero)), List.of(),List.of(),
            Map.of("condition",new ProgramDefinition.Entry(condition,List.of(),List.of()))), Map.of());
        var flow=new FlowProgram(Map.of(),List.of(
            new FlowProgram.Procedure("watch_def",key,Map.of(),Map.of(),Map.of()),
            new FlowProgram.Watch("watch",handler,new FlowInput.Expression("condition","triggered",Map.of())),
            new FlowProgram.EndProcedure("watch_end"),
            new FlowProgram.Procedure("handler",handler,Map.of(),Map.of(),Map.of()),
            new FlowProgram.Shared("increment","hits",new FlowInput.Literal(one),true),
            new FlowProgram.EndProcedure("handler_end")),archive).withAssembly();
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(1000,100_000,12), LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),4,true);
        var host=new ComputerBlockHost(()->{});
        host.installFlow(flow,EMPTY,limits,true);
        host.setPaused(false,limits,true);
        for(long tick=1;tick<=5;tick++)host.tick(tick,limits,true,Map.of());
        assertEquals(5,saved(host).timerTicks());
        assertEquals(zero,saved(host).memory().cells().field("hits"));
        host.setPaused(true,limits,true);
        var raw=host.write(); host.unload();
        var restored=new ComputerBlockHost(()->{});restored.read(raw);restored.tick(10_000,limits,true,Map.of());
        assertEquals(5,saved(restored).timerTicks());
        assertEquals(zero,saved(restored).memory().cells().field("hits"));
        restored.setPaused(false,limits,true);
        for(long t=10_001;t<=10_030;t++)restored.tick(t,limits,true,Map.of());
        assertEquals(35,saved(restored).timerTicks());
        assertEquals(one,saved(restored).memory().cells().field("hits"));
    }

    private static ComputerBlockHost install() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(), new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(4))), LIMITS, true);
        return host;
    }

    @Test void resetCannotEscapeAnAtomicUpdateThroughAnAuthoredCall() {
        var inline = new FlowProgram(Map.of(), List.of(new FlowProgram.Atomic("atomic", "committed"),
            new FlowProgram.TimerReset("reset"), new FlowProgram.EndAtomic("end_atomic"))).withAssembly();
        var authored = new FlowProgram(Map.of(), List.of(new FlowProgram.Atomic("atomic", "committed"),
            new FlowProgram.Invoke("call", CHILD, new FlowCallBindings(Map.of(), Map.of())),
            new FlowProgram.EndAtomic("end_atomic"),
            new FlowProgram.Procedure("definition", CHILD, Map.of(), Map.of(), Map.of(),
                FlowBlockRules.NONE, Optional.of(new AssemblySource("TIMER.RESET\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        for (var program : List.of(inline, authored)) {
            var fault = assertThrows(ComputationException.class, () -> program.validate(LIMITS.hardware()));
            assertEquals(ComputationException.Code.INVALID_GRAPH, fault.code());
            assertTrue(fault.getMessage().contains("atomic"), fault::getMessage);
        }
    }

    private static FlowBlockHost.Saved saved(ComputerBlockHost host) { return host.flowSnapshot().orElseThrow(); }
}
