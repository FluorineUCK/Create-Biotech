package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;

/** Owned sequential-program state, never an extra parallel Computer. */
public final class FlowBlockHost {
    /** Internal editor projection only. It grants neither execution nor reset/receipt authority. */
    public record Source(FlowProgram program, DataValue.RecordValue initialInputs) {}
    public record Saved(FlowProgram program, DataValue.RecordValue initialInputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts,
        FlowControl control, FlowTasks tasks, FlowPinClaims pinClaims, List<XBusCancellation> cancellations, RegisterMemory memory, long timerTicks, FlowPrompts prompts, Set<String> monitors, FlowPrivateMonitors privateMonitors) {
        public Saved {
            Objects.requireNonNull(program); Objects.requireNonNull(initialInputs); Objects.requireNonNull(state);com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(initialInputs);
            if (timerTicks < 0 || timerTicks > 0 && !program.usesTimer()) throw new IllegalArgumentException("Invalid activity timer state");
            receipts = List.copyOf(receipts); Objects.requireNonNull(control);
            Objects.requireNonNull(tasks); tasks.validate(program, state);
            Objects.requireNonNull(pinClaims).validate(program, state, tasks);
            cancellations = List.copyOf(cancellations);
            var cancelled = new HashSet<XBusReceipt.Position>();
            for (var witness : cancellations) {
                if (witness.writers().stream().noneMatch(p -> p.computer().equals(state.computer()))) throw new IllegalArgumentException("Cancellation belongs to another owner");
                for (var p : witness.writers()) if (p.computer().equals(state.computer())) {
                    var current = context(state, tasks, control.generation(), p);
                    if (current.isEmpty() && !(p.generation() < control.generation() || p.generation() == control.generation() && tasks.slots().supersedes(p)))
                        throw new IllegalArgumentException("Cancellation task identity differs from owner");
                    if (current.isPresent() && (p.cursor() >= program.steps().size()
                        || FlowXBus.at(program,p.cursor(),p.instruction()).kind()!=com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.WRITE || !FlowXBus.at(program,p.cursor(),p.instruction()).pin().equals(p.pin())))
                        throw new IllegalArgumentException("Cancellation source differs from installed write");
                }
                for (var p : witness.writers()) if (!cancelled.add(p)) throw new IllegalArgumentException("Overlapping cancellations");
                for (var r : receipts) if (r.positions().stream().anyMatch(witness.writers()::contains)) throw new IllegalArgumentException("Consumed operation cannot be cancelled");
            }
            var contexts = new ArrayList<>(tasks.contexts()); contexts.add(state);
            monitors = Set.copyOf(monitors);
            if (monitors.stream().anyMatch(cell -> !program.usesMonitor() || !program.canShowMonitor(cell)))
                throw new IllegalArgumentException("Unavailable public monitor in saved state");
            Objects.requireNonNull(privateMonitors).validate(program, contexts);
            Objects.requireNonNull(prompts);
            if ((!prompts.equals(FlowPrompts.EMPTY) && !program.usesPrompts())
                || !new HashSet<>(prompts.pending()).equals(waitingForAnswer(state, tasks)))
                throw new IllegalArgumentException("Terminal questions differ from the waiting tasks");
            Objects.requireNonNull(memory);
            if (!memory.cells().fields().keySet().equals(program.expressions().program().cells().keySet()))
                throw new IllegalArgumentException("Memory declarations differ from owner");
            for (var id : memory.owners()) if (contexts.stream().noneMatch(s -> s.flow().equals(id) && s.status() == FlowRuntime.Status.READY))
                throw new IllegalArgumentException("Transaction must belong to a live runnable task");
            for (var s : contexts) if (s.diagnostic().filter(d -> d.code() == ComputationException.Code.XBUS_OPERATION_RETIRED).isPresent()
                && cancellations.stream().noneMatch(c -> c.writers().stream().anyMatch(p -> context(state, tasks, control.generation(), p).filter(s::equals).isPresent()
                    && p.operation() == s.operation() && p.cursor() == s.cursor()))) throw new IllegalArgumentException("Missing cancellation proof");
            if (state.entry().isPresent()) throw new IllegalArgumentException("Procedure tasks require a managed task owner, not the legacy single-flow owner");
            if (control.peers().containsKey(state.computer())) throw new IllegalArgumentException("Computer cannot be its own peer");
            if (!new DataType.RecordType(program.inputs()).accepts(initialInputs))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, "flow", "Initial flow input schema differs");
            var previous = new HashMap<UUID, Long>();
            for (var receipt : receipts) {
                if (receipt.local(state.computer()).isEmpty()) throw new IllegalArgumentException("Receipt belongs to another Computer");
                for (var own : receipt.local(state.computer())) {
                    var context = context(state, tasks, control.generation(), own);
                    if (context.isEmpty()) {
                        if (own.generation() == control.generation() && tasks.slots().supersedes(own)) continue;
                        throw new IllegalArgumentException("Receipt task identity differs from owner");
                    }
                    receipt.validate(program, context.orElseThrow()); long operation = own.operation();
                    if (operation <= previous.getOrDefault(own.flow(), -1L)) throw new IllegalArgumentException("Duplicate or unordered task XBus receipt");
                    previous.put(own.flow(), operation);
                }
                for (var remote : receipt.remote(state.computer())) {
                    var known = control.peers().get(remote.computer());
                    if (known == null || !known.coversReceipt(remote))
                        throw new IllegalArgumentException("Receipt conflicts with retained peer frontier");
                }
            }
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts,
                FlowControl control, FlowTasks tasks, FlowPinClaims pins, List<XBusCancellation> cancellations,
                RegisterMemory memory, FlowPrompts prompts, Set<String> monitors, FlowPrivateMonitors privateMonitors) {
            this(program, inputs, state, receipts, control, tasks, pins, cancellations, memory, 0, prompts, monitors, privateMonitors);
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts,
                FlowControl control, FlowTasks tasks, FlowPinClaims pins, List<XBusCancellation> cancellations, RegisterMemory memory) {
            this(program, inputs, state, receipts, control, tasks, pins, cancellations, memory, FlowPrompts.EMPTY, Set.of(), FlowPrivateMonitors.EMPTY);
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts,
                FlowControl control, FlowTasks tasks, FlowPinClaims pins, List<XBusCancellation> cancellations) {
            this(program, inputs, state, receipts, control, tasks, pins, cancellations, RegisterMemory.initial(program.expressions().program().cells()));
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts,
                FlowControl control, FlowTasks tasks, FlowPinClaims pins) {
            this(program, inputs, state, receipts, control, tasks, pins, List.of());
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks) {
            this(program, inputs, state, receipts, control, tasks, FlowPinClaims.EMPTY.reconcile(program, state, tasks));
        }
        public Optional<FlowRuntime.Checkpoint> context(UUID flow) {
            return state.flow().equals(flow) ? Optional.of(state) : tasks.contexts().stream().filter(s -> s.flow().equals(flow)).findFirst();
        }
        public Optional<FlowRuntime.Checkpoint> context(XBusReceipt.Position p) { return context(state, tasks, control.generation(), p); }
        private static Optional<FlowRuntime.Checkpoint> context(FlowRuntime.Checkpoint main, FlowTasks tasks, long generation, XBusReceipt.Position p) {
            if (!main.computer().equals(p.computer()) || generation != p.generation()) return Optional.empty();
            if (p.slot() == 0) return p.flow().equals(main.flow()) && p.incarnation() == 0 ? Optional.of(main) : Optional.empty();
            if (p.slot() > tasks.slots().entries().size()) return Optional.empty();
            var slot = tasks.slots().entries().get(p.slot() - 1);
            if (slot.retired() || !slot.flow().equals(p.flow()) || slot.incarnation() != p.incarnation()) return Optional.empty();
            return tasks.contexts().stream().filter(s -> s.flow().equals(p.flow())).findFirst();
        }
        public XBusReceipt.Position position(FlowRuntime.Checkpoint selected, String pin) {
            if (context(selected.flow()).filter(selected::equals).isEmpty()) throw new IllegalArgumentException("Position requires the published context");
            int slot = 0; long incarnation = 0;
            if (!selected.flow().equals(state.flow())) {
                for (int i = 0; i < tasks.slots().entries().size(); i++) if (tasks.slots().entries().get(i).flow().equals(selected.flow())) {
                    slot = i + 1; incarnation = tasks.slots().entries().get(i).incarnation(); break;
                }
            }
            int instruction = -1;
            if (selected.cursor() < program.steps().size()
                && program.steps().get(selected.cursor()) instanceof FlowProgram.Procedure procedure
                && procedure.assembly().isPresent() && selected.assembly().isPresent()) {
                var image = FlowXBus.image(program);
                int pc = selected.assembly().orElseThrow().pc();
                if (pc < image.code().size() && image.source(pc) == selected.cursor()
                    && image.code().get(pc).instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Instruction)
                    instruction = pc;
            }
            return new XBusReceipt.Position(state.computer(), selected.flow(), selected.cursor(), pin, control.generation(), selected.operation(), slot, incarnation, instruction);
        }
        public FlowRuntime.Checkpoint pinState(String pin) { return pinClaims.owner(pin).flatMap(this::context).orElse(state); }
        public boolean taskPins() {
            return !tasks.contexts().isEmpty() && (program.usesXBusAssembly() || program.steps().stream().anyMatch(step -> FlowPins.pin(step) != null))
                || receipts.stream().anyMatch(r -> r.grouped() || r.positions().stream().anyMatch(p -> p.slot() != 0));
        }
        public long pinUnits() { return taskPins() ? pinClaims.metadataUnits() : 0; }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts, FlowControl control) {
            this(program, inputs, state, receipts, control, FlowTasks.EMPTY);
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state, List<XBusReceipt> receipts) {
            this(program, inputs, state, receipts, FlowControl.fromReceipts(receipts, state));
        }
        public Saved(FlowProgram program, DataValue.RecordValue inputs, FlowRuntime.Checkpoint state) { this(program, inputs, state, List.of(), FlowControl.INITIAL); }
    }
    private java.util.function.BiConsumer<String, String> launchNotice = (code, handler) -> {};
    public void onLaunchRefused(java.util.function.BiConsumer<String, String> listener) { launchNotice = Objects.requireNonNull(listener); }
    private final Runnable markChanged;
    private final com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKeyboard keyboard = new com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKeyboard();
    private final SerialDevice serial;
    private CompoundTag durable;
    private Saved published;
    private Source retainedSource;
    private FlowRuntime runtime;
    private ComputerHostLimits limits;
    private ComputerBlockHost.Diagnostic failure;
    private boolean available;
    private Saved resetOnly;
    private boolean resetChecked;
    private Object retirementWitness;
    private Object retirementDeviceVersion;
    private java.lang.ref.WeakReference<CompoundTag> retirementOwner = new java.lang.ref.WeakReference<>(null);
    private ComputerHostLimits retirementLimits;

    public FlowBlockHost(Runnable markChanged, CompoundTag saved) {
        this(markChanged, saved, null);
    }
    public FlowBlockHost(Runnable markChanged, CompoundTag saved, SerialDevice serial) {
        this.markChanged = Objects.requireNonNull(markChanged); durable = saved.copy(); this.serial = serial;
    }
    /** Validation-only factory; the outer owner publishes this mode before invoking its dirty callback. */
    public static FlowBlockHost installed(Runnable markChanged, UUID computer, FlowProgram program,
        DataValue.RecordValue inputs, ComputerHostLimits limits) {
        return installed(markChanged, computer, program, inputs, limits, FlowControl.INITIAL);
    }
    public static FlowBlockHost installed(Runnable markChanged, UUID computer, FlowProgram program,
        DataValue.RecordValue inputs, ComputerHostLimits limits, FlowControl control) {
        return installed(markChanged, computer, program, inputs, limits, control, null);
    }
    public static FlowBlockHost installed(Runnable markChanged, UUID computer, FlowProgram program,
        DataValue.RecordValue inputs, ComputerHostLimits limits, FlowControl control, SerialDevice serial) {
        return installed(markChanged, computer, program, inputs, limits, control, serial, List.of());
    }
    public static FlowBlockHost installed(Runnable markChanged, UUID computer, FlowProgram program,
        DataValue.RecordValue inputs, ComputerHostLimits limits, FlowControl control, SerialDevice serial, List<XBusCancellation> cancellations) {
        program=program.forBroadcastHardware(limits.isa1a());
        FlowPins.validate(program, limits);
        var runtime = new FlowRuntime(computer, UUID.randomUUID(), program, inputs, hardware(limits, inputs, List.of(), control));
        runtime.setPaused(true);
        var saved = compactCancellations(new Saved(program, inputs, runtime.checkpoint(), List.of(), control, startInitialTasks(program,computer,inputs,control,limits), FlowPinClaims.EMPTY, cancellations));
        var owner = new FlowBlockHost(markChanged, FlowBlockNbtCodec.encode(saved, limits.archive()), serial);
        owner.runtime = runtime; owner.published = saved; owner.limits = limits; owner.available = true;
        owner.runtime = FlowRuntime.restore(saved.program(), saved.state(), owner.hardware(saved, saved.state().flow())); owner.runtime.load();
        return owner;
    }
    private static FlowTasks startInitialTasks(FlowProgram program, UUID computer, DataValue.RecordValue inputs, FlowControl control, ComputerHostLimits limits) {
        var monitors = program.usesWatch() ? program.watches() : Map.<ModuleDefinition.Key, FlowProgram.Watch>of();
        var startup = program.startupEntries();
        if (monitors.isEmpty() && startup.isEmpty()) return FlowTasks.EMPTY;
        FlowTasks.requireStartupCapacity(program, limits.taskSlots());
        var contexts = new ArrayList<FlowRuntime.Checkpoint>();
        var slots = FlowTaskSlots.EMPTY;
        var hardware = hardware(limits, inputs, List.of(), control);
        var roots = new ArrayList<>(startup); roots.addAll(monitors.keySet());
        for (var key : roots) {
            FlowTasks.requireSupported(program, key);
            var id = UUID.randomUUID();
            var script = FlowRuntime.startProcedure(computer, id, program, key, new DataValue.RecordValue(Map.of()), hardware);
            script.setPaused(true);
            contexts.add(script.checkpoint());
            slots = monitors.containsKey(key) ? slots.admit(id) : slots.admitEvent(id, Optional.empty());
        }
        return new FlowTasks(contexts, Optional.empty(), slots);
    }
    public CompoundTag write() { return durable.copy(); }
    public Optional<Saved> snapshot() { return Optional.ofNullable(published); }
    public Optional<Source> source() {
        return Optional.ofNullable(published == null ? retainedSource : new Source(published.program(), published.initialInputs()));
    }
    public Set<String> xbusStatusPins() {
        return runtime != null ? runtime.xbusStatusPins() : published == null ? Set.of() : FlowDevices.statusPins(FlowDevices.uses(published.program().steps(),published.program().expressions().program().cells()));
    }
    /** Freeze inputs for runnable contexts before the world round. No device work for other instructions.
     * Inspect all ready contexts so this phase does not depend on, or advance, task arbitration. */
    public List<FlowDevices.Use> roundSignalReads() {
        if (!loaded() || published.state().paused()) return List.of();
        return contexts(published).stream().flatMap(state -> runtime.roundSignalRead(state).stream()).toList();
    }
    public Optional<ComputerBlockHost.Diagnostic> diagnostic() { return Optional.ofNullable(failure); }
    public boolean loaded() { return available && failure == null && runtime != null && runtime.loaded(); }
    /** Only a fully validated checkpoint may be inspected or explicitly reset while pins are missing. */
    public boolean hardwareMismatch() { return available && published != null && failure != null && failure.code() == ComputerBlockHost.Code.HARDWARE_MISMATCH; }
    /** Validation capability only: never grants execution, pin access or direct program replacement. */
    public boolean canResetResourceFailure() { return available && resetOnly != null && failure != null && failure.code() == ComputerBlockHost.Code.RESOURCE_LIMIT; }
    public void unload() { available = false; keyboard.clear(); if (runtime != null) runtime.unload(); }
    public void updateTerminalKeys(UUID source, long bits) {
        if (bits == 0 || loaded() && !published.state().paused() && published.program().usesKeyDown()) keyboard.update(source, bits);
    }
    public boolean synchronize(ComputerHostLimits requested, boolean available) {
        return synchronize(requested, available, true);
    }
    private boolean synchronize(ComputerHostLimits requested, boolean available, boolean maintenance) {
        return synchronize(requested, available, maintenance, serial);
    }
    private boolean synchronize(ComputerHostLimits requested, boolean available, boolean maintenance, SerialReceiveAccess receiveAccess) {
        if (!available) { unload(); return false; }
        this.available = true;
        if (!requested.equals(limits)) { if (runtime != null) runtime.unload(); runtime = null; limits = requested; failure = null; resetOnly = null; resetChecked = false; }
        if (failure != null) { if (maintenance && !resetChecked) prepareResourceReset(); return false; }
        if (runtime != null) { runtime.load(); return true; }
        try {
            var saved = FlowBlockNbtCodec.decode(durable, limits.archive());
            retainedSource = new Source(saved.program(), saved.initialInputs());
            // RX authentication can synchronize hardware. Reject it before touching the device.
            // Without RX, finish pure validation so cold mismatches retain explicit repair access.
            if (contexts(saved).stream().anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(p -> p.receiving()).isPresent()))
                FlowPins.validateInstructionSets(saved.program(), limits);
            boolean receiveFailureChanged=false;
            for(var context:contexts(saved)) {
                var progress=context.assembly().flatMap(r->r.serial());if(progress.isEmpty())continue;var p=progress.orElseThrow();
                var image=FlowXBus.image(saved.program());var registers=context.assembly().orElseThrow();
                if(context.cursor()>=saved.program().steps().size() || registers.pc()>=image.code().size() || image.source(registers.pc())!=context.cursor()
                    || !(image.code().get(registers.pc()).instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Instruction))
                    throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT,"assembly","Serial progress has an invalid source projection");
                var op=(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Instruction)image.code().get(registers.pc()).instruction();
                if(op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.STATUS || p.receiving()!=(op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.RECEIVE))throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT,"assembly","Serial progress opcode differs");
                String port=op.pin();
                if(p.receiving()){
                    if(receiveAccess==null)throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT,"assembly","Missing bound serial receive device");
                    String before=p.failure();var head=receiveAccess.beginReceive(port);p.restoreReceive(receiveAccess,head,context.flow());receiveFailureChanged|=!before.equals(p.failure());if(p.failure().isEmpty())p.requireEnvelope(head.limits());
                }
                else if(serial!=null)p.requireEnvelope(serial.encodingLimits(port));
                p.requireEnvelope(limits.hardware().memoryUnits());
            }
            var effective = hardware(saved, saved.state().flow());
            // The compatibility machine treats its supplied arena ceiling as a checkpoint invariant.
            // A reduced host budget is a resource failure, not corrupt player data. ISA buffers must
            // still be reconstructed first; their cold controlUnits may require materialized storage.
            if (saved.state().assembly().isEmpty()
                && effective.memoryUnits() - saved.state().controlUnits()
                    < saved.state().expression().map(MachineCheckpoint::arenaUnits).orElse(0L))
                throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "flow", "Saved flow exceeds current memory capacity");
            var restored = FlowRuntime.restore(saved.program(), saved.state(), effective);
            long pureSpace = effective.memoryUnits() - saved.state().controlUnits();
            if (pureSpace < saved.state().expression().map(MachineCheckpoint::arenaUnits).orElse(0L))
                throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "flow", "Saved flow exceeds current memory capacity");
            // Structural decode omits the cursor-only reconstructed buffers/table/stack.
            // Reuse the same complete archive admission as publication before exposing loaded.
            var restoredDurable=(saved.program().usesSerialAssembly() || saved.program().usesBoundedAssemblyRestore())?FlowBlockNbtCodec.encode(saved, limits.archive()):null;
            published = saved;
            FlowPins.validate(saved.program(), limits);
            restored.load(); runtime = restored;
            // Persist only a newly authenticated failure latch, after every restore gate. This is
            // metadata normalization, not a paid result/PC/queue transition. Ordinary saves must
            // retain it; the existing owner callback dirties the real BE exactly once.
            if(receiveFailureChanged){durable=restoredDurable;markChanged.run();}
            return true;
        } catch (ArchiveException | ComputationException | FlowPins.Unavailable error) {
            suspend(error);
            if (maintenance) prepareResourceReset();
            return false;
        }
    }
    /** A bounded, private inspection workspace, not extra Computer RAM. Decode fresh: a failed
     * normal restore may already have materialized some witnesses. No probe state is published. */
    private void prepareResourceReset() {
        resetChecked = true;
        if (failure == null || failure.code() != ComputerBlockHost.Code.RESOURCE_LIMIT
            || limits.archive().maxValueUnits() <= limits.hardware().memoryUnits()) return;
        try {
            var saved = FlowBlockNbtCodec.decode(durable, limits.archive());
            if (saved.program().execution() != FlowProgram.Execution.REGISTER_V1 || !saved.program().usesValueEqualityAssembly()
                || saved.tasks().slots().entries().size() >= limits.taskSlots()) return;
            // RX authentication can synchronize/mutate the physical receiver. It needs a separate
            // read-only device view; do not broaden this TX/non-RX maintenance capability to it.
            if (contexts(saved).stream().anyMatch(s -> s.assembly().flatMap(r -> r.serial()).filter(p -> p.receiving()).isPresent())) return;
            var inspection = new ComputerHostLimits(limits.hardware().withMemoryUnits(limits.archive().maxValueUnits()), limits.archive(),
                limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots(), limits.isa1a(), limits.isa1b());
            var probe = new FlowBlockHost(() -> {}, durable, serial);
            if (probe.synchronize(inspection, true, false)) resetOnly = probe.published;
        } catch (ComputationException | IllegalArgumentException invalid) {
            // Keep the original diagnostic and bytes, including errors masked by the RAM gate.
        }
    }
    /** Explicit destructive maintenance. Both the new image and its save must fit actual hardware
     * before the old checkpoint is replaced; peer retirement and cancellation proofs survive. */
    public void resetResourceFailure() {
        if (!canResetResourceFailure()) throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "flow", "No validated reset-only checkpoint");
        var saved = resetOnly;
        var replacement = installed(() -> {}, saved.state().computer(), saved.program(), saved.initialInputs(), limits,
            saved.control().retire(saved.receipts(), saved.state()), serial, saved.cancellations());
        published = replacement.published; runtime = replacement.runtime; durable = replacement.durable;
        failure = null; resetOnly = null; resetChecked = false;
        markChanged.run();
    }
    public boolean replaceable() {
        if ((!loaded() && !hardwareMismatch()) || !published.receipts().isEmpty()) return false;
        var startup=new HashSet<>(published.program().startupEntries());
        startup.addAll(published.program().watches().keySet());
        if(published.tasks().contexts().stream().anyMatch(task -> task.entry().filter(startup::contains).isEmpty()
            || FlowTasks.terminal(task) || !task.paused() || task.work()!=0)) return false;
        var state = published.state();
        return state.status() == FlowRuntime.Status.COMPLETE || state.status() == FlowRuntime.Status.FAILED
            || state.status() == FlowRuntime.Status.READY && state.work() == 0;
    }
    /** Authorized pair only. Prepare both complete saves before exposing either continuation. */
    public XBusExchange.Status exchange(String pin, FlowBlockHost peer, String peerPin) {
        if (!loaded() || !peer.loaded()) return XBusExchange.Status.UNAVAILABLE;
        if (published.state().computer().equals(peer.published.state().computer())) return XBusExchange.Status.STALE;
        FlowProgram.name(pin); FlowProgram.name(peerPin);
        if (runtime.checkpoint().paused() || peer.runtime.checkpoint().paused()) return XBusExchange.Status.PAUSED;
        if (published.pinClaims().shared(pin) || peer.published.pinClaims().shared(peerPin)
            || published.receipts().stream().anyMatch(XBusReceipt::grouped) || peer.published.receipts().stream().anyMatch(XBusReceipt::grouped)) {
            var a = new Binding(this, pin); var b = new Binding(peer, peerPin);
            return exchangeGroup(List.of(a, b), published.pinState(pin).status() == FlowRuntime.Status.WAITING_WRITE ? a : b);
        }
        var gate = published.control().check(peer.published.position(peer.published.pinState(peerPin), peerPin));
        if (gate.isPresent()) return gate.orElseThrow();
        gate = peer.published.control().check(published.position(published.pinState(pin), pin));
        if (gate.isPresent()) return gate.orElseThrow();
        Exchange prepared;
        try {
            prepared = prepareExchange(pin, peer, peerPin);
        } catch (ArchiveException error) {
            if (error.code() == ArchiveException.Code.LIMIT_EXCEEDED) return XBusExchange.Status.ARCHIVE_LIMIT;
            throw error;
        } catch (ComputationException error) {
            if (error.code() == ComputationException.Code.MEMORY_LIMIT) return XBusExchange.Status.MEMORY_LIMIT;
            if (error.code() == ComputationException.Code.ARITHMETIC_OVERFLOW) return XBusExchange.Status.ARITHMETIC_OVERFLOW;
            throw error;
        }
        // A failed dirty notification cannot turn an already-published exchange into a resource rejection.
        boolean leftChanged = prepared.left() != null && !prepared.left().saved().equals(published);
        boolean rightChanged = prepared.right() != null && !prepared.right().saved().equals(peer.published);
        if (prepared.left() != null) apply(prepared.left());
        if (prepared.right() != null) peer.apply(prepared.right());
        try { if (leftChanged) markChanged.run(); } finally { if (rightChanged) peer.markChanged.run(); }
        return prepared.status();
    }
    /** Trusted coordinator only; prepare a fully saveable local failure without changing the peer or history. */
    public XBusExchange.Status rejectPausedXBus(String pin) {
        if (!loaded()) return XBusExchange.Status.UNAVAILABLE;
        var state = published.pinState(pin);
        if (state.paused() || !(state.status() == FlowRuntime.Status.WAITING_READ || state.status() == FlowRuntime.Status.WAITING_WRITE
                || state.status() == FlowRuntime.Status.WAITING_READABLE)) return XBusExchange.Status.NOT_READY;
        if (!pin.equals(FlowXBus.pin(published.program(),state))) return XBusExchange.Status.PORT_MISMATCH;
        Publication next;
        try {
            var updates = new LinkedHashMap<UUID, FlowRuntime.Checkpoint>();
            for (var claim : published.pinClaims().entries()) if (claim.pin().equals(pin)) {
                var copy = restored(published.context(claim.flow()).orElseThrow());
                copy.load(); copy.rejectPausedXBus(); updates.put(claim.flow(), copy.checkpoint());
            }
            next = groupCandidates(Map.of(this, updates), null, Map.of(published.state().computer(), this),
                XBusExchange.Status.PAUSED).changes().get(this);
        } catch (ArchiveException error) {
            if (error.code() == ArchiveException.Code.LIMIT_EXCEEDED) return XBusExchange.Status.ARCHIVE_LIMIT;
            throw error;
        } catch (ComputationException error) {
            if (error.code() == ComputationException.Code.MEMORY_LIMIT) return XBusExchange.Status.MEMORY_LIMIT;
            if (error.code() == ComputationException.Code.ARITHMETIC_OVERFLOW) return XBusExchange.Status.ARITHMETIC_OVERFLOW;
            throw error;
        }
        // One local save only; neither a peer continuation nor any historical receipt is modified.
        apply(next); markChanged.run();
        return XBusExchange.Status.PAUSED;
    }
    /** A null side has no update; no checkpoint or dirty notification is published during preparation. */
    private record Exchange(XBusExchange.Status status, Publication left, Publication right) {
        private Exchange(XBusExchange.Status status) { this(status, null, null); }
        private Exchange reversed() { return new Exchange(status, right, left); }
    }
    private Exchange prepareExchange(String pin, FlowBlockHost peer, String peerPin) {
        var recovery = preparePeerRecovery(pin, peer, peerPin);
        if (recovery != null) return recovery;
        recovery = peer.preparePeerRecovery(peerPin, this, pin);
        if (recovery != null) return recovery.reversed();
        var local = restored(published.pinState(pin)); var remote = peer.restored(peer.published.pinState(peerPin));
        var preparation = XBusExchange.prepare(local, pin, remote, peerPin);
        if (preparation.transition().isEmpty()) return new Exchange(preparation.status());
        var transition = preparation.transition().orElseThrow();
        XBusReceipt receipt = null;
        if (transition.leftBefore() != transition.leftAfter() && transition.rightBefore() != transition.rightAfter()) {
            boolean writing = local.checkpoint().status() == FlowRuntime.Status.WAITING_WRITE;
            var writer = writing ? local : remote; var reader = writing ? remote : local;
            receipt = new XBusReceipt((writing ? published : peer.published).position(writer.checkpoint(), writing ? pin : peerPin),
                (writing ? peer.published : published).position(reader.checkpoint(), writing ? peerPin : pin),
                FlowXBus.payload(writer.program(),writer.checkpoint()));
        }
        var leftControl = receipt == null ? published.control() : observing(peer);
        var rightControl = receipt == null ? peer.published.control() : peer.observing(this);
        var left = contextCandidate(transition.leftAfter(), appended(receipt), leftControl);
        var right = peer.contextCandidate(transition.rightAfter(), peer.appended(receipt), rightControl);
        var status = !left.saved().equals(published) && !right.saved().equals(peer.published)
            ? XBusExchange.Status.EXCHANGED : XBusExchange.Status.READABLE;
        return new Exchange(status, left, right);
    }
    private FlowControl observing(FlowBlockHost peer) {
        return published.control().observe(peer.published.state().computer(), FlowControl.Peer.of(peer.published));
    }
    /** A completed peer may repair an older saved operation, but cannot replay its own successor. */
    private Exchange preparePeerRecovery(String pin, FlowBlockHost peer, String peerPin) {
        for (var receipt : published.receipts()) {
            var own = receipt.own(published.state().computer()); var target = receipt.peer(published.state().computer());
            var context = peer.published.context(target); if (context.isEmpty()) continue;
            var state = context.orElseThrow();
            var counterpart = peer.published.receipts().stream().filter(r -> {
                var held = r.own(state.computer()); return held.flow().equals(target.flow()) && held.operation() == target.operation();
            }).findFirst();
            if (counterpart.isPresent()) {
                if (!counterpart.orElseThrow().equals(receipt)) return new Exchange(XBusExchange.Status.HISTORY_CONFLICT);
                continue;
            }
            if (state.cancelled()) continue; // It cannot consume or replay a peer receipt.
            if (state.operation() > target.operation()) return new Exchange(XBusExchange.Status.HISTORY_CONFLICT);
            if (state.operation() != target.operation()) continue;
            if (!FlowXBus.matches(target,state)) return new Exchange(XBusExchange.Status.HISTORY_CONFLICT);
            if (!own.pin().equals(pin) || !target.pin().equals(peerPin)) return new Exchange(XBusExchange.Status.PORT_MISMATCH);
            boolean writing = target.equals(receipt.writer());
            if (state.status() != (writing ? FlowRuntime.Status.WAITING_WRITE : FlowRuntime.Status.WAITING_READ)) return new Exchange(XBusExchange.Status.NOT_READY);
            if (!peer.published.pinState(peerPin).flow().equals(state.flow())) return new Exchange(XBusExchange.Status.RECOVERY_REQUIRED);
            var pending = peer.restored(state);
            var locals = state.locals();
            if (writing) {
                var write=FlowXBus.at(pending.program(),pending.checkpoint());
                if (!write.pin().equals(target.pin()) || !FlowXBus.payload(pending.program(),pending.checkpoint()).equals(receipt.payload())) return new Exchange(XBusExchange.Status.HISTORY_CONFLICT);
            } else {
                var read=FlowXBus.at(pending.program(),pending.checkpoint());
                if (!read.pin().equals(target.pin()) || !read.payload().accepts(receipt.payload())) return new Exchange(XBusExchange.Status.HISTORY_CONFLICT);
            }
            var candidate = peer.contextCandidate(pending.completedXBus(writing ? null : receipt.payload()), peer.appended(receipt), peer.observing(this));
            return new Exchange(XBusExchange.Status.EXCHANGED, null, candidate);
        }
        return null;
    }
    private List<XBusReceipt> appended(XBusReceipt receipt) {
        if (receipt == null) return published.receipts();
        var result = new ArrayList<>(published.receipts()); result.add(receipt); return List.copyOf(result);
    }
    private record Publication(Saved saved, FlowRuntime runtime, CompoundTag encoded) {}
    /** Trusted host/coordinator seam: these are explicitly connected pins, not world addresses. */
    public record Binding(FlowBlockHost host, String pin) {
        public Binding { Objects.requireNonNull(host); FlowProgram.name(pin); }
    }
    private record GroupPrepared(XBusExchange.Status status, Map<FlowBlockHost, Publication> changes, XBusReceipt receipt) {
        private GroupPrepared(XBusExchange.Status status) { this(status, Map.of(), null); }
    }
    /** A short-lived all-owner candidate. Its identities may be fenced by the dimension before commit. */
    public static final class GroupExchange {
        private final GroupPrepared prepared;
        private final Map<FlowBlockHost, Saved> before;
        private final Map<FlowBlockHost, ComputerHostLimits> limits;
        private boolean committed;
        private GroupExchange(GroupPrepared prepared, Collection<FlowBlockHost> owners) {
            this.prepared = prepared; before = new LinkedHashMap<>(); limits = new LinkedHashMap<>();
            for (var owner : owners) { before.put(owner, owner.published); limits.put(owner, owner.limits); }
        }
        public static GroupExchange rejected(XBusExchange.Status status) { return new GroupExchange(new GroupPrepared(status), List.of()); }
        public XBusExchange.Status status() { return prepared.status(); }
        public Optional<XBusReceipt> receipt() { return Optional.ofNullable(prepared.receipt()); }
        public boolean completes(Binding binding) {
            var old = before.get(binding.host()); var next = prepared.changes().get(binding.host());
            return old != null && next != null && old.pinClaims().entries().stream().anyMatch(claim ->
                claim.pin().equals(binding.pin()) && !next.saved().pinClaims().entries().contains(claim));
        }
        public XBusExchange.Status commit() {
            if (committed) return XBusExchange.Status.STALE;
            for (var entry : before.entrySet()) {
                var owner = entry.getKey();
                if (!owner.loaded()) return XBusExchange.Status.UNAVAILABLE;
                if (owner.published != entry.getValue() || !owner.limits.equals(limits.get(owner))) return XBusExchange.Status.STALE;
            }
            committed = true; prepared.changes().forEach(FlowBlockHost::apply);
            RuntimeException notificationFailure = null;
            for (var host : prepared.changes().keySet()) try { host.markChanged.run(); }
            catch (RuntimeException failure) {
                if (notificationFailure == null) notificationFailure = failure;
                else if (notificationFailure != failure) notificationFailure.addSuppressed(failure);
            }
            if (notificationFailure != null) throw notificationFailure;
            return prepared.status();
        }
    }
    /** Prepare every participant's complete bounded save before publishing any one of them. */
    public static XBusExchange.Status exchangeGroup(List<Binding> bindings, Binding selectedWriter) {
        return prepareExchangeGroup(bindings, selectedWriter).commit();
    }
    public static GroupExchange prepareExchangeGroup(List<Binding> bindings, Binding selectedWriter) {
        return prepareGroup(bindings, selectedWriter, false);
    }
    /** No delivery receipt, no output result, and no execution of paused contexts. */
    public static GroupExchange prepareCancellation(List<Binding> bindings, XBusCancellation witness) {
        var owners = bindings.stream().map(Binding::host).distinct().toList();
        var candidates = new LinkedHashMap<FlowBlockHost, Publication>();
        try {
            for (var host : owners) {
                if (!host.loaded()) return GroupExchange.rejected(XBusExchange.Status.UNAVAILABLE);
                var saved = host.published;
                if (witness.writers().stream().noneMatch(p -> p.computer().equals(saved.state().computer()))) continue;
                var updates = new LinkedHashMap<UUID, FlowRuntime.Checkpoint>();
                for (var state : host.contexts(saved)) if (state.status() == FlowRuntime.Status.WAITING_WRITE && witness.matches(saved, state)) {
                    var p = witness.writers().stream().filter(end -> saved.context(end).filter(state::equals).isPresent()
                        && end.operation() == state.operation()).findFirst().orElseThrow();
                    if (!bindings.contains(new Binding(host, p.pin()))) continue;
                    if (saved.state().paused() || state.paused()) continue;
                    var copy = host.restored(state); copy.rejectRetiredXBus(); updates.put(state.flow(), copy.checkpoint());
                }
                // The retired holder needs no new task or memory. Only original, still-owned
                // operation identities retain cancellation proof, including paused siblings.
                if (witness.writers().stream().noneMatch(p -> saved.context(p).isPresent())) continue;
                var retained = new ArrayList<>(saved.cancellations()); if (!retained.contains(witness)) retained.add(witness);
                var main = updates.getOrDefault(saved.state().flow(), saved.state());
                var tasks = new FlowTasks(saved.tasks().contexts().stream().map(s -> updates.getOrDefault(s.flow(), s)).toList(), saved.tasks().after(), saved.tasks().slots());
                var control = saved.control();
                if (!witness.holder().computer().equals(saved.state().computer())) control = control.observe(witness.holder().computer(), witness.proof());
                var candidate = host.candidate(main, saved.receipts(), control, tasks, retained);
                if (!candidate.saved().equals(saved)) candidates.put(host, candidate);
            }
        } catch (ArchiveException error) {
            if (error.code() == ArchiveException.Code.LIMIT_EXCEEDED) return GroupExchange.rejected(XBusExchange.Status.ARCHIVE_LIMIT);
            throw error;
        } catch (ComputationException error) {
            if (error.code() == ComputationException.Code.MEMORY_LIMIT) return GroupExchange.rejected(XBusExchange.Status.MEMORY_LIMIT);
            throw error;
        }
        return new GroupExchange(new GroupPrepared(candidates.isEmpty() ? XBusExchange.Status.NOT_READY : XBusExchange.Status.RETIRED, candidates, null), owners);
    }
    /** Readiness observation only. Never consumes a write, repairs a receipt or delivers to data reads. */
    public static GroupExchange prepareReadableGroup(List<Binding> bindings, Binding selectedWriter) {
        return prepareGroup(bindings, selectedWriter, true);
    }
    private static GroupExchange prepareGroup(List<Binding> bindings, Binding selectedWriter, boolean probesOnly) {
        bindings = List.copyOf(bindings);
        if (!bindings.contains(selectedWriter)) return GroupExchange.rejected(XBusExchange.Status.NOT_FOUND);
        var owners = new LinkedHashMap<UUID, FlowBlockHost>();
        for (var binding : bindings) {
            var host = binding.host(); if (!host.loaded()) return GroupExchange.rejected(XBusExchange.Status.UNAVAILABLE);
            var prior = owners.putIfAbsent(host.published.state().computer(), host);
            if (prior != null && prior != host) return GroupExchange.rejected(XBusExchange.Status.STALE);
        }
        GroupPrepared prepared;
        try { prepared = prepareGroup(new LinkedHashSet<>(bindings), selectedWriter, owners, probesOnly); }
        catch (ArchiveException error) {
            if (error.code() == ArchiveException.Code.LIMIT_EXCEEDED) return GroupExchange.rejected(XBusExchange.Status.ARCHIVE_LIMIT);
            throw error;
        } catch (ComputationException error) {
            if (error.code() == ComputationException.Code.MEMORY_LIMIT) return GroupExchange.rejected(XBusExchange.Status.MEMORY_LIMIT);
            if (error.code() == ComputationException.Code.ARITHMETIC_OVERFLOW) return GroupExchange.rejected(XBusExchange.Status.ARITHMETIC_OVERFLOW);
            throw error;
        }
        return new GroupExchange(prepared, owners.values());
    }
    private static GroupPrepared prepareGroup(Set<Binding> bindings, Binding selected, Map<UUID, FlowBlockHost> owners, boolean probesOnly) {
        // All retained evidence is inspected before a new cohort is chosen. Never replace old recipients
        // with whoever happens to be waiting now, including tasks reusing the same numeric slot.
        var history = new LinkedHashSet<XBusReceipt>();
        var claims = new HashMap<XBusReceipt.Position, XBusReceipt>();
        for (var host : owners.values()) for (var receipt : host.published.receipts()) {
            if (receipt.positions().stream().noneMatch(p -> owners.containsKey(p.computer())
                    && bindings.contains(new Binding(owners.get(p.computer()), p.pin())))) continue;
            history.add(receipt);
            for (var p : receipt.positions()) {
                var old = claims.putIfAbsent(p, receipt);
                if (old != null && !old.equals(receipt)) return new GroupPrepared(XBusExchange.Status.HISTORY_CONFLICT);
            }
        }
        // An older readiness wait may precede its recorded data read. The network barrier chooses
        // genuine repair first; observation must not consume/repair other participants as a side effect.
        for (var receipt : probesOnly ? Set.<XBusReceipt>of() : history) {
            var updates = new LinkedHashMap<FlowBlockHost, Map<UUID, FlowRuntime.Checkpoint>>();
            boolean missing = false, retiredGeneration = false;
            for (var p : receipt.positions()) {
                var host = owners.get(p.computer());
                if (host == null || !bindings.contains(new Binding(host, p.pin()))) { missing = true; continue; }
                var saved = host.published;
                if (saved.control().generation() < p.generation()) return new GroupPrepared(XBusExchange.Status.RETIRED);
                if (saved.control().generation() > p.generation()) { retiredGeneration = true; continue; }
                if (FlowControl.Peer.of(saved).supersedes(p)) continue;
                var context = saved.context(p);
                if (context.isEmpty()) return new GroupPrepared(XBusExchange.Status.HISTORY_CONFLICT);
                if (saved.receipts().contains(receipt)) continue;
                for (var other : owners.values()) if (other != host) {
                    var denial = other.published.control().check(p);
                    if (denial.isPresent()) return new GroupPrepared(denial.orElseThrow());
                }
                var state = context.orElseThrow();
                if (state.cancelled()) continue;
                if (state.operation() > p.operation() || state.operation() == p.operation() && !FlowXBus.matches(p,state))
                    return new GroupPrepared(XBusExchange.Status.HISTORY_CONFLICT);
                if (state.operation() < p.operation()) return new GroupPrepared(XBusExchange.Status.RECOVERY_REQUIRED);
                if (state.paused()) return new GroupPrepared(XBusExchange.Status.PAUSED);
                boolean writing = receipt.writing(p);
                if (state.status() != (writing ? FlowRuntime.Status.WAITING_WRITE : FlowRuntime.Status.WAITING_READ))
                    return new GroupPrepared(XBusExchange.Status.NOT_READY);
                var runtime = host.restored(state);
                if (!p.pin().equals(FlowXBus.pin(runtime.program(),runtime.checkpoint()))) return new GroupPrepared(XBusExchange.Status.PORT_MISMATCH);
                if (p.equals(receipt.writer()) && !FlowXBus.payload(runtime.program(),state).equals(receipt.payload())
                    || !writing && !FlowXBus.at(runtime.program(),runtime.checkpoint()).payload().accepts(receipt.payload()))
                    return new GroupPrepared(XBusExchange.Status.HISTORY_CONFLICT);
                updates.computeIfAbsent(host, ignored -> new LinkedHashMap<>()).put(p.flow(), runtime.completedXBus(writing ? null : receipt.payload()));
            }
            if (!updates.isEmpty()) {
                if (retiredGeneration) return new GroupPrepared(XBusExchange.Status.RETIRED);
                if (missing) return new GroupPrepared(XBusExchange.Status.RECOVERY_REQUIRED);
                return groupCandidates(updates, receipt, owners, XBusExchange.Status.EXCHANGED);
            }
        }
        var writer = selected.host(); var state = writer.published.pinState(selected.pin());
        if (state.paused()) return new GroupPrepared(XBusExchange.Status.PAUSED);
        if (state.status() != FlowRuntime.Status.WAITING_WRITE
            || !selected.pin().equals(FlowXBus.pin(writer.published.program(),state)))
            return new GroupPrepared(XBusExchange.Status.NOT_READY);
        var payload = FlowXBus.payload(writer.published.program(),state);
        var writing = new ArrayList<XBusReceipt.Position>(); writing.add(writer.published.position(state, selected.pin()));
        var reading = new ArrayList<XBusReceipt.Position>();
        var updates = new LinkedHashMap<FlowBlockHost, Map<UUID, FlowRuntime.Checkpoint>>();
        var probes = new LinkedHashMap<FlowBlockHost, Map<UUID, FlowRuntime.Checkpoint>>();
        for (var binding : bindings) {
            var host = binding.host(); var saved = host.published;
            if (saved.state().paused()) continue;
            for (var claim : saved.pinClaims().entries()) {
                if (!binding.pin().equals(claim.pin())) continue;
                var wait = saved.context(claim.flow()).orElseThrow(); var runtime = host.restored(wait);
                if (probesOnly && wait.status() != FlowRuntime.Status.WAITING_READABLE) continue;
                var position = saved.position(wait, binding.pin());
                for (var other : owners.values()) if (other != host) {
                    var denial = other.published.control().check(position);
                    if (denial.isPresent()) return new GroupPrepared(denial.orElseThrow());
                }
                if (wait.status() == FlowRuntime.Status.WAITING_READABLE) {
                    probes.computeIfAbsent(host, ignored -> new LinkedHashMap<>()).put(wait.flow(), runtime.completedXBus(null));
                    continue;
                }
                if (wait.status() == FlowRuntime.Status.WAITING_READ) {
                    if (!FlowXBus.at(runtime.program(),runtime.checkpoint()).payload().accepts(payload)) return new GroupPrepared(XBusExchange.Status.TYPE_MISMATCH);
                    reading.add(position);
                } else if (!position.equals(writing.getFirst())) writing.add(position);
                updates.computeIfAbsent(host, ignored -> new LinkedHashMap<>()).put(wait.flow(), runtime.completedXBus(
                    wait.status() == FlowRuntime.Status.WAITING_READ ? payload : null));
            }
        }
        if (reading.isEmpty()) return probes.isEmpty() ? new GroupPrepared(XBusExchange.Status.NOT_READY)
            : groupCandidates(probes, null, owners, XBusExchange.Status.READABLE);
        var receipt = new XBusReceipt(writing.getFirst(), reading.getFirst(), payload,
            writing.subList(1, writing.size()), reading.subList(1, reading.size()));
        return groupCandidates(updates, receipt, owners, XBusExchange.Status.EXCHANGED);
    }
    private static GroupPrepared groupCandidates(Map<FlowBlockHost, Map<UUID, FlowRuntime.Checkpoint>> updates,
            XBusReceipt receipt, Map<UUID, FlowBlockHost> owners, XBusExchange.Status status) {
        var candidates = new LinkedHashMap<FlowBlockHost, Publication>();
        for (var entry : updates.entrySet()) {
            var host = entry.getKey(); var saved = host.published; var states = entry.getValue();
            var main = states.getOrDefault(saved.state().flow(), saved.state());
            var tasks = new FlowTasks(saved.tasks().contexts().stream().map(s -> states.getOrDefault(s.flow(), s)).toList(),
                saved.tasks().after(), saved.tasks().slots());
            var control = saved.control();
            if (receipt != null) for (var remote : receipt.remote(saved.state().computer()))
                control = control.observe(remote.computer(), FlowControl.Peer.of(owners.get(remote.computer()).published));
            candidates.put(host, host.candidate(main, host.appended(receipt), control, tasks));
        }
        return new GroupPrepared(status, candidates, receipt);
    }
    private static Set<UUID> waitingForAnswer(FlowRuntime.Checkpoint main, FlowTasks tasks) {
        var waiting = new HashSet<UUID>();
        if (main.status() == FlowRuntime.Status.WAITING_ANSWER) waiting.add(main.flow());
        for (var task : tasks.contexts()) if (task.status() == FlowRuntime.Status.WAITING_ANSWER) waiting.add(task.flow());
        return waiting;
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control) {
        return candidate(next, receipts, control, published.tasks());
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks) {
        return candidate(next, receipts, control, tasks, published.cancellations());
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks, List<XBusCancellation> cancellations) {
        return candidate(next, receipts, control, tasks, cancellations, published.memory());
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks,
            List<XBusCancellation> cancellations, RegisterMemory memory) {
        return candidate(next, receipts, control, tasks, cancellations, memory, published.prompts(), published.monitors(), published.privateMonitors());
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks,
            List<XBusCancellation> cancellations, RegisterMemory memory, FlowPrompts prompts, Set<String> monitors, FlowPrivateMonitors privateMonitors) {
        return candidate(next, receipts, control, tasks, cancellations, memory, published.timerTicks(), prompts, monitors, privateMonitors);
    }
    private Publication candidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control, FlowTasks tasks,
            List<XBusCancellation> cancellations, RegisterMemory memory, long timerTicks, FlowPrompts prompts, Set<String> monitors, FlowPrivateMonitors privateMonitors) {
        var live = new HashSet<UUID>();
        if (next.status() == FlowRuntime.Status.READY) live.add(next.flow());
        tasks.contexts().stream().filter(s -> s.status() == FlowRuntime.Status.READY).forEach(s -> live.add(s.flow()));
        memory = memory.retain(live);
        var after = compactCancellations(new Saved(published.program(), published.initialInputs(), next, receipts, control, tasks,
            published.pinClaims().reconcile(published.program(), next, tasks), cancellations, memory, timerTicks, prompts.retain(waitingForAnswer(next, tasks)), monitors, privateMonitors.retain(next, tasks)));
        after = retireCompletedEventTasks(after);
        after = wakeTaskWaiters(after);
        var candidate = FlowRuntime.restore(after.program(), after.state(), hardware(after, next.flow())); candidate.load();
        return new Publication(after, candidate, FlowBlockNbtCodec.encode(after, limits.archive()));
    }
    /** Release cohort metadata only after every position is superseded, retaining remote fences. */
    private static Saved compactCancellations(Saved saved) {
        if (saved.cancellations().isEmpty()) return saved;
        var known = new LinkedHashMap<>(saved.control().peers()); var own = saved.state().computer();
        known.put(own, FlowControl.Peer.of(saved));
        for (var witness : saved.cancellations()) known.merge(witness.holder().computer(), witness.proof(), FlowControl.Peer::merge);
        var control = saved.control(); var retained = new ArrayList<XBusCancellation>();
        for (var witness : saved.cancellations()) {
            if (witness.writers().stream().allMatch(p -> known.containsKey(p.computer()) && known.get(p.computer()).supersedes(p))) {
                for (var p : witness.writers()) if (!p.computer().equals(own)) control = control.observe(p.computer(), known.get(p.computer()));
            } else retained.add(witness);
        }
        return retained.equals(saved.cancellations()) && control.equals(saved.control()) ? saved : new Saved(saved.program(), saved.initialInputs(),
            saved.state(), saved.receipts(), control, saved.tasks(), saved.pinClaims(), retained, saved.memory(), saved.timerTicks(), saved.prompts(), saved.monitors(), saved.privateMonitors());
    }
    private void apply(Publication next) { published = next.saved(); runtime = next.runtime(); durable = next.encoded(); }
    /** One attempt per unchanged owner, hardware, receive metadata and durable proof snapshot. */
    public int maintainXBusReceipts(Object witness, java.util.function.Predicate<XBusReceipt> completed) {
        Objects.requireNonNull(witness);
        if (!available) return 0;
        if (!loaded() && (failure == null || failure.code() != ComputerBlockHost.Code.RESOURCE_LIMIT)) return 0;
        var deviceVersion = !loaded() && serial != null ? serial.receiveObservationVersion() : null;
        if (witness == retirementWitness && durable == retirementOwner.get() && limits == retirementLimits
                && Objects.equals(deviceVersion, retirementDeviceVersion)) return 0;
        retirementWitness = witness; retirementOwner = new java.lang.ref.WeakReference<>(durable); retirementLimits = limits;
        retirementDeviceVersion = deviceVersion;
        return reclaimCompletedXBus(completed);
    }
    /** Trusted coordinator only: the predicate must prove durable completion or retirement.
     * This releases protocol payloads, not task results or program variables. No task is woken. */
    public int reclaimCompletedXBus(java.util.function.Predicate<XBusReceipt> completed) {
        Objects.requireNonNull(completed);
        if (!available) return 0;
        boolean recovering = !loaded();
        if (recovering && (failure == null || failure.code() != ComputerBlockHost.Code.RESOURCE_LIMIT)) return 0;
        var original = durable; var originalLimits = limits;
        Publication next;
        SerialDevice.ReceiveInspection receiveInspection = null;
        int released;
        try {
            // Decode under archive limits, including original receipt/owner validation. Never
            // borrow execution RAM to run the old program or publish a partial cleanup.
            var before = recovering ? FlowBlockNbtCodec.decode(original, limits.archive()) : published;
            if (before.receipts().isEmpty()) return 0;
            // A cold RX witness is authenticated against a borrowed immutable head, never against
            // the live mutation-capable device. Refuse publication if the device changes meanwhile.
            if (recovering && contexts(before).stream().anyMatch(s -> s.assembly().flatMap(r -> r.serial())
                    .filter(p -> p.receiving()).isPresent())) {
                if (serial == null) return 0;
                receiveInspection = serial.inspectReceive().orElse(null);
                if (receiveInspection == null) return 0;
            }
            var after = retirePayloads(before, completed);
            if (after == before || durable != original || limits != originalLimits || !available) return 0;
            released = before.receipts().size() - after.receipts().size();
            var encoded = FlowBlockNbtCodec.encode(after, limits.archive());
            if (recovering) {
                var probe = new FlowBlockHost(() -> {}, encoded, serial);
                if (!probe.synchronize(limits, true, false, receiveInspection)) return 0;
                next = new Publication(probe.published, probe.runtime, probe.durable);
            } else {
                var restored = FlowRuntime.restore(after.program(), after.state(), hardware(after, after.state().flow()));
                restored.load();
                next = new Publication(after, restored, encoded);
            }
        } catch (ComputationException | IllegalArgumentException invalid) {
            if (recovering) return 0; // Preserve the original bytes and diagnostic on every failed candidate.
            throw invalid;
        }
        if (receiveInspection != null && !receiveInspection.current()) return 0;
        if (durable != original || limits != originalLimits || !available) return 0;
        if (recovering) { failure = null; resetOnly = null; resetChecked = false; }
        apply(next); markChanged.run();
        return released;
    }
    private static Saved retirePayloads(Saved before, java.util.function.Predicate<XBusReceipt> completed) {
        var released = new ArrayList<>(before.receipts().stream().filter(completed).toList());
        List<XBusReceipt> retained;
        FlowControl control;
        while (true) {
            if (released.isEmpty()) return before;
            control = before.control().retireOperations(released, before.state().computer());
            retained = before.receipts().stream().filter(receipt -> !released.contains(receipt)).toList();
            var proposed = control;
            var gaps = retained.stream().flatMap(receipt -> receipt.remote(before.state().computer()).stream())
                .filter(position -> !proposed.peers().get(position.computer()).coversReceipt(position)).toList();
            if (gaps.isEmpty()) break;
            // Every pass removes a candidate; never advance a remote watermark across
            // an older receipt which still supplies unresolved recovery data.
            boolean reduced = released.removeIf(receipt -> receipt.remote(before.state().computer()).stream().anyMatch(end ->
                gaps.stream().anyMatch(gap -> end.computer().equals(gap.computer()) && end.flow().equals(gap.flow())
                    && end.generation() == gap.generation() && end.slot() == gap.slot() && end.incarnation() == gap.incarnation()
                    && end.operation() >= gap.operation())));
            if (!reduced) return before;
        }
        // Persist bounded peer watermarks with the payload-free owner. A missing/older
        // dimension journal must not let an older peer replay against this newer save.
        return new Saved(before.program(), before.initialInputs(), before.state(), retained, control,
            before.tasks(), before.pinClaims(), before.cancellations(), before.memory(), before.timerTicks(), before.prompts(), before.monitors(), before.privateMonitors());
    }
    private Publication contextCandidate(FlowRuntime.Checkpoint next, List<XBusReceipt> receipts, FlowControl control) {
        var tasks = published.tasks();
        if (!tasks.contexts().isEmpty()) tasks = tasks.replace(next, next.flow());
        return candidate(next.flow().equals(published.state().flow()) ? next : published.state(), receipts, control, tasks);
    }
    /** Speculative admission: rejection never suspends existing tasks or changes their save. */
    public UUID startTask(ModuleDefinition.Key entry, DataValue.RecordValue arguments) {
        if (!loaded()) throw FlowProgram.error(ComputationException.Code.BUSY, "tasks", "Computer is unavailable");
        if (published.program().steps().stream().anyMatch(step -> step instanceof FlowProgram.Procedure p && p.key().equals(entry) && (p.startup() || p.terminalKey().isPresent())))
            throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT,"startup","Event scripts are admitted only by their configured trigger");
        if (published.tasks().contexts().size() >= limits.taskSlots() - 1)
            throw FlowProgram.error(ComputationException.Code.BUSY, "tasks", "All task slots are occupied, including retained results");
        if (published.program().usesWatch() && published.program().watches().containsKey(entry))
            throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "watch", "Monitors start only as installed event roots");
        FlowTasks.requireSupported(published.program(), entry);
        var id = UUID.randomUUID();
        var child = FlowRuntime.startProcedure(published.state().computer(), id, published.program(), entry, arguments,
            hardware(limits, published.initialInputs(), published.receipts(), published.control()));
        child.setPaused(published.state().paused());
        var contexts = new ArrayList<>(published.tasks().contexts()); contexts.add(child.checkpoint());
        var tasks = new FlowTasks(contexts, published.tasks().after(), published.tasks().slots().admit(id));
        var next = candidate(published.state(), published.receipts(), published.control(), tasks);
        apply(next); markChanged.run(); return id;
    }
    public String pressTerminalKey(String key) {
        key = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.input(key);
        if (!loaded() || published.state().paused())
            throw FlowProgram.error(ComputationException.Code.BUSY, "terminal", "Computer is paused or unavailable");
        var matching = new LinkedHashSet<ModuleDefinition.Key>();
        for (var step : published.program().steps()) if (step instanceof FlowProgram.Procedure p && p.terminalKey().isPresent()) {
            var selector = p.terminalKey().orElseThrow();
            if (selector.equals(key) || selector.equals(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.ANY))
                matching.add(p.key());
        }
        if (matching.isEmpty()) return "no_listener";
        for (var task : published.tasks().contexts()) if (!FlowTasks.terminal(task)) matching.remove(task.entry().orElseThrow());
        if (matching.isEmpty()) return "already_running";
        if (matching.size() > limits.taskSlots() - 1 - published.tasks().contexts().size())
            throw FlowProgram.error(ComputationException.Code.BUSY, "terminal", "Not enough task slots for all matching key hats");
        admitEventHats(matching);
        return "ok";
    }
    public String fisherEvent(String channel) {
        if (!loaded() || published.state().paused()) return "unavailable";
        var matching = new LinkedHashSet<ModuleDefinition.Key>();
        for (var step : published.program().steps()) if (step instanceof FlowProgram.Procedure p
                && p.event().filter(channel::equals).isPresent()) matching.add(p.key());
        if (matching.isEmpty()) return "no_listener";
        if (matching.size() > limits.taskSlots() - 1 - published.tasks().contexts().size()) return "busy";
        try { admitEventHats(matching); }
        catch (ComputationException failure) { return failure.code().name().toLowerCase(Locale.ROOT); }
        catch (ArchiveException failure) { return "archive_limit"; }
        return "ok";
    }
    /** Publish the entire set or none; handlers share the existing CPU and retire normally. */
    private void admitEventHats(Set<ModuleDefinition.Key> matching) {
        var contexts = new ArrayList<>(published.tasks().contexts());
        var slots = published.tasks().slots();
        var hardware = hardware(limits, published.initialInputs(), published.receipts(), published.control());
        for (var entry : matching) {
            FlowTasks.requireSupported(published.program(), entry);
            var id = UUID.randomUUID();
            var child = FlowRuntime.startProcedure(published.state().computer(), id, published.program(), entry,
                new DataValue.RecordValue(Map.of()), hardware);
            contexts.add(child.checkpoint());
            slots = slots.admitEvent(id, Optional.empty());
        }
        var next = candidate(published.state(), published.receipts(), published.control(), new FlowTasks(contexts, published.tasks().after(), slots));
        apply(next); markChanged.run();
    }
    public FlowRuntime.Checkpoint claimTask(UUID id) {
        var contexts = published.tasks().contexts();
        var result = contexts.stream().filter(s -> s.flow().equals(id)).findFirst()
            .orElseThrow(() -> FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, "tasks", "Unknown local task"));
        if (published.program().usesWatch() && result.entry().filter(published.program().watches()::containsKey).isPresent())
            throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "watch", "An installed monitor does not have a task result");
        if (!FlowTasks.terminal(result)) throw FlowProgram.error(ComputationException.Code.BUSY, "tasks", "Task is not complete");
        var retirement = prepareRetirement(published, id);
        apply(candidate(published.state(), retirement.receipts(), retirement.control(), retirement.tasks())); markChanged.run(); return result;
    }
    private record Retirement(FlowTasks tasks, List<XBusReceipt> receipts, FlowControl control) {}
    /** Pure preparation shared by human claim and ISA claim; it never publishes independently. */
    private static Retirement prepareRetirement(Saved saved, UUID id) {
        var contexts = saved.tasks().contexts();
        var result = contexts.stream().filter(s -> s.flow().equals(id)).findFirst().orElseThrow();
        var remaining = contexts.stream().filter(s -> !s.flow().equals(id)).toList();
        var after = saved.tasks().after();
        if (after.filter(id::equals).isPresent()) {
            int index = contexts.indexOf(result);
            after = Optional.of(index == 0 ? saved.state().flow() : contexts.get(index - 1).flow());
        }
        var tasks = new FlowTasks(remaining, remaining.isEmpty() ? Optional.empty() : after, saved.tasks().slots().retire(id));
        var released = saved.receipts().stream().filter(r -> r.local(saved.state().computer()).stream()
            .allMatch(p -> p.flow().equals(id) || tasks.slots().supersedes(p))).toList();
        var retained = saved.receipts().stream().filter(r -> !released.contains(r)).toList();
        return new Retirement(tasks, retained, saved.control().retireOperations(released, saved.state().computer()));
    }
    /** Successful event receivers have no return value to claim. Failures remain inspectable. */
    private static Saved retireCompletedEventTasks(Saved saved) {
        for (var slot : saved.tasks().slots().entries()) {
            if (!slot.event() || saved.context(slot.flow()).orElseThrow().status() != FlowRuntime.Status.COMPLETE) continue;
            var retirement = prepareRetirement(saved, slot.flow());
            saved = new Saved(saved.program(), saved.initialInputs(), saved.state(), retirement.receipts(), retirement.control(),
                retirement.tasks(), saved.pinClaims().reconcile(saved.program(), saved.state(), retirement.tasks()), saved.cancellations(), saved.memory(), saved.timerTicks(), saved.prompts(), saved.monitors(), saved.privateMonitors().retain(saved.state(), retirement.tasks()));
        }
        return saved;
    }
    private record TaskObservation(Optional<FlowRuntime.Checkpoint> target, Optional<DataValue.RecordValue> outcome) {
        static TaskObservation error(String code, String message) { return new TaskObservation(Optional.empty(), Optional.of(
            com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.failure(code, message))); }
    }
    private static TaskObservation inspectTask(Saved saved, FlowRuntime.Checkpoint caller, ModuleDefinition.Key expected, DataValue value) {
        com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle handle;
        try { handle = com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.from(value); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return TaskObservation.error("task_handle_invalid", "Malformed task handle"); }
        if (!handle.computer().equals(saved.state().computer())) return TaskObservation.error("task_foreign_computer", "Task belongs to another Computer");
        if (handle.generation() != saved.control().generation()) return TaskObservation.error("task_generation_stale", "Task program generation is stale");
        if (handle.flow().equals(caller.flow())) return TaskObservation.error("task_self_wait", "A task cannot wait for itself");
        if (handle.slot() > saved.tasks().slots().entries().size()) return TaskObservation.error("task_unknown", "Unknown local task slot");
        var slot = saved.tasks().slots().entries().get(handle.slot() - 1);
        if (handle.incarnation() != slot.incarnation()) return TaskObservation.error("task_incarnation_stale", "Task slot incarnation is stale");
        if (!handle.flow().equals(slot.flow())) return TaskObservation.error("task_identity_stale", "Task flow identity differs from its slot");
        if (slot.retired()) return TaskObservation.error("task_result_claimed", "Task result was already claimed");
        var target = saved.tasks().contexts().stream().filter(s -> s.flow().equals(handle.flow())).findFirst();
        if (target.isEmpty()) return TaskObservation.error("task_unknown", "Unknown local task");
        var state = target.orElseThrow();
        if (!state.entry().filter(expected::equals).isPresent()) return TaskObservation.error("task_procedure_mismatch", "Task procedure differs from the declared result signature");
        if (!FlowTasks.terminal(state)) return new TaskObservation(target, Optional.empty());
        var result = state.status() == FlowRuntime.Status.COMPLETE
            ? com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.success(state.locals())
            : state.cancelled() ? com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.failure("task_cancelled", "Task cancelled by a local stop block")
            : com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.failure(
                "task_failed_" + state.diagnostic().orElseThrow().code().name().toLowerCase(Locale.ROOT), state.diagnostic().orElseThrow().location() + ": " + state.diagnostic().orElseThrow().message());
        return new TaskObservation(target, Optional.of(result));
    }
    /** Event-driven wake on an owner publication, not retry execution or claim reservation. */
    private Saved wakeTaskWaiters(Saved saved) { return wakeTaskWaiters(saved, false); }
    private Saved wakeTaskWaiters(Saved saved, boolean debuggerStep) {
        if ((!saved.program().usesTaskJoinAssembly() && !saved.program().usesEvents()) || saved.state().paused() && !debuggerStep) return saved;
        var main = saved.state(); var children = new ArrayList<FlowRuntime.Checkpoint>();
        for (var state : contexts(saved)) {
            if (state.status() == FlowRuntime.Status.WAITING_TASK) {
                var copy = FlowRuntime.restore(saved.program(), state, limits.hardware());
                if (copy.eventMode().isPresent()) {
                    if (!saved.tasks().hasEventWork(state.flow())) { copy.wakeEvent(); state = copy.checkpoint(); }
                } else if (inspectTask(saved, state, copy.taskExpected(), copy.taskHandleValue()).outcome().isPresent()) {
                    copy.wakeTaskWait(); state = copy.checkpoint();
                }
            }
            if (state.flow().equals(saved.state().flow())) main = state; else children.add(state);
        }
        return new Saved(saved.program(), saved.initialInputs(), main, saved.receipts(), saved.control(),
            new FlowTasks(children, saved.tasks().after(), saved.tasks().slots()), saved.pinClaims(), saved.cancellations(), saved.memory(), saved.timerTicks(), saved.prompts(), saved.monitors(), saved.privateMonitors());
    }
    /** One owner publication either admits the whole linked fan-out or none of it. */
    private long nextTimerTick() {
        long ticks = published.timerTicks();
        return published.program().usesTimer() && ticks < Long.MAX_VALUE ? ticks + 1 : ticks;
    }
    public record Question(String token, DataValue.TextValue text) {}
    public Optional<Question> question() {
        if (!loaded() || published.prompts().pending().isEmpty()) return Optional.empty();
        var waiter = published.context(published.prompts().pending().getFirst()).orElseThrow();
        return Optional.of(new Question(FlowPrompts.token(waiter), restored(waiter).question()));
    }
    public void submitAnswer(String token, String text) {
        if (!loaded() || published.state().paused()) throw FlowProgram.error(ComputationException.Code.BUSY, "terminal", "Computer is unavailable or paused");
        if (published.prompts().pending().isEmpty()) throw new IllegalArgumentException("No pending question");
        var waiter = published.context(published.prompts().pending().getFirst()).orElseThrow();
        if (!FlowPrompts.token(waiter).equals(token)) throw new IllegalArgumentException("The question has changed");
        var answer = new DataValue.TextValue(text);
        FlowPrompts.requireText(answer);
        var copy = restored(waiter);
        copy.acceptAnswer(answer);
        try {
            apply(promptCandidate(copy.checkpoint(), published.prompts().reply(answer)));
        } catch (ArchiveException invalid) {
            throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "terminal", "Answer exceeds the save envelope");
        }
        markChanged.run();
    }
    private Publication promptCandidate(FlowRuntime.Checkpoint next, FlowPrompts prompts) {
        var tasks = published.tasks();
        if (!tasks.contexts().isEmpty()) tasks = tasks.replace(next, next.flow());
        var main = next.flow().equals(published.state().flow()) ? next : published.state();
        return candidate(main, published.receipts(), published.control(), tasks, published.cancellations(),
            published.memory(), prompts, published.monitors(), published.privateMonitors());
    }
    private long advanceTimer(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        var operation = caller.timerInstruction().orElseThrow();
        Publication next;
        try {
            if (operation.resets() && published.memory().active(before.flow()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "timer", "Resetting the timer is prohibited during a transaction");
            caller.executeTimer(published.timerTicks());
            caller.setPaused(paused);
            var tasks = published.tasks();
            if (!tasks.contexts().isEmpty()) tasks = tasks.replace(caller.checkpoint(), before.flow());
            var main = before.flow().equals(published.state().flow()) ? caller.checkpoint() : published.state();
            next = candidate(main, published.receipts(), published.control(), tasks, published.cancellations(),
                published.memory(), operation.resets() ? 0 : published.timerTicks(), published.prompts(), published.monitors(), published.privateMonitors());
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT, error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    private long advancePrompt(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        var prompts = published.prompts();
        Publication next;
        try {
            if (caller.promptInstruction().orElseThrow().asks()) {
                if (published.memory().active(before.flow()))
                    throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "terminal", "Commit or roll back before waiting for an answer");
                if (caller.question().value().length() > FlowPrompts.MAX_CHARS)
                    throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "terminal", "Terminal question is too long");
                prompts = prompts.enqueue(before.flow());
            }
            caller.executePrompt(prompts.answer());
            caller.setPaused(paused);
            next = promptCandidate(caller.checkpoint(), prompts);
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT, error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    public static DataValue.ListValue monitorValue(Set<String> monitors) {
        return new DataValue.ListValue(new TreeSet<>(monitors).stream().<DataValue>map(DataValue.TextValue::new).toList());
    }
    private long advanceMonitor(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        var op = caller.monitorInstruction().orElseThrow();
        Publication next;
        try {
            if (published.memory().active(before.flow()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "monitor", "Monitor changes are prohibited during a transaction");
            caller.executeMonitor();
            caller.setPaused(paused);
            var state = caller.checkpoint();
            var isMain = before.flow().equals(published.state().flow());
            var tasks = published.tasks();
            if (!isMain || !tasks.contexts().isEmpty()) tasks = tasks.replace(state, before.flow());
            var monitors = new HashSet<>(published.monitors());
            var privateMonitors = published.privateMonitors();
            if (op.privateLocal()) privateMonitors = privateMonitors.change(before, op.cell(), op.visible());
            else if (op.visible()) monitors.add(op.cell()); else monitors.remove(op.cell());
            next = candidate(isMain ? state : published.state(), published.receipts(), published.control(), tasks,
                published.cancellations(), published.memory(), published.prompts(), monitors, privateMonitors);
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT, error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    private long advanceKeyDown(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        Publication next;
        try {
            var op = caller.keyDownInstruction().orElseThrow();
            caller.executeKeyDown(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.held(keyboard.sample(), op.selector()));
            caller.setPaused(paused);
            next = taskCandidate(caller.checkpoint());
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT, error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    private long advanceEvent(FlowRuntime copy, boolean paused) {
        var before = copy.checkpoint();
        Publication next;
        try {
            copy.requireAssemblyCycle(Math.addExact(before.work(), 1));
            if (published.memory().active(before.flow()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "events", "Broadcast is prohibited during a transaction");
            var event = copy.eventInstruction().orElseThrow();
            if(!event.valid())throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT,"tasks","Invalid local task group");
            if (event.receivers().size() > limits.taskSlots() - 1 - published.tasks().contexts().size())
                throw FlowProgram.error(ComputationException.Code.BUSY, "events", "Not enough task slots for the whole broadcast");
            boolean await = event.await() && !event.receivers().isEmpty();
            copy.admitEvent(await); copy.setPaused(paused);
            var children = new ArrayList<FlowRuntime.Checkpoint>();
            for (var state : published.tasks().contexts()) children.add(state.flow().equals(before.flow()) ? copy.checkpoint() : state);
            var slots = published.tasks().slots();
            var childLimits = hardware(limits, published.initialInputs(), published.receipts(), published.control());
            for (var receiver : event.receivers()) {
                FlowTasks.requireSupported(published.program(), receiver);
                var id = UUID.randomUUID();
                var child = FlowRuntime.startProcedure(before.computer(), id, published.program(), receiver, new DataValue.RecordValue(Map.of()), childLimits);
                child.setPaused(paused);
                slots = slots.admitEvent(id, await ? Optional.of(before.flow()) : Optional.empty());
                children.add(child.checkpoint());
            }
            var tasks = new FlowTasks(children, children.isEmpty() ? Optional.empty() : Optional.of(before.flow()), slots);
            next = candidate(before.flow().equals(published.state().flow()) ? copy.checkpoint() : published.state(),
                published.receipts(), published.control(), tasks);
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT,
                error instanceof ArchiveException ? "Broadcast exceeds archive capacity" : error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    private long advanceTaskJoin(FlowRuntime copy, boolean paused) {
        var before = copy.checkpoint();
        Publication next;
        try {
            copy.requireAssemblyCycle(Math.addExact(before.work(), 1));
            if (published.memory().active(before.flow())) throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "tasks", "Task waiting/claiming is prohibited during a transaction");
            var observed = inspectTask(published, before, copy.taskExpected(), copy.taskHandleValue());
            if (copy.atTaskWait()) {
                copy.admitTaskWait(observed.outcome().isPresent()); copy.setPaused(paused);
                next = taskCandidate(copy.checkpoint());
            } else {
                if (observed.outcome().isEmpty()) throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "tasks", "Claim target is not terminal");
                copy.completeTaskClaim(observed.outcome().orElseThrow()); copy.setPaused(paused);
                var retirement = observed.target().isPresent() ? prepareRetirement(published, observed.target().orElseThrow().flow())
                    : new Retirement(published.tasks(), published.receipts(), published.control());
                var tasks = retirement.tasks();
                if (!tasks.contexts().isEmpty()) tasks = tasks.replace(copy.checkpoint(), before.flow());
                next = candidate(before.flow().equals(published.state().flow()) ? copy.checkpoint() : published.state(), retirement.receipts(), retirement.control(), tasks);
            }
        } catch (ArchiveException | ComputationException error) {
            try {
                var rejected = restored(before);
                rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT,
                    error instanceof ArchiveException ? "Task result publication exceeds archive capacity" : error.getMessage());
                rejected.setPaused(paused); next = taskCandidate(rejected.checkpoint());
            } catch (ArchiveException | ComputationException failure) { suspend(failure); throw failure; }
        }
        // Preflight may reject a candidate; a callback after publication must never roll it back.
        apply(next); markChanged.run(); return 1;
    }
    private List<FlowRuntime.Checkpoint> contexts(Saved saved) {
        var states = new ArrayList<FlowRuntime.Checkpoint>(); states.add(saved.state()); states.addAll(saved.tasks().contexts()); return states;
    }
    /** Shared immutable constants once; locals, stacks, pure arenas and retained results for every task. */
    private ResourceLimits hardware(Saved saved, UUID selected) {
        var base = hardware(limits, saved.initialInputs(), saved.receipts(), saved.control());
        long remaining = base.memoryUnits() - (saved.program().usesTimer() ? new DataValue.IntegerValue(0).storageUnits() : 0);
        if (saved.program().usesPrompts()) remaining = Math.subtractExact(remaining, saved.prompts().value().storageUnits());
        if (saved.program().usesMonitor()) remaining = Math.subtractExact(remaining, monitorValue(saved.monitors()).storageUnits());
        if (saved.program().usesPrivateMonitors()) remaining = Math.subtractExact(remaining, saved.privateMonitors().value().storageUnits());
        remaining = Math.subtractExact(remaining, saved.memory().storageUnits());
        for (var witness : saved.cancellations()) remaining = Math.subtractExact(remaining, witness.value().storageUnits());
        if (remaining < 0) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "xbus", "Cancellation history exceeds Computer memory");
        base = base.withMemoryUnits(remaining);
        if (saved.tasks().slots().entries().isEmpty() && saved.pinUnits() == 0 && !saved.program().usesMemoryAssembly()) return base;
        if (saved.tasks().contexts().size() >= limits.taskSlots())
            throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "tasks", "Saved task count exceeds hardware slots");
        if (saved.program().usesBoundedAssemblyRestore()) {
            // Count every wire/register/call control and every already allocated witness first.
            // Only genuinely cold construction/TX buffers may be absent from this initial ledger.
            long shared = saved.program().usage(base).constantUnits();
            long total = Math.addExact(Math.addExact(saved.tasks().metadataUnits(), saved.pinUnits()), shared);
            var privateUnits = new LinkedHashMap<UUID, Long>();
            var states = contexts(saved);
            for (var state : states) {
                long units = state.restoreControlUnits();
                total = Math.addExact(total, units); privateUnits.put(state.flow(), units);
            }
            if (total > base.memoryUnits()) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "tasks", "Task collection exceeds Computer memory");
            if (!privateUnits.containsKey(selected)) throw new IllegalArgumentException("Unknown selected task");
            // Rebuild non-comparison buffers sequentially using actual whole-Computer spare RAM.
            // Update retained ownership immediately, before any other context can validate.
            for (var state : states) {
                if (state.assembly().flatMap(r -> r.comparison()).isPresent()) continue;
                long previous = privateUnits.get(state.flow());
                long envelope = base.memoryUnits() - (total - shared - previous);
                var restored = FlowRuntime.restore(saved.program(), state,
                    base.withMemoryUnits(envelope));
                restored.validateMemory(saved.memory());
                long units = state.controlUnits();
                total = Math.addExact(total, Math.subtractExact(units, previous));
                privateUnits.put(state.flow(), units);
                if (total > base.memoryUnits()) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "tasks", "Task collection exceeds Computer memory");
            }
            // Comparison validation sees the complete retained ledger, never borrowed sibling RAM.
            for (var state : states) {
                if (state.assembly().flatMap(r -> r.comparison()).isEmpty()) continue;
                long envelope = base.memoryUnits() - (total - shared - privateUnits.get(state.flow()));
                var restored = FlowRuntime.restore(saved.program(), state,
                    base.withMemoryUnits(envelope));
                restored.validateMemory(saved.memory());
            }
            long selectedUnits = privateUnits.get(selected);
            return base.withMemoryUnits(base.memoryUnits() - (total - shared - selectedUnits));
        }
        long total = Math.addExact(saved.tasks().metadataUnits(), saved.pinUnits()), selectedUnits = -1, shared = -1;
        for (var state : contexts(saved)) {
            var restored = FlowRuntime.restore(saved.program(), state, base);
            restored.validateMemory(saved.memory());
            if (shared < 0) { shared = restored.sharedUnits(); total = Math.addExact(total, shared); }
            long privateUnits = Math.subtractExact(restored.retainedUnits(), shared);
            total = Math.addExact(total, privateUnits);
            if (state.flow().equals(selected)) selectedUnits = privateUnits;
        }
        if (total > base.memoryUnits()) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "tasks", "Task collection exceeds Computer memory");
        if (selectedUnits < 0) throw new IllegalArgumentException("Unknown selected task");
        return base.withMemoryUnits(base.memoryUnits() - (total - shared - selectedUnits));
    }
    /** Admission probe over the current checkpoint; does not publish, reload or poll a device. */
    public boolean fitsMemory(ComputerHostLimits reduced) {
        if (published == null || runtime == null) return false;
        var previous = limits;
        try {
            limits = reduced;
            for (var state : contexts(published)) {
                var envelope = hardware(published, state.flow());
                var restored = FlowRuntime.restore(published.program(), state, envelope);
                restored.validateMemory(published.memory());
            }
            return true;
        } catch (ComputationException | ArchiveException unavailable) {
            return false;
        } finally { limits = previous; }
    }

    private FlowRuntime restored(FlowRuntime.Checkpoint state) {
        var result = FlowRuntime.restore(published.program(), state, hardware(published, state.flow())); result.load(); return result;
    }
    private Publication taskCandidate(FlowRuntime.Checkpoint next) {
        var tasks = published.tasks();
        return candidate(next.flow().equals(published.state().flow()) ? next : published.state(), published.receipts(), published.control(),
            tasks.contexts().isEmpty() ? tasks : tasks.replace(next, next.flow()));
    }
    private FlowRuntime.Checkpoint readyTask(Map<String, Integer> inputs) {
        var states = contexts(published); int start = 0;
        if (published.tasks().after().isPresent()) {
            UUID after = published.tasks().after().orElseThrow();
            for (int i = 0; i < states.size(); i++) if (states.get(i).flow().equals(after)) { start = i + 1; break; }
        }
        for (int i = 0; i < states.size(); i++) {
            var state = states.get((start + i) % states.size());
            if (FlowTasks.ready(state) && !monitorAwaitsSignal(state, inputs)) return state;
        }
        return null;
    }
    private boolean monitorAwaitsSignal(FlowRuntime.Checkpoint state, Map<String, Integer> inputs) {
        // A missing external sample is not an edge or a permanently failed monitor. Keep its
        // PC/call stack/last valid sample until this pin is available again. Explicit reads
        // in the main script or a handler still execute and report their normal fault.
        return state.entry().isPresent() && published.program().usesWatch()
            && runtime.roundSignalRead(state).filter(read -> read.kind() == RegisterDevices.Kind.SIO_READ
                && !inputs.containsKey(read.pin())).isPresent()
            && published.program().watches().containsKey(state.entry().orElseThrow());
    }
    private long advanceTask(Map<String, Integer> inputs, boolean singleStep) {
        var selected = readyTask(inputs); if (selected == null) return 0;
        try {
            var copy = restored(selected); boolean paused = selected.paused();
            if (singleStep) copy.setPaused(false);
            if (copy.taskJoinReady()) return advanceTaskJoin(copy, paused);
            if (copy.eventReady()) return advanceEvent(copy, paused);
            if (copy.taskStart().isPresent()) return advanceTaskStart(copy, paused);
            if (copy.tryLaunch().isPresent()) return advanceTryLaunch(copy, paused);
        if (copy.promptInstruction().isPresent()) return advancePrompt(copy, paused);
            if (copy.stopInstruction().isPresent()) return advanceStop(copy, paused);
            if (copy.timerInstruction().isPresent()) return advanceTimer(copy, paused);
            if (copy.monitorInstruction().isPresent()) return advanceMonitor(copy, paused);
            if (copy.keyDownInstruction().isPresent()) return advanceKeyDown(copy, paused);
            if (copy.serialReady() && !published.memory().active(copy.checkpoint().flow())) return advanceTaskSerial(copy, paused);
            if (published.program().usesMemoryAssembly()) return advanceMemory(copy, inputs, paused);
            copy.advanceRound(inputs);
            rejectOppositePin(copy);
            if (singleStep) copy.setPaused(paused);
            var next = taskCandidate(copy.checkpoint());
            apply(next); markChanged.run(); return copy.checkpoint().work() - selected.work();
        } catch (ArchiveException | ComputationException error) { suspend(error); throw error; }
    }
    private long advanceTryLaunch(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        var target = caller.tryLaunch().orElseThrow().procedure();
        caller.requireAssemblyCycle(Math.addExact(before.work(), 1));
        if (published.memory().active(before.flow()))
            throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "tasks", "Try-launch is prohibited during a transaction");

        Publication next = null;
        boolean running = published.tasks().contexts().stream().anyMatch(s -> !FlowTasks.terminal(s)
            && s.entry().filter(target::equals).isPresent());
        String refusal = running ? "handler_already_running"
            : published.tasks().contexts().size() >= limits.taskSlots() - 1 ? "handler_slots_full" : null;
        if (refusal == null) try {
            var id = UUID.randomUUID();
            var child = FlowRuntime.startProcedure(before.computer(), id, published.program(), target,
                new DataValue.RecordValue(Map.of()), hardware(limits, published.initialInputs(), published.receipts(), published.control()));
            child.setPaused(paused);
            caller.completeTryLaunch(true); caller.setPaused(paused);
            var children = new ArrayList<FlowRuntime.Checkpoint>();
            for (var state : published.tasks().contexts()) children.add(state.flow().equals(before.flow()) ? caller.checkpoint() : state);
            children.add(child.checkpoint());
            var tasks = new FlowTasks(children, Optional.of(before.flow()), published.tasks().slots().admitEvent(id, Optional.empty()));
            next = candidate(before.flow().equals(published.state().flow()) ? caller.checkpoint() : published.state(),
                published.receipts(), published.control(), tasks);
        } catch (ArchiveException error) {
            if (error.code() != ArchiveException.Code.LIMIT_EXCEEDED) throw error;
            refusal = "handler_archive_full";
        } catch (ComputationException error) {
            if (error.code() != ComputationException.Code.MEMORY_LIMIT) throw error;
            refusal = "handler_memory_full";
        }
        if (next == null) {
            var refused = restored(before);
            refused.completeTryLaunch(false); refused.setPaused(paused);
            next = taskCandidate(refused.checkpoint());
        }
        apply(next); markChanged.run();
        if (refusal != null) launchNotice.accept(refusal, target.id());
        return 1;
    }
    /** The caller spends one scheduler instruction; selecting bounded slots never runs a victim or device. */
    private long advanceStop(FlowRuntime caller, boolean paused) {
        var before = caller.checkpoint();
        Publication next;
        try {
            caller.requireAssemblyCycle(Math.addExact(before.work(), 1));
            if (published.memory().active(before.flow()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "tasks", "Commit or roll back before stopping tasks");
            boolean all = caller.stopInstruction().orElseThrow().all();
            caller.executeStop();
            boolean pauseOwner = all || paused;
            caller.setPaused(pauseOwner);
            var stopped = new HashSet<UUID>();
            if (all) stopped.add(before.flow());
            var children = new ArrayList<FlowRuntime.Checkpoint>(); var main = published.state();
            for (var state : contexts(published)) {
                FlowRuntime.Checkpoint nextState;
                if (state.flow().equals(before.flow())) nextState = caller.checkpoint();
                else {
                    nextState = FlowRuntime.cancelVictim(published.program(), state, pauseOwner);
                    if (nextState.cancelled()) stopped.add(state.flow());
                }
                if (state.flow().equals(main.flow())) main = nextState; else children.add(nextState);
            }
            var slots = published.tasks().slots().detachStoppedWaiters(stopped);
            var tasks = new FlowTasks(children, children.isEmpty() ? Optional.empty() : Optional.of(before.flow()), slots);
            next = candidate(main, published.receipts(), published.control(), tasks);
        } catch (ArchiveException | ComputationException error) {
            var rejected = restored(before);
            rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT,
                error instanceof ArchiveException ? "Task cancellation exceeds archive capacity" : error.getMessage());
            rejected.setPaused(paused);
            next = taskCandidate(rejected.checkpoint());
        }
        apply(next); markChanged.run(); return 1;
    }
    /** Parent PC/handle, child continuation and reused slot are one speculative owner publication. */
    private long advanceTaskStart(FlowRuntime copy, boolean paused) {
        var before = copy.checkpoint();
        try {
            copy.requireAssemblyCycle(Math.addExact(before.work(), 1));
            if (published.memory().active(before.flow()))
                throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "tasks", "Task start is prohibited during a transaction");
            if (published.tasks().contexts().size() >= limits.taskSlots() - 1)
                throw FlowProgram.error(ComputationException.Code.BUSY, "tasks", "All task slots are occupied, including retained results");
            var launch = copy.taskStart().orElseThrow(); FlowTasks.requireSupported(published.program(), launch.procedure());
            var id = UUID.randomUUID(); var slots = published.tasks().slots().admit(id);
            var child = FlowRuntime.startProcedure(before.computer(), id, published.program(), launch.procedure(), copy.taskArguments(),
                hardware(limits, published.initialInputs(), published.receipts(), published.control()));
            child.setPaused(paused);
            int index = 0; while (!slots.entries().get(index).flow().equals(id)) index++;
            copy.completeTaskStart(new com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle(
                before.computer(), published.control().generation(), index + 1, slots.entries().get(index).incarnation(), id));
            copy.setPaused(paused);
            var children = new ArrayList<FlowRuntime.Checkpoint>();
            for (var state : published.tasks().contexts()) children.add(state.flow().equals(before.flow()) ? copy.checkpoint() : state);
            children.add(child.checkpoint());
            var tasks = new FlowTasks(children, Optional.of(before.flow()), slots);
            var next = candidate(before.flow().equals(published.state().flow()) ? copy.checkpoint() : published.state(),
                published.receipts(), published.control(), tasks);
            apply(next); markChanged.run(); return 1;
        } catch (ArchiveException | ComputationException error) {
            // Rejection has no admitted child, slot, handle replacement or advanced parent PC.
            // If even this failure checkpoint cannot fit, retain the old durable owner and suspend.
            try {
                var rejected = restored(before);
                rejected.failTaskStart(error instanceof ComputationException c ? c.code() : ComputationException.Code.MEMORY_LIMIT,
                    error instanceof ArchiveException ? "Task admission exceeds archive capacity" : error.getMessage());
                rejected.setPaused(paused); apply(taskCandidate(rejected.checkpoint())); markChanged.run(); return 1;
            } catch (ArchiveException | ComputationException failure) { suspend(failure); throw failure; }
        }
    }
    private long advanceTaskSerial(FlowRuntime copy, boolean paused) {
        long before = copy.checkpoint().work();
        var prepared = performSerial(copy); copy.setPaused(paused);
        var next = taskCandidate(copy.checkpoint());
        if (prepared != null && !prepared.publish()) return 0;
        apply(next); markChanged.run(); return copy.checkpoint().work() - before;
    }
    private long advanceMemory(FlowRuntime copy, Map<String, Integer> samples, boolean paused) {
        var before = copy.checkpoint();
        var memory = copy.advanceOwnedMemory(published.memory(), samples,published.control().generation()); copy.setPaused(paused);
        rejectOppositePin(copy);
        try {
            var tasks = published.tasks().contexts().isEmpty() ? published.tasks() : published.tasks().replace(copy.checkpoint(), copy.checkpoint().flow());
            var main = copy.checkpoint().flow().equals(published.state().flow()) ? copy.checkpoint() : published.state();
            var next = candidate(main, published.receipts(), published.control(), tasks, published.cancellations(), memory);
            apply(next); markChanged.run(); return copy.checkpoint().work() - before.work();
        } catch (ArchiveException error) {
            suspend(error); throw error;
        } catch (ComputationException error) {
            // Reject the whole candidate: no bank publication without its result/register allocation.
            try {
                var rejected = restored(before); rejected.failOwnedMemory(error.code(), error.getMessage()); rejected.setPaused(paused);
                var next = taskCandidate(rejected.checkpoint()); apply(next); markChanged.run(); return 1;
            } catch (ArchiveException | ComputationException failedPublication) { suspend(failedPublication); throw failedPublication; }
        }
    }
    private void rejectOppositePin(FlowRuntime copy) {
        if (!FlowPinClaims.waiting(copy.checkpoint())) return;
        var owner = published.pinClaims().owner(FlowXBus.pin(copy.program(),copy.checkpoint())).flatMap(published::context);
        if (owner.isPresent() && !owner.orElseThrow().flow().equals(copy.checkpoint().flow())
            && (owner.orElseThrow().status() == FlowRuntime.Status.WAITING_WRITE)
                != (copy.checkpoint().status() == FlowRuntime.Status.WAITING_WRITE)) copy.rejectXBusDirection();
    }
    private void changeTasks(Consumer<FlowRuntime> change) { changeTasks(change, published.timerTicks()); }
    private void changeTasks(Consumer<FlowRuntime> change, long timerTicks) {
        try {
            var main = restored(published.state()); change.accept(main);
            var children = new ArrayList<FlowRuntime.Checkpoint>();
            for (var state : published.tasks().contexts()) { var copy = restored(state); change.accept(copy); children.add(copy.checkpoint()); }
            var next = candidate(main.checkpoint(), published.receipts(), published.control(), new FlowTasks(children, published.tasks().after(), published.tasks().slots()),
                published.cancellations(), published.memory(), timerTicks, published.prompts(), published.monitors(), published.privateMonitors());
            if (!next.saved().equals(published)) { apply(next); markChanged.run(); }
        } catch (ArchiveException | ComputationException error) { suspend(error); throw error; }
    }
    /** One loaded activity tick owned by the physical coordinator, never one tick per instruction. */
    public boolean beginActivityTick() {
        if (!loaded()) return false;
        if (published.state().paused()) return true;
        keyboard.tick();
        long ticks = nextTimerTick();
        boolean hasTasks = !published.tasks().contexts().isEmpty();
        if (hasTasks && ticks == published.timerTicks()
                && contexts(published).stream().noneMatch(state -> state.status() == FlowRuntime.Status.SLEEPING)) return true;
        try {
            if (hasTasks) changeTasks(FlowRuntime::elapseTick, ticks);
            else change(FlowRuntime::elapseTick, ticks);
        }
        catch (ArchiveException | ComputationException ignored) { return false; }
        return loaded();
    }
    /** The stable Computer owner grants this one instruction round from its activity allowance. */
    public long advanceRound(Map<String, Integer> inputs) {
        if (!loaded() || published.state().paused()) return 0;
        if (!published.tasks().contexts().isEmpty()) {
            try { return advanceTask(inputs, false); } catch (ArchiveException | ComputationException ignored) { return 0; }
        }
        long before = published.state().work();
        try { if (runtime.eventReady()) advanceEvent(restored(published.state()), false);
            else if (runtime.taskJoinReady()) advanceTaskJoin(restored(published.state()), false);
            else if (runtime.taskStart().isPresent()) advanceTaskStart(restored(published.state()), false);
            else if (runtime.tryLaunch().isPresent()) advanceTryLaunch(restored(published.state()), false);
            else if (runtime.promptInstruction().isPresent()) advancePrompt(restored(published.state()), false);
            else if (runtime.stopInstruction().isPresent()) advanceStop(restored(published.state()), false);
            else if (runtime.timerInstruction().isPresent()) advanceTimer(restored(published.state()), false);
            else if (runtime.monitorInstruction().isPresent()) advanceMonitor(restored(published.state()), false);
            else if (runtime.keyDownInstruction().isPresent()) advanceKeyDown(restored(published.state()), false);
            else if (runtime.serialReady() && !published.memory().active(published.state().flow())) advanceSerial(false);
            else if (published.program().usesMemoryAssembly()) advanceMemory(restored(published.state()), inputs, false); else change(candidate -> candidate.advanceRound(inputs)); }
        catch (ArchiveException | ComputationException ignored) { return 0; }
        return published.state().work() - before;
    }
    /** Standalone batch with one save boundary; its allowance belongs to the outer Computer. */
    public long tick(long workAllowance, Map<String, Integer> inputs) {
        if (workAllowance < 0) throw new IllegalArgumentException("Negative work allowance");
        if (!loaded() || published.state().paused()) return 0;
        if (!published.tasks().contexts().isEmpty() || (published.program().usesTimer() || published.program().usesMonitor() || published.program().usesPrompts() || published.program().usesKeyDown() || published.program().usesTryLaunch() || published.program().usesEvents() || published.program().usesTaskStartAssembly() || published.program().usesTaskJoinAssembly())) {
            if (!beginActivityTick()) return 0;
            long spent = 0; var frozen = Map.copyOf(inputs);
            for (long remaining = Math.min(workAllowance, limits.hardware().workPerSlice()); remaining > 0; remaining--) {
                long next = advanceRound(frozen); if (next == 0) break; spent += next;
            }
            return spent;
        }
        if (published.state().status() != FlowRuntime.Status.READY && published.state().status() != FlowRuntime.Status.COMPUTING
            && published.state().status() != FlowRuntime.Status.SLEEPING) return 0;
        var frozen = Map.copyOf(inputs);
        long before = published.state().work();
        if (FlowProgram.hasSerial(published.program().steps()) || published.program().usesMemoryAssembly()) {
            if (!beginActivityTick()) return 0;
            for (long left = Math.min(workAllowance, limits.hardware().workPerSlice()); left > 0; left--)
                if (advanceRound(frozen) == 0) break;
            return published.state().work() - before;
        }
        try { change(candidate -> {
            candidate.elapseTick();
            for (long remaining = Math.min(workAllowance, limits.hardware().workPerSlice()); remaining > 0; remaining--)
                if (candidate.advanceRound(frozen) == 0) break;
        }); }
        catch (ArchiveException | ComputationException ignored) { return 0; /* The old save and failure are retained. */ }
        return published.state().work() - before;
    }
    public void setPaused(boolean paused) {
        if (paused) keyboard.clear();
        if (published.tasks().contexts().isEmpty()) change(candidate -> candidate.setPaused(paused));
        else changeTasks(candidate -> candidate.setPaused(paused));
    }
    public void step() { step(limits.pins().unconnectedSignals()); }
    public void step(Map<String, Integer> inputs) {
        var frozen = Map.copyOf(inputs);
        // Explicit debugger permission only: passive paused publications never advance a wait PC.
        var awake = wakeTaskWaiters(published, true);
        if (!awake.equals(published)) {
            apply(candidate(awake.state(), awake.receipts(), awake.control(), awake.tasks(), awake.cancellations(), awake.memory())); markChanged.run();
        }
        if (!published.tasks().contexts().isEmpty()) {
            if (readyTask(frozen) != null) advanceTask(frozen, true);
            else if (contexts(published).stream().anyMatch(state -> state.status() == FlowRuntime.Status.SLEEPING))
                changeTasks(copy -> { boolean paused = copy.checkpoint().paused(); copy.setPaused(false); copy.elapseTick(); copy.setPaused(paused); }, nextTimerTick());
            return;
        }
        if (runtime.eventReady()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false); advanceEvent(copy, paused); return;
        }
        if (runtime.taskJoinReady()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceTaskJoin(copy, paused); return;
        }
        if (runtime.tryLaunch().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceTryLaunch(copy, paused); return;
        }
        if (runtime.taskStart().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceTaskStart(copy, paused); return;
        }
        if (runtime.promptInstruction().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advancePrompt(copy, paused); return;
        }
        if (runtime.stopInstruction().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceStop(copy, paused); return;
        }
        if (runtime.timerInstruction().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceTimer(copy, paused); return;
        }
        if (runtime.monitorInstruction().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceMonitor(copy, paused); return;
        }
        if (runtime.keyDownInstruction().isPresent()) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceKeyDown(copy, paused); return;
        }
        if (runtime.serialReady() && !published.memory().active(published.state().flow())) { advanceSerial(true); return; }
        if (published.program().usesMemoryAssembly() && published.state().status() == FlowRuntime.Status.READY) {
            var copy = restored(published.state()); boolean paused = copy.checkpoint().paused(); copy.setPaused(false);
            advanceMemory(copy, frozen, paused); return;
        }
        long ticks = published.state().status() == FlowRuntime.Status.SLEEPING ? nextTimerTick() : published.timerTicks();
        change(candidate -> {
            boolean paused = candidate.checkpoint().paused(); candidate.setPaused(false);
            if (candidate.checkpoint().status() == FlowRuntime.Status.SLEEPING) candidate.elapseTick(); else candidate.advanceRound(frozen);
            candidate.setPaused(paused);
        }, ticks);
    }
    private void advanceSerial(boolean singleStep) {
        Publication publication; SerialDevice.Prepared prepared;
        try {
            var copy = FlowRuntime.restore(published.program(), published.state(), hardware(limits, published.initialInputs(), published.receipts(), published.control()));
            copy.load(); boolean paused = copy.checkpoint().paused();
            if (singleStep) copy.setPaused(false);
            prepared = performSerial(copy); if (singleStep) copy.setPaused(paused);
            publication = candidate(copy.checkpoint(), published.receipts(), published.control());
        } catch (ArchiveException | ComputationException error) { suspend(error); throw error; }
        // No user/provider callbacks between these two local field publications. One BE saves both roots.
        if (prepared != null && !prepared.publish()) return;
        apply(publication); markChanged.run();
    }
    private SerialDevice.Prepared performSerial(FlowRuntime copy) {
        if(serial!=null && copy.serialInstruction().filter(op->op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.RECEIVE).isPresent()) {
            var op=copy.serialInstruction().orElseThrow();var progress=copy.checkpoint().assembly().orElseThrow().serial();
            if(progress.isEmpty()){
                var head=serial.beginReceive(op.pin());if(head.error()!=null){var prepared=serial.prepareReceiveOutcome(head);copy.completeSerial(prepared.result());return prepared;}
                copy.advanceSerialReceive(head);copy.checkpoint().assembly().orElseThrow().serial().orElseThrow().sealReceive(serial);return null;
            }
            var cursor=progress.orElseThrow();String failed=cursor.failure().isEmpty()?serial.receiveFailure(cursor.proof()):cursor.failure();
            if(failed!=null&&!failed.isEmpty()){var certificate=cursor.certificate()==null?serial.signReceiveCursor(cursor.proof()):cursor.certificate();var prepared=serial.prepareReceiveFailure(cursor.proof(),certificate,failed);copy.completeSerial(prepared.result());return prepared;}
            if(!cursor.done()){copy.advanceSerialReceive(cursor.receive());copy.checkpoint().assembly().orElseThrow().serial().orElseThrow().sealReceive(serial);return null;}
        }
        if (serial!=null && copy.serialEncodingReady()) {
            copy.advanceSerialEncoding(serial.encodingLimits(copy.serialInstruction().orElseThrow().pin()),serial.encodingConfiguration(copy.serialInstruction().orElseThrow().pin()));return null;
        }
        var prepared=prepareSerial(copy);copy.completeSerial(serialResult(copy,prepared));return prepared;
    }
    private SerialDevice.Prepared prepareSerial(FlowRuntime copy) {
        if(serial!=null && copy.serialInstruction().isPresent()) {
            var op=copy.serialInstruction().orElseThrow();
            return switch(op.kind()) {
                case STATUS -> serial.prepareStatus(op.pin(),op.claimFaults());
                case SEND -> serial.prepareEncodedSend(op.pin(),copy.checkpoint().assembly().orElseThrow().serial().orElseThrow().encoded(),copy.checkpoint().assembly().orElseThrow().serial().orElseThrow().configuration());
                case RECEIVE -> {var progress=copy.checkpoint().assembly().orElseThrow().serial().orElseThrow();yield serial.prepareDecodedReceive(progress.receive(),progress.decoded());}
            };
        }
        return serial == null ? null : switch (copy.step()) {
            case FlowProgram.SerialSend send -> serial.prepareSend(send.port(), copy.checkpoint().locals().field(send.local()));
            case FlowProgram.SerialReceive receive -> serial.prepareReceive(receive.port(), receive.nativeNbt());
            case FlowProgram.SerialStatus status -> serial.prepareStatus(status.port(), status.claimReceiveFaults());
            default -> throw new IllegalStateException("Not a serial step");
        };
    }
    private static DataValue.RecordValue serialResult(FlowRuntime copy, SerialDevice.Prepared prepared) {
        var step=copy.step();var op=copy.serialInstruction();
        boolean send=op.map(i->i.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.SEND).orElse(step instanceof FlowProgram.SerialSend);
        boolean receive=op.map(i->i.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Kind.RECEIVE).orElse(step instanceof FlowProgram.SerialReceive);
        return prepared != null ? prepared.result() : send ? SerialResult.send("unavailable")
            : receive ? SerialResult.receive("unavailable", null)
            : SerialResult.status(new SerialDevice.Status("unavailable", "unavailable", "", new SerialReceiveBuffer.Status(0, 0, Set.of())));
    }
    /** Reset only this flow. Compact consumed operations into peer frontiers before releasing their values. */
    public void reset() {
        keyboard.clear();
        var control = published.control().retire(published.receipts(), published.state());
        try {
            var candidate = new FlowRuntime(published.state().computer(), UUID.randomUUID(), published.program(), published.initialInputs(),
                hardware(limits, published.initialInputs(), List.of(), control));
            candidate.setPaused(true); var publication = candidate(candidate.checkpoint(), List.of(), control, startInitialTasks(published.program(),published.state().computer(),published.initialInputs(),control,limits),
                published.cancellations(), RegisterMemory.initial(published.program().expressions().program().cells()), 0, FlowPrompts.EMPTY, Set.of(), FlowPrivateMonitors.EMPTY);
            apply(publication);
            if (hardwareMismatch()) runtime.unload();
            markChanged.run();
        } catch (ArchiveException | ComputationException error) { suspend(error); throw error; }
    }
    private void change(Consumer<FlowRuntime> operation) { change(operation, published.timerTicks()); }
    private void change(Consumer<FlowRuntime> operation, long timerTicks) {
        try {
            var candidate = restored(published.state());
            candidate.load(); operation.accept(candidate); publish(candidate, timerTicks);
        } catch (ArchiveException | ComputationException error) { suspend(error); throw error; }
    }
    private void publish(FlowRuntime candidate) { publish(candidate, published.timerTicks()); }
    private void publish(FlowRuntime candidate, long timerTicks) {
        var next = candidate(candidate.checkpoint(), published.receipts(), published.control(), published.tasks(),
            published.cancellations(), published.memory(), timerTicks, published.prompts(), published.monitors(), published.privateMonitors());
        if (next.saved().equals(published)) return;
        apply(next); markChanged.run();
    }
    private void suspend(RuntimeException error) {
        resetOnly = null; resetChecked = false;
        boolean resource = error instanceof ArchiveException archive && archive.code() == ArchiveException.Code.LIMIT_EXCEEDED
            || error instanceof ComputationException computation && (computation.code() == ComputationException.Code.MEMORY_LIMIT
                || computation.code() == ComputationException.Code.PROGRAM_LIMIT);
        failure = new ComputerBlockHost.Diagnostic(error instanceof FlowPins.Unavailable ? ComputerBlockHost.Code.HARDWARE_MISMATCH
            : resource ? ComputerBlockHost.Code.RESOURCE_LIMIT : ComputerBlockHost.Code.INVALID_SAVE, error.getMessage());
        if (runtime != null) runtime.unload();
    }
    private static ResourceLimits hardware(ComputerHostLimits limits, DataValue.RecordValue initial, List<XBusReceipt> receipts, FlowControl control) {
        long retained = Math.addExact(initial.storageUnits(), control.storageUnits());
        for (var receipt : receipts) retained = Math.addExact(retained, receipt.value().storageUnits());
        long memory = limits.hardware().memoryUnits() - retained;
        if (memory < 0) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT, "flow", "Retained inputs and protocol state exceed memory capacity");
        return limits.hardware().withMemoryUnits(memory);
    }
}
