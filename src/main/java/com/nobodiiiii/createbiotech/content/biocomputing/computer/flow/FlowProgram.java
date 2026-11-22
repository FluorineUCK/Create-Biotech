package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEvent;

/** Sequential control IR. Stable source IDs are independent of instruction positions and GUI layout. */
public record FlowProgram(Map<String, DataType> inputs, List<Step> steps, ProgramArchive expressions, Execution execution, Compilation compilation) {
    public enum Execution { LEGACY_GRAPH, REGISTER_V1 }
    public enum Compilation {
        ORIGINAL, CLOSED_V1, SOFTWARE_V1, COMPACT_V1;
        public String token() { return switch (this) { case ORIGINAL -> "original"; case CLOSED_V1 -> "closed_v1"; case SOFTWARE_V1 -> "software_v1"; case COMPACT_V1 -> "compact_v1"; }; }
        public static Compilation parse(String token) {
            if ("compact_v1".equals(token)) return COMPACT_V1;
            if ("software_v1".equals(token)) return SOFTWARE_V1;
            if ("closed_v1".equals(token)) return CLOSED_V1;
            throw new IllegalArgumentException("Unknown flow compilation policy");
        }
    }
    public FlowProgram(Map<String, DataType> inputs, List<Step> steps, ProgramArchive expressions, Execution execution) {
        this(inputs,steps,expressions,execution,Compilation.ORIGINAL);
    }
    public FlowProgram withClosedSpecialization() { return new FlowProgram(inputs,steps,expressions,Execution.REGISTER_V1,Compilation.CLOSED_V1); }
    public FlowProgram {
        inputs = DataValue.immutableMap(inputs); inputs.keySet().forEach(FlowProgram::name);
        steps = List.copyOf(steps);
        Objects.requireNonNull(expressions);
        Objects.requireNonNull(execution); Objects.requireNonNull(compilation);
        if (compilation != Compilation.ORIGINAL && execution != Execution.REGISTER_V1)
            throw new IllegalArgumentException("Compilation policy requires register execution");
        var ids = new HashSet<String>();
        for (var step : steps) if (!ids.add(step.sourceId()))
            throw error(ComputationException.Code.INVALID_GRAPH, step.sourceId(), "Duplicate flow source ID");
    }
    public FlowProgram(Map<String, DataType> inputs, List<Step> steps, ProgramArchive expressions) {
        this(inputs, steps, expressions, Execution.LEGACY_GRAPH);
    }
    /** Explicit new installation only; never reinterpret an existing continuation. */
    public FlowProgram withAssembly() { return new FlowProgram(inputs, steps, expressions, Execution.REGISTER_V1); }
    public FlowProgram(Map<String, DataType> inputs, List<Step> steps) {
        this(inputs, steps, new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of()), Map.of()));
    }

    public sealed interface Step { String sourceId(); }
    public enum ReferenceKind { ADDRESS, READ, WRITE, READONLY, SAME }
    public record Reference(String sourceId,ReferenceKind kind,String target,String local,Optional<FlowInput> value) implements Step {
        public Reference {name(sourceId);Objects.requireNonNull(kind);name(target);Objects.requireNonNull(local);Objects.requireNonNull(value);if(kind!=ReferenceKind.WRITE)name(local);else if(!local.isEmpty())throw new IllegalArgumentException("Reference write has no result");if(value.isPresent()!=(kind==ReferenceKind.WRITE||kind==ReferenceKind.SAME))throw new IllegalArgumentException("Reference operand mismatch");}
    }
    public boolean usesReferences(){return hasReferences(steps);}
    public static boolean hasReferences(List<Step> steps){return steps.stream().anyMatch(s->s instanceof Reference||s instanceof Procedure p&&(p.inputs().values().stream().anyMatch(LocalReferences::contains)||p.outputs().values().stream().anyMatch(LocalReferences::contains)))||authoredAny(steps,"REF.ADDRESS","REF.READ","REF.WRITE","REF.READONLY","REF.SAME");}
    /** Structured source sugar; the VM still executes ordinary transaction instructions. */
    public record Atomic(String sourceId, String result) implements Step {
        public Atomic { name(sourceId); name(result); }
    }
    public record EndAtomic(String sourceId) implements Step { public EndAtomic { name(sourceId); } }
    public enum MemoryKind { BEGIN, READ, WRITE, COMMIT, ROLLBACK }
    /** Explicit access to this Computer's cells; names lower to installation-local logical indices. */
    public record Memory(String sourceId, MemoryKind kind, String cell, String local) implements Step {
        public Memory {
            name(sourceId); Objects.requireNonNull(kind); Objects.requireNonNull(cell); Objects.requireNonNull(local);
            if (kind == MemoryKind.READ || kind == MemoryKind.WRITE) name(cell);
            else if (!cell.isEmpty()) throw new IllegalArgumentException("This memory operation has no cell");
            if (kind == MemoryKind.READ || kind == MemoryKind.WRITE || kind == MemoryKind.COMMIT) name(local);
            else if (!local.isEmpty()) throw new IllegalArgumentException("This memory operation has no local");
        }
    }
    /** ISA-only access to a globally public cell; it never creates or updates a private local. */
    public record Shared(String sourceId, String cell, FlowInput value, boolean add, Optional<String> listResult) implements Step {
        public Shared(String sourceId, String cell, FlowInput value, boolean add) {
            this(sourceId, cell, value, add, Optional.empty());
        }
        public Shared {
            name(sourceId); name(cell); Objects.requireNonNull(value); Objects.requireNonNull(listResult);
            if (listResult.isPresent()) {
                name(listResult.orElseThrow());
                if (add || !(value instanceof FlowInput.Expression expression) || expression.arguments().isEmpty())
                    throw new IllegalArgumentException("Public list edit requires an explicitly bound expression");
            }
        }
    }
    public static boolean hasPublicListEdits(List<Step> steps) {
        return steps.stream().anyMatch(s -> s instanceof Shared shared && shared.listResult().isPresent());
    }
    public static boolean hasShared(List<Step> steps) { return steps.stream().anyMatch(Shared.class::isInstance); }
    public static boolean hasPublicReaders(ProgramArchive archive) {
        return publicReader(archive.program().graph())
            || archive.program().entries().values().stream().anyMatch(entry -> publicReader(entry.graph()))
            || archive.modules().values().stream().anyMatch(module -> publicReader(module.graph()));
    }
    public static boolean publicReader(ComputationGraph graph) {
        return graph.nodes().values().stream().anyMatch(ComputationGraph.ReadMemory.class::isInstance);
    }
    public boolean usesAuthoredV4() {return execution==Execution.REGISTER_V1 && steps.stream().anyMatch(s->s instanceof Procedure p && p.assembly().isPresent());}
    public static boolean authoredOpcode(List<Step> steps,String regex) {return steps.stream().anyMatch(s->s instanceof Procedure p && p.assembly().filter(a->Arrays.stream(ProcedureAssembler.sourceLines(a.text())).map(ProcedureAssembler::uncomment).anyMatch(l->l.matches("(?:"+regex+")(?:\\s.*)?"))).isPresent());}
    private static boolean authoredAny(List<Step> steps, String... opcodes) {
        for (var step : steps) if (step instanceof Procedure p && p.assembly().isPresent()) {
            var source = p.assembly().orElseThrow();
            for (var opcode : opcodes) if (source.hasOpcode(opcode)) return true;
        }
        return false;
    }
    public boolean usesSerialAssembly() { return execution==Execution.REGISTER_V1 && steps.stream().anyMatch(s->s instanceof SerialSend || s instanceof SerialStatus || s instanceof SerialReceive) || execution==Execution.REGISTER_V1 && authoredAny(steps,"SERIAL.SEND","SERIAL.STATUS","SERIAL.RECEIVE"); }
    public boolean usesReceiveAssembly() {return execution==Execution.REGISTER_V1 && (steps.stream().anyMatch(s->s instanceof SerialReceive) || authoredAny(steps,"SERIAL.RECEIVE"));}
    public boolean usesPublicAssembly() { return execution == Execution.REGISTER_V1 && (hasShared(steps) || hasPublicReaders(expressions)); }
    public boolean usesMemoryAssembly() { return execution == Execution.REGISTER_V1
        && (!expressions.program().cells().isEmpty() || steps.stream().anyMatch(s -> s instanceof Memory || s instanceof Atomic || s instanceof Shared)); }
    /** A fixed-version local procedure definition. Its body is skipped by the enclosing main flow. */
    public record Procedure(String sourceId, ModuleDefinition.Key key, Map<String, DataType> inputs,
            Map<String, DataType> outputs, Map<String, String> returns, FlowBlockRules rules, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource> assembly, Optional<String> event, Optional<String> terminalKey, boolean startup) implements Step {
        public Procedure(String sourceId, ModuleDefinition.Key key, Map<String,DataType> inputs, Map<String,DataType> outputs,
                Map<String,String> returns, FlowBlockRules rules, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource> assembly, Optional<String> event, boolean startup) {
            this(sourceId,key,inputs,outputs,returns,rules,assembly,event,Optional.empty(),startup);
        }
        public static Procedure keyHat(String source,ModuleDefinition.Key entry,String key) {
            return new Procedure(source,entry,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.empty(),Optional.empty(),Optional.of(key),false);
        }
        public Procedure(String sourceId, ModuleDefinition.Key key, Map<String, DataType> inputs, Map<String, DataType> outputs,
                Map<String, String> returns, FlowBlockRules rules, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource> assembly, Optional<String> event) {
            this(sourceId,key,inputs,outputs,returns,rules,assembly,event,false);
        }
        public static Procedure startupHat(String source, ModuleDefinition.Key entry) {
            return new Procedure(source,entry,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.empty(),Optional.empty(),true);
        }
        public Procedure(String sourceId, ModuleDefinition.Key key, Map<String, DataType> inputs, Map<String, DataType> outputs,
                Map<String, String> returns, FlowBlockRules rules, Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource> assembly) {
            this(sourceId,key,inputs,outputs,returns,rules,assembly,Optional.empty());
        }
        public Procedure(String sourceId, ModuleDefinition.Key key, Map<String, DataType> inputs, Map<String, DataType> outputs, Map<String, String> returns, FlowBlockRules rules) {
            this(sourceId,key,inputs,outputs,returns,rules,Optional.empty());
        }
        public Procedure(String sourceId, ModuleDefinition.Key key, Map<String, DataType> inputs, Map<String, DataType> outputs, Map<String, String> returns) {
            this(sourceId, key, inputs, outputs, returns, FlowBlockRules.NONE);
        }
        public Procedure {
            name(sourceId); Objects.requireNonNull(key); Objects.requireNonNull(rules); Objects.requireNonNull(assembly);
            terminalKey=terminalKey.map(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey::selector);
            if (terminalKey.isPresent() && (event.isPresent() || assembly.isPresent() || !inputs.isEmpty() || !outputs.isEmpty()))
                throw new IllegalArgumentException("A key hat must be a visual event without arguments or return values");
            if (startup && (terminalKey.isPresent() || event.isPresent() || assembly.isPresent() || !inputs.isEmpty() || !outputs.isEmpty() || !rules.equals(FlowBlockRules.NONE)))
                throw new IllegalArgumentException("A startup hat must be a visual event without arguments or return values");
            event.ifPresent(RegisterEvent::requireChannel);
            if (event.isPresent() && (!inputs.isEmpty() || !outputs.isEmpty()))
                throw new IllegalArgumentException("Broadcast receivers do not take arguments or return values");
            inputs = DataValue.immutableMap(inputs); outputs = DataValue.immutableMap(outputs); returns = DataValue.immutableMap(returns);
            inputs.keySet().forEach(FlowProgram::name); outputs.keySet().forEach(FlowProgram::name); returns.values().forEach(FlowProgram::name);
            if (!outputs.keySet().equals(returns.keySet())) throw error(ComputationException.Code.INVALID_GRAPH, sourceId, "Procedure return sources differ from signature");
        }
    }
    public record Broadcast(String sourceId, String channel, boolean await, boolean software) implements Step {
        public Broadcast(String sourceId,String channel,boolean await){this(sourceId,channel,await,false);}
        public Broadcast { name(sourceId); RegisterEvent.requireChannel(channel); }
    }
    /** Choose the image only on fresh installation. Saved PCs stay bound to their original image. */
    public FlowProgram forBroadcastHardware(boolean parser) {
        if(execution!=Execution.REGISTER_V1 || steps.stream().noneMatch(s->s instanceof Broadcast e && e.software()==parser))return this;
        return new FlowProgram(inputs,steps.stream().map(s -> s instanceof Broadcast e ?
            (Step)new Broadcast(e.sourceId(),e.channel(),e.await(),!parser):s).toList(),expressions,execution,compilation);
    }
    public static boolean hasEvents(List<Step> steps) {
        return steps.stream().anyMatch(s -> s instanceof Broadcast || s instanceof Procedure p && p.event().isPresent())
            || authoredAny(steps, "EVENT.POST", "EVENT.POST.WAIT");
    }
    public static boolean hasTerminalKeys(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Procedure p && p.terminalKey().isPresent());
    }
    public record TimerReset(String sourceId) implements Step { public TimerReset { name(sourceId); } }
    public static boolean hasTimerReset(List<Step> steps) { return steps.stream().anyMatch(TimerReset.class::isInstance); }
    public record Ask(String sourceId, FlowInput question) implements Step {
        public Ask { name(sourceId); Objects.requireNonNull(question); }
    }
    public static boolean hasAsk(List<Step> steps) { return steps.stream().anyMatch(Ask.class::isInstance); }
    public boolean usesTimer() {
        return execution == Execution.REGISTER_V1 && (hasTimerReset(steps) || authoredAny(steps, "TIMER.READ", "TIMER.RESET")
            || usedEntryHas(ComputationGraph.Timer.class::isInstance));
    }
    public boolean usesPrompts() {
        return execution == Execution.REGISTER_V1 && (hasAsk(steps) || authoredAny(steps, "TERMINAL.ASK", "TERMINAL.ANSWER")
            || usedEntryHas(ComputationGraph.Answer.class::isInstance));
    }
    public boolean usesKeyDown() { return execution == Execution.REGISTER_V1 && (usedEntryHas(ComputationGraph.KeyDown.class::isInstance) || authoredAny(steps,"TERMINAL.KEY.DOWN")); }
    public boolean usesTerminalKeys() { return execution==Execution.REGISTER_V1 && hasTerminalKeys(steps); }
    public static boolean hasStartupHats(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Procedure p && p.startup());
    }
    public boolean usesStartupHats() { return execution == Execution.REGISTER_V1 && hasStartupHats(steps); }
    public List<ModuleDefinition.Key> startupEntries() {
        if (execution != Execution.REGISTER_V1) return List.of();
        return steps.stream().filter(step -> step instanceof Procedure p && p.startup()).map(step -> ((Procedure)step).key()).toList();
    }
    public boolean usesEvents() { return execution == Execution.REGISTER_V1 && hasEvents(steps); }
    public record EndProcedure(String sourceId) implements Step { public EndProcedure { name(sourceId); } }
    public record InvokeOperands(String entry, Map<String, String> inputs) {
        public InvokeOperands { name(entry); inputs = new FlowCallBindings(inputs, Map.of()).arguments(); }
    }
    public record Invoke(String sourceId, ModuleDefinition.Key procedure, FlowCallBindings bindings, Optional<InvokeOperands> operands) implements Step {
        public Invoke(String sourceId, ModuleDefinition.Key procedure, FlowCallBindings bindings) {
            this(sourceId, procedure, bindings, Optional.empty());
        }
        public Invoke {
            name(sourceId); Objects.requireNonNull(procedure); Objects.requireNonNull(bindings); Objects.requireNonNull(operands);
            if (operands.isPresent() && !bindings.arguments().isEmpty())
                throw error(ComputationException.Code.INVALID_GRAPH, sourceId, "Use inline arguments or legacy local bindings, not both");
        }
    }
    public record TryLaunch(String sourceId, ModuleDefinition.Key procedure, String started) implements Step {
        public TryLaunch { name(sourceId); Objects.requireNonNull(procedure); name(started); }
    }
    /** Explicit asynchronous local launch; bindings never capture a same-name caller local implicitly. */
    public record TaskStart(String sourceId, ModuleDefinition.Key procedure, Map<String, String> arguments, String handle) implements Step {
        public TaskStart {
            name(sourceId); Objects.requireNonNull(procedure); name(handle); arguments = DataValue.immutableMap(arguments);
            arguments.keySet().forEach(FlowProgram::name); arguments.values().forEach(FlowProgram::name);
        }
    }
    /** One player operation; lowered to a paid wait and an atomic paid claim. */
    public record TaskJoin(String sourceId, ModuleDefinition.Key procedure, String handle, String outcome) implements Step {
        public TaskJoin { name(sourceId); Objects.requireNonNull(procedure); name(handle); name(outcome); }
    }
    public static boolean hasAuthoredAssembly(List<Step> steps) { return steps.stream().anyMatch(s -> s instanceof Procedure p && p.assembly().isPresent()); }
    public boolean usesValueEqualityAssembly() {
        if (execution != Execution.REGISTER_V1) return false;
        if(authoredAny(steps,"VALUE.EQUALS","HAS.FIELD","VALUE.CHECK","FMT.CHECK"))return true;
        for (var step : steps) {
            String entry = recoveryExpressionEntry(step);
            if (entry == null || !expressions.program().entries().containsKey(entry)) continue;
            var graph = expressions.program().entries().get(entry).graph();
            var seen = new HashSet<String>(); var pending = new ArrayDeque<>(graph.outputs().values());
            while (!pending.isEmpty()) {
                String id = pending.removeFirst(); if (!seen.add(id)) continue;
                var node = graph.nodes().get(id);
                if (node instanceof ComputationGraph.ValueEquals || node instanceof ComputationGraph.ListSearch) return true;
                if (node != null) pending.addAll(node.dependencies());
            }
        }
        return false;
    }
    /** Bounded reconstruction is opt-in for new append and fixed text installations; legacy constructors keep their contract. */
    public boolean usesBoundedAssemblyRestore() {
        if (usesValueEqualityAssembly()) return true;
        if (execution != Execution.REGISTER_V1) return false;
        if (authoredAny(steps,"RAND.BELOW","LIST.INSERT","LIST.REMOVE","LIST.REPLACE","LIST.SLICE","LIST.APPEND","TEXT.NEXT","TEXT.CONCAT","TEXT.LETTER","TEXT.CONTAINS.CI")) return true;
        // Newly supported text and list reporters could not previously install through the ISA. Include direct argument graphs
        // without reclassifying historical list/record operand markers.
        for (var step : steps) {
            String entry = expressionEntry(step);
            var definition = entry == null ? null : expressions.program().entries().get(entry);
            if (definition != null && reachableValue(definition.graph(), node -> node instanceof ComputationGraph.ListPosition || node instanceof ComputationGraph.ListEdit || node instanceof ComputationGraph.Text text
                    && (text.operation() == TextOperation.CONCAT || text.operation() == TextOperation.CONTAINS_IGNORE_CASE || text.operation() == TextOperation.LETTER_OF))) return true;
        }
        return false;
    }
    public boolean usesMatchingAssembly() { return execution == Execution.REGISTER_V1 && (hasCorrelation(steps) || usesValueEqualityAssembly()); }
    private com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram softwareImage() { return compile(new ResourceLimits(Long.MAX_VALUE,Long.MAX_VALUE,1)).assembly().orElseThrow(); }
    public boolean usesConstructionAssembly() {
        if (compilation==Compilation.SOFTWARE_V1) return softwareImage().code().stream().anyMatch(c -> c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterNbt.Instruction n && (n.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterNbt.Kind.PACK || n.codec() || n.joining()) || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.MakeRecord || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.MakeList || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.AppendList || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterListEdit || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextConcat || c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextContains);
        if (execution != Execution.REGISTER_V1) return false;
        for (var step : steps) {
            if (step instanceof Procedure p && p.assembly().filter(a -> a.hasOpcode("TEXT.CONTAINS.CI") || a.hasOpcode("TEXT.CONCAT") || a.hasOpcode("BYTES.JOIN") || a.hasOpcode("VALUE.DECODE") || a.hasOpcode("VALUE.ENCODE") || a.hasOpcode("NBT.PACK") || a.hasOpcode("LIST.APPEND") || a.hasOpcode("LIST.INSERT") || a.hasOpcode("LIST.REMOVE") || a.hasOpcode("LIST.REPLACE") || a.hasOpcode("LIST.SLICE") || a.hasOpcode("LIST.MAKE") || a.hasOpcode("RECORD.MAKE")).isPresent()) return true;
            String entry = recoveryExpressionEntry(step);
            if (entry != null && expressions.program().entries().containsKey(entry)
                    && hasConstruction(expressions.program().entries().get(entry).graph())) return true;
        }
        return false;
    }
    /** Old owner markers are part of checkpoint identity. Do not reclassify historical operand-only
     * programs during this source merge; new reporter nodes have their own graph schema boundary. */
    private String recoveryExpressionEntry(Step step) {
        String entry = expressionEntry(step);
        if (step instanceof Invoke) {
            var definition = entry == null ? null : expressions.program().entries().get(entry);
            if (definition == null || !reachableValue(definition.graph(), node -> node instanceof ComputationGraph.ProcedureValue || node instanceof ComputationGraph.ListPosition || node instanceof ComputationGraph.ListSearch || node instanceof ComputationGraph.ListEdit
                    || node instanceof ComputationGraph.Text text && (text.operation() == TextOperation.CONCAT || text.operation() == TextOperation.CONTAINS_IGNORE_CASE))) return null;
        }
        return entry;
    }
    private static boolean hasConstruction(ComputationGraph graph) {
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!seen.add(id)) continue;
            var node = graph.nodes().get(id);
            if (node instanceof ComputationGraph.ListEdit || node instanceof ComputationGraph.MakeList || node instanceof ComputationGraph.MakeRecord
                    || node instanceof ComputationGraph.Text text && (text.operation() == TextOperation.CONCAT || text.operation() == TextOperation.CONTAINS_IGNORE_CASE)) return true;
            if (node != null) pending.addAll(node.dependencies());
        }
        return false;
    }
    private boolean usedEntryHas(java.util.function.Predicate<ComputationGraph.Node> operation) {
        var checked = new HashSet<String>();
        for (var step : steps) {
            String name = expressionEntry(step);
            if (name == null || !checked.add(name)) continue;
            var entry = expressions.program().entries().get(name);
            if (entry != null && reachable(entry.graph(), operation)) return true;
        }
        return false;
    }
    private static boolean reachable(ComputationGraph graph, java.util.function.Predicate<ComputationGraph.Node> operation) {
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!seen.add(id)) continue;
            var node = graph.nodes().get(id);
            if (node != null) {
                if (operation.test(node)) return true;
                pending.addAll(node.dependencies());
            }
        }
        return false;
    }
    public static boolean hasRequiredValue(ProgramArchive archive) {
        var graphs = new ArrayList<ComputationGraph>(); graphs.add(archive.program().graph());
        archive.program().entries().values().forEach(entry -> graphs.add(entry.graph()));
        archive.modules().values().forEach(module -> graphs.add(module.graph()));
        return graphs.stream().anyMatch(FlowProgram::requiredValue);
    }
    public static boolean requiredValue(ComputationGraph graph) {
        return graph.nodes().values().stream().anyMatch(node -> node instanceof ComputationGraph.Scalar scalar
            && scalar.operation() == ScalarOperation.REQUIRE_VALUE);
    }
    public static boolean hasRequiredValue(List<Step> steps) {
        return authoredAny(steps,"REQUIRE_VALUE");
    }
    public boolean usesRequiredValueAssembly() {
        return execution == Execution.REGISTER_V1 && (hasRequiredValue(expressions) || hasRequiredValue(steps) || compilation==Compilation.SOFTWARE_V1 && softwareImage().code().stream().anyMatch(c->c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Alu a && a.operation()==ScalarOperation.REQUIRE_VALUE));
    }
    public boolean usesAuthoredAssembly() { return execution == Execution.REGISTER_V1 && hasAuthoredAssembly(steps); }
    public static boolean hasTaskJoin(List<Step> steps) { return steps.stream().anyMatch(TaskJoin.class::isInstance); }
    public boolean usesTaskJoinAssembly() { return execution == Execution.REGISTER_V1 && hasTaskJoin(steps); }
    public static boolean hasTaskStart(List<Step> steps) { return steps.stream().anyMatch(TaskStart.class::isInstance); }
    /** First and only command in a dedicated, automatically admitted monitor procedure. */
    public record Watch(String sourceId, Optional<ModuleDefinition.Key> handler, FlowInput condition) implements Step {
        public Watch { name(sourceId); Objects.requireNonNull(handler); Objects.requireNonNull(condition); }
        public Watch(String sourceId, ModuleDefinition.Key handler, FlowInput condition) { this(sourceId, Optional.of(handler), condition); }
    }
    public static boolean hasWatch(List<Step> steps) { return steps.stream().anyMatch(Watch.class::isInstance); }
    public boolean usesWatch() { return execution == Execution.REGISTER_V1 && hasWatch(steps); }
    public Map<ModuleDefinition.Key, Watch> watches() {
        var result = new LinkedHashMap<ModuleDefinition.Key, Watch>();
        for (int i = 1; i < steps.size(); i++)
            if (steps.get(i) instanceof Watch watch && steps.get(i - 1) instanceof Procedure monitor)
                result.put(monitor.key(), watch);
        return Collections.unmodifiableMap(result);
    }
    public static boolean hasTryLaunch(List<Step> steps) { return steps.stream().anyMatch(TryLaunch.class::isInstance); }
    public boolean usesTryLaunch() { return execution == Execution.REGISTER_V1 && (hasTryLaunch(steps) || hasWatch(steps)); }
    public boolean usesTaskStartAssembly() { return execution == Execution.REGISTER_V1 && hasTaskStart(steps); }
    public static boolean hasInvokeOperands(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Invoke call && call.operands().isPresent());
    }
    public static boolean hasProcedures(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Procedure || step instanceof EndProcedure || step instanceof Invoke);
    }
    /** Ordinary data correlation, never an authorization token or implicit send. */
    public record Correlation(String sourceId, String local) implements Step {
        public Correlation { name(sourceId); name(local); }
    }
    public static boolean hasCorrelation(List<Step> steps) { return steps.stream().anyMatch(Correlation.class::isInstance) || authoredAny(steps,"CORRELATION"); }
    public record SerialSend(String sourceId, String port, String local, String result) implements Step {
        public SerialSend { name(sourceId); name(port); name(local); name(result); }
    }
    public record SerialReceive(String sourceId, String port, String local, boolean nativeNbt) implements Step {
        public SerialReceive { name(sourceId); name(port); name(local); }
        public SerialReceive(String sourceId, String port, String local) { this(sourceId, port, local, false); }
    }
    public static boolean hasNativeReceive(List<Step> steps) { return steps.stream().anyMatch(step -> step instanceof SerialReceive receive && receive.nativeNbt()); }
    public record SerialStatus(String sourceId, String port, String local, boolean claimReceiveFaults) implements Step {
        public SerialStatus { name(sourceId); name(port); name(local); }
        public SerialStatus(String sourceId, String port, String local) { this(sourceId, port, local, false); }
    }
    public static boolean hasFaultClaim(List<Step> steps) { return steps.stream().anyMatch(step -> step instanceof SerialStatus status && status.claimReceiveFaults()); }
    public static boolean serial(Step step) { return step instanceof SerialSend || step instanceof SerialReceive || step instanceof SerialStatus; }
    public static boolean hasSerial(List<Step> steps) { return steps.stream().anyMatch(FlowProgram::serial) || authoredAny(steps,"SERIAL.SEND","SERIAL.STATUS","SERIAL.RECEIVE"); }
    public record Read(String sourceId, String port, String local, DataType type, boolean result) implements Step {
        public Read { name(sourceId); name(port); name(local);
            if (!(type instanceof DataType.RecordType) && type != DataType.Scalar.ANY)
                throw new IllegalArgumentException("XBus input must be a named record or ANY"); }
        public Read(String sourceId, String port, String local, DataType.RecordType type) { this(sourceId, port, local, type, false); }
    }
    public record Write(String sourceId, String port, String local, Optional<String> result) implements Step {
        public Write { name(sourceId); name(port); name(local); Objects.requireNonNull(result); result.ifPresent(FlowProgram::name); }
        public Write(String sourceId, String port, String local) { this(sourceId, port, local, Optional.empty()); }
    }
    public record AwaitReadable(String sourceId, String port, Optional<String> result) implements Step {
        public AwaitReadable { name(sourceId); name(port); Objects.requireNonNull(result); result.ifPresent(FlowProgram::name); }
        public AwaitReadable(String sourceId, String port) { this(sourceId, port, Optional.empty()); }
    }
    public record SignalRead(String sourceId, String port, String local) implements Step {
        public SignalRead { name(sourceId); name(port); name(local); }
    }
    /** Nonblocking hardware status, not a payload read, acknowledgement or ownership request. */
    public record XBusStatus(String sourceId, String port, String local) implements Step {
        public XBusStatus { name(sourceId); name(port); name(local); }
    }
    public static boolean hasXBusStatus(List<Step> steps) { return steps.stream().anyMatch(XBusStatus.class::isInstance)||authoredAny(steps,"XBUS.STATUS"); }
    /** First versioned device slice; legacy sources do not silently acquire the new ISA semantics. */
    public boolean usesSignalAssembly() { return execution == Execution.REGISTER_V1 && (hasSignals(steps) || hasXBusStatus(steps)); }
    public boolean usesXBusAssembly() { return execution == Execution.REGISTER_V1 && (steps.stream().anyMatch(s -> s instanceof Read || s instanceof Write || s instanceof AwaitReadable) || authoredAny(steps,"XBUS.READ","XBUS.WRITE")); }
    public boolean usesSleepAssembly() { return execution == Execution.REGISTER_V1 && hasSleep(steps); }
    public boolean usesCallAssembly() { return execution == Execution.REGISTER_V1 && hasProcedures(steps); }
    public record SignalWrite(String sourceId, String port, String local) implements Step {
        public SignalWrite { name(sourceId); name(port); name(local); }
    }
    public record Monitor(String sourceId, String cell, boolean visible, boolean privateLocal) implements Step {
        public Monitor(String sourceId, String cell, boolean visible) { this(sourceId, cell, visible, false); }
        public Monitor { name(sourceId); name(cell); }
    }
    public static boolean hasMonitor(List<Step> steps) { return steps.stream().anyMatch(Monitor.class::isInstance); }
    public boolean usesMonitor() { return execution == Execution.REGISTER_V1 && hasMonitor(steps); }
    public boolean usesPrivateMonitors() {
        return usesMonitor() && steps.stream().anyMatch(step -> step instanceof Monitor monitor && monitor.privateLocal());
    }
    public boolean canShowPrivateMonitor(String name) {
        return usesMonitor() && steps.stream().anyMatch(step -> step instanceof Monitor monitor && monitor.privateLocal()
            && monitor.visible() && monitor.cell().equals(name));
    }
    public boolean canShowMonitor(String cell) {
        return expressions.program().cells().containsKey(cell) && steps.stream().anyMatch(
            step -> step instanceof Monitor monitor && !monitor.privateLocal() && monitor.visible() && monitor.cell().equals(cell));
    }


    public enum WaitUnit {
        TICKS(1), SECONDS(20), MINUTES(60 * 20);
        private final int ticks;
        WaitUnit(int ticks) { this.ticks = ticks; }
        public int ticks() { return ticks; }
    }
    public record Sleep(String sourceId, FlowInput ticks, Optional<WaitUnit> unit) implements Step {
        public Sleep { name(sourceId); Objects.requireNonNull(ticks); Objects.requireNonNull(unit); }
        public Sleep(String sourceId, FlowInput ticks) { this(sourceId, ticks, Optional.empty()); }
        public Sleep(String sourceId, String local) { this(sourceId, new FlowInput.Local(local)); }
        public WaitUnit displayedUnit() { return unit.orElse(WaitUnit.TICKS); }
        /** Compatibility-only path for existing named-local continuations. */
        public String ticksLocal() { return ((FlowInput.Local)ticks).name(); }
        public boolean accepts(DataType type) { return type == DataType.Scalar.INTEGER || unit.isPresent() && type == DataType.Scalar.DECIMAL; }
    }
    public static boolean hasWaitUnits(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Sleep sleep && sleep.unit().isPresent());
    }

    public record Evaluate(String sourceId, String expression, Optional<FlowCallBindings> bindings) implements Step {
        public Evaluate { name(sourceId); name(expression); Objects.requireNonNull(bindings); }
        /** Legacy same-name semantics, retained for installed programs and unedited documents. */
        public Evaluate(String sourceId, String expression) { this(sourceId, expression, Optional.empty()); }
        public Evaluate(String sourceId, String expression, FlowCallBindings bindings) { this(sourceId, expression, Optional.of(bindings)); }
        String argument(String port) { return bindings.map(value -> value.arguments().get(port)).orElse(port); }
        Map<String, String> returns(Set<String> ports) { return bindings.map(FlowCallBindings::returns).orElseGet(() -> FlowCallBindings.identity(ports)); }
    }
    public static boolean hasCallBindings(List<Step> steps) { return steps.stream().anyMatch(step -> step instanceof Evaluate call && call.bindings().isPresent()); }
    public record If(String sourceId, FlowInput condition) implements Step {
        public If { name(sourceId); Objects.requireNonNull(condition); }
        public If(String sourceId, String local) { this(sourceId, new FlowInput.Local(local)); }
    }
    public record Otherwise(String sourceId) implements Step { public Otherwise { name(sourceId); } }
    public record EndIf(String sourceId) implements Step { public EndIf { name(sourceId); } }
    public record WaitUntil(String sourceId, FlowInput condition) implements Step {
        public WaitUntil { name(sourceId); Objects.requireNonNull(condition); }
    }
    public static boolean hasWaitUntil(List<Step> steps) { return steps.stream().anyMatch(WaitUntil.class::isInstance); }

    public enum StopMode { CURRENT, OTHERS, ALL }
    public record StopScript(String sourceId, StopMode mode) implements Step {
        public StopScript(String sourceId) { this(sourceId, StopMode.CURRENT); }
        public StopScript { name(sourceId); Objects.requireNonNull(mode); }
        public boolean cap() { return mode != StopMode.OTHERS; }
    }
    public static boolean hasStopScript(List<Step> steps) { return steps.stream().anyMatch(StopScript.class::isInstance); }
    public boolean usesGroupStop() { return execution == Execution.REGISTER_V1 && hasGroupStop(steps); }
    public static boolean hasGroupStop(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof StopScript stop && stop.mode() != StopMode.CURRENT);
    }

    public record Forever(String sourceId) implements Step { public Forever { name(sourceId); } }
    public record EndForever(String sourceId) implements Step { public EndForever { name(sourceId); } }
    public record Repeat(String sourceId, FlowInput count) implements Step {
        public Repeat { name(sourceId); Objects.requireNonNull(count); }
        public Repeat(String sourceId, String local) { this(sourceId, new FlowInput.Local(local)); }
    }
    public record EndRepeat(String sourceId) implements Step { public EndRepeat { name(sourceId); } }
    public record Until(String sourceId, FlowInput condition) implements Step {
        public Until { name(sourceId); Objects.requireNonNull(condition); }
        public Until(String sourceId, String local) { this(sourceId, new FlowInput.Local(local)); }
    }
    public record EndUntil(String sourceId) implements Step { public EndUntil { name(sourceId); } }
    public static boolean hasRepeats(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Repeat || step instanceof EndRepeat);
    }
    public static boolean hasXBusResults(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Read read && read.result()
            || step instanceof Write write && write.result().isPresent()
            || step instanceof AwaitReadable wait && wait.result().isPresent());
    }
    public static boolean hasInputSlots(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Until || step instanceof EndUntil
            || step instanceof If branch && !(branch.condition() instanceof FlowInput.Local)
            || step instanceof Repeat repeat && !(repeat.count() instanceof FlowInput.Local));
    }
    public static FlowInput valueInput(Step step) {
        if(step instanceof Reference ref && ref.kind()==ReferenceKind.WRITE)return ref.value().orElseThrow();
        if (step instanceof Ask ask) return ask.question();
        if (step instanceof Sleep sleep) return sleep.ticks();
        if (step instanceof WaitUntil wait) return wait.condition();
        return step instanceof Repeat repeat ? repeat.count() : step instanceof Until until ? until.condition()
            : step instanceof Watch watch ? watch.condition()
            : step instanceof If branch ? branch.condition() : step instanceof Shared shared ? shared.value() : null;
    }
    public static boolean hasBoundInputs(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof If branch && !(branch.condition() instanceof FlowInput.Local)
            || valueInput(step) instanceof FlowInput.Expression expression && expression.arguments().isPresent());
    }
    public static boolean hasSleepValues(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof Sleep sleep && !(sleep.ticks() instanceof FlowInput.Local));
    }
    public static boolean hasLoops(List<Step> steps) {
        return hasRepeats(steps) || steps.stream().anyMatch(step -> step instanceof Forever || step instanceof EndForever || step instanceof Until || step instanceof EndUntil);
    }
    public static boolean hasBranches(List<Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof If || step instanceof Otherwise || step instanceof EndIf);
    }
    public static boolean hasSignals(List<Step> steps) {
        return steps.stream().anyMatch(s->s instanceof SignalRead||s instanceof SignalWrite)||authoredAny(steps,"SIO.READ","SIO.WRITE");
    }
    public static boolean hasSleep(List<Step> steps) { return steps.stream().anyMatch(step -> step instanceof Sleep || step instanceof WaitUntil); }
    record Installation(ProgramCompiler.InstalledProgram expressions, List<DataType.RecordType> locals, FlowBranches branches, FlowSignalModes signals,
            Optional<com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram> assembly) {
        Installation(ProgramCompiler.InstalledProgram expressions, List<DataType.RecordType> locals, FlowBranches branches, FlowSignalModes signals) {
            this(expressions, locals, branches, signals, Optional.empty());
        }
    }
    static final class Scope {
        final Map<String, DataType> before;
        Map<String, DataType> then;
        Scope(Map<String, DataType> before) { this.before = new LinkedHashMap<>(before); }
    }
    public void validate(ResourceLimits limits) { compile(limits); }
    /** Static installation costs, not an estimate of unknown input-dependent execution time or peak RAM. */
    public record Usage(long programUnits, long constantUnits, int registers) {}
    public Usage usage(ResourceLimits limits) {
        var installed = compile(limits);
        if (installed.assembly().isPresent()) {
            var image = installed.assembly().orElseThrow();
            return new Usage(image.codeWords(), image.constantUnits(), image.registerTypes().size());
        }
        var pure = installed.expressions().usage();
        return new Usage(Math.addExact(pure.programUnits(), steps.size()), Math.addExact(pure.constantUnits(), inputConstantUnits(steps)), 0);
    }
    /** True only after the normal checked image proves every instance of this synchronous source call concrete. */
    public DataValue.RecordValue displayLocals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState registers) {
        return softwareImage().displayLocals(registers);
    }
    public DataValue.RecordValue displayTaskLocals(ModuleDefinition.Key task,
            com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterState registers) {
        return softwareImage().forTask(task.id(), task.revision()).displayLocals(registers);
    }
    public boolean softwareSignatureAt(String sourceId,ResourceLimits limits) {
        if(compilation!=Compilation.SOFTWARE_V1)return false;
        var image=compile(limits).assembly().orElseThrow();
        for(int i=0;i<steps.size();i++)if(steps.get(i).sourceId().equals(sourceId)) {
            int source=i;
            return image.code().stream().filter(c->c.source()==source&&c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Call)
                .map(c->image.procedures().get(((com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Call)c.instruction()).target()))
                .anyMatch(p->java.util.stream.Stream.concat(p.inputs().values().stream(),p.outputs().values().stream()).anyMatch(image.softwareAnyRegisters()::contains));
        }
        return false;
    }
    public boolean closedSignatureAt(String sourceId, ResourceLimits limits) {
        if (compilation == Compilation.ORIGINAL) return false;
        for (int i=0;i<steps.size();i++) if (steps.get(i) instanceof Invoke call && call.sourceId().equals(sourceId)) {
            var image=compile(limits).assembly().orElseThrow();
            int position=i;
            var targets=image.code().stream().filter(code->code.source()==position)
                .map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Code::instruction)
                .filter(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Call.class::isInstance)
                .map(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Call.class::cast)
                .map(op->image.procedures().get(op.target())).toList();
            return !targets.isEmpty() && targets.stream().allMatch(target->java.util.stream.Stream.concat(
                target.inputs().values().stream(),target.outputs().values().stream())
                .noneMatch(reg->image.softwareAnyRegisters().contains(reg)||com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements.containsAny(image.registerTypes().get(reg))));
        }
        return false;
    }
    /** Static editor/debugger information, never a read of runtime locals. */
    public Map<String, DataType> typesBefore(String sourceId, ResourceLimits limits) {
        var installed = compile(limits);
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).sourceId().equals(sourceId)) return installed.locals().get(i).fields();
        throw error(ComputationException.Code.MISSING_REFERENCE, sourceId, "Unknown source ID");
    }
    Installation compile(ResourceLimits limits) {
        return compile(limits, Optional.empty());
    }
    /** The code and source addresses stay unchanged; only continuation reachability has a different root. */
    Installation compile(ResourceLimits limits, Optional<ModuleDefinition.Key> taskEntry) {
        if (execution == Execution.REGISTER_V1) {
            return FlowInstallations.compile(this, limits, taskEntry);
        }
        return checkSource(limits, taskEntry, false);
    }
    /** Cache misses retain the original validation order and capacity checks. No checkpoint is cached. */
    Installation compileRegister(ResourceLimits limits, Optional<ModuleDefinition.Key> taskEntry) {
        if (compilation == Compilation.SOFTWARE_V1) return FlowSoftwareAny.compile(this, limits, taskEntry);
        if (compilation == Compilation.CLOSED_V1 && FlowSpecializations.needed(steps))
            return FlowSpecializations.compile(this, limits, taskEntry);
        var source = new FlowProgram(inputs, steps, expressions);
        var checked = source.checkSource(new ResourceLimits(Long.MAX_VALUE, limits.memoryUnits(), limits.workPerSlice()), taskEntry, true, limits.programUnits());
        var image = FlowAssemblyCompiler.compile(source, checked, limits.programUnits(), compilation == Compilation.COMPACT_V1);
        if (taskEntry.isPresent()) image = image.forTask(taskEntry.orElseThrow().id(), taskEntry.orElseThrow().revision());
        if (image.codeWords() > limits.programUnits()) throw error(ComputationException.Code.PROGRAM_LIMIT, "assembly", "Encoded assembly exceeds code-word capacity");
        return new Installation(checked.expressions(), checked.locals(), checked.branches(), checked.signals(), Optional.of(image));
    }
    private Installation checkSource(ResourceLimits limits, Optional<ModuleDefinition.Key> taskEntry, boolean assembly) {
        return checkSource(limits,taskEntry,assembly,limits.programUnits());
    }
    private Installation checkSource(ResourceLimits limits, Optional<ModuleDefinition.Key> taskEntry, boolean assembly, long codeLimit) {
        var branches = checkedStructure(taskEntry,assembly,codeLimit);
        if (!assembly) for (var step : steps) {
            String entry = expressionEntry(step);
            var definition = entry == null ? null : expressions.program().entries().get(entry);
            if (definition != null && reachableValue(definition.graph(), node -> node instanceof ComputationGraph.ListSearch || node instanceof ComputationGraph.ListPosition))
                throw error(ComputationException.Code.INVALID_GRAPH, step.sourceId(), "List search and special positions require typed ISA execution");
        }
        var definition = expressions.program();
        var compiled = compileExpressions(limits, limits.memoryUnits());
        var fields = new LinkedHashMap<>(inputs);
        var scopes = new ArrayDeque<Scope>();
        var schemas = new ArrayList<DataType.RecordType>();
        schemas.add(new DataType.RecordType(fields));
        for (int position = 0; position < steps.size(); position++) {
            var step = steps.get(position);
            checkStep(position, step, fields, scopes, branches, definition, compiled, assembly);
            schemas.add(new DataType.RecordType(fields));
        }
        taskEntry.ifPresent(key -> schemas.set(steps.size(), new DataType.RecordType(((Procedure)steps.get(branches.procedure(key))).outputs())));
        return new Installation(compiled, List.copyOf(schemas), branches, FlowSignalModes.compile(steps, branches));
    }
    /** Value calls only use fixed concrete local procedure signatures; no implicit ANY conversion or reference ABI. */
    public static Map<ModuleDefinition.Key, ProgramCompiler.ProcedureSignature> valueProcedures(Collection<Procedure> definitions) {
        var result = new LinkedHashMap<ModuleDefinition.Key, ProgramCompiler.ProcedureSignature>();
        for (var procedure : definitions) if (supportsValueCall(procedure))
            result.put(procedure.key(), new ProgramCompiler.ProcedureSignature(procedure.inputs(), procedure.outputs()));
        return Map.copyOf(result);
    }
    public static boolean supportsValueCall(Procedure procedure) {
        return !procedure.outputs().isEmpty() && java.util.stream.Stream.concat(procedure.inputs().values().stream(), procedure.outputs().values().stream())
            .noneMatch(type -> LocalReferences.contains(type) || com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements.containsAny(type));
    }
    Map<ModuleDefinition.Key, ProgramCompiler.ProcedureSignature> valueProcedures() {
        return valueProcedures(steps.stream().filter(Procedure.class::isInstance).map(Procedure.class::cast).toList());
    }
    static String expressionEntry(Step step) {
        if (step instanceof Evaluate evaluate) return evaluate.expression();
        if (step instanceof Invoke invoke) return invoke.operands().map(InvokeOperands::entry).orElse(null);
        return valueInput(step) instanceof FlowInput.Expression input ? input.entry() : null;
    }
    static Map<String, DataType> argumentTypes(Invoke call, Map<String, DataType> signature, Map<String, DataType> fields,
                                               ProgramCompiler.InstalledProgram compiled) {
        var actual = new TreeMap<String, DataType>();
        if (call.operands().isPresent()) {
            var operands = call.operands().orElseThrow();
            var entry = compiled.definition().entries().get(operands.entry());
            if (entry == null)
                throw error(ComputationException.Code.MISSING_REFERENCE, call.sourceId(), "Missing procedure operand graph");
            if (!entry.writes().isEmpty() || !entry.sends().isEmpty())
                throw error(ComputationException.Code.INVALID_GRAPH, call.sourceId(), "An operand graph cannot declare autonomous effects");
            var graph = compiled.select(operands.entry()).root();
            if (!operands.inputs().keySet().equals(graph.graph().inputs().keySet()))
                throw error(ComputationException.Code.MISSING_REFERENCE, call.sourceId(), "Incomplete operand bindings");
            graph.graph().inputs().forEach((name, type) -> {
                var local = fields.get(operands.inputs().get(name));
                if (local == null || !type.compatibleWith(local))
                    throw error(ComputationException.Code.TYPE_MISMATCH, call.sourceId(), "Operand input does not match local: " + name);
            });
            actual.putAll(graph.outputType().fields());
        } else call.bindings().arguments().forEach((port, local) -> actual.put(port, fields.get(local)));
        if (!signature.keySet().equals(actual.keySet()))
            throw error(ComputationException.Code.MISSING_REFERENCE, call.sourceId(), "Call arguments differ from declared ports");
        signature.forEach((port, type) -> {
            var got = actual.get(port);
            if (got == null || !type.compatibleWith(got))
                throw error(ComputationException.Code.TYPE_MISMATCH, call.sourceId(), "Procedure input does not match port: " + port);
        });
        return actual;
    }
    FlowBranches checkedStructure(Optional<ModuleDefinition.Key> taskEntry, boolean assembly, long codeLimit) {
        if (!assembly && hasInvokeOperands(steps)) throw error(ComputationException.Code.INVALID_GRAPH, "call", "Inline procedure operands require typed ISA execution");
        if (!assembly && hasSleepValues(steps)) throw error(ComputationException.Code.INVALID_GRAPH, "sleep", "Sleep value slots require typed ISA execution");
        if (!assembly && usedEntryHas(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation().numericEdge()))
            throw error(ComputationException.Code.INVALID_GRAPH, "flow", "Numeric edges require typed ISA execution");
        if (!assembly && usedEntryHas(ComputationGraph.RandomRange.class::isInstance))
            throw error(ComputationException.Code.INVALID_GRAPH, "flow", "Random range requires typed ISA execution");
        if (!assembly && usedEntryHas(ComputationGraph.Timer.class::isInstance)) throw error(ComputationException.Code.INVALID_GRAPH, "timer", "Timer requires typed ISA execution");
        if (!assembly && usedEntryHas(ComputationGraph.Answer.class::isInstance)) throw error(ComputationException.Code.INVALID_GRAPH,"terminal","Terminal answer requires typed ISA execution");
        if (!assembly && usedEntryHas(ComputationGraph.KeyDown.class::isInstance)) throw error(ComputationException.Code.INVALID_GRAPH,"terminal","Keyboard observations require typed ISA execution");
        if (!assembly && hasTerminalKeys(steps)) throw error(ComputationException.Code.INVALID_GRAPH,"terminal","Key hats require typed ISA execution");
        if (!assembly && (hasTryLaunch(steps) || hasWatch(steps))) throw error(ComputationException.Code.INVALID_GRAPH,"tasks","Try-launch requires typed ISA execution");
        if (!assembly && hasStartupHats(steps)) throw error(ComputationException.Code.INVALID_GRAPH,"startup","Startup scripts require typed ISA execution");
        if (!assembly && hasEvents(steps)) throw error(ComputationException.Code.INVALID_GRAPH,"events","Broadcast requires typed ISA execution");
        FlowReferenceRules.source(this,assembly);
        taskEntry.ifPresent(key->{for(var step:steps)if(step instanceof Procedure p&&p.key().equals(key)&&(p.inputs().values().stream().anyMatch(LocalReferences::contains)||p.outputs().values().stream().anyMatch(LocalReferences::contains)))throw error(ComputationException.Code.TYPE_MISMATCH,p.sourceId(),"Reference task ABI is not supported");});
        if (!assembly && hasRequiredValue(expressions)) throw error(ComputationException.Code.INVALID_GRAPH,"assembly","Require value requires typed ISA execution");
        if(!assembly && hasAuthoredAssembly(steps))throw error(ComputationException.Code.INVALID_GRAPH,"assembly","Authored assembly requires register execution");
        var authored=new HashMap<ModuleDefinition.Key,com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.Image>();
        for(var step:steps)if(step instanceof Procedure p && p.assembly().isPresent()) authored.put(p.key(),
            com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.assemble(p.assembly().orElseThrow(),p.inputs(),p.outputs(),p.returns(),com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES,codeLimit,expressions.program().cells()));
        if (!assembly && (hasTaskStart(steps) || hasTaskJoin(steps))) throw error(ComputationException.Code.INVALID_GRAPH, "tasks", "Task operations require typed ISA execution");
        var definition = expressions.program();
        if (!assembly && (!definition.cells().isEmpty() || steps.stream().anyMatch(s -> s instanceof Memory || s instanceof Atomic || s instanceof Shared))
            || !definition.writes().isEmpty() || !definition.sends().isEmpty()
            || !definition.graph().inputs().isEmpty() || !definition.graph().nodes().isEmpty() || !definition.graph().outputs().isEmpty()
            || definition.entries().values().stream().anyMatch(entry -> !entry.writes().isEmpty() || !entry.sends().isEmpty()))
            throw error(ComputationException.Code.INVALID_GRAPH, "flow", "Expressions must be pure named graphs, without RAM or sends");
        var reporterCalls = new HashMap<Integer, Set<ModuleDefinition.Key>>();
        for (int i = 0; i < steps.size(); i++) {
            var targets = reporterTargets(steps.get(i));
            if (targets.isEmpty()) continue;
            if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, steps.get(i).sourceId(), "Procedure values require typed ISA execution");
            reporterCalls.put(i, targets);
        }
        var branches = FlowBranches.compile(steps, taskEntry, reporterCalls);
        FlowNestingValidator.validate(this, branches, authored);
        return branches;
    }
    void checkStep(int position, Step step, Map<String, DataType> fields, Deque<Scope> scopes,
            FlowBranches branches, ProgramDefinition definition, ProgramCompiler.InstalledProgram compiled, boolean assembly) {
        FlowReferenceRules.step(this,step,fields);
        switch (step) {
            case Reference ref -> {
                if(!assembly)throw error(ComputationException.Code.INVALID_GRAPH,ref.sourceId(),"References require typed ISA");
                if(ref.kind()==ReferenceKind.ADDRESS){var cell=definition.cells().get(ref.target());if(cell==null)throw error(ComputationException.Code.MISSING_REFERENCE,ref.sourceId(),"Unknown public cell");if(!(cell.type() instanceof DataType.Scalar scalar)||scalar!=DataType.Scalar.INTEGER&&scalar!=DataType.Scalar.BOOLEAN&&scalar!=DataType.Scalar.TEXT&&scalar!=DataType.Scalar.NBT)throw error(ComputationException.Code.TYPE_MISMATCH,ref.sourceId(),"Unsupported reference target");fields.put(ref.local(),new DataType.RefType(scalar,true));}
                else {if(!(fields.get(ref.target()) instanceof DataType.RefType type))throw error(ComputationException.Code.TYPE_MISMATCH,ref.sourceId(),"Expected local reference");
                    switch(ref.kind()){
                        case READ->fields.put(ref.local(),type.target());
                        case READONLY->fields.put(ref.local(),new DataType.RefType(type.target(),false));
                        case WRITE->{if(!type.writable())throw error(ComputationException.Code.TYPE_MISMATCH,ref.sourceId(),"Read-only reference");validateSharedInput(new Shared(ref.sourceId(),ref.target(),ref.value().orElseThrow(),false),type.target(),fields,compiled);}
                        case SAME->{var input=ref.value().orElseThrow();if(!(input instanceof FlowInput.Local other)||!(fields.get(other.name()) instanceof DataType.RefType))throw error(ComputationException.Code.TYPE_MISMATCH,ref.sourceId(),"Identity comparison requires references");fields.put(ref.local(),DataType.Scalar.BOOLEAN);}
                        default->throw new AssertionError();
                    }
                }
            }
            case Monitor monitor -> {
                if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, monitor.sourceId(), "Monitor requires typed ISA execution");
                if (monitor.privateLocal() ? !fields.containsKey(monitor.cell()) : !compiled.cells().containsKey(monitor.cell()))
                    throw error(ComputationException.Code.MISSING_FIELD, monitor.sourceId(),
                        "Unknown " + (monitor.privateLocal() ? "private" : "public") + " variable: " + monitor.cell());
            }
            case Broadcast ignored -> {}
            case Atomic ignored -> {}
            case EndAtomic ignored -> fields.put(((Atomic)steps.get(branches.start(position))).result(),
                com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory.RESULT_TYPE);
            case Shared shared -> {
                var cell = definition.cells().get(shared.cell());
                if (cell == null) throw error(ComputationException.Code.MISSING_REFERENCE, shared.sourceId(), "Unknown public cell: " + shared.cell());
                if (shared.add() && cell.type() != DataType.Scalar.INTEGER && cell.type() != DataType.Scalar.DECIMAL)
                    throw error(ComputationException.Code.TYPE_MISMATCH, shared.sourceId(), "Public change requires an integer or decimal cell");
                if (shared.value() instanceof FlowInput.Expression expression && expression.arguments().isEmpty()
                        && definition.entries().containsKey(expression.entry())
                        && !compiled.select(expression.entry()).root().graph().inputs().isEmpty())
                    throw error(ComputationException.Code.MISSING_REFERENCE, shared.sourceId(), "Public value expressions require explicit argument bindings");
                validateSharedInput(shared, cell.type(), fields, compiled);
                if (shared.listResult().isPresent()) {
                    var value = (FlowInput.Expression)shared.value();
                    listSnapshot(shared, compiled.select(value.entry()).root().graph());
                    fields.put(shared.listResult().orElseThrow(),
                        com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory.RESULT_TYPE);
                }
            }
            case Memory memory -> {
                if (memory.kind() == MemoryKind.READ || memory.kind() == MemoryKind.WRITE) {
                    var cell = definition.cells().get(memory.cell());
                    if (cell == null) throw error(ComputationException.Code.MISSING_REFERENCE, memory.sourceId(), "Unknown local memory cell: " + memory.cell());
                    if (memory.kind() == MemoryKind.READ) fields.put(memory.local(), cell.type());
                    else if (!fields.containsKey(memory.local()) || !cell.type().compatibleWith(fields.get(memory.local())))
                        throw error(ComputationException.Code.TYPE_MISMATCH, memory.sourceId(), "Memory write does not match cell type");
                } else if (memory.kind() == MemoryKind.COMMIT) fields.put(memory.local(),
                    com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory.RESULT_TYPE);
            }
            case Procedure procedure -> {
                scopes.push(new Scope(fields)); fields.clear(); fields.putAll(procedure.inputs());
                if (procedure.assembly().isPresent()) {
                    if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH,procedure.sourceId(),"Authored assembly requires register execution");
                    if (branches.end(position) != position + 1) throw error(ComputationException.Code.INVALID_GRAPH,procedure.sourceId(),"Assembly procedure cannot contain visual blocks");
                    // Assembly locals are declarations, not initialized task-entry inputs.
                    // Their output/dataflow proof already comes from the actual ISA verifier.
                }
            }
            case EndProcedure end -> {
                var procedure = (Procedure)steps.get(branches.start(position));
                if(procedure.assembly().isEmpty())procedure.outputs().forEach((port, type) -> {
                    var local = procedure.returns().get(port);
                    if (!fields.containsKey(local) || !type.compatibleWith(fields.get(local)))
                        throw error(ComputationException.Code.TYPE_MISMATCH, end.sourceId(), "Procedure return does not match local: " + port);
                });
                var before = scopes.pop().before; fields.clear(); fields.putAll(before);
            }
            case TaskJoin join -> {
                var procedure = (Procedure)steps.get(branches.procedure(join.procedure()));
                if (procedure.startup() || procedure.terminalKey().isPresent() || watches().containsKey(procedure.key())) throw error(ComputationException.Code.INVALID_GRAPH,procedure.sourceId(),"Event scripts cannot be called or explicitly launched");
                if (!com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE.equals(fields.get(join.handle())))
                    throw error(ComputationException.Code.TYPE_MISMATCH, join.sourceId(), "Task join requires a typed local handle");
                fields.put(join.outcome(), com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome.type(procedure.outputs()));
            }
            case Watch watch -> {
                int owner = branches.owner(position);
                if (!assembly || owner < 0 || owner + 1 != position || branches.end(owner) != position + 1
                        || !(steps.get(owner) instanceof Procedure monitor) || !monitor.inputs().isEmpty()
                        || !monitor.outputs().isEmpty() || monitor.event().isPresent() || monitor.terminalKey().isPresent() || monitor.startup())
                    throw error(ComputationException.Code.INVALID_GRAPH, watch.sourceId(), "A watch must be the sole body of a dedicated monitor");
                var key = watch.handler().orElseThrow(() ->
                    error(ComputationException.Code.MISSING_REFERENCE, watch.sourceId(), "Choose a handler for this event hat"));
                FlowTasks.requireSupported(this, monitor.key());
                checkLaunchTarget(branches, key, watch.sourceId());
                watch.condition().validate(DataType.Scalar.BOOLEAN, fields, compiled, watch.sourceId());
            }
            case TryLaunch launch -> {
                checkLaunchTarget(branches, launch.procedure(), launch.sourceId());
                fields.put(launch.started(), DataType.Scalar.BOOLEAN);
            }
            case TaskStart launch -> {
                var procedure = (Procedure)steps.get(branches.procedure(launch.procedure()));
                if (procedure.startup() || procedure.terminalKey().isPresent() || watches().containsKey(procedure.key())) throw error(ComputationException.Code.INVALID_GRAPH,procedure.sourceId(),"Event scripts cannot be called or explicitly launched");
                new FlowCallBindings(launch.arguments(), Map.of()).validate(procedure.inputs(), Map.of(), fields, launch.sourceId());
                fields.put(launch.handle(), com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE);
            }
            case Invoke call -> {
                var procedure = (Procedure)steps.get(branches.procedure(call.procedure()));
                if (procedure.startup() || procedure.terminalKey().isPresent() || watches().containsKey(procedure.key())) throw error(ComputationException.Code.INVALID_GRAPH,procedure.sourceId(),"Event scripts cannot be called or explicitly launched");
                argumentTypes(call, procedure.inputs(), fields, compiled);
                call.bindings().validateReturns(procedure.outputs(), fields, call.sourceId());
                call.bindings().returns().forEach((port, local) -> fields.put(local, procedure.outputs().get(port)));
            }
            case Correlation tag -> fields.put(tag.local(), DataType.Scalar.TEXT);
            case SerialSend send -> {
                if (!(fields.get(send.local()) instanceof DataType.RecordType) && fields.get(send.local()) != DataType.Scalar.ANY && fields.get(send.local()) != DataType.Scalar.NBT)
                    throw error(ComputationException.Code.TYPE_MISMATCH, send.sourceId(), "Serial send requires a record, native NBT or dynamic value local");
                fields.put(send.result(), SerialResult.SEND);
            }
            case SerialReceive receive -> fields.put(receive.local(), SerialResult.RECEIVE);
            case SerialStatus status -> fields.put(status.local(), SerialResult.STATUS);
            case Read read -> fields.put(read.local(), read.result() ? XBusResult.readType(read.type()) : read.type());
            case TimerReset reset -> {
                if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, reset.sourceId(), "Timer reset requires typed ISA execution");
            }
            case Ask ask -> {
                if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, ask.sourceId(), "Terminal prompt requires typed ISA execution");
                ask.question().validate(DataType.Scalar.TEXT, fields, compiled, ask.sourceId());
            }
            case SignalRead read -> fields.put(read.local(), DataType.Scalar.INTEGER);
            case XBusStatus status -> fields.put(status.local(), DataType.Scalar.INTEGER);
            case SignalWrite write -> {
                if (fields.get(write.local()) != DataType.Scalar.INTEGER)
                    throw error(ComputationException.Code.TYPE_MISMATCH, write.sourceId(), "Signal output requires a declared integer local");
            }
            case Write write -> {
                if (!(fields.get(write.local()) instanceof DataType.RecordType))
                    throw error(ComputationException.Code.TYPE_MISMATCH, write.sourceId(), "Write requires a declared record local");
                write.result().ifPresent(name -> fields.put(name, XBusResult.type()));
            }
            case AwaitReadable wait -> wait.result().ifPresent(name -> fields.put(name, XBusResult.type()));
            case StopScript stop -> {
                if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, stop.sourceId(), "Stop script requires typed ISA execution");
            }
            case WaitUntil wait -> {
                if (!assembly) throw error(ComputationException.Code.INVALID_GRAPH, wait.sourceId(), "Wait until requires typed ISA execution");
                wait.condition().validate(DataType.Scalar.BOOLEAN, fields, compiled, wait.sourceId());
            }
            case Sleep sleep -> {
                if (!assembly && (sleep.unit().isPresent() || !(sleep.ticks() instanceof FlowInput.Local)))
                    throw error(ComputationException.Code.INVALID_GRAPH, sleep.sourceId(), "Inline wait requires typed ISA execution");
                if (!sleep.accepts(sleep.ticks().type(fields, compiled, sleep.sourceId())))
                    throw error(ComputationException.Code.TYPE_MISMATCH, sleep.sourceId(),
                        sleep.unit().isPresent() ? "Wait duration requires a number" : "Input requires INTEGER");
            }
            case If branch -> {
                branch.condition().validate(DataType.Scalar.BOOLEAN, fields, compiled, branch.sourceId());
                scopes.push(new Scope(fields));
            }
            case Otherwise ignored -> {
                var scope = scopes.peek(); scope.then = new LinkedHashMap<>(fields);
                fields.clear(); fields.putAll(scope.before);
            }
            case EndIf end -> {
                var scope = scopes.pop(); var other = scope.then == null ? scope.before : scope.then;
                for (var entry : fields.entrySet()) {
                    if (other.containsKey(entry.getKey()) && !entry.getValue().equals(other.get(entry.getKey())))
                        throw error(ComputationException.Code.TYPE_MISMATCH, end.sourceId(), "Branch output types differ: " + entry.getKey());
                }
                fields.keySet().retainAll(other.keySet());
            }
            case Forever ignored -> scopes.push(new Scope(fields));
            case Repeat repeat -> {
                repeat.count().validate(DataType.Scalar.INTEGER, fields, compiled, repeat.sourceId());
                scopes.push(new Scope(fields));
            }
            case EndForever end -> closeLoop(fields, scopes.pop().before, end.sourceId());
            case EndRepeat end -> closeLoop(fields, scopes.pop().before, end.sourceId());
            case Until until -> {
                until.condition().validate(DataType.Scalar.BOOLEAN, fields, compiled, until.sourceId());
                scopes.push(new Scope(fields));
            }
            case EndUntil end -> closeLoop(fields, scopes.pop().before, end.sourceId());
            case Evaluate evaluate -> {
                if (!definition.entries().containsKey(evaluate.expression()))
                    throw error(ComputationException.Code.MISSING_REFERENCE, evaluate.sourceId(), "Unknown expression");
                var graph = compiled.select(evaluate.expression()).root();
                evaluate.bindings().ifPresent(bindings -> bindings.validate(graph.graph().inputs(), graph.outputType().fields(), fields, evaluate.sourceId()));
                graph.graph().inputs().forEach((name, type) -> {
                    String local = evaluate.argument(name);
                    if (!fields.containsKey(local) || !type.compatibleWith(fields.get(local)))
                        throw error(ComputationException.Code.TYPE_MISMATCH, evaluate.sourceId(), "Expression input does not match local: " + name + " <- " + local);
                });
                evaluate.returns(graph.outputType().fields().keySet()).forEach((port, local) -> fields.put(local, graph.outputType().fields().get(port)));
            }
        }
    }
    /** Public SET needs a static assignment proof, not exact identity or value-dependent narrowing.
     * Keep legacy control-input validation unchanged; ADD retains exact numeric operand types. */
    record ListSnapshot(String read, String position, String index) {
        boolean defers(String id) { return id.equals(read) || id.equals(position) || id.equals(index); }
    }
    static ListSnapshot listSnapshot(Shared shared, ComputationGraph graph) {
        var value = (FlowInput.Expression)shared.value();
        String root = graph.outputs().get(value.output());
        if (graph.outputs().size() == 1 && graph.nodes().get(root) instanceof ComputationGraph.ListEdit edit) {
            String source = edit.arguments().get("source");
            String position = null, index = edit.arguments().get("index");
            if (index != null && graph.nodes().get(index) instanceof ComputationGraph.Scalar offset
                    && offset.operation() == ScalarOperation.SUBTRACT_INTEGER
                    && graph.nodes().get(offset.arguments().get("right")) instanceof ComputationGraph.Constant one
                    && one.value().equals(new DataValue.IntegerValue(1))
                    && graph.nodes().get(offset.arguments().get("left")) instanceof ComputationGraph.ListPosition selector
                    && selector.source().equals(source) && selector.insertion() == (edit.operation() == ListOperation.INSERT))
                position = offset.arguments().get("left");
            if (graph.nodes().get(source) instanceof ComputationGraph.ReadMemory read && read.cell().equals(shared.cell())
                && consumedOnlyBy(graph, source, root, position)
                && edit.arguments().entrySet().stream().noneMatch(entry ->
                    !entry.getKey().equals("source") && entry.getValue().equals(source))) {
                if (position == null) return new ListSnapshot(source, null, null);
                String deferredIndex = index;
                if (consumedOnlyBy(graph, position, index, null) && consumedOnlyBy(graph, index, root, null)
                    && edit.arguments().entrySet().stream().noneMatch(entry ->
                        !entry.getKey().equals("index") && entry.getValue().equals(deferredIndex)))
                    return new ListSnapshot(source, position, index);
            }
        }
        throw error(ComputationException.Code.INVALID_GRAPH, shared.sourceId(),
            "Public list edit requires its own read of the selected list as the final operation's source");
    }
    private static boolean consumedOnlyBy(ComputationGraph graph, String value, String first, String second) {
        return graph.nodes().entrySet().stream().noneMatch(entry -> !entry.getKey().equals(first)
            && !entry.getKey().equals(second) && entry.getValue().dependencies().contains(value));
    }
    private static void validateSharedInput(Shared shared, DataType cellType, Map<String, DataType> fields,
            ProgramCompiler.InstalledProgram compiled) {
        DataType actual = switch (shared.value()) {
            case FlowInput.Literal literal -> DataType.typeOf(literal.value());
            case FlowInput.Local local -> fields.get(local.name());
            case FlowInput.Expression expression -> {
                if (!compiled.definition().entries().containsKey(expression.entry()))
                    throw error(ComputationException.Code.MISSING_REFERENCE, shared.sourceId(), "Unknown input expression: " + expression.entry());
                var output = compiled.select(expression.entry()).root().outputType().fields().get(expression.output());
                if (output == null) throw error(ComputationException.Code.MISSING_REFERENCE, shared.sourceId(), "Unknown input expression output: " + expression.output());
                yield output;
            }
        };
        if (actual == null) throw error(ComputationException.Code.TYPE_MISMATCH, shared.sourceId(), "Unknown public value local");
        // Reuse existing parameter-binding and local validation against the proven source type.
        shared.value().validate(actual, fields, compiled, shared.sourceId());
        boolean assignable = shared.add() ? cellType.equals(actual)
            : com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterAssignment.permits(cellType, actual);
        if (!assignable) throw error(ComputationException.Code.TYPE_MISMATCH, shared.sourceId(), "Public value does not fit declared cell type");
    }
    private static void closeLoop(Map<String, DataType> fields, Map<String, DataType> before, String source) {
        for (var entry : before.entrySet()) if (!entry.getValue().equals(fields.get(entry.getKey())))
            throw error(ComputationException.Code.TYPE_MISMATCH, source, "Loop-carried local type differs: " + entry.getKey());
        fields.keySet().retainAll(before.keySet());
    }
    Set<ModuleDefinition.Key> reporterTargets(Step step) {
        String entry = expressionEntry(step);
        var definition = entry == null ? null : expressions.program().entries().get(entry);
        if (definition == null) return Set.of();
        var targets = new LinkedHashSet<ModuleDefinition.Key>();
        reachableValue(definition.graph(), node -> {
            if (node instanceof ComputationGraph.ProcedureValue call) targets.add(call.procedure());
            return false;
        });
        return targets;
    }
    Set<ModuleDefinition.Key> synchronousTargets(Step step) {
        var targets = new LinkedHashSet<>(reporterTargets(step));
        if (step instanceof Invoke call) targets.add(call.procedure());
        return targets;
    }
    private static boolean reachableValue(ComputationGraph graph, java.util.function.Predicate<ComputationGraph.Node> visit) {
        var seen = new HashSet<String>(); var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) {
            var id = pending.removeFirst(); if (!seen.add(id)) continue;
            var node = graph.nodes().get(id);
            if (node != null) { if (visit.test(node)) return true; pending.addAll(node.dependencies()); }
        }
        return false;
    }
    ProgramCompiler.InstalledProgram compileExpressions(ResourceLimits limits, long memory) {
        if (steps.size() > limits.programUnits())
            throw error(ComputationException.Code.PROGRAM_LIMIT, "flow", "Control instructions exceed program capacity");
        long remaining = memory - inputConstantUnits(steps);
        if (remaining < 0) throw error(ComputationException.Code.MEMORY_LIMIT, "flow", "Input literals exceed memory capacity");
        return ProgramCompiler.compileFlow(expressions.program(), expressions.modules(),
            new ResourceLimits(limits.programUnits() - steps.size(), remaining, 1), valueProcedures());
    }
    public static long inputConstantUnits(List<Step> steps) {
        long units = 0;
        try {
            for (var step : steps) {
                FlowInput input = valueInput(step);
                if (input instanceof FlowInput.Literal literal) units = Math.addExact(units, literal.value().storageUnits());
            }
        } catch (ArithmeticException overflow) { throw error(ComputationException.Code.MEMORY_LIMIT, "flow", "Input literal size exceeds supported range"); }
        return units;
    }
    private void checkLaunchTarget(FlowBranches branches, ModuleDefinition.Key key, String sourceId) {
        var target = (Procedure) steps.get(branches.procedure(key));
        if (target.startup() || target.terminalKey().isPresent() || target.event().isPresent() || !target.inputs().isEmpty()
                || !target.outputs().isEmpty() || watches().containsKey(key))
            throw error(ComputationException.Code.INVALID_GRAPH, sourceId, "Choose an ordinary local handler without arguments or results");
        // Keep the same task hardware restrictions as an explicit launch.
        FlowTasks.requireSupported(this, key);
    }
    static void name(String name) {
        if (name == null || name.isBlank()) throw error(ComputationException.Code.INVALID_GRAPH, "flow", "Blank flow name");
    }
    static ComputationException error(ComputationException.Code code, String source, String message) {
        return new ComputationException(code, source, message);
    }
}
