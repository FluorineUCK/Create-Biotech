package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Explicit, server-published pin selection. Saving metadata does not schedule an XBus exchange. */
public final class ComputerWiringScreen extends AbstractSimiContainerScreen<AbstractContainerMenu> {
    private final Screen parent;
    private String nameText = "";
    private EditBox name;
    private CreateTextButton local, peer, entry, save, remove, toggle, refresh, previous, next;
    private boolean requested, pending, showingEntry;
    private WiringMenu.Action requestedAction;
    private long responseVersion;
    private int localChoice, peerChoice, entryChoice, page;
    private WiringStatusView snapshot;
    private List<String> lines = List.of();
    private Component notice = Component.empty();
    public <M extends AbstractContainerMenu & WiringMenu> ComputerWiringScreen(M menu, Inventory inventory, Component title, Screen parent) {
        super(menu, inventory, title); this.parent = Objects.requireNonNull(parent); responseVersion = menu.wiringResponseVersion();
    }
    private WiringMenu host() { return (WiringMenu) menu; }
    @Override protected void init() {
        boolean focusName = name != null && getFocused() == name;
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.WIRING));
        name = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 44, imageWidth - 16, 18, text("name")));
        name.setMaxLength(EndpointConfiguration.MAX_NAME_LENGTH + 1); name.setValue(nameText);
        name.setHint(Component.literal(font.plainSubstrByWidth(text("name").getString(), name.getWidth() - 8)));
        name.setResponder(value -> nameText = value);
        int column = (imageWidth - 20) / 2;
        local = button(8, 66, column, "local", () -> {
            if (!pending && snapshot != null && !snapshot.localPins().isEmpty()) { localChoice = (localChoice + 1) % snapshot.localPins().size(); showingEntry = false; page = 0; update(); }
        });
        peer = button(12 + column, 66, column, "peer", () -> {
            if (!pending && snapshot != null && !snapshot.peers().isEmpty()) { peerChoice = (peerChoice + 1) % snapshot.peers().size(); showingEntry = false; page = 0; update(); }
        });
        save = button(8, 90, column, "save", this::save);
        refresh = button(12 + column, 90, column, "refresh", this::read);
        entry = button(8, 114, imageWidth - 172, "entries", () -> {
            if (!pending && snapshot != null && !snapshot.entries().isEmpty()) {
                if (showingEntry) entryChoice = (entryChoice + 1) % snapshot.entries().size();
                showingEntry = true; page = 0; update();
            }
        });
        toggle = button(imageWidth - 160, 114, 72, "enable", this::toggle);
        remove = button(imageWidth - 84, 114, 76, "remove", this::remove);
        previous = button(8, imageHeight - 27, 32, "previous", () -> { page--; refreshPages(); });
        next = button(44, imageHeight - 27, 32, "next", () -> { page++; refreshPages(); });
        button(imageWidth - 76, imageHeight - 27, 68, "back", this::onClose);
        update(); if (focusName) setFocused(name);
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, text(key), action, true));
    }
    private void read() { send(WiringMenu.Action.READ, new DataValue.RecordValue(Map.of())); }
    private void save() {
        if (pending || snapshot == null || snapshot.localPins().isEmpty() || snapshot.peers().isEmpty()) return;
        try { send(WiringMenu.Action.ADD, new WiringControlData.Add(snapshot.view(), nameText, snapshot.localPins().get(localChoice), peerChoice).encode()); }
        catch (IllegalArgumentException invalid) { notice = text("invalid", code(invalid.getMessage())); }
    }
    private void remove() {
        if (pending || snapshot == null || !showingEntry || snapshot.entries().isEmpty()) return;
        send(WiringMenu.Action.REMOVE, new WiringControlData.Remove(snapshot.view(), snapshot.entries().get(entryChoice).name()).encode());
    }
    private void toggle() {
        if (pending || snapshot == null || !showingEntry || snapshot.entries().isEmpty()) return;
        var selected = snapshot.entries().get(entryChoice);
        send(WiringMenu.Action.SET_ENABLED, new WiringControlData.SetEnabled(snapshot.view(), selected.name(), !selected.enabled()).encode());
    }
    private void send(WiringMenu.Action action, DataValue.RecordValue inputs) {
        if (pending) return;
        pending = true; requestedAction = action; snapshot = null; lines = List.of(); notice = text("waiting");
        host().wiringControl(action, inputs); update();
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; read(); }
        if (responseVersion == host().wiringResponseVersion()) return;
        responseVersion = host().wiringResponseVersion(); pending = false; snapshot = null;
        host().wiringResponse().ifPresent(reply -> {
            notice = reply.code().equals("ok") ? text(requestedAction == WiringMenu.Action.ADD ? "saved"
                : requestedAction == WiringMenu.Action.REMOVE ? "removed" : requestedAction == WiringMenu.Action.SET_ENABLED ? "state_saved" : "received")
                : text("invalid", code(reply.detail().isEmpty() ? reply.code() : reply.detail()));
            try {
                if (reply.status().isPresent()) {
                    var received = WiringStatusView.decode(reply.status().orElseThrow());
                    if (received.view() != reply.intent()) throw new IllegalArgumentException("stale");
                    snapshot = received;
                }
            } catch (IllegalArgumentException invalid) { notice = text("invalid", code(invalid.getMessage())); }
        });
        localChoice = peerChoice = entryChoice = page = 0; showingEntry = false; update();
    }
    private void update() {
        boolean ready = snapshot != null && !pending;
        boolean hasLocal = ready && !snapshot.localPins().isEmpty(), hasPeer = ready && !snapshot.peers().isEmpty();
        boolean hasEntry = ready && !snapshot.entries().isEmpty();
        local.active = hasLocal; peer.active = hasPeer; entry.active = hasEntry;
        save.active = hasLocal && hasPeer; remove.active = hasEntry && showingEntry; refresh.active = !pending;
        toggle.active = hasEntry && showingEntry;
        toggle.setMessage(text(hasEntry && showingEntry && snapshot.entries().get(entryChoice).enabled() ? "disable" : "enable"));
        toggle.getToolTip().clear(); toggle.getToolTip().add(text("activation_help"));
        label(local, hasLocal ? text("local_value", literal(snapshot.localPins().get(localChoice))) : text("local"));
        label(peer, hasPeer ? text("peer_value", peerChoice + 1, snapshot.peers().size(), literal(snapshot.peers().get(peerChoice).endpoint()), literal(snapshot.peers().get(peerChoice).pin())) : text("peer"));
        label(entry, hasEntry ? text("entry_value", entryChoice + 1, snapshot.entries().size(), literal(snapshot.entries().get(entryChoice).name())) : text("entries"));
        var details = new ArrayList<String>();
        host().wiringContext().ifPresent(context -> wrap(details, context));
        if (snapshot != null) {
            wrap(details, text("discovery", code(snapshot.discovery()), snapshot.revision()));
            if (showingEntry && hasEntry) {
                var selected = snapshot.entries().get(entryChoice);
                wrap(details, text("entry_detail", literal(selected.name()), literal(selected.localPin()), code(selected.availability())));
                wrap(details, text(selected.enabled() ? "enabled_config" : "disabled_config"));
                describe(details, selected.peer());
            } else if (hasPeer) describe(details, snapshot.peers().get(peerChoice));
        }
        lines = List.copyOf(details); refreshPages();
    }
    private static void label(CreateTextButton button, Component label) {
        button.setMessage(label); button.getToolTip().clear(); button.getToolTip().add(label); button.getToolTip().add(text("select_help"));
    }
    private void describe(List<String> lines, WiringStatusView.Pin pin) {
        wrap(lines, text("target", literal(pin.domain()), literal(pin.endpoint()), literal(pin.pin())));
        wrap(lines, text("position", pin.x(), pin.y(), pin.z()));
    }
    private void wrap(List<String> lines, Component value) {
        String remaining = value.getString();
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, imageWidth - 16);
            if (!line.isEmpty() && Character.isHighSurrogate(line.charAt(line.length() - 1))) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) line = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
            lines.add(line); remaining = remaining.substring(line.length());
        }
    }
    private int pageSize() { return Math.max(1, (imageHeight - 198) / 12); }
    private void refreshPages() {
        var selected = ComputerValueDisplay.page(lines, page, pageSize()); page = selected.index();
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = page + 1 < selected.count();
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        line(graphics, text("title"), 10, 0xffeeeeee); line(graphics, text("mode"), 30, 0xffffddaa);
        var slice = ComputerValueDisplay.page(lines, page, pageSize());
        for (int row = 0; row < slice.lines().size(); row++) line(graphics, Component.literal(slice.lines().get(row)), 140 + row * 12, 0xffdddddd);
        line(graphics, notice, imageHeight - 53, 0xffffddaa);
    }
    private void line(GuiGraphics graphics, Component value, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth - (y == 10 ? 90 : 16)), leftPos + 8, topPos + y, color, false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.render(graphics, mouseX, mouseY, partial);
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8) {
            Component tip = mouseY >= topPos + 28 && mouseY < topPos + 42 ? text("mode_help")
                : mouseY >= topPos + imageHeight - 55 && mouseY < topPos + imageHeight - 40 ? notice : null;
            if (tip != null) graphics.renderTooltip(font, font.split(tip, Math.min(320, imageWidth - 16)), mouseX, mouseY);
        }
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { host().leaveWiring(); minecraft.setScreen(parent); }
    @Override public void removed() {}
    private static String literal(String value) { return BrowserLiteralDisplay.quote(value); }
    private static Component code(String value) {
        String key = "create_biotech.biocomputing.wiring.code." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(literal(value));
    }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.wiring." + key, args); }
}
