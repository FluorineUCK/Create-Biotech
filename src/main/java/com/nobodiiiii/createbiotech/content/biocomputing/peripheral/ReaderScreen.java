package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Source and component selection are a draft; only Apply authorizes background requests. */
public final class ReaderScreen extends AbstractContainerScreen<ReaderMenu> {
    private int page, selected, rows;
    private boolean componentsTab, waiting;
    private final Set<Integer> enabled = new TreeSet<>();
    public ReaderScreen(ReaderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); selected = (int)((DataValue.IntegerValue)menu.view().field("current")).value();
        var granted = ((DataValue.ListValue)menu.view().field("components")).values(); var catalog = entries("catalog");
        for (int i = 0; i < catalog.size(); i++) if (granted.contains(((DataValue.RecordValue)catalog.get(i)).field("id"))) enabled.add(i);
    }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.reader." + key, args); }
    static Component statusText(String code) {
        if (code.isEmpty()) return Component.empty();
        for (String prefix : List.of("create_biotech.reader.code.", "create_biotech.biocomputing.serial.code."))
            if (Language.getInstance().has(prefix + code)) return Component.translatable(prefix + code);
        return Component.literal(BrowserLiteralDisplay.quote(code));
    }
    private List<DataValue> entries(String name) { return ((DataValue.ListValue)menu.view().field(name)).values(); }
    private String field(String name) { return ((DataValue.TextValue)menu.view().field(name)).value(); }
    private Component source(DataValue value) {
        if (!(value instanceof DataValue.RecordValue r)) return text("closed");
        return text("source", text(((DataValue.TextValue)r.field("kind")).value()), ((DataValue.TextValue)r.field("terminal")).value(),
            ((DataValue.TextValue)r.field("port")).value(), ((DataValue.TextValue)r.field("member")).value());
    }
    private Component notice() { return text("notice", waiting ? text("waiting") : statusText(menu.code()), statusText(field("last")), statusText(field("connection"))); }
    @Override protected void init() {
        imageWidth = Math.min(420, Math.max(180, width - 16)); imageHeight = Math.min(290, Math.max(212, height - 16)); super.init();
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.READER));
        rows = Math.max(1, (imageHeight - 184) / 22); var entries = entries(componentsTab ? "catalog" : "choices");
        page = Math.max(0, Math.min(page, Math.max(0, (entries.size() - 1) / rows))); int half = (imageWidth - 24) / 2;
        button(8, 84, half, text("sources"), () -> { componentsTab = false; page = 0; rebuildWidgets(); }).active = !waiting && componentsTab;
        button(16 + half, 84, half, text("components", enabled.size()), () -> { componentsTab = true; page = 0; rebuildWidgets(); }).active = !waiting && !componentsTab;
        for (int row = 0; row < rows && page * rows + row < entries.size(); row++) {
            int index = page * rows + row; var entry = (DataValue.RecordValue)entries.get(index);
            Component label = componentsTab ? Component.translatable(((DataValue.TextValue)entry.field("label")).value()) : source(entry);
            Component detail = componentsTab ? Component.empty().append(label).append("\n").append(((DataValue.TextValue)entry.field("id")).value())
                .append("\n").append(text(((DataValue.BooleanValue)entry.field("additional")).value() ? "additional" : "builtin")) : label;
            boolean checked = componentsTab ? enabled.contains(index) : selected == index;
            var button = button(8, 108 + row * 22, imageWidth - 16, Component.literal((checked ? "✓ " : "")
                + font.plainSubstrByWidth(label.getString(), imageWidth - 38)), () -> {
                    if (componentsTab) { if (!enabled.remove(index)) enabled.add(index); } else selected = index; rebuildWidgets();
                });
            button.setTooltip(Tooltip.create(detail));
        }
        if (entries.size() > rows) {
            button(8, imageHeight - 72, half, text("previous"), () -> { page--; rebuildWidgets(); }).active = page > 0 && !waiting;
            button(16 + half, imageHeight - 72, half, text("next"), () -> { page++; rebuildWidgets(); }).active = (page + 1) * rows < entries.size() && !waiting;
        }
        var grant = button(8, imageHeight - 48, half, text("grant"), () -> send(ReaderRequest.Action.GRANT));
        grant.active = !waiting && selected >= 0 && !enabled.isEmpty(); grant.setTooltip(Tooltip.create(text("grant_hint")));
        button(16 + half, imageHeight - 48, half, text("revoke"), () -> send(ReaderRequest.Action.REVOKE)).active = !waiting && menu.view().field("binding") instanceof DataValue.RecordValue;
        button(8, imageHeight - 24, half, text("refresh"), () -> send(ReaderRequest.Action.REFRESH));
        button(16 + half, imageHeight - 24, half, text("back"), this::onClose);
    }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 18, label, action)); button.active = !waiting; return button;
    }
    private void send(ReaderRequest.Action action) {
        if (waiting) return;
        CBPackets.sendToServer(menu.request(action, action == ReaderRequest.Action.GRANT ? selected : -1,
            action == ReaderRequest.Action.GRANT ? List.copyOf(enabled) : List.of())); waiting = true; rebuildWidgets();
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 24);
    }
    private void line(GuiGraphics g, Component message, int y, int color) { g.drawString(font, font.plainSubstrByWidth(message.getString(), imageWidth - (y == 8 ? 90 : 16)), 8, y, color, false); }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        line(g, title, 8, 0xffeeeeee); line(g, source(menu.view().field("binding")), 28, 0xffdddddd);
        line(g, text("grant_hint"), 42, 0xffdddddd); line(g, notice(), 56, 0xffffcf80);
        if (entries(componentsTab ? "catalog" : "choices").isEmpty()) line(g, text("empty"), 112, 0xffdddddd);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8 && mouseY >= topPos + 28 && mouseY < topPos + 78) {
            var detail = mouseY < topPos + 42 ? source(menu.view().field("binding")) : mouseY < topPos + 56 ? text("grant_hint")
                : Component.empty().append(notice()).append("\n").append(statusText(field("failure"))).append("\n").append(statusText(field("diagnostic")));
            g.renderTooltip(font, font.split(detail, Math.min(260, imageWidth - 16)), mouseX, mouseY);
        }
    }
}
