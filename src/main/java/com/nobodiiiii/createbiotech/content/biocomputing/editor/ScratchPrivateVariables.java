package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Source refactor for declared main-frame locals. Separate definition stacks own their own names. */
final class ScratchPrivateVariables {
    private ScratchPrivateVariables() {}

    static ProgramDocument edit(ProgramDocument before, String selected, String name, DataValue value) {
        if (name == null || name.isBlank()) throw ScratchPublicVariables.invalid("public_name_blank");
        var body = before.flow().orElseThrow();
        var prior = selected == null ? null : body.inputs().get(selected);
        if (selected != null && prior == null) throw ScratchPublicVariables.invalid("private_name_missing", selected);
        if (!name.equals(selected) && body.inputs().containsKey(name)) throw ScratchPublicVariables.invalid("private_name_exists", name);
        if (prior != null && !prior.accepts(value))
            throw ScratchPublicVariables.invalid("private_initial_type", selected);

        var types = new LinkedHashMap<String, DataType>();
        var values = new LinkedHashMap<String, DataValue>();
        body.inputs().forEach((id, type) -> {
            types.put(id.equals(selected) ? name : id, type);
            values.put(id.equals(selected) ? name : id, id.equals(selected) ? value : body.initial().field(id));
        });
        if (selected == null) { types.put(name, DataType.typeOf(value)); values.put(name, value); }
        if (selected == null || name.equals(selected))
            return withBody(before, body.edited(types, body.steps(), new DataValue.RecordValue(values), body.canvas(), body.inline()));
        return rewrite(before, types, values, reference -> {
            if (reference.equals(name)) throw ScratchPublicVariables.invalid("private_name_exists", name);
            return reference.equals(selected) ? name : reference;
        });
    }

    static ProgramDocument remove(ProgramDocument before, String name) {
        var body = before.flow().orElseThrow();
        var types = new LinkedHashMap<>(body.inputs());
        if (types.remove(name) == null) return before;
        var values = new LinkedHashMap<>(body.initial().fields());
        values.remove(name);
        return rewrite(before, types, values, reference -> {
            if (reference.equals(name)) throw ScratchPublicVariables.invalid("private_name_in_use", name);
            return reference;
        });
    }

    private static ProgramDocument withBody(ProgramDocument before, FlowDocumentBody body) {
        return new ProgramDocument(before.archive(), before.positions(), before.terminal(), Optional.of(body));
    }

    private static ProgramDocument rewrite(ProgramDocument before, Map<String, DataType> types, Map<String, DataValue> values,
            UnaryOperator<String> name) {
        var body = before.flow().orElseThrow();
        var archive = before.archive().program();
        var canvas = body.canvas();
        var stacks = canvas.detached().stream().map(stack -> stack.steps().getFirst() instanceof FlowProgram.Procedure
            ? stack : new FlowBlockLayout.Stack(stack.position(), steps(stack.steps(), archive, name))).toList();
        var parked = canvas.values().parked().stream().map(r -> r.procedureScope().isPresent()
            ? r : new ScratchValueLayout.Reporter(r.position(), r.graph(), bindings(r.arguments(), name), r.shadows())).toList();
        var layout = canvas.arranged(canvas.main(), stacks).withValues(canvas.values().withParked(parked));
        return new ProgramDocument(before.archive(), before.positions(),
            before.terminal().map(t -> ScratchPublicVariables.terminal(t, "output", name)),
            Optional.of(body.edited(types, steps(body.steps(), archive, name), new DataValue.RecordValue(values), layout, body.inline())));
    }

    private static Map<String, String> bindings(Map<String, String> source, UnaryOperator<String> name) {
        var result = new LinkedHashMap<>(source);
        result.replaceAll((port, local) -> name.apply(local));
        return result;
    }

    private static Map<String, String> identity(Set<String> ports) {
        var result = new LinkedHashMap<String, String>();
        ports.forEach(port -> result.put(port, port));
        return result;
    }

    private static FlowInput input(FlowInput input, ProgramDefinition archive, UnaryOperator<String> name) {
        return switch (input) {
            case FlowInput.Literal literal -> literal;
            case FlowInput.Local local -> new FlowInput.Local(name.apply(local.name()));
            case FlowInput.Expression expression -> {
                var args = expression.arguments().orElseGet(() -> {
                    var entry = archive.entries().get(expression.entry());
                    return entry == null ? Map.of() : identity(entry.graph().inputs().keySet());
                });
                var next = bindings(args, name);
                yield next.equals(args) ? expression : new FlowInput.Expression(expression.entry(), expression.output(), next);
            }
        };
    }

