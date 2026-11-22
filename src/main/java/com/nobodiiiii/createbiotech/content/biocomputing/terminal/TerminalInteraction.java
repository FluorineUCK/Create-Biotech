package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import java.util.*;

/** Resolves one published terminal instance without projecting or scanning unrelated rows. */
public final class TerminalInteraction {
    private TerminalInteraction() {}
    public record Invocation(TerminalTarget target, DataValue.RecordValue inputs) {
        public Invocation(String entry, DataValue.RecordValue inputs) { this(new TerminalTarget.Event(entry), inputs); }
        public String entry() { return target.eventEntry(); }
    }
    static final class BindingException extends IllegalArgumentException {
        private final String code;
        BindingException(String code, String detail) { super(detail); this.code = code; }
        String code() { return code; }
    }
    public static Invocation resolve(TerminalDefinition definition, DataValue.RecordValue roots, String instance) {
        var target = target(definition, roots, instance);
        if (!(target.node() instanceof TerminalDefinition.Button control)) throw new IllegalArgumentException("Terminal instance is not a button");
        var resolved = resolveButton(control, target.roots());
        if (!resolved.enabled()) throw new IllegalArgumentException("Terminal button is disabled");
        return new Invocation(control.target(), resolved.inputs());
    }
    /** Resolve display/context for one instance, including an edited instance outside the visible page. */
    public static TerminalProjection.ResolvedInput resolveInput(TerminalDefinition definition, DataValue.RecordValue roots, String instance) {
        var target = target(definition, roots, instance);
        if (!(target.node() instanceof TerminalDefinition.Input control)) throw new IllegalArgumentException("Terminal instance is not an input");
        return resolveInput(control, target.roots());
    }
    public static Invocation resolveSubmit(TerminalDefinition definition, DataValue.RecordValue roots, String instance, String text) {
        Objects.requireNonNull(text); var target = target(definition, roots, instance);
        if (!(target.node() instanceof TerminalDefinition.Input control)) throw new IllegalArgumentException("Terminal instance is not an input");
        var resolved = resolveInput(control, target.roots());
        if (!resolved.enabled()) throw new IllegalArgumentException("Terminal input is disabled");
        var values = new LinkedHashMap<>(resolved.inputs().fields()); values.put(control.argument(), new DataValue.TextValue(text));
        return new Invocation(control.target(), new DataValue.RecordValue(values));
    }
    private record Target(TerminalDefinition.Node node, DataValue.RecordValue roots) {}
    private static Target target(TerminalDefinition definition, DataValue.RecordValue roots, String instance) {
        Objects.requireNonNull(definition); Objects.requireNonNull(roots); Objects.requireNonNull(instance);
        var segments = parse(instance); if (segments.isEmpty() || segments.getFirst().row() != null || !segments.getFirst().id().equals(definition.root())) throw new IllegalArgumentException("Invalid terminal instance root");
        String id = definition.root(); DataValue.RecordValue currentRoots = roots;
        for (int i = 1; i < segments.size(); i++) { var segment = segments.get(i); var node = definition.nodes().get(id);
            if (node instanceof TerminalDefinition.Repeat repeat) { if (!repeat.template().equals(segment.id()) || segment.row() == null) throw new IllegalArgumentException("Invalid repeat instance"); DataValue value = resolve(repeat.items(), currentRoots, "repeat_items"); if (!(value instanceof DataValue.ListValue list) || segment.row() < 0 || segment.row() >= list.values().size()) throw new IllegalArgumentException("Repeat row out of bounds"); var fields = new LinkedHashMap<>(currentRoots.fields()); fields.put("row", list.values().get(segment.row())); currentRoots = new DataValue.RecordValue(fields); id = segment.id(); continue; }
            if (segment.row() != null || !TerminalDefinition.children(node).contains(segment.id())) throw new IllegalArgumentException("Instance is not a child of " + id); id = segment.id(); }
        return new Target(definition.nodes().get(id), currentRoots);
    }
    static TerminalProjection.ResolvedInput resolveInput(TerminalDefinition.Input input, DataValue.RecordValue roots) {
        return resolveInput(input, roots, null);
    }
    /** Presentation only; invocation paths keep the strict overload without output availability. */
    static TerminalProjection.ResolvedInput resolveInput(TerminalDefinition.Input input, DataValue.RecordValue roots, TerminalProjection.Problem unavailableOutput) {
        DataValue initial = resolve(input.initial(), roots, "input_initial", unavailableOutput); if (!(initial instanceof DataValue.TextValue text)) throw new BindingException("invalid_input_initial", "Input initial requires text");
        DataValue enabled = resolve(input.enabled(), roots, "input_enabled", unavailableOutput); if (!(enabled instanceof DataValue.BooleanValue active)) throw new BindingException("invalid_input_enabled", "Input enabled requires a boolean");
        DataValue inputs = resolve(input.inputs(), roots, "input_inputs", unavailableOutput); if (!(inputs instanceof DataValue.RecordValue record)) throw new BindingException("invalid_input_inputs", "Input inputs require a record");
        return new TerminalProjection.ResolvedInput(text.value(), active.value(), record);
    }
    static TerminalProjection.ResolvedButton resolveButton(TerminalDefinition.Button button, DataValue.RecordValue roots) {
        return resolveButton(button, roots, null);
    }
    static TerminalProjection.ResolvedButton resolveButton(TerminalDefinition.Button button, DataValue.RecordValue roots, TerminalProjection.Problem unavailableOutput) {
        DataValue title = resolve(button.title(), roots, "button_title", unavailableOutput);
        if (!(title instanceof DataValue.TextValue text)) throw new BindingException("invalid_button_title", "Button title requires text");
        DataValue enabled = resolve(button.enabled(), roots, "button_enabled", unavailableOutput);
        if (!(enabled instanceof DataValue.BooleanValue active)) throw new BindingException("invalid_button_enabled", "Button enabled requires a boolean");
        DataValue inputs = resolve(button.inputs(), roots, "button_inputs", unavailableOutput);
        if (!(inputs instanceof DataValue.RecordValue record)) throw new BindingException("invalid_button_inputs", "Button inputs require a record");
        return new TerminalProjection.ResolvedButton(text.value(), active.value(), record);
    }
    private static DataValue resolve(TerminalBinding binding, DataValue.RecordValue roots, String code) {
        return resolve(binding, roots, code, null);
    }
    private static DataValue resolve(TerminalBinding binding, DataValue.RecordValue roots, String code, TerminalProjection.Problem unavailableOutput) {
        if (unavailableOutput != null && binding instanceof TerminalBinding.Path path && path.path().input().equals("output"))
            throw new BindingException(unavailableOutput.code(), unavailableOutput.detail());
        try { return binding.resolve(roots); }
        catch (RuntimeException error) { throw new BindingException(code, String.valueOf(error.getMessage())); }
    }
    private record Segment(String id, Integer row) {}
    private static List<Segment> parse(String instance) {
        var result = new ArrayList<Segment>(); int cursor = 0;
        while (cursor < instance.length()) {
            if (!result.isEmpty()) { if (instance.charAt(cursor++) != '/') throw new IllegalArgumentException("Invalid terminal instance separator"); }
            int colon = instance.indexOf(':', cursor); if (colon < 0) throw new IllegalArgumentException("Invalid terminal instance segment");
            int length; try { length = Integer.parseInt(instance.substring(cursor, colon)); } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid terminal instance length"); }
            if (length <= 0 || length > instance.length() - colon - 1) throw new IllegalArgumentException("Invalid terminal instance length");
            String id = instance.substring(colon + 1, colon + 1 + length); cursor = colon + 1 + length; Integer row = null;
            if (cursor < instance.length() && instance.charAt(cursor) == '[') {
                int close = instance.indexOf(']', cursor); if (close < 0) throw new IllegalArgumentException("Invalid repeat row");
                try { row = Integer.parseInt(instance.substring(cursor + 1, close)); } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid repeat row"); }
                cursor = close + 1;
            }
            result.add(new Segment(id, row));
        }
        return result;
    }
}
