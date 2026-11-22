package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** A local grant editor, not a material-dispatch form or a claim that accepted packages completed production. */
public final class ProductionPortScreen extends AbstractContainerScreen<ProductionPortMenu> {
    private int page;
    private int selected = -1;
    private boolean waiting;
    private String draftAddress;
    private EditBox addressBox;
    private CreateTextButton grantButton;
    private Layout layout;

    public ProductionPortScreen(ProductionPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        draftAddress = menu.draftAddress();
    }
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.production_port." + key, args);
    }
    static Component statusText(String code) {
        if (code.isEmpty()) return Component.empty();
        for (String prefix : List.of("create_biotech.production_port.code.", "create_biotech.biocomputing.serial.code."))
            if (Language.getInstance().has(prefix + code)) return Component.translatable(prefix + code);
        return literal(code);
    }
    private static Component literal(String value) { return Component.literal(BrowserLiteralDisplay.quote(value)); }
    private String field(String name) { return value(menu.view().fields().get(name)); }
    private static String value(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        if (value instanceof DataValue.IntegerValue number) return Long.toString(number.value());
        return "—";
    }
    private List<DataValue> choices() {
        return menu.view().fields().get("choices") instanceof DataValue.ListValue list ? list.values() : List.of();
    }
    static Component description(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) return text("closed");
        String kind = value(record.fields().get("kind"));
        Component sourceKind = kind.equals("computer") || kind.equals("cluster") ? text(kind) : literal(kind);
        return text("source", sourceKind, literal(value(record.fields().get("terminal"))),
            literal(value(record.fields().get("port"))), literal(value(record.fields().get("member"))));
    }
    private Component notice() {
        var result = text("notice_summary", waiting ? text("waiting") : statusText(menu.code()), statusText(field("last")));
        return field("failure").isEmpty() ? result
            : Component.empty().append(result).append(text("notice_failure", statusText(field("failure"))));
    }
    private Component network() { return text("network", literal(field("network")),
        menu.view().field("orders") instanceof DataValue.IntegerValue ? field("orders") : text("unknown_count")); }
    private Component connection() {
        return text("connection", statusText(field("connection")), statusText(field("transmit")), field("frames"));
    }
    private Component details() {
        return Component.empty().append(network()).append("\n").append(description(menu.view().field("binding")))
            .append("\n").append(connection()).append("\n").append(notice())
            .append("\n").append(text("device", literal(field("device"))))
            .append("\n").append(text("current_address", literal(field("address"))))
            .append("\n").append(text("diagnostic", statusText(field("diagnostic"))))
            .append("\n").append(text("acceptance_warning"));
    }

    record Layout(int width, int height, boolean compact, boolean dense, int addressLabelY, int addressY,
            int choicesLabelY, int sourceY, int rows, boolean paging, int pageY, int grantY, int bottomY) {}
    static Layout layoutFor(int viewportWidth, int viewportHeight, int choiceCount) {
        int width = Math.min(440, Math.max(180, viewportWidth - 16));
        int height = Math.min(342, Math.max(188, viewportHeight - 16));
        boolean compact = height < 280, dense = height < 224;
        int addressLabel = dense ? 46 : compact ? 70 : 112;
        int address = addressLabel + 12, choicesLabel = address + 24, source = choicesLabel + 12;
        int grant = height - 48, bottom = height - 24, page = grant - 24;
        int rows = Math.max(1, (grant - source) / 22);
        boolean paging = choiceCount > rows;
        if (paging) rows = Math.max(1, (page - source) / 22);
        return new Layout(width, height, compact, dense, addressLabel, address, choicesLabel, source,
            rows, paging, page, grant, bottom);
    }

    @Override protected void init() {
        boolean editing = addressBox != null && addressBox.isFocused();
        layout = layoutFor(width, height, choices().size());
        imageWidth = layout.width(); imageHeight = layout.height();
        super.init();
        int rows = layout.rows();
        page = Math.max(0, Math.min(page, Math.max(0, (choices().size() - 1) / rows)));
        if (selected >= choices().size() || selected >= 0 && selected / rows != page) selected = -1;
        addressBox = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + layout.addressY(), imageWidth - 16, 18, text("address")));
        addressBox.setMaxLength(ProductionPortRequest.MAX_ADDRESS_LENGTH);
        addressBox.setFilter(ProductionPortRequest::validDraft);
        addressBox.setValue(draftAddress);
        addressBox.setHint(text("address_hint"));
        addressBox.setTooltip(Tooltip.create(text("address_hint")));
        addressBox.setEditable(!waiting);
        addressBox.setResponder(value -> { draftAddress = value; updateGrant(); });
        if (editing && !waiting) setInitialFocus(addressBox);
        for (int row = 0; row < rows && page * rows + row < choices().size(); row++) {
            int choice = page * rows + row;
            var label = description(choices().get(choice));
            var button = button(8, layout.sourceY() + row * 22, imageWidth - 16,
                Component.literal((selected == choice ? "✓ " : "") + font.plainSubstrByWidth(label.getString(), imageWidth - 38)),
                () -> { selected = choice; rebuildWidgets(); });
            button.setTooltip(Tooltip.create(label));
        }
        int half = (imageWidth - 24) / 2;
        if (layout.paging()) {
            button(8, layout.pageY(), half, text("previous"), () -> { page--; rebuildWidgets(); }).active = page > 0 && !waiting;
            button(16 + half, layout.pageY(), half, text("next"), () -> { page++; rebuildWidgets(); }).active
                = (page + 1) * rows < choices().size() && !waiting;
        }
        grantButton = button(8, layout.grantY(), half, text("grant"), () -> send(ProductionPortRequest.Action.GRANT, selected));
        grantButton.setTooltip(Tooltip.create(selected < 0 ? text("grant_hint")
            : Component.empty().append(text("grant_hint")).append("\n").append(description(choices().get(selected)))));
        updateGrant();
        button(16 + half, layout.grantY(), half, text("revoke"), () -> send(ProductionPortRequest.Action.REVOKE, -1)).active
            = !waiting && menu.view().field("binding") instanceof DataValue.RecordValue;
        button(8, layout.bottomY(), half, text("refresh"), () -> send(ProductionPortRequest.Action.REFRESH, -1));
        button(16 + half, layout.bottomY(), half, text("back"), this::onClose).active = true;
    }
    private void updateGrant() {
        if (grantButton != null) grantButton.active = !waiting && selected >= 0 && selected < choices().size() && !draftAddress.isBlank();
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 18, label, action, true));
        button.active = !waiting;
        return button;
    }
    private void send(ProductionPortRequest.Action action, int choice) {
        if (waiting) return;
        CBPackets.sendToServer(menu.request(action, choice, action == ProductionPortRequest.Action.REVOKE ? "" : draftAddress));
        waiting = true;
        rebuildWidgets();
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Container inventory shortcuts must not close the menu while typing an address (notably the letter E).
        if (addressBox != null && addressBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB)
            return addressBox.keyPressed(keyCode, scanCode, modifiers) || addressBox.canConsumeInput();
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, layout.dense() ? 20 : 24);
    }
    private void line(GuiGraphics graphics, Component value, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth - 16), 8, y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        line(graphics, title, layout.dense() ? 6 : 8, 0xffeeeeee);
        line(graphics, network(), layout.dense() ? 24 : 28, 0xffeeeeee);
        if (!layout.dense()) {
            if (!layout.compact()) {
                line(graphics, text("device", literal(field("device"))), 42, 0xffdddddd);
                line(graphics, connection(), 56, 0xffdddddd);
                line(graphics, description(menu.view().field("binding")), 70, 0xffdddddd);
                line(graphics, notice(), 84, 0xffffcf80);
            } else line(graphics, notice(), 42, 0xffffcf80);
        }
        line(graphics, text("acceptance_warning"), layout.dense() ? 36 : layout.compact() ? 56 : 98, 0xffffcf80);
        line(graphics, text("address"), layout.addressLabelY(), 0xffeeeeee);
        line(graphics, text("choices", choices().size()), layout.choicesLabelY(), 0xffeeeeee);
        if (choices().isEmpty()) line(graphics, text("empty"), layout.sourceY() + 4, 0xffdddddd);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8
                && mouseY >= topPos + 24 && mouseY < topPos + layout.addressLabelY())
            graphics.renderTooltip(font, font.split(details(), Math.min(320, imageWidth - 16)), mouseX, mouseY);
    }
}
