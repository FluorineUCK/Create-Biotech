package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** Read-only descriptions from a single acknowledged installation, never from live values or the editable draft. */
final class ScratchDebugSource {
    private static final int PREVIEW_LENGTH = 112;
    private static final int PREVIEW_DEPTH = 4;
    private static final int PREVIEW_NODES = 12;
    private final Map<String, FlowProgram.Step> steps = new LinkedHashMap<>();
    private final Map<String, FlowProgram.Procedure> owners = new LinkedHashMap<>();
    private final FlowDocumentBody body;

    ScratchDebugSource(ProgramDocument document, FlowDocumentBody body) {
        this.body = body;
        // Use the same linked source ids as the installed program (including event/custom bodies, not parked drafts).
        FlowProgram.Procedure owner = null;
        for (var step : body.program(document.archive()).steps()) {
            if (step instanceof FlowProgram.Procedure procedure) owner = procedure;
            steps.put(step.sourceId(), step);
            if (owner != null) owners.put(step.sourceId(), owner);
            if (step instanceof FlowProgram.EndProcedure) owner = null;
        }
        // Retain authored operation tags (for example list mutation) before inline names are lowered.
        body.steps().forEach(this::keepAuthoredStep);
        body.canvas().detached().forEach(stack -> stack.steps().forEach(this::keepAuthoredStep));
    }

    private void keepAuthoredStep(FlowProgram.Step step) { steps.replace(step.sourceId(), step); }

    static ScratchDebugSource decode(ProgramEditorResponse installed) {
        byte[] archive = installed.archive();
        if (archive.length == 0) return null;
        try {
            var document = ProgramDocumentWire.decode(archive, installed.archiveLimits());
            return document.flow().map(body -> new ScratchDebugSource(document, body)).orElse(null);
        } catch (IllegalArgumentException | ComputationException invalid) {
            // An invalid source archive cannot change or discredit the runtime observation.
            return null;
        }
    }

    static Component location(ScratchDebugSource source, DataValue id) {
        if (source != null && id instanceof DataValue.TextValue name)
            return source.label(name.value()).orElseGet(() -> text("source_unknown"));
        return text("source_unknown");
    }

    static List<Component> frames(ScratchDebugSource source, DataValue value) {
        if (!(value instanceof DataValue.ListValue stack)) return List.of();
        return stack.values().stream().map(frame -> {
            if (frame instanceof DataValue.RecordValue call
                    && call.fields().get("procedure") instanceof DataValue.TextValue procedure
                    && call.fields().get("revision") instanceof DataValue.IntegerValue revision)
                return text("stack_frame", clip(procedure.value(), 48), revision.value(),
                    source == null ? text("source_unknown") : source.callOrigin(call.fields().get("source"), procedure.value(), revision.value()));
            return text("stack_frame_unknown");
        }).toList();
    }

    private Component callOrigin(DataValue id, String callee, long revision) {
        if (!(id instanceof DataValue.TextValue name) || !steps.containsKey(name.value())) return text("source_unknown");
        var owner = owners.get(name.value());
        var scope = editor("main_hat");
        if (owner != null && !owner.startup()) {
            if (owner.event().isPresent()) scope = editor("receive_broadcast_hat", clip(owner.event().orElseThrow(), 48));
            else if (owner.terminalKey().isPresent()) scope = editor("terminal_key_hat", clip(owner.terminalKey().orElseThrow(), 48));
            else {
                var button = body.canvas().terminalHats().get(owner.sourceId());
                scope = button != null ? editor("terminal_hat", clip(button, 48))
                    : Component.literal(clip(owner.key().id(), 48) + " @ " + owner.key().revision());
            }
        }
        // A direct call's label would only repeat the callee. An enclosing expression is still useful.
        if (steps.get(name.value()) instanceof FlowProgram.Invoke invoke && invoke.procedure().id().equals(callee)
                && invoke.procedure().revision() == revision) return scope;
        return scope.copy().append(" · ").append(location(this, id));
    }

    static DataValue.RecordValue taskFields(ComputerProgramMenu menu, long revision, DataValue.RecordValue frozen) {
        var source = menu.installedSource(revision).map(ScratchDebugSource::decode).orElse(null);
        var fields = frozen.fields();
        var displayed = new LinkedHashMap<String, DataValue>();
        var raw = new LinkedHashMap<String, DataValue>();
        var phase = fields.get("phase");
        var here = fields.get("source");
        Component block = new DataValue.TextValue("COMPLETE").equals(phase) || new DataValue.TextValue("CANCELLED").equals(phase)
            ? text("task_source_inactive") : location(source, here);
        displayed.put(text("task_current_block").getString(), new DataValue.TextValue(block.getString()));
        var calls = frames(source, fields.get("call_stack"));
        if (!calls.isEmpty())
            displayed.put(text("stack_title").getString(), new DataValue.ListValue(calls.stream()
                .<DataValue>map(frame -> new DataValue.TextValue(frame.getString())).toList()));
        if (fields.get("diagnostic") instanceof DataValue.RecordValue failure)
            displayed.put(text("field.diagnostic").getString(), new DataValue.TextValue(diagnostic(source, failure, here).getString()));
        fields.forEach((key, value) -> {
            switch (key) {
                case "source", "call_stack", "diagnostic" -> raw.put(key, value);
                default -> displayed.put(key, value);
            }
        });
        if (!raw.isEmpty()) displayed.put(text("advanced").getString(), new DataValue.RecordValue(raw));
        return new DataValue.RecordValue(displayed);
    }

