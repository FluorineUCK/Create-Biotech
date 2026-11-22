package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Transient widgets in graph coordinates. The owner supplies the viewport for both drawing and input. */
final class ComputerInlineParameterEditor {
    private final String node;
    private final EditBox field;
    private final List<AbstractWidget> widgets;
    private final int capacity;
    private Component error;
    private String observedValue;

    ComputerInlineParameterEditor(Font font, ProgramDraft.Node node, Runnable apply, Runnable cancel) {
        this.node = node.id(); String initial = NodeParameter.text(node.definition());
        capacity = Math.max(ProgramEditorWire.MAX_BYTES, initial.length());
        field = new EditBox(font, 0, 0, 86, 18, label("parameter"));
        // One overflow character prevents a long paste from becoming an accepted, truncated prefix.
        field.setMaxLength(capacity + 1); field.setValue(initial); field.setHighlightPos(0);
        observedValue = field.getValue();
        field.setResponder(ignored -> {
            // Vanilla also calls the responder for cursor movement; only an edit clears a diagnostic.
            if (!field.getValue().equals(observedValue)) {
                observedValue = field.getValue(); error = null; field.setTextColor(0xffeeeeee);
            }
        });
        var confirm = new CreateTextButton(font, 0, 0, 14, 18, Component.literal("✓"), apply, true);
        var discard = new CreateTextButton(font, 0, 0, 14, 18, Component.literal("×"), cancel, true);
        widgets = List.of(field, confirm, discard); moveTo(node);
    }
    String node() { return node; }
    EditBox field() { return field; }
    List<AbstractWidget> widgets() { return widgets; }
    String value() {
        if (field.getValue().length() > capacity) throw new IllegalArgumentException("Parameter is too large");
        return field.getValue();
    }
    void error(Component error) { this.error = error; field.setTextColor(0xffff8888); }
    void moveTo(ProgramDraft.Node position) {
        field.setPosition((int)(position.x() + 4), (int)(position.y() + 18));
        widgets.get(1).setPosition((int)(position.x() + 94), (int)(position.y() + 18));
        widgets.get(2).setPosition((int)(position.x() + 112), (int)(position.y() + 18));
    }
    AbstractWidget hit(double x, double y) { return widgets.stream().filter(widget -> widget.isMouseOver(x, y)).findFirst().orElse(null); }
    Component tooltip(double x, double y) {
        var hovered = hit(x, y);
        if (hovered == null) return null;
        if (hovered == field) return error == null ? label("parameter") : error;
        return hovered == widgets.get(1) ? label("configure") : Component.translatable("gui.cancel");
    }
    void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        widgets.forEach(widget -> widget.render(graphics, mouseX, mouseY, partial));
    }
    private static Component label(String key) { return Component.translatable("create_biotech.biocomputing.editor." + key); }
}