    private static FlowCallBindings call(FlowCallBindings bindings, UnaryOperator<String> name) {
        return new FlowCallBindings(bindings(bindings.arguments(), name), bindings(bindings.returns(), name));
    }

    private static List<FlowProgram.Step> steps(List<FlowProgram.Step> steps, ProgramDefinition archive, UnaryOperator<String> name) {
        return steps.stream().map(step -> {
            var value = FlowProgram.valueInput(step);
            if (value != null && !(step instanceof FlowProgram.Reference) && !(step instanceof FlowProgram.Shared))
                return FlowInlineValues.withInput(step, input(value, archive, name));
            return switch (step) {
                case FlowProgram.Monitor m when m.privateLocal() -> new FlowProgram.Monitor(m.sourceId(), name.apply(m.cell()), m.visible(), true);
                case FlowProgram.Shared s -> new FlowProgram.Shared(s.sourceId(), s.cell(), input(s.value(), archive, name), s.add(), s.listResult().map(name));
                case FlowProgram.Reference r -> new FlowProgram.Reference(r.sourceId(), r.kind(),
                    r.kind() == FlowProgram.ReferenceKind.ADDRESS ? r.target() : name.apply(r.target()),
                    r.local().isEmpty() ? r.local() : name.apply(r.local()), r.value().map(v -> input(v, archive, name)));
                case FlowProgram.Atomic a -> new FlowProgram.Atomic(a.sourceId(), name.apply(a.result()));
                case FlowProgram.Memory m -> new FlowProgram.Memory(m.sourceId(), m.kind(), m.cell(), m.local().isEmpty() ? m.local() : name.apply(m.local()));
                case FlowProgram.Invoke i -> new FlowProgram.Invoke(i.sourceId(), i.procedure(), call(i.bindings(), name),
                    i.operands().map(o -> new FlowProgram.InvokeOperands(o.entry(), bindings(o.inputs(), name))));
                case FlowProgram.Evaluate e -> {
                    var entry = archive.entries().get(e.expression());
                    var old = e.bindings().orElseGet(() -> entry == null ? new FlowCallBindings(Map.of(), Map.of())
                        : new FlowCallBindings(identity(entry.graph().inputs().keySet()), identity(entry.graph().outputs().keySet())));
                    var next = call(old, name);
                    yield next.equals(old) ? e : new FlowProgram.Evaluate(e.sourceId(), e.expression(), next);
                }
                case FlowProgram.Watch w -> new FlowProgram.Watch(w.sourceId(), w.handler(), input(w.condition(), archive, name));
                case FlowProgram.TryLaunch t -> new FlowProgram.TryLaunch(t.sourceId(), t.procedure(), name.apply(t.started()));
                case FlowProgram.TaskStart t -> new FlowProgram.TaskStart(t.sourceId(), t.procedure(), bindings(t.arguments(), name), name.apply(t.handle()));
                case FlowProgram.TaskJoin t -> new FlowProgram.TaskJoin(t.sourceId(), t.procedure(), name.apply(t.handle()), name.apply(t.outcome()));
                case FlowProgram.Correlation c -> new FlowProgram.Correlation(c.sourceId(), name.apply(c.local()));
                case FlowProgram.SerialSend s -> new FlowProgram.SerialSend(s.sourceId(), s.port(), name.apply(s.local()), name.apply(s.result()));
                case FlowProgram.SerialReceive s -> new FlowProgram.SerialReceive(s.sourceId(), s.port(), name.apply(s.local()), s.nativeNbt());
                case FlowProgram.SerialStatus s -> new FlowProgram.SerialStatus(s.sourceId(), s.port(), name.apply(s.local()), s.claimReceiveFaults());
                case FlowProgram.Read r -> new FlowProgram.Read(r.sourceId(), r.port(), name.apply(r.local()), r.type(), r.result());
                case FlowProgram.Write w -> new FlowProgram.Write(w.sourceId(), w.port(), name.apply(w.local()), w.result().map(name));
                case FlowProgram.AwaitReadable r -> new FlowProgram.AwaitReadable(r.sourceId(), r.port(), r.result().map(name));
                case FlowProgram.SignalRead r -> new FlowProgram.SignalRead(r.sourceId(), r.port(), name.apply(r.local()));
                case FlowProgram.SignalWrite w -> new FlowProgram.SignalWrite(w.sourceId(), w.port(), name.apply(w.local()));
                case FlowProgram.XBusStatus x -> new FlowProgram.XBusStatus(x.sourceId(), x.port(), name.apply(x.local()));
                default -> step;
            };
        }).toList();
    }
}