    Optional<Component> label(String id) {
        var step = steps.get(id);
        if (step == null) return Optional.empty();
        var graph = body.inline().get(id);
        var extension = ScratchExtensionMouth.binding(step, graph);
        if (extension.isPresent()) return Optional.of(Component.translatable(extension.orElseThrow().command().labelKey()));
        if (step instanceof FlowProgram.Procedure procedure && ScratchExtensions.reserved(procedure.key()))
            return Optional.of(FlowBlockCatalog.extensionValueLabel(procedure.key()));
        var named = FlowNamedLists.target(step, graph);
        if (named.isPresent()) {
            var list = named.orElseThrow();
            var name = list.shared() ? editor("list_shared_target", clip(list.local(), 48)) : Component.literal(clip(list.local(), 48));
            String operation = list.operation() == ListOperation.SLICE ? "clear" : list.operation().name().toLowerCase(Locale.ROOT);
            return Optional.of(text("source_list_" + operation, name));
        }
        if (step instanceof FlowProgram.Evaluate evaluate) {
            if (graph != null && graph.outputs().containsKey("value") && graph.outputs().size() == 1 && evaluate.bindings().isPresent()) {
                var bindings = evaluate.bindings().orElseThrow();
                var expression = preview(graph, bindings.arguments(), graph.outputs().get("value"));
                String target = bindings.returns().get("value");
                return Optional.of(target == null ? text("source_discard", expression) : text("source_assign", clip(target, 48), expression));
            }
            return Optional.of(graph == null ? text("source_named_calculation", clip(evaluate.expression(), 48))
                : editor("inline_expression_label"));
        }
        if (step instanceof FlowProgram.Shared shared && graph != null && graph.outputs().containsKey("value")) {
            var expression = preview(graph, FlowInlineValues.bindings(step).arguments(), graph.outputs().get("value"));
            return Optional.of(text(shared.add() ? "source_public_add" : "source_public_set", clip(shared.cell(), 48), expression));
        }
        return Optional.of(ComputerFlowScreen.blockLabel(step, graph));
    }

    private static String preview(ComputationGraph graph, Map<String, String> bindings, String id) {
        return clip(operand(graph, bindings, id, 0, new int[]{PREVIEW_NODES}), PREVIEW_LENGTH);
    }

    private static String operand(ComputationGraph graph, Map<String, String> bindings, String id, int depth, int[] budget) {
        if (id == null || depth >= PREVIEW_DEPTH || budget[0]-- <= 0) return "…";
        var node = graph.nodes().get(id);
        if (node == null) return "…";
        if (node instanceof ComputationGraph.Constant c) return literal(c.value());
        if (node instanceof ComputationGraph.Input in)
            return clip(bindings.getOrDefault(in.name(), editor("inline_expression_label").getString()), 48);
        String label = InlineGraphView.label(node);
        if (node instanceof ComputationGraph.MakeList list) return label + " (" + list.elements().size() + ")";
        if (node instanceof ComputationGraph.MakeRecord record) return label + " (" + record.fields().size() + ")";
        var ports = NodePorts.bindings(node);
        if (InlineGraphView.infix(node) && ports.containsKey("left") && ports.containsKey("right")) {
            String result = operand(graph, bindings, ports.get("left"), depth + 1, budget) + " " + label + " "
                + operand(graph, bindings, ports.get("right"), depth + 1, budget);
            return depth == 0 ? result : "(" + result + ")";
        }
        if (node instanceof ComputationGraph.Scalar scalar && label.equals(scalar.operation().name()))
            label = Component.translatable("create_biotech.biocomputing.operation.name." + label.toLowerCase(Locale.ROOT)).getString();
        if (label.endsWith(" ▾")) label = label.substring(0, label.length() - 2);
        label = clip(label, 48);
        if (node instanceof ComputationGraph.ReadMemory || ports.isEmpty()) return label;
        var arguments = ports.values().stream().limit(3).map(child -> operand(graph, bindings, child, depth + 1, budget)).toList();
        return label + " [" + String.join(", ", arguments) + (ports.size() > arguments.size() ? ", …]" : "]");
    }

    private static String literal(DataValue value) {
        return switch (value) {
            case DataValue.BooleanValue flag -> Boolean.toString(flag.value());
            case DataValue.IntegerValue n -> Long.toString(n.value());
            case DataValue.DecimalValue n -> Double.toString(n.value());
            case DataValue.TextValue t -> "\"" + clip(t.value(), 36) + "\"";
            case DataValue.NullValue ignored -> "null";
            default -> editor("inline_expression_label").getString();
        };
    }

    static Component diagnostic(ScratchDebugSource source, DataValue value, DataValue currentSource) {
        if (!(value instanceof DataValue.RecordValue record))
            return Component.literal(ComputerValueDisplay.lines(value, Integer.MAX_VALUE).getFirst());
        var fields = record.fields();
        String code = fields.get("code") instanceof DataValue.TextValue key ? clip(key.value(), 48) : "";
        String message = fields.get("message") instanceof DataValue.TextValue detail ? clip(detail.value(), 240) : "";
        var summary = code.isEmpty() ? Component.literal(message) : text("fault_message", code, message);
        if (source != null && fields.get("location") instanceof DataValue.TextValue location && !location.equals(currentSource))
            return source.label(location.value()).map(label -> text("fault_at", label, summary)).orElse(summary);
        return summary;
    }

    private static String clip(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String oneLine = text.substring(0, end).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return end == text.length() ? oneLine : oneLine + "…";
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.debug." + key, args);
    }

    private static Component editor(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args);
    }
}
