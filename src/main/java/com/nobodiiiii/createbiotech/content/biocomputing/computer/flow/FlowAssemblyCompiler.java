package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEdge;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEdgeSample;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterListEdit;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProcedure;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterAssignment;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemoryOp;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterNbt;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMonitor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEvent;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterTaskGroup;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterTimer;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterPrompt;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterReference;
import java.util.function.Function;

/** Lowers checked source to register addresses and explicit jumps. Never executes source graphs. */
final class FlowAssemblyCompiler {
    private final List<RegisterProgram.Code> code = new ArrayList<>();
    private final List<DataType> types = new ArrayList<>();
    private final List<DataValue> constants = new ArrayList<>();
    private Map<String, Integer> locals = new TreeMap<>();
    private final Map<String, Integer> inputs = new TreeMap<>();
    private record ProcedureScope(FlowProgram.Procedure definition, Map<String, Integer> inputs,
        Map<String, Integer> locals, Map<String, Integer> outputs) {}
    private final Map<Integer, ProcedureScope> procedures = new TreeMap<>();
    private final Map<Integer, Integer> procedureEnds = new HashMap<>();
    private final Map<Integer, Integer> relocations = new LinkedHashMap<>();
    private record RepeatLoop(int remaining, int one, int head) {}
    private final Map<Integer, RepeatLoop> repeats = new HashMap<>();
    private final FlowProgram source;
    private final List<FlowProgram.Step> steps;
    private final int[] originalPositions;
    private final Map<String, List<ModuleDefinition.Key>> eventReceivers = new HashMap<>();
    private final Map<String, ProgramCompiler.CompiledGraph> graphViews;
    private final FlowProgram.Installation checked;
    private final long codeLimit;
    private long emittedWords;
    private Optional<String> advancedTypeLocation = Optional.empty();
    private final Map<String,DataType> authoredTypes = new TreeMap<>();
    private void declared(String location, DataType type) {
        // Only an actual lowering plan may replace a top-level ANY with its billed software ABI.
        if (software != null && type == DataType.Scalar.ANY) return;
        authoredTypes.put(location,type);
    }
    private int cursor;
    private final List<Integer> sampleOutputs = new ArrayList<>();
    private FlowSoftwareAny.Plan software;
    private boolean compact;
    private final Set<Integer> carriers = new TreeSet<>();
    private static final DataType.RecordType CARRIER = com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.SoftwareAnyAbi.TYPE;
    static RegisterProgram compileSoftware(FlowProgram source, FlowProgram.Installation checked, long limit, FlowSoftwareAny.Plan plan) {
        var compiler=new FlowAssemblyCompiler(source,checked,limit,source.steps(),null,Map.of());
        compiler.software=plan; return compiler.compile();
    }
    private DataType logical(int r) { return carriers.contains(r)?DataType.Scalar.ANY:types.get(r); }

