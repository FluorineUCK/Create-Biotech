package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Editor projection of an existing value slot. It never evaluates a graph or reads runtime locals. */
final class FlowInlineValues {
    private FlowInlineValues() {}
    static net.minecraft.network.chat.Component waitUnitLabel(FlowProgram.WaitUnit unit) {
        return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.wait_unit_"
            + unit.name().toLowerCase(Locale.ROOT));
    }
    static DataType expected(FlowProgram.Step step) { return step instanceof FlowProgram.Shared || step instanceof FlowProgram.Reference ? DataType.Scalar.ANY : step instanceof FlowProgram.Repeat || step instanceof FlowProgram.Sleep ? DataType.Scalar.INTEGER : step instanceof FlowProgram.Ask ? DataType.Scalar.TEXT : DataType.Scalar.BOOLEAN; }
    static ComputationGraph graph(FlowDocumentBody body, FlowProgram.Step step) {
        return graph(body, step, expected(step));
    }
    /** The screen supplies the declared public variable type; the canvas only needs a visual projection. */
    static ComputationGraph graph(FlowDocumentBody body, FlowProgram.Step step, DataType expected) {
        if (step instanceof FlowProgram.Sleep && body.execution() != FlowProgram.Execution.REGISTER_V1) return null;
        var stored = body.inline().get(step.sourceId()); if (stored != null) return stored;
        var input = FlowProgram.valueInput(step);
        if (input instanceof FlowInput.Literal literal)
            return new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(literal.value())), Map.of("value","value"));
        if (input instanceof FlowInput.Local local)
            return new ComputationGraph(Map.of(local.name(), expected), Map.of("value",new ComputationGraph.Input(local.name())),Map.of("value","value"));
        return null; // An old named expression remains an explicit graph call, not a copied anonymous graph.
    }
    static FlowCallBindings bindings(FlowProgram.Step step) {
        if (step instanceof FlowProgram.Evaluate call) return call.bindings().orElseThrow();
        if (step instanceof FlowProgram.Invoke call && call.operands().isPresent())
            return new FlowCallBindings(call.operands().orElseThrow().inputs(), Map.of());
        var input=FlowProgram.valueInput(step);
        var arguments = input instanceof FlowInput.Expression expression ? expression.arguments().orElseThrow()
            : input instanceof FlowInput.Local local ? Map.of(local.name(),local.name()) : Map.<String,String>of();
        return new FlowCallBindings(arguments, Map.of());
    }
    static FlowProgram.Step withInput(FlowProgram.Step step, FlowInput input) {
        if (step instanceof FlowProgram.Ask) return new FlowProgram.Ask(step.sourceId(), input);
        if (step instanceof FlowProgram.Sleep sleep) return new FlowProgram.Sleep(step.sourceId(), input, sleep.unit());
        if(step instanceof FlowProgram.Reference ref && ref.kind()==FlowProgram.ReferenceKind.WRITE)return new FlowProgram.Reference(ref.sourceId(),ref.kind(),ref.target(),ref.local(),Optional.of(input));
        if (step instanceof FlowProgram.Shared shared) return new FlowProgram.Shared(step.sourceId(),shared.cell(),input,shared.add(),shared.listResult());
        if (step instanceof FlowProgram.Repeat) return new FlowProgram.Repeat(step.sourceId(),input);
        if (step instanceof FlowProgram.Watch watch) return new FlowProgram.Watch(watch.sourceId(),watch.handler(),input);
        if (step instanceof FlowProgram.WaitUntil) return new FlowProgram.WaitUntil(step.sourceId(), input);
        if (step instanceof FlowProgram.Until) return new FlowProgram.Until(step.sourceId(),input);
        if (step instanceof FlowProgram.If) return new FlowProgram.If(step.sourceId(),input);
        throw new IllegalArgumentException("Step has no control value slot");
    }
    static boolean owned(FlowProgram.Step step) {
        return step instanceof FlowProgram.Invoke invoke && invoke.operands().isPresent()
            || step instanceof FlowProgram.Evaluate call && call.bindings().isPresent()
            || FlowProgram.valueInput(step) instanceof FlowInput.Expression expression && expression.arguments().isPresent();
    }
    static FlowProgram.Invoke operands(FlowProgram.Invoke call, String entry, Map<String,String> inputs) {
        return new FlowProgram.Invoke(call.sourceId(), call.procedure(), call.bindings(),
            Optional.of(new FlowProgram.InvokeOperands(entry, inputs)));
    }
    static FlowProgram.Step lower(FlowProgram.Step step, String entry) {
        if (step instanceof FlowProgram.Evaluate call) return new FlowProgram.Evaluate(call.sourceId(),entry,call.bindings());
        if (step instanceof FlowProgram.Invoke call) return operands(call, entry, call.operands().orElseThrow().inputs());
        var expression=(FlowInput.Expression)FlowProgram.valueInput(step);
        return withInput(step,new FlowInput.Expression(entry,expression.output(),expression.arguments()));
    }
}
