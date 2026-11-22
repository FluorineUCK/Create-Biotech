package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Typed ISA installation. The execution path has no graph, node references, named expressions or world access.
 * Scalar instructions, including loop back-edges, each perform one bounded step. */
public final class RegisterProgram {
    public sealed interface Instruction permits RegisterTaskGroup, RegisterTimer, RegisterPrompt, DefaultNumber, RegisterMonitor, RegisterKeyDown, RegisterEvent, RegisterReference, RegisterNbt.Instruction, FormatCheck, FormatProject, ValueCheck, HasField, ValueEquals, Correlation, RandomBelow, RandomRange, AppendList, RegisterListEdit, MakeList, MakeRecord, Constant, Move, Alu, Nonnegative, JumpFalse, Jump, Field, Index, DynamicIndex, ListLength, TextLength, TextNext, TextConcat, TextLetter, TextContains, Sleep, Call, Return, Drop, TryLaunch, TaskStart, TaskWait, TaskClaim, TaskStop, RegisterDevices.Instruction, RegisterXBus.Instruction, RegisterSerial.Instruction, RegisterMemoryOp {
        int words();
        default InstructionFamily family() { return InstructionFamily.of(this); }
    }
    /** Immutable append: admission, one cycle per source occurrence, element, then atomic publication. */
    public record AppendList(int to, int from, int element) implements Instruction {
        public int words() { return 4; }
    }
    public record MakeList(int to, List<Integer> from) implements Instruction {
        public MakeList {
            from = List.copyOf(from);
        }
        public int words() {
            return Math.addExact(3, from.size());
        }
    }
    public record NamedOperand(String key, int from) {
        public NamedOperand {
            Objects.requireNonNull(key);
        }
    }
    public static final class MakeRecord implements Instruction {
        private final int to;
        private final List<NamedOperand> fields;
        private final DataValue.RecordShape shape;
        private final List<Integer> from;
        public MakeRecord(int to, List<NamedOperand> fields) {
            this.to = to;
            this.fields = List.copyOf(fields);
            shape = new DataValue.RecordShape(this.fields.stream().map(NamedOperand::key).toList());
            from = this.fields.stream().map(NamedOperand::from).toList();
        }
        public int to() {
            return to;
        }
        public List<NamedOperand> fields() {
            return fields;
        }
        public DataValue.RecordShape shape() {
            return shape;
        }
        public List<Integer> from() {
            return from;
        }
        @Override public boolean equals(Object other) {
            return other instanceof MakeRecord record && to == record.to && fields.equals(record.fields);
        }
        @Override public int hashCode() { return Objects.hash(to, fields); }
        @Override public String toString() { return "MakeRecord[to=" + to + ", fields=" + fields + "]"; }
        public int words() {
            int words = 3;
            for (var field : fields) {
                words = Math.addExact(words, Math.addExact(2, field.key().length()));
            }
            return words;
        }
    }
    public static long work(Instruction op) {
        if (op instanceof RegisterListEdit edit)
            return edit.operation() == ListOperation.REMOVE || edit.operation() == ListOperation.SLICE ? 2 : 3;
        if (op instanceof TextConcat || op instanceof TextContains) return 2;
        if (op instanceof RegisterNbt.Instruction n && (n.codec() || n.joining())) return 2;
        // Compound also needs its named-prefix-to-native-validation seal step, even when empty.
        if (op instanceof RegisterNbt.Instruction n && n.packing()) return n.operand()==10?3:2;
        if (op instanceof AppendList || op instanceof FormatCheck || op instanceof ValueEquals || op instanceof ValueCheck) return 3;
        if (op instanceof MakeList list) return 2L + list.from().size();
        if (op instanceof MakeRecord record) return 2L + record.fields().size();
        return 1;
    }
    /** Shared by call and ordinary path validation; a new incremental opcode must not get a fixed upper bound. */
    public static boolean variableWork(Instruction op) {
        return op instanceof RegisterNbt.Instruction n && (n.packing() || n.codec() || n.joining()) || op instanceof AppendList || op instanceof RegisterListEdit || op instanceof FormatCheck || op instanceof ValueCheck || op instanceof ValueEquals || op instanceof HasField || op instanceof TextConcat || op instanceof TextContains || op instanceof TextLetter || op instanceof TextLength
            || retriesRandom(op) || op instanceof RegisterSerial.Instruction serial && serial.kind() != RegisterSerial.Kind.STATUS;
    }
    /** Ordinary text, scoped to the owning flow and paid operation ordinal. */
    public record HasField(int to, int from, int key) implements Instruction { public int words() { return 4; } }
    public record ValueEquals(int to, int left, int right) implements Instruction { public int words() { return 4; } }
    /** Checked result is {ok:boolean,value:optional T}; T is declared, never inferred from a sample. */
    public record ValueCheck(int to, int from) implements Instruction { public int words() { return 3; } }
    public record FormatCheck(int to, int from, String fmt, int version) implements Instruction {
        public FormatCheck { format(fmt,version); }
        public int words() { return 5+fmt.length(); }
    }
    public record FormatProject(int to, int from, String fmt, int version) implements Instruction {
        public FormatProject { format(fmt,version); }
        public int words() { return 5+fmt.length(); }
    }
    private static void format(String fmt,int version) {
        DataType.Semantic.recipe(fmt,version);
    }
    /** Uniform exclusive upper bound; each rejected sample consumes one owner cycle. */
    static boolean retriesRandom(Instruction op) { return op instanceof RandomBelow || op instanceof RandomRange range && !range.decimal(); }

    public record RandomRange(int to, int from, int through, boolean decimal) implements Instruction {
        public int words() { return 4; }
        public DataType.Scalar type() { return decimal ? DataType.Scalar.DECIMAL : DataType.Scalar.INTEGER; }
    }
    public record RandomBelow(int to, int bound) implements Instruction { public int words() { return 3; } }
    public record Correlation(int to) implements Instruction { public int words() { return 2; } }
    public record Constant(int to, int pool) implements Instruction { public int words() { return 3; } }
    /** Initialize an unassigned numeric local from another; never observes a device. */
    public record DefaultNumber(int to, int from) implements Instruction { public int words() { return 3; } }
    public record Move(int to, int from) implements Instruction { public int words() { return 3; } }
    public record Alu(ScalarOperation operation, int to, List<Integer> from) implements Instruction {
        public Alu { Objects.requireNonNull(operation); from = List.copyOf(from); }
        public int words() { return 2 + from.size(); }
    }
    public record JumpFalse(int condition, int target) implements Instruction { public int words() { return 3; } }
    public record Jump(int target) implements Instruction { public int words() { return 2; } }
    public record Nonnegative(int value) implements Instruction { public int words() { return 2; } }
    public record Field(int to, int from, String key) implements Instruction {
        public Field { Objects.requireNonNull(key); }
        public int words() { return Math.addExact(4, key.length()); }
    }
    public record Index(int to, int from, int index) implements Instruction { public int words() { return 4; } }
    /** Runtime logical index, zero-based; range is checked before narrowing to Java int. */
    public record DynamicIndex(int to, int from, int indexRegister) implements Instruction { public int words() { return 4; } }
    /** Immutable list size, no element traversal or copy. */
    public record ListLength(int to, int from) implements Instruction { public int words() { return 3; } }
    /** One codepoint at an explicit UTF-16 boundary; fixed shape, no scan or policy. */
    public record TextNext(int to, int from, int offset) implements Instruction {
        public static final DataType.RecordType TYPE = new DataType.RecordType(Map.of(
            "done", DataType.Scalar.BOOLEAN, "next", DataType.Scalar.INTEGER, "codepoint", DataType.Scalar.INTEGER,
            "whitespace", DataType.Scalar.BOOLEAN, "control", DataType.Scalar.BOOLEAN));
        // 1 record + five (field occurrence + name + scalar) entries. Reserve before allocation.
        public static final long RESULT_UNITS = 45;
        public int words() { return 4; }
    }
    /** One-based Unicode code-point position; absent positions return empty text. */
    public record TextLetter(int to, int from, int index) implements Instruction { public int words() { return 4; } }
    public record TextContains(int to, int text, int needle) implements Instruction { public int words() { return 4; } }
    public record TextConcat(int to, int left, int right) implements Instruction { public int words() { return 4; } }
    public record TextLength(int to, int from) implements Instruction { public int words() { return 3; } }
    /** Base control instruction: one admission cycle; only the owner's active clock wakes it. */
    public record Sleep(int ticks) implements Instruction { public int words() { return 2; } }
    public record Call(int target) implements Instruction { public int words() { return 2; } }
    /** Arguments have each been metered into fresh staging registers before admission. */
    public record TryLaunch(ModuleDefinition.Key procedure, int started) implements Instruction {
        public TryLaunch { Objects.requireNonNull(procedure); }
        public int words() { return Math.addExact(4, procedure.id().length()); }
    }
    public record TaskStart(ModuleDefinition.Key procedure, Map<String, Integer> arguments, int handle) implements Instruction {
        public TaskStart { Objects.requireNonNull(procedure); arguments = Collections.unmodifiableMap(new TreeMap<>(arguments)); }
        public int words() { return Math.addExact(4 + procedure.id().length(), Math.multiplyExact(2, arguments.size())); }
    }
    public record TaskWait(ModuleDefinition.Key procedure, int handle) implements Instruction {
        public TaskWait { Objects.requireNonNull(procedure); }
        public int words() { return 3 + procedure.id().length(); }
    }
    public record TaskStop(boolean all) implements Instruction { public int words() { return 2; } }
    public record TaskClaim(ModuleDefinition.Key procedure, int handle, int outcome) implements Instruction {
        public TaskClaim { Objects.requireNonNull(procedure); }
        public int words() { return 4 + procedure.id().length(); }
    }
    public record Return() implements Instruction { public int words() { return 1; } }
    /** Release a dead operand slot; payloads are immutable values, never mutable aliases. */
    public record Drop(int register) implements Instruction { public int words() { return 2; } }
    public record Code(Instruction instruction, int source, String location) {
        public Code { Objects.requireNonNull(instruction); Objects.requireNonNull(location); if (source < 0) throw new IllegalArgumentException("source"); }
    }
    private final List<Code> code;
    private final List<DataType> types;
    private final List<DataValue> constants;
    private final Map<String, Integer> inputs, locals;
    private final Map<Integer, RegisterProcedure> procedures;
    private final RegisterProcedure taskRoot;
    private final int[] owners;
    private final int endSource;
    private final String fingerprint;
    private final Set<Integer> softwareAnyRegisters;
    private final long words, constantUnits;
    private final boolean structured;
    private final RegisterSignalModes signals;
    private final RegisterCallWork callWork;
    private final long[] minimum, maximum;
    private final List<Set<Integer>> required, possible;
    private final List<Set<Integer>> edges = new ArrayList<>();
    private final List<List<Integer>> predecessors = new ArrayList<>();
    private final Map<String, ProgramDefinition.Cell> cells;
    private final boolean memoryEnabled;
    private final boolean constructionEnabled;
    private final boolean serialEnabled;
    private final boolean equalityEnabled;
    private final Map<Integer, ValueTypeTask.Schema> typeChecks;
    private final Set<InstructionFamily> instructionFamilies;
    private final Optional<String> advancedTypeLocation;
    private final Map<String,DataType> authoredTypes;
    public Map<String,DataType> authoredTypes() { return authoredTypes; }
    public Optional<String> structuredTypeLocation() { return authoredTypes.entrySet().stream().filter(e -> AssemblyTypeRequirements.structured(e.getValue())).map(Map.Entry::getKey).findFirst(); }

