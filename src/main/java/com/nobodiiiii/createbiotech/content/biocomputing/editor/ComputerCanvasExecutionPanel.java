package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Explicit installed-program snapshots beside a draft; never evaluates nodes or reads the world. */
public final class ComputerCanvasExecutionPanel {
    private static final List<ComputerControlSession.Action> ACTIONS = List.of(
        ComputerControlSession.Action.REFRESH, ComputerControlSession.Action.RUN, ComputerControlSession.Action.START_PAUSED,
        ComputerControlSession.Action.PAUSE, ComputerControlSession.Action.RESUME, ComputerControlSession.Action.STEP);
    private static final List<String> FIRST = List.of("phase", "paused", "entry", "work", "arena", "diagnostic",
        "installation_diagnostic", "pending", "output", "memory");
    private static final int CONTENT_TOP = 84;
    private final Font font;
    private final Component heading;
    private final Consumer<ComputerControlSession.Action> command;
    private final Runnable editInputs;
    private final List<CreateTextButton> widgets = new ArrayList<>();
    private Optional<DataValue.RecordValue> snapshot = Optional.empty();
    private List<String> lines = List.of();
    private Component notice = text("loading");
    private int x, y, width, height, page;
    private long revision;
    private boolean flow;
    private CreateTextButton previous, next;

    public ComputerCanvasExecutionPanel(Font font, Component title, Consumer<ComputerControlSession.Action> command,
        Runnable editInputs) {
        this.font = Objects.requireNonNull(font); this.heading = text("installed_program", title);
        this.command = Objects.requireNonNull(command); this.editInputs = Objects.requireNonNull(editInputs);
    }

    public void layout(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height; widgets.clear();
        int cell = (width - 12) / 3;
        for (int i = 0; i < ACTIONS.size(); i++) {
            var action = ACTIONS.get(i);
            add(4 + i % 3 * (cell + 2), 20 + i / 3 * 22, cell,
                text(action.name().toLowerCase(Locale.ROOT)), () -> request(action)).active = allowed(action);
        }
        previous = add(4, height - 24, 24, Component.literal("<"), () -> { page--; updatePages(); });
        next = add(30, height - 24, 24, Component.literal(">"), () -> { page++; updatePages(); });
        add(104, height - 24, width - 108,
            Component.translatable("create_biotech.biocomputing.editor.edit_values"), editInputs);
        rebuildLines();
    }

    private CreateTextButton add(int dx, int dy, int w, Component label, Runnable action) {
        var button = new CreateTextButton(font, x + dx, y + dy, w, 20, label, action, true);
        button.setTooltip(Tooltip.create(label)); widgets.add(button); return button;
    }
    public List<CreateTextButton> widgets() { return List.copyOf(widgets); }
    public boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
    public void invalidate() {
        snapshot = Optional.empty(); lines = List.of(); page = 0; notice = text("result.stale", ""); updatePages();
    }
    public ComputerValueDisplay.Page page() {
        return ComputerValueDisplay.page(lines, page, Math.max(1, (height - CONTENT_TOP - 30) / 12));
    }
    public void request(ComputerControlSession.Action action) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported canvas control");
        if (!allowed(action)) return;
        snapshot = Optional.empty(); lines = List.of(); page = 0; notice = text("waiting"); updatePages();
        command.accept(action);
    }
    /** The menu has already checked nonce/intent and decoded the complete bounded status. */
    public void accept(ProgramStatusResponse response, Optional<DataValue.RecordValue> status) {
        notice = text("result." + response.code(), response.detail()); revision = response.revision();
        snapshot = status.filter(value -> value.fields().get("phase") instanceof DataValue.TextValue);
        snapshot.ifPresent(value -> flow = new DataValue.TextValue("flow").equals(value.fields().get("mode")));
        for (int i = 0; i < ACTIONS.size() && i < widgets.size(); i++) widgets.get(i).active = allowed(ACTIONS.get(i));
        rebuildLines();
    }
    private boolean allowed(ComputerControlSession.Action action) {
        return !flow || action != ComputerControlSession.Action.RUN && action != ComputerControlSession.Action.START_PAUSED;
    }
    private void rebuildLines() {
        var result = new ArrayList<String>();
        snapshot.ifPresent(value -> {
            wrap(result, "revision: " + revision);
            var ordered = new LinkedHashSet<>(FIRST); ordered.addAll(value.fields().keySet());
            for (var key : ordered) {
                var field = value.fields().get(key);
                if (field != null) wrap(result, key + ": " + ComputerValueDisplay.lines(field, Integer.MAX_VALUE).getFirst());
            }
        });
        lines = List.copyOf(result); updatePages();
    }
    private void wrap(List<String> result, String text) {
        while (!text.isEmpty()) {
            int length = font.plainSubstrByWidth(text, Math.max(1, width - 12)).length();
            if (length > 0 && length < text.length() && Character.isHighSurrogate(text.charAt(length - 1))
                && Character.isLowSurrogate(text.charAt(length))) length--;
            if (length == 0) length = Character.charCount(text.codePointAt(0));
            result.add(text.substring(0, length)); text = text.substring(length);
        }
    }
    private void updatePages() {
        var current = page(); page = current.index();
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = page + 1 < current.count();
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, x, y, width, height);
        graphics.fill(x, y, x + 1, y + height, 0xffaaccee);
        graphics.drawString(font, font.plainSubstrByWidth(heading.getString(), width - 12), x + 6, y + 5, 0xffaaccee, false);
        graphics.drawString(font, font.plainSubstrByWidth(notice.getString(), width - 12), x + 6, y + 68, 0xffffddaa, false);
        var current = page();
        for (int row = 0; row < current.lines().size(); row++)
            graphics.drawString(font, current.lines().get(row), x + 6, y + CONTENT_TOP + row * 12, 0xffdddddd, false);
        graphics.drawString(font, font.plainSubstrByWidth((current.index() + 1) + "/" + current.count(), 44),
            x + 58, y + height - 18, 0xffaaccee, false);
        if (mouseX >= x && mouseX < x + width) {
            if (mouseY >= y && mouseY < y + 18) tooltip(graphics, heading, mouseX, mouseY);
            else if (mouseY >= y + 66 && mouseY < y + CONTENT_TOP) tooltip(graphics, notice, mouseX, mouseY);
        }
    }
    private void tooltip(GuiGraphics graphics, Component value, int mouseX, int mouseY) {
        graphics.renderTooltip(font, font.split(value, width), mouseX, mouseY);
    }
    private static Component text(String key, Object... arguments) {
        return Component.translatable("create_biotech.biocomputing.debug." + key, arguments);
    }
}
