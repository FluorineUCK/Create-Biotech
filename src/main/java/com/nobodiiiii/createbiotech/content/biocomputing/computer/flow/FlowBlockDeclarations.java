package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import java.util.*;

/** Shared source declarations for structure, editor grouping and reusable nesting constraints. */
public final class FlowBlockDeclarations {
    private FlowBlockDeclarations() {}
    public enum Shape { STATEMENT, OPEN, CLOSE, ALTERNATIVE, DEFINITION }
    public enum Kind {
        MONITOR_SHOW(Shape.STATEMENT, new FlowBlockRules(Set.of(), Set.of("atomic"))),
        MONITOR_HIDE(Shape.STATEMENT, new FlowBlockRules(Set.of(), Set.of("atomic"))),
        ATOMIC(Shape.OPEN, new FlowBlockRules(Set.of("atomic", "transaction_control", "device_wait", "public_memory", "task_start", "task_join", "broadcast", "broadcast_wait"), Set.of())), END_ATOMIC(Shape.CLOSE, "atomic"),
        IF(Shape.OPEN), OTHERWISE(Shape.ALTERNATIVE, "if"), END_IF(Shape.CLOSE, "if"),
        FOREVER(Shape.OPEN, "", "loop"), END_FOREVER(Shape.CLOSE, "forever"),
        REPEAT(Shape.OPEN, "", "loop"), END_REPEAT(Shape.CLOSE, "repeat"),
        UNTIL(Shape.OPEN, "", "loop"), END_UNTIL(Shape.CLOSE, "until"),
        PROCEDURE(Shape.DEFINITION, "", "custom"), END_PROCEDURE(Shape.CLOSE, "procedure"), INVOKE(Shape.STATEMENT, "", "custom"),
        BROADCAST(Shape.STATEMENT), BROADCAST_WAIT(Shape.STATEMENT, "", "device_wait"),
        WATCH(Shape.STATEMENT, "", "device_wait", "task_start"), TRY_LAUNCH(Shape.STATEMENT, "", new FlowBlockRules(Set.of(), Set.of("atomic")), "task_start"), TASK_START(Shape.STATEMENT, "", "custom"), TASK_JOIN(Shape.STATEMENT, "", "custom", "device_wait"),
        REF_ADDRESS(Shape.STATEMENT,"","public_memory"), REF_READ(Shape.STATEMENT,"","public_memory"), REF_WRITE(Shape.STATEMENT,"","public_memory"), REF_READONLY, REF_SAME,
        PUBLIC_SET(Shape.STATEMENT, "", "public_memory"), PUBLIC_ADD(Shape.STATEMENT, "", "public_memory"),
        MEMORY_BEGIN(Shape.STATEMENT, "", "transaction_control"), MEMORY_COMMIT(Shape.STATEMENT, "", "transaction_control"),
        MEMORY_ROLLBACK(Shape.STATEMENT, "", "transaction_control"), MEMORY_READ, MEMORY_WRITE,
        READ(Shape.STATEMENT, "", "device_wait"), WRITE(Shape.STATEMENT, "", "device_wait"), READABLE(Shape.STATEMENT, "", "device_wait"),
        SLEEP(Shape.STATEMENT, "", "device_wait"), WAIT_UNTIL(Shape.STATEMENT, "", "device_wait"), SERIAL_SEND(Shape.STATEMENT, "", "device_wait"), SERIAL_RECEIVE(Shape.STATEMENT, "", "device_wait"),
        STOP_SCRIPT(Shape.STATEMENT, new FlowBlockRules(Set.of(), Set.of("atomic"))),
        TIMER_RESET(Shape.STATEMENT, new FlowBlockRules(Set.of(), Set.of("atomic"))),
        ASK(Shape.STATEMENT, "", "device_wait"),
        XBUS_STATUS, SIGNAL_READ, SIGNAL_WRITE, SERIAL_STATUS, CORRELATION, EVALUATE;
        private final Shape shape;
        private final String closes;
        private final Set<String> selectors;
        private final FlowBlockRules rules;
        Kind() { this(Shape.STATEMENT); }
        Kind(Shape shape, String closes, String... groups) {
            this(shape, closes, FlowBlockRules.NONE, groups);
        }
        Kind(Shape shape, String closes, FlowBlockRules rules, String... groups) {
            this.shape = shape; this.closes = closes;
            this.rules = rules;
            var values = new LinkedHashSet<>(List.of(groups)); values.add(id()); selectors = Collections.unmodifiableSet(values);
        }
        Kind(Shape shape) { this(shape, ""); }
        Kind(Shape shape, FlowBlockRules rules) { this(shape, "", rules); }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public Shape shape() { return shape; }
        public String closes() { return closes; }
        public Set<String> selectors() { return selectors; }
        public boolean header() { return shape == Shape.OPEN || shape == Shape.DEFINITION; }
        public boolean delimiter() { return shape == Shape.CLOSE || shape == Shape.ALTERNATIVE; }
        public FlowBlockRules rules() { return rules; }
    }
    public static Kind of(FlowProgram.Step step) {
        return switch (step) {
            case FlowProgram.Atomic ignored -> Kind.ATOMIC; case FlowProgram.EndAtomic ignored -> Kind.END_ATOMIC;
            case FlowProgram.Reference ref -> Kind.valueOf("REF_"+ref.kind().name());
            case FlowProgram.Shared shared -> shared.add() ? Kind.PUBLIC_ADD : Kind.PUBLIC_SET;
            case FlowProgram.Memory memory -> Kind.valueOf("MEMORY_" + memory.kind());
            case FlowProgram.Procedure ignored -> Kind.PROCEDURE; case FlowProgram.EndProcedure ignored -> Kind.END_PROCEDURE;
            case FlowProgram.Invoke ignored -> Kind.INVOKE;
            case FlowProgram.Monitor monitor -> monitor.visible() ? Kind.MONITOR_SHOW : Kind.MONITOR_HIDE;
            case FlowProgram.Broadcast event -> event.await() ? Kind.BROADCAST_WAIT : Kind.BROADCAST;
            case FlowProgram.Watch ignored -> Kind.WATCH;
            case FlowProgram.TryLaunch ignored -> Kind.TRY_LAUNCH;
            case FlowProgram.TaskStart ignored -> Kind.TASK_START;
            case FlowProgram.TaskJoin ignored -> Kind.TASK_JOIN;
            case FlowProgram.If ignored -> Kind.IF; case FlowProgram.Otherwise ignored -> Kind.OTHERWISE; case FlowProgram.EndIf ignored -> Kind.END_IF;
            case FlowProgram.Forever ignored -> Kind.FOREVER; case FlowProgram.EndForever ignored -> Kind.END_FOREVER;
            case FlowProgram.Repeat ignored -> Kind.REPEAT; case FlowProgram.EndRepeat ignored -> Kind.END_REPEAT;
            case FlowProgram.Until ignored -> Kind.UNTIL; case FlowProgram.EndUntil ignored -> Kind.END_UNTIL;
            case FlowProgram.Read ignored -> Kind.READ; case FlowProgram.Write ignored -> Kind.WRITE; case FlowProgram.AwaitReadable ignored -> Kind.READABLE;
            case FlowProgram.Sleep ignored -> Kind.SLEEP; case FlowProgram.WaitUntil ignored -> Kind.WAIT_UNTIL;
            case FlowProgram.StopScript ignored -> Kind.STOP_SCRIPT; case FlowProgram.Evaluate ignored -> Kind.EVALUATE;
            case FlowProgram.TimerReset ignored -> Kind.TIMER_RESET;
            case FlowProgram.Ask ignored -> Kind.ASK;
            case FlowProgram.SignalRead ignored -> Kind.SIGNAL_READ; case FlowProgram.SignalWrite ignored -> Kind.SIGNAL_WRITE;
            case FlowProgram.XBusStatus ignored -> Kind.XBUS_STATUS; case FlowProgram.SerialSend ignored -> Kind.SERIAL_SEND;
            case FlowProgram.SerialReceive ignored -> Kind.SERIAL_RECEIVE; case FlowProgram.SerialStatus ignored -> Kind.SERIAL_STATUS;
            case FlowProgram.Correlation ignored -> Kind.CORRELATION;
        };
    }
    public static String procedure(ModuleDefinition.Key key) { return "custom:" + key.id().length() + ":" + key.id() + ":" + key.revision(); }
    public static Set<String> selectors(FlowProgram.Step step) {
        var result = new LinkedHashSet<>(of(step).selectors());
        if (step instanceof FlowProgram.Procedure definition) result.add(procedure(definition.key()));
        if (step instanceof FlowProgram.Invoke call) result.add(procedure(call.procedure()));
        if (step instanceof FlowProgram.TryLaunch launch) result.add(procedure(launch.procedure()));
        if (step instanceof FlowProgram.Watch watch) watch.handler().ifPresent(key -> result.add(procedure(key)));
        if (step instanceof FlowProgram.TaskStart launch) result.add(procedure(launch.procedure()));
        if (step instanceof FlowProgram.TaskJoin join) result.add(procedure(join.procedure()));
        return result;
    }
    public static FlowBlockRules rules(FlowProgram.Step step) {
        return step instanceof FlowProgram.Procedure procedure ? procedure.rules() : of(step).rules();
    }
}
