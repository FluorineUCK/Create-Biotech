package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import java.util.*;

/** Immutable flat node table. References form one tree; templates are ordinary children. */
public record TerminalDefinition(String root, Map<String, Node> nodes) {
    public sealed interface Node {}
    public record Text(TerminalBinding value) implements Node {
        public Text { Objects.requireNonNull(value); }
    }
    public record Item(TerminalBinding value) implements Node {
        public Item { Objects.requireNonNull(value); }
    }
    public record Button(TerminalBinding title, TerminalBinding enabled, TerminalTarget target,
                         TerminalBinding inputs) implements Node {
        public Button {
            Objects.requireNonNull(title); Objects.requireNonNull(enabled); Objects.requireNonNull(target); Objects.requireNonNull(inputs);
        }
        public Button(TerminalBinding title, TerminalBinding enabled, String entry, TerminalBinding inputs) { this(title, enabled, new TerminalTarget.Event(entry), inputs); }
        public String entry() { return target.eventEntry(); }
    }
    public record Input(TerminalBinding initial, TerminalBinding enabled, TerminalTarget target, String argument,
                        TerminalBinding inputs) implements Node {
        public Input { Objects.requireNonNull(initial); Objects.requireNonNull(enabled); Objects.requireNonNull(target); requireName(argument); Objects.requireNonNull(inputs); }
        public Input(TerminalBinding initial, TerminalBinding enabled, String entry, String argument, TerminalBinding inputs) { this(initial, enabled, new TerminalTarget.Event(entry), argument, inputs); }
        public String entry() { return target.eventEntry(); }
    }
    public record Row(List<String> children) implements Node {
        public Row { children = copyChildren(children); }
    }
    public record Column(List<String> children) implements Node {
        public Column { children = copyChildren(children); }
    }
    public record Grid(int columns, List<String> children) implements Node {
        public Grid {
            if (columns <= 0) throw new IllegalArgumentException("Grid columns must be positive");
            children = copyChildren(children);
        }
    }
    public record Repeat(TerminalBinding items, String template) implements Node {
        public Repeat { Objects.requireNonNull(items); requireName(template); }
    }
    public TerminalDefinition {
        requireName(root);
        var copy = new LinkedHashMap<String, Node>();
        nodes.forEach((id, node) -> { requireName(id); copy.put(id, Objects.requireNonNull(node)); });
        if (!copy.containsKey(root)) throw new IllegalArgumentException("Missing terminal root: " + root);
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.push(root);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            Node node = copy.get(id);
            if (node == null) throw new IllegalArgumentException("Missing terminal node: " + id);
            if (!visited.add(id)) throw new IllegalArgumentException("Cycle or multiple parents at terminal node: " + id);
            for (String child : children(node)) pending.push(child);
        }
        if (visited.size() != copy.size()) throw new IllegalArgumentException("Unreachable terminal nodes");
        nodes = Collections.unmodifiableMap(copy);
    }
    public static TerminalDefinition empty() {
        return new TerminalDefinition("root", Map.of("root", new Column(List.of())));
    }
    /** Presentation-only layouts cannot dispatch program entries, including inside row templates. */
    public boolean readOnly() {
        return nodes.values().stream().noneMatch(node -> node instanceof Button || node instanceof Input);
    }
    public boolean hasTasks() { return targets().stream().anyMatch(TerminalTarget.Task.class::isInstance); }
    public boolean usesTasks() {
        return hasTasks() || nodes.values().stream().flatMap(node -> switch (node) {
            case Text t -> java.util.stream.Stream.of(t.value());
            case Item i -> java.util.stream.Stream.of(i.value());
            case Button b -> java.util.stream.Stream.of(b.title(), b.enabled(), b.inputs());
            case Input i -> java.util.stream.Stream.of(i.initial(), i.enabled(), i.inputs());
            case Repeat r -> java.util.stream.Stream.of(r.items());
            default -> java.util.stream.Stream.<TerminalBinding>empty();
        }).anyMatch(binding -> binding instanceof TerminalBinding.Path path && path.path().input().equals("tasks"));
    }
    public List<TerminalTarget> targets() {
        return nodes.values().stream().flatMap(node -> node instanceof Button button ? java.util.stream.Stream.of(button.target())
            : node instanceof Input input ? java.util.stream.Stream.of(input.target()) : java.util.stream.Stream.empty()).toList();
    }
    static List<String> children(Node node) {
        return switch (node) {
            case Row row -> row.children();
            case Column column -> column.children();
            case Grid grid -> grid.children();
            case Repeat repeat -> List.of(repeat.template());
            default -> List.of();
        };
    }
    private static List<String> copyChildren(List<String> children) {
        var copy = List.copyOf(children);
        copy.forEach(TerminalDefinition::requireName);
        return copy;
    }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Terminal node name must not be blank");
    }
}
