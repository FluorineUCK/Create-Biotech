package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Transactional local editing of the same flat tree used by persistence and projection. */
public final class TerminalDraft {
    public enum Kind { TEXT, ITEM, BUTTON, INPUT, ROW, COLUMN, GRID, REPEAT }
    private TerminalDefinition definition;
    private long sequence;
    public TerminalDraft(TerminalDefinition definition) { this.definition = Objects.requireNonNull(definition); }
    public TerminalDefinition snapshot() { return definition; }
    public static Kind kind(TerminalDefinition.Node node) {
        return switch (node) {
            case TerminalDefinition.Text ignored -> Kind.TEXT;
            case TerminalDefinition.Item ignored -> Kind.ITEM;
            case TerminalDefinition.Button ignored -> Kind.BUTTON;
            case TerminalDefinition.Input ignored -> Kind.INPUT;
            case TerminalDefinition.Row ignored -> Kind.ROW;
            case TerminalDefinition.Column ignored -> Kind.COLUMN;
            case TerminalDefinition.Grid ignored -> Kind.GRID;
            case TerminalDefinition.Repeat ignored -> Kind.REPEAT;
        };
    }
    public static List<String> children(TerminalDefinition.Node node) { return TerminalDefinition.children(node); }
    public Optional<String> parent(String id) {
        return definition.nodes().entrySet().stream().filter(e -> children(e.getValue()).contains(id)).map(Map.Entry::getKey).findFirst();
    }
    public String add(String parent, Kind kind) {
        var nodes = new LinkedHashMap<>(definition.nodes()); var owner = nodes.get(parent);
        if (!(owner instanceof TerminalDefinition.Row || owner instanceof TerminalDefinition.Column || owner instanceof TerminalDefinition.Grid))
            throw new IllegalArgumentException("Select a row, column or grid to add children");
        String id = nextId(nodes);
        TerminalDefinition.Node node = switch (kind) {
            case TEXT -> new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("")));
            case ITEM -> new TerminalDefinition.Item(new TerminalBinding.Literal(new DataValue.RecordValue(Map.of(
                "item", new DataValue.TextValue("minecraft:air"), "count", new DataValue.IntegerValue(0)))));
            case BUTTON -> new TerminalDefinition.Button(new TerminalBinding.Literal(new DataValue.TextValue("Button")),
                new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "click",
                new TerminalBinding.Literal(new DataValue.RecordValue(Map.of())));
            case INPUT -> new TerminalDefinition.Input(new TerminalBinding.Literal(new DataValue.TextValue("")),
                new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "submit", "value", new TerminalBinding.Literal(new DataValue.RecordValue(Map.of())));
            case ROW -> new TerminalDefinition.Row(List.of());
            case COLUMN -> new TerminalDefinition.Column(List.of());
            case GRID -> new TerminalDefinition.Grid(2, List.of());
            case REPEAT -> {
                String template = nextId(nodes); nodes.put(template, new TerminalDefinition.Column(List.of()));
                yield new TerminalDefinition.Repeat(new TerminalBinding.Literal(new DataValue.ListValue(List.of())), template);
            }
        };
        nodes.put(id, node); var children = new ArrayList<>(children(owner)); children.add(id);
        nodes.put(parent, withChildren(owner, children)); publish(nodes); return id;
    }
    public void replace(String id, TerminalDefinition.Node node) {
        if (!definition.nodes().containsKey(id)) throw new IllegalArgumentException("Unknown terminal node");
        var nodes = new LinkedHashMap<>(definition.nodes()); nodes.put(id, Objects.requireNonNull(node)); publish(nodes);
    }
    public void remove(String id) {
        String owner = parent(id).orElseThrow(() -> new IllegalArgumentException("Cannot remove root"));
        var nodes = new LinkedHashMap<>(definition.nodes()); var parentNode = nodes.get(owner);
        if (parentNode instanceof TerminalDefinition.Repeat) throw new IllegalArgumentException("Repeat requires its template root");
        var children = new ArrayList<>(children(parentNode)); children.remove(id); nodes.put(owner, withChildren(parentNode, children));
        var pending = new ArrayDeque<String>(); pending.push(id);
        while (!pending.isEmpty()) { var removed = nodes.remove(pending.pop()); pending.addAll(children(removed)); }
        publish(nodes);
    }
    public void reorder(String id, int offset) {
        String owner = parent(id).orElseThrow(() -> new IllegalArgumentException("Root has no siblings"));
        var parentNode = definition.nodes().get(owner); var children = new ArrayList<>(children(parentNode));
        int from = children.indexOf(id), to = (int)Math.clamp((long)from + offset, 0, children.size() - 1);
        if (from == to) return;
        children.remove(from); children.add(to, id); replace(owner, withChildren(parentNode, children));
    }
    private String nextId(Map<String, ?> nodes) {
        String id; do { id = "node_" + ++sequence; } while (nodes.containsKey(id)); return id;
    }
    private void publish(Map<String, TerminalDefinition.Node> nodes) { definition = new TerminalDefinition(definition.root(), nodes); }
    private static TerminalDefinition.Node withChildren(TerminalDefinition.Node node, List<String> children) {
        return switch (node) {
            case TerminalDefinition.Row ignored -> new TerminalDefinition.Row(children);
            case TerminalDefinition.Column ignored -> new TerminalDefinition.Column(children);
            case TerminalDefinition.Grid grid -> new TerminalDefinition.Grid(grid.columns(), children);
            default -> throw new IllegalArgumentException("Node does not accept a child list");
        };
    }
}
