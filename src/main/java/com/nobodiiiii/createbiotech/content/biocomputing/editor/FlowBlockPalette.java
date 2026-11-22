package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Editor prototypes, not installed instructions or a second execution registry. */
final class FlowBlockPalette {
    enum Kind { MONITOR_SHOW, MONITOR_HIDE, STARTUP, WATCH, TERMINAL_KEY, RECEIVE_BROADCAST, BROADCAST, BROADCAST_WAIT, REF_ADDRESS,REF_READ,REF_WRITE,REF_READONLY,REF_SAME, READ, WRITE, READABLE, XBUS_STATUS, EVALUATE, ASSIGN, PUBLIC_SET, PUBLIC_ADD, IF, IF_ELSE, OTHERWISE, END_IF, SLEEP, WAIT_UNTIL, STOP_SCRIPT, SIGNAL_READ, TIMER_RESET, ASK, SIGNAL_WRITE, FOREVER, END_FOREVER, REPEAT, END_REPEAT, UNTIL, END_UNTIL, SERIAL_SEND, SERIAL_RECEIVE, SERIAL_STATUS, CORRELATION, MEMORY_BEGIN, MEMORY_READ, MEMORY_WRITE, MEMORY_COMMIT, MEMORY_ROLLBACK, ATOMIC, END_ATOMIC, LIST_APPEND_LOCAL, LIST_INSERT_LOCAL, LIST_REMOVE_LOCAL, LIST_REPLACE_LOCAL, LIST_CLEAR }
    enum Category {
        EVENTS(Kind.STARTUP,Kind.WATCH,Kind.TERMINAL_KEY,Kind.RECEIVE_BROADCAST,Kind.BROADCAST,Kind.BROADCAST_WAIT), CONTROL(Kind.REPEAT, Kind.UNTIL, Kind.FOREVER, Kind.IF, Kind.IF_ELSE, Kind.SLEEP, Kind.WAIT_UNTIL, Kind.STOP_SCRIPT),
        OPERATORS(), VARIABLES(Kind.MONITOR_SHOW, Kind.MONITOR_HIDE, Kind.ASSIGN, Kind.PUBLIC_SET, Kind.PUBLIC_ADD,Kind.REF_ADDRESS,Kind.REF_READ,Kind.REF_WRITE,Kind.REF_READONLY,Kind.REF_SAME),
        STORAGE(Kind.ATOMIC, Kind.MEMORY_READ, Kind.MEMORY_WRITE, Kind.MEMORY_BEGIN, Kind.MEMORY_COMMIT, Kind.MEMORY_ROLLBACK), DATA(Kind.LIST_APPEND_LOCAL, Kind.LIST_INSERT_LOCAL, Kind.LIST_REMOVE_LOCAL, Kind.LIST_REPLACE_LOCAL, Kind.LIST_CLEAR), READERS(),
        COMMUNICATION(Kind.READ, Kind.WRITE, Kind.READABLE, Kind.XBUS_STATUS, Kind.SIGNAL_READ, Kind.SIGNAL_WRITE,
            Kind.SERIAL_SEND, Kind.SERIAL_RECEIVE, Kind.SERIAL_STATUS, Kind.CORRELATION),
        OUTPUT(), SENSING(Kind.ASK, Kind.TIMER_RESET), CUSTOM(Kind.EVALUATE), EXTENSIONS();
        final List<Kind> kinds;
        Category(Kind... kinds) { this.kinds = List.of(kinds); }
    }
    static List<FlowProgram.Step> prototype(Kind kind, FlowDraft draft, ProgramWorkspace workspace, List<String> xbus, List<String> signals) {
        var ids = new HashSet<String>(); draft.allSteps().forEach(step -> ids.add(step.sourceId()));
        String id; int i = 1;
        do { id = "block_" + i++; } while (ids.contains(id) || ids.contains(id + "_condition") || ids.contains(id + "_end") || ids.contains(id + "_else"));
        String value = id + "_value", pin = xbus.isEmpty() ? "x0" : xbus.getFirst(), signal = signals.isEmpty() ? "p0" : signals.getFirst();
        return switch (kind) {
            case WATCH -> List.of(
                new FlowProgram.Procedure(id, new ModuleDefinition.Key("monitor_" + UUID.randomUUID(), 1), Map.of(), Map.of(), Map.of()),
                new FlowProgram.Watch(id + "_condition", Optional.empty(), new FlowInput.Literal(new DataValue.BooleanValue(false))),
                new FlowProgram.EndProcedure(id + "_end"));
            case STARTUP -> List.of(FlowProgram.Procedure.startupHat(id,new ModuleDefinition.Key("startup_"+UUID.randomUUID(),1)),new FlowProgram.EndProcedure(id+"_end"));
            case TERMINAL_KEY -> List.of(FlowProgram.Procedure.keyHat(id,new ModuleDefinition.Key("terminal_key_"+UUID.randomUUID(),1),"space"),new FlowProgram.EndProcedure(id+"_end"));
            case RECEIVE_BROADCAST -> List.of(new FlowProgram.Procedure(id,
                new ModuleDefinition.Key("broadcast_receiver_" + UUID.randomUUID(),1),Map.of(),Map.of(),Map.of(),
                FlowBlockRules.NONE,Optional.empty(),Optional.of(defaultEventChannel(draft))),new FlowProgram.EndProcedure(id+"_end"));
            case BROADCAST,BROADCAST_WAIT -> List.of(new FlowProgram.Broadcast(id,defaultEventChannel(draft),kind==Kind.BROADCAST_WAIT));
            case REF_ADDRESS,REF_READ,REF_WRITE,REF_READONLY,REF_SAME -> {
                var operation=FlowProgram.ReferenceKind.valueOf(kind.name().substring(4));
                String target=kind==Kind.REF_ADDRESS?workspace.root().cells().keySet().stream().sorted().findFirst().orElse("variable"):"reference";
                yield List.of(new FlowProgram.Reference(id,operation,target,kind==Kind.REF_WRITE?"":value,kind==Kind.REF_WRITE||kind==Kind.REF_SAME?Optional.of(new FlowInput.Local("value")):Optional.empty()));
            }
            case PUBLIC_SET, PUBLIC_ADD -> {
                String cell = workspace.root().cells().keySet().stream().sorted().findFirst().orElse("variable");
                yield List.of(new FlowProgram.Shared(id, cell, publicDefault(workspace, cell, kind == Kind.PUBLIC_ADD), kind == Kind.PUBLIC_ADD));
            }
            case ATOMIC -> List.of(new FlowProgram.Atomic(id, value), new FlowProgram.EndAtomic(id + "_end"));
            case MEMORY_BEGIN, MEMORY_READ, MEMORY_WRITE, MEMORY_COMMIT, MEMORY_ROLLBACK -> {
                var operation = FlowProgram.MemoryKind.valueOf(kind.name().substring("MEMORY_".length()));
                String cell = operation == FlowProgram.MemoryKind.READ || operation == FlowProgram.MemoryKind.WRITE
                    ? workspace.root().cells().keySet().stream().sorted().findFirst().orElse("cell") : "";
                yield List.of(new FlowProgram.Memory(id, operation, cell,
                    operation == FlowProgram.MemoryKind.BEGIN || operation == FlowProgram.MemoryKind.ROLLBACK ? "" : value));
            }
            case REPEAT -> List.of(new FlowProgram.Repeat(id, new FlowInput.Literal(new DataValue.IntegerValue(10))), new FlowProgram.EndRepeat(id + "_end"));
            case UNTIL -> List.of(new FlowProgram.Until(id, new FlowInput.Literal(new DataValue.BooleanValue(false))), new FlowProgram.EndUntil(id + "_end"));
            case STOP_SCRIPT -> List.of(new FlowProgram.StopScript(id));
            case WAIT_UNTIL -> List.of(new FlowProgram.WaitUntil(id, new FlowInput.Literal(new DataValue.BooleanValue(false))));
            case FOREVER -> List.of(new FlowProgram.Forever(id), new FlowProgram.EndForever(id + "_end"));
            case IF, IF_ELSE -> FlowDraft.branch(id, new FlowInput.Literal(new DataValue.BooleanValue(false)), kind == Kind.IF_ELSE);
            case SLEEP -> List.of(workspace.flow().orElseThrow().execution() == FlowProgram.Execution.REGISTER_V1
                ? waitPrototype(id) : new FlowProgram.Sleep(id, "ticks"));
            case TIMER_RESET -> List.of(new FlowProgram.TimerReset(id));
            case ASK -> List.of(new FlowProgram.Ask(id, defaultQuestion()));
            case READ -> List.of(new FlowProgram.Read(id, pin, value, new DataType.RecordType(Map.of())));
            case WRITE -> List.of(new FlowProgram.Write(id, pin, "message"));
            case READABLE -> List.of(new FlowProgram.AwaitReadable(id, pin));
            case XBUS_STATUS -> List.of(new FlowProgram.XBusStatus(id, pin, value));
            case SIGNAL_READ -> List.of(new FlowProgram.SignalRead(id, signal, value));
            case SIGNAL_WRITE -> List.of(new FlowProgram.SignalWrite(id, signal, "signal"));
            // Missing bindings remain visible validation errors. Creation never silently binds same-name locals.
            case EVALUATE -> List.of(new FlowProgram.Evaluate(id, workspace.entryNames().stream().findFirst().orElse("expression"), new FlowCallBindings(Map.of(), Map.of())));
            case LIST_APPEND_LOCAL, LIST_INSERT_LOCAL, LIST_REMOVE_LOCAL, LIST_REPLACE_LOCAL, LIST_CLEAR -> throw new IllegalArgumentException("Named list requires an explicit typed target");
            case MONITOR_SHOW, MONITOR_HIDE -> List.of(new FlowProgram.Monitor(id,
                workspace.root().cells().keySet().stream().sorted().findFirst().orElseThrow(), kind == Kind.MONITOR_SHOW));
            case ASSIGN -> List.of(new FlowProgram.Evaluate(id, id, new FlowCallBindings(Map.of(), Map.of("value", value))));
            // This is an editable name, NOT an authorization or inferred bridge to an XBus pin.
            case SERIAL_SEND -> List.of(new FlowProgram.SerialSend(id, "s0", "message", value));
            case SERIAL_RECEIVE -> List.of(new FlowProgram.SerialReceive(id, "s0", value));
            case SERIAL_STATUS -> List.of(new FlowProgram.SerialStatus(id, "s0", value));
            case CORRELATION -> List.of(new FlowProgram.Correlation(id, value));
            default -> throw new IllegalArgumentException("Control delimiters belong to their header");
        };
    }
    static FlowProgram.Sleep waitPrototype(String id) {
        return new FlowProgram.Sleep(id, new FlowInput.Literal(new DataValue.IntegerValue(1)), Optional.of(FlowProgram.WaitUnit.SECONDS));
    }
    static String defaultEventChannel(FlowDraft draft) { return eventChannels(draft).stream().findFirst().orElse("message1"); }
    static FlowInput.Literal defaultQuestion() {
        return new FlowInput.Literal(new DataValue.TextValue("")); // An empty text socket is still editable.
    }
    static List<String> eventChannels(FlowDraft draft) {
        var names = new TreeSet<String>();
        for (var step : draft.allSteps()) {
            if (step instanceof FlowProgram.Procedure procedure) procedure.event().ifPresent(names::add);
            if (step instanceof FlowProgram.Broadcast event) names.add(event.channel());
        }
        return List.copyOf(names);
    }
    static FlowInput publicDefault(ProgramWorkspace workspace, String name, boolean add) {
        var cell = workspace.root().cells().get(name);
        DataValue initial = cell == null ? new DataValue.IntegerValue(0) : cell.initial();
        return new FlowInput.Literal(!add ? initial : initial instanceof DataValue.DecimalValue
            ? new DataValue.DecimalValue(1) : new DataValue.IntegerValue(1));
    }
}
