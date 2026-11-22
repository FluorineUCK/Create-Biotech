package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;

/** Configuration only. Applying a grant never starts a program or sets a redstone value. */
public final class RedstoneOutputScreen extends AbstractContainerScreen<RedstoneOutputMenu> {
    private int page, selected = -1, rows;
    private boolean waiting;
    public RedstoneOutputScreen(RedstoneOutputMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.redstone_output." + key, args); }
    static Component statusText(String code) {
        for (String prefix : List.of("create_biotech.redstone_output.code.", "create_biotech.biocomputing.serial.code."))
            if (Language.getInstance().has(prefix + code)) return Component.translatable(prefix + code);
        return Component.literal(BrowserLiteralDisplay.quote(code));
    }
    static Component directionText(String face) {
        return Component.translatableWithFallback("create_biotech.biocomputing.communication.face." + face,
            "%s", Component.literal(BrowserLiteralDisplay.quote(face)));
    }
    static Component notice(String code, String last, String failure, boolean waiting) {
        var result = text("notice_summary", waiting ? text("waiting") : statusText(code), statusText(last));
        return failure.isEmpty() ? result : Component.empty().append(result).append(text("notice_failure", statusText(failure)));
    }
    private Component notice() { return notice(menu.code(), field("last"), field("failure"), waiting); }
    private List<DataValue> choices() { return ((DataValue.ListValue)menu.view().field("choices")).values(); }
    private String field(String name) { return value(menu.view().fields().get(name)); }
    private static String value(DataValue v) {
        if (v instanceof DataValue.TextValue t) return t.value();
        if (v instanceof DataValue.IntegerValue i) return Long.toString(i.value());
        return "—";
    }
    private Component description(DataValue value) {
        if (!(value instanceof DataValue.RecordValue r)) return text("closed");
        return text("source", text(value(r.field("kind"))), value(r.field("terminal")), value(r.field("port")), value(r.field("member")));
    }
    @Override protected void init() {
        imageWidth = Math.min(420, Math.max(180, width - 16)); imageHeight = Math.min(270, Math.max(198, height - 16));
        super.init();
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.READER));
        rows = Math.max(1, (imageHeight - 172) / 22);
        page = Math.max(0, Math.min(page, Math.max(0, (choices().size() - 1) / rows)));
        for (int row = 0; row < rows && page * rows + row < choices().size(); row++) {
            int choice = page * rows + row; var label = description(choices().get(choice));
            var button = button(8, 100 + row * 22, imageWidth - 16,
                Component.literal((selected == choice ? "✓ " : "") + font.plainSubstrByWidth(label.getString(), imageWidth - 38)),
                () -> { selected = choice; rebuildWidgets(); });
            button.setTooltip(Tooltip.create(label));
        }
        int half = (imageWidth - 24) / 2;
        if (choices().size() > rows) {
            button(8, imageHeight - 72, half, text("previous"), () -> { page--; rebuildWidgets(); }).active = page > 0 && !waiting;
            button(16 + half, imageHeight - 72, half, text("next"), () -> { page++; rebuildWidgets(); }).active = (page + 1) * rows < choices().size() && !waiting;
        }
        var grant = button(8, imageHeight - 48, half, text("grant"), () -> send(RedstoneOutputRequest.Action.GRANT, selected));
        grant.active = selected >= 0 && !waiting; grant.setTooltip(Tooltip.create(text("grant_hint")));
        button(16 + half, imageHeight - 48, half, text("revoke"), () -> send(RedstoneOutputRequest.Action.REVOKE, -1)).active = !waiting
            && menu.view().field("binding") instanceof DataValue.RecordValue;
        button(8, imageHeight - 24, half, text("refresh"), () -> send(RedstoneOutputRequest.Action.REFRESH, -1));
        button(16 + half, imageHeight - 24, half, text("back"), this::onClose);
    }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 18, label, action));
        button.active = !waiting; return button;
    }
    private void send(RedstoneOutputRequest.Action action, int choice) {
        if (waiting) return; CBPackets.sendToServer(menu.request(action, choice)); waiting = true; rebuildWidgets();
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 24);
    }
    private void line(GuiGraphics g, Component value, int y, int color) {
        g.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth - (y == 8 ? 90 : 16)), 8, y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        line(g, title, 8, 0xffeeeeee);
        line(g, text("levels", field("desired"), field("applied")), 28, 0xffeeeeee);
        line(g, text("face", directionText(field("face")), statusText(field("connection")), field("frames")), 42, 0xffeeeeee);
        line(g, description(menu.view().field("binding")), 56, 0xffdddddd);
        line(g, notice(), 70, 0xffffcf80);
        line(g, text("choices"), 86, 0xffeeeeee);
        if (choices().isEmpty()) line(g, text("empty"), 105, 0xffdddddd);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8 && mouseY >= topPos + 42 && mouseY < topPos + 80) {
            var detail = mouseY < topPos + 56
                ? text("face", directionText(field("face")), statusText(field("connection")), field("frames"))
                : mouseY < topPos + 70 ? description(menu.view().field("binding")) : notice();
            g.renderTooltip(font, font.split(detail, Math.min(260, imageWidth - 16)), mouseX, mouseY);
        }
    }
}
