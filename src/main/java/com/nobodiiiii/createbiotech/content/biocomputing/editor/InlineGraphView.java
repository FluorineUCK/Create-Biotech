package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowBlockPalette.Category;
import java.util.function.ToIntFunction;

/** A bounded visual projection of ordinary graph nodes, never an expression interpreter. */
final class InlineGraphView {
    record Child(Tile tile, int x, int y) {}
    record Tile(String node, String label, boolean bool, int width, int height, int labelX, List<Child> children, boolean positionMenu,
                boolean literal, Category category, List<ScratchSignaturePreview.Phrase> phrases) {
        Tile(String node, String label, boolean bool, int width, int height, int labelX, List<Child> children,
             boolean positionMenu, boolean literal, Category category) {
            this(node, label, bool, width, height, labelX, children, positionMenu, literal, category, List.of());
        }
    }
    private static Tile placeholder(String id, String label) {
        return new Tile(id, label, false, 24, 18, 6, List.of(), false, false, Category.DATA);
    }
    static Tile layout(ComputationGraph graph, ToIntFunction<String> measure) {
        return layout(graph, measure, Map.of());
    }
    static Tile layout(ComputationGraph graph, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures) {
        return layout(graph, measure, procedures, Map.of());
    }
    static Tile layout(ComputationGraph graph, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures,
            Map<String, String> bindings) {
        return output(graph, "value", measure, procedures, bindings);
    }
    static Tile output(ComputationGraph graph, String name, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures,
            Map<String, String> bindings) {
        return output(graph, name, measure, procedures, bindings, Set.of());
    }
    static Tile output(ComputationGraph graph, String name, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures,
            Map<String, String> bindings, Set<String> parameterNames) {
        return node(graph, graph.outputs().get(name), measure, procedures, bindings, parameterNames);
    }
    static Tile node(ComputationGraph graph, String node, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures,
            Map<String, String> bindings, Set<String> parameterNames) {
        return node(graph, node, measure, procedures, bindings, parameterNames, Map.of());
    }
    static Tile node(ComputationGraph graph, String node, ToIntFunction<String> measure,
            Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures,
            Map<String, String> bindings, Set<String> parameterNames, Map<ModuleDefinition.Key, ScratchProcedureLabel.Layout> labels) {
        return layout(graph, node, measure, new HashSet<>(), new int[] {256}, 0, procedures, bindings, parameterNames, labels);
    }
    private static Tile layout(ComputationGraph graph, String id, ToIntFunction<String> measure, Set<String> path, int[] remaining, int depth, Map<ModuleDefinition.Key, com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure> procedures, Map<String, String> bindings, Set<String> parameterNames, Map<ModuleDefinition.Key, ScratchProcedureLabel.Layout> labels) {
        if (id == null || depth >= 16 || remaining[0]-- <= 0 || !path.add(id)) return placeholder(id, "…");
        var node = graph.nodes().get(id);
        if (node == null) { path.remove(id); return placeholder(null, "?"); }
        String label = node instanceof ComputationGraph.Input input ? bindings.getOrDefault(input.name(), input.name()) : label(node); boolean bool = node instanceof ComputationGraph.KeyDown || node instanceof ComputationGraph.ListSearch search && search.contains() || node instanceof ComputationGraph.ValueEquals || node instanceof ComputationGraph.Constant c && c.value() instanceof DataValue.BooleanValue
            || node instanceof ComputationGraph.Input input && graph.inputs().get(input.name()) == DataType.Scalar.BOOLEAN
            || node instanceof ComputationGraph.Text text && text.operation().outputType(text.operation().inputs(), id) == DataType.Scalar.BOOLEAN
            || node instanceof ComputationGraph.Scalar scalar && scalar.operation().outputType(scalar.operation().inputs(), id) == DataType.Scalar.BOOLEAN;
        if (node instanceof ComputationGraph.ProcedureValue call) {
            var signature = procedures.get(call.procedure());
            var output = signature != null ? signature.outputs().get(call.result())
                : ScratchExtensions.binding(call.procedure())
                    .map(binding -> binding.command().outputs().get(call.result())).orElse(null);
            bool = output == DataType.Scalar.BOOLEAN;
        }
        var children = new ArrayList<Tile>();
        var authored = node instanceof ComputationGraph.ProcedureValue call ? labels.get(call.procedure()) : null;
        var parts = authored == null ? List.<ScratchProcedureLabel>of() : authored.matching(NodePorts.bindings(node).keySet());
        if (node instanceof ComputationGraph.ProcedureValue call && authored != null) {
            label = FlowBlockCatalog.procedureValueLabel(call.procedure(), call.result(), parts.isEmpty() ? authored.order(call.arguments().keySet()) : List.of()).getString();
            for (var port : authored.order(call.arguments().keySet()))
                children.add(layout(graph, call.arguments().get(port), measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels));
        } else if (node instanceof ComputationGraph.ProcedureValue call && ScratchExtensions.reserved(call.procedure())) {
            for (String child : call.arguments().values()) {
                var slot = layout(graph, child, measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels);
                children.add(slot.literal() ? ScratchExtensionMenus.decorate(graph, slot, ScratchExtensionMenus.nested(graph, call, child), measure) : slot);
            }
        } else if (node instanceof ComputationGraph.Scalar scalar && scalar.operation().numericEdge()) {
            for (var port : scalar.operation().inputs().keySet())
                children.add(layout(graph, scalar.arguments().get(port), measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels));
        } else if (node instanceof ComputationGraph.DynamicIndex index && scratchIndex(graph, index) != null) {
            label = net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_get").getString();
            children.add(layout(graph, index.source(), measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels));
            children.add(positionField(layout(graph, scratchIndex(graph, index), measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels), graph.nodes().get(scratchIndex(graph, index))));
        } else if (node instanceof ComputationGraph.ListPosition) {
            // The selector aliases its parent's source; never draw the whole list twice.
        } else if (node instanceof ComputationGraph.ListEdit edit) {
            for(String port:edit.operation().inputs().keySet()) {
                String child=edit.arguments().get(port);
                String field=port.equals("index")?ScratchListPositions.field(graph,child):null;
                if(field!=null) children.add(positionField(layout(graph,field,measure,path,remaining,depth+1,procedures,bindings,parameterNames,labels),graph.nodes().get(field)));
                else children.add(layout(graph,child,measure,path,remaining,depth+1,procedures,bindings,parameterNames,labels));
            }
        } else if (node instanceof ComputationGraph.MakeList || node instanceof ComputationGraph.MakeRecord) {
            // Member form pages expose all rows; the compact canvas never grows horizontally without bound.
            NodePorts.bindings(node).values().stream().limit(3).forEach(child->children.add(layout(graph, child, measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels)));
        } else if (infix(node)) {
            var ports = NodePorts.bindings(node);
            // The visual operand order must follow named ports, never map iteration order.
            for (var port : List.of("left", "right")) children.add(layout(graph, ports.get(port), measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels));
        } else NodePorts.bindings(node).values().forEach(child -> children.add(layout(graph, child, measure, path, remaining, depth + 1, procedures, bindings, parameterNames, labels)));
        path.remove(id);
        Category category = switch (node) {
            case ComputationGraph.Input input -> bindings.containsKey(input.name()) && parameterNames.contains(bindings.get(input.name()))
                ? Category.CUSTOM : Category.VARIABLES;
            case ComputationGraph.ReadMemory ignored -> Category.VARIABLES;
            case ComputationGraph.ValueEquals ignored -> Category.OPERATORS;
            case ComputationGraph.ListSearch ignored -> Category.VARIABLES;
            case ComputationGraph.RandomRange ignored -> Category.OPERATORS;
            case ComputationGraph.Timer ignored -> Category.SENSING;
            case ComputationGraph.Answer ignored -> Category.SENSING;
            case ComputationGraph.KeyDown ignored -> Category.SENSING;
            case ComputationGraph.ProcedureValue call -> com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(call.procedure())
                ? Category.EXTENSIONS : Category.CUSTOM;
            case ComputationGraph.Text ignored -> Category.OPERATORS;
            case ComputationGraph.Scalar scalar -> scalar.operation().category() == ScalarOperation.Category.VALUE
                ? Category.DATA : Category.OPERATORS;
            default -> Category.DATA;
        };
        boolean literal = node instanceof ComputationGraph.Constant || node instanceof ComputationGraph.ListPosition;
        int height = children.isEmpty() ? 18 : children.stream().mapToInt(Tile::height).max().orElse(18) + 8;
        int labelWidth = measure.applyAsInt(label);
        if (infix(node)) {
            var left = children.getFirst(); var right = children.getLast();
            int labelX = 6 + left.width() + 6, rightX = labelX + labelWidth + 6;
            return new Tile(id, label, bool, rightX + right.width() + 6, height, labelX, List.of(
                new Child(left, 6, (height - left.height()) / 2),
                new Child(right, rightX, (height - right.height()) / 2)), false, literal, category);
        }
        int x = Math.max(24, labelWidth + 12); var placed = new ArrayList<Child>();
        var phrases = new ArrayList<ScratchSignaturePreview.Phrase>();
        for (int i = 0; i <= children.size(); i++) {
            for (var part : parts) if (part.before() == i) {
                phrases.add(new ScratchSignaturePreview.Phrase(part.text(), x)); x += measure.applyAsInt(part.text()) + 6;
            }
            if (i < children.size()) {
                var child = children.get(i); placed.add(new Child(child, x, (height - child.height()) / 2)); x += child.width() + 6;
            }
        }
        return new Tile(id, label, bool, node instanceof ComputationGraph.KeyDown ? x+height : x, height, 6, List.copyOf(placed), node instanceof ComputationGraph.KeyDown, literal, category, List.copyOf(phrases));
    }
    private static Tile positionField(Tile tile, ComputationGraph.Node node) {
        if (!ScratchListPositions.hasMenu(node)) return tile;
        return new Tile(tile.node(), tile.label(), tile.bool(), tile.width()+tile.height(), tile.height(), tile.labelX(), tile.children(), true, tile.literal(), tile.category());
    }
    static boolean infix(ComputationGraph.Node node) {
        if (node instanceof ComputationGraph.ValueEquals) return true;
        return node instanceof ComputationGraph.Scalar scalar && switch (scalar.operation()) {
            case ADD_INTEGER, ADD_DECIMAL, SUBTRACT_INTEGER, SUBTRACT_DECIMAL,
                MULTIPLY_INTEGER, MULTIPLY_DECIMAL, DIVIDE_INTEGER, DIVIDE_DECIMAL, REMAINDER_INTEGER, MOD_INTEGER, MOD_DECIMAL,
                LESS_INTEGER, LESS_DECIMAL, LESS_EQUAL_INTEGER, LESS_EQUAL_DECIMAL,
                GREATER_INTEGER, GREATER_DECIMAL, GREATER_EQUAL_INTEGER, GREATER_EQUAL_DECIMAL,
                EQUAL_INTEGER, EQUAL_DECIMAL, AND_BOOLEAN, OR_BOOLEAN, XOR_BOOLEAN -> true;
            default -> false;
        };
    }
    /** Presentation sugar only; hidden subtraction remains explicit executable IR. */
    private static String scratchIndex(ComputationGraph graph, ComputationGraph.DynamicIndex index) {
        if (graph.nodes().get(index.index()) instanceof ComputationGraph.Scalar scalar && scalar.operation() == ScalarOperation.SUBTRACT_INTEGER
            && graph.nodes().get(scalar.arguments().get("right")) instanceof ComputationGraph.Constant c && c.value().equals(new DataValue.IntegerValue(1)))
            return scalar.arguments().get("left");
        return null;
    }
    static String label(ComputationGraph.Node node) {
        if (node instanceof ComputationGraph.Timer) return FlowBlockCatalog.label(new InlineGraphEdits.Timer()).getString();
        if (node instanceof ComputationGraph.Answer) return FlowBlockCatalog.label(new InlineGraphEdits.Answer()).getString();
        if(node instanceof ComputationGraph.KeyDown key)return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_key_down",FlowBlockCanvas.keyLabel(key.selector())).getString();
        if (node instanceof ComputationGraph.RandomRange range) return net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.random_" + (range.decimal() ? "decimal" : "integer") + "_caption").getString();
        if (node instanceof ComputationGraph.ProcedureValue call) {
            if (com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(call.procedure()))
                return FlowBlockCatalog.extensionValueLabel(call.procedure()).getString();
            return FlowBlockCatalog.procedureValueLabel(call.procedure(), call.result(), new TreeSet<>(call.arguments().keySet())).getString();
        }
        if (node instanceof ComputationGraph.Constant value) {
            try { return EditorValueFormat.format(value.value()); } catch (RuntimeException invalid) { return "…"; }
        }
        if (node instanceof ComputationGraph.ListPosition position)
            return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.list_position_"
                + position.choice().name().toLowerCase(Locale.ROOT)).getString();
        if (node instanceof ComputationGraph.ListSearch search) return FlowBlockCatalog.label(new InlineGraphEdits.ListSearch(search.contains())).getString();
        if (node instanceof ComputationGraph.ListEdit edit && edit.operation() == ListOperation.APPEND)
            return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_append").getString();
        if (node instanceof ComputationGraph.ListEdit edit && edit.operation() != ListOperation.SLICE)
            return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.add_list_"
                + edit.operation().name().toLowerCase(Locale.ROOT) + "_local").getString();
        if (node instanceof ComputationGraph.MakeList)return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_list_make").getString();
        if (node instanceof ComputationGraph.MakeRecord)return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_record_make").getString();
        if (node instanceof ComputationGraph.Input input) return input.name();
        if (node instanceof ComputationGraph.ReadMemory read) return net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.inline_public", read.cell()).getString();
        if (node instanceof ComputationGraph.Field field) return "." + field.name();
        if (node instanceof ComputationGraph.Index index) return "[" + index.index() + "]";
        if (node instanceof ComputationGraph.Text text) return net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.text_operation.name." + text.operation().name().toLowerCase(Locale.ROOT)).getString();
        if (node instanceof ComputationGraph.ValueEquals) return net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.inline_value_equals").getString();
        if (node instanceof ComputationGraph.Scalar scalar) {
            if (ScratchMathFunctions.hasMenu(scalar)) return net.minecraft.network.chat.Component.translatable(
                "create_biotech.biocomputing.flow_editor." + ScratchMathFunctions.key(scalar.operation())).getString() + " ▾";
            return scalar.operation().numericEdge()
                ? FlowBlockCatalog.label(new InlineGraphEdits.Operator(scalar.operation())).getString() + " ▾" : symbol(scalar.operation());
        }
        return node.getClass().getSimpleName();
    }
    static net.minecraft.network.chat.Component operationName(ScalarOperation operation) {
        return net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.operation.name." + operation.name().toLowerCase(Locale.ROOT));
    }
    static String symbol(ScalarOperation operation) {
        return switch (operation) {
            case ADD_INTEGER, ADD_DECIMAL -> "+";
            case SUBTRACT_INTEGER, SUBTRACT_DECIMAL -> "−";
            case MULTIPLY_INTEGER, MULTIPLY_DECIMAL -> "×";
            case DIVIDE_INTEGER, DIVIDE_DECIMAL -> "÷";
            case REMAINDER_INTEGER -> "%";
            case MOD_INTEGER, MOD_DECIMAL -> net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.math_mod").getString();
            case ROUND_DECIMAL_TO_INTEGER -> net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.math_round").getString();
            case LESS_INTEGER, LESS_DECIMAL -> "<";
            case LESS_EQUAL_INTEGER, LESS_EQUAL_DECIMAL -> "≤";
            case GREATER_INTEGER, GREATER_DECIMAL -> ">";
            case GREATER_EQUAL_INTEGER, GREATER_EQUAL_DECIMAL -> "≥";
            case EQUAL_INTEGER, EQUAL_DECIMAL -> "=";
            case REQUIRE_VALUE, LIST_LENGTH, IS_NULL, IS_ERROR -> net.minecraft.network.chat.Component.translatable(
                "create_biotech.biocomputing.flow_editor.inline_" + operation.name().toLowerCase(Locale.ROOT)).getString();
            case AND_BOOLEAN -> "∧"; case OR_BOOLEAN -> "∨"; case XOR_BOOLEAN -> "⊕"; case NOT_BOOLEAN -> "¬";
            default -> operation.name();
        };
    }
}
