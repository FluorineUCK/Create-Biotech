package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerNodePalette.Kind;

/** Exact, editor-only parameter sugar shared by canvas fields and full node configuration. */
final class NodeParameter {
    static Kind kind(ComputationGraph.Node node) {
        return switch (node) {
            case null -> null;
            case ComputationGraph.Constant c -> switch (c.value()) {
                case DataValue.IntegerValue ignored -> Kind.INTEGER;
                case DataValue.DecimalValue ignored -> Kind.DECIMAL;
                case DataValue.TextValue ignored -> Kind.TEXT;
                case DataValue.BooleanValue ignored -> Kind.BOOLEAN;
                case DataValue.NbtValue ignored -> Kind.NBT;
                default -> null;
            };
            case ComputationGraph.Field ignored -> Kind.FIELD;
            case ComputationGraph.Index ignored -> Kind.INDEX;
            case ComputationGraph.MakeList ignored -> Kind.LIST;
            case ComputationGraph.Input ignored -> Kind.INPUT;
            case ComputationGraph.ReadMemory ignored -> Kind.MEMORY;
            default -> null;
        };
    }

    static String text(ComputationGraph.Node node) {
        return switch (node) {
            case ComputationGraph.Constant c when c.value() instanceof DataValue.NbtValue -> EditorValueFormat.format(c.value());
            case ComputationGraph.Constant c -> switch (c.value()) {
                case DataValue.IntegerValue n -> Long.toString(n.value());
                case DataValue.DecimalValue n -> Double.toString(n.value());
                case DataValue.TextValue n -> n.value();
                case DataValue.BooleanValue n -> Boolean.toString(n.value());
                default -> throw new IllegalArgumentException("Not an inline parameter");
            };
            case ComputationGraph.Field n -> n.name();
            case ComputationGraph.Index n -> Integer.toString(n.index());
            case ComputationGraph.MakeList n -> Integer.toString(n.elements().size());
            case ComputationGraph.Input n -> n.name();
            case ComputationGraph.ReadMemory n -> n.cell();
            default -> throw new IllegalArgumentException("Not an inline parameter");
        };
    }

    static ComputationGraph.Node parse(Kind kind, String text) {
        return switch (kind) {
            case NBT -> new ComputationGraph.Constant(EditorValueFormat.parse(EditorValueFormat.Kind.NBT, text));
            case INTEGER -> new ComputationGraph.Constant(new DataValue.IntegerValue(Long.parseLong(text)));
            case DECIMAL -> new ComputationGraph.Constant(new DataValue.DecimalValue(Double.parseDouble(text)));
            case TEXT -> new ComputationGraph.Constant(new DataValue.TextValue(text));
            case BOOLEAN -> {
                if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("true / false");
                yield new ComputationGraph.Constant(new DataValue.BooleanValue(Boolean.parseBoolean(text)));
            }
            case FIELD -> new ComputationGraph.Field("source", text);
            case INDEX -> new ComputationGraph.Index("source", Integer.parseInt(text));
            case LIST -> {
                int count = Integer.parseInt(text);
                // Each reference needs at least one transport byte; reject impossible allocation before building ports.
                if (count < 0 || count > ProgramEditorWire.MAX_BYTES)
                    throw new IllegalArgumentException("List input count exceeds the editor representation limit");
                yield new ComputationGraph.MakeList(java.util.stream.IntStream.range(0, count).mapToObj(Integer::toString).toList());
            }
            case INPUT -> new ComputationGraph.Input(text);
            case MEMORY -> new ComputationGraph.ReadMemory(text);
            default -> throw new IllegalArgumentException("Not an inline parameter");
        };
    }
}
