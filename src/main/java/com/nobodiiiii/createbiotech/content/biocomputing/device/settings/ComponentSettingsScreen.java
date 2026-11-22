package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.EditorValueFormat;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsRequest.Action;

public final class ComponentSettingsScreen extends AbstractContainerScreen<ComponentSettingsMenu> {
    private int page, selected = -1;
    private boolean waiting;
    public ComponentSettingsScreen(ComponentSettingsMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
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
        button(8, imageHeight - 48, half, msg("grant"), () -> send(Action.GRANT, selected)).active =
            !waiting && selected >= 0 && (field("target_status").equals("ready") || field("target_status").equals("target_changed"));
        button(16 + half, imageHeight - 48, half, msg("revoke"), () -> send(Action.REVOKE, -1)).active =
            !waiting && menu.view().field("binding") instanceof DataValue.RecordValue;
        button(8, imageHeight - 24, half, msg("refresh"), () -> send(Action.REFRESH, -1));
        button(16 + half, imageHeight - 24, half, msg("back"), this::onClose);
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
    private static Component msg(String key, Object... args) { return Component.translatable("create_biotech.component_settings." + key, args); }
    private static Component code(String value) {
        if ("invalid_label".equals(value)) return Component.translatable("create_biotech.label_output.code.invalid_label");
        String key = "create_biotech.component_settings.code." + value;
        return Component.translatableWithFallback(Language.getInstance().has(key) ? key : "create_biotech.biocomputing.serial.code." + value, value);
    }
    private static Component describe(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) return msg("closed");
        return Component.literal(text(record.field("terminal")) + " / " + text(record.field("port")) + " · " + text(record.field("face")));
    }
    private static String text(DataValue value) { return value instanceof DataValue.TextValue t ? t.value() : "—"; }
    private String field(String name) { return text(menu.view().field(name)); }
    private Component armDescription(DataValue.RecordValue state) {
        var phase = code(text(state.field("phase")));
        return state.field("action") instanceof DataValue.TextValue
            ? msg("arm_action", code(text(state.field("transfer"))), phase)
            : msg("sequence", field("device"), phase);
    }
    private Component settingLabel() {
        String setting = field("field");
        if ("movement_mode".equals(setting) && "create:cart_assembler".equals(field("device")))
            return Component.translatableWithFallback("create.contraptions.cart_movement_mode", setting);
        if ("roller_mode".equals(setting) && "create:mechanical_roller".equals(field("device")))
            return Component.translatableWithFallback("create.contraptions.roller_mode", setting);
        if ("attachment_range".equals(setting))
            return Component.translatableWithFallback("create.contraptions.chassis.range", setting);
        if ("rotation_direction".equals(setting) && "create:steam_engine".equals(field("device")))
            return Component.translatableWithFallback("create.contraptions.windmill.rotation_direction", setting);
        if ("distribution_mode".equals(setting) && "create:brass_tunnel".equals(field("device")))
            return Component.translatableWithFallback("create.logistics.when_multiple_outputs_available", setting);
        if ("extraction_batch".equals(setting))
            return Component.translatableWithFallback("create.logistics.filter.extracted_amount", setting);
        return code(setting);
    }
    private String clip(Component text) { return font.plainSubstrByWidth(text.getString(), Math.max(1, imageWidth - 23)); }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 24);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, clip(title), 8, 8, 0xffeeeeee, false);
        g.drawString(font, clip(msg("target", field("target"), field("face"), code(field("target_status")))), 8, 29, 0xffeeeeee, false);
        var sequence = menu.view().fields().get("sequence");
        var arm = menu.view().fields().get("arm");
        var deployer = menu.view().fields().get("deployer");
        var value = menu.view().field("value");
        var description = DisplayLinkRows.FIELD.equals(field("field"))
            ? Component.literal(field("device") + " · ")
                .append(Component.translatableWithFallback("create.display_link.display_on_multiline", "Start row:"))
                .append(" " + EditorValueFormat.format(value))
            : sequence instanceof DataValue.RecordValue state
            ? msg("sequence", field("device"), code(text(state.field("phase"))))
            : arm instanceof DataValue.RecordValue state
                ? armDescription(state)
            : deployer instanceof DataValue.RecordValue state
                ? msg("sequence", field("device"), code(text(state.field("phase"))))
                : field("field").equals("floor_names") && value instanceof DataValue.RecordValue names
                    ? msg("floor_names", text(names.field("short")), text(names.field("long")))
                    : msg("state", field("device"), settingLabel(), EditorValueFormat.format(value), code(field("unit")));
        g.drawString(font, clip(description), 8, 42, 0xffeeeeee, false);
        g.drawString(font, clip(msg(arm instanceof DataValue.RecordValue ? "arm_notice" : "notice",
            code(waiting ? "waiting" : menu.code()), code(field("last")), code(field("line")))), 8, 56, 0xffffcf80, false);
        if (menu.view().field("motion") instanceof DataValue.RecordValue motion) {
            boolean reached = new DataValue.BooleanValue(true).equals(motion.field("reached"));
            String status = reached ? "reached" : new DataValue.BooleanValue(true).equals(motion.field("overstressed"))
                ? "overstressed" : new DataValue.BooleanValue(true).equals(motion.field("has_source")) ? "not_reached" : "no_drive";
            g.drawString(font, clip(msg("motion", EditorValueFormat.format(motion.field("actual_rpm")), code(status))), 8, 68, 0xffeeeeee, false);
        } else if (sequence instanceof DataValue.RecordValue state) {
            g.drawString(font, clip(msg("sequence_feedback", code(text(state.field("availability"))),
                code(text(state.field("arrival"))))), 8, 68, 0xffeeeeee, false);
        } else if (arm instanceof DataValue.RecordValue state) {
            g.drawString(font, clip(msg("arm_feedback", EditorValueFormat.format(state.field("input_count")),
                EditorValueFormat.format(state.field("output_count")), code(text(state.field("reason"))))), 8, 68, 0xffeeeeee, false);
        } else if (deployer instanceof DataValue.RecordValue state) {
            g.drawString(font, clip(msg("deployer_feedback", code(text(state.field("mode"))), code(text(state.field("availability"))),
                code(text(state.field("process"))))), 8, 68, 0xffeeeeee, false);
        } else if (menu.view().fields().get("inversion") instanceof DataValue.BooleanValue inverted) {
            g.drawString(font, clip(msg("inversion", code(inverted.value() ? "enabled" : "disabled"))), 8, 68, 0xffeeeeee, false);
        } else if (menu.view().fields().get("door_control") instanceof DataValue.TextValue mode) {
            g.drawString(font, clip(msg("door_policy", msg("door_mode." + mode.value()))), 8, 68, 0xffeeeeee, false);
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float ticks) {
        renderBackground(g, x, y, ticks);
        super.render(g, x, y, ticks);
        if (x >= leftPos + 8 && x < leftPos + imageWidth - 8 && y >= topPos + 41 && y < topPos + 41 + font.lineHeight + 2
            && menu.view().field("arm") instanceof DataValue.RecordValue arm
            && arm.field("action") instanceof DataValue.TextValue id)
            g.renderComponentTooltip(font, List.of(armDescription(arm), msg("arm_action_id", id.value())), x, y);
    }
}