    public RegisterProgram(List<Code> code, List<DataType> types, List<DataValue> constants,
            Map<String, Integer> inputs, Map<String, Integer> locals, int endSource) {
        this(code, types, constants, inputs, locals, endSource, List.of());
    }
    public RegisterProgram(List<Code> code, List<DataType> types, List<DataValue> constants,
            Map<String, Integer> inputs, Map<String, Integer> locals, int endSource, List<RegisterProcedure> procedures) {
        this(code, types, constants, inputs, locals, endSource, procedures, Map.of());
    }
    public RegisterProgram(List<Code> code, List<DataType> types, List<DataValue> constants,
            Map<String, Integer> inputs, Map<String, Integer> locals, int endSource, List<RegisterProcedure> procedures,
            Map<String, ProgramDefinition.Cell> cells) {
        this(code, types, constants, inputs, locals, endSource, procedures, cells, Optional.empty());
    }
    /** Compiler-derived authored type requirement. It is rederived on load, never accepted from a save capability claim. */
    public RegisterProgram(List<Code> code, List<DataType> types, List<DataValue> constants,
            Map<String, Integer> inputs, Map<String, Integer> locals, int endSource, List<RegisterProcedure> procedures,
            Map<String, ProgramDefinition.Cell> cells, Optional<String> advancedTypeLocation) {
        this(code,types,constants,inputs,locals,endSource,procedures,cells,advancedTypeLocation,Set.of());
    }
    public RegisterProgram(List<Code> code,List<DataType> types,List<DataValue> constants,
            Map<String,Integer> inputs,Map<String,Integer> locals,int endSource,List<RegisterProcedure> procedures,
            Map<String,ProgramDefinition.Cell> cells,Optional<String> advancedTypeLocation,Set<Integer> softwareAnyRegisters) {
        this(code,types,constants,inputs,locals,endSource,procedures,cells,advancedTypeLocation,softwareAnyRegisters,Map.of());
    }
    public RegisterProgram(List<Code> code,List<DataType> types,List<DataValue> constants,
            Map<String,Integer> inputs,Map<String,Integer> locals,int endSource,List<RegisterProcedure> procedures,
            Map<String,ProgramDefinition.Cell> cells,Optional<String> advancedTypeLocation,Set<Integer> softwareAnyRegisters,
            Map<String,DataType> authoredTypes) {
        this.authoredTypes=Collections.unmodifiableMap(new TreeMap<>(authoredTypes));
        if(advancedTypeLocation.isEmpty()) advancedTypeLocation=this.authoredTypes.entrySet().stream()
            .filter(e -> AssemblyTypeRequirements.dynamic(e.getValue())).map(Map.Entry::getKey).findFirst();
        this.softwareAnyRegisters=Collections.unmodifiableSet(new TreeSet<>(softwareAnyRegisters));
        for(int r:softwareAnyRegisters)if(r<0||r>=types.size()||!types.get(r).equals(SoftwareAnyAbi.TYPE))throw new IllegalArgumentException("Invalid software ANY slot");
        equalityEnabled = code.stream().anyMatch(c -> c.instruction() instanceof FormatCheck || c.instruction() instanceof ValueEquals || c.instruction() instanceof HasField || c.instruction() instanceof ValueCheck);
        serialEnabled = code.stream().anyMatch(c->c.instruction() instanceof RegisterSerial.Instruction);
        constructionEnabled = code.stream().anyMatch(c->c.instruction() instanceof RegisterNbt.Instruction n && (n.kind()==RegisterNbt.Kind.PACK || n.codec() || n.joining()) || c.instruction() instanceof AppendList || c.instruction() instanceof RegisterListEdit || c.instruction() instanceof MakeList || c.instruction() instanceof MakeRecord || c.instruction() instanceof TextConcat || c.instruction() instanceof TextContains);
        this.cells = Collections.unmodifiableMap(new TreeMap<>(cells));
        memoryEnabled = !cells.isEmpty() || code.stream().anyMatch(c -> c.instruction() instanceof RegisterMemoryOp);
        this.code = List.copyOf(code); this.types = List.copyOf(types); this.constants = List.copyOf(constants);
        constants.forEach(LocalReferences::portable);
        if(types.stream().anyMatch(t->!(t instanceof DataType.RefType)&&LocalReferences.contains(t)))throw new IllegalArgumentException("Nested reference registers are not supported");
        if(inputs.values().stream().anyMatch(r->LocalReferences.contains(types.get(r))))throw new IllegalArgumentException("Reference external register inputs are not supported");
        Objects.requireNonNull(advancedTypeLocation);
        if (advancedTypeLocation.isEmpty() && types.stream().anyMatch(AssemblyTypeRequirements::semantic)) advancedTypeLocation = Optional.of("semantic register declaration");
        if (advancedTypeLocation.isEmpty() && cells.values().stream().anyMatch(c -> AssemblyTypeRequirements.semantic(c.type()) || AssemblyTypeRequirements.semantic(c.initial()))) advancedTypeLocation = Optional.of("semantic cell declaration or initial value");
        if (advancedTypeLocation.isEmpty() && constants.stream().anyMatch(AssemblyTypeRequirements::semantic)) advancedTypeLocation = Optional.of("semantic constant");
        if (advancedTypeLocation.isEmpty()) advancedTypeLocation=code.stream().filter(c -> c.instruction() instanceof FormatCheck || c.instruction() instanceof FormatProject).map(Code::location).findFirst();
        this.advancedTypeLocation = advancedTypeLocation;
        var families = EnumSet.copyOf(InstructionFamily.requirements(this.code));
        if (advancedTypeLocation.isPresent()) families.add(InstructionFamily.ISA_1B);
        if (structuredTypeLocation().isPresent()) families.add(InstructionFamily.ISA_1A);
        instructionFamilies = Collections.unmodifiableSet(families);
        this.inputs = Collections.unmodifiableMap(new TreeMap<>(inputs)); this.locals = Collections.unmodifiableMap(new TreeMap<>(locals));
        var definitions = new TreeMap<Integer, RegisterProcedure>();
        owners = new int[code.size() + 1]; Arrays.fill(owners, -1);
        for (var p : procedures) {
            if (p.end() >= code.size() || definitions.putIfAbsent(p.entry(), p) != null
                    || !(code.get(p.end()).instruction() instanceof Return)) throw new IllegalArgumentException("Invalid procedure range");
            for (int pc = p.entry(); pc <= p.end(); pc++) {
                if (owners[pc] != -1) throw new IllegalArgumentException("Overlapping procedure bodies"); owners[pc] = p.entry();
            }
            for (int r : p.inputs().values()) register(r);
            for (int r : p.locals().values()) register(r);
            for (int r : p.outputs().values()) register(r);
            if (!new HashSet<>(p.locals().values()).containsAll(p.inputs().values())) throw new IllegalArgumentException("Missing procedure input locals");
        }
        this.procedures = Collections.unmodifiableMap(definitions);
        taskRoot = null;
        this.endSource = endSource;
        if (endSource < 0) throw new IllegalArgumentException("Invalid end source");
        structured = equalityEnabled || serialEnabled || constructionEnabled || !cells.isEmpty() || !procedures.isEmpty() || types.stream().anyMatch(t -> !scalar(t)) || code.stream().anyMatch(c -> c.instruction() instanceof Field || c.instruction() instanceof Index || c.instruction() instanceof DynamicIndex || c.instruction() instanceof ListLength || c.instruction() instanceof RandomBelow || c.instruction() instanceof RandomRange || c.instruction() instanceof TextLength || c.instruction() instanceof TextLetter || c.instruction() instanceof TextNext || c.instruction() instanceof Sleep || c.instruction() instanceof RegisterDevices.Instruction || c.instruction() instanceof RegisterXBus.Instruction || c.instruction() instanceof RegisterMemoryOp);
        for (int register : inputs.values()) register(register);
        for (int register : locals.values()) register(register);
        if (!new HashSet<>(locals.values()).containsAll(inputs.values())) throw new IllegalArgumentException("Inputs missing from locals");
        long size = 0, pool = 0;
        for (int pc = 0; pc < code.size(); pc++) {
            var entry = code.get(pc); if (entry.source() >= endSource) throw new IllegalArgumentException("Source outside program");
            size = Math.addExact(size, entry.instruction().words());
            try { verifyInstruction(entry.instruction());
                if(entry.instruction() instanceof RegisterTaskGroup group)verifyStaging(pc,group); }
            catch (IllegalArgumentException | ComputationException invalid) { throw new IllegalArgumentException(entry.location()+": "+invalid.getMessage(),invalid); }
        }
        for (var value : constants) pool = Math.addExact(pool, value.storageUnits());
        var checks = new HashMap<Integer, ValueTypeTask.Schema>();
        for (var entry : code) if (entry.instruction() instanceof ValueCheck check)
            checks.computeIfAbsent(check.to(), index -> new ValueTypeTask.Schema(checkedType(index)));
        for (var entry : code) if (entry.instruction() instanceof RegisterNbt.Instruction n && n.decoding())
            checks.computeIfAbsent(n.to(), index -> new ValueTypeTask.Schema(RegisterNbt.decodedType(register(index))));
        typeChecks = Map.copyOf(checks);
        words = size; constantUnits = pool;
        try {
            // Compiler/ISA version is part of the identity; debug/source positions are not execution addresses.
            var digest = MessageDigest.getInstance("SHA-256");
            if (structured) RegisterDataIdentity.append(digest, this.code, this.types, this.constants, this.inputs, this.locals, endSource);
            else {
                String canonical = "register-isa-1\n" + this.code + '\n' + this.types + '\n' + this.constants
                    + '\n' + this.inputs + '\n' + this.locals + '\n' + endSource;
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            }
            if (!procedures.isEmpty()) RegisterDataIdentity.procedures(digest, this.procedures.values());
            if (!cells.isEmpty()) RegisterDataIdentity.memory(digest, this.cells);
            if(!softwareAnyRegisters.isEmpty())digest.update(("software-any-abi-1\n"+this.softwareAnyRegisters).getBytes(StandardCharsets.UTF_8));
            fingerprint = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        minimum = new long[code.size() + 1]; maximum = new long[code.size() + 1]; Arrays.fill(minimum, Long.MAX_VALUE);
        required = new ArrayList<>(); possible = new ArrayList<>();
        for (int i = 0; i <= code.size(); i++) {
            required.add(null); possible.add(new HashSet<>()); predecessors.add(new ArrayList<>());
            edges.add(i == code.size() || code.get(i).instruction() instanceof Return ? Set.of() : successors(i, code.get(i).instruction()));
        }
        for (int pc = 0; pc < code.size(); pc++) {
            if (code.get(pc).instruction() instanceof Return && owners[pc] < 0) throw new IllegalArgumentException("Return outside procedure");
            for (int next : edges.get(pc)) {
                if (owners[pc] != owners[next]) throw new IllegalArgumentException("Jump across register scopes");
                predecessors.get(next).add(pc);
            }
        }
        rejectRecursion();
        analyze();
        signals = new RegisterSignalModes(this.code, signalEdges());
        callWork = procedures.isEmpty() ? null : new RegisterCallWork(this.code, edges, owners, this.procedures);
    }
    /** A root view over the same immutable code/analysis, not another installed program or a fake CALL. */
    public RegisterProgram forTask(String name, long revision) {
        var root = procedures.values().stream().filter(p -> p.name().equals(name) && p.revision() == revision)
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing fixed task root"));
        if (code.stream().anyMatch(c -> c.instruction() instanceof RegisterDevices.Instruction d
                && d.kind() == RegisterDevices.Kind.SIO_WRITE) && taskUsesSignals(root))
            throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT, "tasks", "Simple I/O output task arbitration is not implemented yet");
        return new RegisterProgram(this, root);
    }
    private boolean taskUsesSignals(RegisterProcedure root) {
        var pending = new ArrayDeque<Integer>(); pending.add(root.entry());
        var visited = new HashSet<Integer>();
        while (!pending.isEmpty()) {
            int entry = pending.removeFirst(); if (!visited.add(entry)) continue;
            var procedure = procedures.get(entry);
            for (int pc = procedure.entry(); pc < procedure.end(); pc++) {
                var instruction = code.get(pc).instruction();
                if (instruction instanceof RegisterDevices.Instruction d && d.kind() != RegisterDevices.Kind.XBUS_STATUS) return true;
                if (instruction instanceof Call call) pending.addLast(call.target());
            }
        }
        return false;
    }
    private RegisterProgram(RegisterProgram image, RegisterProcedure root) {
        softwareAnyRegisters=image.softwareAnyRegisters;
        instructionFamilies = image.instructionFamilies;
        advancedTypeLocation = image.advancedTypeLocation;
        authoredTypes = image.authoredTypes;
        cells = image.cells;
        memoryEnabled = image.memoryEnabled;
        equalityEnabled = image.equalityEnabled;
        typeChecks = image.typeChecks;
        constructionEnabled = image.constructionEnabled; serialEnabled = image.serialEnabled;
        code = image.code; types = image.types; constants = image.constants; inputs = image.inputs; locals = image.locals;
        procedures = image.procedures; taskRoot = root; owners = image.owners; endSource = image.endSource;
        words = image.words; constantUnits = image.constantUnits; structured = image.structured;
        signals = image.signals; callWork = image.callWork; minimum = image.minimum; maximum = image.maximum;
        required = image.required; possible = image.possible; edges.addAll(image.edges); predecessors.addAll(image.predecessors);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(("register-task-root-1\n" + image.fingerprint + "\n" + root.entry()).getBytes(StandardCharsets.UTF_8));
            fingerprint = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void analyze() {
        var roots = new TreeMap<Integer, Collection<Integer>>(); roots.put(0, inputs.values());
        procedures.values().forEach(p -> roots.put(p.entry(), p.inputs().values()));
        var pending = new ArrayDeque<Integer>();
        roots.forEach((pc, values) -> { minimum[pc] = 0; required.set(pc, new HashSet<>(values)); possible.get(pc).addAll(values); pending.add(pc); });
        while (!pending.isEmpty()) {
            int pc = pending.removeFirst();
            for (int next : edges.get(pc)) if (minimum[pc] + work(code.get(pc).instruction()) < minimum[next]) {
                minimum[next] = minimum[pc] + work(code.get(pc).instruction());
                pending.addLast(next);
            }
        }
        // Back-edges require a fixed point, not one visit in source order. Entry's initial
        // register set remains a predecessor even when an instruction jumps back to PC 0.
        boolean[] queued = new boolean[code.size() + 1]; roots.keySet().forEach(pc -> { pending.add(pc); queued[pc] = true; });
        while (!pending.isEmpty()) {
            int pc = pending.removeFirst(); queued[pc] = false;
            if (pc == code.size()) continue;
            var op = code.get(pc).instruction();
            var definite = new HashSet<>(required.get(pc)); var maybe = new HashSet<>(possible.get(pc));
            var targets = writes(op); definite.addAll(targets); maybe.addAll(targets);
            if (op instanceof Drop d) { definite.remove(d.register()); maybe.remove(d.register()); }
            for (int next : edges.get(pc)) {
                boolean changed;
                if (required.get(next) == null) { required.set(next, new HashSet<>(definite)); changed = true; }
                else changed = required.get(next).retainAll(definite);
                changed |= possible.get(next).addAll(maybe);
                if (changed && !queued[next]) { pending.addLast(next); queued[next] = true; }
            }
        }
        for (int pc = 0; pc < code.size(); pc++)
            if (minimum[pc] != Long.MAX_VALUE && !required.get(pc).containsAll(readsAt(pc)))
                throw new IllegalArgumentException(code.get(pc).location()+": Read before register initialization");
        // Acyclic prefixes keep their exact upper bound; cycles and their successors
        // have no static finite bound. Never infer a loop's run count from source order.
        int[] incoming = new int[code.size() + 1];
        for (int pc = 0; pc <= code.size(); pc++) if (minimum[pc] != Long.MAX_VALUE) {
            for (int previous : predecessors.get(pc)) if (minimum[previous] != Long.MAX_VALUE) incoming[pc]++;
            if (incoming[pc] == 0) pending.addLast(pc);
        }
        while (!pending.isEmpty()) {
            int pc = pending.removeFirst();
            if (pc < code.size() && retriesRandom(code.get(pc).instruction())) maximum[pc] = Long.MAX_VALUE;
            for (int next : edges.get(pc)) {
                long bound = maximum[pc] == Long.MAX_VALUE || variableWork(code.get(pc).instruction()) ? Long.MAX_VALUE : Math.addExact(maximum[pc], work(code.get(pc).instruction()));
                maximum[next] = Math.max(maximum[next], bound);
                if (--incoming[next] == 0) pending.addLast(next);
            }
        }
        for (int pc = 0; pc <= code.size(); pc++) if (incoming[pc] > 0) maximum[pc] = Long.MAX_VALUE;
    }
    public static boolean scalar(DataType type) { return type == DataType.Scalar.INTEGER || type == DataType.Scalar.DECIMAL || type == DataType.Scalar.BOOLEAN; }
    /** These ANY-input ALU operations inspect/select a tag without visiting the payload. */
    public static boolean tagPredicate(ScalarOperation operation) { return operation == ScalarOperation.IS_NULL || operation == ScalarOperation.IS_ERROR || operation == ScalarOperation.REQUIRE_VALUE; }
    private DataType register(int index) {
        if (index < 0 || index >= types.size()) throw new IllegalArgumentException("Register outside installation"); return types.get(index);
    }
    private void verifyInstruction(Instruction op) {
        switch (op) {
            case RegisterNbt.Instruction n -> n.verify(this::register);
            case RegisterListEdit edit -> edit.verify(this::register);
            case AppendList a -> {
                if (!(register(a.to()) instanceof DataType.ListType t) || !(register(a.from()) instanceof DataType.ListType source)
                    || !RegisterAssignment.permits(t.element(), source.element()) || !RegisterAssignment.permits(t.element(), register(a.element())))
                    throw new IllegalArgumentException("LIST.APPEND list/element type");
            }
            case MakeList l -> {
                if (!(register(l.to()) instanceof DataType.ListType t)) throw new IllegalArgumentException("LIST.MAKE destination must be list");
                for (int r:l.from())if (!RegisterAssignment.permits(t.element(), register(r))) throw new IllegalArgumentException("LIST.MAKE element type");
            }
            case MakeRecord r -> {
                if (!(register(r.to()) instanceof DataType.RecordType t) || !t.fields().keySet().equals(new HashSet<>(r.shape().keys()))) throw new IllegalArgumentException("RECORD.MAKE destination shape");
                for (var f:r.fields())if (!RegisterAssignment.permits(t.fields().get(f.key()), register(f.from()))) throw new IllegalArgumentException("RECORD.MAKE field type: " + f.key());
            }
            case Constant c -> { if (c.pool() < 0 || c.pool() >= constants.size() || !register(c.to()).accepts(constants.get(c.pool()))) throw new IllegalArgumentException("Invalid constant load"); }
            case DefaultNumber m -> { var type = register(m.to()); if (type != register(m.from()) || type != DataType.Scalar.INTEGER && type != DataType.Scalar.DECIMAL) throw new IllegalArgumentException("Numeric default requires matching numeric types"); }
            case Move m -> { if (!RegisterAssignment.permits(register(m.to()), register(m.from()))) throw new IllegalArgumentException("Move requires a metered dynamic type check"); }
            case Alu a -> {
                if (a.operation().numericEdge()) throw new IllegalArgumentException("Numeric edge is a source macro; lower it into comparisons first");
                if (a.operation() != ScalarOperation.REQUIRE_VALUE && !scalar(register(a.to())) || !tagPredicate(a.operation()) && a.from().stream().anyMatch(r -> !scalar(register(r)))) throw new IllegalArgumentException("Variable-size ALU requires metered instructions");
                if (a.from().size() != a.operation().inputs().size()) throw new IllegalArgumentException("ALU arity");
                var arguments = new LinkedHashMap<String, DataType>(); int i = 0;
                for (String name : a.operation().inputs().keySet()) arguments.put(name, register(a.from().get(i++)));
                if (!register(a.to()).equals(a.operation().outputType(arguments, "assembly"))) throw new IllegalArgumentException("ALU result type");
            }
            case Nonnegative n -> { if (register(n.value()) != DataType.Scalar.INTEGER) throw new IllegalArgumentException("Nonnegative guard type"); }
            case Sleep s -> { if (register(s.ticks()) != DataType.Scalar.INTEGER) throw new IllegalArgumentException("Sleep duration must be integer"); }
            case Call c -> { if (!procedures.containsKey(c.target())) throw new IllegalArgumentException("Unknown procedure entry"); }
            case TryLaunch launch -> {
                if (register(launch.started()) != DataType.Scalar.BOOLEAN || procedures.values().stream().noneMatch(p ->
                    p.name().equals(launch.procedure().id()) && p.revision() == launch.procedure().revision()
                        && p.inputs().isEmpty() && p.outputs().isEmpty()))
                    throw new IllegalArgumentException("Invalid resultless try-launch signature");
            }
            case TaskStart launch -> {
                var target = procedures.values().stream().filter(p -> p.name().equals(launch.procedure().id()) && p.revision() == launch.procedure().revision())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown task procedure"));
                if (!register(launch.handle()).equals(com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE)
                    || !launch.arguments().keySet().equals(target.inputs().keySet())) throw new IllegalArgumentException("Invalid task start signature");
                launch.arguments().forEach((name, from) -> {
                    if (!RegisterAssignment.permits(register(target.inputs().get(name)), register(from))) throw new IllegalArgumentException("Task argument type mismatch");
                });
            }
            case TaskWait wait -> verifyTaskJoin(wait.procedure(), wait.handle(), -1);
            case TaskClaim claim -> verifyTaskJoin(claim.procedure(), claim.handle(), claim.outcome());
            case TaskStop ignored -> { }
            case RegisterMonitor monitor -> {
                // A private target is validated by the Flow compiler at its lexical source position.
                if (!monitor.privateLocal() && !cells.containsKey(monitor.cell()))
                    throw new IllegalArgumentException("Unknown monitor cell: " + monitor.cell());
            }
            case RegisterTaskGroup group -> {
                for(var reg:group.arguments())if(register(reg)!=DataType.Scalar.INTEGER)throw new IllegalArgumentException("Expected task target index");

            }
            case RegisterEvent event -> {
                for (var key : event.receivers())
                    if (procedures.values().stream().noneMatch(p -> p.name().equals(key.id()) && p.revision() == key.revision()
                            && p.inputs().isEmpty() && p.outputs().isEmpty()))
                        throw new IllegalArgumentException("Invalid event receiver signature: " + key);
            }
            case Return ignored -> { }
            case Drop d -> register(d.register());
            case JumpFalse j -> { if (register(j.condition()) != DataType.Scalar.BOOLEAN) throw new IllegalArgumentException("Branch type"); jump(j.target()); }
            case Jump j -> jump(j.target());
            case Field f -> {
                var source = register(f.from());
                DataType output = source instanceof DataType.RecordType r ? r.fields().get(f.key()) : source instanceof DataType.MapType m ? m.element() : source == DataType.Scalar.ANY ? source : null;
                if (output == null || !RegisterAssignment.permits(register(f.to()),output)) throw new IllegalArgumentException("Field type mismatch");
            }
            case ListLength l -> {
                var source = register(l.from());
                if (register(l.to()) != DataType.Scalar.INTEGER || !(source instanceof DataType.ListType) && source != DataType.Scalar.ANY) throw new IllegalArgumentException("List length type mismatch");
            }
            case DynamicIndex i -> {
                var source = register(i.from());
                DataType output = source instanceof DataType.ListType l ? l.element() : source == DataType.Scalar.ANY ? source : null;
                if (register(i.indexRegister()) != DataType.Scalar.INTEGER || output == null || !register(i.to()).equals(output)) throw new IllegalArgumentException("Dynamic list index type mismatch");
            }
            case Index i -> {
                var source = register(i.from());
                DataType output = source instanceof DataType.ListType l ? l.element() : source == DataType.Scalar.ANY ? source : null;
                if (i.index() < 0 || output == null || !register(i.to()).equals(output)) throw new IllegalArgumentException("Index type mismatch");
            }
            case HasField h -> { if (register(h.to()) != DataType.Scalar.BOOLEAN || !DataType.Scalar.TEXT.compatibleWith(register(h.key())) || !(register(h.from()) instanceof DataType.RecordType) && !(register(h.from()) instanceof DataType.MapType) && register(h.from()) != DataType.Scalar.ANY) throw new IllegalArgumentException("Presence operand types"); }
            case ValueEquals e -> { register(e.left()); register(e.right()); if (register(e.to()) != DataType.Scalar.BOOLEAN) throw new IllegalArgumentException("Comparison result must be boolean"); }
            case FormatCheck c -> { register(c.from()); if(checkedType(c.to())!=DataType.Semantic.recipe(c.fmt(),c.version()))throw new IllegalArgumentException("FMT.CHECK requires a checked result of the declared format version"); }
            case FormatProject p -> {
                var source=register(p.from());
                if(register(p.to())!=DataType.Scalar.ANY || !(source==DataType.Scalar.ANY || source==DataType.Semantic.recipe(p.fmt(),p.version()) || source instanceof DataType.OptionalType optional && optional.value()==DataType.Semantic.recipe(p.fmt(),p.version())))
                    throw new IllegalArgumentException("FMT.PROJECT requires ANY destination and a compatible recipe version or ANY source");
            }
            case ValueCheck c -> { register(c.from()); checkedType(c.to()); }
            case RandomRange range -> {
                if (register(range.to()) != range.type() || register(range.from()) != range.type() || register(range.through()) != range.type())
                    throw new IllegalArgumentException("Random range requires matching numeric registers");
            }
            case RandomBelow random -> {
                if (register(random.to()) != DataType.Scalar.INTEGER || register(random.bound()) != DataType.Scalar.INTEGER)
                    throw new IllegalArgumentException("RAND.BELOW requires integer registers");
            }
            case Correlation c -> { if (register(c.to()) != DataType.Scalar.TEXT) throw new IllegalArgumentException("Correlation destination must be text"); }
            case TextNext t -> {
                if (!register(t.to()).equals(TextNext.TYPE) || register(t.from()) != DataType.Scalar.TEXT
                    || register(t.offset()) != DataType.Scalar.INTEGER) throw new IllegalArgumentException("TEXT.NEXT type mismatch");
            }
            case TextLetter t -> {
                if (register(t.to()) != DataType.Scalar.TEXT || register(t.from()) != DataType.Scalar.TEXT
                        || register(t.index()) != DataType.Scalar.INTEGER) throw new IllegalArgumentException("TEXT.LETTER type mismatch");
            }
            case TextContains t -> {
                if (register(t.to()) != DataType.Scalar.BOOLEAN || register(t.text()) != DataType.Scalar.TEXT
                        || register(t.needle()) != DataType.Scalar.TEXT) throw new IllegalArgumentException("TEXT.CONTAINS.CI type mismatch");
            }
            case TextConcat t -> {
                if (register(t.to()) != DataType.Scalar.TEXT || !DataType.Scalar.TEXT.compatibleWith(register(t.left()))
                    || !DataType.Scalar.TEXT.compatibleWith(register(t.right()))) throw new IllegalArgumentException("TEXT.CONCAT type mismatch");
            }
            case TextLength t -> {
                if (register(t.to()) != DataType.Scalar.INTEGER || !DataType.Scalar.TEXT.compatibleWith(register(t.from()))) throw new IllegalArgumentException("Text length type mismatch");
            }
            case RegisterDevices.Instruction d -> {
                if (register(d.register()) != DataType.Scalar.INTEGER) throw new IllegalArgumentException("Device register must be integer");
            }
            case RegisterSerial.Instruction d -> {
                if (!register(d.output()).equals(d.kind()==RegisterSerial.Kind.SEND ? com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.SEND : d.kind()==RegisterSerial.Kind.RECEIVE ? com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.RECEIVE : com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.STATUS)) throw new IllegalArgumentException("Serial output schema");
                if (d.input()>=0 && !(register(d.input()) instanceof DataType.RecordType) && register(d.input())!=DataType.Scalar.NBT && register(d.input())!=DataType.Scalar.ANY) throw new IllegalArgumentException("Serial input schema");
            }
            case RegisterXBus.Instruction d -> {
                if (d.input() >= 0 && !(register(d.input()) instanceof DataType.RecordType)
                    || d.output() >= 0 && !(register(d.output()) instanceof DataType.RecordType) && !(d.kind()==RegisterXBus.Kind.READ && register(d.output())==DataType.Scalar.ANY))
                    throw new IllegalArgumentException("XBus register must be a typed record");
            }
            case RegisterTimer timer -> {
                if (!timer.resets() && register(timer.destination()) != DataType.Scalar.DECIMAL)
                    throw new IllegalArgumentException("Timer requires a decimal destination");
            }
            case RegisterPrompt prompt -> {
                if (register(prompt.register()) != DataType.Scalar.TEXT)
                    throw new IllegalArgumentException("Terminal prompt requires text");
            }
            case RegisterKeyDown key -> {
                if(register(key.destination()) != DataType.Scalar.BOOLEAN) throw new IllegalArgumentException("Terminal key read requires a boolean destination");
            }
            case RegisterReference r -> r.validate(types,cells);
            case RegisterMemoryOp m -> {
                if (m.cell() >= cells.size()) throw new IllegalArgumentException("Memory index outside installation");
                var cell = m.cell() < 0 ? null : new ArrayList<>(cells.values()).get(m.cell());
                if ((m.kind() == RegisterMemoryOp.Kind.READ || m.kind() == RegisterMemoryOp.Kind.PUBLIC_LOAD) && !register(m.register()).equals(cell.type())
                    || (m.kind() == RegisterMemoryOp.Kind.WRITE || m.kind() == RegisterMemoryOp.Kind.PUBLIC_STORE) && !RegisterAssignment.permits(cell.type(), register(m.register()))
                    || m.kind() == RegisterMemoryOp.Kind.PUBLIC_ADD && (cell.type() != DataType.Scalar.INTEGER && cell.type() != DataType.Scalar.DECIMAL || register(m.register()) != cell.type())
                    || m.kind() == RegisterMemoryOp.Kind.COMMIT && !register(m.register()).equals(RegisterMemory.RESULT_TYPE))
                    throw new IllegalArgumentException("Memory operand type mismatch");
            }
        }
    }
    private void verifyTaskJoin(ModuleDefinition.Key key, int handle, int outcome) {
        var target = procedures.values().stream().filter(p -> p.name().equals(key.id()) && p.revision() == key.revision())
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown task procedure"));
        if (!register(handle).equals(com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE))
            throw new IllegalArgumentException("Task join handle type");
        if (outcome >= 0) {
            var outputs = new TreeMap<String, DataType>(); target.outputs().forEach((name, r) -> outputs.put(name, register(r)));
            if (!register(outcome).equals(com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.type(outputs)))
                throw new IllegalArgumentException("Task join outcome type");
        }
    }
    private DataType checkedType(int destination) {
        if (!(register(destination) instanceof DataType.RecordType result)
                || !result.fields().keySet().equals(Set.of("ok", "value"))
                || result.fields().get("ok") != DataType.Scalar.BOOLEAN
                || !(result.fields().get("value") instanceof DataType.OptionalType value))
            throw new IllegalArgumentException("VALUE.CHECK requires {ok:BOOLEAN,value:Optional<T>}");
        return value.value();
    }
    private void jump(int target) { if (target < 0 || target > code.size()) throw new IllegalArgumentException("Jump outside installation"); }
    private static int written(Instruction op) {
        return switch (op) {
            case RegisterTimer timer -> timer.destination();
            case RegisterPrompt prompt -> prompt.asks() ? -1 : prompt.register();
            case RegisterKeyDown key -> key.destination();
            case RegisterReference r -> r.output();
            case RegisterNbt.Instruction n -> n.to();
            case AppendList a -> a.to();
            case RegisterListEdit edit -> edit.to();
            case MakeList l -> l.to();
            case MakeRecord r -> r.to();
            case TryLaunch t -> t.started();
            case TaskStart t -> t.handle();
            case TaskClaim t -> t.outcome();
            case HasField h -> h.to();
            case ValueEquals e -> e.to();
            case FormatCheck c -> c.to();
            case FormatProject p -> p.to();
            case ValueCheck c -> c.to();
            case RandomRange r -> r.to();
            case RandomBelow r -> r.to();
            case Correlation c -> c.to();
            case Constant c -> c.to();
            case DefaultNumber m -> m.to();
            case Move m -> m.to();
            case Alu a -> a.to();
            case Field f -> f.to();
            case Index i -> i.to();
            case DynamicIndex i -> i.to();
            case ListLength l -> l.to();
            case TextNext t -> t.to();
            case TextLetter t -> t.to();
            case TextContains t -> t.to();
            case TextConcat t -> t.to();
            case TextLength t -> t.to();
            case RegisterDevices.Instruction d -> d.kind().writesRegister() ? d.register() : -1;
            case RegisterSerial.Instruction d -> d.output();
            case RegisterXBus.Instruction d -> d.output();
            case RegisterMemoryOp m -> m.output();
            default -> -1;
        };
    }
    private Collection<Integer> writes(Instruction op) {
        if (op instanceof Call c) return procedures.get(c.target()).outputs().values();
        int target = written(op); return target < 0 ? List.of() : List.of(target);
    }
    private Collection<Integer> readsAt(int pc) {
        var op = code.get(pc).instruction();
        if (op instanceof Call c) return procedures.get(c.target()).inputs().values();
        if (op instanceof Return) return procedures.get(owners[pc]).outputs().values();
        return reads(op);
    }
    private void rejectRecursion() {
        var pending = new HashSet<>(procedures.keySet());
        while (!pending.isEmpty()) {
            var leaves = new HashSet<Integer>();
            for (int owner : pending) {
                var p = procedures.get(owner); boolean child = false;
                for (int pc = p.entry(); pc <= p.end(); pc++)
                    if (code.get(pc).instruction() instanceof Call c && pending.contains(c.target())) { child = true; break; }
                if (!child) leaves.add(owner);
            }
            if (leaves.isEmpty()) throw new IllegalArgumentException("Recursive assembly procedures are not supported");
            pending.removeAll(leaves);
        }
    }
    private List<Set<Integer>> signalEdges() {
        var result = new ArrayList<Set<Integer>>(); edges.forEach(e -> result.add(new HashSet<>(e)));
        for (int pc = 0; pc < code.size(); pc++) if (code.get(pc).instruction() instanceof Call c) {
            result.set(pc, Set.of(c.target())); result.get(procedures.get(c.target()).end()).add(pc + 1);
        }
        return result;
    }
    private static List<Integer> reads(Instruction op) {
        return switch (op) {
            case RegisterPrompt prompt -> prompt.asks() ? List.of(prompt.register()) : List.of();
            case RegisterReference r -> r.reads();
            case RegisterNbt.Instruction n -> n.reads();
            case HasField h -> List.of(h.from(), h.key());
            case ValueEquals e -> List.of(e.left(), e.right());
            case FormatCheck c -> List.of(c.from());
            case FormatProject p -> List.of(p.from());
            case ValueCheck c -> List.of(c.from());
            case AppendList a -> List.of(a.from(),a.element());
            case RegisterListEdit edit -> edit.operands();
            case MakeList l -> l.from();
            case MakeRecord r -> r.from();
            case TaskStart t -> List.copyOf(t.arguments().values());
            case TaskWait t -> List.of(t.handle());
            case TaskClaim t -> List.of(t.handle());
            case DefaultNumber m -> List.of(m.from());
            case Move m -> List.of(m.from());
            case Alu a -> a.from();
            case Nonnegative n -> List.of(n.value());
            case Sleep s -> List.of(s.ticks());
            case JumpFalse j -> List.of(j.condition());
            case Field f -> List.of(f.from());
            case Index i -> List.of(i.from());
            case DynamicIndex i -> List.of(i.from(), i.indexRegister());
            case ListLength l -> List.of(l.from());
            case TextNext t -> List.of(t.from(), t.offset());
            case TextLetter t -> List.of(t.from(), t.index());
            case TextContains t -> List.of(t.text(), t.needle());
            case TextConcat t -> List.of(t.left(), t.right());
            case RandomRange r -> List.of(r.from(), r.through());
            case RandomBelow r -> List.of(r.bound());
            case TextLength t -> List.of(t.from());
            case RegisterDevices.Instruction d -> d.kind().writesRegister() ? List.of() : List.of(d.register());
            case RegisterSerial.Instruction d -> d.input() < 0 ? List.of() : List.of(d.input());
            case RegisterXBus.Instruction d -> d.input() < 0 ? List.of() : List.of(d.input());
            case RegisterMemoryOp m -> m.input() < 0 ? List.of() : List.of(m.input());
            default -> List.of();
        };
    }
    private static Set<Integer> successors(int pc, Instruction op) { return op instanceof Jump j ? Set.of(j.target()) : op instanceof JumpFalse j ? new HashSet<>(List.of(pc + 1, j.target())) : op instanceof TaskStop stop && stop.all() ? Set.of() : Set.of(pc + 1); }
    public List<DataValue> constants() { return constants; }
    public Set<Integer> softwareAnyRegisters() {return softwareAnyRegisters;}
    public Map<Integer, RegisterProcedure> procedures() { return procedures; }
    public List<Code> code() { return code; }
    /** Includes fixed procedure bodies and task roots, not merely the currently executing branch. */
    public Set<InstructionFamily> instructionFamilies() { return instructionFamilies; }
    public Optional<String> advancedTypeLocation() { return advancedTypeLocation; }
    public List<DataType> registerTypes() { return types; }
    public long codeWords() { return words; }
    public long constantUnits() { return constantUnits; }
    public int source(int pc) { return pc == code.size() ? endSource : code.get(pc).source(); }
    public String location(int pc) { return pc == code.size() ? "assembly/complete" : code.get(pc).location(); }
    public boolean complete(RegisterState state) { return state.pc() == code.size(); }
    public OptionalLong sleepTicks(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof Sleep s
            ? OptionalLong.of(((DataValue.IntegerValue)state.registers().get(s.ticks())).value()) : OptionalLong.empty();
    }
    public Optional<TaskWait> taskWait(RegisterState state) { return !complete(state) && code.get(state.pc()).instruction() instanceof TaskWait t ? Optional.of(t) : Optional.empty(); }
    public Optional<TaskStop> taskStop(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof TaskStop stop ? Optional.of(stop) : Optional.empty();
    }
    public RegisterState completeStopOthers(RegisterState state) {
        if (taskStop(state).orElseThrow().all()) throw new IllegalStateException("Stop all does not have a successor");
        return state(fingerprint, state.pc() + 1, state.registers(), structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<TaskClaim> taskClaim(RegisterState state) { return !complete(state) && code.get(state.pc()).instruction() instanceof TaskClaim t ? Optional.of(t) : Optional.empty(); }
    public DataValue taskHandle(RegisterState state) {
        int handle = taskWait(state).map(TaskWait::handle).orElseGet(() -> taskClaim(state).orElseThrow().handle());
        return state.registers().get(handle);
    }
    public RegisterState completeTaskWait(RegisterState state) {
        var wait = taskWait(state).orElseThrow();
        if (state.pc() + 1 >= code.size() || !(code.get(state.pc() + 1).instruction() instanceof TaskClaim c)
            || !c.procedure().equals(wait.procedure()) || c.handle() != wait.handle()) throw new IllegalArgumentException("Wait has no matching claim");
        return state(fingerprint, state.pc() + 1, state.registers(), structured, Optional.empty(), state.calls(), state.transaction());
    }
    public RegisterState completeTaskClaim(RegisterState state, DataValue.RecordValue outcome) {
        var claim = taskClaim(state).orElseThrow(); var registers = new TreeMap<>(state.registers()); registers.put(claim.outcome(), outcome);
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<TryLaunch> tryLaunch(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof TryLaunch launch ? Optional.of(launch) : Optional.empty();
    }
    public RegisterState completeTryLaunch(RegisterState state, boolean started) {
        var registers = new TreeMap<>(state.registers());
        registers.put(tryLaunch(state).orElseThrow().started(), new DataValue.BooleanValue(started));
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<TaskStart> taskStart(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof TaskStart t ? Optional.of(t) : Optional.empty();
    }
    public Optional<RegisterMonitor> monitor(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterMonitor monitor ? Optional.of(monitor) : Optional.empty();
    }
    public RegisterState completeMonitor(RegisterState state) {
        monitor(state).orElseThrow();
        return state(fingerprint, state.pc() + 1, state.registers(), structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<RegisterTimer> timer(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterTimer t ? Optional.of(t) : Optional.empty();
    }
    public Optional<RegisterPrompt> prompt(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterPrompt op ? Optional.of(op) : Optional.empty();
    }
    public DataValue.TextValue question(RegisterState state) {
        var op = prompt(state).filter(RegisterPrompt::asks).orElseThrow();
        return (DataValue.TextValue) state.registers().get(op.register());
    }
    public RegisterState completeTimer(RegisterState state, long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Negative timer ticks");
        var operation = timer(state).orElseThrow();
        var registers = new TreeMap<>(state.registers());
        if (!operation.resets())
            registers.put(operation.destination(), new DataValue.DecimalValue(ticks / (double) RegisterTimer.TICKS_PER_SECOND));
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public RegisterState completePrompt(RegisterState state, DataValue.TextValue answer) {
        var operation = prompt(state).orElseThrow();
        var registers = new TreeMap<>(state.registers());
        if (!operation.asks()) registers.put(operation.register(), answer);
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<RegisterKeyDown> keyDown(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterKeyDown key ? Optional.of(key) : Optional.empty();
    }
    public RegisterState completeKeyDown(RegisterState state, boolean held) {
        var op = keyDown(state).orElseThrow();
        var registers = new TreeMap<>(state.registers());
        registers.put(op.destination(), new DataValue.BooleanValue(held));
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    private void verifyStaging(int pc,RegisterTaskGroup group){
        int start=pc-group.arguments().size();
        var distinct=new HashSet<Integer>();
        if(start<0)throw new IllegalArgumentException("Missing linked task addresses");
        for(int i=0;i<group.arguments().size();i++){
            if(!(code.get(start+i).instruction() instanceof Constant c) || c.to()!=group.arguments().get(i)
                    || !distinct.add(c.to()) || !(constants.get(c.pool()) instanceof DataValue.IntegerValue n))
                throw new IllegalArgumentException("Invalid staged task address");
            var proc=n.value()<0 || n.value()>Integer.MAX_VALUE ? null : procedures.get((int)n.value());
            if(proc==null || !proc.inputs().isEmpty() || !proc.outputs().isEmpty())
                throw new IllegalArgumentException("Task group target must be a parameterless procedure");
        }
    }
    /** Scheduler probes and suspended waits must not rebuild the target list. */
    public Optional<Boolean> eventMode(RegisterState state){
        if(complete(state))return Optional.empty();
        var op=code.get(state.pc()).instruction();
        if(op instanceof RegisterEvent e)return Optional.of(e.await());
        return op instanceof RegisterTaskGroup g?Optional.of(g.await()):Optional.empty();
    }
    public Optional<RegisterTaskGroup.Dispatch> event(RegisterState state) {
        if(complete(state))return Optional.empty();
        var op=code.get(state.pc()).instruction();
        if(op instanceof RegisterTaskGroup group){
            var entries=new ArrayList<ModuleDefinition.Key>();
            int at=state.pc()-group.arguments().size();
            for(int r:group.arguments()){
                var load=(Constant)code.get(at++).instruction();
                var value=state.registers().get(r);
                if(!constants.get(load.pool()).equals(value))return Optional.of(new RegisterTaskGroup.Dispatch(group.await(),List.of(),false));
                var proc=procedures.get((int)((DataValue.IntegerValue)value).value());
                entries.add(new ModuleDefinition.Key(proc.name(),proc.revision()));
            }
            return Optional.of(new RegisterTaskGroup.Dispatch(group.await(),List.copyOf(entries),true));
        }
        return op instanceof RegisterEvent e ? Optional.of(new RegisterTaskGroup.Dispatch(e.await(),e.receivers(),true)) : Optional.empty();
    }
    public RegisterState completeEvent(RegisterState state) {
        eventMode(state).orElseThrow();
        return state(fingerprint, state.pc() + 1, state.registers(), structured, Optional.empty(), state.calls(), state.transaction());
    }
    public DataValue.RecordValue taskArguments(RegisterState state) {
        var values = new TreeMap<String, DataValue>();
        taskStart(state).orElseThrow().arguments().forEach((name, from) -> values.put(name, state.registers().get(from)));
        return new DataValue.RecordValue(values);
    }
    public RegisterState completeTaskStart(RegisterState state, com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle handle) {
        var registers = new TreeMap<>(state.registers()); registers.put(taskStart(state).orElseThrow().handle(), handle.value());
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<RegisterMemoryOp> memoryOp(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterMemoryOp m ? Optional.of(m) : Optional.empty();
    }
    public record MemoryExecution(RegisterState registers, RegisterMemory memory) {}
    public Optional<RegisterReference> referenceOp(RegisterState state){return !complete(state)&&code.get(state.pc()).instruction() instanceof RegisterReference r?Optional.of(r):Optional.empty();}
    public MemoryExecution stepReference(RegisterState state,RegisterMemory memory,UUID computer,long generation,UUID context){
        var op=referenceOp(state).orElseThrow();
        if(!fingerprint.equals(state.image()))throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,"reference","Reference continuation differs from its image");
        if((op.kind()==RegisterReference.Kind.ADDRESS||op.kind()==RegisterReference.Kind.READ||op.kind()==RegisterReference.Kind.WRITE)&&state.transaction().orElse(true))throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,"reference","Public reference access during transaction");
        var registers=new TreeMap<>(state.registers());
        var next=op.execute(registers,memory,computer,generation,fingerprint,context);
        return new MemoryExecution(state(fingerprint,state.pc()+1,registers,structured,Optional.empty(),state.calls(),state.transaction()),next);
    }
    /** Pure register/bank candidate, to publish together only after the owner's resource preflight.
     * Like step(), this consumes an already validated live continuation and its installed bank;
     * hosts must validate untrusted/restored state before execution, not per payload assignment. */
    public MemoryExecution stepMemory(RegisterState state, RegisterMemory memory, UUID task) {
        var op = memoryOp(state).orElseThrow(); var registers = new TreeMap<>(state.registers());
        if (op.publicAccess() && !fingerprint.equals(state.image()))
            throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "memory", "Public access continuation differs from its image");
        if (op.publicAccess() && state.transaction().orElse(true))
            throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT, "memory", "Public access is not allowed during this task's transaction");
        var next = switch (op.kind()) {
            case BEGIN -> memory.begin(task);
            case READ -> { registers.put(op.register(), memory.read(task, op.cell())); yield memory.readMark(task, op.cell()); }
            case WRITE -> memory.write(task, op.cell(), registers.get(op.register()));
            case PREPARE -> memory.prepare(task);
            case PREPARE_CELL -> memory.prepareCell(task, op.cell());
            case COMMIT -> { var result = memory.commit(task); registers.put(op.register(), result.result()); yield result.memory(); }
            case ROLLBACK -> memory.rollback(task);
            case PUBLIC_ADD -> memory.publicAdd(task, op.cell(), registers.get(op.register()));
            case PUBLIC_LOAD -> { registers.put(op.register(), memory.publicLoad(task, op.cell())); yield memory; }
            case PUBLIC_STORE -> memory.publicStoreValidated(task, op.cell(), registers.get(op.register()));
        };
        return new MemoryExecution(state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), Optional.of(switch (op.kind()) {
            case BEGIN -> true;
            case COMMIT, ROLLBACK -> false;
            default -> state.transaction().orElseThrow();
        })), next);
    }
    /** Pure wake candidate. The host retains the admitted duration and commits on its active tick. */
    public RegisterState completeSleep(RegisterState state) {
        if (sleepTicks(state).isEmpty()) throw new IllegalStateException("Not at a Sleep instruction");
        return state(fingerprint, state.pc() + 1, state.registers(), structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<RegisterXBus.Instruction> xbus(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterXBus.Instruction d ? Optional.of(d) : Optional.empty();
    }
    /** Pure candidate only. The host must have an admitted wait and preflight the full owner save,
     * logical RAM and receipt before publishing this PC/result together with its peer. */
    public RegisterState completeXBus(RegisterState state, Optional<DataValue> result) {
        var device = xbus(state).orElseThrow(() -> new IllegalStateException("Not at an XBus instruction"));
        if ((device.output() >= 0) != result.isPresent() || result.isPresent() && !register(device.output()).accepts(result.orElseThrow()))
            throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "XBus result differs from register signature");
        var registers = new TreeMap<>(state.registers()); result.ifPresent(value -> registers.put(device.output(), value));
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public Optional<RegisterSerial.Instruction> serial(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof RegisterSerial.Instruction d ? Optional.of(d):Optional.empty();
    }
    public RegisterState advanceSerial(RegisterState state,com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits limits,DataValue.RecordValue configuration) {
        var op=serial(state).orElseThrow(); if(op.kind()!=RegisterSerial.Kind.SEND)throw new IllegalStateException("Not send");
        var next=state.serial().isEmpty()?RegisterSerial.Progress.start(state,op,limits,configuration):state.serial().orElseThrow().advance(state);
        return new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),constructionEnabled,Optional.empty(),true,Optional.of(next),equalityEnabled,Optional.empty());
    }
    public RegisterState completeSerial(RegisterState state,DataValue.RecordValue result) {
        var op=serial(state).orElseThrow(); if(!register(op.output()).accepts(result))throw new IllegalArgumentException("Serial result schema");
        var registers=new TreeMap<>(state.registers());registers.put(op.output(),result);
        return state(fingerprint,state.pc()+1,registers,structured,Optional.empty(),state.calls(),state.transaction());
    }
    public RegisterState advanceReceive(RegisterState state,com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice.Receive head,UUID flow,long origin){
        var op=serial(state).orElseThrow();if(op.kind()!=RegisterSerial.Kind.RECEIVE)throw new IllegalStateException("Not receive");
        var next=state.serial().isEmpty()?RegisterSerial.Progress.startReceive(state,head,flow,op.nativeNbt(),origin):state.serial().orElseThrow().advance(state);
        return new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),constructionEnabled,Optional.empty(),true,Optional.of(next),equalityEnabled,Optional.empty());
    }
    private RegisterState state(String image, int pc, Map<Integer, DataValue> registers, boolean structured, Optional<RegisterState.Scan> scan, Optional<RegisterCalls> calls, Optional<Boolean> transaction) {
        return new RegisterState(image, pc, registers, structured, scan, calls, transaction, constructionEnabled, Optional.empty(), serialEnabled, Optional.empty(), equalityEnabled, Optional.empty());
    }
    public Optional<Correlation> correlation(RegisterState state) {
        return !complete(state) && code.get(state.pc()).instruction() instanceof Correlation c ? Optional.of(c) : Optional.empty();
    }
    public boolean drawsRandom(RegisterState state) {
        if (complete(state)) return false;
        var op = code.get(state.pc()).instruction();
        return op instanceof RandomBelow || op instanceof RandomRange;
    }
    /** Pure function of persisted task identity and paid work, never world time or global RNG. */
    public RegisterState tryRandom(RegisterState state, UUID task, long work) {
        long draw = randomMix((randomMix(task.getMostSignificantBits()) ^ task.getLeastSignificantBits())
            + work * 0x9e3779b97f4a7c15L);
        int destination;
        DataValue result;
        switch (code.get(state.pc()).instruction()) {
            case RandomBelow op -> {
                long bound = ((DataValue.IntegerValue)state.registers().get(op.bound())).value();
                if (bound <= 0) throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT, location(state.pc()),
                    "RAND.BELOW requires a positive bound (random position is unavailable on an empty list)");
                if (rejectDraw(draw, bound)) return state;
                destination = op.to();
                result = new DataValue.IntegerValue(Long.remainderUnsigned(draw, bound));
            }
            case RandomRange op -> {
                destination = op.to();
                if (op.decimal()) {
                    double first = ((DataValue.DecimalValue)state.registers().get(op.from())).value();
                    double second = ((DataValue.DecimalValue)state.registers().get(op.through())).value();
                    result = new DataValue.DecimalValue(randomDecimal(first, second, draw));
                } else {
                    long first = ((DataValue.IntegerValue)state.registers().get(op.from())).value();
                    long second = ((DataValue.IntegerValue)state.registers().get(op.through())).value();
                    long low = Math.min(first, second), high = Math.max(first, second);
                    // The width is unsigned. Zero represents all 2^64 integer values.
                    long width = high - low + 1;
                    if (width != 0 && rejectDraw(draw, width)) return state;
                    result = new DataValue.IntegerValue(low + (width == 0 ? draw : Long.remainderUnsigned(draw, width)));
                }
            }
            default -> throw new IllegalStateException("Not a random draw");
        }
        var registers = new TreeMap<>(state.registers());
        registers.put(destination, result);
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    private static boolean rejectDraw(long draw, long width) {
        // Reject the incomplete residue class. The PC stays put and the owner charges one cycle.
        return Long.compareUnsigned(draw, Long.remainderUnsigned(-width, width)) < 0;
    }
    private static double randomDecimal(double first, double second, long draw) {
        double low = Math.min(first, second), high = Math.max(first, second);
        if (low == high) return low;
        double unit = (draw >>> 11) * 0x1.0p-53;
        double width = high - low;
        // Overflowing width can only occur across zero; the weighted terms then have opposite signs.
        double result = Double.isFinite(width) ? Math.fma(unit, width, low) : low * (1 - unit) + high * unit;
        return Math.clamp(result, low, Math.nextDown(high)); // Rounding may otherwise publish the excluded endpoint.
    }
    private static long randomMix(long word) {
        word = (word ^ (word >>> 30)) * 0xbf58476d1ce4e5b9L;
        word = (word ^ (word >>> 27)) * 0x94d049bb133111ebL;
        return word ^ (word >>> 31);
    }
    /** Candidate only; publication of tag and PC remains the owning Computer's resource gate. */
    public RegisterState completeCorrelation(RegisterState state, UUID flow, long ordinal) {
        if (ordinal < 0 || ordinal == Long.MAX_VALUE) throw new ComputationException(ComputationException.Code.ARITHMETIC_OVERFLOW, location(state.pc()), "Correlation ordinal exhausted");
        var registers = new TreeMap<>(state.registers());
        registers.put(correlation(state).orElseThrow().to(), new DataValue.TextValue(flow + ":" + ordinal));
        return state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction());
    }
    public RegisterState begin(DataValue.RecordValue values) {
        var arguments = taskRoot == null ? inputs : taskRoot.inputs();
        if (!values.fields().keySet().equals(arguments.keySet())) throw new IllegalArgumentException("Assembly input names differ");
        var registers = new TreeMap<Integer, DataValue>(); arguments.forEach((name, r) -> registers.put(r, values.field(name)));
        var result = state(fingerprint, taskRoot == null ? 0 : taskRoot.entry(), registers, structured, Optional.empty(), procedures.isEmpty() ? Optional.empty() : Optional.of(RegisterCalls.EMPTY), memoryEnabled ? Optional.of(false) : Optional.empty());
        validate(result, 0, false);
        return result;
    }
    public DataValue.RecordValue locals(RegisterState state) {
        var result = new LinkedHashMap<String, DataValue>();
        var visible = taskRoot != null && complete(state) ? taskRoot.outputs() : owners[state.pc()] < 0 ? locals : procedures.get(owners[state.pc()]).locals();
        visible.forEach((name, r) -> { if (state.registers().containsKey(r)) result.put(name, state.registers().get(r)); });
        return new DataValue.RecordValue(result);
    }
    /** Observation only: unwrap at most one marked root per visible local, never walk payloads.
     * Checkpoints, RAM accounting and execution continue to use locals(state). */
    public DataValue.RecordValue displayLocals(RegisterState state) {
        var result=new LinkedHashMap<>(locals(state).fields());
        var visible=taskRoot!=null&&complete(state)?taskRoot.outputs():owners[state.pc()]<0?locals:procedures.get(owners[state.pc()]).locals();
        visible.forEach((name,r)->{
            if(state.registers().get(r) instanceof DataValue.LocalReference ref)result.put(name,new DataValue.TextValue(ref.toString()+" cell="+ref.cell()));
            if(softwareAnyRegisters.contains(r)&&state.registers().containsKey(r)) {
                var carrier=(DataValue.RecordValue)state.registers().get(r);var payload=(DataValue.NbtValue)carrier.field("payload");
                long kind=((DataValue.IntegerValue)carrier.field("kind")).value();
                result.put(name,kind==4?payload:kind==2?new DataValue.BooleanValue(((DataValue.IntegerValue)payload.value()).value()==1):payload.value());
            }
        });
        return new DataValue.RecordValue(result);
    }
    public void validate(RegisterState state, long cycles, boolean failed) {
        validate(state, cycles, failed, ignored -> {});
    }
    public void validate(RegisterState state, long cycles, boolean failed, java.util.function.LongConsumer reserve) {
        if (state.equalityCapable() != equalityEnabled) invalidCall("Comparison format differs from installation");
        if (state.serialCapable()!=serialEnabled) invalidCall("Serial format differs from installation");
        if (state.constructionCapable() != constructionEnabled)invalidCall("Construction format differs from installation");
        if (state.transaction().isPresent() != memoryEnabled) invalidCall("Memory continuation format differs from installation");
        if ((failed || complete(state)) && state.transaction().orElse(false)) invalidCall("Terminal task retains a transaction");
        if (state.calls().isPresent() != !procedures.isEmpty()) invalidCall("Call format differs from installation");
        if (taskRoot != null && complete(state)) { validateTaskResult(state, cycles, failed); return; }
        if (state.calls().isPresent()) validateFrames(state, cycles, failed);
        validateRegisters(state, cycles - state.calls().map(RegisterCalls::excludedWork).orElse(0L), failed, reserve);
    }
    private void validateTaskResult(RegisterState state, long cycles, boolean failed) {
        var context = state.calls().orElseThrow(); long local = cycles - context.excludedWork() - 1;
        if (failed || cycles <= 0 || cycles == Long.MAX_VALUE || !state.structured() || !fingerprint.equals(state.image())
        || state.comparison().isPresent() || state.scan().isPresent() || state.build().isPresent() || state.serial().isPresent() || !context.frames().isEmpty() || context.excludedWork() > cycles
                || local < minimum[taskRoot.end()] || local > maximum[taskRoot.end()]
                || !state.registers().keySet().equals(new HashSet<>(taskRoot.outputs().values())))
            invalidCall("Task result differs from its root return");
        for (var value : state.registers().entrySet()) if (!register(value.getKey()).accepts(value.getValue())) invalidCall("Invalid task result type");
        callWork.validate(taskRoot.end(), 0, context.excludedWork(), cycles - 1, false);
    }
    private void validateRegisters(RegisterState state, long cycles, boolean failed, java.util.function.LongConsumer reserve) {
        int pc = state.pc();
        String checkpointLocation = pc < code.size() && code.get(pc).instruction() instanceof TextNext ? location(pc) : "assembly";
        String checkpointPrefix = checkpointLocation.equals("assembly") ? "" : checkpointLocation + ": ";
        long completed = (failed ? cycles - 1 : cycles) - state.progressWork();
        if (state.structured() != structured || !fingerprint.equals(state.image()) || pc > code.size() || minimum[pc] == Long.MAX_VALUE
                || cycles < 0 || !failed && cycles == Long.MAX_VALUE || failed && (cycles == 0 || pc == code.size())
                || completed < minimum[pc] || completed > maximum[pc]
                || !state.registers().keySet().containsAll(required.get(pc)) || !possible.get(pc).containsAll(state.registers().keySet()))
            throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, checkpointLocation, checkpointPrefix + "Register continuation does not match its installed code");
        for (var entry : state.registers().entrySet()) if (!register(entry.getKey()).accepts(entry.getValue()))
            throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, checkpointLocation, checkpointPrefix + "Register value type differs");
        for(int r:softwareAnyRegisters)if(state.registers().containsKey(r)&&!SoftwareAnyAbi.canonical(state.registers().get(r)))
            throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,checkpointLocation,"Noncanonical software ANY carrier");
        validateOptionalRegisters(state, completed);
        if (state.comparison().isPresent()) {
            if (pc == code.size()) invalidCall("Terminal comparison");
            var op=code.get(pc).instruction();
            if(op instanceof FormatCheck f)state.comparison().orElseThrow().validate(state,f,reserve);
            else if(op instanceof ValueEquals e)state.comparison().orElseThrow().validate(state,e,reserve);
            else if(op instanceof HasField h)state.comparison().orElseThrow().validate(state,h,reserve);
            else if(op instanceof ValueCheck c)state.comparison().orElseThrow().validate(state,c,typeChecks.get(c.to()),reserve);
            else invalidCall("Comparison at wrong opcode");
        }
        if (state.build().isPresent()) {
            if (pc == code.size())invalidCall("Terminal construction");
            var op = code.get(pc).instruction();
            if (op instanceof RegisterNbt.Instruction n && n.joining())state.build().orElseThrow().validateJoining(state,n);
            else if (op instanceof RegisterNbt.Instruction n && n.decoding())state.build().orElseThrow().validateDecoding(state,n);
            else if (op instanceof RegisterNbt.Instruction n && n.encoding())state.build().orElseThrow().validateEncoding(state,n);
            else if (op instanceof RegisterNbt.Instruction n && n.packing())state.build().orElseThrow().validateNative(state,n,reserve);
            else if (op instanceof TextContains t) state.build().orElseThrow().validateTextContains(state, t);
            else if (op instanceof TextConcat t) state.build().orElseThrow().validateTextConcat(state, t);
            else if (op instanceof AppendList a) {
                var values=((DataValue.ListValue)state.registers().get(a.from())).values();
                state.build().orElseThrow().validate(state, Math.addExact(values.size(),1), i -> i<values.size()?values.get(i):state.registers().get(a.element()), null, reserve);
            }
            else if (op instanceof RegisterListEdit edit) {
                ListOperation.Selection projection;
                try { projection = edit.project(state, location(pc)); }
                catch (ComputationException invalid) {
                    throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, location(pc),
                        "List edit cursor has invalid operands");
                }
                state.build().orElseThrow().validate(state, projection.size(), projection.element(), null, reserve);
            }
            else if (op instanceof MakeList l)state.build().orElseThrow().validate(state, l.from(), null, reserve);
            else if (op instanceof MakeRecord r)state.build().orElseThrow().validate(state, r.from(), r.shape(), reserve);
            else invalidCall("Construction does not belong to its opcode");
        }
        if (state.serial().isPresent()) {
            var op=serial(state).orElseThrow(()->new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,"assembly","Serial cursor at wrong opcode"));
            if((op.kind()==RegisterSerial.Kind.RECEIVE)!=state.serial().orElseThrow().receiving() || op.kind()==RegisterSerial.Kind.STATUS) invalidCall("Serial cursor belongs to another opcode");
            state.serial().orElseThrow().validate(state,op,reserve);
        }
        if (state.scan().isPresent()) {
            var progress = state.scan().orElseThrow();
            var op = pc == code.size() ? null : code.get(pc).instruction();
            int from = switch (op) {
                case TextLength t -> t.from();
                case TextLetter t -> {
                    if (!(state.registers().get(t.index()) instanceof DataValue.IntegerValue index) || index.value() <= progress.points())
                        invalidCall("Letter scan advanced past its target");
                    yield t.from();
                }
                case null, default -> -1;
            };
            if (!(state.registers().get(from) instanceof DataValue.TextValue text))
                throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Scan does not belong to a text instruction");
            progress.validate(text.value());
        }
    }
    private void validateOptionalRegisters(RegisterState state, long completed) {
        var optional = new HashSet<>(state.registers().keySet()); optional.removeAll(required.get(state.pc()));
        if (optional.isEmpty()) return;
        // A register retained from a prior iteration needs enough work to reach its
        // writer AND return here. Do not accept a warmed register file at a cold PC.
        long[] distance = new long[code.size() + 1];
        Arrays.fill(distance, Long.MAX_VALUE);
        distance[state.pc()] = 0;
        var pending = new ArrayDeque<Integer>(); pending.add(state.pc());
        while (!pending.isEmpty()) {
            int pc = pending.removeFirst();
            for (int previous : predecessors.get(pc)) if (distance[pc] + work(code.get(previous).instruction()) < distance[previous]) {
                distance[previous] = distance[pc] + work(code.get(previous).instruction());
                pending.addLast(previous);
            }
        }
        for (int pc = 0; pc < code.size(); pc++) {
            var written = writes(code.get(pc).instruction());
            if (Collections.disjoint(optional, written) || minimum[pc] == Long.MAX_VALUE) continue;
            for (int next : edges.get(pc)) if (distance[next] != Long.MAX_VALUE && minimum[pc] + work(code.get(pc).instruction()) + distance[next] <= completed) {
                optional.removeAll(written); break;
            }
        }
        if (!optional.isEmpty()) throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Registers precede their initialization path");
    }
    /** One bounded instruction; commit the returned register file only after owner resource preflight. */
    public RegisterState step(RegisterState state) {
        if (!complete(state) && code.get(state.pc()).instruction() instanceof RegisterDevices.Instruction)
            throw new IllegalStateException("Device instruction requires an explicit signal snapshot");
        return step(state, Map.of(), Map.of()).registers();
    }
    public record Execution(RegisterState registers, Map<String, Integer> outputs) {
        public Execution { Objects.requireNonNull(registers); outputs = Map.copyOf(outputs); }
    }
    public void validateSignals(RegisterState state, long cycles, boolean failed, Map<String, Integer> outputs) {
        // forTask rejects any task that could drive SIO or interfere with a root output. A task starts at its procedure,
        // not PC 0, so the main's minimum path to that procedure is not its clock.
        // Input-only tasks cannot own a latch at any PC, including a forged restored state.
        if (taskRoot != null) {
            if (!outputs.isEmpty()) throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,
                "assembly", "Input-only task cannot own Simple I/O outputs");
            return;
        }
        signals.validate(state.pc(), (failed ? cycles - 1 : cycles) - state.progressWork(), outputs);
    }
    /** A pure candidate. No hardware effect is observable before the owner commits BOTH parts. */
    public Execution step(RegisterState state, Map<String, Integer> samples, Map<String, Integer> outputs) {
        if (state.calls().isPresent()) throw new IllegalStateException("Call-capable image requires the owner's cycle counter");
        return step(state, samples, outputs, 0);
    }
    public Execution step(RegisterState state, Map<String, Integer> samples, Map<String, Integer> outputs, long nextWork) {
        return step(state, samples, outputs, nextWork, ignored -> {});
    }
    public Execution step(RegisterState state, Map<String, Integer> samples, Map<String, Integer> outputs, long nextWork, java.util.function.LongConsumer reserve) {
        if (complete(state)) return new Execution(state, outputs);
        if (code.get(state.pc()).instruction() instanceof FormatCheck f) {
            var progress=state.comparison();
            if(progress.isPresent() && progress.orElseThrow().completeFormat(state)) {
                var cursor=progress.orElseThrow();
                reserve.accept(Math.addExact(cursor.ownedUnits(),cursor.formatResultUnits(state)));
                var result=new TreeMap<>(state.registers());result.put(f.to(),cursor.formatResult(state));
                return new Execution(state(fingerprint,state.pc()+1,result,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
            }
            var next=progress.isEmpty()?RegisterComparison.start(state,f,reserve):progress.orElseThrow().advance(state,f,reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),constructionEnabled,Optional.empty(),serialEnabled,Optional.empty(),true,Optional.of(next)),outputs);
        }
        if (code.get(state.pc()).instruction() instanceof ValueCheck c) {
            var progress = state.comparison();
            if (progress.isPresent() && progress.orElseThrow().completeType(state)) {
                var result = new TreeMap<>(state.registers()); result.put(c.to(), progress.orElseThrow().typeResult(state, c));
                return new Execution(state(fingerprint, state.pc()+1, result, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
            }
            var next = progress.isEmpty() ? RegisterComparison.start(state,c,typeChecks.get(c.to()),reserve) : progress.orElseThrow().advance(state,c,reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),constructionEnabled,Optional.empty(),serialEnabled,Optional.empty(),true,Optional.of(next)),outputs);
        }
        if (code.get(state.pc()).instruction() instanceof HasField h) {
            var progress=state.comparison();
            if(progress.isPresent() && progress.orElseThrow().completePresence(state)) {
                var result=new TreeMap<>(state.registers());result.put(h.to(),progress.orElseThrow().presenceResult(state));
                return new Execution(state(fingerprint,state.pc()+1,result,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
            }
            var next=progress.isEmpty()?RegisterComparison.start(state,h,reserve):progress.orElseThrow().advance(state,h,reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),constructionEnabled,Optional.empty(),serialEnabled,Optional.empty(),true,Optional.of(next)),outputs);
        }
        if (code.get(state.pc()).instruction() instanceof ValueEquals e) {
            var progress = state.comparison();
            if (progress.isPresent() && progress.orElseThrow().complete(state, e)) {
                var result = new TreeMap<>(state.registers()); result.put(e.to(), progress.orElseThrow().result(state, e));
                return new Execution(state(fingerprint, state.pc()+1, result, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
            }
            var next = progress.isEmpty() ? RegisterComparison.start(state, e, reserve) : progress.orElseThrow().advance(state, e, reserve);
            return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(), state.calls(), state.transaction(), constructionEnabled, Optional.empty(), serialEnabled, Optional.empty(), true, Optional.of(next)), outputs);
        }
        if (code.get(state.pc()).instruction() instanceof TextNext t) {
            // Check long bounds before narrowing; only bounded current/preceding unit accesses.
            var text = ((DataValue.TextValue) state.registers().get(t.from())).value();
            long cursor = ((DataValue.IntegerValue) state.registers().get(t.offset())).value();
            if (cursor < 0 || cursor > text.length()) throw new ComputationException(
                ComputationException.Code.INDEX_OUT_OF_BOUNDS, location(state.pc()), "Text cursor exceeds UTF-16 bounds");
            int offset = (int) cursor;
            if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                && Character.isHighSurrogate(text.charAt(offset - 1))) throw new ComputationException(
                ComputationException.Code.INVALID_ARGUMENT, location(state.pc()), "Text cursor splits a surrogate pair");
            boolean done = offset == text.length();
            int point = done ? -1 : text.codePointAt(offset);
            reserve.accept(TextNext.RESULT_UNITS);
            var value = new DataValue.RecordValue(Map.of(
                "done", new DataValue.BooleanValue(done),
                "next", new DataValue.IntegerValue(done ? offset : offset + Character.charCount(point)),
                "codepoint", new DataValue.IntegerValue(point),
                "whitespace", new DataValue.BooleanValue(!done && Character.isWhitespace(point)),
                "control", new DataValue.BooleanValue(!done && Character.isISOControl(point))));
            var published = new TreeMap<>(state.registers()); published.put(t.to(), value);
            return new Execution(state(fingerprint, state.pc()+1, published, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
        }
        if (code.get(state.pc()).instruction() instanceof TextContains t) {
            String text = ((DataValue.TextValue)state.registers().get(t.text())).value();
            String needle = ((DataValue.TextValue)state.registers().get(t.needle())).value();
            var progress = RegisterTextContains.START;
            if (state.build().isPresent()) {
                progress = state.build().orElseThrow().textContains();
                if (progress.done(text, needle)) {
                    reserve.accept(1);
                    var registers = new TreeMap<>(state.registers());
                    registers.put(t.to(), new DataValue.BooleanValue(progress.found(needle)));
                    return new Execution(state(fingerprint, state.pc() + 1, registers, structured,
                        Optional.empty(), state.calls(), state.transaction()), outputs);
                }
                progress = progress.advance(text, needle);
            }
            return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(), state.calls(),
                state.transaction(), true, Optional.of(RegisterConstruction.textContains(state, progress)), serialEnabled,
                Optional.empty(), equalityEnabled, Optional.empty()), outputs);
        }
        if (code.get(state.pc()).instruction() instanceof TextConcat t) {
            String first = RegisterTextConcat.operand(state, t.left(), location(state.pc()));
            String second = RegisterTextConcat.operand(state, t.right(), location(state.pc()));
            long length = (long)first.length() + second.length();
            if (length > Integer.MAX_VALUE)
                throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, location(state.pc()), "Concatenated text exceeds the representable length");
            if (state.build().isPresent()) {
                var progress = state.build().orElseThrow().textConcat();
                if (progress.done(first, second)) {
                    // Every code point has already consumed its work. Allocate exactly one output now,
                    // after reserving it while both immutable input registers remain live.
                    reserve.accept(1 + length);
                    var registers = new TreeMap<>(state.registers());
                    registers.put(t.to(), new DataValue.TextValue(first.concat(second)));
                    return new Execution(state(fingerprint, state.pc() + 1, registers, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
                }
            }
            var progress = state.build().map(cursor -> cursor.textConcat().advance(first, second)).orElse(RegisterTextConcat.START);
            var cursor = RegisterConstruction.textConcat(state, progress);
            return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(), state.calls(),
                state.transaction(), true, Optional.of(cursor), serialEnabled, Optional.empty(), equalityEnabled, Optional.empty()), outputs);
        }
        var construction = code.get(state.pc()).instruction();
        if(construction instanceof RegisterNbt.Instruction n && n.joining()) {
            var progress=state.build().orElse(null);
            if(progress!=null && progress.joiningDone()) {
                reserve.accept(progress.joiningResultUnits());
                var published=new TreeMap<>(state.registers());published.put(n.to(),progress.joiningResult());
                return new Execution(state(fingerprint,state.pc()+1,published,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
            }
            var next=progress==null?RegisterConstruction.startJoining(state,n,reserve):progress.advanceJoining(state,n,reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),true,Optional.of(next),serialEnabled,Optional.empty(),equalityEnabled,Optional.empty()),outputs);
        }
        if(construction instanceof RegisterNbt.Instruction n && n.decoding()) {
            var progress=state.build().orElse(null);
            if(progress!=null && progress.decodingDone()) {
                reserve.accept(progress.decodingResultUnits());
                var published=new TreeMap<>(state.registers());published.put(n.to(),progress.decodingResult());
                return new Execution(state(fingerprint,state.pc()+1,published,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
            }
            var next=progress==null?RegisterConstruction.startDecoding(state,n,reserve):progress.advanceDecoding(state,n,typeChecks.get(n.to()),reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),true,Optional.of(next),serialEnabled,Optional.empty(),equalityEnabled,Optional.empty()),outputs);
        }
        if(construction instanceof RegisterNbt.Instruction n && n.encoding()) {
            var progress=state.build().orElse(null);
            if(progress!=null && progress.encodingDone()) {
                reserve.accept(progress.encodingResultUnits());
                var published=new TreeMap<>(state.registers());published.put(n.to(),progress.encodingResult());
                return new Execution(state(fingerprint,state.pc()+1,published,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
            }
            var next=progress==null?RegisterConstruction.startEncoding(state,n,reserve):progress.advanceEncoding(state,n,reserve);
            return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),true,Optional.of(next),serialEnabled,Optional.empty(),equalityEnabled,Optional.empty()),outputs);
        }
        if(construction instanceof RegisterNbt.Instruction n && n.packing()) {
            try {
                var progress=state.build().orElse(null);
                if(progress!=null && progress.nativeDone()) {
                    reserve.accept(progress.nativeResultUnits());
                    var published=new TreeMap<>(state.registers());published.put(n.to(),progress.nativeResult());
                    return new Execution(state(fingerprint,state.pc()+1,published,structured,Optional.empty(),state.calls(),state.transaction()),outputs);
                }
                var next=progress==null?RegisterConstruction.startNative(state,n,reserve):progress.advanceNative(state,reserve);
                return new Execution(new RegisterState(fingerprint,state.pc(),state.registers(),structured,Optional.empty(),state.calls(),state.transaction(),true,Optional.of(next),serialEnabled,Optional.empty(),equalityEnabled,Optional.empty()),outputs);
            } catch(IllegalArgumentException invalid) {throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,location(state.pc()),invalid.getMessage());}
        }
        if (construction instanceof AppendList a)
            return advanceList(state, outputs, a.to(), ListOperation.APPEND.project(
                (DataValue.ListValue)state.registers().get(a.from()), 0, 0, state.registers().get(a.element()), location(state.pc())), reserve);
        if (construction instanceof RegisterListEdit edit)
            return advanceList(state, outputs, edit.to(), edit.project(state, location(state.pc())), reserve);
        if (construction instanceof MakeList || construction instanceof MakeRecord) {
            List<Integer> operands = construction instanceof MakeList l?l.from():((MakeRecord)construction).from();
            DataValue.RecordShape shape = construction instanceof MakeRecord r?r.shape():null;
            int to = construction instanceof MakeList l?l.to():((MakeRecord)construction).to();
            if (state.build().isEmpty())return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(), state.calls(), state.transaction(), true, Optional.of(RegisterConstruction.start(state, shape)), serialEnabled, Optional.empty(), equalityEnabled, Optional.empty()), outputs);
            var prefix = state.build().orElseThrow();
            if (prefix.cursor()<operands.size())return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(), state.calls(), state.transaction(), true, Optional.of(prefix.append(state, state.registers().get(operands.get(prefix.cursor())))), serialEnabled, Optional.empty(), equalityEnabled, Optional.empty()), outputs);
            var published = new TreeMap<>(state.registers());
            published.put(to, prefix.finish());
            return new Execution(state(fingerprint, state.pc() + 1, published, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
        }
        var registers = new TreeMap<>(state.registers()); int next = state.pc() + 1;
        switch (code.get(state.pc()).instruction()) {
            case RegisterNbt.Instruction n -> {
                try {registers.put(n.to(),n.execute(state,reserve));}
                catch(IllegalArgumentException invalid) {throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,location(state.pc()),invalid.getMessage());}
            }
            case HasField ignored -> throw new AssertionError();
            case ValueEquals ignored -> throw new AssertionError();
            case FormatCheck ignored -> throw new AssertionError();
            case FormatProject p -> {
                if(!(registers.get(p.from()) instanceof DataValue.RecipeValue value) || value.version()!=p.version())throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"assembly","FMT.PROJECT requires the declared recipe version");
                reserve.accept(value.projection().storageUnits());
                registers.put(p.to(),value.projection());
            }
            case ValueCheck ignored -> throw new AssertionError();
            case TextContains ignored -> throw new AssertionError();
            case TextConcat ignored -> throw new AssertionError();
            case TextNext ignored -> throw new AssertionError();
            case AppendList ignored -> throw new AssertionError();
            case RegisterListEdit ignored -> throw new AssertionError();
            case MakeList ignored -> throw new AssertionError();
            case MakeRecord ignored -> throw new AssertionError();
            case RandomRange ignored -> throw new IllegalStateException("Random draw requires its owning task identity");
            case RandomBelow ignored -> throw new IllegalStateException("Random draw requires its owning task identity");
            case Correlation ignored -> throw new IllegalStateException("Correlation requires its owning flow identity");
            case RegisterMonitor ignored -> throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,
                location(state.pc()), "Monitor requires its owning Computer");
            case RegisterTimer ignored -> throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,
                location(state.pc()), "Timer requires its owning Computer");
            case RegisterPrompt ignored -> throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,
                location(state.pc()), "Terminal prompt requires its owning Computer");
            case RegisterKeyDown ignored -> throw new IllegalStateException("Terminal keyboard requires its owning Computer");
            case RegisterTaskGroup ignored -> throw new IllegalStateException("Task admission requires scheduler");
            case RegisterEvent ignored -> throw new IllegalStateException("Event dispatch requires the Computer task owner");
            case TryLaunch ignored -> throw new IllegalStateException("Try-launch requires atomic Computer admission");
            case TaskStart ignored -> throw new IllegalStateException("Task start requires atomic Computer admission");
            case TaskWait ignored -> throw new IllegalStateException("Task wait requires its Computer owner");
            case TaskClaim ignored -> throw new IllegalStateException("Task claim requires atomic Computer publication");
            case TaskStop ignored -> throw new IllegalStateException("Task cancellation requires atomic Computer publication");
            case Constant c -> registers.put(c.to(), constants.get(c.pool()));
            case DefaultNumber m -> registers.putIfAbsent(m.to(), registers.get(m.from()));
            case Move m -> registers.put(m.to(), registers.get(m.from()));
            case Drop d -> registers.remove(d.register());
            case Call c -> { return new Execution(enter(state, c, nextWork), outputs); }
            case Return ignored -> { return new Execution(leave(state, nextWork), outputs); }
            case Alu a -> {
                var arguments = new LinkedHashMap<String, DataValue>(); int i = 0;
                for (String name : a.operation().inputs().keySet()) arguments.put(name, registers.get(a.from().get(i++)));
                registers.put(a.to(), a.operation().evaluate(arguments, location(state.pc())));
            }
            case Nonnegative n -> {
                if (((DataValue.IntegerValue)registers.get(n.value())).value() < 0)
                    throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT, location(state.pc()), "Repeat count must be nonnegative");
            }
            case JumpFalse j -> { if (!((DataValue.BooleanValue) registers.get(j.condition())).value()) next = j.target(); }
            case Jump j -> next = j.target();
            case Field f -> {
                var value = registers.get(f.from());
                DataValue selected = value instanceof DataValue.RecordValue r ? r.field(f.key()) : value instanceof DataValue.MapValue m ? m.field(f.key()) : null;
                if (selected == null) throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "Expected record or map");
                registers.put(f.to(), selected);
            }
            case ListLength l -> {
                if (!(registers.get(l.from()) instanceof DataValue.ListValue list)) throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "Expected list");
                registers.put(l.to(), new DataValue.IntegerValue(list.values().size()));
            }
            case DynamicIndex i -> {
                if (!(registers.get(i.from()) instanceof DataValue.ListValue list)) throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "Expected list");
                long index = ((DataValue.IntegerValue)registers.get(i.indexRegister())).value();
                if (index < 0 || index >= list.values().size()) throw new ComputationException(ComputationException.Code.INDEX_OUT_OF_BOUNDS, location(state.pc()), "List index " + index + " is out of bounds");
                registers.put(i.to(), list.values().get((int)index));
            }
            case Index i -> {
                if (!(registers.get(i.from()) instanceof DataValue.ListValue list)) throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "Expected list");
                if (i.index() >= list.values().size()) throw new ComputationException(ComputationException.Code.INDEX_OUT_OF_BOUNDS, location(state.pc()), "List index exceeds size");
                registers.put(i.to(), list.values().get(i.index()));
            }
            case TextLength t -> {
                if (!(registers.get(t.from()) instanceof DataValue.TextValue text)) throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location(state.pc()), "Expected text");
                int offset = state.scan().map(RegisterState.Scan::offset).orElse(0);
                long points = state.progressWork();
                if (offset < text.value().length()) return new Execution(state(fingerprint, state.pc(), registers, structured,
                    Optional.of(RegisterState.Scan.advanced(text.value(), offset, points)), state.calls(), state.transaction()), outputs);
                registers.put(t.to(), new DataValue.IntegerValue(points));
            }
            case TextLetter t -> {
                String text = ((DataValue.TextValue)registers.get(t.from())).value();
                long index = ((DataValue.IntegerValue)registers.get(t.index())).value();
                int offset = state.scan().map(RegisterState.Scan::offset).orElse(0);
                long preceding = state.progressWork();
                if (index > 0 && offset < text.length() && preceding + 1 < index)
                    return new Execution(state(fingerprint, state.pc(), state.registers(), structured,
                        Optional.of(RegisterState.Scan.advanced(text, offset, preceding)), state.calls(), state.transaction()), outputs);
                int size = index > 0 && offset < text.length() ? Character.charCount(text.codePointAt(offset)) : 0;
                reserve.accept(1L + size);
                registers.put(t.to(), new DataValue.TextValue(size == 0 ? "" : text.substring(offset, offset + size)));
            }
            case RegisterDevices.Instruction d -> {
                switch (d.kind()) {
                    case SIO_READ -> {
                        Integer sample = samples.get(d.pin());
                        if (sample == null) throw new ComputationException(ComputationException.Code.MISSING_REFERENCE, location(state.pc()), "No available signal sample: " + d.pin());
                        if (sample < 0 || sample > 15) throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT, location(state.pc()), "Invalid signal sample");
                        registers.put(d.register(), new DataValue.IntegerValue(sample));
                        outputs = new LinkedHashMap<>(outputs); outputs.remove(d.pin());
                    }
                    case SIO_WRITE -> {
                        long level = ((DataValue.IntegerValue)registers.get(d.register())).value();
                        outputs = new LinkedHashMap<>(outputs); outputs.put(d.pin(), (int)Math.clamp(level, 0, 15));
                    }
                    case XBUS_STATUS -> registers.put(d.register(), new DataValue.IntegerValue(samples.getOrDefault(d.pin(), 0) == 15 ? 15 : 0));
                }
            }
            case RegisterSerial.Instruction ignored -> throw new IllegalStateException("Serial requires admitted owner host");
            case RegisterXBus.Instruction ignored -> throw new IllegalStateException("XBus requires an admitted owner rendezvous");
            case Sleep ignored -> throw new IllegalStateException("Sleep requires an admitted owner clock wait");
            case RegisterReference ignored -> throw new IllegalStateException("Reference requires its Computer owner");
            case RegisterMemoryOp ignored -> throw new IllegalStateException("Memory requires its Computer owner");
        }
        return new Execution(state(fingerprint, next, registers, structured, Optional.empty(), state.calls(), state.transaction()), outputs);
    }
    private RegisterState enter(RegisterState state, Call call, long nextWork) {
        var context = state.calls().orElseThrow(); var frames = new ArrayList<>(context.frames());
        frames.add(new RegisterCalls.Frame(state.pc(), source(state.pc()), state.registers(), context.excludedWork(), nextWork));
        var arguments = new TreeMap<Integer, DataValue>();
        procedures.get(call.target()).inputs().values().forEach(r -> arguments.put(r, state.registers().get(r)));
        return state(fingerprint, call.target(), arguments, true, Optional.empty(), Optional.of(new RegisterCalls(nextWork, frames)), state.transaction());
    }
    private RegisterState leave(RegisterState state, long nextWork) {
        var context = state.calls().orElseThrow(); var frames = new ArrayList<>(context.frames());
        if (taskRoot != null && frames.isEmpty()) {
            var result = new TreeMap<Integer, DataValue>(); taskRoot.outputs().values().forEach(r -> result.put(r, state.registers().get(r)));
            return state(fingerprint, code.size(), result, true, Optional.empty(), Optional.of(context), state.transaction());
        }
        var caller = frames.removeLast();
        var registers = new TreeMap<>(caller.registers());
        procedures.get(owners[state.pc()]).outputs().values().forEach(r -> registers.put(r, state.registers().get(r)));
        long excluded = Math.addExact(caller.excludedWork(), Math.subtractExact(nextWork, caller.enteredWork()));
        return state(fingerprint, caller.pc() + 1, registers, true, Optional.empty(), Optional.of(new RegisterCalls(excluded, frames)), state.transaction());
    }
    private void validateFrames(RegisterState state, long cycles, boolean attempted) {
        var context = state.calls().orElseThrow(); int expected = taskRoot == null ? -1 : taskRoot.entry(); long entered = 0;
        var visited = new HashSet<Integer>(); visited.add(expected);
        for (var frame : context.frames()) {
            if (frame.pc() >= code.size() || owners[frame.pc()] != expected || source(frame.pc()) != frame.source() || !(code.get(frame.pc()).instruction() instanceof Call)
                    || frame.enteredWork() <= entered || frame.enteredWork() > cycles || frame.excludedWork() < entered)
                invalidCall("Caller frame differs from its instruction");
            validateRegisters(state(fingerprint, frame.pc(), frame.registers(), true, Optional.empty(), Optional.empty(), Optional.empty()),
                frame.enteredWork() - 1 - frame.excludedWork(), false, ignored -> {});
            callWork.validate(frame.pc(), entered, frame.excludedWork(), frame.enteredWork() - 1, false);
            expected = ((Call)code.get(frame.pc()).instruction()).target(); entered = frame.enteredWork();
            if (!visited.add(expected)) invalidCall("Recursive call stack");
        }
        if (state.pc() > code.size() || owners[state.pc()] != expected || context.excludedWork() < entered || context.excludedWork() > cycles)
            invalidCall("Active register scope differs from its callers");
        callWork.validate(state.pc(), entered, context.excludedWork(),
        cycles - state.progressWork(), attempted);
    }
    private static void invalidCall(String message) {
        throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", message);
    }
    private Execution advanceList(RegisterState state, Map<String, Integer> outputs, int destination,
                                  ListOperation.Selection projection, java.util.function.LongConsumer reserve) {
        RegisterConstruction next;
        if (state.build().isEmpty()) {
            reserve.accept(1);
            next = RegisterConstruction.start(state, null);
        } else {
            var prefix = state.build().orElseThrow();
            if (prefix.cursor() == projection.size()) {
                reserve.accept(prefix.ownedUnits());
                var registers = new TreeMap<>(state.registers());
                registers.put(destination, prefix.finish());
                return new Execution(state(fingerprint, state.pc() + 1, registers, structured,
                    Optional.empty(), state.calls(), state.transaction()), outputs);
            }
            var value = projection.element().apply(prefix.cursor());
            reserve.accept(Math.addExact(prefix.ownedUnits(), value.storageUnits()));
            next = prefix.append(state, value);
        }
        return new Execution(new RegisterState(fingerprint, state.pc(), state.registers(), structured, Optional.empty(),
            state.calls(), state.transaction(), true, Optional.of(next), serialEnabled, Optional.empty(),
            equalityEnabled, Optional.empty()), outputs);
    }
}
