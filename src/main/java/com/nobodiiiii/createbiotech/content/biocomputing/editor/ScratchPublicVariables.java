package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.network.chat.Component;

/** Refactor explicit references to one declared Computer cell, never local or parameter names. */
final class ScratchPublicVariables {
    private ScratchPublicVariables() {}

    static ProgramDocument edit(ProgramDocument before, String selected, String name, DataValue value) {
        if (name == null || name.isBlank()) throw invalid("public_name_blank");
        var existing = before.archive().program().cells();
        var prior = selected == null ? null : existing.get(selected);
        if (selected != null && prior == null) throw invalid("public_name_missing", selected);
        if (!name.equals(selected) && (existing.containsKey(name)))
            throw invalid("public_name_exists", name);
        var cell = new ProgramDefinition.Cell(prior == null ? DataType.typeOf(value) : prior.type(), value);
        var cells = new LinkedHashMap<String, ProgramDefinition.Cell>();
        existing.forEach((id, definition) -> {
            if (id.equals(selected)) cells.put(name, cell);
            else cells.put(id, definition);
        });
        if (selected == null) cells.put(name, cell);
        return rewrite(before, cells, reference -> Objects.equals(reference, selected) ? name : reference);
    }

    static ProgramDocument declareList(ProgramDocument before, String name, DataType element) {
        if (name.isBlank() || before.archive().program().cells().containsKey(name))
            throw new IllegalArgumentException("Invalid or duplicate public list name: " + name);
        var cells = new LinkedHashMap<>(before.archive().program().cells());
        cells.put(name, new ProgramDefinition.Cell(new DataType.ListType(element), new DataValue.ListValue(List.of())));
        return rewrite(before, cells, UnaryOperator.identity());
    }

    static ProgramDocument remove(ProgramDocument before, String name) {
        var cells = new LinkedHashMap<>(before.archive().program().cells());
        if (cells.remove(name) == null) return before;
        return rewrite(before, cells, reference -> {
            if (reference.equals(name)) throw invalid("public_name_in_use", name);
            return reference;
        });
    }

    static IllegalArgumentException invalid(String key, Object... arguments) {
        return new IllegalArgumentException(Component.translatable("create_biotech.biocomputing.flow_editor." + key, arguments).getString());
    }


    private static ProgramDocument rewrite(ProgramDocument before, Map<String, ProgramDefinition.Cell> cells, UnaryOperator<String> name) {
        var source = before.archive().program();
        var entries = new LinkedHashMap<String, ProgramDefinition.Entry>();
        source.entries().forEach((id, entry) -> entries.put(id,
            new ProgramDefinition.Entry(graph(entry.graph(), name), writes(entry.writes(), name), entry.sends())));
        var archive = new ProgramArchive(new ProgramDefinition(graph(source.graph(), name), cells, writes(source.writes(), name),
            source.sends(), entries), before.archive().modules());
        return new ProgramDocument(archive, before.positions(), before.terminal().map(t -> terminal(t, "memory", name)),
            before.flow().map(body -> flow(body, name)));
    }

    private static List<ProgramDefinition.Write> writes(List<ProgramDefinition.Write> writes, UnaryOperator<String> name) {
        return writes.stream().map(w -> new ProgramDefinition.Write(name.apply(w.cell()), w.output())).toList();
    }

    private static ComputationGraph graph(ComputationGraph graph, UnaryOperator<String> name) {
        var nodes = new LinkedHashMap<>(graph.nodes());
        nodes.replaceAll((id, node) -> node instanceof ComputationGraph.ReadMemory read
            ? new ComputationGraph.ReadMemory(name.apply(read.cell())) : node);
        return new ComputationGraph(graph.inputs(), nodes, graph.outputs());
    }

