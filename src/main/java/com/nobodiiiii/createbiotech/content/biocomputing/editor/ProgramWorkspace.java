package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import java.util.*;

/** One isolated mutable editing workspace. */
public final class ProgramWorkspace {
    private Optional<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition> terminal;
    private Optional<FlowDocumentBody> flow;
    private ProgramDraft root;
    private ScratchClipboard scratchClipboard;
    private final LinkedHashMap<String, ProgramDraft> entries = new LinkedHashMap<>();
    private final LinkedHashMap<ModuleDefinition.Key, ProgramDraft> modules = new LinkedHashMap<>();

    public ProgramWorkspace() {
        this(ProgramDocument.of(new ProgramArchive(new ProgramDefinition(
            new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of())));
    }
    public ProgramWorkspace(ProgramDocument document) {
        Objects.requireNonNull(document);
        terminal = document.terminal();
        flow = document.flow();
        var archive = document.archive();
        var declarations = new LinkedHashMap<>(archive.program().cells());
        root = new ProgramDraft(archive.program().graph(), declarations, archive.program().writes(), archive.program().sends(),
            archive.modules(), true, document.positions().getOrDefault(ProgramDocument.graphId(null), Map.of()), Map.of());
        archive.program().entries().forEach((name, entry) -> entries.put(name,
            new ProgramDraft(entry.graph(), declarations, entry.writes(), entry.sends(), archive.modules(), true,
                document.positions().getOrDefault(ProgramDocument.entryGraphId(name), Map.of()), Map.of())));
        archive.modules().forEach((key, module) -> modules.put(key, new ProgramDraft(module.graph(), Map.of(), List.of(), List.of(),
            archive.modules(), false, document.positions().getOrDefault(ProgramDocument.graphId(key), Map.of()))));
    }
    void restoreVariableEdit(ProgramDocument document) {
        var restored = new ProgramWorkspace(document);
        root = restored.root;
        entries.clear();
        entries.putAll(restored.entries);
        terminal = restored.terminal;
        flow = restored.flow;
    }
    ScratchClipboard scratchClipboard() { return scratchClipboard; }
    void copyScratch(ScratchClipboard fragment) { scratchClipboard = Objects.requireNonNull(fragment); }
    public ProgramDraft root() { return root; }
    public Optional<FlowDocumentBody> flow() { return flow; }
    public void setFlow(Optional<FlowDocumentBody> flow) { this.flow = Objects.requireNonNull(flow); }
    public Optional<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition> terminal() { return terminal; }
    public void setTerminal(Optional<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition> terminal) { this.terminal = Objects.requireNonNull(terminal); }
    public ProgramDraft entry(String name) { return Objects.requireNonNull(entries.get(name), "Unknown entry: " + name); }
    public List<String> entryNames() { return List.copyOf(entries.keySet()); }
    public ProgramDraft createEntry(String name) {
        requireName(name);
        if (entries.containsKey(name)) throw new IllegalArgumentException("Duplicate entry name: " + name);
        var draft = emptyEntry();
        entries.put(name, draft);
        return draft;
    }
    public record PathExpression(String entry, String output, String node) {}
    /** Produce a parked Scratch reporter assignment, with no snapshot literal or executable-body change. */
    public void createPathBlock(DataValue.RecordValue frozenInputs, ValuePath path) {
        var body = flow.orElseThrow(() -> new IllegalStateException("Path blocks require a Scratch draft"));
        var source = emptyEntry();
        var inserted = PathNodeBuilder.insert(source, frozenInputs, path, 0, 0);
        var graph = source.definition(Map.of()).graph();
        var inline = new ComputationGraph(graph.inputs(), graph.nodes(), Map.of("value", inserted.node()));
        var draft = new FlowDraft(body); String id = draft.nextId();
        var step = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Evaluate(id, id,
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(
                Map.of(path.input(), path.input()), Map.of("value", "read_value_" + id)));
        draft.place(List.of(step), null, new ProgramDocument.Position(20, 20 + body.canvas().detached().size() * 72d), Map.of(id, inline));
        flow = Optional.of(draft.snapshot());
    }
    /** Stage ordinary extraction nodes before publishing a new Flow expression; never change its control body. */
    public PathExpression createPathExpression(DataValue.RecordValue frozenInputs, ValuePath path, double x, double y) {
        if (flow.isEmpty()) throw new IllegalStateException("Path expressions require a Flow draft");
        var draft = emptyEntry();
        var inserted = PathNodeBuilder.insert(draft, frozenInputs, path, x, y);
        String name = "read_path";
        for (long suffix = 2; entries.containsKey(name); suffix++) name = "read_path_" + suffix;
        entries.put(name, draft);
        return new PathExpression(name, inserted.output(), inserted.node());
    }
    private ProgramDraft emptyEntry() {
        return new ProgramDraft(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            root.cellsInternal(), List.of(), List.of(), Map.of(), true, Map.of(), Map.of());
    }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name must not be blank");
    }
    public ProgramDraft graph(ModuleDefinition.Key key) {
        if (key == null) return root;
        return Objects.requireNonNull(modules.get(key), "Unknown module: " + key);
    }
    public List<ModuleDefinition.Key> keys() { return List.copyOf(modules.keySet()); }
    public SubgraphDocument exportSubgraph(ModuleDefinition.Key key, ArchiveLimits limits) {
        var definitions = SubgraphDocument.collect(key, dependency -> {
            var draft = modules.get(dependency);
            return draft == null ? null : new ModuleDefinition(dependency, draft.moduleGraph(true));
        });
        var positions = new LinkedHashMap<String, Map<String, ProgramDocument.Position>>();
        definitions.keySet().forEach(dependency -> positions.put(ProgramDocument.graphId(dependency), modules.get(dependency).positions()));
        var document = new SubgraphDocument(key, definitions, positions);
        ValueNbtCodec.encode(SubgraphDocumentCodec.encode(document), limits);
        return document;
    }
    /** Validate and stage every addition before mutating this workspace; no application snapshot or install. */
    public void importSubgraph(SubgraphDocument document, ArchiveLimits limits) {
        Objects.requireNonNull(document);
        ValueNbtCodec.encode(SubgraphDocumentCodec.encode(document), limits);
        var additions = new LinkedHashMap<ModuleDefinition.Key, ProgramDraft>();
        document.modules().forEach((key, definition) -> {
            var existing = modules.get(key);
            if (existing != null) {
                if (!existing.moduleGraph(true).equals(definition.graph()))
                    throw new IllegalArgumentException("Conflicting module revision: " + key.id() + " @ " + key.revision());
            } else additions.put(key, new ProgramDraft(definition.graph(), Map.of(), List.of(), List.of(), document.modules(), false,
                document.positions().getOrDefault(ProgramDocument.graphId(key), Map.of())));
        });
        modules.putAll(additions);
    }
    public ModuleDefinition.Key createModule(String id) {
        if (modules.keySet().stream().anyMatch(key -> key.id().equals(id)))
            throw new IllegalArgumentException("Duplicate module id: " + id);
        var key = new ModuleDefinition.Key(id, 1);
        modules.put(key, new ProgramDraft(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), Map.of(), false, Map.of()));
        return key;
    }
    public ProgramDocument snapshot() {
        var definitions = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
        modules.forEach((key, draft) -> definitions.put(key, new ModuleDefinition(key, draft.moduleGraph(false))));
        var eventDefinitions = new LinkedHashMap<String, ProgramDefinition.Entry>();
        entries.forEach((name, draft) -> {
            var definition = draft.definition(Map.of());
            eventDefinitions.put(name, new ProgramDefinition.Entry(definition.graph(), definition.writes(), definition.sends()));
        });
        var archive = new ProgramArchive(root.definition(eventDefinitions, flow.isPresent()), definitions);
        validateUsedModules(archive);
        var positions = new LinkedHashMap<String, Map<String, ProgramDocument.Position>>();
        positions.put(ProgramDocument.graphId(null), root.positions());
        entries.forEach((name, draft) -> positions.put(ProgramDocument.entryGraphId(name), draft.positions()));
        modules.forEach((key, draft) -> positions.put(ProgramDocument.graphId(key), draft.positions()));
        return new ProgramDocument(archive, positions, terminal, flow);
    }
    private static void validateUsedModules(ProgramArchive archive) {
        var pending = new ArrayDeque<ModuleDefinition.Key>();
        collectCalls(archive.program().graph(), pending);
        archive.program().entries().values().forEach(entry -> collectCalls(entry.graph(), pending));
        var seen = new HashSet<ModuleDefinition.Key>();
        while (!pending.isEmpty()) {
            var key = pending.removeFirst(); if (!seen.add(key)) continue;
            var module = archive.modules().get(key);
            if (module == null) throw new IllegalStateException("Missing module: " + key);
            if (module.graph().outputs().isEmpty()) throw new IllegalStateException("Incomplete used module: " + key.id());
            collectCalls(module.graph(), pending);
        }
    }
    private static void collectCalls(ComputationGraph graph, Queue<ModuleDefinition.Key> result) {
        var reachable = new ArrayDeque<>(graph.outputs().values()); var seen = new HashSet<String>();
        while (!reachable.isEmpty()) {
            var id = reachable.removeFirst(); if (!seen.add(id)) continue;
            var node = graph.nodes().get(id); if (node == null) continue;
            if (node instanceof ComputationGraph.Call call) result.add(call.module());
			if (node instanceof ComputationGraph.Branch branch) { result.add(branch.whenTrue()); result.add(branch.whenFalse()); }
            if (node instanceof ComputationGraph.Iterate loop) result.add(loop.module());
            if (node instanceof ComputationGraph.Fold fold) result.add(fold.module());
            if (node instanceof ComputationGraph.Sort sort) result.add(sort.module());
            reachable.addAll(node.dependencies());
        }
    }
}
