package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.ArrayList;
import java.util.Objects;

/** Editing sugar only: emits ordinary Input/Field/Index nodes and never embeds snapshot values. */
public final class PathNodeBuilder {
    public record Insertion(String output, String node) {}
    private PathNodeBuilder() {}

    public static Insertion insert(ProgramDraft draft, DataValue.RecordValue frozenInputs, ValuePath path, double x, double y) {
        Objects.requireNonNull(draft); Objects.requireNonNull(path);
        path.resolve(frozenInputs);
        var declaration = draft.inputs().get(path.input());
        if (declaration != null && !declaration.accepts(frozenInputs.field(path.input())))
            throw new IllegalArgumentException("Existing input declaration does not accept this snapshot");
        var definitions = new ArrayList<ComputationGraph.Node>();
        definitions.add(new ComputationGraph.Input(path.input()));
        for (var step : path.steps()) definitions.add(step instanceof ValuePath.Key key
            ? new ComputationGraph.Field("source", key.name()) : new ComputationGraph.Index("source", ((ValuePath.Index)step).index()));
        for (int i = 0; i < definitions.size(); i++)
            if (!Double.isFinite(x) || !Double.isFinite(y + i * 80d)) throw new IllegalArgumentException("Non-finite canvas coordinate");
        String output = "read_value";
        for (long suffix = 2; draft.outputs().containsKey(output); suffix++) output = "read_value_" + suffix;
        // All fallible selection/type/layout validation precedes draft mutations.
        if (declaration == null) draft.putInput(path.input(), DataType.Scalar.ANY);
        String source = null;
        for (int i = 0; i < definitions.size(); i++) {
            String id = draft.add(definitions.get(i), x, y + i * 80d);
            if (source != null) draft.connect(id, "source", source);
            source = id;
        }
        draft.output(output, source);
        return new Insertion(output, source);
    }
}
