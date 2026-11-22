package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState;
import java.util.*;

/** One thread-confined flow; waiting never runs a recipient event and never blocks the server thread. */
public final class FlowRuntime {
    public enum Status { READY, COMPUTING, WAITING_READ, WAITING_WRITE, WAITING_READABLE, COMPLETE, FAILED, SLEEPING, WAITING_TASK, WAITING_ANSWER }
    public record Checkpoint(UUID computer, UUID flow, int cursor, Status status,
        DataValue.RecordValue locals, long work, boolean paused, Optional<MachineCheckpoint> expression,
        Optional<ComputationMachine.Diagnostic> diagnostic, long sleepTicks, SimpleIoState signals, long operation, FlowRepeatState repeats,
        Optional<FlowInput.Invocation> inputInvocation, FlowCallStack calls, Optional<ModuleDefinition.Key> entry, Optional<RegisterState> assembly) {
        public Checkpoint {
            Objects.requireNonNull(computer); Objects.requireNonNull(flow); Objects.requireNonNull(status);
            Objects.requireNonNull(locals); Objects.requireNonNull(signals); Objects.requireNonNull(repeats);
            Objects.requireNonNull(expression); Objects.requireNonNull(diagnostic); Objects.requireNonNull(inputInvocation);
            Objects.requireNonNull(calls); Objects.requireNonNull(entry); Objects.requireNonNull(assembly);
            if (cursor < 0 || work < 0 || sleepTicks < 0 || operation < cursor) throw new IllegalArgumentException("Invalid flow position/work/time/operation");
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals, long operation, FlowRepeatState repeats, Optional<FlowInput.Invocation> inputInvocation,
            FlowCallStack calls, Optional<ModuleDefinition.Key> entry) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, operation,
                repeats, inputInvocation, calls, entry, Optional.empty());
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals, long operation, FlowRepeatState repeats, Optional<FlowInput.Invocation> inputInvocation, FlowCallStack calls) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, operation, repeats, inputInvocation, calls, Optional.empty());
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals, long operation, FlowRepeatState repeats, Optional<FlowInput.Invocation> inputInvocation) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, operation, repeats, inputInvocation, FlowCallStack.EMPTY);
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals, long operation, FlowRepeatState repeats) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, operation, repeats, Optional.empty());
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals, long operation) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, operation, FlowRepeatState.EMPTY);
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic,
            long sleepTicks, SimpleIoState signals) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, signals, cursor);
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic, long sleepTicks) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, sleepTicks, SimpleIoState.EMPTY);
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work,
            boolean paused, Optional<MachineCheckpoint> expression, Optional<ComputationMachine.Diagnostic> diagnostic) {
            this(computer, flow, cursor, status, locals, work, paused, expression, diagnostic, 0);
        }
        public Checkpoint(UUID computer, UUID flow, int cursor, Status status, DataValue.RecordValue locals, long work, boolean paused) {
            this(computer, flow, cursor, status, locals, work, paused, Optional.empty(), Optional.empty());
        }
        /** Mutable continuation ownership; pure-machine arena is charged separately. Diagnostics are host metadata. */
        public long controlUnits() { return Math.addExact(wireControlUnits(), assembly.map(RegisterState::constructionUnits).orElse(0L)); }
        /** Source-free wire/register/call controls plus all already materialized owned buffers. */
        long restoreControlUnits() { return Math.addExact(wireControlUnits(), assembly.map(RegisterState::materializedConstructionUnits).orElse(0L)); }
        private long wireControlUnits() {
            var fields = new LinkedHashMap<String, DataValue>(Map.of("computer", new DataValue.TextValue(computer.toString()),
                "flow", new DataValue.TextValue(flow.toString()), "cursor", new DataValue.IntegerValue(cursor),
                "status", new DataValue.IntegerValue(status.ordinal()), "locals", locals,
                "work", new DataValue.IntegerValue(work), "paused", new DataValue.BooleanValue(paused)));
            if (sleepTicks > 0) fields.put("sleep_ticks", new DataValue.IntegerValue(sleepTicks));
            if (!signals.outputs().isEmpty()) fields.put("signal_outputs", signals.value());
            if (operation != cursor) fields.put("operation", new DataValue.IntegerValue(operation));
            if (!repeats.frames().isEmpty()) fields.put("repeats", repeats.value());
            inputInvocation.ifPresent(invocation -> fields.put("input_invocation", invocation.value()));
            if (!calls.frames().isEmpty()) fields.put("calls", calls.value());
            assembly.ifPresent(registers -> fields.put("assembly", registers.value()));
            entry.ifPresent(key -> fields.put("entry", new DataValue.RecordValue(Map.of(
                "name", new DataValue.TextValue(key.id()), "revision", new DataValue.IntegerValue(key.revision())))));
            return new DataValue.RecordValue(fields).storageUnits();
        }
        public boolean cancelled() {
            return status == Status.FAILED && diagnostic.filter(d -> d.code() == ComputationException.Code.TASK_CANCELLED
                || d.code() == ComputationException.Code.TASK_CANCELLED_ADMITTED).isPresent();
        }

    }
    private final FlowProgram program;
    private final ResourceLimits limits;
    private final FlowProgram.Installation installation;
    private ComputationMachine expression;
    private Checkpoint state;
    private boolean loaded = true;

    public FlowRuntime(UUID computer, UUID flow, FlowProgram program, DataValue.RecordValue inputs, ResourceLimits limits) {
        this(computer, flow, program, inputs, limits, Optional.empty());
    }
    /** A continuation factory, not a scheduler or admission API: the owning Computer must reserve resources first. */
    public static FlowRuntime startProcedure(UUID computer, UUID task, FlowProgram program, ModuleDefinition.Key entry,
            DataValue.RecordValue arguments, ResourceLimits limits) {
        return new FlowRuntime(computer, task, program, arguments, limits, Optional.of(entry));
    }
    private FlowRuntime(UUID computer, UUID flow, FlowProgram program, DataValue.RecordValue inputs, ResourceLimits limits,
            Optional<ModuleDefinition.Key> entry) {
        LocalReferences.portable(inputs);
        this.program = Objects.requireNonNull(program); this.limits = Objects.requireNonNull(limits);
        installation = program.compile(limits, entry);
        int start = installation.branches().entry();
        if (!installation.locals().get(start).accepts(inputs))
            throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, "flow", "Input schema differs");
        if (installation.assembly().isPresent()) {
            var image = installation.assembly().orElseThrow(); var registers = image.begin(inputs);
            state = new Checkpoint(computer, flow, image.source(registers.pc()), image.complete(registers) ? Status.COMPLETE : Status.READY,
                inputs, 0, false, Optional.empty(), Optional.empty(), 0, SimpleIoState.EMPTY, program.usesXBusAssembly() ? program.steps().size() + 1L : image.source(registers.pc()), FlowRepeatState.EMPTY,
                Optional.empty(), FlowCallStack.EMPTY, entry, Optional.of(registers));
            requireCapacity(state); return;
        }
        state = new Checkpoint(computer, flow, start, program.steps().isEmpty() ? Status.COMPLETE : Status.READY, inputs, 0, false,
            Optional.empty(), Optional.empty(), 0, SimpleIoState.EMPTY, start, FlowRepeatState.EMPTY, Optional.empty(), FlowCallStack.EMPTY, entry);
        requireCapacity(state);
    }
    public FlowProgram program() { return program; }
    /** Sampling requirements of the installed image, never reparsing source during execution. */
    public Set<String> xbusStatusPins() {
        return FlowDevices.statusPins(installation.assembly().map(FlowDevices::instructions).orElseGet(() -> FlowDevices.uses(program.steps(),program.expressions().program().cells())));
    }
    /** Scalar input needed by one upcoming instruction, not every device mentioned in the program.
     * Owned task roots share the installed code addresses; no recompilation or world observation here. */
    Optional<FlowDevices.Use> roundSignalRead(Checkpoint context) {
        if (!FlowTasks.ready(context)) return Optional.empty();
        if (installation.assembly().isPresent()) {
            var code = installation.assembly().orElseThrow().code();
            int pc = context.assembly().orElseThrow().pc();
            if (pc < code.size() && code.get(pc).instruction() instanceof
                    com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices.Instruction device
                    && device.kind().writesRegister())
                return Optional.of(new FlowDevices.Use(device.kind(), device.pin(), code.get(pc).location()));
            return Optional.empty();
        }
        if (context.cursor() >= program.steps().size()) return Optional.empty();
        return FlowDevices.uses(List.of(program.steps().get(context.cursor()))).stream()
            .filter(use -> use.kind().writesRegister()).findFirst();
    }
    public static FlowRuntime restore(FlowProgram program, Checkpoint checkpoint, ResourceLimits limits) {
        return new FlowRuntime(program, checkpoint, limits);
    }
    private FlowRuntime(FlowProgram program, Checkpoint checkpoint, ResourceLimits limits) {
        this.program = Objects.requireNonNull(program); this.limits = Objects.requireNonNull(limits);
        state = Objects.requireNonNull(checkpoint); installation = program.compile(limits, state.entry()); loaded = false;
        if (installation.assembly().isPresent()) { validateAssembly(); requireCapacity(state); return; }
        requireState(state.assembly().isEmpty(), "Register continuation requires an assembly installation");
        requireState(state.cursor() <= program.steps().size(), "Continuation is outside the installed flow");
        requireState((state.status() == Status.COMPLETE) == (state.cursor() == program.steps().size()), "Invalid end position");
        requireState(installation.locals().get(state.cursor()).accepts(state.locals()), "Local schema differs from continuation");
        long minimumWork = installation.branches().minimumWork(state.cursor());
        if ((FlowProgram.hasLoops(program.steps()) || FlowProgram.hasProcedures(program.steps())) && state.operation() > state.cursor()) {
            minimumWork = installation.branches().repeatedWork(state.cursor());
            requireState(state.operation() - state.cursor() >= installation.branches().repeatedOffset(state.cursor()),
                "Operation distance underreports a completed loop back-edge");
        }
        requireState(minimumWork != Long.MAX_VALUE, "Continuation is unreachable");
        requireState(state.work() >= minimumWork, "Work underreports executed control instructions");
        state.repeats().validate(program.steps(), installation.branches(), state.cursor(), state.operation(), state.work());
        state.calls().validate(program, installation, state);
        requireState((state.status() == Status.COMPUTING) == state.expression().isPresent(), "Invalid pure subframe presence");
        requireState((state.status() == Status.COMPUTING && loopInput() instanceof FlowInput.Expression) == state.inputInvocation().isPresent(),
            "Invalid input invocation presence");
        requireState((state.status() == Status.FAILED) == state.diagnostic().isPresent(), "Invalid diagnostic presence");
        requireState((state.status() == Status.SLEEPING) == (state.sleepTicks() > 0), "Invalid remaining sleep time");
        switch (state.status()) {
            case WAITING_TASK, WAITING_ANSWER -> requireState(false, "Task and terminal waits require typed ISA execution");
            case SLEEPING -> {
                requireState(step() instanceof FlowProgram.Sleep, "Sleep is not at a sleep operation");
                long requested = ((DataValue.IntegerValue)state.locals().field(((FlowProgram.Sleep)step()).ticksLocal())).value();
                requireState(state.sleepTicks() <= requested, "Remaining sleep exceeds requested duration");
            }
            case WAITING_READ -> requireState(step() instanceof FlowProgram.Read, "Read wait is not at a read operation");
            case WAITING_WRITE -> requireState(step() instanceof FlowProgram.Write, "Write wait is not at a write operation");
            case WAITING_READABLE -> requireState(step() instanceof FlowProgram.AwaitReadable, "Readable wait is not at a wait operation");
            case COMPUTING -> {
                String entry = expressionEntry();
                requireState(entry != null, "Pure subframe is not at an expression operation");
                if (loopInput() instanceof FlowInput.Expression graph) {
                    var invocation = state.inputInvocation().orElseThrow();
                    requireState(invocation.entry().equals(graph.entry()) && invocation.output().equals(graph.output()), "Input invocation differs from slot");
                    requireState(invocation.inputs().equals(expressionInputs(entry)), "Frozen input invocation differs from locals");
                }
                var pure = state.expression().orElseThrow();
                requireState(pure.status() == ComputationMachine.Status.RUNNING && !pure.paused(), "Invalid pure subframe status");
                requireState(pure.workUnits() < state.work() - minimumWork, "Pure work exceeds the flow work count");
                var compiled = program.compileExpressions(limits, remainingMemory()).select(entry);
                expression = ComputationMachine.restore(compiled, pure);
                // The existing machine replaces the root graph by a commit frame before completing.
                if (pure.frames().getFirst() instanceof MachineCheckpoint.GraphState root)
                    requireState(root.inputs().equals(expressionInputs(entry)), "Frozen expression inputs differ from locals");
            }
            case FAILED -> requireState(state.diagnostic().orElseThrow().location().equals(step().sourceId()), "Diagnostic source differs");
            default -> {}
        }
        if (state.status() != Status.READY && state.status() != Status.COMPLETE)
            requireState(state.work() > minimumWork, "Operation was not executed before waiting");
        installation.signals().validate(state.cursor(), state.operation(), state.signals());
        requireCapacity(state);
    }
    private static void requireState(boolean valid, String message) {
        if (!valid) throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "flow", message);
    }
    public Checkpoint checkpoint() { return state; }
    public boolean loaded() { return loaded; }
    public void load() { if (!loaded) { loaded = true; setPaused(state.paused()); } }
    public void unload() { if (loaded) { loaded = false; setPaused(state.paused()); } }
    public void setPaused(boolean paused) {
        state = withPause(state, paused);
    }
    private static Checkpoint withPause(Checkpoint state, boolean paused) {
        if (state.paused() == paused) return state;
        return new Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), state.work(), paused,
            state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), state.inputInvocation(), state.calls(), state.entry(), state.assembly());
    }
    /** One local active tick, never an instruction budget or wall-clock catch-up. */
    public void elapseTick() {
        if (!loaded || state.paused() || state.status() != Status.SLEEPING) return;
        try {
            if (installation.assembly().isPresent()) {
                var image = installation.assembly().orElseThrow();
                var registers = state.assembly().orElseThrow();
                var next = state.sleepTicks() == 1
                    ? assemblyState(image.completeSleep(registers), state.signals(), state.work(), Optional.empty())
                    : assemblyState(registers, state.signals(), state.work(), Optional.empty(), Status.SLEEPING, state.sleepTicks() - 1);
                requireCapacity(next); state = next; return;
            }
            state = state.sleepTicks() == 1 ? following(state.locals()) : new Checkpoint(state.computer(), state.flow(), state.cursor(),
                state.status(), state.locals(), state.work(), false, Optional.empty(), Optional.empty(), state.sleepTicks() - 1, state.signals(), state.operation(), state.repeats(), Optional.empty(), state.calls(), state.entry());
        } catch (ComputationException error) { fail(error.code(), error.getMessage()); }
    }
    public Optional<String> sourceId() {
        return state.cursor() == program.steps().size() ? Optional.empty() : Optional.of(step().sourceId());
    }
    FlowProgram.Step step() { return program.steps().get(state.cursor()); }
    boolean serialReady() { return loaded && state.status() == Status.READY && (state.assembly().isPresent()
        ? installation.assembly().orElseThrow().serial(state.assembly().orElseThrow()).isPresent():FlowProgram.serial(step())); }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Instruction> serialInstruction() {
        return state.assembly().flatMap(r->installation.assembly().orElseThrow().serial(r));
    }
    boolean serialEncodingReady() { return serialInstruction().filter(op->op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.SEND).isPresent()
        && state.assembly().orElseThrow().serial().filter(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Progress::done).isEmpty(); }
    void advanceSerialEncoding(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits bounds,DataValue.RecordValue configuration) {
        if(!serialReady() || state.paused())throw new IllegalStateException("Serial is not running");
        long work=Math.addExact(state.work(),1);requireAssemblyCycle(work);
        var next=assemblyState(installation.assembly().orElseThrow().advanceSerial(state.assembly().orElseThrow(),bounds,configuration),state.signals(),work,Optional.empty());
        requireCapacity(next);state=next;
    }
    void advanceSerialReceive(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Receive head) {
        if(!serialReady()||state.paused())throw new IllegalStateException("Serial is not running");
        long work=Math.addExact(state.work(),1);requireAssemblyCycle(work);
        var next=assemblyState(installation.assembly().orElseThrow().advanceReceive(state.assembly().orElseThrow(),head,state.flow(),state.work()),state.signals(),work,Optional.empty());
        requireCapacity(next);state=next;
    }
    /** A fully prepared local interface result; the host publishes this continuation with its queue mutation. */
    void completeSerial(DataValue.RecordValue result) {
        if (!serialReady() || state.paused()) throw new IllegalStateException("Serial instruction is not running");
        if(state.assembly().isPresent()) {
            long work=Math.addExact(state.work(),1);requireAssemblyCycle(work);
            var next=assemblyState(installation.assembly().orElseThrow().completeSerial(state.assembly().orElseThrow(),result),state.signals(),work,Optional.empty());
            requireCapacity(next);state=next;return;
        }
        String local = switch (step()) {
            case FlowProgram.SerialSend send -> send.result();
            case FlowProgram.SerialReceive receive -> receive.local();
            case FlowProgram.SerialStatus status -> status.local();
            default -> throw new IllegalStateException("Not a serial instruction");
        };
        if (!installation.locals().get(state.cursor() + 1).fields().get(local).accepts(result))
            throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, step().sourceId(), "Serial result schema differs");
        var fields = new LinkedHashMap<>(state.locals().fields()); fields.put(local, result);
        final long work;
        try { work = Math.incrementExact(state.work()); }
        catch (ArithmeticException overflow) { throw FlowProgram.error(ComputationException.Code.ARITHMETIC_OVERFLOW, step().sourceId(), "Flow work exhausted"); }
        state = new Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), work,
            false, state.expression(), state.diagnostic(), 0, state.signals(), state.operation(), state.repeats(), state.inputInvocation(), state.calls(), state.entry());
        state = following(new DataValue.RecordValue(fields));
    }
    /** Trusted connected-network coordinator only: all peers were proven paused and history was settled. */
    public boolean rejectPausedXBus() {
        if (!loaded || state.paused() || state.status() != Status.WAITING_READ
                && state.status() != Status.WAITING_WRITE && state.status() != Status.WAITING_READABLE) return false;
        if(state.assembly().isPresent()) {
            var op=FlowXBus.at(program,state);
            if(op.result()) {var image=installation.assembly().orElseThrow();var registers=image.completeXBus(state.assembly().orElseThrow(),Optional.of(XBusResult.value(null,op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.READ,true)));var next=assemblyState(registers,state.signals(),state.work(),Optional.empty());requireCapacity(next);state=next;}
            else fail(ComputationException.Code.XBUS_PEER_PAUSED,"XBus peer paused");
            return true;
        }
        var target = resultLocal();
        if (target.isPresent()) {
            var fields = new LinkedHashMap<>(state.locals().fields());
            fields.put(target.orElseThrow(), XBusResult.value(null, step() instanceof FlowProgram.Read, true));
            state = following(new DataValue.RecordValue(fields));
        } else fail(ComputationException.Code.XBUS_PEER_PAUSED, "XBus peer paused");
        return true;
    }
    /** Explicit retirement of the admitted operation, not a delivery acknowledgement or retry. */
    void rejectRetiredXBus() {
        if (!loaded || state.paused() || state.status() != Status.WAITING_WRITE) throw new IllegalStateException("Retirement requires an active waiting write");
        fail(ComputationException.Code.XBUS_OPERATION_RETIRED, "XBus operation retired");
    }
    /** Local hardware conflict, not a remote acknowledgement or a queued retry. */
    void rejectXBusDirection() {
        if (!FlowPinClaims.waiting(state)) throw new IllegalStateException("Direction rejection requires a pending pin operation");
        fail(ComputationException.Code.WRITE_CONFLICT, "XBus pin is already occupied in the opposite direction");
    }
    private Optional<String> resultLocal() {
        return switch (step()) {
            case FlowProgram.Read read -> read.result() ? Optional.of(read.local()) : Optional.empty();
            case FlowProgram.Write write -> write.result();
            case FlowProgram.AwaitReadable wait -> wait.result();
            default -> Optional.empty();
        };
    }
    /** The transport/recovery passes the original payload; only this local continuation wraps results. */
    Checkpoint completedXBus(DataValue.RecordValue payload) {
        if(state.assembly().isPresent()) {
            var op=FlowXBus.at(program,state);DataValue value=null;
            if(op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.READ){if(payload==null||!op.payload().accepts(payload))throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,step().sourceId(),"XBus payload differs");value=op.result()?XBusResult.value(payload,true,false):payload;}
            else if(op.result())value=XBusResult.value(null,false,false);
            var next=assemblyState(installation.assembly().orElseThrow().completeXBus(state.assembly().orElseThrow(),Optional.ofNullable(value)),state.signals(),state.work(),Optional.empty());requireCapacity(next);return next;
        }
        var fields = new LinkedHashMap<>(state.locals().fields());
        if (step() instanceof FlowProgram.Read read) {
            if (payload == null || !read.type().accepts(payload))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, read.sourceId(), "XBus payload differs");
            fields.put(read.local(), read.result() ? XBusResult.value(payload, true, false) : payload);
        } else resultLocal().ifPresent(name -> fields.put(name, XBusResult.value(null, false, false)));
        return following(new DataValue.RecordValue(fields));
    }
    Checkpoint following(DataValue.RecordValue locals) {
        if (installation.assembly().isPresent()) {
            var image = installation.assembly().orElseThrow();
            var name = step() instanceof FlowProgram.Read read ? Optional.of(read.local()) : resultLocal();
            var registers = image.completeXBus(state.assembly().orElseThrow(), name.map(locals::field));
            var next = assemblyState(registers, state.signals(), state.work(), Optional.empty());
            requireCapacity(next); return next;
        }
        return at(state.cursor() + 1, locals);
    }
    private Checkpoint branchTo(int next) { return branchTo(next, state.repeats()); }
    private Checkpoint branchTo(int next, FlowRepeatState repeats) {
        var fields = new LinkedHashMap<String, DataValue>();
        installation.locals().get(next).fields().keySet().forEach(name -> fields.put(name, state.locals().field(name)));
        return at(next, new DataValue.RecordValue(fields), repeats);
    }
    private Checkpoint at(int next, DataValue.RecordValue locals) { return at(next, locals, state.repeats()); }
    private Checkpoint at(int next, DataValue.RecordValue locals, FlowRepeatState repeats) {
        var result = new Checkpoint(state.computer(), state.flow(), next,
            next == program.steps().size() ? Status.COMPLETE : Status.READY, locals, state.work(), state.paused(), Optional.empty(), Optional.empty(), 0, state.signals(), nextOperation(next), repeats, Optional.empty(), state.calls(), state.entry());
        requireCapacity(result);
        return result;
    }
    /** Preserve old forward addresses; a future back-edge must still get a strictly newer identity. */
    private long nextOperation(int next) {
        try { return Math.addExact(state.operation(), Math.max(1L, (long)next - state.cursor())); }
        catch (ArithmeticException exhausted) {
            throw FlowProgram.error(ComputationException.Code.ARITHMETIC_OVERFLOW, step().sourceId(), "Flow operation sequence exhausted");
        }
    }
    void publish(Checkpoint next) { state = next; }
    public long advance(long budget) { return advance(budget, Map.of()); }
    /** One instruction round against immutable inputs sampled before any member executes. Never advances local time. */
    public long advanceRound(Map<String, Integer> inputs) {
        var frozen = Map.copyOf(inputs);
        if (frozen.values().stream().anyMatch(value -> value < SimpleIoState.MIN || value > SimpleIoState.MAX))
            throw new IllegalArgumentException("Invalid Simple I/O input sample");
        return advance(Math.min(1, limits.workPerSlice()), frozen);
    }
    private long advance(long budget, Map<String, Integer> signals) {
        if (budget < 0 || budget > limits.workPerSlice()) throw new IllegalArgumentException("Invalid flow work budget");
        if (installation.assembly().isPresent()) return advanceAssembly(budget, signals);
        long consumed = 0;
        while (loaded && !state.paused() && consumed < budget && (state.status() == Status.READY || state.status() == Status.COMPUTING)) {
            if (serialReady()) break; // The pure runtime cannot consume hardware queues or guess their results.
            try {
                state = new Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(),
                    Math.incrementExact(state.work()), false, state.expression(), state.diagnostic(), 0, state.signals(), state.operation(), state.repeats(), state.inputInvocation(), state.calls(), state.entry());
                consumed++;
                if (state.status() == Status.COMPUTING) advanceExpression();
                else switch (step()) {
                    case FlowProgram.TaskJoin ignored -> throw new IllegalStateException("Task join requires assembly and its Computer owner");
                    case FlowProgram.Monitor ignored -> throw new IllegalStateException("Monitor requires typed ISA execution");
                    case FlowProgram.Broadcast ignored -> throw new IllegalStateException("Broadcast requires its Computer owner");
            case FlowProgram.Watch ignored -> throw new IllegalStateException("Watch requires typed ISA execution");
                    case FlowProgram.TryLaunch ignored -> throw new IllegalStateException("Try-launch requires its Computer owner");
                    case FlowProgram.TaskStart ignored -> throw new IllegalStateException("Task start requires assembly and its Computer owner");
                    case FlowProgram.Reference ignored -> throw new IllegalStateException("Reference requires assembly and its Computer owner");
                    case FlowProgram.Shared ignored -> throw new IllegalStateException("Public cells require assembly and their Computer owner");
                    case FlowProgram.Memory ignored -> throw new IllegalStateException("Memory requires assembly and its Computer owner");
                    case FlowProgram.Atomic ignored -> throw new IllegalStateException("Atomic storage requires assembly");
                    case FlowProgram.EndAtomic ignored -> throw new IllegalStateException("Atomic storage requires assembly");
                    case FlowProgram.Procedure ignored -> state = branchTo(installation.branches().end(state.cursor()) + 1);
                    case FlowProgram.Invoke call -> enterProcedure(call);
                    case FlowProgram.EndProcedure ignored -> returnProcedure();
                    case FlowProgram.Correlation tag -> {
                        var fields = new LinkedHashMap<>(state.locals().fields());
                        fields.put(tag.local(), new DataValue.TextValue(state.flow() + ":" + state.operation()));
                        state = following(new DataValue.RecordValue(fields));
                    }
                    case FlowProgram.SerialSend ignored -> throw new IllegalStateException("Serial host required");
                    case FlowProgram.SerialReceive ignored -> throw new IllegalStateException("Serial host required");
                    case FlowProgram.SerialStatus ignored -> throw new IllegalStateException("Serial host required");
                    case FlowProgram.SignalWrite write -> {
                        var outputs = state.signals().write(write.port(), ((DataValue.IntegerValue)state.locals().field(write.local())).value());
                        signalFollowing(state.locals(), outputs);
                    }
                    case FlowProgram.TimerReset ignored -> throw new IllegalStateException("Timer requires typed ISA execution");
                    case FlowProgram.Ask ignored -> throw new IllegalStateException("Terminal prompt requires typed ISA execution");
                    case FlowProgram.SignalRead read -> {
                        Integer sample = signals.get(read.port());
                        if (sample == null) throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, read.sourceId(), "No available signal sample: " + read.port());
                        var fields = new LinkedHashMap<>(state.locals().fields());
                        fields.put(read.local(), new DataValue.IntegerValue(sample));
                        signalFollowing(new DataValue.RecordValue(fields), state.signals().input(read.port()));
                    }
                    case FlowProgram.XBusStatus status -> {
                        var fields = new LinkedHashMap<>(state.locals().fields());
                        // An absent/error status is zero; this exception does NOT apply to signal/data reads.
                        int code = signals.getOrDefault(status.port(), 0) == SimpleIoState.MAX ? SimpleIoState.MAX : 0;
                        fields.put(status.local(), new DataValue.IntegerValue(code));
                        state = following(new DataValue.RecordValue(fields));
                    }
                    case FlowProgram.Read ignored -> waitAt(Status.WAITING_READ);
                    case FlowProgram.Write ignored -> waitAt(Status.WAITING_WRITE);
                    case FlowProgram.AwaitReadable ignored -> waitAt(Status.WAITING_READABLE);
                    case FlowProgram.Sleep sleep -> {
                        long ticks = ((DataValue.IntegerValue)state.locals().field(sleep.ticksLocal())).value();
                        if (ticks < 0) throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, sleep.sourceId(), "Sleep ticks must be nonnegative");
                        var next = ticks == 0 ? following(state.locals()) : new Checkpoint(state.computer(), state.flow(), state.cursor(),
                            Status.SLEEPING, state.locals(), state.work(), false, Optional.empty(), Optional.empty(), ticks, state.signals(), state.operation(), state.repeats(), Optional.empty(), state.calls(), state.entry());
                        requireCapacity(next); state = next;
                    }
                    case FlowProgram.If branch -> beginInput(branch.condition());
                    case FlowProgram.Otherwise ignored -> state = branchTo(installation.branches().end(state.cursor()) + 1);
                    case FlowProgram.EndIf ignored -> state = branchTo(state.cursor() + 1);
                    case FlowProgram.Repeat repeat -> beginInput(repeat.count());
                    case FlowProgram.EndRepeat ignored -> state = state.repeats().top().remaining() == 1
                        ? branchTo(state.cursor() + 1, state.repeats().exit())
                        : branchTo(installation.branches().start(state.cursor()) + 1, state.repeats().completedIteration());
                    case FlowProgram.Forever ignored -> state = following(state.locals());
                    case FlowProgram.EndForever ignored -> state = branchTo(installation.branches().start(state.cursor()));
                    case FlowProgram.Until until -> beginInput(until.condition());
                    case FlowProgram.StopScript ignored -> throw new IllegalStateException("Stop script requires typed ISA execution");
                    case FlowProgram.WaitUntil ignored -> throw new IllegalStateException("Wait until requires typed ISA execution");
                    case FlowProgram.EndUntil ignored -> state = branchTo(installation.branches().start(state.cursor()));
                    case FlowProgram.Evaluate evaluate -> {
                        beginExpression(evaluate.expression());
                    }
                }
            } catch (ComputationException error) { fail(error.code(), error.getMessage()); }
            catch (ArithmeticException overflow) { fail(ComputationException.Code.ARITHMETIC_OVERFLOW, "Flow counter overflow"); }
        }
        return consumed;
    }
    private void signalFollowing(DataValue.RecordValue locals, SimpleIoState signals) {
        int next = state.cursor() + 1;
        var after = new Checkpoint(state.computer(), state.flow(), next, next == program.steps().size() ? Status.COMPLETE : Status.READY,
            locals, state.work(), state.paused(), Optional.empty(), Optional.empty(), 0, signals, nextOperation(next), state.repeats(), Optional.empty(), state.calls(), state.entry());
        requireCapacity(after); state = after;
    }
    private void beginInput(FlowInput input) {
        if (input instanceof FlowInput.Expression graph) beginExpression(graph.entry());
        else finishInput(input.read(state.locals()));
    }
    private void finishInput(DataValue result) {
        if (step() instanceof FlowProgram.Repeat repeat) {
            long remaining = ((DataValue.IntegerValue)result).value();
            if (remaining < 0) throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, repeat.sourceId(), "Repeat count must be nonnegative");
            state = remaining == 0 ? branchTo(installation.branches().end(state.cursor()) + 1)
                : at(state.cursor() + 1, state.locals(), state.repeats().enter(state.cursor(), remaining));
        } else if (step() instanceof FlowProgram.If) state = branchTo(((DataValue.BooleanValue)result).value()
            ? state.cursor() + 1 : installation.branches().alternate(state.cursor()));
        else state = branchTo(((DataValue.BooleanValue)result).value() ? installation.branches().end(state.cursor()) + 1 : state.cursor() + 1);
    }
    private FlowInput loopInput() {
        return FlowProgram.valueInput(step());
    }
    private String expressionEntry() {
        if (step() instanceof FlowProgram.Evaluate evaluate) return evaluate.expression();
        if (loopInput() instanceof FlowInput.Expression graph) return graph.entry();
        return null;
    }
    private void beginExpression(String entry) {
        var inputs = expressionInputs(entry);
        var invocation = loopInput() instanceof FlowInput.Expression graph
            ? Optional.of(new FlowInput.Invocation(graph.entry(), graph.output(), inputs)) : Optional.<FlowInput.Invocation>empty();
        // Reserve the immutable call context before starting the pure engine, without publishing a half-started state.
        var prepared = new Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), state.work(),
            state.paused(), state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), invocation, state.calls(), state.entry());
        var compiled = program.compileExpressions(limits, remainingMemory(prepared)).select(entry);
        expression = new ComputationMachine(compiled);
        expression.begin(inputs);
        publishExpression(invocation);
    }
    private DataValue.RecordValue expressionInputs(String entry) {
        var fields = new LinkedHashMap<String, DataValue>();
        program.expressions().program().entries().get(entry).graph().inputs().forEach((name, type) -> {
            var value = state.locals().field(step() instanceof FlowProgram.Evaluate call ? call.argument(name)
                : loopInput() instanceof FlowInput.Expression graph ? graph.argument(name) : name);
            if (!type.accepts(value)) throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, step().sourceId(), "Expression input differs");
            fields.put(name, value);
        });
        return new DataValue.RecordValue(fields);
    }
    private void advanceExpression() { expression.step(); publishExpression(state.inputInvocation()); }
    private void publishExpression(Optional<FlowInput.Invocation> invocation) {
        switch (expression.report().status()) {
            case FAILED -> { var diagnostic = expression.report().diagnostic().orElseThrow(); fail(diagnostic.code(), diagnostic.message()); }
            case SUCCEEDED -> {
                var output = expression.report().output().orElseThrow();
                if (loopInput() instanceof FlowInput.Expression graph) finishInput(output.field(graph.output()));
                else {
                    var fields = new LinkedHashMap<>(state.locals().fields());
                    ((FlowProgram.Evaluate) step()).returns(output.fields().keySet()).forEach((port, local) -> fields.put(local, output.field(port)));
                    state = following(new DataValue.RecordValue(fields));
                }
                expression = null;
            }
            case RUNNING -> state = new Checkpoint(state.computer(), state.flow(), state.cursor(), Status.COMPUTING, state.locals(),
                state.work(), false, Optional.of(expression.checkpoint()), Optional.empty(), 0, state.signals(), state.operation(), state.repeats(), invocation, state.calls(), state.entry());
            default -> throw new IllegalStateException("Unstarted expression");
        }
    }
    private void waitAt(Status status) {
        state = new Checkpoint(state.computer(), state.flow(), state.cursor(), status, state.locals(), state.work(), false, Optional.empty(), Optional.empty(), 0, state.signals(), state.operation(), state.repeats(), Optional.empty(), state.calls(), state.entry());
    }
    private void fail(ComputationException.Code code, String message) {
        if (installation.assembly().isPresent()) {
            state = assemblyState(state.assembly().orElseThrow(), state.signals(), state.work(),
                Optional.of(new ComputationMachine.Diagnostic(code, step().sourceId(), message)));
            return;
        }
        state = new Checkpoint(state.computer(), state.flow(), state.cursor(), Status.FAILED, state.locals(), state.work(), false,
            Optional.empty(), Optional.of(new ComputationMachine.Diagnostic(code, step().sourceId(), message)), 0, state.signals(), state.operation(), state.repeats(), Optional.empty(), state.calls(), state.entry());
        expression = null;
    }
    private long remainingMemory() { return remainingMemory(state); }
    private void enterProcedure(FlowProgram.Invoke call) {
        int next = installation.branches().procedure(call.procedure()) + 1;
        var fields = new LinkedHashMap<String, DataValue>();
        call.bindings().arguments().forEach((port, local) -> fields.put(port, state.locals().field(local)));
        var candidate = new Checkpoint(state.computer(), state.flow(), next, Status.READY, new DataValue.RecordValue(fields), state.work(), state.paused(),
            Optional.empty(), Optional.empty(), 0, state.signals(), nextOperation(next), FlowRepeatState.EMPTY, Optional.empty(), state.calls().enter(state), state.entry());
        requireCapacity(candidate); state = candidate;
    }
    private void returnProcedure() {
        if (state.calls().frames().isEmpty() && state.entry().isPresent()) {
            var procedure = (FlowProgram.Procedure)program.steps().get(installation.branches().entryOwner());
            var fields = new LinkedHashMap<String, DataValue>();
            procedure.returns().forEach((port, local) -> fields.put(port, state.locals().field(local)));
            state = at(program.steps().size(), new DataValue.RecordValue(fields));
            return;
        }
        var frame = state.calls().top();
        var call = (FlowProgram.Invoke)program.steps().get(frame.cursor());
        var procedure = (FlowProgram.Procedure)program.steps().get(installation.branches().procedure(call.procedure()));
        var fields = new LinkedHashMap<>(frame.locals().fields());
        call.bindings().returns().forEach((port, local) -> fields.put(local, state.locals().field(procedure.returns().get(port))));
        int next = frame.cursor() + 1;
        var candidate = new Checkpoint(state.computer(), state.flow(), next, next == program.steps().size() ? Status.COMPLETE : Status.READY,
            new DataValue.RecordValue(fields), state.work(), state.paused(), Optional.empty(), Optional.empty(), 0, state.signals(), nextOperation(next),
            frame.repeats(), Optional.empty(), state.calls().exit(), state.entry());
        requireCapacity(candidate); state = candidate;
    }
    private long remainingMemory(Checkpoint checkpoint) {
        long remaining = limits.memoryUnits() - checkpoint.controlUnits();
        if (remaining < 0) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, step().sourceId(), "Flow locals exceed memory capacity");
        return remaining;
    }
    private void requireRegisters(RegisterState registers, String location) {
        int occupied = registers.registers().size();
        if (occupied > limits.registerSlots()) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT,
            location, "Active ISA frame needs " + occupied + " registers; hardware has " + limits.registerSlots());
    }
    private void requireCapacity(Checkpoint checkpoint) {
        checkpoint.assembly().ifPresent(registers -> requireRegisters(registers, "flow"));
        if (retainedUnits(checkpoint) > limits.memoryUnits())
            throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "flow", "Flow continuation exceeds memory capacity");
    }
    /** Includes the immutable pool once, so a task owner can subtract other contexts' private portions. */
    long retainedUnits() { return retainedUnits(state); }
    long sharedUnits() { return installation.assembly().map(image -> image.constantUnits())
        .orElseGet(() -> Math.addExact(FlowProgram.inputConstantUnits(program.steps()), installation.expressions().usage().constantUnits())); }
    private long retainedUnits(Checkpoint checkpoint) {
        if (installation.assembly().isPresent()) return Math.addExact(checkpoint.controlUnits(), sharedUnits());
        // Empty initial/current pure RAM images remain part of the existing machine's accounting.
        long pure = checkpoint.expression().map(MachineCheckpoint::arenaUnits).orElse(
            Math.addExact(installation.expressions().usage().constantUnits(), Math.multiplyExact(2, installation.expressions().usage().initialMemoryUnits())));
        return Math.addExact(FlowProgram.inputConstantUnits(program.steps()), Math.addExact(checkpoint.controlUnits(), pure));
    }
    /** One speculative owner instruction. Bank and registers must be preflighted and published together. */
    public boolean taskJoinReady() { return state.status() == Status.READY && installation.assembly().map(i -> i.taskWait(state.assembly().orElseThrow()).isPresent() || i.taskClaim(state.assembly().orElseThrow()).isPresent()).orElse(false); }
    public boolean atTaskWait() { return installation.assembly().orElseThrow().taskWait(state.assembly().orElseThrow()).isPresent(); }
    public ModuleDefinition.Key taskExpected() {
        var image = installation.assembly().orElseThrow(); var registers = state.assembly().orElseThrow();
        return image.taskWait(registers).map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TaskWait::procedure)
            .orElseGet(() -> image.taskClaim(registers).orElseThrow().procedure());
    }
    public DataValue taskHandleValue() { return installation.assembly().orElseThrow().taskHandle(state.assembly().orElseThrow()); }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterTaskGroup.Dispatch> eventInstruction() {
        return installation.assembly().flatMap(image -> image.event(state.assembly().orElseThrow()));
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMonitor> monitorInstruction() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.monitor(state.assembly().orElseThrow())) : Optional.empty();
    }
    void executeMonitor() {
        long work = Math.addExact(state.work(), 1);
        requireAssemblyCycle(work);
        var registers = installation.assembly().orElseThrow().completeMonitor(state.assembly().orElseThrow());
        var next = assemblyState(registers, state.signals(), work, Optional.empty());
        requireCapacity(next);
        state = next;
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterTimer> timerInstruction() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.timer(state.assembly().orElseThrow())) : Optional.empty();
    }
    void executeTimer(long ticks) {
        long work = Math.addExact(state.work(), 1);
        requireAssemblyCycle(work);
        var registers = installation.assembly().orElseThrow().completeTimer(state.assembly().orElseThrow(), ticks);
        var next = assemblyState(registers, state.signals(), work, Optional.empty());
        requireCapacity(next);
        state = next;
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterPrompt> promptInstruction() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.prompt(state.assembly().orElseThrow())) : Optional.empty();
    }
    DataValue.TextValue question() {
        return installation.assembly().orElseThrow().question(state.assembly().orElseThrow());
    }
    void executePrompt(DataValue.TextValue answer) {
        long work = Math.addExact(state.work(), 1);
        requireAssemblyCycle(work);
        var image = installation.assembly().orElseThrow();
        var registers = state.assembly().orElseThrow();
        var next = promptInstruction().orElseThrow().asks()
            ? assemblyState(registers, state.signals(), work, Optional.empty(), Status.WAITING_ANSWER)
            : assemblyState(image.completePrompt(registers, answer), state.signals(), work, Optional.empty());
        requireCapacity(next);
        state = next;
    }
    void acceptAnswer(DataValue.TextValue answer) {
        if (state.paused() || state.status() != Status.WAITING_ANSWER) throw new IllegalStateException("No active question");
        var image = installation.assembly().orElseThrow();
        var next = assemblyState(image.completePrompt(state.assembly().orElseThrow(), answer), state.signals(), state.work(), Optional.empty());
        requireCapacity(next);
        state = next;
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterKeyDown> keyDownInstruction() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.keyDown(state.assembly().orElseThrow())) : Optional.empty();
    }
    void executeKeyDown(boolean held) {
        long work = Math.addExact(state.work(), 1);
        requireAssemblyCycle(work);
        var registers = installation.assembly().orElseThrow().completeKeyDown(state.assembly().orElseThrow(), held);
        var next = assemblyState(registers, state.signals(), work, Optional.empty());
        requireCapacity(next); state = next;
    }
    Optional<Boolean> eventMode(){return installation.assembly().flatMap(image->image.eventMode(state.assembly().orElseThrow()));}
    boolean eventReady() { return state.status() == Status.READY && eventMode().isPresent(); }
    void admitEvent(boolean await) {
        long work = Math.addExact(state.work(), 1);
        var registers = state.assembly().orElseThrow();
        state = await ? assemblyState(registers, state.signals(), work, Optional.empty(), Status.WAITING_TASK)
            : assemblyState(installation.assembly().orElseThrow().completeEvent(registers), state.signals(), work, Optional.empty());
    }
    void wakeEvent() {
        if (state.status() != Status.WAITING_TASK || !eventMode().orElse(false))
            throw new IllegalStateException("Not an event waiter");
        state = assemblyState(installation.assembly().orElseThrow().completeEvent(state.assembly().orElseThrow()),
            state.signals(), state.work(), Optional.empty());
    }
    public void admitTaskWait(boolean ready) {
        state = assemblyState(state.assembly().orElseThrow(), state.signals(), Math.addExact(state.work(), 1), Optional.empty(), Status.WAITING_TASK);
        if (ready) wakeTaskWait();
    }
    public void wakeTaskWait() {
        if (state.status() != Status.WAITING_TASK) throw new IllegalStateException("Not a task waiter");
        state = assemblyState(installation.assembly().orElseThrow().completeTaskWait(state.assembly().orElseThrow()), state.signals(), state.work(), Optional.empty());
    }
    public void completeTaskClaim(DataValue.RecordValue outcome) {
        // No transient caller-only capacity check: the owner removes the child in this same candidate.
        state = assemblyState(installation.assembly().orElseThrow().completeTaskClaim(state.assembly().orElseThrow(), outcome), state.signals(), Math.addExact(state.work(), 1), Optional.empty());
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TryLaunch> tryLaunch() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.tryLaunch(state.assembly().orElseThrow())) : Optional.empty();
    }
    void completeTryLaunch(boolean started) {
        var image = installation.assembly().orElseThrow();
        state = assemblyState(image.completeTryLaunch(state.assembly().orElseThrow(), started),
            state.signals(), Math.addExact(state.work(), 1), Optional.empty());
    }
    public Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TaskStart> taskStart() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.taskStart(state.assembly().orElseThrow())) : Optional.empty();
    }
    /** Reserve the final representable cycle for a durable failed attempt, including owner operations. */
    void requireAssemblyCycle(long nextWork) {
        long lastCycle = program.usesXBusAssembly() ? (Long.MAX_VALUE - program.steps().size() - 1) / 2 : Long.MAX_VALUE;
        if (nextWork >= lastCycle) throw FlowProgram.error(ComputationException.Code.ARITHMETIC_OVERFLOW,
            step().sourceId(), "Assembly cycle counter exhausted");
    }
    public DataValue.RecordValue taskArguments() { return installation.assembly().orElseThrow().taskArguments(state.assembly().orElseThrow()); }
    public void completeTaskStart(com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle handle) {
        state = assemblyState(installation.assembly().orElseThrow().completeTaskStart(state.assembly().orElseThrow(), handle), state.signals(), Math.addExact(state.work(), 1), Optional.empty());
    }
    public void failTaskStart(ComputationException.Code code, String message) {
        var registers = state.assembly().orElseThrow();
        state = assemblyState(registers.transaction().isPresent() ? registers.withTransaction(false) : registers, state.signals(), Math.addExact(state.work(), 1),
            Optional.of(new ComputationMachine.Diagnostic(code, step().sourceId(), message)));
    }
    Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TaskStop> stopInstruction() {
        return state.status() == Status.READY ? installation.assembly().flatMap(image -> image.taskStop(state.assembly().orElseThrow())) : Optional.empty();
    }
    void executeStop() {
        var stop = stopInstruction().orElseThrow();
        var image = installation.assembly().orElseThrow();
        var registers = state.assembly().orElseThrow();
        long work = Math.addExact(state.work(), 1);
        state = stop.all() ? assemblyState(registers, state.signals(), work,
            Optional.of(cancelDiagnostic(ComputationException.Code.TASK_CANCELLED_ADMITTED, step().sourceId())))
            : assemblyState(image.completeStopOthers(registers), state.signals(), work, Optional.empty());
    }
    static Checkpoint cancelVictim(FlowProgram program, Checkpoint state, boolean paused) {
        if (FlowTasks.terminal(state)) return withPause(state, paused);
        var code = state.status() == Status.READY ? ComputationException.Code.TASK_CANCELLED : ComputationException.Code.TASK_CANCELLED_ADMITTED;
        return new Checkpoint(state.computer(), state.flow(), state.cursor(), Status.FAILED, state.locals(), state.work(), paused,
            Optional.empty(), Optional.of(cancelDiagnostic(code, program.steps().get(state.cursor()).sourceId())), 0,
            state.signals(), state.operation(), FlowRepeatState.EMPTY, Optional.empty(), FlowCallStack.EMPTY, state.entry(),
            state.assembly().map(r -> r.transaction().isPresent() ? r.withTransaction(false) : r));
    }
    private static ComputationMachine.Diagnostic cancelDiagnostic(ComputationException.Code code, String source) {
        return new ComputationMachine.Diagnostic(code, source, "Task cancelled by a local stop block");
    }
    public com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory advanceOwnedMemory(
            com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory memory, Map<String, Integer> samples) {return advanceOwnedMemory(memory,samples,0);}
    public com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory advanceOwnedMemory(
            com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory memory, Map<String,Integer> samples,long generation) {
        if (!loaded || state.paused() || state.status() != Status.READY) return memory;
        var before = state; var image = installation.assembly().orElseThrow();
        long work = Math.addExact(state.work(), 1);
        try {
            requireAssemblyCycle(work);
            if (memory.active(state.flow()) && (image.sleepTicks(state.assembly().orElseThrow()).isPresent()
                || image.xbus(state.assembly().orElseThrow()).isPresent() || serialReady()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, step().sourceId(), "Commit or roll back before Sleep or device waiting");
            if(image.referenceOp(state.assembly().orElseThrow()).isPresent()){
                var ref=image.referenceOp(state.assembly().orElseThrow()).orElseThrow();
                if(ref.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterReference.Kind.WRITE){
                    if(memory.active(state.flow()))throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT,step().sourceId(),"Public reference access during transaction");
                    // Ref-1 publication peak: old bank remains in the owner's ledger; reserve the
                    // candidate bank version + changed cell version + candidate payload. Unchanged
                    // installed schema/targets retain their one owner, as in the indexed bank model.
                    long candidate=Math.addExact(2,state.assembly().orElseThrow().registers().get(ref.value()).storageUnits());
                    if(candidate>limits.memoryUnits()-retainedUnits())throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT,step().sourceId(),"Reference write candidate exceeds Computer memory");
                }
                var result=image.stepReference(state.assembly().orElseThrow(),memory,state.computer(),generation,state.flow());state=assemblyState(result.registers(),state.signals(),work,Optional.empty());memory=result.memory();}
            else if (image.memoryOp(state.assembly().orElseThrow()).isPresent()) {
                var result = image.stepMemory(state.assembly().orElseThrow(), memory, state.flow());
                state = assemblyState(result.registers(), state.signals(), work, Optional.empty()); memory = result.memory();
            } else advanceRound(samples);
            if (memory.active(state.flow()) && state.status() == Status.COMPLETE)
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, before.cursor() < program.steps().size() ? program.steps().get(before.cursor()).sourceId() : "memory", "Task ended with an open transaction");
        } catch (ComputationException error) {
            state = before; failOwnedMemory(error.code(), error.getMessage());
        }
        if (state.status() == Status.FAILED && memory.active(state.flow())) {
            memory = memory.rollback(state.flow());
            state = assemblyState(state.assembly().orElseThrow().withTransaction(false), state.signals(), state.work(), state.diagnostic());
        }
        return memory;
    }
    public void failOwnedMemory(ComputationException.Code code, String message) {
        state = assemblyState(state.assembly().orElseThrow().withTransaction(false), state.signals(), Math.addExact(state.work(), 1),
            Optional.of(new ComputationMachine.Diagnostic(code, step().sourceId(), message)));
    }
    void validateMemory(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory memory) {
        if (installation.assembly().isEmpty()) return;
        requireState(state.assembly().orElseThrow().transaction().orElse(false) == memory.active(state.flow()), "Transaction identity differs from the task continuation");
        if (state.status() == Status.FAILED) return;
        var op = installation.assembly().orElseThrow().memoryOp(state.assembly().orElseThrow());
        int progress = memory.preparationIndex(state.flow());
        boolean preparing = op.filter(m -> m.kind() == com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemoryOp.Kind.PREPARE_CELL
            || m.kind() == com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemoryOp.Kind.COMMIT).isPresent();
        requireState(preparing == (progress >= 0), "Memory preparation differs from the current instruction");
        if (preparing) requireState(progress == (op.orElseThrow().cell() < 0 ? memory.size() : op.orElseThrow().cell()), "Memory preparation cursor differs");
    }
    private void validateAssembly() {
        var image = installation.assembly().orElseThrow();
        requireState(state.assembly().isPresent(), "Assembly installation is missing its register file");
        var registers = state.assembly().orElseThrow();
        boolean waiting = FlowPinClaims.waiting(state);
        boolean sleeping = state.status() == Status.SLEEPING;
        boolean taskWaiting = state.status() == Status.WAITING_TASK;
        boolean answerWaiting = state.status() == Status.WAITING_ANSWER;
        boolean cancelledBeforeAdmission = state.cancelled() && state.diagnostic().orElseThrow().code() == ComputationException.Code.TASK_CANCELLED;
        boolean attempted = state.status() == Status.FAILED && !cancelledBeforeAdmission || waiting || sleeping || taskWaiting || answerWaiting;
        if (state.cancelled()) {
            requireState(program.usesGroupStop(), "Cancellation requires a local stop program");
            if (!cancelledBeforeAdmission) requireState(image.sleepTicks(registers).orElse(0) > 0 || image.xbus(registers).isPresent()
                || image.taskWait(registers).isPresent() || image.event(registers).filter(e -> e.await()).isPresent()
                || image.taskStop(registers).filter(stop -> stop.all()).isPresent()
                || image.prompt(registers).filter(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterPrompt::asks).isPresent(),
                "Cancellation has no admitted wait or stop instruction");
        }
        registers.serial().ifPresent(p->p.requireEnvelope(limits.memoryUnits()));
        // Preserve legacy constructor admission; equality and v6 APPEND require bounded reconstruction.
        if (program.usesBoundedAssemblyRestore()) image.validate(registers, state.work(), attempted, this::requireAssemblyRestoreTemporary);
        else image.validate(registers, state.work(), attempted);
        image.validateSignals(registers, state.work(), attempted, state.signals().outputs());
        requireState(state.expression().isEmpty() && state.inputInvocation().isEmpty()
            && state.calls().frames().isEmpty() && state.repeats().frames().isEmpty(),
            "Unexpected legacy frame in assembly continuation");
        requireState(sleeping == (state.sleepTicks() > 0), "Invalid assembly remaining sleep time");
        if (sleeping) requireState(image.sleepTicks(registers).orElse(-1) >= state.sleepTicks(), "Sleep continuation differs from its admitted instruction");
        registers.serial().ifPresent(p->p.checkReceiveWork(state.work()));
        requireState(state.cursor() == image.source(registers.pc()) && state.locals().equals(image.locals(registers)), "Assembly source/locals projection differs");
        requireState(state.operation() == assemblyOperation(state.work(), state.cursor(), cancelledBeforeAdmission ? Status.READY : state.status()), "Assembly operation projection differs");
        requireState(state.status() == Status.READY || state.status() == Status.COMPLETE || state.status() == Status.FAILED || waiting || sleeping || taskWaiting || answerWaiting, "Invalid assembly status");
        if (answerWaiting) {
            requireState(image.prompt(registers).filter(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterPrompt::asks).isPresent(),
                "Terminal wait differs from its admitted instruction");
            FlowPrompts.requireText(image.question(registers));
        }
        if (taskWaiting) requireState((program.usesTaskJoinAssembly() && image.taskWait(registers).isPresent()) || (program.usesEvents() && image.event(registers).filter(e -> e.await() && !e.receivers().isEmpty()).isPresent()), "Task wait differs from its admitted instruction");
        if (waiting) requireState(image.xbus(registers).map(d -> switch (d.kind()) {
            case READ -> state.status() == Status.WAITING_READ;
            case WRITE -> state.status() == Status.WAITING_WRITE;
            case WAIT_READABLE -> state.status() == Status.WAITING_READABLE;
        }).orElse(false), "Assembly wait differs from the admitted device instruction");
        requireState((state.status() == Status.COMPLETE) == image.complete(registers), "Invalid assembly end position");
        requireState((state.status() == Status.FAILED) == state.diagnostic().isPresent(), "Invalid assembly diagnostic");
        if (state.diagnostic().isPresent()) requireState(state.diagnostic().orElseThrow().location().equals(step().sourceId()), "Assembly diagnostic source differs");
    }
    private Checkpoint assemblyState(RegisterState registers, SimpleIoState outputs, long work, Optional<ComputationMachine.Diagnostic> diagnostic) {
        var image = installation.assembly().orElseThrow();
        return assemblyState(registers, outputs, work, diagnostic, diagnostic.isPresent() ? Status.FAILED : image.complete(registers) ? Status.COMPLETE : Status.READY);
    }
    /** Two phases per paid instruction distinguish an admitted wait from its consumed successor.
     * The fixed source span keeps positions >= source cursors even after forward jumps. */
    private long assemblyOperation(long work, int source, Status status) {
        if (!program.usesXBusAssembly()) return Math.max(work, source);
        long phase = status == Status.READY || status == Status.COMPLETE ? 1 : 0;
        return Math.addExact(Math.addExact(Math.multiplyExact(work, 2), program.steps().size()), phase);
    }
    private Checkpoint assemblyState(RegisterState registers, SimpleIoState outputs, long work,
            Optional<ComputationMachine.Diagnostic> diagnostic, Status status) {
        return assemblyState(registers, outputs, work, diagnostic, status, 0);
    }
    private Checkpoint assemblyState(RegisterState registers, SimpleIoState outputs, long work,
            Optional<ComputationMachine.Diagnostic> diagnostic, Status status, long sleepTicks) {
        var image = installation.assembly().orElseThrow(); int source = image.source(registers.pc());
        if (registers.registers().size() > limits.registerSlots()) requireRegisters(registers, step().sourceId());
        return new Checkpoint(state.computer(), state.flow(), source, status,
            image.locals(registers), work, state.paused(), Optional.empty(), diagnostic, sleepTicks, outputs, assemblyOperation(work, source, status),
            FlowRepeatState.EMPTY, Optional.empty(), FlowCallStack.EMPTY, state.entry(), Optional.of(registers));
    }
    private void requireAssemblyRestoreTemporary(long temporary) {
        long retained = Math.addExact(state.restoreControlUnits(), sharedUnits());
        if (temporary > limits.memoryUnits() - retained)
            throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "assembly", "Cold continuation buffers exceed memory capacity");
    }
    private void requireAssemblyTemporary(long temporary) {
        long retained=retainedUnits(state), available=limits.memoryUnits()-retained;
        if (temporary > available)
            throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "assembly",
                "Instruction workspace exceeds memory capacity (PC="+state.assembly().orElseThrow().pc()
                    +", required="+temporary+", retained="+retained+", available="+available+")");
    }
    private long advanceAssembly(long budget, Map<String, Integer> samples) {
        long consumed = 0; var image = installation.assembly().orElseThrow();
        while (loaded && !state.paused() && state.status() == Status.READY && consumed < budget) {
            long nextWork = Math.addExact(state.work(), 1); consumed++;
            try {
                requireAssemblyCycle(nextWork);
                var registers = state.assembly().orElseThrow();
                if (image.drawsRandom(registers)) {
                    var next = assemblyState(image.tryRandom(registers, state.flow(), state.work()), state.signals(), nextWork, Optional.empty());
                    requireCapacity(next); state = next; continue;
                }
                if (image.correlation(registers).isPresent()) {
                    var next = assemblyState(image.completeCorrelation(registers, state.flow(), state.work()), state.signals(), nextWork, Optional.empty());
                    requireCapacity(next); state = next; continue;
                }
                var sleep = image.sleepTicks(registers);
                if (sleep.isPresent()) {
                    long ticks = sleep.orElseThrow();
                    if (ticks < 0) throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, step().sourceId(), "Sleep ticks must be nonnegative");
                    var next = ticks == 0 ? assemblyState(image.completeSleep(registers), state.signals(), nextWork, Optional.empty())
                        : assemblyState(registers, state.signals(), nextWork, Optional.empty(), Status.SLEEPING, ticks);
                    requireCapacity(next); state = next; continue;
                }
                var device = image.xbus(registers);
                if (device.isPresent()) {
                    var status = switch (device.orElseThrow().kind()) {
                        case READ -> Status.WAITING_READ;
                        case WRITE -> Status.WAITING_WRITE;
                        case WAIT_READABLE -> Status.WAITING_READABLE;
                    };
                    var next = assemblyState(state.assembly().orElseThrow(), state.signals(), nextWork, Optional.empty(), status);
                    requireCapacity(next); state = next; continue;
                }
                if (image.serial(registers).isPresent()) { consumed--;break; }
                var result = image.step(state.assembly().orElseThrow(), samples, state.signals().outputs(), nextWork, this::requireAssemblyTemporary);
                var next = assemblyState(result.registers(), new SimpleIoState(result.outputs()), nextWork, Optional.empty());
                requireCapacity(next); state = next;
            } catch (ComputationException error) {
                state = assemblyState(state.assembly().orElseThrow(), state.signals(), nextWork,
                    Optional.of(new ComputationMachine.Diagnostic(error.code(), step().sourceId(), error.getMessage())));
            }
        }
        return consumed;
    }
}
