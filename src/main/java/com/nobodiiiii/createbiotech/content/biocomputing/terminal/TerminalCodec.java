package com.nobodiiiii.createbiotech.content.biocomputing.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValuePath;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;

/** Closed schema 4 (schemas 1 and 2 remain readable). Layout nesting is represented by ids. */
public final class TerminalCodec {
    private TerminalCodec() {}
    public static DataValue.RecordValue encode(TerminalDefinition definition) {
        var nodes = new LinkedHashMap<String, DataValue>();
        definition.nodes().forEach((id, node) -> nodes.put(id, encodeNode(node)));
        return record(Map.of("version", integer(definition.usesTasks() ? 4 : 3), "root", text(definition.root()), "nodes", record(nodes)));
    }
    public static TerminalDefinition decode(DataValue value) {
        try {
            var root = exact(value, "version", "root", "nodes");
            long version = number(root.get("version"));
            if (version < 1 || version > 4)
                throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported terminal schema version");
            var nodes = new LinkedHashMap<String, TerminalDefinition.Node>();
            fields(root.get("nodes")).forEach((id, node) -> nodes.put(id, decodeNode(node, version)));
            var definition = new TerminalDefinition(string(root.get("root")), nodes);
            if (version < 4 && definition.usesTasks()) throw invalid("Task bindings require terminal schema 4");
            return definition;
        } catch (ArchiveException error) { throw error; }
        catch (IllegalArgumentException | NullPointerException error) {
            throw invalid("Invalid terminal: " + error.getMessage());
        }
    }
    private static DataValue encodeNode(TerminalDefinition.Node node) {
        return switch (node) {
            case TerminalDefinition.Text t -> record(Map.of("kind", text("text"), "value", encodeBinding(t.value())));
            case TerminalDefinition.Item i -> record(Map.of("kind", text("item"), "value", encodeBinding(i.value())));
            case TerminalDefinition.Button b -> record(Map.of("kind", text("button"), "title", encodeBinding(b.title()),
                "enabled", encodeBinding(b.enabled()), "entry", target(b.target()), "inputs", encodeBinding(b.inputs())));
            case TerminalDefinition.Input i -> record(Map.of("kind", text("input"), "initial", encodeBinding(i.initial()),
                "enabled", encodeBinding(i.enabled()), "entry", target(i.target()), "argument", text(i.argument()), "inputs", encodeBinding(i.inputs())));
            case TerminalDefinition.Row r -> record(Map.of("kind", text("row"), "children", names(r.children())));
            case TerminalDefinition.Column c -> record(Map.of("kind", text("column"), "children", names(c.children())));
            case TerminalDefinition.Grid g -> record(Map.of("kind", text("grid"), "columns", integer(g.columns()), "children", names(g.children())));
            case TerminalDefinition.Repeat r -> record(Map.of("kind", text("repeat"), "items", encodeBinding(r.items()), "template", text(r.template())));
        };
    }
    private static TerminalDefinition.Node decodeNode(DataValue value, long version) {
        String kind = string(fields(value).get("kind"));
        var f = switch (kind) {
            case "text", "item" -> exact(value, "kind", "value");
            case "row", "column" -> exact(value, "kind", "children");
            case "grid" -> exact(value, "kind", "columns", "children");
            case "repeat" -> exact(value, "kind", "items", "template");
            case "button" -> {
                if (version < 2) throw invalid("Button requires terminal schema 2");
                yield exact(value, "kind", "title", "enabled", "entry", "inputs");
            }
            case "input" -> {
                if (version < 3) throw invalid("Input requires terminal schema 3");
                yield exact(value, "kind", "initial", "enabled", "entry", "argument", "inputs");
            }
            default -> throw invalid("Unknown terminal node kind: " + kind);
        };
        return switch (kind) {
            case "text" -> new TerminalDefinition.Text(decodeBinding(f.get("value")));
            case "item" -> new TerminalDefinition.Item(decodeBinding(f.get("value")));
            case "row" -> new TerminalDefinition.Row(decodeNames(f.get("children")));
            case "column" -> new TerminalDefinition.Column(decodeNames(f.get("children")));
            case "grid" -> new TerminalDefinition.Grid(intNumber(f.get("columns")), decodeNames(f.get("children")));
            case "repeat" -> new TerminalDefinition.Repeat(decodeBinding(f.get("items")), string(f.get("template")));
            case "button" -> new TerminalDefinition.Button(decodeBinding(f.get("title")), decodeBinding(f.get("enabled")),
                target(f.get("entry"), version), decodeBinding(f.get("inputs")));
            case "input" -> new TerminalDefinition.Input(decodeBinding(f.get("initial")), decodeBinding(f.get("enabled")),
                target(f.get("entry"), version), string(f.get("argument")), decodeBinding(f.get("inputs")));
            default -> throw invalid("Unknown terminal node kind");
        };
    }
    private static DataValue target(TerminalTarget target) {
        if (target instanceof TerminalTarget.Event event) return text(event.entry());
        var key = ((TerminalTarget.Task)target).procedure();
        return record(Map.of("kind", text("task"), "name", text(key.id()), "revision", integer(key.revision())));
    }
    private static TerminalTarget target(DataValue value, long version) {
        if (value instanceof DataValue.TextValue name) return new TerminalTarget.Event(name.value());
        if (version < 4) throw invalid("Task dispatch requires terminal schema 4");
        var data = exact(value, "kind", "name", "revision");
        if (!string(data.get("kind")).equals("task")) throw invalid("Unknown terminal target");
        return new TerminalTarget.Task(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key(
            string(data.get("name")), number(data.get("revision"))));
    }
    private static DataValue encodeBinding(TerminalBinding binding) {
        if (binding instanceof TerminalBinding.Literal literal)
            return record(Map.of("kind", text("literal"), "value", literal.value()));
        var path = ((TerminalBinding.Path) binding).path();
        var steps = new ArrayList<DataValue>();
        for (ValuePath.Step step : path.steps()) {
            if (step instanceof ValuePath.Key key)
                steps.add(record(Map.of("kind", text("key"), "name", text(key.name()))));
            else steps.add(record(Map.of("kind", text("index"), "index", integer(((ValuePath.Index) step).index()))));
        }
        return record(Map.of("kind", text("path"), "input", text(path.input()), "steps", new DataValue.ListValue(steps)));
    }
    private static TerminalBinding decodeBinding(DataValue value) {
        String kind = string(fields(value).get("kind"));
        if (kind.equals("literal")) return new TerminalBinding.Literal(exact(value, "kind", "value").get("value"));
        if (!kind.equals("path")) throw invalid("Unknown terminal binding kind: " + kind);
        var path = exact(value, "kind", "input", "steps");
        var steps = new ArrayList<ValuePath.Step>();
        for (DataValue item : list(path.get("steps"))) {
            String step = string(fields(item).get("kind"));
            switch (step) {
                case "key" -> steps.add(new ValuePath.Key(string(exact(item, "kind", "name").get("name"))));
                case "index" -> steps.add(new ValuePath.Index(intNumber(exact(item, "kind", "index").get("index"))));
                default -> throw invalid("Unknown terminal path step: " + step);
            }
        }
        return new TerminalBinding.Path(new ValuePath(string(path.get("input")), steps));
    }
    private static DataValue.ListValue names(List<String> names) {
        var values = new ArrayList<DataValue>();
        names.forEach(name -> values.add(text(name)));
        return new DataValue.ListValue(values);
    }
    private static List<String> decodeNames(DataValue value) {
        var names = new ArrayList<String>();
        for (DataValue name : list(value)) names.add(string(name));
        return names;
    }
    private static Map<String, DataValue> fields(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record.fields();
        throw invalid("Expected terminal record");
    }
    private static Map<String, DataValue> exact(DataValue value, String... names) {
        var fields = fields(value);
        if (!fields.keySet().equals(Set.of(names))) throw invalid("Wrong terminal fields; expected " + String.join(", ", names));
        return fields;
    }
    private static List<DataValue> list(DataValue value) {
        if (value instanceof DataValue.ListValue list) return list.values();
        throw invalid("Expected terminal list");
    }
    private static String string(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        throw invalid("Expected terminal text");
    }
    private static long number(DataValue value) {
        if (value instanceof DataValue.IntegerValue integer) return integer.value();
        throw invalid("Expected terminal integer");
    }
    private static int intNumber(DataValue value) {
        long number = number(value);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid("Terminal integer out of range");
        return (int) number;
    }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static ArchiveException invalid(String message) { return new ArchiveException(ArchiveException.Code.INVALID_FORMAT, message); }
}
