package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Browse a frozen input tree. No control requests, world reads, value edits or installs occur here. */
public final class ComputerValueTreeScreen<M extends net.minecraft.world.inventory.AbstractContainerMenu> extends AbstractSimiContainerScreen<M> {
    private static final int ROW_TOP = 54, ROW_HEIGHT = 22;
    private final Screen parent;
    private final ValueTree tree;
    private final Consumer<ValuePath> generate;
    private final Component actionTitle, frozenNotice;
    private final Runnable close;
    private ValuePath selected;
    private int treePage, detailPage;
    private ValueTree.Page visible;
    private List<CreateTextButton> rowButtons, expandButtons;
    private List<String> detailLines = List.of();
    private Component notice = text("frozen");

    public ComputerValueTreeScreen(M menu, Inventory inventory, Component title, Screen parent,
            DataValue.RecordValue inputs, Consumer<ValuePath> generate) {
        this(menu, inventory, title, parent, inputs, Objects.requireNonNull(generate), text("generate"), text("frozen"), null);
    }
    /** Null selection callback means pure inspection; the old constructor retains path generation. */
    public ComputerValueTreeScreen(M menu, Inventory inventory, Component title, Screen parent,
            DataValue.RecordValue inputs, Consumer<ValuePath> generate, Component actionTitle, Component frozenNotice, Runnable close) {
        super(menu, inventory, title);
        this.parent = Objects.requireNonNull(parent); this.tree = new ValueTree(inputs);
        this.generate = generate; this.actionTitle = Objects.requireNonNull(actionTitle);
        this.frozenNotice = Objects.requireNonNull(frozenNotice); this.notice = frozenNotice;
        this.close = close;
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init();
        setFocused(null); clearWidgets();
        visible = tree.page(treePage, rows()); treePage = visible.index();
        rowButtons = new ArrayList<>(); expandButtons = new ArrayList<>();
        for (int i = 0; i < visible.rows().size(); i++) {
            var row = visible.rows().get(i);
            int indent = Math.min(row.depth() * 10, Math.max(0, imageWidth - 140));
            int y = ROW_TOP + i * ROW_HEIGHT;
            var toggle = button(8 + indent, y, 18, Component.literal(row.expanded() ? "-" : "+"), () -> {
                tree.toggle(row.path()); init();
            });
            toggle.active = row.expandable(); toggle.visible = row.expandable(); expandButtons.add(toggle);
            int x = 30 + indent, rowWidth = imageWidth - x - 8;
            String label = (row.path().equals(selected) ? "> " : "") + name(row.path()) + " · "
                + text("type." + ValueTree.kind(row.value())).getString() + " = " + summary(row.value());
            rowButtons.add(button(x, y, rowWidth, Component.literal(font.plainSubstrByWidth(label, rowWidth - 8)), () -> {
                selected = row.path(); detailPage = 0; notice = frozenNotice; init();
            }));
        }
        int navigation = ROW_TOP + rows() * ROW_HEIGHT + 2;
        button(8, navigation, 32, text("tree_previous"), () -> { treePage--; init(); }).active = treePage > 0;
        button(44, navigation, 32, text("tree_next"), () -> { treePage++; init(); }).active = treePage + 1 < visible.count();
        detailLines = details();
        var details = ComputerValueDisplay.page(detailLines, detailPage, detailRows()); detailPage = details.index();
        button(8, imageHeight - 51, 32, text("detail_previous"), () -> { detailPage--; init(); }).active = detailPage > 0;
        button(44, imageHeight - 51, 32, text("detail_next"), () -> { detailPage++; init(); }).active = detailPage + 1 < details.count();
        if (generate != null) button(8, imageHeight - 27, 148, actionTitle, this::generate).active = selected != null;
        button(imageWidth - 76, imageHeight - 27, 68, text("back"), this::onClose);
    }
    private int rows() { return Math.max(1, (imageHeight - ROW_TOP - 108) / ROW_HEIGHT); }
    private int detailTop() { return ROW_TOP + rows() * ROW_HEIGHT + 28; }
    private int detailRows() { return Math.max(1, (imageHeight - detailTop() - 56) / 10); }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
    }
    private void generate() {
        if (selected == null) return;
        try { generate.accept(selected); }
        catch (RuntimeException error) { notice = text("invalid", String.valueOf(error.getMessage())); }
    }
    private List<String> details() {
        if (selected == null) return List.of(text("select").getString());
        var lines = new ArrayList<String>();
        DataValue value = selected.resolve(tree.inputs());
        wrap(lines, selected.display());
        wrap(lines, text("type." + ValueTree.kind(value)).getString() + ": " + summary(value));
        return List.copyOf(lines);
    }
    private void wrap(List<String> lines, String value) {
        int width = Math.max(1, imageWidth - 20);
        int offset = 0;
        while (offset < value.length()) {
            String part = font.plainSubstrByWidth(value.substring(offset), width);
            if (part.isEmpty()) part = value.substring(offset, offset + Character.charCount(value.codePointAt(offset)));
            lines.add(part); offset += part.length();
        }
    }
    private static String name(ValuePath path) {
        if (path.steps().isEmpty()) return BrowserLiteralDisplay.quote(path.input());
        var step = path.steps().getLast();
        return step instanceof ValuePath.Key key ? BrowserLiteralDisplay.quote(key.name()) : "[" + ((ValuePath.Index)step).index() + "]";
    }
    private static String summary(DataValue value) {
        if (value instanceof DataValue.RecordValue || value instanceof DataValue.MapValue || value instanceof DataValue.ListValue)
            return text("entries", ValueTree.childCount(value)).getString();
        // Only leaves are formatted, never recursively materialize a container's descendants.
        return BrowserLiteralDisplay.leaf(value);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, text("title"), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        graphics.drawString(font, font.plainSubstrByWidth(notice.getString(), imageWidth - 20), leftPos + 10, topPos + 34, 0xffffddaa, false);
        graphics.drawString(font, (treePage + 1) + "/" + visible.count(), leftPos + 86,
            topPos + ROW_TOP + rows() * ROW_HEIGHT + 8, 0xffaaccee, false);
        if (visible.rows().isEmpty()) graphics.drawString(font, text("empty"), leftPos + 10, topPos + ROW_TOP, 0xffdddddd, false);
        var page = ComputerValueDisplay.page(detailLines, detailPage, detailRows());
        for (int i = 0; i < page.lines().size(); i++)
            graphics.drawString(font, page.lines().get(i), leftPos + 10, topPos + detailTop() + i * 10, 0xffdddddd, false);
        graphics.drawString(font, (detailPage + 1) + "/" + page.count(), leftPos + 86, topPos + imageHeight - 45, 0xffaaccee, false);
    }
    @Override public void onClose() { if (close != null) close.run(); else minecraft.setScreen(parent); }
    @Override public void removed() { }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.tree." + key, args); }
}
