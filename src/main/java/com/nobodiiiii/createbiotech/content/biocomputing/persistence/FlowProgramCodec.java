package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockRules;
import java.util.*;

/** One instruction representation shared by installed continuations and player documents. */
public final class FlowProgramCodec {
    private FlowProgramCodec() {}
    public static DataValue encode(FlowProgram program) {
        var value = obj("inputs", TypeArchiveCodec.encode(new DataType.RecordType(program.inputs())),
            "steps", sequence(program.steps(), FlowProgramCodec::step), "expressions", ProgramArchiveCodec.encode(program.expressions()));
        if (program.execution() == FlowProgram.Execution.LEGACY_GRAPH) return value;
        var fields = new LinkedHashMap<>(value.fields()); fields.put("execution", new DataValue.TextValue("register_v1"));
        if (program.compilation()!=FlowProgram.Compilation.ORIGINAL)
            fields.put("compilation",new DataValue.TextValue(program.compilation().token()));
        return new DataValue.RecordValue(fields);
    }
    public static FlowProgram decode(DataValue data) {
        boolean assembly = fields(data).containsKey("execution");
        boolean specialized=fields(data).containsKey("compilation");
        if (assembly) { 
            if (specialized) exact(data,"inputs","steps","expressions","execution","compilation");
            else exact(data,"inputs","steps","expressions","execution");
            if (!string(at(data, "execution")).equals("register_v1")) throw ArchiveException.invalid("Unknown flow execution format");
        } else exact(data, "inputs", "steps", "expressions");
        FlowProgram.Compilation compilation=FlowProgram.Compilation.ORIGINAL;
        if (specialized) try { compilation=FlowProgram.Compilation.parse(string(at(data,"compilation"))); }
        catch (IllegalArgumentException invalid) { throw ArchiveException.invalid(invalid.getMessage()); }
        return new FlowProgram(recordType(at(data, "inputs")).fields(), list(at(data, "steps")).stream().map(FlowProgramCodec::readStep).toList(),
            ProgramArchiveCodec.decode(at(data, "expressions")), assembly ? FlowProgram.Execution.REGISTER_V1 : FlowProgram.Execution.LEGACY_GRAPH, compilation);
    }
    private static DataValue step(FlowProgram.Step step) {
        return switch (step) {
            case FlowProgram.Reference ref -> {var f=new LinkedHashMap<>(obj("kind","reference","source",ref.sourceId(),"operation",ref.kind().name().toLowerCase(Locale.ROOT),"target",ref.target(),"local",ref.local()).fields());ref.value().ifPresent(v->f.put("value",input(v)));yield new DataValue.RecordValue(f);}
            case FlowProgram.Shared shared -> shared.listResult().<DataValue>map(result ->
                obj("kind", "public_list_edit", "source", shared.sourceId(), "cell", shared.cell(), "value", input(shared.value()), "result", result))
                .orElseGet(() -> obj("kind", "shared", "source", shared.sourceId(), "cell", shared.cell(), "value", input(shared.value()), "add", shared.add()));
            case FlowProgram.Atomic atomic -> obj("kind", "atomic", "source", atomic.sourceId(), "result", atomic.result());
            case FlowProgram.EndAtomic end -> obj("kind", "end_atomic", "source", end.sourceId());
            case FlowProgram.Memory memory -> obj("kind", "memory", "source", memory.sourceId(), "operation", memory.kind().name().toLowerCase(Locale.ROOT),
                "cell", memory.cell(), "local", memory.local());
            case FlowProgram.Procedure procedure -> {
                var value = obj("kind", "procedure", "source", procedure.sourceId(), "name", procedure.key().id(), "revision", procedure.key().revision(),
                    "inputs", TypeArchiveCodec.encode(new DataType.RecordType(procedure.inputs())), "outputs", TypeArchiveCodec.encode(new DataType.RecordType(procedure.outputs())), "returns", names(procedure.returns()));
                if (procedure.rules().equals(FlowBlockRules.NONE) && procedure.assembly().isEmpty() && procedure.event().isEmpty() && procedure.terminalKey().isEmpty() && !procedure.startup()) yield value; // Preserve old declarations byte-for-byte.
                var fields = new LinkedHashMap<>(value.fields());
                procedure.terminalKey().ifPresent(key->fields.put("terminal_key",new DataValue.TextValue(key)));
                if (procedure.startup()) fields.put("startup",new DataValue.BooleanValue(true));
                procedure.event().ifPresent(channel -> fields.put("event",new DataValue.TextValue(channel)));
                procedure.assembly().ifPresent(source -> fields.put("assembly", obj("assembly_version", source.version(), "source", source.text())));
                if (!procedure.rules().equals(FlowBlockRules.NONE)) fields.put("rules", obj("version", 1, "forbid_inside", sequence(procedure.rules().forbidInside().stream().sorted().toList(), DataValue.TextValue::new),
                    "forbid_contexts", sequence(procedure.rules().forbidContexts().stream().sorted().toList(), DataValue.TextValue::new)));
                yield new DataValue.RecordValue(fields);
            }
            case FlowProgram.Monitor m -> obj("kind", m.privateLocal() ? "monitor_private" : "monitor", "source", m.sourceId(), "cell", m.cell(), "visible", new DataValue.BooleanValue(m.visible()));
            case FlowProgram.Broadcast event -> obj("kind", event.software()?"broadcast_sw_v1":"broadcast", "source", event.sourceId(), "channel", event.channel(), "wait", event.await());
            case FlowProgram.EndProcedure end -> obj("kind", "end_procedure", "source", end.sourceId());
            case FlowProgram.TaskJoin join -> obj("kind", "task_join", "source", join.sourceId(), "name", join.procedure().id(), "revision", join.procedure().revision(),
                "handle", join.handle(), "outcome", join.outcome());
            case FlowProgram.StopScript stop -> stop.mode() == FlowProgram.StopMode.CURRENT
                ? obj("kind", "stop_script", "source", stop.sourceId())
                : obj("kind", "stop_group", "source", stop.sourceId(), "scope", stop.mode() == FlowProgram.StopMode.ALL ? "all" : "others");
            case FlowProgram.WaitUntil wait -> obj("kind", "wait_until", "source", wait.sourceId(), "condition", input(wait.condition()));
            case FlowProgram.Watch watch -> obj("kind", "watch", "source", watch.sourceId(), "condition", input(watch.condition()),
                "handler", watch.handler().map(GraphArchiveCodec::key).orElse(DataValue.NullValue.INSTANCE));
            case FlowProgram.TryLaunch launch -> obj("kind", "task_try_launch", "source", launch.sourceId(), "name",
                launch.procedure().id(), "revision", launch.procedure().revision(), "started", launch.started());
            case FlowProgram.TaskStart launch -> obj("kind", "task_start", "source", launch.sourceId(), "name", launch.procedure().id(), "revision", launch.procedure().revision(),
                "arguments", names(launch.arguments()), "handle", launch.handle());
            case FlowProgram.Invoke call -> call.operands().<DataValue>map(operands -> obj("kind", "invoke_operands", "source", call.sourceId(),
                "name", call.procedure().id(), "revision", call.procedure().revision(), "entry", operands.entry(),
                "inputs", names(operands.inputs()), "returns", names(call.bindings().returns())))
                .orElseGet(() -> obj("kind", "invoke", "source", call.sourceId(), "name", call.procedure().id(), "revision", call.procedure().revision(),
                    "arguments", names(call.bindings().arguments()), "returns", names(call.bindings().returns())));
            case FlowProgram.Correlation tag -> obj("kind", "correlation", "source", tag.sourceId(), "local", tag.local());
            case FlowProgram.SerialSend send -> obj("kind", "serial_send", "source", send.sourceId(), "port", send.port(), "local", send.local(), "result", send.result());
            case FlowProgram.SerialReceive receive -> obj("kind", receive.nativeNbt() ? "serial_receive_nbt" : "serial_receive", "source", receive.sourceId(), "port", receive.port(), "local", receive.local());
            case FlowProgram.SerialStatus status -> obj("kind", status.claimReceiveFaults() ? "serial_claim_faults" : "serial_status", "source", status.sourceId(), "port", status.port(), "local", status.local());
            case FlowProgram.Read read -> result(obj("kind", "read", "source", read.sourceId(), "port", read.port(),
                "local", read.local(), "type", TypeArchiveCodec.encode(read.type())), read.result() ? Optional.of(new DataValue.BooleanValue(true)) : Optional.empty());
            case FlowProgram.Write write -> result(obj("kind", "write", "source", write.sourceId(), "port", write.port(), "local", write.local()), write.result().map(DataValue.TextValue::new));
            case FlowProgram.AwaitReadable wait -> result(obj("kind", "readable", "source", wait.sourceId(), "port", wait.port()), wait.result().map(DataValue.TextValue::new));
            case FlowProgram.TimerReset reset -> obj("kind", "timer_reset", "source", reset.sourceId());
            case FlowProgram.Ask ask -> obj("kind", "ask", "source", ask.sourceId(), "question", input(ask.question()));
            case FlowProgram.SignalRead read -> obj("kind", "signal_read", "source", read.sourceId(), "port", read.port(), "local", read.local());
            case FlowProgram.XBusStatus status -> obj("kind", "xbus_status", "source", status.sourceId(), "port", status.port(), "local", status.local());
            case FlowProgram.SignalWrite write -> obj("kind", "signal_write", "source", write.sourceId(), "port", write.port(), "local", write.local());
            case FlowProgram.Sleep sleep -> sleep.unit().isPresent()
                ? obj("kind", "wait_duration", "source", sleep.sourceId(), "amount", input(sleep.ticks()), "unit",
                    sleep.unit().orElseThrow().name().toLowerCase(java.util.Locale.ROOT))
                : sleep.ticks() instanceof FlowInput.Local local
                    ? obj("kind", "sleep", "source", sleep.sourceId(), "ticks", local.name())
                    : obj("kind", "sleep_value", "source", sleep.sourceId(), "ticks", input(sleep.ticks()));
            case FlowProgram.Evaluate evaluate -> evaluate.bindings().<DataValue>map(bindings -> obj("kind", "evaluate_bound", "source", evaluate.sourceId(),
                "expression", evaluate.expression(), "arguments", names(bindings.arguments()), "returns", names(bindings.returns())))
                .orElseGet(() -> obj("kind", "evaluate", "source", evaluate.sourceId(), "expression", evaluate.expression()));
            case FlowProgram.If branch -> branch.condition() instanceof FlowInput.Local local
                ? obj("kind", "if", "source", branch.sourceId(), "condition", local.name())
                : obj("kind", "if_input", "source", branch.sourceId(), "condition", input(branch.condition()));
            case FlowProgram.Otherwise branch -> obj("kind", "otherwise", "source", branch.sourceId());
            case FlowProgram.EndIf branch -> obj("kind", "end_if", "source", branch.sourceId());
            case FlowProgram.Forever loop -> obj("kind", "forever", "source", loop.sourceId());
            case FlowProgram.EndForever loop -> obj("kind", "end_forever", "source", loop.sourceId());
            case FlowProgram.Repeat loop -> obj("kind", "repeat", "source", loop.sourceId(), "count",
                loop.count() instanceof FlowInput.Local local ? new DataValue.TextValue(local.name()) : input(loop.count()));
            case FlowProgram.EndRepeat loop -> obj("kind", "end_repeat", "source", loop.sourceId());
            case FlowProgram.Until loop -> obj("kind", "until", "source", loop.sourceId(), "condition", input(loop.condition()));
            case FlowProgram.EndUntil loop -> obj("kind", "end_until", "source", loop.sourceId());
        };
    }
    public static FlowProgram.Step copyStep(FlowProgram.Step original, String source) {
        return readStep(step(original), source);
    }
    private static FlowProgram.Step readStep(DataValue data) {
        return readStep(data, string(at(data, "source")));
    }
    private static FlowProgram.Step readStep(DataValue data, String source) {
        return switch (string(at(data, "kind"))) {
            case "reference" -> {boolean operand=fields(data).containsKey("value");if(operand)exact(data,"kind","source","operation","target","local","value");else exact(data,"kind","source","operation","target","local");yield new FlowProgram.Reference(source,FlowProgram.ReferenceKind.valueOf(string(at(data,"operation")).toUpperCase(Locale.ROOT)),string(at(data,"target")),string(at(data,"local")),operand?Optional.of(readInput(at(data,"value"))):Optional.empty());}
            case "atomic" -> { exact(data, "kind", "source", "result"); yield new FlowProgram.Atomic(source, string(at(data, "result"))); }
            case "end_atomic" -> { exact(data, "kind", "source"); yield new FlowProgram.EndAtomic(source); }
            case "public_list_edit" -> {
                exact(data, "kind", "source", "cell", "value", "result");
                yield new FlowProgram.Shared(source, string(at(data, "cell")), readInput(at(data, "value")), false,
                    Optional.of(string(at(data, "result"))));
            }
            case "shared" -> {
                exact(data, "kind", "source", "cell", "value", "add");
                yield new FlowProgram.Shared(source, string(at(data, "cell")), readInput(at(data, "value")), bool(at(data, "add")));
            }
            case "memory" -> {
                exact(data, "kind", "source", "operation", "cell", "local");
                var kind = FlowProgram.MemoryKind.valueOf(string(at(data, "operation")).toUpperCase(Locale.ROOT));
                if (!string(at(data, "operation")).equals(kind.name().toLowerCase(Locale.ROOT))) throw ArchiveException.invalid("Invalid memory operation");
                yield new FlowProgram.Memory(source, kind, string(at(data, "cell")), string(at(data, "local")));
            }
            case "xbus_status" -> {
                exact(data, "kind", "source", "port", "local");
                yield new FlowProgram.XBusStatus(source, string(at(data, "port")), string(at(data, "local")));
            }
            case "procedure" -> {
                boolean hasKey = fields(data).containsKey("terminal_key");
                boolean startup = fields(data).containsKey("startup");
                boolean hasEvent = fields(data).containsKey("event");
                boolean hasRules = fields(data).containsKey("rules");
                boolean hasAssembly = fields(data).containsKey("assembly");
                var expected = new HashSet<>(Set.of("kind","source","name","revision","inputs","outputs","returns"));
                if (hasKey) expected.add("terminal_key");
                if (startup) { expected.add("startup"); if (!bool(at(data,"startup"))) throw ArchiveException.invalid("Startup hat flag must be true"); }
                if (hasEvent) expected.add("event");
                if (hasRules) expected.add("rules"); if (hasAssembly) expected.add("assembly"); exact(data,expected.toArray(String[]::new));
                Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource> assembly = Optional.empty();
                if (hasAssembly) { var nativeSource=at(data,"assembly"); // The numeric test-era v1 header described a different language.
                    if (fields(nativeSource).containsKey("version"))
                        throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Pre-standard assembler header");
                    exact(nativeSource,"assembly_version","source");
                    if(!com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.supported(number(at(nativeSource,"assembly_version"))))throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION,"Unknown assembler version");
                    assembly=Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource((int)number(at(nativeSource,"assembly_version")),string(at(nativeSource,"source")))); }

                yield new FlowProgram.Procedure(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))),
                    recordType(at(data, "inputs")).fields(), recordType(at(data, "outputs")).fields(), readNames(at(data, "returns")), hasRules ? readRules(at(data, "rules")) : FlowBlockRules.NONE, assembly, hasEvent ? Optional.of(string(at(data,"event"))) : Optional.empty(),hasKey?Optional.of(string(at(data,"terminal_key"))):Optional.empty(),startup);
            }
            case "end_procedure" -> { exact(data, "kind", "source"); yield new FlowProgram.EndProcedure(source); }
            case "monitor", "monitor_private" -> { exact(data, "kind", "source", "cell", "visible"); yield new FlowProgram.Monitor(source, string(at(data, "cell")), bool(at(data, "visible")), string(at(data, "kind")).equals("monitor_private")); }
            case "broadcast", "broadcast_sw_v1" -> {
                exact(data,"kind","source","channel","wait");
                yield new FlowProgram.Broadcast(source,string(at(data,"channel")),bool(at(data,"wait")),string(at(data,"kind")).equals("broadcast_sw_v1"));
            }
            case "task_join" -> {
                exact(data, "kind", "source", "name", "revision", "handle", "outcome");
                yield new FlowProgram.TaskJoin(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))), string(at(data, "handle")), string(at(data, "outcome")));
            }
            case "stop_script" -> { exact(data, "kind", "source"); yield new FlowProgram.StopScript(source); }
            case "stop_group" -> {
                exact(data, "kind", "source", "scope");
                var mode = switch (string(at(data, "scope"))) {
                    case "all" -> FlowProgram.StopMode.ALL;
                    case "others" -> FlowProgram.StopMode.OTHERS;
                    default -> throw ArchiveException.invalid("Invalid local stop scope");
                };
                yield new FlowProgram.StopScript(source, mode);
            }
            case "wait_until" -> { exact(data, "kind", "source", "condition"); yield new FlowProgram.WaitUntil(source, readInput(at(data, "condition"))); }
            case "watch" -> {
                exact(data, "kind", "source", "condition", "handler");
                var handler = at(data, "handler");
                yield new FlowProgram.Watch(source, handler == DataValue.NullValue.INSTANCE ? Optional.empty()
                    : Optional.of(GraphArchiveCodec.readKey(handler)), readInput(at(data, "condition")));
            }
            case "task_try_launch" -> {
                exact(data, "kind", "source", "name", "revision", "started");
                yield new FlowProgram.TryLaunch(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))),
                    string(at(data, "started")));
            }
            case "task_start" -> {
                exact(data, "kind", "source", "name", "revision", "arguments", "handle");
                yield new FlowProgram.TaskStart(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))), readNames(at(data, "arguments")), string(at(data, "handle")));
            }
            case "invoke_operands" -> {
                exact(data, "kind", "source", "name", "revision", "entry", "inputs", "returns");
                yield new FlowProgram.Invoke(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))),
                    new FlowCallBindings(Map.of(), readNames(at(data, "returns"))),
                    Optional.of(new FlowProgram.InvokeOperands(string(at(data, "entry")), readNames(at(data, "inputs")))));
            }
            case "invoke" -> {
                exact(data, "kind", "source", "name", "revision", "arguments", "returns");
                yield new FlowProgram.Invoke(source, new ModuleDefinition.Key(string(at(data, "name")), number(at(data, "revision"))),
                    new FlowCallBindings(readNames(at(data, "arguments")), readNames(at(data, "returns"))));
            }
            case "serial_claim_faults" -> {
                exact(data, "kind", "source", "port", "local");
                yield new FlowProgram.SerialStatus(source, string(at(data, "port")), string(at(data, "local")), true);
            }
            case "serial_receive_nbt" -> {
                exact(data, "kind", "source", "port", "local");
                yield new FlowProgram.SerialReceive(source, string(at(data, "port")), string(at(data, "local")), true);
            }
            case "correlation" -> {
                exact(data, "kind", "source", "local");
                yield new FlowProgram.Correlation(source, string(at(data, "local")));
            }
            case "serial_send" -> {
                exact(data, "kind", "source", "port", "local", "result");
                yield new FlowProgram.SerialSend(source, string(at(data, "port")), string(at(data, "local")), string(at(data, "result")));
            }
            case "serial_receive", "serial_status" -> {
                exact(data, "kind", "source", "port", "local");
                yield string(at(data, "kind")).equals("serial_receive") ? new FlowProgram.SerialReceive(source, string(at(data, "port")), string(at(data, "local")))
                    : new FlowProgram.SerialStatus(source, string(at(data, "port")), string(at(data, "local")));
            }
            case "if_input" -> {
                exact(data, "kind", "source", "condition"); var input = readInput(at(data, "condition"));
                if (input instanceof FlowInput.Local) throw ArchiveException.invalid("Named If inputs use the legacy text form");
                yield new FlowProgram.If(source, input);
            }
            case "until" -> {
                exact(data, "kind", "source", "condition");
                yield new FlowProgram.Until(source, readInput(at(data, "condition")));
            }
            case "end_until" -> { exact(data, "kind", "source"); yield new FlowProgram.EndUntil(source); }
            case "repeat" -> {
                exact(data, "kind", "source", "count");
                var count = at(data, "count");
                if (count instanceof DataValue.TextValue text) yield new FlowProgram.Repeat(source, text.value());
                var input = readInput(count);
                if (input instanceof FlowInput.Local) throw ArchiveException.invalid("Named repeat inputs use the legacy text form");
                yield new FlowProgram.Repeat(source, input);
            }
            case "end_repeat" -> { exact(data, "kind", "source"); yield new FlowProgram.EndRepeat(source); }
            case "forever", "end_forever" -> {
                exact(data, "kind", "source");
                yield string(at(data, "kind")).equals("forever") ? new FlowProgram.Forever(source) : new FlowProgram.EndForever(source);
            }
            case "timer_reset" -> { exact(data, "kind", "source"); yield new FlowProgram.TimerReset(source); }
            case "ask" -> { exact(data, "kind", "source", "question"); yield new FlowProgram.Ask(source, readInput(at(data, "question"))); }
            case "signal_read", "signal_write" -> {
                exact(data, "kind", "source", "port", "local");
                String port = string(at(data, "port")), local = string(at(data, "local"));
                yield string(at(data, "kind")).equals("signal_read") ? new FlowProgram.SignalRead(source, port, local) : new FlowProgram.SignalWrite(source, port, local);
            }
            case "sleep" -> {
                exact(data, "kind", "source", "ticks");
                yield new FlowProgram.Sleep(source, string(at(data, "ticks")));
            }
            case "sleep_value" -> {
                exact(data, "kind", "source", "ticks");
                var ticks = readInput(at(data, "ticks"));
                if (ticks instanceof FlowInput.Local) throw ArchiveException.invalid("Named Sleep inputs use the legacy text form");
                yield new FlowProgram.Sleep(source, ticks);
            }
            case "wait_duration" -> {
                exact(data, "kind", "source", "amount", "unit");
                var unit = switch (string(at(data, "unit"))) {
                    case "ticks" -> FlowProgram.WaitUnit.TICKS;
                    case "seconds" -> FlowProgram.WaitUnit.SECONDS;
                    case "minutes" -> FlowProgram.WaitUnit.MINUTES;
                    default -> throw ArchiveException.invalid("Unknown wait unit");
                };
                yield new FlowProgram.Sleep(source, readInput(at(data, "amount")), java.util.Optional.of(unit));
            }
            case "if" -> {
                exact(data, "kind", "source", "condition");
                yield new FlowProgram.If(source, string(at(data, "condition")));
            }
            case "otherwise", "end_if" -> {
                exact(data, "kind", "source");
                yield string(at(data, "kind")).equals("otherwise") ? new FlowProgram.Otherwise(source) : new FlowProgram.EndIf(source);
            }
            case "read" -> {
                boolean result = fields(data).containsKey("result");
                if (result) { exact(data, "kind", "source", "port", "local", "type", "result");
                    if (!bool(at(data, "result"))) throw ArchiveException.invalid("Result mode must be explicit true");
                } else exact(data, "kind", "source", "port", "local", "type");
                yield new FlowProgram.Read(source, string(at(data, "port")), string(at(data, "local")), TypeArchiveCodec.decode(at(data, "type")), result);
            }
            case "write" -> {
                boolean result = fields(data).containsKey("result");
                if (result) exact(data, "kind", "source", "port", "local", "result"); else exact(data, "kind", "source", "port", "local");
                yield new FlowProgram.Write(source, string(at(data, "port")), string(at(data, "local")), result ? Optional.of(string(at(data, "result"))) : Optional.empty());
            }
            case "readable" -> {
                boolean result = fields(data).containsKey("result");
                if (result) exact(data, "kind", "source", "port", "result"); else exact(data, "kind", "source", "port");
                yield new FlowProgram.AwaitReadable(source, string(at(data, "port")), result ? Optional.of(string(at(data, "result"))) : Optional.empty());
            }
            case "evaluate_bound" -> {
                exact(data, "kind", "source", "expression", "arguments", "returns");
                yield new FlowProgram.Evaluate(source, string(at(data, "expression")), new FlowCallBindings(readNames(at(data, "arguments")), readNames(at(data, "returns"))));
            }
            case "evaluate" -> {
                exact(data, "kind", "source", "expression");
                yield new FlowProgram.Evaluate(source, string(at(data, "expression")));
            }
            default -> throw ArchiveException.invalid("Unknown control instruction");
        };
    }

    public static DataValue control(Map<String, DataType> inputs, List<FlowProgram.Step> steps) {
        return obj("inputs", TypeArchiveCodec.encode(new DataType.RecordType(inputs)), "steps", sequence(steps, FlowProgramCodec::step));
    }
    private static DataValue.RecordValue names(Map<String, String> values) {
        var result = new LinkedHashMap<String, DataValue>(); values.forEach((key, value) -> result.put(key, new DataValue.TextValue(value)));
        return new DataValue.RecordValue(result);
    }
    private static Map<String, String> readNames(DataValue value) {
        var result = new LinkedHashMap<String, String>(); fields(value).forEach((key, name) -> result.put(key, string(name))); return result;
    }
    private static DataValue result(DataValue.RecordValue step, Optional<? extends DataValue> result) {
        if (result.isEmpty()) return step;
        var fields = new LinkedHashMap<>(step.fields()); fields.put("result", result.orElseThrow());
        return new DataValue.RecordValue(fields);
    }
    private static DataValue input(FlowInput input) {
        return switch (input) {
            case FlowInput.Literal literal -> obj("kind", "literal", "value", literal.value());
            case FlowInput.Local local -> obj("kind", "local", "name", local.name());
            case FlowInput.Expression expression -> expression.arguments().<DataValue>map(arguments -> obj("kind", "expression_bound",
                "entry", expression.entry(), "output", expression.output(), "arguments", names(arguments)))
                .orElseGet(() -> obj("kind", "expression", "entry", expression.entry(), "output", expression.output()));
        };
    }
    private static FlowInput readInput(DataValue data) {
        return switch (string(at(data, "kind"))) {
            case "literal" -> { exact(data, "kind", "value"); yield new FlowInput.Literal(at(data, "value")); }
            case "local" -> { exact(data, "kind", "name"); yield new FlowInput.Local(string(at(data, "name"))); }
            case "expression_bound" -> {
                exact(data, "kind", "entry", "output", "arguments");
                yield new FlowInput.Expression(string(at(data, "entry")), string(at(data, "output")), readNames(at(data, "arguments")));
            }
            case "expression" -> {
                exact(data, "kind", "entry", "output");
                yield new FlowInput.Expression(string(at(data, "entry")), string(at(data, "output")));
            }
            default -> throw ArchiveException.invalid("Unknown flow input source");
        };
    }
    public static FlowProgram readControl(DataValue value, ProgramArchive expressions) {
        exact(value, "inputs", "steps");
        return new FlowProgram(recordType(at(value, "inputs")).fields(), list(at(value, "steps")).stream().map(FlowProgramCodec::readStep).toList(), expressions);
    }
    private static DataType.RecordType recordType(DataValue data) {
        var type = TypeArchiveCodec.decode(data);
        if (type instanceof DataType.RecordType record) return record;
        throw ArchiveException.invalid("Expected a record type");
    }
    private static FlowBlockRules readRules(DataValue data) {
        exact(data, "version", "forbid_inside", "forbid_contexts");
        if (number(at(data, "version")) != 1) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unknown block rules version");
        return new FlowBlockRules(ruleNames(at(data, "forbid_inside")), ruleNames(at(data, "forbid_contexts")));
    }
    private static Set<String> ruleNames(DataValue data) {
        var result = new LinkedHashSet<String>();
        for (var value : list(data)) if (!result.add(string(value))) throw ArchiveException.invalid("Duplicate block rule selector");
        return result;
    }
    private static void exact(DataValue data, String... names) {
        if (!fields(data).keySet().equals(Set.of(names))) throw ArchiveException.invalid("Unexpected flow archive fields");
    }
}
