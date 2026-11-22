package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import java.util.*;

/** One immutable active-layout/RAM observation, without a program or debugger duplicate. */
public record TerminalStatusView(Optional<TerminalDefinition> terminal, DataValue.RecordValue roots, String phase, boolean paused, boolean keyboard, Optional<Question> question) {
    public record Question(String token, String text) {}
    public TerminalStatusView(Optional<TerminalDefinition> terminal, DataValue.RecordValue roots, String phase, boolean paused, boolean keyboard) { this(terminal,roots,phase,paused,keyboard,Optional.empty()); }
    public TerminalStatusView(Optional<TerminalDefinition> terminal,DataValue.RecordValue roots,String phase,boolean paused) { this(terminal,roots,phase,paused,false); }
    public TerminalStatusView { Objects.requireNonNull(terminal); Objects.requireNonNull(roots); Objects.requireNonNull(phase); Objects.requireNonNull(question); }
    /** Presentation-only absence: never changes a value, a binding, or an execution checkpoint. */
    Optional<TerminalProjection.Problem> unavailableOutput() {
        if (roots.fields().get("output") != DataValue.NullValue.INSTANCE) return Optional.empty();
        String code = switch (phase) {
            case "IDLE", "RUNNING", "READY", "COMPUTING", "WAITING_READ", "WAITING_WRITE", "WAITING_READABLE", "WAITING_TASK", "WAITING_ANSWER", "SLEEPING" -> "output_pending";
            case "FAILED" -> "output_failed";
            case "NO_PROGRAM" -> "output_no_program";
            default -> "output_unavailable";
        };
        return Optional.of(new TerminalProjection.Problem(code, phase));
    }
    public static DataValue.RecordValue of(Optional<TerminalDefinition> terminal, Optional<ComputerRuntime.Snapshot> snapshot) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("terminal", terminal.<DataValue>map(TerminalCodec::encode).orElse(DataValue.NullValue.INSTANCE));
        fields.put("memory", snapshot.<DataValue>map(s -> s.machine().memory()).orElseGet(() -> new DataValue.RecordValue(Map.of())));
        fields.put("output", snapshot.flatMap(s -> s.machine().output()).<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE));
        fields.put("phase", new DataValue.TextValue(snapshot.map(s -> s.machine().status().name()).orElse("NO_PROGRAM")));
        fields.put("paused", new DataValue.BooleanValue(snapshot.map(s -> s.machine().paused()).orElse(false)));
        return new DataValue.RecordValue(fields);
    }
    /** A completed Flow exposes its immutable named result, not its private continuation or a fake RAM bank. */
    public static DataValue.RecordValue ofFlow(Optional<TerminalDefinition> terminal, Optional<FlowRuntime.Checkpoint> snapshot) {
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("terminal", terminal.<DataValue>map(TerminalCodec::encode).orElse(DataValue.NullValue.INSTANCE));
        fields.put("memory", new DataValue.RecordValue(Map.of()));
        fields.put("output", snapshot.filter(s -> s.status() == FlowRuntime.Status.COMPLETE)
            . <DataValue>map(s->LocalReferences.describeRoots(s.locals())).orElse(DataValue.NullValue.INSTANCE));
        fields.put("phase", new DataValue.TextValue(snapshot.map(s -> s.cancelled() ? "CANCELLED" : s.status().name()).orElse("NO_PROGRAM")));
        fields.put("paused", new DataValue.BooleanValue(snapshot.map(FlowRuntime.Checkpoint::paused).orElse(false)));
        return new DataValue.RecordValue(fields);
    }
    /** Authorized local terminal only. In-progress locals and call stacks are not part of the presentation API. */
    public static DataValue.RecordValue ofFlowOwner(Optional<TerminalDefinition> terminal,
            Optional<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost.Saved> snapshot) {
        var base = ofFlow(terminal, snapshot.map(s -> s.state()));
        if (snapshot.filter(s -> s.program().usesMemoryAssembly()).isPresent()) {
            var values = new LinkedHashMap<>(base.fields()); values.put("memory", snapshot.orElseThrow().memory().cells());
            base = new DataValue.RecordValue(values);
        }
        if (snapshot.filter(saved->(saved.program().usesTerminalKeys() || saved.program().usesKeyDown())).isPresent()) {
            var values=new LinkedHashMap<>(base.fields());values.put("keyboard",new DataValue.BooleanValue(true));base=new DataValue.RecordValue(values);
        }
        if (terminal.filter(TerminalDefinition::usesTasks).isEmpty() && snapshot.filter(s -> !s.tasks().contexts().isEmpty()).isEmpty()) return base;
        var rows = snapshot.stream().flatMap(s -> s.tasks().contexts().stream()).<DataValue>map(state -> {
            var entry = state.entry().orElseThrow();
            return new DataValue.RecordValue(Map.of("handle", new DataValue.TextValue(state.flow().toString()),
                "procedure", new DataValue.TextValue(entry.id()), "revision", new DataValue.IntegerValue(entry.revision()),
                "phase", new DataValue.TextValue(state.cancelled() ? "CANCELLED" : state.status().name()),
                "result", state.status() == FlowRuntime.Status.COMPLETE ? LocalReferences.describeRoots(state.locals()) : DataValue.NullValue.INSTANCE,
                "failure", state.diagnostic().<DataValue>map(d -> new DataValue.RecordValue(Map.of("code", new DataValue.TextValue(d.code().name()),
                    "detail", new DataValue.TextValue(d.message())))).orElse(DataValue.NullValue.INSTANCE)));
        }).toList();
        var fields = new LinkedHashMap<>(base.fields()); fields.put("tasks", new DataValue.ListValue(rows));
        return new DataValue.RecordValue(fields);
    }
    public static TerminalStatusView decode(DataValue.RecordValue value) {
        LocalReferences.portable(value);
        boolean tasks = value.fields().containsKey("tasks");
        var keys=new HashSet<>(Set.of("terminal","memory","output","phase","paused"));
        if (tasks) keys.add("tasks");
        boolean keyboard=value.fields().containsKey("keyboard");
        if (keyboard) { keys.add("keyboard");if (!new DataValue.BooleanValue(true).equals(value.field("keyboard"))) throw new IllegalArgumentException("Invalid keyboard flag"); }
        var question = Optional.<Question>empty();
        if (value.fields().containsKey("question")) {
            keys.add("question");
            if (!(value.field("question") instanceof DataValue.RecordValue prompt)
                || !prompt.fields().keySet().equals(Set.of("token", "text"))
                || !(prompt.field("token") instanceof DataValue.TextValue token) || !(prompt.field("text") instanceof DataValue.TextValue text))
                throw new IllegalArgumentException("Invalid terminal question");
            question = Optional.of(new Question(token.value(), text.value()));
        }
        if (!value.fields().keySet().equals(keys)
            || tasks && !(value.field("tasks") instanceof DataValue.ListValue)
            || !(value.field("output") instanceof DataValue.RecordValue || value.field("output") == DataValue.NullValue.INSTANCE)
            || !(value.field("memory") instanceof DataValue.RecordValue) || !(value.field("phase") instanceof DataValue.TextValue phase)
            || !(value.field("paused") instanceof DataValue.BooleanValue paused)) throw new IllegalArgumentException("Invalid terminal observation");
        var roots = new LinkedHashMap<String, DataValue>(); roots.put("memory", value.field("memory")); roots.put("output", value.field("output"));
        if (tasks) roots.put("tasks", value.field("tasks"));
        return new TerminalStatusView(value.field("terminal") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(TerminalCodec.decode(value.field("terminal"))),
            new DataValue.RecordValue(roots), phase.value(), paused.value(),keyboard,question);
    }
}
