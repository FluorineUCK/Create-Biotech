package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.MenuOption;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Project a declared menu onto its root literal. Expressions keep the usual reporter behavior. */
final class ScratchExtensionMenus {
    private ScratchExtensionMenus() {}

    private record Capture(DataValue.RecordValue components, DataValue.RecordValue types) {
        Optional<MenuOption> option(String id) {
            var value = components.fields().get(id);
            if (value == null || value instanceof DataValue.ErrorValue
                || !(types.fields().get(id) instanceof DataValue.RecordValue type)
                || !(type.fields().get("label") instanceof DataValue.TextValue label) || label.value().isBlank()) return Optional.empty();
            return Optional.of(new MenuOption(label.value(), new DataValue.TextValue(id)));
        }
    }
    record Selection(Component caption, Optional<String> componentId) {
        Component tooltip(boolean shift) {
            if (componentId.isEmpty()) return Component.empty();
            var id = componentId.orElseThrow();
            return shift && !caption.getString().equals(id)
                ? caption.copy().append("\n").append(Component.literal(id)) : caption;
        }
    }

    static List<MenuOption> at(FlowProgram.Step step, ComputationGraph graph, String node) {
        if (node == null || !(graph.nodes().get(node) instanceof ComputationGraph.Constant)) return List.of();
        if (step instanceof FlowProgram.Invoke call && ScratchExtensions.reserved(call.procedure())) {
            var matching = graph.outputs().entrySet().stream().filter(port -> node.equals(port.getValue())).toList();
            if (matching.size() == 1) {
                String argument = matching.getFirst().getKey();
                var direct = ScratchExtensions.binding(call.procedure())
                    .map(binding -> binding.command().menus().getOrDefault(argument, List.of())).orElseGet(List::of);
                if (!direct.isEmpty()) return editable(graph, node) ? direct : List.of();
            }
        }
        var pending = new ArrayDeque<>(graph.outputs().values());
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!seen.add(id)) continue;
            var value = graph.nodes().get(id);
            if (value == null) continue;
            if (value instanceof ComputationGraph.ProcedureValue parent && parent.arguments().containsValue(node)) {
                var options = nested(graph, parent, node);
                if (!options.isEmpty()) return options;
            }
            pending.addAll(value.dependencies());
        }
        return List.of();
    }

    static List<MenuOption> nested(ComputationGraph graph, ComputationGraph.ProcedureValue parent, String node) {
        if (!ScratchExtensions.reserved(parent.procedure())
            || !(graph.nodes().get(node) instanceof ComputationGraph.Constant)) return List.of();
        var binding = ScratchExtensions.binding(parent.procedure()).orElse(null);
        if (binding == null || binding.command().menus().isEmpty()) return List.of();
        String argument = null;
        for (var port : parent.arguments().entrySet()) if (node.equals(port.getValue())) {
            if (argument != null) return List.of();
            argument = port.getKey();
        }
        if (argument == null) return List.of();
        var options = binding.command().menus().getOrDefault(argument, List.of());
        return options.isEmpty() || editable(graph, node) ? options : List.of();
    }

    private static boolean editable(ComputationGraph graph, String node) {
        try {
            FlowReporterEdits.requireUnshared(graph, node);
            return true;
        } catch (IllegalArgumentException shared) {
            return false;
        }
    }

    static InlineGraphView.Tile decorate(ComputationGraph graph, InlineGraphView.Tile slot, List<MenuOption> options,
                                        java.util.function.ToIntFunction<String> measure) {
        if (options.isEmpty()) return slot;
        var value = ((ComputationGraph.Constant)graph.nodes().get(slot.node())).value();
        return decorate(slot, caption(options, value, slot.label()).getString(), measure);
    }

    static InlineGraphView.Tile decorateReader(FlowProgram.Step step, ComputationGraph graph, InlineGraphView.Tile slot,
            java.util.function.ToIntFunction<String> measure, java.util.function.Supplier<Optional<DataValue.RecordValue>> capture) {
        var choice = readerSelection(step, graph, slot.node(), capture);
        return choice.isPresent() ? decorate(slot, choice.orElseThrow().caption().getString(), measure)
            : decorate(graph, slot, at(step, graph, slot.node()), measure);
    }

    private static InlineGraphView.Tile decorate(InlineGraphView.Tile slot, String caption, java.util.function.ToIntFunction<String> measure) {
        int end = caption.offsetByCodePoints(0, Math.min(40, caption.codePointCount(0, caption.length())));
        while (end > 0 && measure.applyAsInt(caption.substring(0, end)) > 82) end = caption.offsetByCodePoints(end, -1);
        caption = caption.substring(0, end) + " ▾";
        return new InlineGraphView.Tile(slot.node(), caption, slot.bool(), Math.max(26, measure.applyAsInt(caption) + 12),
            slot.height(), slot.labelX(), slot.children(), false, true, slot.category());
    }

    private static Optional<ScratchExtension.CapturedReaderComponents> readerDescriptor(FlowProgram.Step step,
            ComputationGraph graph, String node) {
        if (!(step instanceof FlowProgram.Invoke call) || node == null
            || !(graph.nodes().get(node) instanceof ComputationGraph.Constant)) return Optional.empty();
        var descriptors = ScratchExtensions.binding(call.procedure()).map(binding -> binding.command().capturedMenus()).orElseGet(Map::of);
        if (descriptors.isEmpty()) return Optional.empty();
        var argument = graph.outputs().entrySet().stream().filter(entry -> node.equals(entry.getValue())).toList();
        if (argument.size() != 1) return Optional.empty();
        var axes = descriptors.get(argument.getFirst().getKey());
        return axes != null && editable(graph, node) ? Optional.of(axes) : Optional.empty();
    }

    static Optional<String> readerKey(FlowProgram.Step step, ComputationGraph graph, String node) {
        return readerDescriptor(step, graph, node).flatMap(axes -> readerKey(graph, axes));
    }

    private static Optional<String> readerKey(ComputationGraph graph, ScratchExtension.CapturedReaderComponents axes) {
        var coordinates = new StringJoiner(",");
        for (String axis : List.of(axes.x(), axes.y(), axes.z())) {
            var source = graph.outputs().get(axis);
            if (source == null || !(graph.nodes().get(source) instanceof ComputationGraph.Constant literal)
                || !(literal.value() instanceof DataValue.IntegerValue position)
                || position.value() < Integer.MIN_VALUE || position.value() > Integer.MAX_VALUE) return Optional.empty();
            coordinates.add(Long.toString(position.value()));
        }
        return Optional.of(coordinates.toString());
    }

    /** A caption is presentation only: the draft still stores precisely the selected ID. */
    static Optional<Selection> readerSelection(FlowProgram.Step step, ComputationGraph graph, String node,
            java.util.function.Supplier<Optional<DataValue.RecordValue>> capture) {
        return readerDescriptor(step, graph, node).flatMap(axes -> {
            var value = ((ComputationGraph.Constant)graph.nodes().get(node)).value();
            if (!(value instanceof DataValue.TextValue text)) return Optional.empty();
            var baseline = at(step, graph, node);
            String id = text.value();
            var named = baseline.stream().filter(option -> option.value().equals(value)).findFirst()
                .or(() -> readerCapture(readerKey(graph, axes), capture.get()).flatMap(found -> found.option(id)));
            Component caption = named.<Component>map(option -> Component.translatableWithFallback(option.labelKey(), id))
                .orElseGet(() -> {
                    var location = ResourceLocation.tryParse(id);
                    if (location == null || !location.toString().equals(id) || !location.getNamespace().equals("create_biotech"))
                        return Component.literal(id);
                    // Static client translations describe component kinds, not a target or its permissions.
                    return Component.translatableWithFallback("create_biotech.biocomputing.component." + location.getPath(), id);
                });
            return Optional.of(new Selection(caption, Optional.of(id)));
        });
    }

    static List<MenuOption> suggestions(List<MenuOption> baseline, Optional<String> reader, Optional<DataValue.RecordValue> capture) {
        return choices(baseline, reader, capture).options();
    }

    private enum SuggestionState { NOT_REQUESTED, TARGET_MISSING, NO_USABLE_COMPONENTS, AVAILABLE }
    record Choices(List<MenuOption> options, Optional<String> reader, SuggestionState state) {}

    static Choices choices(List<MenuOption> baseline, Optional<String> reader, Optional<DataValue.RecordValue> capture) {
        // Use only the correlated, actor-checked capture for this target; a network capture has no peripheral root.
        if (reader.isEmpty() || capture.isEmpty()
            || !(capture.orElseThrow().fields().get("peripherals") instanceof DataValue.RecordValue root))
            return new Choices(baseline, reader, SuggestionState.NOT_REQUESTED);
        if (!(root.fields().get("readers") instanceof DataValue.RecordValue readers)
            || !readers.fields().containsKey(reader.orElseThrow()))
            return new Choices(baseline, reader, SuggestionState.TARGET_MISSING);
        var sampled = readerCapture(reader, capture).orElse(null);
        if (sampled == null) return new Choices(baseline, reader, SuggestionState.NO_USABLE_COMPONENTS);
        var staticById = new HashMap<DataValue, MenuOption>();
        baseline.forEach(option -> staticById.put(option.value(), option));
        var options = new ArrayList<MenuOption>();
        for (var id : new TreeSet<>(sampled.components().fields().keySet())) {
            if (options.size() == ScratchExtension.MAX_MENU_OPTIONS) break;
            sampled.option(id).ifPresent(option -> options.add(staticById.getOrDefault(option.value(), option)));
        }
        return options.isEmpty() ? new Choices(baseline, reader, SuggestionState.NO_USABLE_COMPONENTS)
            : new Choices(List.copyOf(options), reader, SuggestionState.AVAILABLE);
    }

    private static Optional<Capture> readerCapture(Optional<String> reader, Optional<DataValue.RecordValue> capture) {
        if (reader.isEmpty() || capture.isEmpty()) return Optional.empty();
        // Use only the actor-checked interactive capture for this request and this reader.
        if (!(capture.orElseThrow().fields().get("peripherals") instanceof DataValue.RecordValue root)
            || !(root.fields().get("readers") instanceof DataValue.RecordValue readers)
            || !(readers.fields().get(reader.orElseThrow()) instanceof DataValue.RecordValue sample)
            || !(sample.fields().get("components") instanceof DataValue.RecordValue components)
            || !(root.fields().get("component_types") instanceof DataValue.RecordValue types)) return Optional.empty();
        return Optional.of(new Capture(components, types));
    }

    // Only the current, correlated peripheral reply is supplied by the menu.
    record CaptureFeedback(Component caption, Component tooltip) {}
    static CaptureFeedback captureFeedback(Choices choices, boolean pending, Optional<ProgramStatusResponse> failure) {
        var reply = failure.orElse(null);
        String key = pending ? "extension_menu_refreshing" : "extension_menu_refresh";
        var tooltip = captureText("extension_menu_refresh_hint");
        if (!pending && reply != null) {
            key = switch (reply.code()) {
                case "too_large" -> "extension_menu_failure_too_large";
                case "unavailable" -> "extension_menu_failure_unavailable";
                case "invalid" -> reply.detail().equals("access_denied")
                    ? "extension_menu_failure_access_denied" : "extension_menu_failure";
                default -> "extension_menu_failure";
            };
            if (!reply.detail().isBlank()) tooltip.append("\n").append(Component.literal(reply.detail()));
        } else if (!pending) {
            switch (choices.state()) {
                case TARGET_MISSING -> {
                    key = "extension_menu_target_not_captured";
                    tooltip.append("\n").append(captureText("extension_menu_target_help", choices.reader().orElseThrow().replace(",", ", ")));
                }
                case NO_USABLE_COMPONENTS -> {
                    key = "extension_menu_no_suggestions";
                    tooltip.append("\n").append(captureText("extension_menu_no_suggestions_help"));
                }
                case AVAILABLE -> {
                    return new CaptureFeedback(captureText("extension_menu_suggestions", choices.options().size()), tooltip);
                }
                case NOT_REQUESTED -> { }
            }
        }
        return new CaptureFeedback(captureText(key), tooltip);
    }

    private static net.minecraft.network.chat.MutableComponent captureText(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args);
    }

    static Component label(MenuOption option) { return Component.translatable(option.labelKey()); }

    static Component caption(List<MenuOption> options, DataValue value, String fallback) {
        return options.stream().filter(option -> option.value().equals(value)).findFirst()
            .map(ScratchExtensionMenus::label).orElseGet(() -> Component.literal(fallback));
    }
}
