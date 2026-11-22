package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;

/** Local editable facts and layout. Only snapshot() creates the existing executable archive. */
public final class ProgramDraft {
    public record Node(String id, ComputationGraph.Node definition, Map<String, String> connections, double x, double y) {
        public Node { connections = Collections.unmodifiableMap(new LinkedHashMap<>(connections)); }
        public Set<String> ports() { return definition == null ? Set.of() : NodePorts.bindings(definition).keySet(); }
    }
    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, DataType> inputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, ProgramDefinition.Cell> cells;
    private final ArrayList<ProgramDefinition.Write> writes = new ArrayList<>();
    private final ArrayList<ProgramDefinition.Send> sends = new ArrayList<>();
    private final Map<ModuleDefinition.Key, ModuleDefinition> modules;
    private final Map<String, ProgramDefinition.Entry> importedEntries;
    private final boolean memoryAllowed;
    private long nextId;

    public ProgramDraft() {
        this(new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of()));
    }
    public ProgramDraft(ProgramArchive archive) {
        this(Objects.requireNonNull(archive).program().graph(), archive.program().cells(), archive.program().writes(), archive.program().sends(),
            archive.modules(), true, Map.of(), archive.program().entries());
    }
    ProgramDraft(ComputationGraph graph, Map<String, ProgramDefinition.Cell> cells,
        List<ProgramDefinition.Write> writes, List<ProgramDefinition.Send> sends, Map<ModuleDefinition.Key, ModuleDefinition> modules,
        boolean memoryAllowed, Map<String, ProgramDocument.Position> positions) {
        this(graph, new LinkedHashMap<>(cells), writes, sends, modules, memoryAllowed, positions, Map.of());
    }
    ProgramDraft(ComputationGraph graph, Map<String, ProgramDefinition.Cell> cells,
        List<ProgramDefinition.Write> writes, List<ProgramDefinition.Send> sends, Map<ModuleDefinition.Key, ModuleDefinition> modules,
        boolean memoryAllowed, Map<String, ProgramDocument.Position> positions, Map<String, ProgramDefinition.Entry> importedEntries) {
        this(graph, new LinkedHashMap<>(cells), writes, sends, modules, memoryAllowed, positions, importedEntries);
    }
    ProgramDraft(ComputationGraph graph, LinkedHashMap<String, ProgramDefinition.Cell> cells,
        List<ProgramDefinition.Write> writes, List<ProgramDefinition.Send> sends, Map<ModuleDefinition.Key, ModuleDefinition> modules,
        boolean memoryAllowed, Map<String, ProgramDocument.Position> positions, Map<String, ProgramDefinition.Entry> importedEntries) {
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(modules)));
        this.importedEntries = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(importedEntries)));
        this.memoryAllowed = memoryAllowed;
        inputs.putAll(graph.inputs()); this.cells = cells; this.writes.addAll(writes); this.sends.addAll(sends);
        graph.nodes().forEach((id, node) -> {
            int index = nodes.size();
            var position = positions.get(id);
            nodes.put(id, new Node(id, node, NodePorts.bindings(node),
                position == null ? 20 + index % 3 * 150 : position.x(), position == null ? 20 + index / 3 * 90 : position.y()));
        });
        outputs.putAll(graph.outputs());
        nextId = nodes.keySet().stream().filter(id -> id.matches("n\\d+")).mapToLong(id -> Long.parseLong(id.substring(1))).max().orElse(0);
    }
    public List<Node> nodes() { return List.copyOf(nodes.values()); }
    public Node node(String id) { return Objects.requireNonNull(nodes.get(id), "Unknown node: " + id); }
    public Map<String, String> outputs() { return Collections.unmodifiableMap(outputs); }
    public Map<String, DataType> inputs() { return Collections.unmodifiableMap(inputs); }
    public void putInput(String name, DataType type) { requireName(name); inputs.put(name, Objects.requireNonNull(type)); }
    public void removeInput(String name) { inputs.remove(name); }
    public Map<String, ProgramDefinition.Cell> cells() { return Collections.unmodifiableMap(cells); }
    LinkedHashMap<String, ProgramDefinition.Cell> cellsInternal() { return cells; }
    public void putCell(String name, DataType type, DataValue initial) {
        requireMemory(); requireName(name); cells.put(name, new ProgramDefinition.Cell(Objects.requireNonNull(type), Objects.requireNonNull(initial)));
    }
    public void removeCell(String name) { requireMemory(); cells.remove(name); }
    public List<ProgramDefinition.Write> writes() { return List.copyOf(writes); }
    public List<ProgramDefinition.Send> sends() { return List.copyOf(sends); }
    public void addSend(String endpointOutput, String payloadOutput) {
        addSend(new ProgramDefinition.Send(endpointOutput, payloadOutput));
    }
    public void addSend(ProgramDefinition.Send send) {
        requireMemory();
        sends.add(Objects.requireNonNull(send));
    }
    public void setSend(int index, String endpointOutput, String payloadOutput) {
        requireMemory();
        var existing = sends.get(index);
        setSend(index, new ProgramDefinition.Send(endpointOutput, payloadOutput, existing.dispatch(),
            existing.requestSlot(), existing.replyEntry(), existing.continuation(), existing.conditionOutput()));
    }
    public void setSend(int index, ProgramDefinition.Send send) {
        requireMemory();
        sends.set(index, Objects.requireNonNull(send));
    }
    public void removeSend(int index) { requireMemory(); sends.remove(index); }
    public void setWrite(String cell, String output) {
        requireMemory(); requireName(cell);
        var replacement = output == null ? null : new ProgramDefinition.Write(cell, output);
        writes.removeIf(write -> write.cell().equals(cell));
        if (replacement != null) writes.add(replacement);
    }
    public List<String> readers(String cell) {
        requireName(cell);
        return nodes.values().stream().filter(node -> node.definition() instanceof ComputationGraph.ReadMemory read && read.cell().equals(cell))
            .map(Node::id).toList();
    }
    public List<String> writers(String cell) {
        requireName(cell); return writes.stream().filter(write -> write.cell().equals(cell)).map(ProgramDefinition.Write::output).toList();
    }
    public String add(ComputationGraph.Node definition, double x, double y) {
        finite(x); finite(y);
        String id; do { id = "n" + ++nextId; } while (nodes.containsKey(id));
        nodes.put(id, new Node(id, definition, Map.of(), x, y)); return id;
    }
    public void configure(String id, ComputationGraph.Node definition) {
        var old = node(id); Objects.requireNonNull(definition);
        var connections = new LinkedHashMap<>(old.connections());
        connections.keySet().retainAll(NodePorts.bindings(definition).keySet());
        nodes.put(id, new Node(id, definition, connections, old.x(), old.y()));
    }
    public void connect(String target, String port, String source) {
        var node = node(target); node(source);
        if (!node.ports().contains(port)) throw new IllegalArgumentException("Unknown port: " + port);
        var connections = new LinkedHashMap<>(node.connections()); connections.put(port, source);
        nodes.put(target, new Node(target, node.definition(), connections, node.x(), node.y()));
    }
    public void disconnect(String target, String port) {
        var node = node(target); var connections = new LinkedHashMap<>(node.connections()); connections.remove(port);
        nodes.put(target, new Node(target, node.definition(), connections, node.x(), node.y()));
    }
    public void moveBy(String id, double dx, double dy) {
        var node = node(id); double x = node.x() + dx, y = node.y() + dy; finite(x); finite(y);
        nodes.put(id, new Node(id, node.definition(), node.connections(), x, y));
    }
    public void remove(String id) {
        nodes.remove(id); outputs.values().removeIf(id::equals);
        for (var node : nodes()) for (var entry : node.connections().entrySet())
            if (entry.getValue().equals(id)) disconnect(node.id(), entry.getKey());
    }
    public void output(String name, String source) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Output name must not be blank");
        node(source); outputs.put(name, source);
    }
    public void removeOutput(String name) { outputs.remove(name); }
    public ProgramArchive snapshot() {
        var graph = new LinkedHashMap<String, ComputationGraph.Node>();
        for (var node : nodes.values()) {
            if (node.definition() == null) throw new IllegalStateException("Unconfigured node: " + node.id());
            for (String port : node.ports()) if (!node.connections().containsKey(port))
                throw new IllegalStateException("Unconnected port: " + node.id() + "." + port);
            graph.put(node.id(), NodePorts.bind(node.definition(), node.connections()));
        }
        return new ProgramArchive(definition(graph, importedEntries), modules);
    }
    ProgramDefinition definition() {
        var graph = new LinkedHashMap<String, ComputationGraph.Node>();
        for (var node : nodes.values()) {
            if (node.definition() == null) throw new IllegalStateException("Unconfigured node: " + node.id());
            for (String port : node.ports()) if (!node.connections().containsKey(port))
                throw new IllegalStateException("Unconnected port: " + node.id() + "." + port);
            graph.put(node.id(), NodePorts.bind(node.definition(), node.connections()));
        }
        return definition(graph, importedEntries);
    }
    ProgramDefinition definition(Map<String, ProgramDefinition.Entry> entries) {
        return definition(entries, false);
    }
    ProgramDefinition definition(Map<String, ProgramDefinition.Entry> entries, boolean allowEmptyRoot) {
        var graph = new LinkedHashMap<String, ComputationGraph.Node>();
        for (var node : nodes.values()) {
            if (node.definition() == null) throw new IllegalStateException("Unconfigured node: " + node.id());
            for (String port : node.ports()) if (!node.connections().containsKey(port))
                throw new IllegalStateException("Unconnected port: " + node.id() + "." + port);
            graph.put(node.id(), NodePorts.bind(node.definition(), node.connections()));
        }
        return definition(graph, entries, allowEmptyRoot);
    }
    private ProgramDefinition definition(Map<String, ComputationGraph.Node> graph, Map<String, ProgramDefinition.Entry> entries) {
        return definition(graph, entries, false);
    }
    private ProgramDefinition definition(Map<String, ComputationGraph.Node> graph, Map<String, ProgramDefinition.Entry> entries, boolean allowEmptyRoot) {
        // Event-driven applications may intentionally have no manual computation.
        // This does not make an unfinished event, module or partially drawn root valid.
        boolean eventOnlyRoot = (allowEmptyRoot || !entries.isEmpty()) && graph.isEmpty() && inputs.isEmpty()
            && writes.isEmpty() && sends.isEmpty();
        if (outputs.isEmpty() && !eventOnlyRoot) throw new IllegalStateException("No named output");
        return new ProgramDefinition(new ComputationGraph(inputs, graph, outputs), cells, writes, sends, entries);
    }
    ComputationGraph moduleGraph(boolean required) {
        if (!memoryAllowed && (!cells.isEmpty() || !writes.isEmpty() || !sends.isEmpty())) throw new IllegalStateException("Modules cannot own effects");
        if (!required && nodes.isEmpty() && outputs.isEmpty()) return new ComputationGraph(inputs, Map.of(), Map.of());
        return definition().graph();
    }
    Map<String, ProgramDocument.Position> positions() {
        var result = new LinkedHashMap<String, ProgramDocument.Position>();
        nodes.values().forEach(node -> result.put(node.id(), new ProgramDocument.Position(node.x(), node.y())));
        return result;
    }
    private void requireMemory() { if (!memoryAllowed) throw new IllegalStateException("Modules have no RAM"); }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name must not be blank");
    }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite canvas coordinate"); }
}
