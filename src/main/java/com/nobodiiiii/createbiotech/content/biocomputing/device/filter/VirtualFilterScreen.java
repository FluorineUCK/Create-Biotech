package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRequest.Action;

public final class VirtualFilterScreen extends AbstractContainerScreen<VirtualFilterMenu> {
    private int page, selected = -1;
    private boolean waiting;
    public VirtualFilterScreen(VirtualFilterMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    @Override protected void init() {
        imageWidth = Math.min(420, width - 12);
        imageHeight = Math.min(260, height - 12);
        super.init();
        int half = (imageWidth - 24) / 2;
        int rows = Math.max(1, (imageHeight - 160) / 22);
        List<DataValue> sources = ((DataValue.ListValue)menu.view().field("choices")).values();
        for (int row = 0; row < rows && page * rows + row < sources.size(); row++) {
            int index = page * rows + row;
            var desc = describe(sources.get(index));
            button(8, 78 + row * 22, imageWidth - 16, Component.literal((index == selected ? "✓ " : "") + clip(desc)),
                () -> { selected = index; rebuildWidgets(); }).setTooltip(Tooltip.create(desc));
        }
        if (sources.size() > rows) {
            button(8, imageHeight - 72, half, Component.literal("<"), () -> { page--; rebuildWidgets(); }).active = !waiting && page > 0;
            button(16 + half, imageHeight - 72, half, Component.literal(">"), () -> { page++; rebuildWidgets(); }).active =
                !waiting && (page + 1) * rows < sources.size();
        }
        boolean list = menu.view().field("supports_list") instanceof DataValue.BooleanValue supported && supported.value();
        boolean directBlocked = !list && field("slot").equals("native_occupied");
        var grant = button(8, imageHeight - 48, half, msg(list ? "grant_list" : "grant"), () -> send(list ? Action.GRANT_LIST : Action.GRANT, selected));
        grant.active = !waiting && selected >= 0 && !directBlocked
            && (field("target_status").equals("ready") || field("target_status").equals("target_changed"));
        if (directBlocked) grant.setTooltip(Tooltip.create(code("filter_slot_occupied")));
        button(16 + half, imageHeight - 48, half, msg("revoke"), () -> send(Action.REVOKE, -1)).active =
            !waiting && menu.view().field("binding") instanceof DataValue.RecordValue;
        var rule = button(8, imageHeight - 24, half, msg("refresh"), () -> send(Action.REFRESH, -1));
        button(16 + half, imageHeight - 24, half, msg("back"), this::onClose);
        if (menu.view().field("rule") instanceof DataValue.RecordValue data)
            rule.setTooltip(Tooltip.create(Component.literal(font.plainSubstrByWidth(EditorValueFormat.format(data), 600))));
    }
    private void send(Action action, int choice) {
        if (waiting) return;
        waiting = true;
        CBPackets.sendToServer(menu.request(action, choice));
        rebuildWidgets();
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        var control = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, Math.max(1, width), 18, label, action));
        control.active = !waiting;
        return control;
    }
    private static Component msg(String key, Object... args) { return Component.translatable("create_biotech.virtual_filter." + key, args); }
    private static Component code(String value) { return Component.translatableWithFallback("create_biotech.virtual_filter.code." + value, value); }
    private static Component describe(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) return msg("closed");
        return Component.literal(text(record.field("terminal")) + " / " + text(record.field("port")) + " · " + text(record.field("face")));
    }
    private static String text(DataValue value) { return value instanceof DataValue.TextValue t ? t.value() : "—"; }
    private String field(String name) { return text(menu.view().field(name)); }
    private String clip(Component text) { return font.plainSubstrByWidth(text.getString(), Math.max(1, imageWidth - 23)); }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 24);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, clip(title), 8, 8, 0xffeeeeee, false);
        g.drawString(font, clip(msg("target", field("target_pos"), field("face"), code(field("target_status")))), 8, 29, 0xffeeeeee, false);
        g.drawString(font, clip(msg("state", code(field("slot")), code(field("scope")), describe(menu.view().field("binding")))), 8, 42, 0xffeeeeee, false);
        g.drawString(font, clip(msg("notice", code(waiting ? "waiting" : menu.code()), code(field("last")), code(field("connection")))), 8, 56, 0xffffcf80, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float ticks) {
        renderBackground(g, x, y, ticks);
        super.render(g, x, y, ticks);
    }
}
