package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerValueTreeScreen;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest.Action;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationValues.record;

public final class StockApplicationScreen extends AbstractContainerScreen<StockApplicationMenu> {
    private static final int ROW_START = 74, ROW_HEIGHT = 21;
    private final Inventory playerInventory;
    private final List<CreateTextButton> rowButtons = new ArrayList<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private UUID shown;
    private boolean config, materials, waiting;
    private String materialRoute;
    private int page, choice = -1;
    private String search = "", selected = "", address = "", quantity = "1";
    private List<Entry> entries = List.of(), matches = List.of();
    private CreateTextButton previous, next, submit, details;
    public StockApplicationScreen(StockApplicationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); playerInventory = inventory; config = physical();
    }
    public static Component text(String key, Object... args) { return Component.translatable("create_biotech.stock_application." + key, args); }
    private static Component code(String code) {
        return Component.translatableWithFallback("create_biotech.stock_application.code." + code, code);
    }
    private String field(String name) { return StockApplicationCodec.string(menu.view().field(name)); }
    private int integer(String name) { return (int)StockApplicationCodec.integer(menu.view().field(name)); }
    private List<DataValue> choices() { return ((DataValue.ListValue)menu.view().field("choices")).values(); }
    private boolean physical() { return ((DataValue.BooleanValue)menu.view().field("physical")).value(); }
    private boolean nativeOrder() { return menu.view().fields().get("native_order") instanceof DataValue.NbtValue; }
    private boolean mayWithdraw() { return ((DataValue.BooleanValue)menu.view().field("may_withdraw")).value(); }
    private boolean manage() { return ((DataValue.BooleanValue)menu.view().field("manage")).value(); }
    private Entry selected() { return entries.stream().filter(entry -> entry.key().equals(selected)).findFirst().orElse(null); }
    private long count() { try { return Long.parseLong(quantity); } catch (NumberFormatException invalid) { return 0; } }
    private ItemStack icon(Entry entry) {
        return minecraft.level == null ? ItemStack.EMPTY : icons.computeIfAbsent(entry.key(), key ->
            StockApplicationIcons.resolve(entry.icon(), minecraft.level.registryAccess()));
    }

    @Override protected void init() {
        imageWidth = Math.min(370, width - 12); imageHeight = Math.min(280, height - 12);
        super.init();
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.ORDER));
        shown = menu.nonce(); entries = StockApplicationCodec.readEntries(menu.view().field("catalog"));
        icons.clear();
        if (selected() == null) selected = "";
        if (choice >= choices().size()) choice = -1;
        int half = (imageWidth - 20) / 2;
        if (nativeOrder()) {
            rowButtons.clear();
            button(8, imageHeight - 64, imageWidth - 16, text("details"), () -> minecraft.setScreen(
                new ComputerValueTreeScreen<>(menu, playerInventory, title, this,
                    record("order",menu.view().field("native_order"),"address",menu.view().field("native_address")),
                    null, Component.empty(), text("native_details"), null)));
            var confirm = button(8,imageHeight - 42,imageWidth - 16,text("native_confirm"),this::confirmNative);
            confirm.active = !waiting && field("status").equals("ready")
                && !Set.of("input_queued","existing","retired").contains(menu.code());
            button(8,imageHeight - 20,half,text("refresh"),() -> send(Action.REFRESH,-1));
            button(12+half,imageHeight - 20,half,text("back"),() -> send(Action.BACK,-1));
            return;
        }
        if (physical()) {
            button(8, 24, half, text("port"), () -> tab(true, false)).setTooltip(Tooltip.create(text("shared_wire")));
            button(12 + half, 24, half, text("materials"), () -> tab(true, true));
        } else {
            button(8, 24, half, text("catalog"), () -> tab(false, false));
            button(12 + half, 24, half, text("port"), () -> tab(true, false)).setTooltip(Tooltip.create(text("shared_wire")));
        }
        if (materials) {
            if (materialRoute == null) materialRoute = field("material_address");
            var destination = edit(8, 90, imageWidth - 16, text("material_address"), 25, materialRoute);
            destination.setResponder(value -> materialRoute = value);
            destination.setEditable(!waiting && mayGrantMaterials());
            button(8, 114, half, text("grant_materials"), () -> send(Action.GRANT_MATERIALS, -1)).active = !waiting && mayGrantMaterials();
            button(12 + half, 114, half, text("revoke_materials"), () -> send(Action.REVOKE_MATERIALS, -1)).active =
                !waiting && mayWithdraw() && !field("material_address").isEmpty();
            button(8, imageHeight - 20, half, text("refresh"), () -> send(Action.REFRESH, -1));
            button(12 + half, imageHeight - 20, half, text("back"), () -> send(Action.BACK, -1));
            return;
        }
        if (!config) {
            var box = edit(8, 48, imageWidth - 16, text("search"), 128, search);
            box.setResponder(value -> { search = value; page = 0; updateRows(); });
        }
        rowButtons.clear();
        int footer = imageHeight - 90;
        int rows = Math.max(1, (footer - ROW_START - 3) / ROW_HEIGHT);
        for (int i = 0; i < rows; i++) {
            int row = i;
            rowButtons.add(button(config ? 8 : 28, ROW_START + row * ROW_HEIGHT, imageWidth - (config ? 16 : 36),
                Component.empty(), () -> select(row)));
        }
        previous = button(8, footer, 30, Component.literal("<"), () -> { page--; updateRows(); });
        next = button(42, footer, 30, Component.literal(">"), () -> { page++; updateRows(); });
        if (config) {
            int third = (imageWidth - 24) / 3;
            submit = button(8, imageHeight - 64, third, text("bind"), () -> send(Action.BIND, choice));
            var revoke = button(12 + third, imageHeight - 64, third, text(physical() ? "revoke" : "unbind"), () -> send(Action.REVOKE, -1));
            revoke.active = !waiting && mayWithdraw() && menu.view().field(physical() ? "binding" : "selected_adapter") instanceof DataValue.RecordValue;
            var discard = button(16 + 2 * third, imageHeight - 64, third, text("discard"), this::discard);
            discard.visible = physical(); discard.active = !waiting && physical() && mayWithdraw() && integer("pending") > 0;
            discard.setTooltip(Tooltip.create(text("discard_hint")));
            details = null;
        } else {
            int amount = 55;
            var qty = edit(8, imageHeight - 65, amount, text("quantity"), 19, quantity);
            qty.setFilter(value -> value.matches("[0-9]*"));
            qty.setResponder(value -> { quantity = value; updateActions(); });
            var dest = edit(12 + amount, imageHeight - 65, imageWidth - amount - 20,
                text("address"), StockApplicationMenuRequest.MAX_ADDRESS, address);
            dest.setResponder(value -> { address = value; updateActions(); });
            submit = button(8, imageHeight - 42, half, text("confirm"), () -> send(Action.CONFIRM, -1));
            details = button(12 + half, imageHeight - 42, half, text("details"), this::details);
        }
        button(8, imageHeight - 20, half, text("refresh"), () -> send(Action.REFRESH, -1));
        button(12 + half, imageHeight - 20, half, text("back"), () -> send(Action.BACK, -1));
        updateRows();
    }
    private boolean mayGrantMaterials() { return ((DataValue.BooleanValue)menu.view().field("may_grant_materials")).value(); }
    private void tab(boolean port, boolean materialTab) {
        if (config != port || materials != materialTab) { config = port; materials = materialTab; page = 0; rebuildWidgets(); }
    }
    private EditBox edit(int x, int y, int w, Component label, int maximum, String value) {
        var edit = new EditBox(font, leftPos + x, topPos + y, w, 18, label);
        edit.setMaxLength(maximum); edit.setHint(label); edit.setValue(value); edit.setTooltip(Tooltip.create(label));
        return addRenderableWidget(edit);
    }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 18, label, action, true));
        button.active = !waiting; return button;
    }
    private static Component source(DataValue value) {
        if (!(value instanceof DataValue.RecordValue row)) return text("no_binding");
        var kind = text(StockApplicationCodec.string(row.field("kind")));
        return text("source", kind, StockApplicationCodec.string(row.field("terminal")),
            BrowserLiteralDisplay.quote(StockApplicationCodec.string(row.field("port"))), StockApplicationCodec.string(row.field("member")));
    }
    private int total() { return config ? choices().size() : matches.size(); }
    private void updateRows() {
        String query = search.toLowerCase(Locale.ROOT).strip();
        matches = entries.stream().filter(entry -> (entry.title() + " " + entry.key() + " " + icon(entry).getHoverName().getString())
            .toLowerCase(Locale.ROOT).contains(query)).toList();
        int rows = rowButtons.size();
        page = Math.clamp(page, 0, Math.max(0, (total() - 1) / rows));
        for (int i = 0; i < rows; i++) {
            int index = page * rows + i; var button = rowButtons.get(i);
            button.visible = index < total(); button.active = !waiting && button.visible;
            if (!button.visible) continue;
            if (config) {
                var label = source(choices().get(index));
                button.setMessage(Component.literal((index == choice ? "> " : "") + label.getString()));
                button.setTooltip(Tooltip.create(label));
            } else {
                var entry = matches.get(index);
                var label = text("entry", BrowserLiteralDisplay.quote(entry.title()), BrowserLiteralDisplay.quote(entry.key()), BrowserLiteralDisplay.quote(entry.unit()));
                button.setMessage(Component.literal((entry.key().equals(selected) ? "> " : "") + label.getString()));
                button.setTooltip(Tooltip.create(label));
            }
        }
        previous.active = !waiting && page > 0; next.active = !waiting && (page + 1) * rows < total();
        updateActions();
    }
    private void select(int row) {
        int index = page * rowButtons.size() + row;
        if (index >= total()) return;
        if (config) choice = index; else selected = matches.get(index).key();
        updateRows();
    }
    private void updateActions() {
        if (submit == null) return;
        if (config) submit.active = !waiting && manage() && choice >= 0;
        else {
            var entry = selected();
            submit.active = !waiting && entry != null && !icon(entry).isEmpty() && field("status").equals("ready")
                && count() > 0 && !address.isBlank() && address.length() <= integer("address_chars");
            if (details != null) details.active = !waiting && entry != null;
        }
    }
    private void send(Action action, int choice) {
        if (waiting) return;
        CBPackets.sendToServer(menu.request(action, choice, action == Action.CONFIRM ? selected : "",
            action == Action.CONFIRM ? count() : 0, action == Action.CONFIRM ? address : action == Action.GRANT_MATERIALS ? materialRoute : ""));
        waiting = true; rebuildWidgets();
    }
    private void discard() {
        int pending = integer("pending"); UUID snapshot = menu.nonce();
        minecraft.setScreen(new ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes && snapshot.equals(menu.nonce())) send(Action.DISCARD, pending);
        }, text("discard"), text("discard_confirm", pending)));
    }
    private void confirmNative() {
        UUID snapshot = menu.nonce();
        minecraft.setScreen(new ConfirmScreen(yes -> {
            minecraft.setScreen(this);
            if (yes && snapshot.equals(menu.nonce())) send(Action.CONFIRM_NATIVE,-1);
        },text("native_confirm"),text("native_confirm_hint")));
    }
    private void details() {
        var entry = selected();
        if (entry != null) minecraft.setScreen(new ComputerValueTreeScreen<>(menu, playerInventory, title, this,
            record("entry", StockApplicationValues.entry(entry)), null, Component.empty(), text("public_details"), null));
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!menu.nonce().equals(shown)) { waiting = false; choice = -1; materialRoute = null; rebuildWidgets(); }
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 22);
    }
    private Component notice() { return waiting ? text("waiting") : text("notice", code(menu.code()), code(field("status"))); }
    private Component lineStatus() { return text("line", field("target"), field("face"), code(field("connection")), integer("pending"), integer("capacity")); }
    private void line(GuiGraphics g, Component label, int x, int y, int color) {
        g.drawString(font, font.plainSubstrByWidth(label.getString(), imageWidth - x - 8), x, y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 90), 8, 7, 0xffeeeeee, false);
        if (nativeOrder()) {
            line(g,text("native_details"),8,32,0xffeeeeee);
            line(g,source(menu.view().field("binding")),8,49,0xffdddddd);
            line(g,text("native_address",field("native_address")),8,66,0xffdddddd);
            int y = 85;
            for (var row : font.split(text("native_confirm_hint"),imageWidth - 16)) {
                if (y + font.lineHeight > imageHeight - 90) break;
                g.drawString(font,row,8,y,0xffdddddd,false); y += font.lineHeight + 2;
            }
            line(g,notice(),8,imageHeight - 84,0xffffcf80);
            return;
        }
        if (materials) {
            line(g, source(menu.view().field("binding")), 8, 47, 0xffdddddd);
            line(g, text("material_hint"), 8, 61, 0xffdddddd);
            line(g, text("material_state", code(field("material_status")), field("material_address")), 8, 76, 0xffdddddd);
            line(g, text("notice", code(menu.code()), code(field("last"))), 8, 140, 0xffffcf80);
            return;
        }
        if (config) {
            line(g, source(menu.view().field(physical() ? "binding" : "selected_adapter")), 8, 47, 0xffdddddd);
            line(g, lineStatus(), 8, 60, 0xffdddddd);
            line(g, physical() ? text("diagnostics", code(field("last")), code(field("discovery")))
                : source(menu.view().field("binding")), 8, imageHeight - 42, 0xffdddddd);
        }
        line(g, notice(), 80, imageHeight - 85, 0xffffcf80);
        if (total() == 0) line(g, text(config ? manage() ? "no_sources" : "admin_only" : "no_entries"), 8, ROW_START, 0xffdddddd);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g, mx, my, partial);
        super.render(g, mx, my, partial);
        if (!nativeOrder() && !config) for (int i = 0; i < rowButtons.size(); i++) {
            int index = page * rowButtons.size() + i;
            if (index >= matches.size()) break;
            var item = icon(matches.get(index));
            int x = leftPos + 8, y = topPos + ROW_START + i * ROW_HEIGHT + 1;
            if (!item.isEmpty()) {
                g.renderItem(item, x, y); g.renderItemDecorations(font, item, x, y);
                if (mx >= x && mx < x + 16 && my >= y && my < y + 16) g.renderTooltip(font, item, mx, my);
            } else {
                g.drawString(font, "?", x + 4, y + 3, 0xffffcf80);
                if (mx >= x && mx < x + 16 && my >= y && my < y + 16) g.renderTooltip(font, text("invalid_icon"), mx, my);
            }
        }
        if (mx >= leftPos + 8 && mx < leftPos + imageWidth - 8) {
            Component tooltip = null;
            if (my >= topPos + imageHeight - 89 && my < topPos + imageHeight - 72 && mx >= leftPos + 78) tooltip = notice();
            else if (config && !materials && my >= topPos + 44 && my < topPos + ROW_START)
                tooltip = my < topPos + 58 ? source(menu.view().field(physical() ? "binding" : "selected_adapter")) : lineStatus();
            if (tooltip != null) g.renderTooltip(font, font.split(tooltip, Math.min(240, imageWidth - 12)), mx, my);
        }
    }
}
