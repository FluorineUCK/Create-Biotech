package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterCalls;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** A pinned sequential program and its continuation; decoding never installs or runs it. */
public final class FlowNbtCodec {
    private FlowNbtCodec() {}
    public record Snapshot(FlowProgram program, FlowRuntime.Checkpoint state) {
        public Snapshot { Objects.requireNonNull(program); Objects.requireNonNull(state); }
    }
    public static CompoundTag encode(Snapshot snapshot, ArchiveLimits limits) {
        var value = value(snapshot);
        long temporary = snapshot.state().assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L);
        if (temporary > limits.maxValueUnits() - value.storageUnits()) throw ArchiveException.limit();
        return ValueNbtCodec.encode(value, limits);
    }
    static DataValue value(Snapshot snapshot) {
        boolean assembly = snapshot.program().execution() == FlowProgram.Execution.REGISTER_V1;
        boolean dataAssembly = snapshot.state().assembly().map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState::structured).orElse(false);
        if (assembly != snapshot.state().assembly().isPresent()) throw ArchiveException.invalid("Execution format differs from continuation");
        boolean task = snapshot.state().entry().isPresent();
        boolean procedures = FlowProgram.hasProcedures(snapshot.program().steps());
        boolean boundInputs = procedures || FlowProgram.hasBoundInputs(snapshot.program().steps());
        boolean bindings = boundInputs || FlowProgram.hasCallBindings(snapshot.program().steps());
        boolean correlation = FlowProgram.hasCorrelation(snapshot.program().steps());
        boolean serial = FlowProgram.hasSerial(snapshot.program().steps());
        boolean results = correlation || serial || FlowProgram.hasXBusResults(snapshot.program().steps()) || snapshot.state().diagnostic()
            .filter(d -> d.code() == ComputationException.Code.XBUS_PEER_PAUSED || d.code() == ComputationException.Code.XBUS_OPERATION_RETIRED).isPresent();
        boolean equality = ProgramArchiveCodec.hasValueEquality(snapshot.program().expressions())
            || snapshot.state().expression().map(machine -> machine.frames().stream().anyMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)).orElse(false);
        boolean distinct = bindings || results || equality || ProgramArchiveCodec.hasDistinct(snapshot.program().expressions())
            || snapshot.state().expression().map(machine -> machine.frames().stream().anyMatch(MachineCheckpoint.DistinctState.class::isInstance)).orElse(false);
        boolean loops = FlowProgram.hasLoops(snapshot.program().steps());
        boolean repeats = FlowProgram.hasRepeats(snapshot.program().steps());
        boolean inputs = FlowProgram.hasInputSlots(snapshot.program().steps());
        boolean operation = loops || snapshot.state().operation() != snapshot.state().cursor();
        boolean hasSignals = FlowProgram.hasSignals(snapshot.program().steps());
        boolean status = FlowProgram.hasXBusStatus(snapshot.program().steps());
        boolean expanded = hasSignals || status || assembly;
        boolean signals = operation || hasSignals;
        boolean sleep = signals || FlowProgram.hasSleep(snapshot.program().steps());
        if (!hasSignals && !snapshot.state().signals().outputs().isEmpty()) throw ArchiveException.invalid("Signal state without signal instructions");
        if (!repeats && !snapshot.state().repeats().frames().isEmpty()) throw ArchiveException.invalid("Repeat state without repeat instructions");
        if (!inputs && snapshot.state().inputInvocation().isPresent()) throw ArchiveException.invalid("Input invocation without input slots");
        if (!procedures && !snapshot.state().calls().frames().isEmpty()) throw ArchiveException.invalid("Call stack without procedure instructions");
        if (task && !procedures) throw ArchiveException.invalid("Task entry without procedure definitions");
        return obj("version", snapshot.program().usesPrivateMonitors()?57:snapshot.program().usesStartupHats()?56:snapshot.program().usesMonitor()?55:FlowProgram.hasWaitUnits(snapshot.program().steps())?54:snapshot.program().usesWatch()?52:snapshot.program().usesTryLaunch()?51:snapshot.program().usesKeyDown()?50:snapshot.program().usesTerminalKeys()?49:snapshot.program().usesPrompts()?48:snapshot.program().usesTimer()?47:snapshot.program().usesGroupStop()?46:FlowProgram.hasStopScript(snapshot.program().steps())?45:FlowProgram.hasWaitUntil(snapshot.program().steps())?44:FlowProgram.hasPublicListEdits(snapshot.program().steps())?42:snapshot.program().usesEvents()?41:snapshot.program().usesReferences()?40:snapshot.program().usesAuthoredV4()?39:snapshot.program().usesMatchingAssembly() ? 38 : snapshot.program().usesReceiveAssembly() ? 37 : snapshot.program().usesSerialAssembly() ? 36 : snapshot.program().usesRequiredValueAssembly() ? 35 : snapshot.program().usesConstructionAssembly() ? 34 : snapshot.program().usesAuthoredAssembly() ? 33 : snapshot.program().usesTaskJoinAssembly() ? 32 : snapshot.program().usesTaskStartAssembly() ? 31 : snapshot.program().usesPublicAssembly() ? 30 : snapshot.program().usesMemoryAssembly() ? 29 : assembly && task ? 28 : snapshot.program().usesCallAssembly() ? 27 : snapshot.program().usesSleepAssembly() ? 26 : snapshot.program().usesXBusAssembly() ? 25 : snapshot.program().usesSignalAssembly() ? 24 : dataAssembly ? 23 : assembly ? 22 : status ? 21 : hasSignals ? 20 : task ? 19 : procedures ? 18 : boundInputs ? 17 : bindings ? 16 : FlowProgram.hasFaultClaim(snapshot.program().steps()) ? 15 : FlowProgram.hasNativeReceive(snapshot.program().steps()) ? 14 : correlation ? 13 : serial ? 12 : results ? 11 : equality ? 10 : distinct ? 9 : inputs ? 8 : repeats ? 7 : loops ? 6 : operation ? 5 : signals ? 4 : sleep ? 3 : FlowProgram.hasBranches(snapshot.program().steps()) ? 2 : 1,
            "program", FlowProgramCodec.encode(snapshot.program()), "state", state(snapshot.state(), sleep || distinct || expanded, signals || distinct || expanded,
                operation || distinct || expanded, repeats || inputs || distinct || expanded, inputs || distinct || expanded,
                procedures || expanded, task || expanded));
    }
    /** Structural decode only. FlowRuntime.restore additionally validates control position, types and hardware limits. */
    public static Snapshot decode(CompoundTag tag, ArchiveLimits limits) {
        try {
            var wire = ValueNbtCodec.decode(tag, limits);
            var snapshot = readValue(wire);
            long temporary = snapshot.state().assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L);
            if (temporary > limits.maxValueUnits() - wire.storageUnits()) throw ArchiveException.limit();
            return snapshot;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ArchiveException archive) throw archive;
            throw ArchiveException.invalid("Invalid flow archive: " + invalid.getMessage());
        }
    }
    static Snapshot readValue(DataValue data) {
        long version = number(at(data, "version"));
        if (version < 1 || (version > 42 && version != 44 && version != 45 && version != 46 && version != 47 && version != 48 && version != 49 && version != 50 && version != 51 && version != 52 && version != 54 && version != 55 && version != 56 && version != 57)) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported flow snapshot version");
        exact(data, "version", "program", "state");
        var program = FlowProgramCodec.decode(at(data, "program"));
        if ((version==57)!=program.usesPrivateMonitors()) throw ArchiveException.invalid("Private monitors require Flow snapshot schema 57");
        if (version < 56 && (version==55)!=program.usesMonitor()) throw ArchiveException.invalid("Monitor requires Flow snapshot schema 55");
        if (version < 57 && (version==56)!=program.usesStartupHats()) throw ArchiveException.invalid("Startup scripts require Flow snapshot schema 56");
        if (version < 55 && (version == 54) != FlowProgram.hasWaitUnits(program.steps())) throw ArchiveException.invalid("Wait units require Flow snapshot schema 54");
        if (version < 54 && (version==52)!=program.usesWatch()) throw ArchiveException.invalid("Watch requires Flow snapshot schema 52");
        if (version < 52 && (version==51)!=program.usesTryLaunch()) throw ArchiveException.invalid("Try-launch requires Flow snapshot schema 51");
        if (version < 51 && (version==50)!=program.usesKeyDown()) throw ArchiveException.invalid("Terminal key reporter requires Flow snapshot schema 50");
        if (version < 50 && (version==49)!=program.usesTerminalKeys()) throw ArchiveException.invalid("Terminal keys require Flow snapshot schema 49");
        if (version < 49 && (version == 48) != program.usesPrompts()) throw ArchiveException.invalid("Terminal prompt requires Flow snapshot schema 48");
        if (version < 48 && (version == 47) != program.usesTimer()) throw ArchiveException.invalid("Timer requires Flow snapshot schema 47");
        if (version < 47 && (version == 46) != program.usesGroupStop()) throw ArchiveException.invalid("Group stop requires Flow snapshot schema 46");
        if (version < 46 && (version == 45) != FlowProgram.hasStopScript(program.steps())) throw ArchiveException.invalid("Stop script requires Flow snapshot schema 45");
        if (version < 45 && (version == 44) != FlowProgram.hasWaitUntil(program.steps())) throw ArchiveException.invalid("Wait until requires Flow snapshot schema 44");
        if (version < 44 && (version==42)!=FlowProgram.hasPublicListEdits(program.steps())) throw ArchiveException.invalid("Public list edits require Flow snapshot schema 42");
        if (version < 42 && (version == 41) != program.usesEvents()) throw ArchiveException.invalid("Broadcast requires Flow snapshot schema 41");
        if(version<41 && (version==40)!=program.usesReferences())throw ArchiveException.invalid("Local reference snapshot requires schema 40");
        if(version<40 && (version==39)!=program.usesAuthoredV4())throw ArchiveException.invalid("Authored v4 requires Flow snapshot schema 39");
        if (version<39 && (version == 38) != program.usesMatchingAssembly()) throw ArchiveException.invalid("Native correlation/comparison requires Flow snapshot schema 38");
        if (version < 38 && (version == 37) != program.usesReceiveAssembly()) throw ArchiveException.invalid("Serial RX requires Flow snapshot schema 37");
        if (version < 37 && (version == 36) != program.usesSerialAssembly()) throw ArchiveException.invalid("Serial TX requires Flow snapshot schema 36");
        if (version < 36 && (version == 35) != program.usesRequiredValueAssembly()) throw ArchiveException.invalid("Require value requires Flow snapshot schema 35");
        if (version < 35 && (version == 34) != program.usesConstructionAssembly()) throw ArchiveException.invalid("Data construction requires Flow snapshot schema 34");
        if (version < 34 && (version == 33) != program.usesAuthoredAssembly()) throw ArchiveException.invalid("Authored assembly requires Flow snapshot schema 33");
        if (version < 33 && (version == 32) != program.usesTaskJoinAssembly()) throw ArchiveException.invalid("Task join requires Flow snapshot schema 32");
        if (version < 32 && (version == 31) != program.usesTaskStartAssembly()) throw ArchiveException.invalid("Task start requires Flow snapshot schema 31");
        if (version < 31 && (version == 30) != program.usesPublicAssembly()) throw ArchiveException.invalid("Public cells require Flow snapshot schema 30");
        if (version < 31 && (version >= 29) != program.usesMemoryAssembly()) throw ArchiveException.invalid("Memory assembly requires Flow snapshot schema 29");
        if (version < 29 && (version >= 27) != program.usesCallAssembly()) throw ArchiveException.invalid("Call assembly requires Flow snapshot schema 27 or 28");
        if (version < 27 && (version == 26) != program.usesSleepAssembly()) throw ArchiveException.invalid("Sleep assembly requires Flow snapshot schema 26");
        if (version < 26 && (version == 25) != program.usesXBusAssembly()) throw ArchiveException.invalid("XBus assembly requires Flow snapshot schema 25");
        if (version < 25 && (version == 24) != program.usesSignalAssembly()) throw ArchiveException.invalid("Signal assembly requires Flow snapshot schema 24");
        if ((version >= 22) != (program.execution() == FlowProgram.Execution.REGISTER_V1)) throw ArchiveException.invalid("Assembly execution requires Flow snapshot schema 22 or 23");
        if (version < 21 && FlowProgram.hasXBusStatus(program.steps())) throw ArchiveException.invalid("XBus status requires Flow snapshot schema 21");
        if (version < 18 && FlowProgram.hasProcedures(program.steps())) throw ArchiveException.invalid("Flow procedures require snapshot schema 18");
        if (version < 17 && FlowProgram.hasBoundInputs(program.steps())) throw ArchiveException.invalid("Bound control inputs require Flow snapshot schema 17");
        if (version < 16 && FlowProgram.hasCallBindings(program.steps())) throw ArchiveException.invalid("Explicit call bindings require Flow snapshot schema 16");
        if (version < 15 && FlowProgram.hasFaultClaim(program.steps())) throw ArchiveException.invalid("Receive fault claim requires Flow snapshot schema 15");
        if (version < 14 && FlowProgram.hasNativeReceive(program.steps())) throw ArchiveException.invalid("Native NBT receive requires Flow snapshot schema 14");
        if (version < 13 && FlowProgram.hasCorrelation(program.steps())) throw ArchiveException.invalid("Correlation requires Flow snapshot schema 13");
        if (version < 12 && FlowProgram.hasSerial(program.steps())) throw ArchiveException.invalid("Serial operations require Flow snapshot schema 12");
        if (version < 11 && FlowProgram.hasXBusResults(program.steps())) throw ArchiveException.invalid("XBus results require Flow snapshot schema 11");
        if (version < 10 && ProgramArchiveCodec.hasValueEquality(program.expressions())) throw ArchiveException.invalid("Value equality requires Flow snapshot schema 10");
        if (version < 9 && ProgramArchiveCodec.hasDistinct(program.expressions())) throw ArchiveException.invalid("Distinct requires Flow snapshot schema 9");
        if (version < 8 && FlowProgram.hasInputSlots(program.steps())) throw ArchiveException.invalid("Input slots and Until require flow snapshot version 8");
        if (version < 7 && FlowProgram.hasRepeats(program.steps())) throw ArchiveException.invalid("Counted loops require flow snapshot version 7");
        if (version < 6 && FlowProgram.hasLoops(program.steps())) throw ArchiveException.invalid("Loops require flow snapshot version 6");
        if (version == 1 && FlowProgram.hasBranches(program.steps())) throw ArchiveException.invalid("Branches require flow snapshot version 2");
        if (version < 3 && FlowProgram.hasSleep(program.steps())) throw ArchiveException.invalid("Sleep requires flow snapshot version 3");
        if (version < 4 && FlowProgram.hasSignals(program.steps())) throw ArchiveException.invalid("Signals require flow snapshot version 4");
        var shapeVersion = version;
        if (version >= 35) {
            var registers = fields(at(at(data, "state"), "assembly"));
            shapeVersion = program.usesMemoryAssembly() ? 29 : registers.containsKey("calls") ? 27 : registers.containsKey("data_version") ? 23 : 22;
        } else if (version >= 31 && !program.usesMemoryAssembly()) shapeVersion = 28;
        var state = readState(at(data, "state"), shapeVersion, program.usesConstructionAssembly(), program.usesSerialAssembly(), program.usesValueEqualityAssembly());
        if (version < 29 && (version == 28) != (program.execution() == FlowProgram.Execution.REGISTER_V1 && state.entry().isPresent()))
            throw ArchiveException.invalid("Assembly task root requires Flow snapshot schema 28");
        if (version < 11 && state.diagnostic().filter(d -> d.code() == ComputationException.Code.XBUS_PEER_PAUSED).isPresent())
            throw ArchiveException.invalid("Paused XBus operation failure requires Flow snapshot schema 11");
        return new Snapshot(program, state);
    }
    private static DataValue state(FlowRuntime.Checkpoint state, boolean sleep, boolean signals, boolean operation, boolean repeats, boolean inputs, boolean procedures, boolean task) {
        var fields = new LinkedHashMap<>(obj("computer", state.computer().toString(), "flow", state.flow().toString(), "cursor", state.cursor(),
            "status", state.status().name(), "locals", state.locals(), "work", state.work(), "paused", state.paused(),
            "expression", state.expression().map(ComputerNbtCodec::pureMachine).orElse(DataValue.NullValue.INSTANCE),
            "diagnostic", state.diagnostic().<DataValue>map(diagnostic -> obj("code", diagnostic.code().name(),
                "source", diagnostic.location(), "message", diagnostic.message())).orElse(DataValue.NullValue.INSTANCE)).fields());
        if (sleep) fields.put("sleep_ticks", new DataValue.IntegerValue(state.sleepTicks()));
        if (signals) fields.put("signal_outputs", state.signals().value());
        if (operation) fields.put("operation", new DataValue.IntegerValue(state.operation()));
        if (repeats) fields.put("repeats", state.repeats().value());
        if (inputs) fields.put("input_invocation", state.inputInvocation().<DataValue>map(FlowInput.Invocation::value).orElse(DataValue.NullValue.INSTANCE));
        if (procedures) fields.put("calls", state.calls().value());
        if (task) fields.put("entry", state.entry().<DataValue>map(key -> obj("name", key.id(), "revision", key.revision()))
            .orElse(DataValue.NullValue.INSTANCE));
        state.assembly().ifPresent(registers -> fields.put("assembly", registers.value()));
        return new DataValue.RecordValue(fields);
    }
    /** Owner schema 15 stores the installed program once and only these schema-19 task states. */
    static DataValue taskState(FlowRuntime.Checkpoint state) {
        if (state.entry().isEmpty()) throw ArchiveException.invalid("Missing procedure task entry");
        return state(state, true, true, true, true, true, true, true);
    }
    static FlowRuntime.Checkpoint readTaskState(DataValue state) { return readState(state, 19); }
    static FlowRuntime.Checkpoint readTaskState(DataValue state, boolean redstone) { return readState(state, redstone ? 20 : 19); }
    static FlowRuntime.Checkpoint readAssemblyTaskState(DataValue state) {
        return readAssemblyTaskState(state, false);
    }
    static FlowRuntime.Checkpoint readAssemblyTaskState(DataValue state, boolean memory) {
        var result = readState(state, memory ? 29 : 28);
        if (result.entry().isEmpty()) throw ArchiveException.invalid("Missing assembly task root");
        return result;
    }
    private static FlowRuntime.Checkpoint readState(DataValue data, long version) {
        return readState(data, version, false);
    }
    private static FlowRuntime.Checkpoint readState(DataValue data,long version,boolean construction) {return readState(data,version,construction,false);}
    private static FlowRuntime.Checkpoint readState(DataValue data, long version, boolean construction,boolean serial) { return readState(data,version,construction,serial,false); }
    private static FlowRuntime.Checkpoint readState(DataValue data, long version, boolean construction,boolean serial, boolean equality) {
        if (version < 3) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic");
        else if (version == 3) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks");
        else if (version == 4) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs");
        else if (version < 7) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation");
        else if (version == 7) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation", "repeats");
        else if (version < 18) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation", "repeats", "input_invocation");
        else if (version == 18) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation", "repeats", "input_invocation", "calls");
        else if (version < 22) exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation", "repeats", "input_invocation", "calls", "entry");
        else exact(data, "computer", "flow", "cursor", "status", "locals", "work", "paused", "expression", "diagnostic", "sleep_ticks", "signal_outputs", "operation", "repeats", "input_invocation", "calls", "entry", "assembly");
        var outputs = new LinkedHashMap<String, Integer>();
        if (version >= 4) fields(at(data, "signal_outputs")).forEach((pin, level) -> {
            long value = number(level);
            // Legacy saves owned a 0..100 electrical output, not a different numeric data type.
            // Saturate only that hardware output. Locals, initial inputs and program documents stay intact.
            if (version < 20) {
                if (value < 0 || value > 100) throw ArchiveException.invalid("Invalid legacy Simple I/O level");
                value = Math.min(value, SimpleIoState.MAX);
            }
            outputs.put(pin, Math.toIntExact(value));
        });
        var expression = at(data, "expression"); var diagnostic = at(data, "diagnostic");
        return new FlowRuntime.Checkpoint(UUID.fromString(string(at(data, "computer"))), UUID.fromString(string(at(data, "flow"))),
            index(at(data, "cursor")), enumeration(FlowRuntime.Status.class, at(data, "status")), record(at(data, "locals")),
            number(at(data, "work")), bool(at(data, "paused")), expression == DataValue.NullValue.INSTANCE ? Optional.empty()
                : Optional.of(ComputerNbtCodec.readPureMachine(expression, version)), diagnostic == DataValue.NullValue.INSTANCE ? Optional.empty()
                : Optional.of(readDiagnostic(diagnostic)), version < 3 ? 0 : number(at(data, "sleep_ticks")), new SimpleIoState(outputs),
                version < 5 ? index(at(data, "cursor")) : number(at(data, "operation")),
                version < 7 ? FlowRepeatState.EMPTY : new FlowRepeatState(list(at(data, "repeats")).stream().map(FlowNbtCodec::repeatFrame).toList()),
                version < 8 || at(data, "input_invocation") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(invocation(at(data, "input_invocation"))),
                version < 18 ? FlowCallStack.EMPTY : new FlowCallStack(list(at(data, "calls")).stream().map(FlowNbtCodec::callFrame).toList()),
                version < 19 || version >= 20 && at(data, "entry") == DataValue.NullValue.INSTANCE
        ? Optional.empty() : Optional.of(entry(at(data, "entry"))), version < 22 ? Optional.empty() : Optional.of(registers(at(data, "assembly"), version >= 23, version >= 27 && ((!construction && version < 29) || fields(at(data, "assembly")).containsKey("calls")), version >= 29, construction, serial, equality)));
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState registers(DataValue value, boolean structured, boolean calls, boolean memory, boolean construction,boolean serial, boolean equality) {
        if (equality) {
            var names = new ArrayList<>(List.of("image", "pc", "registers", "data_version", "scan", "comparison"));
            if (construction) names.add("build"); if (serial) names.add("serial"); if (calls) names.add("calls"); if (memory) names.add("transaction");
            exact(value, names.toArray(String[]::new));
        } else if (serial) {
            var names=new ArrayList<>(List.of("image","pc","registers","data_version","scan","serial"));
            if(construction)names.add("build");if(calls)names.add("calls");if(memory)names.add("transaction");exact(value,names.toArray(String[]::new));
        } else if (construction) {
            var names = new ArrayList<>(List.of("image", "pc", "registers", "data_version", "scan", "build"));
            if (calls)names.add("calls");
            if (memory)names.add("transaction");
            exact(value, names.toArray(String[]::new));
        } else if (memory && calls) exact(value, "image", "pc", "registers", "data_version", "scan", "calls", "transaction");
        else if (memory) exact(value, "image", "pc", "registers", "data_version", "scan", "transaction");
        else if (calls) exact(value, "image", "pc", "registers", "data_version", "scan", "calls");
        else if (structured) exact(value, "image", "pc", "registers", "data_version", "scan"); else exact(value, "image", "pc", "registers");
        if (structured && number(at(value, "data_version")) != (equality ? 6 : serial ? 5 : construction ? 4 : memory ? 3 : calls ? 2 : 1)) throw ArchiveException.invalid("Unsupported assembly data format");
        Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState.Scan> scan = Optional.empty();
        if (structured && at(value, "scan") != DataValue.NullValue.INSTANCE) {
            var progress = at(value, "scan"); exact(progress, "offset", "points");
            scan = Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState.Scan(index(at(progress, "offset")), number(at(progress, "points"))));
        }
        Optional<RegisterCalls> context = Optional.empty();
        if (calls) {
            var c = at(value, "calls"); exact(c, "excluded", "frames");
            context = Optional.of(new RegisterCalls(number(at(c, "excluded")), list(at(c, "frames")).stream().map(frame -> {
                exact(frame, "pc", "source", "registers", "excluded", "entered");
                return new RegisterCalls.Frame(index(at(frame, "pc")), index(at(frame, "source")), registerValues(at(frame, "registers")),
                    number(at(frame, "excluded")), number(at(frame, "entered")));
            }).toList()));
        }
        return new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState(string(at(value, "image")), index(at(value, "pc")), registerValues(at(value, "registers")), structured, scan, context, memory ? Optional.of(bool(at(value, "transaction"))) : Optional.empty(), construction, !construction || at(value, "build") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(build(at(value, "build"))), serial, !serial || at(value,"serial")==DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(serialProgress(at(value,"serial"))), equality, !equality || at(value,"comparison") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(comparison(at(value,"comparison"))));
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterComparison comparison(DataValue value) {
        exact(value, "version", "paid", "progress");
        if (number(at(value, "version")) < 1 || number(at(value,"version")) > 4) throw ArchiveException.invalid("Unsupported typed comparison cursor version");
        if ((number(at(value,"version"))==2) != record(at(value,"progress")).fields().containsKey("index")) throw ArchiveException.invalid("Comparison cursor version differs from shape");
        if ((number(at(value,"version"))==3) != record(at(value,"progress")).fields().containsKey("typing")) throw ArchiveException.invalid("Type-check cursor version differs from shape");
        if ((number(at(value,"version"))==4) != record(at(value,"progress")).fields().containsKey("format")) throw ArchiveException.invalid("Format-check cursor version differs from shape");
        return new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterComparison(number(at(value, "paid")), record(at(value, "progress")));
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Progress serialProgress(DataValue value) {
        long version=number(at(value,"version"));if(version!=1&&version!=2&&version!=3)throw ArchiveException.invalid("Unsupported serial cost format");
        if(version==1)exact(value,"version","paid","bytes","heap","nodes","chars","units","configuration");
        else exact(value,"version","paid","bytes","heap","nodes","chars","units","configuration","receive");
        var config=at(value,"configuration");if(bool(at(config,"configured"))) {
            if(version==1)exact(config,"configured","side","enabled","gateway");else {
                exact(config,"configured","side","enabled","gateway","hardware","epoch");var h=at(config,"hardware");exact(h,"bits","frame","frames","bytes","epoch");if(number(at(config,"epoch"))<0||number(at(h,"epoch"))<0)throw ArchiveException.invalid("Negative receive epoch");
                new com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.Port(number(at(h,"bits")),index(at(h,"frame")),index(at(h,"frames")),number(at(h,"bytes")));
            }
            if(index(at(config,"side"))>=6)throw ArchiveException.invalid("Serial side");bool(at(config,"enabled"));string(at(config,"gateway"));
        }else {exact(config,"configured");if(version>=2)throw ArchiveException.invalid("Unconfigured receive cursor");}
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(index(at(value,"bytes")),number(at(value,"heap")),new ArchiveLimits(index(at(value,"nodes")),index(at(value,"chars")),number(at(value,"units"))));
        if(version==1)return new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Progress(number(at(value,"paid")),limits,record(config));
        var rx=at(value,"receive");
        if(version==3){exact(rx,"flow","device","face","head","pin","native","origin","length","consumed","witness","image","pc","certificate","failure");return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Progress.savedCertifiedReceive(number(at(value,"paid")),limits,record(config),UUID.fromString(string(at(rx,"flow"))),UUID.fromString(string(at(rx,"device"))),UUID.fromString(string(at(rx,"face"))),UUID.fromString(string(at(rx,"head"))),string(at(rx,"pin")),bool(at(rx,"native")),number(at(rx,"origin")),index(at(rx,"length")),index(at(rx,"consumed")),number(at(rx,"witness")),string(at(rx,"image")),index(at(rx,"pc")),string(at(rx,"certificate")),string(at(rx,"failure")));}
        exact(rx,"flow","device","face","head","pin","native","origin","length","consumed","witness");
        return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Progress.savedReceive(number(at(value,"paid")),limits,record(config),UUID.fromString(string(at(rx,"flow"))),UUID.fromString(string(at(rx,"device"))),UUID.fromString(string(at(rx,"face"))),UUID.fromString(string(at(rx,"head"))),string(at(rx,"pin")),bool(at(rx,"native")),number(at(rx,"origin")),index(at(rx,"length")),index(at(rx,"consumed")),number(at(rx,"witness")));
    }
    private static com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction build(DataValue value) {
        if(record(value).fields().containsKey("kind")) {
            if(string(at(value,"kind")).equals("text_contains")) {
                exact(value,"kind","version","start","offset","needle","compared");
                if(number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown text search format");
                return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedTextContains(index(at(value,"start")),index(at(value,"offset")),index(at(value,"needle")),number(at(value,"compared")));
            }
            if(string(at(value,"kind")).equals("text_concat")) {
                exact(value,"kind","version","left","right","points");
                if(number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown text concatenation format");
                return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedTextConcat(index(at(value,"left")),index(at(value,"right")),number(at(value,"points")));
            }
            if(string(at(value,"kind")).equals("bytes_join")) {
                exact(value,"kind","version","paid","cursor");
                if(number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown byte joining format");
                return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedJoining(number(at(value,"paid")),number(at(value,"cursor")));
            }
            if(string(at(value,"kind")).equals("value_decode")) {
                exact(value,"kind","version","paid","cursor");
                if(number(at(value,"version"))==2)return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedUnitDecoding(number(at(value,"paid")),number(at(value,"cursor")));
                if(number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown value decoding format");
                return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedDecoding(number(at(value,"paid")),number(at(value,"cursor")));
            }
            if(string(at(value,"kind")).equals("value_encode")) {
                exact(value,"kind","version","paid","cursor");
                if(number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown value encoding format");
                return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedEncoding(number(at(value,"paid")),number(at(value,"cursor")));
            }
            exact(value,"kind","version","tag","paid");
            if(!string(at(value,"kind")).equals("native_pack") || number(at(value,"version"))!=1)throw ArchiveException.invalid("Unknown native construction format");
            return com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction.savedNative(index(at(value,"tag")),number(at(value,"paid")));
        }
        exact(value, "cursor");
        return new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterConstruction(index(at(value, "cursor")));
    }
    static FlowRuntime.Checkpoint readAssemblyTaskState(DataValue state, boolean memory, boolean construction,boolean serial) {
        var result = readState(state, memory?29:28, construction,serial);
        if (result.entry().isEmpty()) throw ArchiveException.invalid("Missing assembly task root");
        return result;
    }
    static FlowRuntime.Checkpoint readAssemblyTaskState(DataValue state, boolean memory, boolean construction, boolean serial, boolean equality) {
        var result = readState(state, memory?29:28, construction,serial,equality);
        if (result.entry().isEmpty()) throw ArchiveException.invalid("Missing assembly task root");
        return result;
    }
    private static Map<Integer, DataValue> registerValues(DataValue value) {
        var values = new TreeMap<Integer, DataValue>();
        fields(value).forEach((key, item) -> {
            int address = Integer.parseInt(key);
            if (!Integer.toString(address).equals(key) || values.putIfAbsent(address, item) != null) throw ArchiveException.invalid("Invalid register address");
        });
        return values;
    }
    private static ModuleDefinition.Key entry(DataValue value) {
        exact(value, "name", "revision");
        return new ModuleDefinition.Key(string(at(value, "name")), number(at(value, "revision")));
    }
    private static FlowCallStack.Frame callFrame(DataValue value) {
        exact(value, "cursor", "locals", "repeats", "work", "operation");
        return new FlowCallStack.Frame(index(at(value, "cursor")), record(at(value, "locals")),
            new FlowRepeatState(list(at(value, "repeats")).stream().map(FlowNbtCodec::repeatFrame).toList()), number(at(value, "work")), number(at(value, "operation")));
    }
    private static FlowInput.Invocation invocation(DataValue value) {
        exact(value, "entry", "output", "inputs");
        return new FlowInput.Invocation(string(at(value, "entry")), string(at(value, "output")), record(at(value, "inputs")));
    }
    private static FlowRepeatState.Frame repeatFrame(DataValue value) {
        exact(value, "header", "initial", "remaining");
        return new FlowRepeatState.Frame(index(at(value, "header")), number(at(value, "initial")), number(at(value, "remaining")));
    }
    private static ComputationMachine.Diagnostic readDiagnostic(DataValue data) {
        exact(data, "code", "source", "message");
        return new ComputationMachine.Diagnostic(enumeration(ComputationException.Code.class, at(data, "code")),
            string(at(data, "source")), string(at(data, "message")));
    }
    private static void exact(DataValue data, String... names) {
        if (!fields(data).keySet().equals(Set.of(names))) throw ArchiveException.invalid("Unexpected flow archive fields");
    }
}