    private static FlowDocumentBody flow(FlowDocumentBody body, UnaryOperator<String> name) {
        var canvas = body.canvas();
        var stacks = canvas.detached().stream().map(stack -> new FlowBlockLayout.Stack(stack.position(), steps(stack.steps(), name))).toList();
        var graphs = new LinkedHashMap<>(body.inline());
        graphs.replaceAll((id, expression) -> graph(expression, name));
        var parked = canvas.values().parked().stream().map(r -> new ScratchValueLayout.Reporter(r.position(),
            graph(r.graph(), name), r.arguments(), r.shadows(), r.procedureScope())).toList();
        var layout = canvas.arranged(canvas.main(), stacks).withValues(canvas.values().withParked(parked));
        return body.edited(body.inputs(), steps(body.steps(), name), body.initial(), layout, graphs);
    }

    private static List<FlowProgram.Step> steps(List<FlowProgram.Step> steps, UnaryOperator<String> name) {
        return steps.stream().map(step -> switch (step) {
            case FlowProgram.Monitor m when !m.privateLocal() -> new FlowProgram.Monitor(m.sourceId(), name.apply(m.cell()), m.visible());
            case FlowProgram.Shared s -> new FlowProgram.Shared(s.sourceId(), name.apply(s.cell()), s.value(), s.add(), s.listResult());
            case FlowProgram.Memory m -> m.cell().isEmpty() ? m
                : new FlowProgram.Memory(m.sourceId(), m.kind(), name.apply(m.cell()), m.local());
            case FlowProgram.Reference r when r.kind() == FlowProgram.ReferenceKind.ADDRESS ->
                new FlowProgram.Reference(r.sourceId(), r.kind(), name.apply(r.target()), r.local(), r.value());
            case FlowProgram.Procedure p -> {
                // Handwritten source can address a cell by name. Do not silently rewrite a fixed procedure.
                if (p.assembly().isPresent() && p.assembly().orElseThrow().hasOpcode("REF.ADDRESS")) {
                    for (String line : ProcedureAssembler.sourceLines(p.assembly().orElseThrow().text())) {
                        var tokens = ProcedureAssembler.uncomment(line).split("\\s+");
                        if (tokens.length == 3 && tokens[0].equals("REF.ADDRESS") && !name.apply(tokens[2]).equals(tokens[2]))
                            throw invalid("public_name_assembly_link", p.key().id(), tokens[2]);
                    }
                }
                yield p;
            }
            default -> step;
        }).toList();
    }

    static TerminalDefinition terminal(TerminalDefinition terminal, String root, UnaryOperator<String> name) {
        var nodes = new LinkedHashMap<>(terminal.nodes());
        nodes.replaceAll((id, node) -> switch (node) {
            case TerminalDefinition.Text t -> new TerminalDefinition.Text(binding(t.value(), root, name));
            case TerminalDefinition.Item i -> new TerminalDefinition.Item(binding(i.value(), root, name));
            case TerminalDefinition.Button b -> new TerminalDefinition.Button(binding(b.title(), root, name), binding(b.enabled(), root, name), b.target(), binding(b.inputs(), root, name));
            case TerminalDefinition.Input i -> new TerminalDefinition.Input(binding(i.initial(), root, name), binding(i.enabled(), root, name), i.target(), i.argument(), binding(i.inputs(), root, name));
            case TerminalDefinition.Repeat r -> new TerminalDefinition.Repeat(binding(r.items(), root, name), r.template());
            default -> node;
        });
        return new TerminalDefinition(terminal.root(), nodes);
    }

    private static TerminalBinding binding(TerminalBinding binding, String root, UnaryOperator<String> name) {
        if (!(binding instanceof TerminalBinding.Path path) || !path.path().input().equals(root)
                || path.path().steps().isEmpty() || !(path.path().steps().getFirst() instanceof ValuePath.Key key)) return binding;
        var steps = new ArrayList<>(path.path().steps());
        steps.set(0, new ValuePath.Key(name.apply(key.name())));
        return new TerminalBinding.Path(new ValuePath(root, steps));
    }
}
