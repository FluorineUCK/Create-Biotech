package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PeriodicSchedule;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Same authorized menu, explicit frozen-input configuration, and no automatic world reads. */
public final class ComputerPeriodicScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final ComputerExecutionScreen parent;
    private DataValue.RecordValue frozenInputs;
    private Optional<PeriodicSchedule> saved = Optional.empty();
    private String intervalText = "";
    private EditBox interval;
    private CreateTextButton loadInputs;
    private boolean requested, intervalEdited, intervalSeeded;
    private long responseVersion;
    private Component notice = Component.empty();

    public ComputerPeriodicScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ComputerExecutionScreen parent) {
        super(menu, inventory, title);
        this.parent = Objects.requireNonNull(parent);
        frozenInputs = menu.debuggerInputs(); responseVersion = menu.controlResponseVersion();
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(380, height - 12)); super.init();
        setFocused(null); clearWidgets();
        interval = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 34, imageWidth - 16, 18, text("interval")));
        // More than a long's decimal length, so overflow is reported instead of silently truncated.
        interval.setMaxLength(64); interval.setValue(intervalText); interval.setHint(text("interval"));
        interval.setResponder(value -> { intervalText = value; intervalEdited = true; });
        int column = (imageWidth - 20) / 2;
        button(8, 58, column, "save_enable", this::saveEnable);
        button(12 + column, 58, column, "disable", () -> send(ComputerControlSession.Action.DISABLE_PERIOD));
        loadInputs = button(8, 82, column, "load_inputs", () -> saved.ifPresent(schedule -> {
            frozenInputs = schedule.inputs(); menu.rememberDebuggerInputs(frozenInputs); notice = text("inputs_loaded");
        }));
        loadInputs.active = saved.isPresent();
        button(12 + column, 82, column, "clear", () -> send(ComputerControlSession.Action.CLEAR_PERIOD));
        button(8, imageHeight - 27, column, "refresh", () -> send(ComputerControlSession.Action.READ_PERIOD));
        button(12 + column, imageHeight - 27, column, "back", this::onClose);
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos+x, topPos+y, width, 20, text(key), action, true));
    }
    private void saveEnable() {
        try {
            var settings = new PeriodicControlData(Long.parseLong(intervalText.trim()), frozenInputs);
            menu.control(ComputerControlSession.Action.CONFIGURE_PERIOD, settings.encode()); notice = debug("waiting");
        } catch (IllegalArgumentException invalid) { notice = debug("invalid", invalid.getMessage()); }
    }
    private void send(ComputerControlSession.Action action) {
        menu.control(action, new DataValue.RecordValue(Map.of())); notice = debug("waiting");
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; send(ComputerControlSession.Action.READ_PERIOD); }
        if (responseVersion == menu.controlResponseVersion()) return;
        responseVersion = menu.controlResponseVersion();
        menu.controlResponse().ifPresent(reply -> notice = debug("result." + reply.code(), reply.detail()));
        menu.periodicStatus().ifPresent(status -> {
            try {
                var value = status.field("periodic");
                if (value == DataValue.NullValue.INSTANCE) saved = Optional.empty();
                else if (value instanceof DataValue.RecordValue record
                    && record.fields().get("interval_ticks") instanceof DataValue.IntegerValue ticks
                    && record.fields().get("remaining_ticks") instanceof DataValue.IntegerValue remaining
                    && record.fields().get("enabled") instanceof DataValue.BooleanValue enabled
                    && record.fields().get("inputs") instanceof DataValue.RecordValue inputs)
                    saved = Optional.of(new PeriodicSchedule(ticks.value(), remaining.value(), enabled.value(), inputs));
                else throw new IllegalArgumentException("Incomplete periodic status");
                if (!intervalSeeded) {
                    if (!intervalEdited) saved.ifPresent(schedule -> interval.setValue(Long.toString(schedule.intervalTicks())));
                    intervalSeeded = true;
                }
                loadInputs.active = saved.isPresent();
            } catch (IllegalArgumentException invalid) { notice = debug("invalid", invalid.getMessage()); }
        });
    }
    @Override public void onClose() { parent.restoreLocalInputs(); minecraft.setScreen(parent); }
    @Override public void removed() { }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos+4, topPos+4, imageWidth-8, 22);
        line(graphics, text("title"), 10, 0xffeeeeee);
        line(graphics, saved.map(schedule -> text(schedule.enabled() ? "enabled" : "disabled", schedule.intervalTicks(), schedule.remainingTicks()))
            .orElseGet(() -> text("not_configured")), 111, 0xffdddddd);
        line(graphics, text("local_inputs", frozenInputs.fields().size(), frozenInputs.storageUnits()), 125, 0xffaaccee);
        line(graphics, text("stored_units", saved.map(schedule -> schedule.inputs().storageUnits()).orElse(0L)), 139, 0xffaaccee);
        String help = text("frozen_hint").getString();
        for (int y=155; !help.isEmpty() && y<imageHeight-50; y+=12) {
            String part = font.plainSubstrByWidth(help, imageWidth-16); if (part.isEmpty()) break;
            graphics.drawString(font, part, leftPos+8, topPos+y, 0xffbbbbbb, false); help=help.substring(part.length());
        }
        line(graphics, notice, imageHeight-42, 0xffffddaa);
    }
    private void line(GuiGraphics graphics, Component value, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), imageWidth-16), leftPos+8, topPos+y, color, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.periodic."+key, args); }
    private static Component debug(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.debug."+key, args); }
}
