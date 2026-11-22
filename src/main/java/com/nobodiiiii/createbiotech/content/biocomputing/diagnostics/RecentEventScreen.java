package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.BrowserLiteralDisplay;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Shares the real authorized device menu. No new endpoint grant, world capture or execution action. */
public final class RecentEventScreen extends AbstractSimiContainerScreen<AbstractContainerMenu> {
    private record Line(RecentEventScroll.Anchor anchor, FormattedCharSequence text, int color) {}
    private final Runnable back;
    private final RecentEventScroll scroll = new RecentEventScroll();
    private RecentEventWire.View view;
    private List<Line> lines = List.of();
    private CreateTextButton refresh;
    private long responseVersion;
    private int ticks;
    private boolean requested, pending;
    private Component notice = text("loading");
    private static final int CONTENT_TOP = 66, LINE_HEIGHT = 12, REFRESH_TICKS = 20;

    public <M extends AbstractContainerMenu & RecentEventMenu> RecentEventScreen(M menu, Inventory inventory, Runnable back) {
        super(menu, inventory, title()); this.back = Objects.requireNonNull(back); responseVersion = menu.logResponseVersion();
    }
    public static Component title() { return text("title"); }
    private RecentEventMenu host() { return (RecentEventMenu)menu; }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init(); clearWidgets();
        refresh = button(8, 30, 80, text("refresh"), this::read);
        button(92, 30, 80, text("tail"), () -> scroll.scroll(Integer.MAX_VALUE));
        button(imageWidth - 80, imageHeight - 27, 72, text("back"), this::onClose);
        rebuild(); refresh.active = !pending;
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
    }
    private int visibleRows() { return Math.max(1, (imageHeight - CONTENT_TOP - 54) / LINE_HEIGHT); }
    private void read() {
        if (pending) return;
        pending = true; ticks = 0; refresh.active = false; host().requestLog();
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; read(); }
        if (responseVersion != host().logResponseVersion()) {
            responseVersion = host().logResponseVersion();
            host().logResponse().ifPresent(reply -> {
                pending = false; refresh.active = true;
                if (!reply.code().equals("ok")) { view = null; notice = text("failure", reply.code()); }
                else try {
                    var next = RecentEventWire.readStatus(reply.status().orElseThrow());
                    if (view != null && next.sequence() < view.sequence()) scroll.scroll(Integer.MAX_VALUE);
                    view = next; notice = text("received");
                } catch (IllegalArgumentException | NoSuchElementException invalid) { view = null; notice = text("failure", "invalid_status"); }
                rebuild();
            });
        }
        if (!pending && ++ticks >= REFRESH_TICKS) read();
    }
    private void rebuild() {
        var result = new ArrayList<Line>();
        if (view != null) for (var entry : view.entries()) {
            var value = Component.empty().append(text("tick", entry.tick()).copy().withStyle(net.minecraft.ChatFormatting.GRAY)).append(" ")
                .append(eventText(entry.code()));
            if (!entry.subject().isEmpty()) value.append(" · ").append(BrowserLiteralDisplay.quote(entry.subject()));
            var wrapped = font.split(value, Math.max(1, imageWidth - 26));
            for (int part = 0; part < wrapped.size(); part++) result.add(new Line(
                new RecentEventScroll.Anchor(entry.sequence(), part), wrapped.get(part), color(entry.kind())));
        }
        lines = List.copyOf(result); scroll.update(lines.stream().map(Line::anchor).toList(), visibleRows());
    }
    static Component eventText(String code) {
        // Reuse device diagnostics; unknown extension codes remain visible rather than becoming "ready".
        var device = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerScreen.code(code);
        if (code.startsWith("wiring_")) {
            String cause = code.substring("wiring_".length());
            var fallback = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerScreen.code(cause);
            device = Component.translatable("create_biotech.biocomputing.events.wiring",
                Component.translatableWithFallback("create_biotech.biocomputing.wiring.code." + cause, "%s", fallback));
        }
        if (code.startsWith("execution_")) {
            String cause = code.substring("execution_".length());
            var fallback = com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerScreen.code(cause);
            var diagnostic = Component.translatableWithFallback("create_biotech.biocomputing.cluster.code.xbus_" + cause, "%s", fallback);
            device = Component.translatable("create_biotech.biocomputing.events.execution",
                Component.translatableWithFallback("create_biotech.biocomputing.events.code.xbus_" + cause, "%s", diagnostic));
        }
        if (code.startsWith("serial_")) {
            boolean receiving = code.startsWith("serial_rx_");
            String cause = code.substring(receiving ? "serial_rx_".length() : "serial_".length());
            device = Component.translatable("create_biotech.biocomputing.events." + (receiving ? "serial_receive" : "serial"),
                Component.translatableWithFallback("create_biotech.biocomputing.serial.code." + cause, "%s", Component.literal(cause)));
        }
        return Component.translatableWithFallback("create_biotech.biocomputing.events.code." + code, "%s", device);
    }
    private static int color(RecentEventLog.Kind kind) {
        return switch (kind) {
            case WAIT -> 0xffffdd88;
            case TRANSFER -> 0xff88ddff;
            case UNAVAILABLE -> 0xffffaa77;
            case RECOVERED -> 0xff99ee99;
            case ERROR -> 0xffff8888;
            case CONTROL -> 0xffbbbbdd;
        };
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= leftPos && x < leftPos + imageWidth && y >= topPos + CONTENT_TOP && y < topPos + imageHeight - 48) {
            scroll.scroll(vertical > 0 ? -3 : vertical < 0 ? 3 : 0); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        draw(graphics, title(), 10, 0xffeeeeee);
        draw(graphics, text("scope"), 54, 0xffaabbcc);
        if (lines.isEmpty()) draw(graphics, view == null ? notice : text("empty"), CONTENT_TOP, 0xffbbbbbb);
        for (int row = 0; row < visibleRows() && scroll.offset() + row < lines.size(); row++) {
            var line = lines.get(scroll.offset() + row);
            graphics.drawString(font, line.text(), leftPos + 8, topPos + CONTENT_TOP + row * LINE_HEIGHT, line.color(), false);
        }
        if (scroll.maximum() > 0) {
            int top = topPos + CONTENT_TOP, height = visibleRows() * LINE_HEIGHT;
            graphics.fill(leftPos + imageWidth - 12, top, leftPos + imageWidth - 9, top + height, 0xff333333);
            int y = top + (int)((long)scroll.offset() * (height - 8) / scroll.maximum());
            graphics.fill(leftPos + imageWidth - 12, y, leftPos + imageWidth - 9, y + 8, 0xffaaccee);
        }
        if (view != null && view.omitted() > 0) draw(graphics, text("omitted", view.omitted()), imageHeight - 48, 0xffffdd88);
        else draw(graphics, notice, imageHeight - 48, 0xffffdd88);
        draw(graphics, text(scroll.following() ? "following" : "browsing"), imageHeight - 19, 0xffaabbcc);
    }
    private void draw(GuiGraphics graphics, Component value, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth - 16), leftPos + 8, topPos + y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void removed() {} // The parent and log screen share this physical menu.
    @Override public void onClose() { back.run(); }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.events." + key, args); }
}
