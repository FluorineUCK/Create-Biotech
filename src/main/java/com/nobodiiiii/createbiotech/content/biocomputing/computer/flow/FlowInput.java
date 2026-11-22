package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A typed value source. A graph input reuses the ordinary pure engine, never an independent task. */
public sealed interface FlowInput {
    record Literal(DataValue value) implements FlowInput { public Literal { Objects.requireNonNull(value); } }
    record Local(String name) implements FlowInput { public Local { FlowProgram.name(name); } }
    record Expression(String entry, String output, Optional<Map<String, String>> arguments) implements FlowInput {
        public Expression(String entry, String output) { this(entry, output, Optional.empty()); }
        public Expression(String entry, String output, Map<String,String> arguments) { this(entry, output, Optional.of(arguments)); }
        public Expression {
            FlowProgram.name(entry); FlowProgram.name(output); Objects.requireNonNull(arguments);
            arguments = arguments.map(values -> new FlowCallBindings(values, Map.of()).arguments());
        }
        public String argument(String name) { return arguments.map(values -> values.get(name)).orElse(name); }
    }
    /** The slot call context survives even after the pure engine replaces its root by a commit frame. */
    record Invocation(String entry, String output, DataValue.RecordValue inputs) {
        public Invocation { FlowProgram.name(entry); FlowProgram.name(output); Objects.requireNonNull(inputs); }
        public DataValue.RecordValue value() {
            return new DataValue.RecordValue(Map.of("entry", new DataValue.TextValue(entry),
                "output", new DataValue.TextValue(output), "inputs", inputs));
        }
    }

    default void validate(DataType expected, Map<String, DataType> locals,
            ProgramCompiler.InstalledProgram compiled, String source) {
        if (!expected.equals(type(locals, compiled, source)))
            throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, source, "Input requires " + expected);
    }
    default DataType type(Map<String, DataType> locals, ProgramCompiler.InstalledProgram compiled, String source) {
        return switch (this) {
            case Literal literal -> DataType.typeOf(literal.value());
            case Local local -> locals.get(local.name());
            case Expression expression -> {
                if (!compiled.definition().entries().containsKey(expression.entry()))
                    throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, source, "Unknown input expression: " + expression.entry());
                var graph = compiled.select(expression.entry()).root();
                if (expression.arguments().isPresent() && !expression.arguments().orElseThrow().keySet().equals(graph.graph().inputs().keySet()))
                    throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, source, "Input expression bindings must match every parameter");
                graph.graph().inputs().forEach((name, type) -> {
                    String local = expression.argument(name);
                    if (!locals.containsKey(local) || !type.compatibleWith(locals.get(local)))
                        throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH, source, "Input expression parameter differs: " + name);
                });
                var output = graph.outputType().fields().get(expression.output());
                if (output == null) throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, source, "Unknown input expression output: " + expression.output());
                yield output;
            }
        };
    }
    default DataValue read(DataValue.RecordValue locals) {
        return switch (this) {
            case Literal literal -> literal.value();
            case Local local -> locals.field(local.name());
            case Expression ignored -> throw new IllegalStateException("Graph input requires budgeted evaluation");
        };
    }
}
