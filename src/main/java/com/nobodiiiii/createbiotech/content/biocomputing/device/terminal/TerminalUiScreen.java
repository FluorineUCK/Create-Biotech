package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest.Action;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalItemDisplay;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ValueTree;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Reuses the Computer terminal projector and responsive layout, without any program-editor dependency. */
public final class TerminalUiScreen extends AbstractContainerScreen<TerminalUiMenu> {
    private static final int BODY = 64, ROW = TerminalLayout.ROW_HEIGHT;
    private record Label(int x, int y, int width, Component text, ItemStack icon) {}
    private final List<Label> labels = new ArrayList<>();
    private final Map<String, Integer> pages = new HashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private int scroll, sourcePage, selected = -1;
    private boolean waiting;
    private String ccId = "";

    public TerminalUiScreen(TerminalUiMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override protected void init() {
        imageWidth = Math.max(1, Math.min(580, width - 12));
        imageHeight = Math.max(1, Math.min(420, height - 12));
        super.init();
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        setFocused(null);
        labels.clear();
        boolean manage = flag("managing");
        int half = (imageWidth - 20) / 2;
        button(8, 37, half, message("refresh"), () -> send(Action.REFRESH, -1, "", ""));
        button(12 + half, 37, half, message(manage ? "view" : "manage"),
            () -> send(manage ? Action.VIEW : Action.MANAGE, -1, "", "")).active = !waiting && (manage || flag("may_manage"));
        if (manage) management(half);
        else page();
    }

    private void management(int half) {
        var choices = ((DataValue.ListValue)menu.view().field("choices")).values();
        boolean ccAvailable = flag("cc_available");
        boolean ccPublisher = menu.view().field("cc_publisher") instanceof DataValue.IntegerValue;
        boolean cc = ccAvailable || ccPublisher;
        int rows = Math.max(1, (imageHeight - BODY - (cc ? 56 + ROW * 3 : 56)) / ROW);
        if (choices.isEmpty()) label(8, BODY, imageWidth - 16, message("no_sources"));
        for (int row = 0; row < rows; row++) {
            int index = sourcePage * rows + row;
            if (index >= choices.size()) break;
            var source = (DataValue.RecordValue)choices.get(index);
            var title = Component.literal((selected == index ? "› " : "") + binding(source));
            button(8, BODY + ROW * row, imageWidth - 16, title, () -> { selected = index; rebuild(); });
        }
        int bottom = imageHeight - 26;
        int serialBottom = bottom - (cc ? ROW * 3 : 0);
        if (choices.size() > rows) {
            button(8, serialBottom - ROW, half, Component.literal("<"), () -> { sourcePage--; rebuild(); }).active = sourcePage > 0 && !waiting;
            button(12 + half, serialBottom - ROW, half, Component.literal(">"), () -> { sourcePage++; rebuild(); }).active =
                (sourcePage + 1) * rows < choices.size() && !waiting;
        }
        button(8, serialBottom, half, message("grant"), () -> send(Action.GRANT, selected, "", "")).active =
            !waiting && selected >= 0 && !ccPublisher;
        button(12 + half, serialBottom, half, message("revoke"), () -> send(Action.REVOKE, -1, "", "")).active = !waiting && connected();
        if (cc) ccControls(half, bottom, ccAvailable, ccPublisher);
    }

    private void ccControls(int half, int bottom, boolean ccAvailable, boolean ccPublisher) {
        var reader = menu.view().field("cc_reader");
        var publisher = menu.view().field("cc_publisher");
        label(8, bottom - 2 * ROW, half - 4, message("cc_roles", summary(reader), summary(publisher)));
        var field = addRenderableWidget(new EditBox(font, leftPos + 12 + half, topPos + bottom - 2 * ROW, half, 18, message("cc_id")));
        field.setMaxLength(10);
        field.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
        field.setValue(ccId);
        field.setHint(message("cc_id"));
        field.active = !waiting && ccAvailable;
        var grantRead = button(8, bottom - ROW, half, message("cc_grant"), () -> send(Action.CC_ALLOW_READ, readCcId(), "", ""));
        button(12 + half, bottom - ROW, half, message("cc_revoke"), () -> send(Action.CC_REVOKE_READ, -1, "", "")).active =
            !waiting && ccAvailable && reader instanceof DataValue.IntegerValue;
        var grantPublish = button(8, bottom, half, message("cc_grant_publish"), () -> send(Action.CC_ALLOW_PUBLISH, readCcId(), "", ""));
        if (connected()) grantPublish.setTooltip(Tooltip.create(code("publisher_in_use")));
        button(12 + half, bottom, half, message("cc_revoke_publish"), () -> send(Action.CC_REVOKE_PUBLISH, -1, "", "")).active =
            !waiting && ccPublisher;
        Runnable update = () -> {
            grantRead.active = !waiting && ccAvailable && readCcId() >= 0;
            grantPublish.active = grantRead.active && !connected();
        };
        update.run();
        field.setResponder(value -> { ccId = value; update.run(); });
    }

    private int readCcId() {
        try { return Integer.parseInt(ccId); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private void page() {
        if (!(menu.view().field("page") instanceof DataValue.RecordValue data)) {
            label(8, BODY, imageWidth - 16, message("no_page"));
            return;
        }
        try {
            int bodyHeight = Math.max(ROW, (imageHeight - BODY - 28) / ROW * ROW);
            var layout = TerminalLayout.layout(TerminalProjection.project(TerminalCodec.decode(data.field("layout")),
                (DataValue.RecordValue)data.field("roots"), pages, Math.max(1, bodyHeight / ROW - 1),
                TerminalUiState.PAGE_FORMAT.values().maxNodes()), imageWidth - 16);
            int maxScroll = Math.max(0, layout.height() - bodyHeight);
            scroll = Math.clamp(scroll, 0, maxScroll);
            for (var cell : layout.cells()) {
                if (cell.y() >= scroll && cell.y() + ROW <= scroll + bodyHeight) cell(cell);
            }
            if (maxScroll > 0) {
                int bottom = imageHeight - 26;
                button(8, bottom, 32, Component.literal("↑"), () -> { scroll = Math.max(0, scroll - bodyHeight); rebuild(); }).active = !waiting && scroll > 0;
                button(44, bottom, 32, Component.literal("↓"), () -> { scroll = Math.min(maxScroll, scroll + bodyHeight); rebuild(); }).active = !waiting && scroll < maxScroll;
            }
        } catch (IllegalArgumentException invalid) { label(8, BODY, imageWidth - 16, message("invalid_page")); }
    }

    private void cell(TerminalLayout.Cell cell) {
        var view = cell.view();
        int x = 8 + cell.x(), y = BODY + cell.y() - scroll, w = cell.width();
        if (view.page().isPresent()) {
            var page = view.page().orElseThrow();
            button(x, y, 18, Component.literal("<"), () -> changePage(view.instance(), page.index() - 1)).active = !waiting && page.index() > 0;
            button(x + 21, y, 18, Component.literal(">"), () -> changePage(view.instance(), page.index() + 1)).active =
                !waiting && page.index() + 1 < page.count();
            label(x + 42, y, w - 42, Component.literal((page.index() + 1) + " / " + page.count()));
        } else if (view.problem().isPresent()) {
            label(x, y, w, code(view.problem().orElseThrow().code()));
        } else if (view.button().isPresent()) {
            var control = view.button().orElseThrow();
            button(x, y, w, Component.literal(control.title()), () -> send(Action.CLICK, -1, view.instance(), "")).active =
                !waiting && flag("interactive") && control.enabled();
        } else if (view.input().isPresent()) {
            var input = view.input().orElseThrow();
            int submit = Math.min(66, Math.max(30, w / 3));
            var field = addRenderableWidget(new EditBox(font, leftPos + x, topPos + y, Math.max(1, w - submit - 4), 18, message("input")));
            field.setMaxLength((int)((DataValue.IntegerValue)menu.view().field("input_chars")).value());
            field.setValue(drafts.getOrDefault(view.instance(), input.text()));
            field.setResponder(value -> drafts.put(view.instance(), value));
            field.active = !waiting && flag("interactive") && input.enabled();
            button(x + w - submit, y, submit, message("submit"), () -> send(Action.SUBMIT, -1, view.instance(), field.getValue())).active = field.active;
        } else if (view.value().isPresent()) {
            var value = view.value().orElseThrow();
            if (view.definition() instanceof TerminalDefinition.Item) {
                var item = TerminalItemDisplay.resolve(value);
                labels.add(new Label(x, y, w, item.problem().map(TerminalUiScreen::code)
                    .orElseGet(() -> Component.literal(item.id() + " × " + item.count())), item.stack()));
            } else label(x, y, w, Component.literal(summary(value)));
        }
    }

    private void changePage(String id, int page) { pages.put(id, page); rebuild(); }
    private boolean connected() { return menu.view().field("binding") instanceof DataValue.RecordValue; }
    private boolean flag(String name) { return menu.view().field(name) instanceof DataValue.BooleanValue value && value.value(); }
    private static Component message(String key, Object... args) { return Component.translatable("create_biotech.terminal_ui." + key, args); }
    private static Component code(String code) { return Component.translatableWithFallback("create_biotech.terminal_ui.code." + code, code); }
    private static String binding(DataValue.RecordValue source) {
        return summary(source.field("terminal")) + " / " + summary(source.field("port")) + " · " + summary(source.field("face"));
    }
    private static String summary(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        if (value instanceof DataValue.IntegerValue number) return Long.toString(number.value());
        if (value instanceof DataValue.DecimalValue number) return Double.toString(number.value());
        if (value instanceof DataValue.BooleanValue flag) return Boolean.toString(flag.value());
        if (value == null || value == DataValue.NullValue.INSTANCE) return "—";
        return Component.translatable("create_biotech.biocomputing.tree.type." + ValueTree.kind(value)).getString();
    }
    private void label(int x, int y, int width, Component text) { labels.add(new Label(x, y, width, text, ItemStack.EMPTY)); }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        var control = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, Math.max(1, w), 18, label, action));
        if (font.width(label) > w - 8) control.setTooltip(Tooltip.create(label));
        control.active = !waiting;
        return control;
    }
    private void send(Action action, int choice, String instance, String value) {
        if (waiting) return;
        waiting = true;
        CBPackets.sendToServer(menu.request(action, choice, instance, value));
        rebuild();
    }
    private String clipped(Component text, int width) { return font.plainSubstrByWidth(text.getString(), Math.max(0, width)); }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 21);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, clipped(title, imageWidth - 16), 8, 7, 0xffeeeeee, false);
        var status = message("status", summary(menu.view().field("revision")), summary(menu.view().field("events")),
            waiting ? message("waiting") : code(menu.code()));
        g.drawString(font, clipped(status, imageWidth - 16), 8, 25, 0xffffcf80, false);
        for (var label : labels) {
            int icon = label.icon().isEmpty() ? 0 : 20;
            if (icon > 0) g.renderItem(label.icon(), label.x(), label.y());
            g.drawString(font, clipped(label.text(), label.width() - icon), label.x() + icon, label.y() + 5, 0xffeeeeee, false);
        }
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        super.render(g, mouseX, mouseY, partial);
        for (var label : labels) {
            int x = mouseX - leftPos - label.x(), y = mouseY - topPos - label.y();
            if (x >= 0 && x < label.width() && y >= 0 && y < 18)
                g.renderTooltip(font, font.split(label.text(), Math.min(240, imageWidth - 16)), mouseX, mouseY);
        }
    }
}
