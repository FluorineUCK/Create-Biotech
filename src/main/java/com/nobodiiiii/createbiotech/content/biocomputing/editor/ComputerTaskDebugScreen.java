package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local task directory and explicitly requested frozen details; never claims a result. */
final class ComputerTaskDebugScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private static final int ROW_TOP = 36, ROW_HEIGHT = 22, REPLY_TIMEOUT = 80;
    private final Inventory inventory;
    private final Consumer<Screen> navigate;
    private final Runnable close;
    private BiConsumer<ComputerControlSession.Action, DataValue.RecordValue> sender;
    private long seenReply, view;
    private boolean requested, reading, details;
    private int waited, page, pages = 1, listingSize, requestedSize;
    private List<DataValue.RecordValue> rows = List.of();
    private Component notice = text("loading");

    ComputerTaskDebugScreen(ComputerProgramMenu menu, Inventory inventory, Consumer<Screen> navigate, Runnable close) {
        super(menu, inventory, text("task_debug"));
        this.inventory = inventory; this.navigate = navigate; this.close = close;
        this.sender = menu::control; this.seenReply = menu.controlResponseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(540, width - 12), Math.min(286, height - 12));
        super.init(); clearWidgets(); setFocused(null);
        for (int i = 0; i < Math.min(rows.size(), rowLimit()); i++) {
            int row = i;
            button(8, ROW_TOP + i * ROW_HEIGHT, imageWidth - 16, caption(rows.get(i)), () -> select(row))
                .active = !reading && view > 0;
        }
        int bottom = imageHeight - 27;
        var previous = button(8, bottom, 28, Component.literal("<"), () -> list(page - 1));
        var next = button(40, bottom, 28, Component.literal(">"), () -> list(page + 1));
        previous.visible = next.visible = pages > 1;
        previous.active = !reading && page > 0;
        next.active = !reading && page + 1 < pages;
        var back = text("task_debug_back_debugger");
        int backWidth = Math.max(68, Math.min(font.width(back) + 12, imageWidth - 108));
        int refreshWidth = Math.max(1, Math.min(92, imageWidth - backWidth - 88));
        button(76, bottom, refreshWidth, text("refresh"), () -> list(page)).active = !reading;
        button(imageWidth - backWidth - 8, bottom, backWidth, back, this::onClose);
    }
    private int rowLimit() { return Math.clamp((imageHeight - 77) / ROW_HEIGHT, 1, TaskDebugView.PAGE_SIZE); }
    private void list(int page) {
        if (reading) return;
        requested = true; requestedSize = rowLimit(); view = 0;
        send(TaskDebugView.pageRequest(page, requestedSize), false);
    }
    private void select(int row) {
        if (view > 0 && row >= 0 && row < rows.size()) send(TaskDebugView.taskRequest(view, row), true);
    }
    private void send(DataValue.RecordValue input, boolean details) {
        if (reading) return;
        reading = true; this.details = details; waited = 0; notice = text("waiting");
        sender.accept(ComputerControlSession.Action.READ_TASK_DEBUG, input);
        init();
    }
    private void invalidView(Component message) { view = 0; notice = message; init(); }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { list(0); return; }
        if (!reading) {
            if (view > 0 && listingSize != rowLimit()) list(0);
            return;
        }
        if (menu.controlResponseVersion() == seenReply) {
            if (++waited >= REPLY_TIMEOUT) { reading = false; invalidView(text("task_debug_timeout")); }
            return;
        }
        seenReply = menu.controlResponseVersion();
        if (!menu.taskDebugReply()) { reading = false; invalidView(text("task_debug_live_stale")); return; }
        reading = false;
        var reply = menu.controlResponse().orElseThrow();
        if (!reply.code().equals("ok")) { invalidView(text("result." + reply.code(), reply.detail())); return; }
        try {
            var result = menu.status().orElseThrow(() -> new IllegalArgumentException("Missing task observation"));
            if (details) {
                var task = record(result.field("debug_task")); record(task.field("locals"));
                notice = text("task_debug_frozen"); init();
                navigate.accept(new ComputerValueTreeScreen<>(menu, inventory, caption(task), this,
                    ScratchDebugSource.taskFields(menu, reply.revision(), task), null,
                    text("task_debug"), text("task_debug_frozen"), () -> navigate.accept(this)));
                return;
            }
            var directory = record(result.field("debug_tasks"));
            int receivedPage = integer(directory.field("page")), receivedPages = integer(directory.field("pages"));
            if (receivedPages < 1 || receivedPage >= receivedPages || !(directory.field("rows") instanceof DataValue.ListValue list)
                    || list.values().size() > requestedSize) throw new IllegalArgumentException("Invalid task directory");
            var selected = list.values().stream().map(ComputerTaskDebugScreen::record).toList();
            selected.forEach(ComputerTaskDebugScreen::caption);
            rows = selected; page = receivedPage; pages = receivedPages; listingSize = requestedSize; view = reply.intent();
            if (!rows.isEmpty()) menu.requestInstalledSource(reply.revision());
            notice = text(rows.isEmpty() ? "task_debug_empty" : "task_debug_page", page + 1, pages);
            init();
        } catch (IllegalArgumentException invalid) {
            invalidView(text("result.invalid", String.valueOf(invalid.getMessage())));
        }
    }
    private static DataValue.RecordValue record(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record;
        throw new IllegalArgumentException("Invalid task observation");
    }
    private static int integer(DataValue value) {
        if (value instanceof DataValue.IntegerValue n && n.value() >= 0 && n.value() <= Integer.MAX_VALUE) return (int)n.value();
        throw new IllegalArgumentException("Invalid task page");
    }
    private static Component caption(DataValue.RecordValue row) {
        if (!(row.field("procedure") instanceof DataValue.TextValue procedure) || !(row.field("revision") instanceof DataValue.IntegerValue revision)
                || !(row.field("phase") instanceof DataValue.TextValue phase) || !(row.field("paused") instanceof DataValue.BooleanValue paused))
            throw new IllegalArgumentException("Invalid task row");
        var state = text("state." + phase.value().toLowerCase(Locale.ROOT));
        if (paused.value() && !Set.of("COMPLETE", "FAILED", "CANCELLED").contains(phase.value())) state = text("paused_state", state);
        return text("task_debug_row", procedure.value(), revision.value(), state);
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        var clipped = font.plainSubstrByWidth(label.getString(), Math.max(0, width - 8));
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, Component.literal(clipped), action, true));
        button.setTooltip(Tooltip.create(label)); return button;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        g.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 20), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        int noticeY = topPos + imageHeight - 43;
        g.drawString(font, font.plainSubstrByWidth(notice.getString(), imageWidth - 16), leftPos + 8, noticeY, 0xffddcba9, false);
        if (mx >= leftPos + 8 && mx < leftPos + imageWidth - 8 && my >= noticeY && my < noticeY + 11)
            g.renderTooltip(font, font.split(notice, imageWidth - 16), mx, my);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    @Override public void onClose() { close.run(); }
    @Override public void removed() {}
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.debug." + key, args); }
}
