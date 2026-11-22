package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPrompts;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusReceipt;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowControl;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTasks;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTaskSlots;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPinClaims;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FlowBlockNbtCodec {
    private FlowBlockNbtCodec() {}
    public static final String MARKER = "FlowOwnerVersion";
    /** The marker selects the mode even for invalid/future data: never fall back to a legacy runtime. */
    public static boolean claims(Tag tag) { return tag instanceof CompoundTag compound && compound.contains(MARKER); }
    public static CompoundTag encode(FlowBlockHost.Saved saved, ArchiveLimits limits) {
        var value = obj("initial", saved.initialInputs(), "flow", FlowNbtCodec.value(new FlowNbtCodec.Snapshot(saved.program(), saved.state())),
            "receipts", sequence(saved.receipts(), XBusReceipt::value), "control", saved.control().value());
        if (saved.program().usesMonitor()) {
            var fields = new java.util.LinkedHashMap<>(value.fields());
            fields.put("monitors", FlowBlockHost.monitorValue(saved.monitors()));
            if (saved.program().usesPrivateMonitors()) fields.put("private_monitors", saved.privateMonitors().value());
            value = new DataValue.RecordValue(fields);
        }
        boolean assembly = saved.program().execution() == FlowProgram.Execution.REGISTER_V1;
        boolean timer = saved.program().usesTimer();
        if (timer) {
            var fields = new java.util.LinkedHashMap<>(value.fields());
            fields.put("timer_ticks", new DataValue.IntegerValue(saved.timerTicks()));
            value = new DataValue.RecordValue(fields);
        }
        boolean prompts = saved.program().usesPrompts();
        if (prompts) {
            var fields = new java.util.LinkedHashMap<>(value.fields());
            fields.put("prompts", saved.prompts().value());
            value = new DataValue.RecordValue(fields);
        }
        boolean cancellations = !saved.cancellations().isEmpty();
        boolean instructionEnvelope = requiresInstructionEnvelope(saved);
        if (cancellations || saved.program().usesSignalAssembly() || saved.program().usesXBusAssembly() || saved.program().usesSleepAssembly() || saved.program().usesCallAssembly() || saved.program().usesMemoryAssembly()) { var fields = new java.util.LinkedHashMap<>(value.fields()); fields.put("cancellations", sequence(saved.cancellations(), com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusCancellation::value)); value = new DataValue.RecordValue(fields); }
        if (saved.program().usesMemoryAssembly()) { var fields = new java.util.LinkedHashMap<>(value.fields()); fields.put("memory", saved.memory().value()); value = new DataValue.RecordValue(fields); }
        if (!saved.tasks().slots().entries().isEmpty() || saved.control().taskAware() || saved.taskPins() || assembly || cancellations || instructionEnvelope) {
            var fields = new java.util.LinkedHashMap<>(value.fields());
            fields.put("tasks", obj("contexts", sequence(saved.tasks().contexts(), FlowNbtCodec::taskState),
                "slots", saved.tasks().slots().value(),
                "after", saved.tasks().after().<DataValue>map(id -> new DataValue.TextValue(id.toString())).orElse(DataValue.NullValue.INSTANCE)));
            value = new DataValue.RecordValue(fields);
        }
        if (saved.taskPins() || assembly || cancellations || instructionEnvelope) {
            var fields = new java.util.LinkedHashMap<>(value.fields()); fields.put("pin_claims", saved.pinClaims().value());
            value = new DataValue.RecordValue(fields);
        }
        boolean equality = number(at(at(value, "flow"), "version")) >= 10;
        boolean distinct = number(at(at(value, "flow"), "version")) >= 9;
        boolean operation = saved.state().operation() != saved.state().cursor() || saved.control().wideOperations()
            || saved.receipts().stream().anyMatch(receipt -> receipt.writer().operation() != receipt.writer().cursor()
                || receipt.reader().operation() != receipt.reader().cursor());
        var result = new CompoundTag(); result.putInt(MARKER, FlowProgram.hasCorrelation(saved.program().steps()) ? 12 : FlowProgram.hasSerial(saved.program().steps()) ? 11 : number(at(at(value, "flow"), "version")) >= 11 ? 10 : equality ? 9 : distinct ? 8 : FlowProgram.hasInputSlots(saved.program().steps()) ? 7 : FlowProgram.hasRepeats(saved.program().steps()) ? 6 : FlowProgram.hasLoops(saved.program().steps()) ? 5 : operation ? 4 : 3); result.put("Data", ValueNbtCodec.encodeCheckpoint(value, limits));
        if (FlowProgram.hasNativeReceive(saved.program().steps())) result.putInt(MARKER, 13);
        if (FlowProgram.hasFaultClaim(saved.program().steps())) result.putInt(MARKER, 14);
        if (!saved.tasks().slots().entries().isEmpty()) result.putInt(MARKER, 16);
        if (saved.control().taskAware()) result.putInt(MARKER, 17);
        if (saved.taskPins()) result.putInt(MARKER, 18);
        if (saved.receipts().stream().anyMatch(XBusReceipt::grouped)) result.putInt(MARKER, 19);
        if (assembly) result.putInt(MARKER, saved.state().assembly().orElseThrow().structured() ? 21 : 20);
        if (cancellations) result.putInt(MARKER, 22);
        if (saved.program().usesSignalAssembly()) result.putInt(MARKER, 23);
        if (saved.program().usesXBusAssembly()) result.putInt(MARKER, 24);
        if (saved.program().usesSleepAssembly()) result.putInt(MARKER, 25);
        if (saved.program().usesCallAssembly()) result.putInt(MARKER, 26);
        if (assembly && !saved.tasks().slots().entries().isEmpty()) result.putInt(MARKER, 27);
        if (saved.program().usesMemoryAssembly()) result.putInt(MARKER, 28);
        if (saved.program().usesPublicAssembly()) result.putInt(MARKER, 29);
        if (saved.program().usesTaskStartAssembly()) result.putInt(MARKER, 30);
        if (saved.program().usesTaskJoinAssembly()) result.putInt(MARKER, 31);
        if (saved.program().usesAuthoredAssembly()) result.putInt(MARKER, 32);
        if (saved.program().usesRequiredValueAssembly()) result.putInt(MARKER, 33);
        if(saved.program().usesSerialAssembly())result.putInt(MARKER,34);
        if(saved.program().usesReceiveAssembly())result.putInt(MARKER,35);
        if (saved.program().usesMatchingAssembly()) result.putInt(MARKER,36);
        if(instructionEnvelope)result.putInt(MARKER,37);
        if(saved.program().usesReferences())result.putInt(MARKER,38);
        if(saved.program().usesEvents())result.putInt(MARKER,39);
        if(FlowProgram.hasPublicListEdits(saved.program().steps()))result.putInt(MARKER,40);
        if(FlowProgram.hasWaitUntil(saved.program().steps()))result.putInt(MARKER,42);
        if(FlowProgram.hasStopScript(saved.program().steps()))result.putInt(MARKER,43);
        if(saved.program().usesGroupStop())result.putInt(MARKER,44);
        if(timer)result.putInt(MARKER,45);
        if(prompts)result.putInt(MARKER,46);
        if(saved.program().usesTerminalKeys())result.putInt(MARKER,47);
        if(saved.program().usesKeyDown())result.putInt(MARKER,48);
        if(saved.program().usesTryLaunch())result.putInt(MARKER,49);
        if(saved.program().usesWatch())result.putInt(MARKER,50);
        if (FlowProgram.hasWaitUnits(saved.program().steps())) result.putInt(MARKER, 52);
        if(saved.program().usesMonitor())result.putInt(MARKER,53);
        if(saved.program().usesStartupHats())result.putInt(MARKER,54);
        if(saved.program().usesPrivateMonitors())result.putInt(MARKER,55);
        long temporary=saved.state().assembly().flatMap(r->r.serial()).map(p->p.ownedUnits()).orElse(0L);
        for(var task:saved.tasks().contexts())temporary=Math.addExact(temporary,task.assembly().flatMap(r->r.serial()).map(p->p.ownedUnits()).orElse(0L));
        temporary = Math.addExact(temporary, saved.state().assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L));
        for (var task : saved.tasks().contexts()) temporary = Math.addExact(temporary, task.assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L));
        if(temporary>limits.maxValueUnits()-value.storageUnits())throw ArchiveException.limit();
        return result;
    }
    public static FlowBlockHost.Saved decode(CompoundTag data, ArchiveLimits limits) {
        try {
            if (!data.getAllKeys().equals(Set.of(MARKER, "Data")) || !data.contains(MARKER, Tag.TAG_INT) || !data.contains("Data", Tag.TAG_COMPOUND))
                throw ArchiveException.invalid("Invalid sequential owner fields");
            int version = data.getInt(MARKER);
            if (version < 1 || (version > 40 && version != 42 && version != 43 && version != 44 && version != 45 && version != 46 && version != 47 && version != 48 && version != 49 && version != 50 && version != 52 && version != 53 && version != 54 && version != 55)) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported sequential owner version");
            var value = ValueNbtCodec.decodeCheckpoint(data.getCompound("Data"), limits);
            var flow = FlowNbtCodec.readValue(at(value, "flow"));
            if ((version==55)!=flow.program().usesPrivateMonitors()) throw ArchiveException.invalid("Private monitors require owner schema 55");
            if (version < 54 && (version==53)!=flow.program().usesMonitor()) throw ArchiveException.invalid("Monitor requires owner schema 53");
            if (version < 55 && (version==54)!=flow.program().usesStartupHats()) throw ArchiveException.invalid("Startup scripts require owner schema 54");
            if (version < 53 && (version == 52) != FlowProgram.hasWaitUnits(flow.program().steps())) throw ArchiveException.invalid("Wait units require owner schema 52");
            if (version < 52 && (version == 50) != flow.program().usesWatch()) throw ArchiveException.invalid("Watch requires owner schema 50");
            if (version < 50 && (version==49)!=flow.program().usesTryLaunch()) throw ArchiveException.invalid("Try-launch requires owner schema 49");
            if (version < 49 && (version==48)!=flow.program().usesKeyDown()) throw ArchiveException.invalid("Terminal key reporter requires owner schema 48");
            if (version < 48 && (version==47)!=flow.program().usesTerminalKeys()) throw ArchiveException.invalid("Terminal keys require owner schema 47");
            if (version < 47 && (version == 46) != flow.program().usesPrompts()) throw ArchiveException.invalid("Terminal prompt requires owner schema 46");
            if (version < 46 && (version == 45) != flow.program().usesTimer()) throw ArchiveException.invalid("Timer requires owner schema 45");
            if (version < 45 && (version == 44) != flow.program().usesGroupStop()) throw ArchiveException.invalid("Group stop requires owner schema 44");
            if (version < 44 && (version == 43) != FlowProgram.hasStopScript(flow.program().steps())) throw ArchiveException.invalid("Stop script requires owner schema 43");
            if (version < 43 && (version == 42) != FlowProgram.hasWaitUntil(flow.program().steps())) throw ArchiveException.invalid("Wait until requires owner schema 42");
            if (version < 42 && (version==40)!=FlowProgram.hasPublicListEdits(flow.program().steps())) throw ArchiveException.invalid("Public list edits require owner schema 40");
            if (version < 40 && (version == 39) != flow.program().usesEvents()) throw ArchiveException.invalid("Events require owner schema 39");
            if(version<39 && (version==38)!=flow.program().usesReferences())throw ArchiveException.invalid("Local references require owner schema 38");
            var keys = switch (version) {
                case 33, 34, 35, 36, 37, 38, 39, 40, 42, 43, 44, 45, 46, 47, 48, 49, 50, 52, 53, 54, 55 -> {
                    var names = new java.util.HashSet<>(Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims"));
                    if (flow.program().usesSignalAssembly() || flow.program().usesXBusAssembly() || flow.program().usesSleepAssembly() || flow.program().usesCallAssembly() || flow.program().usesMemoryAssembly() || fields(value).containsKey("cancellations") && !list(at(value,"cancellations")).isEmpty()) names.add("cancellations");
                    if (flow.program().usesMemoryAssembly()) names.add("memory");
                    if (flow.program().usesTimer()) names.add("timer_ticks");
                    if (flow.program().usesPrompts()) names.add("prompts");
                    if (flow.program().usesMonitor()) names.add("monitors");
                    if (flow.program().usesPrivateMonitors()) names.add("private_monitors");
                    yield names;
                }
                case 30, 31, 32 -> flow.program().usesMemoryAssembly()
                    ? Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims", "cancellations", "memory")
                    : Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims", "cancellations");
                case 1 -> Set.of("initial", "flow"); case 2 -> Set.of("initial", "flow", "receipts");
                case 15, 16, 17 -> Set.of("initial", "flow", "receipts", "control", "tasks");
                case 18, 19, 20, 21 -> Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims");
                case 22, 23, 24, 25, 26, 27 -> Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims", "cancellations");
                case 28, 29 -> Set.of("initial", "flow", "receipts", "control", "tasks", "pin_claims", "cancellations", "memory");
                default -> Set.of("initial", "flow", "receipts", "control");
            };
            if (!fields(value).keySet().equals(keys)) throw ArchiveException.invalid("Invalid sequential owner data");
            
            if (version<37 && (version == 36) != flow.program().usesMatchingAssembly()) throw ArchiveException.invalid("Native correlation/comparison requires owner schema 36");
            if (version < 36 && (version == 35) != flow.program().usesReceiveAssembly()) throw ArchiveException.invalid("Serial RX requires owner schema 35");
            if (version < 35 && (version == 34) != flow.program().usesSerialAssembly()) throw ArchiveException.invalid("Serial TX requires owner schema 34");
            if (version < 34 && (version == 33) != flow.program().usesRequiredValueAssembly()) throw ArchiveException.invalid("Require value requires owner schema 33");
            if (version < 33 && (version == 32) != flow.program().usesAuthoredAssembly()) throw ArchiveException.invalid("Authored assembly requires owner schema 32");
            if (version < 32 && (version == 31) != flow.program().usesTaskJoinAssembly()) throw ArchiveException.invalid("Task join requires owner schema 31");
            if (version < 31 && (version == 30) != flow.program().usesTaskStartAssembly()) throw ArchiveException.invalid("Task start requires owner schema 30");
            if (version < 30 && (version == 29) != flow.program().usesPublicAssembly()) throw ArchiveException.invalid("Public cells require owner schema 29");
            if (version < 30 && (version >= 28) != flow.program().usesMemoryAssembly()) throw ArchiveException.invalid("Memory assembly requires owner schema 28");
            if (version < 28 && (version >= 26) != flow.program().usesCallAssembly()) throw ArchiveException.invalid("Call assembly requires owner schema 26");
            if (version < 26 && (version == 25) != flow.program().usesSleepAssembly()) throw ArchiveException.invalid("Sleep assembly requires owner schema 25");
            if (version < 25 && (version == 24) != flow.program().usesXBusAssembly()) throw ArchiveException.invalid("XBus assembly requires owner schema 24");
            if (version < 24 && (version == 23) != flow.program().usesSignalAssembly()) throw ArchiveException.invalid("Signal assembly requires owner schema 23");
            if (version < 22 && (version >= 20) != (flow.program().execution() == FlowProgram.Execution.REGISTER_V1)) throw ArchiveException.invalid("Assembly requires owner schema 20 or 21");
            if (version < 22 && (version == 21) != flow.state().assembly().map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState::structured).orElse(false)) throw ArchiveException.invalid("Data assembly requires owner schema 21");
            if (version < 14 && FlowProgram.hasFaultClaim(flow.program().steps()))
                throw ArchiveException.invalid("Receive fault claim requires owner schema 14");
            if (version < 13 && FlowProgram.hasNativeReceive(flow.program().steps()))
                throw ArchiveException.invalid("Native NBT receive requires owner version 13");
            if (version < 12 && FlowProgram.hasCorrelation(flow.program().steps()))
                throw ArchiveException.invalid("Correlation continuations require owner version 12");
            // Later pure features (e.g. explicit calls in snapshot 16) do not acquire serial state.
            // Gate the same capability the encoder uses, including already-saved owner-10 calls.
            if (version < 11 && FlowProgram.hasSerial(flow.program().steps()))
                throw ArchiveException.invalid("Serial continuations require owner version 11");
            if (version < 10 && number(at(at(value, "flow"), "version")) >= 11)
                throw ArchiveException.invalid("XBus result continuations require owner version 10");
            if (version < 9 && number(at(at(value, "flow"), "version")) >= 10)
                throw ArchiveException.invalid("Value equality continuations require owner version 9");
            if (version < 8 && number(at(at(value, "flow"), "version")) >= 9)
                throw ArchiveException.invalid("Distinct continuations require owner version 8");
            if (version < 7 && number(at(at(value, "flow"), "version")) >= 8)
                throw ArchiveException.invalid("Input slots and Until continuations require owner version 7");
            if (version < 6 && number(at(at(value, "flow"), "version")) >= 7)
                throw ArchiveException.invalid("Counted continuations require owner version 6");
            if (version < 5 && number(at(at(value, "flow"), "version")) >= 6)
                throw ArchiveException.invalid("Loop continuations require owner version 5");
            if (version < 4 && number(at(at(value, "flow"), "version")) >= 5)
                throw ArchiveException.invalid("Independent flow operations require owner version 4");
            List<XBusReceipt> receipts = version == 1 ? List.of() : list(at(value, "receipts")).stream().map(v -> receipt(v, version)).toList();
            var control = version >= 3 ? FlowControlCodec.decode(at(value, "control"), version >= 4, version >= 17) : FlowControl.fromReceipts(receipts, flow.state());
            var tasks = FlowTasks.EMPTY;
            if (version >= 15) {
                var dataTasks = at(value, "tasks");
                if (!fields(dataTasks).keySet().equals(version == 15 ? Set.of("contexts", "after") : Set.of("contexts", "after", "slots")))
                    throw ArchiveException.invalid("Invalid task collection fields");
                boolean redstone = number(at(at(value, "flow"), "version")) >= 20;
                var contexts = list(at(dataTasks, "contexts")).stream().map(v -> version >= 27 && flow.program().execution() == FlowProgram.Execution.REGISTER_V1 ? FlowNbtCodec.readAssemblyTaskState(v, flow.program().usesMemoryAssembly(), flow.program().usesConstructionAssembly(),flow.program().usesSerialAssembly(),flow.program().usesValueEqualityAssembly()) : FlowNbtCodec.readTaskState(v, redstone)).toList();
                if (version == 15 && contexts.isEmpty()) throw ArchiveException.invalid("Empty schema-15 task collection");
                var after = at(dataTasks, "after");
                var slots = version == 15 ? FlowTaskSlots.legacy(contexts) : new FlowTaskSlots(list(at(dataTasks, "slots")).stream().map(v -> {
                    boolean event = version >= 39 && fields(v).containsKey("event_waiter");
                    if (!fields(v).keySet().equals(event ? Set.of("flow","incarnation","retired","event_waiter") : Set.of("flow","incarnation","retired")))
                        throw ArchiveException.invalid("Invalid task slot fields");
                    var waiter = event ? at(v,"event_waiter") : DataValue.NullValue.INSTANCE;
                    return new FlowTaskSlots.Slot(UUID.fromString(string(at(v,"flow"))),number(at(v,"incarnation")),bool(at(v,"retired")),event,
                        waiter == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(UUID.fromString(string(waiter))));
                }).toList());
                if (version < 17 && slots.entries().isEmpty()) throw ArchiveException.invalid("Empty task slot collection");
                tasks = new FlowTasks(contexts, after == DataValue.NullValue.INSTANCE ? java.util.Optional.empty()
                    : java.util.Optional.of(UUID.fromString(string(after))), slots);
            }
            if (version < 28 && (version == 27) != (flow.program().execution() == FlowProgram.Execution.REGISTER_V1 && !tasks.slots().entries().isEmpty()))
                throw ArchiveException.invalid("Assembly task slots require owner schema 27");
            if (version < 18 && tasks.contexts().stream().anyMatch(s -> !FlowTasks.xbusPins(flow.program(), s.entry().orElseThrow()).isEmpty()))
                throw ArchiveException.invalid("Task pin operations require owner version 18");
            var pins = version < 18 ? FlowPinClaims.EMPTY.reconcile(flow.program(), flow.state(), tasks)
                : new FlowPinClaims(list(at(value, "pin_claims")).stream().map(v -> {
                    if (!fields(v).keySet().equals(Set.of("flow", "pin", "operation"))) throw ArchiveException.invalid("Invalid pin request fields");
                    return new FlowPinClaims.Claim(UUID.fromString(string(at(v, "flow"))), string(at(v, "pin")), number(at(v, "operation")));
                }).toList());
            var cancellations = version >= 22 && (version < 34 || fields(value).containsKey("cancellations")) ? list(at(value, "cancellations")).stream().map(v -> XBusCancellationCodec.decode(v, version)).toList()
                : List.<com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusCancellation>of();
            if (version == 22 && cancellations.isEmpty()) throw ArchiveException.invalid("Empty cancellation owner schema");
            var memory = flow.program().usesMemoryAssembly() ? RegisterMemory.restore(at(value, "memory"), flow.program().expressions().program().cells()) : RegisterMemory.EMPTY;
            long comparisonTemporary = flow.state().assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L);
            for (var task : tasks.contexts()) comparisonTemporary = Math.addExact(comparisonTemporary,
                task.assembly().flatMap(r -> r.comparison()).map(p -> p.ownedUnits()).orElse(0L));
            if (comparisonTemporary > limits.maxValueUnits() - value.storageUnits()) throw ArchiveException.limit();
            var prompts = FlowPrompts.EMPTY;
            if (flow.program().usesPrompts()) {
                var prompt = at(value, "prompts");
                if (!fields(prompt).keySet().equals(Set.of("answer", "pending"))) throw ArchiveException.invalid("Invalid terminal prompt fields");
                prompts = new FlowPrompts(new DataValue.TextValue(string(at(prompt, "answer"))),
                    list(at(prompt, "pending")).stream().map(id -> UUID.fromString(string(id))).toList());
            }
            var monitors = new java.util.LinkedHashSet<String>();
            if (flow.program().usesMonitor())
                for (var name : list(at(value, "monitors")))
                    if (!monitors.add(string(name))) throw ArchiveException.invalid("Duplicate monitor name");
            var privateMonitors = flow.program().usesPrivateMonitors()
                ? com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPrivateMonitors.decode(at(value, "private_monitors"))
                : com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPrivateMonitors.EMPTY;
            var saved = new FlowBlockHost.Saved(flow.program(), record(at(value, "initial")), flow.state(), receipts, control, tasks, pins, cancellations, memory,
                flow.program().usesTimer() ? number(at(value, "timer_ticks")) : 0, prompts, monitors, privateMonitors);
            if (version<38 && (version == 37) != requiresInstructionEnvelope(saved))
                throw ArchiveException.invalid("Authored v4 source or XBus instruction metadata requires owner schema 37");
            // This owner-private cold boundary is the only capability reconstruction path.
            // Normal publication validates just actual Ref operands, not all register roots again.
            if(value.localReferences()!=0){
                var contexts=new java.util.ArrayList<>(tasks.contexts());contexts.add(flow.state());
                for(var context:contexts)if(context.assembly().isPresent()){
                    var registers=context.assembly().orElseThrow();
                    for(var root:registers.registers().values())com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterReference.validate(root,context.computer(),control.generation(),registers.image(),context.flow(),memory);
                    registers.calls().ifPresent(calls->calls.frames().forEach(frame->frame.registers().values().forEach(root->com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterReference.validate(root,context.computer(),control.generation(),registers.image(),context.flow(),memory))));
                }
            }
            return saved;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ArchiveException archive) throw archive;
            throw ArchiveException.invalid("Invalid sequential owner: " + invalid.getMessage());
        }
    }
    /** Shared receipts/cancellations may carry a newer peer's PC even when this owner's source is old.
     * Only the envelope changes; source, execution image and no-instruction saves retain their identity. */
    private static boolean requiresInstructionEnvelope(FlowBlockHost.Saved saved) {
        return saved.program().usesAuthoredV4()
            || saved.receipts().stream().flatMap(r -> r.positions().stream()).anyMatch(p -> p.instruction() >= 0)
            || saved.cancellations().stream().flatMap(c -> c.writers().stream()).anyMatch(p -> p.instruction() >= 0);
    }
    private static XBusReceipt receipt(DataValue value, int version) {
        boolean group = fields(value).containsKey("other_writers") || fields(value).containsKey("other_readers");
        if (group && version < 19) throw ArchiveException.invalid("Group receipt requires owner version 19");
        if (!fields(value).keySet().equals(group ? Set.of("writer", "reader", "payload", "other_writers", "other_readers")
                : Set.of("writer", "reader", "payload"))) throw ArchiveException.invalid("Invalid XBus receipt fields");
        return new XBusReceipt(position(at(value, "writer"), version), position(at(value, "reader"), version), record(at(value, "payload")),
            group ? list(at(value, "other_writers")).stream().map(v -> position(v, version)).toList() : List.of(),
            group ? list(at(value, "other_readers")).stream().map(v -> position(v, version)).toList() : List.of());
    }
    static XBusReceipt.Position position(DataValue value, int version) {
        var names = new java.util.HashSet<>(version < 3 ? Set.of("computer", "flow", "cursor", "pin")
            : Set.of("computer", "flow", "cursor", "pin", "generation"));
        boolean operation = version >= 4 && fields(value).containsKey("operation");
        if (operation) names.add("operation");
        boolean instruction=version>=37 && fields(value).containsKey("instruction");if(instruction)names.add("instruction");
        boolean task = version >= 18 && (fields(value).containsKey("slot") || fields(value).containsKey("incarnation"));
        if (task) { names.add("slot"); names.add("incarnation"); }
        if (!fields(value).keySet().equals(names)) throw ArchiveException.invalid("Invalid XBus position fields");
        return new XBusReceipt.Position(UUID.fromString(string(at(value, "computer"))), UUID.fromString(string(at(value, "flow"))),
            index(at(value, "cursor")), string(at(value, "pin")), version < 3 ? 0 : number(at(value, "generation")),
            operation ? number(at(value, "operation")) : index(at(value, "cursor")),
            task ? index(at(value, "slot")) : 0, task ? number(at(value, "incarnation")) : 0, instruction?index(at(value,"instruction")):-1);
    }
}