    private FlowAssemblyCompiler(FlowProgram source, FlowProgram.Installation checked, long codeLimit,
            List<FlowProgram.Step> steps, int[] originalPositions, Map<String, ProgramCompiler.CompiledGraph> graphViews) {
        this.source = source; this.checked = checked; this.codeLimit = codeLimit;
        this.steps = steps; this.originalPositions = originalPositions; this.graphViews = graphViews;
        for (var step : source.steps()) if (step instanceof FlowProgram.Procedure definition)
            definition.event().ifPresent(channel -> eventReceivers.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(definition.key()));
    }
    static RegisterProgram compile(FlowProgram source, FlowProgram.Installation checked, long codeLimit) { return new FlowAssemblyCompiler(source, checked, codeLimit, source.steps(), null, Map.of()).compile(); }
    static RegisterProgram compile(FlowProgram source, FlowProgram.Installation checked, long codeLimit, boolean compact) {
        var compiler = new FlowAssemblyCompiler(source, checked, codeLimit, source.steps(), null, Map.of());
        compiler.compact = compact;
        return compiler.compile();
    }
    static RegisterProgram compileProjected(FlowProgram source, FlowProgram.Installation checked, long codeLimit,
            List<FlowProgram.Step> steps, int[] originalPositions, Map<String, ProgramCompiler.CompiledGraph> graphViews) {
        return new FlowAssemblyCompiler(source, checked, codeLimit, steps, originalPositions, graphViews).compile();
    }
    private int sourcePosition(int position) { return originalPositions == null ? position : originalPositions[position]; }
    private RegisterProgram compile() {
        source.inputs().forEach((name,type)->declared("input "+name,type));
        source.expressions().program().cells().forEach((name,cell)->declared("cell "+name,cell.type()));
        for(var step:steps) if(step instanceof FlowProgram.Procedure p && p.assembly().isEmpty()) {
            p.inputs().forEach((name,type)->declared(p.sourceId()+" input "+name,type));
            p.outputs().forEach((name,type)->declared(p.sourceId()+" output "+name,type));
        } else if(step instanceof FlowProgram.Read read) declared(read.sourceId()+" input",read.type());
        if (originalPositions != null) {
            if (source.inputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny))
                advancedTypeLocation = Optional.of("flow input: unresolved native ANY");
            for (var step : steps) if (advancedTypeLocation.isEmpty() && step instanceof FlowProgram.Procedure procedure
                    && procedure.assembly().isEmpty() && (procedure.inputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)
                        || procedure.outputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)))
                advancedTypeLocation = Optional.of(procedure.sourceId() + ": unresolved native ANY signature");
        }
        new TreeMap<>(source.inputs()).forEach((name, type) -> inputs.put(name, local(name, type)));
        var mainLocals = locals;
        for (int at = 0; at < steps.size(); at++) if (steps.get(at) instanceof FlowProgram.Procedure definition) {
            var arguments = new TreeMap<String, Integer>(); var results = new TreeMap<String, Integer>();
            new TreeMap<>(definition.inputs()).forEach((name, type) -> arguments.put(name, register(type)));
            new TreeMap<>(definition.outputs()).forEach((name, type) -> results.put(name, register(type)));
            procedures.put(at, new ProcedureScope(definition, arguments, new TreeMap<>(arguments), results));
        }
        int[] labels = new int[steps.size() + 1];
        for (cursor = 0; cursor < steps.size(); cursor++) {
            labels[cursor] = code.size();
            switch (steps.get(cursor)) {
                case FlowProgram.Reference ref -> {
                    int target=ref.kind()==FlowProgram.ReferenceKind.ADDRESS?new ArrayList<>(new TreeMap<>(source.expressions().program().cells()).keySet()).indexOf(ref.target()):requiredLocal(ref.target());
                    DataType result=switch(ref.kind()){case ADDRESS->new DataType.RefType((DataType.Scalar)source.expressions().program().cells().get(ref.target()).type(),true);case READ->((DataType.RefType)logical(target)).target();case READONLY->new DataType.RefType(((DataType.RefType)logical(target)).target(),false);case SAME->DataType.Scalar.BOOLEAN;case WRITE->null;};
                    int value=ref.value().map(this::input).orElse(-1),to=result==null?-1:local(ref.local(),result);
                    emit(new RegisterReference(RegisterReference.Kind.valueOf(ref.kind().name()),target,value,to),"reference/"+ref.kind().name().toLowerCase(Locale.ROOT));
                }
                case FlowProgram.Atomic ignored -> emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.BEGIN, -1, -1), "begin");
                case FlowProgram.EndAtomic ignored -> commitMemory(((FlowProgram.Atomic)steps.get(checked.branches().start(cursor))).result());
                case FlowProgram.Shared shared -> {
                    int index = new ArrayList<>(new TreeMap<>(source.expressions().program().cells()).keySet()).indexOf(shared.cell());
                    if (shared.listResult().isPresent()) {
                        var edit = (FlowInput.Expression)shared.value();
                        int firstTemporary = types.size();
                        int operand = expression(edit.entry(), edit::argument, shared, index).get(edit.output());
                        emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.WRITE, index, operand), "write");
                        for (int r = firstTemporary; r < types.size(); r++) if (!retainedSamples.contains(r)) emit(new RegisterProgram.Drop(r), "release_list_operand");
                        commitMemory(shared.listResult().orElseThrow());
                    } else {
                        int operand = input(shared.value());
                        emit(new RegisterMemoryOp(shared.add() ? RegisterMemoryOp.Kind.PUBLIC_ADD : RegisterMemoryOp.Kind.PUBLIC_STORE, index, operand), "public/" + shared.cell());
                    }
                }
                case FlowProgram.Memory memory -> {
                    var cells = new TreeMap<>(source.expressions().program().cells());
                    int index = new ArrayList<>(cells.keySet()).indexOf(memory.cell());
                    switch (memory.kind()) {
                        case BEGIN -> emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.BEGIN, -1, -1), "begin");
                        case ROLLBACK -> emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.ROLLBACK, -1, -1), "rollback");
                        case READ -> emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.READ, index, local(memory.local(), cells.get(memory.cell()).type())), "read");
                        case WRITE -> emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.WRITE, index, requiredLocal(memory.local())), "write");
                        case COMMIT -> commitMemory(memory.local());
                    }
                }
                case FlowProgram.Procedure definition -> {
                    relocate(new RegisterProgram.Jump(0), checked.branches().end(cursor) + 1);
                    locals = procedures.get(cursor).locals();
                }
                case FlowProgram.EndProcedure ignored -> {
                    int owner = checked.branches().owner(cursor); var procedure = procedures.get(owner);
                    if(procedure.definition().assembly().isPresent()) {
                        authored(procedure); procedureEnds.put(owner,code.size()-1); locals=mainLocals; break;
                    }
                    var staged=new TreeMap<String,Integer>();
                    procedure.outputs().forEach((name,to)->staged.put(name,software==null?requiredLocal(procedure.definition().returns().get(name)):adapt(requiredLocal(procedure.definition().returns().get(name)),logical(to))));
                    procedure.outputs().forEach((name,to)->emit(new RegisterProgram.Move(to,staged.get(name)),"result/"+name));
                    procedureEnds.put(owner, code.size()); emit(new RegisterProgram.Return(), "return"); locals = mainLocals;
                }
                case FlowProgram.TaskJoin join -> {
                    var target = procedures.get(checked.branches().procedure(join.procedure()));
                    int handle = requiredLocal(join.handle());
                    int outcome = local(join.outcome(), com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.type(target.definition().outputs()));
                    emit(new RegisterProgram.TaskWait(join.procedure(), handle), "task_wait");
                    emit(new RegisterProgram.TaskClaim(join.procedure(), handle, outcome), "task_claim");
                }
                case FlowProgram.Monitor monitor -> emit(new RegisterMonitor(monitor.cell(), monitor.visible(), monitor.privateLocal()), "monitor");
                case FlowProgram.Broadcast event -> {
                    if(!event.software()) emit(linkEvent(event.channel(),event.await()),event.await()?"event_wait":"event_post");
                    else {
                        var entries=eventReceivers.getOrDefault(event.channel(),List.of());
                        var args=new ArrayList<Integer>();
                        for(var entry:entries){
                            int target=register(DataType.Scalar.INTEGER);
                            taskAddressFixups.put(code.size(),entry);args.add(target);
                            emit(new RegisterProgram.Constant(target,0),"stage/"+entry.id()); // relocated when bodies have addresses
                        }
                        emit(new RegisterTaskGroup(args,event.await()),"publish_tasks");
                        for(int reg:args) emit(new RegisterProgram.Drop(reg),"release_target");
                    }
                }
                case FlowProgram.TryLaunch launch -> emit(new RegisterProgram.TryLaunch(launch.procedure(),
                    local(launch.started(), DataType.Scalar.BOOLEAN)), "task_try_launch");
                case FlowProgram.TaskStart launch -> {
                    var target = procedures.get(checked.branches().procedure(launch.procedure()));
                    var captured = new TreeMap<String, Integer>();
                    new TreeMap<>(launch.arguments()).forEach((name, from) -> {
                        int staging = register(target.definition().inputs().get(name));
                        emit(new RegisterProgram.Move(staging, requiredLocal(from)), "task_argument/" + name);
                        captured.put(name, staging);
                    });
                    emit(new RegisterProgram.TaskStart(launch.procedure(), captured,
                        local(launch.handle(), com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE)), "task_start");
                    captured.values().forEach(r -> emit(new RegisterProgram.Drop(r), "release_task_argument"));
                }
                case FlowProgram.Invoke call -> {
                    int target = checked.branches().procedure(call.procedure()); var procedure = procedures.get(target);
                    int firstTemporary = types.size();
                    var operands = call.operands().map(value -> expression(value.entry(), value.inputs()::get)).orElse(null);
                    var staged = new TreeMap<String,Integer>();
                    procedure.inputs().forEach((name,to) -> {
                        int from = operands == null ? requiredLocal(call.bindings().arguments().get(name)) : operands.get(name);
                        staged.put(name, software == null ? from : adapt(from, logical(to)));
                    });
                    procedure.inputs().forEach((name,to)->emit(new RegisterProgram.Move(to,staged.get(name)),"argument/"+name));
                    if (operands != null)
                        for (int register = firstTemporary; register < types.size(); register++)
                            if (!retainedSamples.contains(register)) emit(new RegisterProgram.Drop(register), "release_operand");
                    relocate(new RegisterProgram.Call(0), target + 1);
                    if(software==null) new TreeMap<>(call.bindings().returns()).forEach((name,to)->{
                        int from=procedure.outputs().get(name);emit(new RegisterProgram.Move(local(to,logical(from)),from),"result/"+name);
                    });
                    else {
                        var results=new TreeMap<Integer,Integer>();
                        new TreeMap<>(call.bindings().returns()).forEach((name,to)->{
                            int from=procedure.outputs().get(name),destination=local(to,logical(from));results.put(destination,adapt(from,logical(destination)));
                        });
                        results.forEach((to,from)->emit(new RegisterProgram.Move(to,from),"result/publish"));
                    }
                    procedure.inputs().values().forEach(r -> emit(new RegisterProgram.Drop(r), "release_argument"));
                    procedure.outputs().values().forEach(r -> emit(new RegisterProgram.Drop(r), "release_result"));
                }
                case FlowProgram.Evaluate call -> {
                    int expressionStart = code.size();
                    var returns = expression(call.expression(), call::argument);
                    if(software==null) call.returns(returns.keySet()).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                        int result=returns.get(entry.getKey()), to=local(entry.getValue(),logical(result));
                        if (!compact || returns.size() != 1 || !publishLastExpressionResult(expressionStart, result, to))
                            emit(new RegisterProgram.Move(to,result),"return/"+entry.getKey());
                    });
                    else {
                        var staged=new TreeMap<Integer,Integer>();
                        call.returns(returns.keySet()).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
                            int result=returns.get(entry.getKey()),to=local(entry.getValue(),logical(result));staged.put(to,adapt(result,logical(to)));
                        });
                        staged.forEach((to,from)->emit(new RegisterProgram.Move(to,from),"return/publish"));
                    }
                }
                case FlowProgram.If branch -> {
                    int condition = typedInput(branch.condition(),DataType.Scalar.BOOLEAN);
                    relocate(new RegisterProgram.JumpFalse(condition, 0), checked.branches().alternate(cursor));
                }
                case FlowProgram.Otherwise ignored -> relocate(new RegisterProgram.Jump(0), checked.branches().end(cursor) + 1);
                case FlowProgram.EndIf ignored -> relocate(new RegisterProgram.Jump(0), cursor + 1);
                case FlowProgram.Repeat loop -> {
                    int count = typedInput(loop.count(),DataType.Scalar.INTEGER), remaining = register(DataType.Scalar.INTEGER);
                    emit(new RegisterProgram.Move(remaining, count), "capture_count");
                    emit(new RegisterProgram.Nonnegative(remaining), "check_count");
                    int zero = constant(new DataValue.IntegerValue(0), "zero"), one = constant(new DataValue.IntegerValue(1), "one");
                    repeats.put(cursor, new RepeatLoop(remaining, one, code.size()));
                    int condition = alu(ScalarOperation.GREATER_INTEGER, Map.of("left", remaining, "right", zero), DataType.Scalar.BOOLEAN, "remaining");
                    relocate(new RegisterProgram.JumpFalse(condition, 0), checked.branches().end(cursor) + 1);
                }
                case FlowProgram.EndRepeat ignored -> {
                    var loop = repeats.get(checked.branches().start(cursor));
                    emit(new RegisterProgram.Alu(ScalarOperation.SUBTRACT_INTEGER, loop.remaining(), List.of(loop.remaining(), loop.one())), "decrement");
                    emit(new RegisterProgram.Jump(loop.head()), "repeat");
                }
                case FlowProgram.Watch watch -> {
                    int first = sampleOutputs.size();
                    // A named expression can export other values. They must not arm this hat.
                    int condition = watch.condition() instanceof FlowInput.Expression expr
                        ? expression(expr.entry(), expr::argument, null, -1, expr.output()).get(expr.output())
                        : typedInput(watch.condition(), DataType.Scalar.BOOLEAN);
                    var pulses = sampleOutputs.subList(first, sampleOutputs.size());
                    if (pulses.isEmpty()) throw unsupported("Add at least one numeric edge reporter to this event condition");
                    int pulse = pulses.getFirst();
                    for (int i = 1; i < pulses.size(); i++)
                        pulse = alu(ScalarOperation.OR_BOOLEAN, Map.of("left", pulse, "right", pulses.get(i)), DataType.Scalar.BOOLEAN, "watch_any_edge");
                    int idle = code.size();
                    emit(new RegisterProgram.JumpFalse(pulse, 0), "watch_no_edge");
                    int unmatched = code.size();
                    if (condition != pulse) emit(new RegisterProgram.JumpFalse(condition, 0), "watch_condition_false");
                    emit(new RegisterProgram.TryLaunch(watch.handler().orElseThrow(), register(DataType.Scalar.BOOLEAN)), "watch_launch");
                    patch(idle, code.size());
                    if (condition != pulse) patch(unmatched, code.size());
                    int delay = constant(new DataValue.IntegerValue(1), "watch_delay");
                    emit(new RegisterProgram.Sleep(delay), "watch_yield");
                    relocate(new RegisterProgram.Jump(0), cursor);
                }
                case FlowProgram.StopScript stop -> {
                    if (stop.mode() != FlowProgram.StopMode.CURRENT) {
                        emit(new RegisterProgram.TaskStop(stop.mode() == FlowProgram.StopMode.ALL), "stop_tasks");
                        break;
                    }
                    int owner = checked.branches().owner(cursor);
                    // A procedure uses its normal, typed return epilogue. The root exits its image.
                    relocate(new RegisterProgram.Jump(0), owner < 0 ? steps.size() : checked.branches().end(owner));
                }
                case FlowProgram.WaitUntil wait -> {
                    int condition = typedInput(wait.condition(), DataType.Scalar.BOOLEAN);
                    jumpOnTrue(condition, cursor + 1, "wait_if_false");
                    int delay = constant(new DataValue.IntegerValue(1), "retry_delay");
                    emit(new RegisterProgram.Sleep(delay), "wait");
                    emit(new RegisterProgram.Drop(delay), "release_delay");
                    relocate(new RegisterProgram.Jump(0), cursor);
                }
                case FlowProgram.Until loop -> {
                    int condition = typedInput(loop.condition(),DataType.Scalar.BOOLEAN);
                    emit(new RegisterProgram.JumpFalse(condition, code.size() + 2), "until_body");
                    relocate(new RegisterProgram.Jump(0), checked.branches().end(cursor) + 1);
                }
                case FlowProgram.EndUntil ignored -> relocate(new RegisterProgram.Jump(0), checked.branches().start(cursor));
                case FlowProgram.Forever ignored -> { if (!compact) emit(new RegisterProgram.Jump(code.size() + 1), "loop_head"); }
                case FlowProgram.EndForever ignored -> relocate(new RegisterProgram.Jump(0), checked.branches().start(cursor));
                case FlowProgram.Correlation tag -> emit(new RegisterProgram.Correlation(local(tag.local(), DataType.Scalar.TEXT)), "correlation");
                case FlowProgram.Sleep sleep -> {
                    if (sleep.unit().isPresent()) waitInUnits(sleep);
                    else emit(new RegisterProgram.Sleep(typedInput(sleep.ticks(), DataType.Scalar.INTEGER)), "sleep");
                }
                case FlowProgram.SerialSend send -> emit(new RegisterSerial.Instruction(RegisterSerial.Kind.SEND,send.port(),requiredLocal(send.local()),local(send.result(),SerialResult.SEND),false),"serial_send");
                case FlowProgram.SerialReceive receive -> emit(new RegisterSerial.Instruction(RegisterSerial.Kind.RECEIVE,receive.port(),-1,local(receive.local(),SerialResult.RECEIVE),false,receive.nativeNbt()),"serial_receive");
                case FlowProgram.SerialStatus status -> emit(new RegisterSerial.Instruction(RegisterSerial.Kind.STATUS,status.port(),-1,local(status.local(),SerialResult.STATUS),status.claimReceiveFaults()),"serial_status");
                case FlowProgram.TimerReset ignored -> emit(RegisterTimer.RESET, "timer_reset");
                case FlowProgram.Ask ask -> {
                    int question = typedInput(ask.question(), DataType.Scalar.TEXT);
                    emit(new RegisterPrompt(true, question), "ask");
                    if (!(ask.question() instanceof FlowInput.Local local) || requiredLocal(local.name()) != question)
                        emit(new RegisterProgram.Drop(question), "release_question");
                }
                case FlowProgram.SignalRead read -> emit(new RegisterDevices.Instruction(RegisterDevices.Kind.SIO_READ,
                    local(read.local(), DataType.Scalar.INTEGER), read.port()), "sio_read");
                case FlowProgram.SignalWrite write -> emit(new RegisterDevices.Instruction(RegisterDevices.Kind.SIO_WRITE,
                    requiredLocal(write.local()), write.port()), "sio_write");
                case FlowProgram.XBusStatus status -> emit(new RegisterDevices.Instruction(RegisterDevices.Kind.XBUS_STATUS,
                    local(status.local(), DataType.Scalar.INTEGER), status.port()), "xbus_status");
                case FlowProgram.Read read -> emit(new RegisterXBus.Instruction(RegisterXBus.Kind.READ, read.port(), -1,
                    local(read.local(), read.result() ? XBusResult.readType(read.type()) : read.type())), "xbus_read");
                case FlowProgram.Write write -> emit(new RegisterXBus.Instruction(RegisterXBus.Kind.WRITE, write.port(), requiredLocal(write.local()),
                    write.result().map(name -> local(name, XBusResult.type())).orElse(-1)), "xbus_write");
                case FlowProgram.AwaitReadable wait -> emit(new RegisterXBus.Instruction(RegisterXBus.Kind.WAIT_READABLE, wait.port(), -1,
                    wait.result().map(name -> local(name, XBusResult.type())).orElse(-1)), "xbus_wait");
                default -> throw unsupported("Control operation is not lowered to assembly yet");
            }
        }
        labels[cursor] = code.size();
        relocations.forEach((pc, target) -> {
            var entry = code.get(pc);
            RegisterProgram.Instruction instruction = entry.instruction() instanceof RegisterProgram.JumpFalse j
                ? new RegisterProgram.JumpFalse(j.condition(), labels[target]) : entry.instruction() instanceof RegisterProgram.Call
                    ? new RegisterProgram.Call(labels[target]) : new RegisterProgram.Jump(labels[target]);
            code.set(pc, new RegisterProgram.Code(instruction, entry.source(), entry.location()));
        });
        var bodies = procedures.entrySet().stream().map(e -> {
            var p = e.getValue(); return new RegisterProcedure(labels[e.getKey() + 1], procedureEnds.get(e.getKey()),
                p.definition().key().id(), p.definition().key().revision(), p.inputs(), p.locals(), p.outputs(), p.definition().assembly());
        }).toList();
        if(!taskAddressFixups.isEmpty()){
            var addresses=new HashMap<ModuleDefinition.Key,Integer>();
            for(var proc:bodies) addresses.put(new ModuleDefinition.Key(proc.name(),proc.revision()),proc.entry());
            taskAddressFixups.forEach((pc,key)->{
                var value=new DataValue.IntegerValue(Objects.requireNonNull(addresses.get(key)));
                int pool=constants.indexOf(value);if(pool<0){pool=constants.size();constants.add(value);}
                var old=code.get(pc);var load=(RegisterProgram.Constant)old.instruction();
                code.set(pc,new RegisterProgram.Code(new RegisterProgram.Constant(load.to(),pool),old.source(),old.location()));
            });
        }
        return new RegisterProgram(code, types, constants, inputs, mainLocals, source.steps().size(), bodies, source.expressions().program().cells(), advancedTypeLocation, carriers, authoredTypes);
    }
    /** Only the last pure producer of a single result can publish directly. Multiple outputs
     * retain staging (including swaps), and all variable-cost work remains on the producer. */
    private boolean publishLastExpressionResult(int start, int from, int to) {
        if (code.size() <= start) return false;
        var last = code.getLast();
        RegisterProgram.Instruction replacement;
        if (last.instruction() instanceof RegisterProgram.Field f && f.to() == from)
            replacement = new RegisterProgram.Field(to, f.from(), f.key());
        else if (last.instruction() instanceof RegisterProgram.Alu a && a.to() == from)
            replacement = new RegisterProgram.Alu(a.operation(), to, a.from());
        else return false;
        code.set(code.size() - 1, new RegisterProgram.Code(replacement, last.source(), last.location()));
        return true;
    }
    private void authored(ProcedureScope scope) {
        var p=scope.definition(); var image=ProcedureAssembler.assemble(p.assembly().orElseThrow(),p.inputs(),p.outputs(),p.returns(),
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES,codeLimit-emittedWords,source.expressions().program().cells()).program();
        var declaration=image.procedures().values().iterator().next(); var mapping=new HashMap<Integer,Integer>();
        image.authoredTypes().forEach((location,type)->authoredTypes.put(p.sourceId()+"/assembly/"+location,type));
        if (advancedTypeLocation.isEmpty()) advancedTypeLocation = image.advancedTypeLocation().map(location -> p.sourceId() + "/assembly/" + location);
        declaration.locals().forEach((name,reg)->mapping.put(reg,local(name,image.registerTypes().get(reg))));
        declaration.outputs().forEach((name,reg)->mapping.put(reg,scope.outputs().get(name)));
        // v8 lowering-only registers have no source symbols and are not debugger locals.
        for (int reg = 0; reg < image.registerTypes().size(); reg++)
            if (!mapping.containsKey(reg)) mapping.put(reg, register(image.registerTypes().get(reg)));
        int base=code.size();var pools=new HashMap<Integer,Integer>();
        for(int i=0;i<image.constants().size();i++){var value=image.constants().get(i);int pool=constants.indexOf(value);if(pool<0){pool=constants.size();constants.add(value);}pools.put(i,pool);}
        for(var entry:image.code()) {
            var op=switch(entry.instruction()) {
                case RegisterProgram.HasField h -> new RegisterProgram.HasField(mapping.get(h.to()),mapping.get(h.from()),mapping.get(h.key()));
                case RegisterProgram.ValueEquals e -> new RegisterProgram.ValueEquals(mapping.get(e.to()),mapping.get(e.left()),mapping.get(e.right()));
                case RegisterProgram.FormatCheck f -> new RegisterProgram.FormatCheck(mapping.get(f.to()),mapping.get(f.from()),f.fmt(),f.version());
                case RegisterProgram.FormatProject f -> new RegisterProgram.FormatProject(mapping.get(f.to()),mapping.get(f.from()),f.fmt(),f.version());
                case RegisterProgram.ValueCheck c -> new RegisterProgram.ValueCheck(mapping.get(c.to()),mapping.get(c.from()));
                case RegisterProgram.RandomRange r -> new RegisterProgram.RandomRange(mapping.get(r.to()), mapping.get(r.from()), mapping.get(r.through()), r.decimal());
                case RegisterProgram.RandomBelow r -> new RegisterProgram.RandomBelow(mapping.get(r.to()), mapping.get(r.bound()));
                case RegisterProgram.Correlation c -> new RegisterProgram.Correlation(mapping.get(c.to()));
                case RegisterXBus.Instruction d -> new RegisterXBus.Instruction(d.kind(),d.pin(),d.input()<0?-1:mapping.get(d.input()),d.output()<0?-1:mapping.get(d.output()));
                case RegisterSerial.Instruction d -> new RegisterSerial.Instruction(d.kind(),d.pin(),d.input()<0?-1:mapping.get(d.input()),mapping.get(d.output()),d.claimFaults(),d.nativeNbt());
                case RegisterProgram.AppendList a -> new RegisterProgram.AppendList(mapping.get(a.to()),mapping.get(a.from()),mapping.get(a.element()));
                case RegisterListEdit edit -> new RegisterListEdit(edit.operation(), mapping.get(edit.to()),
                    edit.operands().stream().map(mapping::get).toList());
                case RegisterProgram.MakeList l -> new RegisterProgram.MakeList(mapping.get(l.to()), l.from().stream().map(mapping::get).toList());
                case RegisterProgram.MakeRecord r -> new RegisterProgram.MakeRecord(mapping.get(r.to()), r.fields().stream().map(f->new RegisterProgram.NamedOperand(f.key(), mapping.get(f.from()))).toList());
                case RegisterProgram.Constant c -> new RegisterProgram.Constant(mapping.get(c.to()),pools.get(c.pool()));
                case RegisterProgram.Move m -> new RegisterProgram.Move(mapping.get(m.to()),mapping.get(m.from()));
                case RegisterProgram.Drop d -> new RegisterProgram.Drop(mapping.get(d.register()));
                case RegisterProgram.Alu a -> new RegisterProgram.Alu(a.operation(),mapping.get(a.to()),a.from().stream().map(mapping::get).toList());
                case RegisterProgram.Jump j -> new RegisterProgram.Jump(base+j.target());
                case RegisterProgram.JumpFalse j -> new RegisterProgram.JumpFalse(mapping.get(j.condition()),base+j.target());
                case RegisterProgram.Field f -> new RegisterProgram.Field(mapping.get(f.to()),mapping.get(f.from()),f.key());
                case RegisterProgram.ListLength l -> new RegisterProgram.ListLength(mapping.get(l.to()),mapping.get(l.from()));
                case RegisterProgram.DefaultNumber m -> new RegisterProgram.DefaultNumber(mapping.get(m.to()),mapping.get(m.from()));
                case com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterKeyDown k -> new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterKeyDown(mapping.get(k.destination()), k.selector());
                case RegisterTimer t -> t.resets() ? t : new RegisterTimer(mapping.get(t.destination()));
                case RegisterPrompt q -> new RegisterPrompt(q.asks(), mapping.get(q.register()));
                case RegisterReference r -> r.remap(mapping);
                case RegisterNbt.Instruction n -> n.remap(mapping);
                case RegisterProgram.TextNext t -> new RegisterProgram.TextNext(mapping.get(t.to()),mapping.get(t.from()),mapping.get(t.offset()));
                case RegisterProgram.TextLetter t -> new RegisterProgram.TextLetter(mapping.get(t.to()), mapping.get(t.from()), mapping.get(t.index()));
                case RegisterProgram.TextContains t -> new RegisterProgram.TextContains(mapping.get(t.to()), mapping.get(t.text()), mapping.get(t.needle()));
                case RegisterProgram.TextConcat t -> new RegisterProgram.TextConcat(mapping.get(t.to()),mapping.get(t.left()),mapping.get(t.right()));
                case RegisterProgram.TextLength t -> new RegisterProgram.TextLength(mapping.get(t.to()),mapping.get(t.from()));
                case RegisterProgram.DynamicIndex i -> new RegisterProgram.DynamicIndex(mapping.get(i.to()),mapping.get(i.from()),mapping.get(i.indexRegister()));
                case RegisterProgram.Sleep s -> new RegisterProgram.Sleep(mapping.get(s.ticks()));
                case RegisterDevices.Instruction d -> new RegisterDevices.Instruction(d.kind(),mapping.get(d.register()),d.pin());
                case RegisterProgram.TryLaunch ignored -> throw new IllegalStateException("Try-launch links only from program control IR");
                case RegisterEvent event -> linkEvent(event.channel(),event.await());
                case RegisterProgram.Return r -> r;
                default -> throw unsupported("Unsupported authored instruction");
            };
            emit(op,"assembly/"+entry.location());
            int definition=checked.branches().owner(cursor);
            code.set(code.size()-1,new RegisterProgram.Code(op,sourcePosition(definition),p.sourceId()+"/assembly/"+entry.location()));
        }
    }
    private final Map<Integer,ModuleDefinition.Key> taskAddressFixups=new LinkedHashMap<>();
    private RegisterEvent linkEvent(String channel, boolean await) {
        return new RegisterEvent(channel, await, eventReceivers.getOrDefault(channel, List.of()));
    }
    private void commitMemory(String result) {
        emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.PREPARE, -1, -1), "prepare");
        for (int i = 0; i < source.expressions().program().cells().size(); i++)
            emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.PREPARE_CELL, i, -1), "cell/" + i);
        emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.COMMIT, -1, local(result, RegisterMemory.RESULT_TYPE)), "publish");
    }
    private int typedInput(FlowInput value,DataType expected) {int from=input(value);return software==null?from:adapt(from,expected);}
    private void waitInUnits(FlowProgram.Sleep sleep) {
        int amount = input(sleep.ticks()), ticks = amount;
        int scale = sleep.unit().orElseThrow().ticks();
        if (types.get(amount) == DataType.Scalar.INTEGER) {
            if (scale != 1) {
                int factor = constant(new DataValue.IntegerValue(scale), "wait_scale");
                ticks = alu(ScalarOperation.MULTIPLY_INTEGER, Map.of("left", amount, "right", factor), DataType.Scalar.INTEGER, "wait_ticks");
                emit(new RegisterProgram.Drop(factor), "release_scale");
            }
        } else {
            int scaled = amount;
            if (scale != 1) {
                int factor = constant(new DataValue.DecimalValue(scale), "wait_scale");
                scaled = alu(ScalarOperation.MULTIPLY_DECIMAL, Map.of("left", amount, "right", factor), DataType.Scalar.DECIMAL, "wait_scaled");
                emit(new RegisterProgram.Drop(factor), "release_scale");
            }
            ticks = alu(ScalarOperation.CEIL_DECIMAL_TO_INTEGER, Map.of("value", scaled), DataType.Scalar.INTEGER, "wait_round_up");
            if (scaled != amount) emit(new RegisterProgram.Drop(scaled), "release_scaled");
            // Rounding up could turn a small negative duration into zero. Preserve the Sleep fault.
            int zero = constant(new DataValue.DecimalValue(0), "wait_zero");
            int negative = alu(ScalarOperation.LESS_DECIMAL, Map.of("left", amount, "right", zero), DataType.Scalar.BOOLEAN, "wait_negative");
            int skip = code.size();
            emit(new RegisterProgram.JumpFalse(negative, 0), "wait_nonnegative");
            int invalid = constant(new DataValue.IntegerValue(-1), "wait_invalid");
            emit(new RegisterProgram.Move(ticks, invalid), "wait_reject_negative");
            emit(new RegisterProgram.Drop(invalid), "release_invalid");
            patch(skip, code.size());
            emit(new RegisterProgram.Drop(zero), "release_zero");
            emit(new RegisterProgram.Drop(negative), "release_check");
        }
        boolean temporary = !(sleep.ticks() instanceof FlowInput.Local);
        if (temporary && ticks != amount) emit(new RegisterProgram.Drop(amount), "release_duration");
        emit(new RegisterProgram.Sleep(ticks), "sleep");
        if (temporary || ticks != amount) emit(new RegisterProgram.Drop(ticks), "release_ticks");
    }
    private int input(FlowInput input) {
        return switch (input) {
            case FlowInput.Local local -> requiredLocal(local.name());
            case FlowInput.Literal literal -> constant(literal.value(), "literal");
            case FlowInput.Expression expression -> expression(expression.entry(), expression::argument).get(expression.output());
        };
    }
    private Map<String, Integer> expression(String entry, Function<String, String> arguments) {
        return expression(entry, arguments, null, -1);
    }
    private Map<String, Integer> expression(String entry, Function<String, String> arguments, FlowProgram.Shared listEdit, int cell) {
        return expression(entry, arguments, listEdit, cell, null);
    }
    private Map<String, Integer> expression(String entry, Function<String, String> arguments, FlowProgram.Shared listEdit, int cell, String outputName) {
        var graph = graphViews.get(entry);
        if (graph == null) graph = checked.expressions().select(entry).root();
        var snapshot = listEdit == null ? null : FlowProgram.listSnapshot(listEdit, graph.graph());
        var registers = new HashMap<String, Integer>();
        for (String id : expressionOrder(graph, outputName)) {
            if (snapshot != null && snapshot.defers(id)) continue;
            var node = graph.graph().nodes().get(id); int output;
            switch (node) {
                case ComputationGraph.ProcedureValue call -> {
                    int target = checked.branches().procedure(call.procedure());
                    var procedure = procedures.get(target);
                    var staged = new TreeMap<String, Integer>();
                    procedure.inputs().forEach((name, to) -> {
                        int from = registers.get(call.arguments().get(name));
                        staged.put(name, software == null ? from : adapt(from, logical(to)));
                    });
                    procedure.inputs().forEach((name, to) -> emit(new RegisterProgram.Move(to, staged.get(name)), id + "/argument/" + name));
                    relocate(new RegisterProgram.Call(0), target + 1);
                    int result = procedure.outputs().get(call.result());
                    output = register(logical(result));
                    emit(new RegisterProgram.Move(output, result), id + "/result/" + call.result());
                    procedure.inputs().values().forEach(r -> emit(new RegisterProgram.Drop(r), id + "/release_argument"));
                    procedure.outputs().values().forEach(r -> emit(new RegisterProgram.Drop(r), id + "/release_result"));
                }
                case ComputationGraph.ListPosition position -> output = listPosition(id, position, registers.get(position.source()));
                case ComputationGraph.ListSearch search -> output = listSearch(id, search, registers);
                case ComputationGraph.ListEdit edit -> {
                    if (snapshot != null && edit.arguments().get("source").equals(snapshot.read())) {
                        int original = register(graph.nodeTypes().get(snapshot.read()));
                        emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.BEGIN, -1, -1), "begin");
                        emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.READ, cell, original), "read_list");
                        registers.put(snapshot.read(), original);
                        if (snapshot.position() != null) {
                            var selector = (ComputationGraph.ListPosition)graph.graph().nodes().get(snapshot.position());
                            var offset = (ComputationGraph.Scalar)graph.graph().nodes().get(snapshot.index());
                            int selected = listPosition(snapshot.position(), selector, original);
                            registers.put(snapshot.index(), alu(offset.operation(),
                                Map.of("left", selected, "right", registers.get(offset.arguments().get("right"))),
                                DataType.Scalar.INTEGER, snapshot.index()));
                        }
                    }
                    output = register(graph.nodeTypes().get(id));
                    if (edit.operation() == ListOperation.APPEND)
                        emit(new RegisterProgram.AppendList(output, registers.get(edit.arguments().get("source")),
                            registers.get(edit.arguments().get("value"))), id);
                    else
                        emit(new RegisterListEdit(edit.operation(), output,
                            edit.operation().inputs().keySet().stream().map(name -> registers.get(edit.arguments().get(name))).toList()), id);
                }
                case ComputationGraph.MakeList list -> {
                    output = register(graph.nodeTypes().get(id));
                    emit(new RegisterProgram.MakeList(output, list.elements().stream().map(registers::get).toList()), id);
                }
                case ComputationGraph.MakeRecord record -> {
                    output = register(graph.nodeTypes().get(id));
                    emit(new RegisterProgram.MakeRecord(output, record.fields().entrySet().stream().map(f->new RegisterProgram.NamedOperand(f.getKey(), registers.get(f.getValue()))).toList()), id);
                }
                case ComputationGraph.Constant value -> output = constant(value.value(), id);
                case ComputationGraph.Input value -> {
                    int from = requiredLocal(arguments.apply(value.name()));
                    // A pure expression borrows an immutable task-local value until publication.
                    // Keep snapshots for multi-output expressions, where publication may alias an input.
                    if (compact && graph.graph().outputs().size() == 1) output = from;
                    else {
                        output = register(software==null?types.get(from):graph.graph().inputs().get(value.name()));
                        emit(new RegisterProgram.Move(output, from), id);
                    }
                }
                case ComputationGraph.ReadMemory read -> {
                    int index = new ArrayList<>(new TreeMap<>(source.expressions().program().cells()).keySet()).indexOf(read.cell());
                    output = register(graph.nodeTypes().get(id));
                    emit(new RegisterMemoryOp(RegisterMemoryOp.Kind.PUBLIC_LOAD, index, output), id);
                }
                case ComputationGraph.Binary binary -> output = alu(ScalarOperation.forLegacy(binary.operation()),
                    Map.of("left", registers.get(binary.left()), "right", registers.get(binary.right())), graph.nodeTypes().get(id), id);
                case ComputationGraph.Scalar scalar -> {
                    var bindings = new LinkedHashMap<String, Integer>(); scalar.arguments().forEach((name, value) -> bindings.put(name, registers.get(value)));
                    output = alu(scalar.operation(), bindings, graph.nodeTypes().get(id), id);
                }
                case ComputationGraph.Field field -> {
                    if(software!=null&&(logical(registers.get(field.source()))==DataType.Scalar.ANY||AssemblyTypeRequirements.containsAny(graph.nodeTypes().get(id))))throw unsupported("Software ANY field extraction from open/native containers is not supported");
                    output = register(graph.nodeTypes().get(id)); emit(new RegisterProgram.Field(output, registers.get(field.source()), field.name()), id);
                }
                case ComputationGraph.DynamicIndex index -> {
                    output = register(graph.nodeTypes().get(id)); emit(new RegisterProgram.DynamicIndex(output, registers.get(index.source()), registers.get(index.index())), id);
                }
                case ComputationGraph.Index index -> {
                    output = register(graph.nodeTypes().get(id)); emit(new RegisterProgram.Index(output, registers.get(index.source()), index.index()), id);
                }
                case ComputationGraph.RandomRange range -> {
                    int from = registers.get(range.from()), through = registers.get(range.through());
                    if (software != null) { from = adapt(from, range.type()); through = adapt(through, range.type()); }
                    output = register(range.type());
                    emit(new RegisterProgram.RandomRange(output, from, through, range.decimal()), id);
                }
                case ComputationGraph.Timer ignored -> {
                    output = register(DataType.Scalar.DECIMAL);
                    emit(new RegisterTimer(output), id);
                }
                case ComputationGraph.Answer ignored -> {
                    output = register(DataType.Scalar.TEXT);
                    emit(new RegisterPrompt(false, output), id);
                }
                case ComputationGraph.KeyDown key -> {
                    output = register(DataType.Scalar.BOOLEAN);
                    emit(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterKeyDown(output,key.selector()),id);
                }
                case ComputationGraph.ValueEquals e -> {
                    int left=registers.get(e.left()),right=registers.get(e.right());
                    if(software!=null&&(carriers.contains(left)||carriers.contains(right))){left=adapt(left,DataType.Scalar.ANY);right=adapt(right,DataType.Scalar.ANY);}
                    output=register(DataType.Scalar.BOOLEAN);emit(new RegisterProgram.ValueEquals(output,left,right),id);
                }
                case ComputationGraph.Text text -> {
                    java.util.function.ToIntFunction<String> operand = name -> {
                        int source = registers.get(text.arguments().get(name));
                        return software == null ? source : adapt(source, text.operation().inputs().get(name));
                    };
                    switch (text.operation()) {
                        case LENGTH -> {
                            output = register(DataType.Scalar.INTEGER);
                            emit(new RegisterProgram.TextLength(output, operand.applyAsInt("text")), id);
                        }
                        case CONCAT -> {
                            output = register(DataType.Scalar.TEXT);
                            emit(new RegisterProgram.TextConcat(output, operand.applyAsInt("left"), operand.applyAsInt("right")), id);
                        }
                        case LETTER_OF -> {
                            output = register(DataType.Scalar.TEXT);
                            emit(new RegisterProgram.TextLetter(output, operand.applyAsInt("text"), operand.applyAsInt("index")), id);
                        }
                        case CONTAINS_IGNORE_CASE -> {
                            output = register(DataType.Scalar.BOOLEAN);
                            emit(new RegisterProgram.TextContains(output, operand.applyAsInt("text"), operand.applyAsInt("needle")), id);
                        }
                        default -> throw unsupported("Text operation is not lowered to assembly yet: " + text.operation());
                    }
                }
                default -> throw unsupported("Expression operation is not lowered to assembly yet: " + id);
            }
            registers.put(id, output);
        }
        var outputs = new TreeMap<String, Integer>();
        graph.graph().outputs().forEach((name, node) -> { if (outputName == null || name.equals(outputName)) outputs.put(name, registers.get(node)); });
        return outputs;
    }
    /** Ordinary pure graph ordering is stable. New value calls execute in operand order, once per node. */
    private int listPosition(String id, ComputationGraph.ListPosition position, int list) {
        int length = register(DataType.Scalar.INTEGER);
        emit(new RegisterProgram.ListLength(length, list), id + "/length");
        if (!position.insertion() && position.choice() == ComputationGraph.ListPosition.Choice.LAST) return length;
        int one = constant(new DataValue.IntegerValue(1), id + "/one");
        if (position.insertion())
            emit(new RegisterProgram.Alu(ScalarOperation.ADD_INTEGER, length, List.of(length, one)), id + "/include_end");
        if (position.choice() == ComputationGraph.ListPosition.Choice.LAST) return length;
        int index = register(DataType.Scalar.INTEGER);
        emit(new RegisterProgram.RandomBelow(index, length), id + "/random");
        emit(new RegisterProgram.Alu(ScalarOperation.ADD_INTEGER, index, List.of(index, one)), id + "/one_based");
        return index;
    }
    private int listSearch(String id, ComputationGraph.ListSearch search, Map<String, Integer> registers) {
        int source = registers.get(search.source()), value = registers.get(search.value());
        int firstTemporary = types.size();
        int result = constant(search.contains() ? new DataValue.BooleanValue(false) : new DataValue.IntegerValue(0), id + "/no_match");
        int index = constant(new DataValue.IntegerValue(0), id + "/index");
        int one = constant(new DataValue.IntegerValue(1), id + "/one");
        int length = register(DataType.Scalar.INTEGER), condition = register(DataType.Scalar.BOOLEAN);
        int item = register(((DataType.ListType)logical(source)).element());
        emit(new RegisterProgram.ListLength(length, source), id + "/length");
        int loop = code.size();
        emit(new RegisterProgram.Alu(ScalarOperation.LESS_INTEGER, condition, List.of(index, length)), id + "/in_range");
        int exhausted = code.size();
        emit(new RegisterProgram.JumpFalse(condition, 0), id + "/exhausted");
        emit(new RegisterProgram.DynamicIndex(item, source, index), id + "/read_item");
        int left = software != null && carriers.contains(value) ? adapt(item, DataType.Scalar.ANY) : item;
        emit(new RegisterProgram.ValueEquals(condition, left, value), id + "/compare_item");
        int mismatch = code.size();
        emit(new RegisterProgram.JumpFalse(condition, 0), id + "/mismatch");
        if (search.contains()) emit(new RegisterProgram.Move(result, condition), id + "/found");
        else emit(new RegisterProgram.Alu(ScalarOperation.ADD_INTEGER, result, List.of(index, one)), id + "/one_based_result");
        int matched = code.size();
        emit(new RegisterProgram.Jump(0), id + "/matched");
        patch(mismatch, code.size());
        emit(new RegisterProgram.Alu(ScalarOperation.ADD_INTEGER, index, List.of(index, one)), id + "/advance");
        emit(new RegisterProgram.Jump(loop), id + "/next_item");
        patch(exhausted, code.size()); patch(matched, code.size());
        for (int r = firstTemporary + 1; r < types.size(); r++) emit(new RegisterProgram.Drop(r), id + "/release_search_operand");
        return result;
    }
    private static List<String> expressionOrder(ProgramCompiler.CompiledGraph compiled, String output) {
        var graph = compiled.graph();
        boolean hasCalls = graph.nodes().values().stream().anyMatch(ComputationGraph.ProcedureValue.class::isInstance);
        if (!hasCalls && output == null) return compiled.order();
        record Visit(String id, boolean leaving) {}
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<Visit>();
        if (output == null) new TreeMap<>(graph.outputs()).descendingMap().values().forEach(id -> pending.push(new Visit(id, false)));
        else pending.push(new Visit(graph.outputs().get(output), false));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (visit.leaving()) { result.add(visit.id()); continue; }
            if (!seen.add(visit.id())) continue;
            pending.push(new Visit(visit.id(), true));
            var children = graph.nodes().get(visit.id()).dependencies();
            for (int i = children.size() - 1; i >= 0; i--) pending.push(new Visit(children.get(i), false));
        }
        return hasCalls ? result : compiled.order().stream().filter(seen::contains).toList();
    }
    private final Set<Integer> retainedSamples = new HashSet<>();

    private int alu(ScalarOperation operation, Map<String, Integer> operands, DataType type, String id) {
        if(software!=null) {
            if(operation==ScalarOperation.SELECT && type==DataType.Scalar.ANY) {
                int result=register(type),condition=adapt(operands.get("condition"),DataType.Scalar.BOOLEAN);
                int branch=code.size();emit(new RegisterProgram.JumpFalse(condition,0),id);
                emit(new RegisterProgram.Move(result,adapt(operands.get("when_true"),type)),id);
                int skip=code.size();emit(new RegisterProgram.Jump(0),id);patch(branch,code.size());
                emit(new RegisterProgram.Move(result,adapt(operands.get("when_false"),type)),id);patch(skip,code.size());return result;
            }
            Integer value=operands.get("value");
            if(value!=null&&carriers.contains(value)) {
                if(operation==ScalarOperation.IS_NULL)return compare(field(value,"kind",DataType.Scalar.INTEGER),constant(new DataValue.IntegerValue(0),"any/null_kind"));
                if(operation==ScalarOperation.IS_ERROR)return constant(new DataValue.BooleanValue(false),"any/not_error");
                if(operation==ScalarOperation.REQUIRE_VALUE) {
                    int isNull=compare(field(value,"kind",DataType.Scalar.INTEGER),constant(new DataValue.IntegerValue(0),"any/null_kind"));
                    requireTrue(alu(ScalarOperation.NOT_BOOLEAN,Map.of("value",isNull),DataType.Scalar.BOOLEAN,"any/not_null"));return value;
                }
            }
            var converted=new LinkedHashMap<String,Integer>();
            for(var entry:operands.entrySet())converted.put(entry.getKey(),operation.inputs().get(entry.getKey())==DataType.Scalar.ANY?entry.getValue():adapt(entry.getValue(),operation.inputs().get(entry.getKey())));
            operands=converted;
        }
        if(software!=null) {
            if(operation==ScalarOperation.SELECT && type==DataType.Scalar.ANY) {
                int result=register(type),condition=adapt(operands.get("condition"),DataType.Scalar.BOOLEAN);
                int branch=code.size();emit(new RegisterProgram.JumpFalse(condition,0),id);
                emit(new RegisterProgram.Move(result,adapt(operands.get("when_true"),type)),id);
                int skip=code.size();emit(new RegisterProgram.Jump(0),id);patch(branch,code.size());
                emit(new RegisterProgram.Move(result,adapt(operands.get("when_false"),type)),id);patch(skip,code.size());return result;
            }
            Integer value=operands.get("value");
            if(value!=null&&carriers.contains(value)) {
                if(operation==ScalarOperation.IS_NULL)return compare(field(value,"kind",DataType.Scalar.INTEGER),constant(new DataValue.IntegerValue(0),"any/null_kind"));
                if(operation==ScalarOperation.IS_ERROR)return constant(new DataValue.BooleanValue(false),"any/not_error");
                if(operation==ScalarOperation.REQUIRE_VALUE) {
                    int isNull=compare(field(value,"kind",DataType.Scalar.INTEGER),constant(new DataValue.IntegerValue(0),"any/null_kind"));
                    requireTrue(alu(ScalarOperation.NOT_BOOLEAN,Map.of("value",isNull),DataType.Scalar.BOOLEAN,"any/not_null"));return value;
                }
            }
            var converted=new LinkedHashMap<String,Integer>();
            for(var entry:operands.entrySet())converted.put(entry.getKey(),operation.inputs().get(entry.getKey())==DataType.Scalar.ANY?entry.getValue():adapt(entry.getValue(),operation.inputs().get(entry.getKey())));
            operands=converted;
        }
        if (operation.retainedEdge()) {
            int current = operands.get("current"), history = register(types.get(current));
            retainedSamples.add(history);
            int result = register(DataType.Scalar.BOOLEAN);
            var edge = new RegisterEdge(operation.fallingEdge() ? RegisterEdge.Direction.FALLING : RegisterEdge.Direction.RISING,
                result, history, current, operands.get("threshold"));
            new RegisterEdgeSample(edge).lower(register(DataType.Scalar.BOOLEAN), types.get(current)).forEach(op -> emit(op,id));
            sampleOutputs.add(result);
            return result;
        }
        if (operation.numericEdge()) {
            int result = register(DataType.Scalar.BOOLEAN), current = operands.get("current");
            new RegisterEdge(operation.fallingEdge() ? RegisterEdge.Direction.FALLING : RegisterEdge.Direction.RISING,
                result, operands.get("previous"), current, operands.get("threshold"))
                .lower(register(DataType.Scalar.BOOLEAN), types.get(current)).forEach(op -> emit(op,id));
            return result;
        }
        if (operation == ScalarOperation.LIST_LENGTH) {
            int result = register(DataType.Scalar.INTEGER);
            emit(new RegisterProgram.ListLength(result, operands.get("value")), id); return result;
        }
        // Variable-size values/operators must get their own metered lowering, not a one-cycle escape hatch.
        if (operation != ScalarOperation.REQUIRE_VALUE && !RegisterProgram.scalar(type) || !RegisterProgram.tagPredicate(operation) && operands.values().stream().anyMatch(r -> !RegisterProgram.scalar(types.get(r)))) throw unsupported("Variable-size ALU operand");
        int result = register(type);
        emit(new RegisterProgram.Alu(operation, result, operation.inputs().keySet().stream().map(operands::get).toList()), id); return result;
    }
    private int constant(DataValue value, String id) {
        int register = register(DataType.typeOf(value)); int pool = constants.indexOf(value);
        if (pool < 0) { pool = constants.size(); constants.add(value); }
        emit(new RegisterProgram.Constant(register, pool), id); return register;
    }
    private int register(DataType type) {
        int result = types.size();
        if(software!=null && type==DataType.Scalar.ANY) { types.add(CARRIER); carriers.add(result); }
        else { if(software!=null && AssemblyTypeRequirements.containsAny(type) && !type.equals(SerialResult.RECEIVE)) throw unsupported("Nested software ANY is not supported"); types.add(type); }
        return result;
    }
    private int local(String name, DataType type) {
        if(software!=null) type=software.contract(cursor<steps.size()?checked.branches().owner(cursor):-1,name,type);
        var old = locals.get(name);
        // The current image has one fixed type per local; widening a MOV must not silently
        // disagree with the source checker's narrowed type at later instructions or joins.
        if (old != null) { if (!logical(old).equals(type)) throw unsupported("Changing local type requires further lowering"); return old; }
        int result = register(type); locals.put(name, result); return result;
    }
    private int requiredLocal(String name) { var r = locals.get(name); if (r == null) throw unsupported("Unknown assembly local: " + name); return r; }
    private void emit(RegisterProgram.Instruction instruction, String node) {
        if(software!=null && instruction instanceof RegisterProgram.Move move && !logical(move.to()).equals(logical(move.from()))) {
            int converted=adapt(move.from(),logical(move.to()));
            instruction=new RegisterProgram.Move(move.to(),converted);
        }
        if (instruction instanceof RegisterProgram.Move move && !RegisterAssignment.permits(types.get(move.to()), types.get(move.from())))
            throw unsupported("Dynamic argument/result type checks require metered lowering");
        emittedWords = Math.addExact(emittedWords, instruction.words());
        if (emittedWords > codeLimit) throw new ComputationException(ComputationException.Code.PROGRAM_LIMIT,
            steps.get(cursor).sourceId(), "Compiled instructions exceed code capacity");
        code.add(new RegisterProgram.Code(instruction, sourcePosition(cursor), steps.get(cursor).sourceId() + "/" + node));
    }
    private int kind(DataType type) {
        if(type==DataType.Scalar.NULL)return 0;
        if(type==DataType.Scalar.INTEGER)return 1;
        if(type==DataType.Scalar.BOOLEAN)return 2;
        if(type==DataType.Scalar.TEXT)return 3;
        if(type==DataType.Scalar.NBT)return 4;
        throw unsupported("Unsupported software ANY variant: "+type);
    }
    private int field(int from,String name,DataType type) {
        int to=register(type);emit(new RegisterProgram.Field(to,from,name),"any/"+name);return to;
    }
    private int nativeOp(RegisterNbt.Kind operation,int from,int operand,DataType type) {
        int to=register(type);emit(new RegisterNbt.Instruction(operation,to,from,operand),"any/"+operation);return to;
    }
    private void patch(int pc,int target) {
        var old=code.get(pc);var jump=old.instruction();
        code.set(pc,new RegisterProgram.Code(jump instanceof RegisterProgram.JumpFalse j?new RegisterProgram.JumpFalse(j.condition(),target):new RegisterProgram.Jump(target),old.source(),old.location()));
    }
    private int compare(int left,int right) {return alu(ScalarOperation.EQUAL_INTEGER,Map.of("left",left,"right",right),DataType.Scalar.BOOLEAN,"any/kind_check");}
    private void requireTrue(int condition) {
        int branch=code.size();emit(new RegisterProgram.JumpFalse(condition,0),"any/check");
        int skip=code.size();emit(new RegisterProgram.Jump(0),"any/valid");
        patch(branch,code.size());
        int empty=register(new DataType.OptionalType(DataType.Scalar.INTEGER));
        int pool=constants.indexOf(DataValue.NullValue.INSTANCE);if(pool<0){pool=constants.size();constants.add(DataValue.NullValue.INSTANCE);}
        emit(new RegisterProgram.Constant(empty,pool),"any/mismatch");
        int failed=register(DataType.Scalar.INTEGER);
        emit(new RegisterProgram.Alu(ScalarOperation.REQUIRE_VALUE,failed,List.of(empty)),"any/require_kind");
        patch(skip,code.size());
    }
    private int adapt(int from,DataType target) {
        DataType actual=logical(from);
        if(actual.equals(target))return from;
        if(target==DataType.Scalar.ANY) {
            int tag=kind(actual),payload;
            if(actual==DataType.Scalar.NBT)payload=from;
            else {
                int body=from,nativeTag=switch(tag){case 0->0;case 1->4;case 2->1;case 3->8;default->throw new IllegalStateException();};
                if(actual==DataType.Scalar.BOOLEAN) {
                    body=register(DataType.Scalar.INTEGER);
                    int branch=code.size();emit(new RegisterProgram.JumpFalse(from,0),"any/bool");
                    emit(new RegisterProgram.Move(body,constant(new DataValue.IntegerValue(1),"any/one")),"any/true");
                    int skip=code.size();emit(new RegisterProgram.Jump(0),"any/bool_done");patch(branch,code.size());
                    emit(new RegisterProgram.Move(body,constant(new DataValue.IntegerValue(0),"any/zero")),"any/false");patch(skip,code.size());
                }
                payload=nativeOp(RegisterNbt.Kind.PACK,body,nativeTag,DataType.Scalar.NBT);
            }
            int marker=constant(new DataValue.IntegerValue(tag),"any/kind");int boxed=register(DataType.Scalar.ANY);
            emit(new RegisterProgram.MakeRecord(boxed,List.of(new RegisterProgram.NamedOperand("kind",marker),new RegisterProgram.NamedOperand("payload",payload))),"any/box");
            return boxed;
        }
        if(actual!=DataType.Scalar.ANY)return from;
        int expected=kind(target);
        int tag=field(from,"kind",DataType.Scalar.INTEGER);
        requireTrue(compare(tag,constant(new DataValue.IntegerValue(expected),"any/expected")));
        int payload=field(from,"payload",DataType.Scalar.NBT);
        if(target==DataType.Scalar.NBT)return payload;
        if(target==DataType.Scalar.NULL)return constant(DataValue.NullValue.INSTANCE,"any/null");
        DataType bodyType=target==DataType.Scalar.TEXT?DataType.Scalar.TEXT:DataType.Scalar.INTEGER;
        int read=nativeOp(target==DataType.Scalar.TEXT?RegisterNbt.Kind.READ_TEXT:RegisterNbt.Kind.READ_INTEGER,payload,-1,RegisterNbt.checked(bodyType));
        int maybe=field(read,"value",new DataType.OptionalType(bodyType)),result=register(bodyType);
        emit(new RegisterProgram.Alu(ScalarOperation.REQUIRE_VALUE,result,List.of(maybe)),"any/checked_extract");
        if(target==DataType.Scalar.BOOLEAN)return compare(result,constant(new DataValue.IntegerValue(1),"any/true"));
        return result;
    }
    private void relocate(RegisterProgram.Instruction instruction, int target) { relocations.put(code.size(), target); emit(instruction, "branch"); }
    private void jumpOnTrue(int condition, int target, String node) {
        emit(new RegisterProgram.JumpFalse(condition, 0), node);
        int branch = code.size() - 1;
        relocate(new RegisterProgram.Jump(0), target);
        var prior = code.get(branch);
        code.set(branch, new RegisterProgram.Code(new RegisterProgram.JumpFalse(condition, code.size()), prior.source(), prior.location()));
    }
    private ComputationException unsupported(String message) {
        return new ComputationException(ComputationException.Code.INVALID_GRAPH, cursor < steps.size() ? steps.get(cursor).sourceId() : "assembly", message);
    }
}
