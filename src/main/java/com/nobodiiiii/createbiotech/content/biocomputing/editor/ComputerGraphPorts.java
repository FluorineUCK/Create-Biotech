package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;

/** Graph-coordinate layout shared by drawing and pointer input; no program/runtime ownership. */
final class ComputerGraphPorts {
    static final int WIDTH = 130, BODY_HEIGHT = 54, FIRST_INPUT = 62, INPUT_STEP = 14;
    record Socket(String node, String input) {
        boolean output() { return input == null; }
        String label() { return output() ? node : node + "." + input; }
    }
    record Point(double x, double y) { }
    static int height(ProgramDraft.Node node) {
        return node.ports().isEmpty() ? BODY_HEIGHT : FIRST_INPUT + (node.ports().size() - 1) * INPUT_STEP + 8;
    }
    static List<Socket> sockets(ProgramDraft.Node node) {
        var result = new ArrayList<Socket>(); result.add(new Socket(node.id(), null));
        node.ports().forEach(port -> result.add(new Socket(node.id(), port))); return result;
    }
    static Point position(ProgramDraft draft, Socket socket) {
        var node = draft.node(socket.node());
        return socket.output() ? new Point(node.x() + WIDTH, node.y() + 42)
            : new Point(node.x(), node.y() + FIRST_INPUT + new ArrayList<>(node.ports()).indexOf(socket.input()) * INPUT_STEP);
    }
    static Socket hit(ProgramDraft draft, double x, double y) {
        if (draft == null) return null;
        var nodes = draft.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var node = nodes.get(i);
            if (x < node.x() - 5 || x > node.x() + WIDTH + 5 || y < node.y() || y >= node.y() + height(node)) continue;
            for (var socket : sockets(node)) {
                var point = position(draft, socket);
                if (Math.abs(x - point.x()) <= 5 && Math.abs(y - point.y()) <= 5) return socket;
            }
            return null; // an upper node body occludes lower sockets
        }
        return null;
    }
    static void render(GuiGraphics g, Font font, ProgramDraft draft, ProgramDraft.Node node, NodeTypeHints types) {
        for (var socket : sockets(node)) {
            var point = position(draft, socket); int x = (int)point.x(), y = (int)point.y();
            g.fill(x - 4, y - 4, x + 5, y + 5, 0xff30343a);
            g.fill(x - 2, y - 2, x + 3, y + 3, color(types.type(socket)));
            if (!socket.output()) {
                if (!node.connections().containsKey(socket.input())) g.fill(x - 1, y - 1, x + 2, y + 2, 0xff30343a);
                g.drawString(font, font.plainSubstrByWidth(socket.input(), WIDTH - 14), x + 7, y - 4, 0xffeeeeee, false);
            }
        }
    }
    static Component tooltip(ProgramDraft draft, Socket socket, NodeTypeHints types) {
        String expected = typeLabel(types.type(socket));
        if (!socket.output() && draft.node(socket.node()).definition() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Field)
            expected = family("record") + " | " + family("map");
        var label = socket.label() + " : " + expected;
        if (!socket.output()) {
            String source = draft.node(socket.node()).connections().get(socket.input());
            label += " ← " + (source == null ? "?" : source + " : " + typeLabel(types.output(source)));
        }
        return Component.literal(label.length() <= 512 ? label : label.substring(0, 509) + "…");
    }
    static int color(DataType type) {
        if (type == null) return 0xff777777;
        return switch (type) {
            case DataType.RefType ignored -> 0xff99cccc;
            case DataType.Semantic ignored -> 0xffffac66;
            case DataType.Scalar n -> switch (n) {
                case INTEGER -> 0xff66bbff; case DECIMAL -> 0xff55dddd; case BOOLEAN -> 0xff66dd88;
                case TEXT -> 0xffdd99ee; case ERROR -> 0xffff7777; case NULL -> 0xffbbbbbb; case ANY -> 0xffffd577;
                case NBT -> 0xffffac66;
            };
            case DataType.RecordType ignored -> 0xffffac66;
            case DataType.ListType ignored -> 0xffaabbff;
            case DataType.MapType ignored -> 0xffd9ad77;
            case DataType.OptionalType ignored -> 0xffcccc99;
        };
    }
    static String typeLabel(DataType type) { return typeLabel(type, 0); }
    private static String typeLabel(DataType type, int depth) {
        if (type == null) return "?";
        if (depth == 3) return "…";
        return switch (type) {
            case DataType.RefType ref -> "Ref<"+typeLabel(ref.target(),depth+1)+","+(ref.writable()?"RW":"RO")+">";
            case DataType.Semantic s -> "RECIPE@"+s.version();
            case DataType.Scalar n -> n == DataType.Scalar.ANY ? "ANY" : family(n.name().toLowerCase(java.util.Locale.ROOT));
            case DataType.ListType n -> family("list") + "<" + typeLabel(n.element(), depth + 1) + ">";
            case DataType.MapType n -> family("map") + "<" + typeLabel(n.element(), depth + 1) + ">";
            case DataType.OptionalType n -> typeLabel(n.value(), depth + 1) + "?";
            case DataType.RecordType ignored -> family("record");
        };
    }
    private static String family(String name) { return Component.translatable("create_biotech.biocomputing.tree.type." + name).getString(); }
    static void wire(GuiGraphics g, Point a, Point b, int color) {
        int ax = (int)a.x(), ay = (int)a.y(), bx = (int)b.x(), by = (int)b.y(), mid = (ax + bx) / 2;
        line(g, ax, ay, mid, ay, color); line(g, mid, ay, mid, by, color); line(g, mid, by, bx, by, color);
    }
    private static void line(GuiGraphics g, int ax, int ay, int bx, int by, int color) {
        g.fill(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx) + 1, Math.max(ay, by) + 1, color);
    }
}
