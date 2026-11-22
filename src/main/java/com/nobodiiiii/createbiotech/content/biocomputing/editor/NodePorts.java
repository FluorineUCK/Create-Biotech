package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import java.util.*;

/** Editor adapter for canonical nodes, not an executable graph or operation registry. */
public final class NodePorts {
    private NodePorts() {}

    public static Map<String, String> bindings(ComputationGraph.Node node) {
        var result = new LinkedHashMap<String, String>();
        switch (node) {
            case ComputationGraph.Binary binary -> { result.put("left", binary.left()); result.put("right", binary.right()); }
            case ComputationGraph.RandomRange range -> { result.put("from", range.from()); result.put("through", range.through()); }
			case ComputationGraph.Text text -> result.putAll(text.arguments());
			case ComputationGraph.Scalar scalar -> result.putAll(scalar.arguments());
            case ComputationGraph.Field field -> result.put("source", field.source());
            case ComputationGraph.DynamicField field -> { result.put("source", field.source()); result.put("name", field.name()); }
            case ComputationGraph.HasField field -> { result.put("source", field.source()); result.put("name", field.name()); }
            case ComputationGraph.Keys keys -> result.put("source", keys.source());
            case ComputationGraph.Index index -> result.put("source", index.source());
            case ComputationGraph.DynamicIndex index -> { result.put("source", index.source()); result.put("index", index.index()); }
            case ComputationGraph.ListEdit edit -> result.putAll(edit.arguments());
            case ComputationGraph.ListPosition position -> result.put("source", position.source());
            case ComputationGraph.ListSearch search -> {
                if (!search.contains()) result.put("value", search.value());
                result.put("source", search.source());
                if (search.contains()) result.put("value", search.value());
            }
            case ComputationGraph.ValueEquals equality -> { result.put("left", equality.left()); result.put("right", equality.right()); }
            case ComputationGraph.Distinct distinct -> result.put("source", distinct.source());
            case ComputationGraph.MakeRecord record -> result.putAll(record.fields());
            case ComputationGraph.MakeList list -> {
                for (int index = 0; index < list.elements().size(); index++) result.put(Integer.toString(index), list.elements().get(index));
            }
            case ComputationGraph.ConcatLists list -> { result.put("left", list.left()); result.put("right", list.right()); }
            case ComputationGraph.Call call -> result.putAll(call.arguments());
            case ComputationGraph.ProcedureValue call -> result.putAll(call.arguments());
			case ComputationGraph.Branch branch -> {
				result.put("condition", branch.condition());
				branch.arguments().forEach((name, source) -> result.put("argument:" + name, source));
			}
            case ComputationGraph.Fold fold -> {
                result.put("source", fold.source());
                result.put("initial", fold.initial());
                fold.captures().forEach((name, source) -> result.put("capture:" + name, source));
            }
            case ComputationGraph.Iterate loop -> {
                result.put("source", loop.source());
                loop.captures().forEach((name, source) -> result.put("capture:" + name, source));
            }
            case ComputationGraph.Sort sort -> {
                result.put("source", sort.source());
                sort.captures().forEach((name, source) -> result.put("capture:" + name, source));
            }
            default -> { }
        }
        return Collections.unmodifiableMap(result);
    }

    public static ComputationGraph.Node bind(ComputationGraph.Node template, Map<String, String> ports) {
        if (!ports.keySet().equals(bindings(template).keySet())) throw new IllegalArgumentException("Incomplete node ports");
        return switch (template) {
            case ComputationGraph.Binary binary -> new ComputationGraph.Binary(binary.operation(), ports.get("left"), ports.get("right"));
            case ComputationGraph.RandomRange range -> new ComputationGraph.RandomRange(ports.get("from"), ports.get("through"), range.decimal());
			case ComputationGraph.Text text -> new ComputationGraph.Text(text.operation(), ports);
			case ComputationGraph.Scalar scalar -> new ComputationGraph.Scalar(scalar.operation(), ports);
            case ComputationGraph.Field field -> new ComputationGraph.Field(ports.get("source"), field.name());
            case ComputationGraph.DynamicField ignored -> new ComputationGraph.DynamicField(ports.get("source"), ports.get("name"));
            case ComputationGraph.HasField ignored -> new ComputationGraph.HasField(ports.get("source"), ports.get("name"));
            case ComputationGraph.Keys ignored -> new ComputationGraph.Keys(ports.get("source"));
            case ComputationGraph.Index index -> new ComputationGraph.Index(ports.get("source"), index.index());
            case ComputationGraph.DynamicIndex ignored -> new ComputationGraph.DynamicIndex(ports.get("source"), ports.get("index"));
            case ComputationGraph.ListEdit edit -> new ComputationGraph.ListEdit(edit.operation(), ports);
            case ComputationGraph.ListPosition position -> new ComputationGraph.ListPosition(ports.get("source"), position.choice(), position.insertion());
            case ComputationGraph.ListSearch search -> new ComputationGraph.ListSearch(ports.get("source"), ports.get("value"), search.contains());
            case ComputationGraph.ValueEquals ignored -> new ComputationGraph.ValueEquals(ports.get("left"), ports.get("right"));
            case ComputationGraph.Distinct ignored -> new ComputationGraph.Distinct(ports.get("source"));
            case ComputationGraph.MakeRecord ignored -> new ComputationGraph.MakeRecord(ports);
            case ComputationGraph.MakeList list -> new ComputationGraph.MakeList(java.util.stream.IntStream.range(0, list.elements().size())
                .mapToObj(index -> ports.get(Integer.toString(index))).toList());
            case ComputationGraph.ConcatLists ignored -> new ComputationGraph.ConcatLists(ports.get("left"), ports.get("right"));
            case ComputationGraph.Call call -> new ComputationGraph.Call(call.module(), ports);
            case ComputationGraph.ProcedureValue call -> new ComputationGraph.ProcedureValue(call.procedure(), call.result(), ports);
			case ComputationGraph.Branch branch -> {
				var arguments = new LinkedHashMap<String, String>();
				branch.arguments().keySet().forEach(name -> arguments.put(name, ports.get("argument:" + name)));
				yield new ComputationGraph.Branch(ports.get("condition"), branch.whenTrue(), branch.whenFalse(), arguments);
			}
            case ComputationGraph.Fold fold -> {
                var captures = new LinkedHashMap<String, String>();
                fold.captures().keySet().forEach(name -> captures.put(name, ports.get("capture:" + name)));
                yield new ComputationGraph.Fold(ports.get("source"), ports.get("initial"), fold.module(),
                    fold.itemPort(), fold.accumulatorPort(), captures, fold.resultPort());
            }
            case ComputationGraph.Iterate loop -> {
                var captures = new LinkedHashMap<String, String>();
                loop.captures().keySet().forEach(name -> captures.put(name, ports.get("capture:" + name)));
                yield new ComputationGraph.Iterate(loop.mode(), ports.get("source"), loop.module(), loop.itemPort(), captures, loop.resultPort());
            }
            case ComputationGraph.Sort sort -> {
                var captures = new LinkedHashMap<String, String>();
                sort.captures().keySet().forEach(name -> captures.put(name, ports.get("capture:" + name)));
                yield new ComputationGraph.Sort(ports.get("source"), sort.module(), sort.itemPort(), captures, sort.resultPort(), sort.descending());
            }
            default -> template;
        };
    }
}
