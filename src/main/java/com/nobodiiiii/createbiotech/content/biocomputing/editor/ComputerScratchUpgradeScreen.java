package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Explicit version choice for one call; opening or cancelling changes nothing. */
final class ComputerScratchUpgradeScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final List<FlowProgram.Procedure> choices;
    private final Function<FlowProgram.Procedure, Optional<Map<String, DataValue>>> defaults;
    private final Consumer<FlowProgram.Procedure> apply;
    private final Runnable close;
    private Component notice = text("procedure_upgrade_hint");
    private int page;

    ComputerScratchUpgradeScreen(ComputerProgramMenu menu, Inventory inventory, FlowProgram.Procedure current,
            List<FlowProgram.Procedure> choices, Function<FlowProgram.Procedure, Optional<Map<String, DataValue>>> defaults,
            Consumer<FlowProgram.Procedure> apply, Runnable close) {
        super(menu, inventory, text("procedure_upgrade_title", current.key().id(), current.key().revision()));
        this.choices = List.copyOf(choices);
        this.defaults = defaults;
        this.apply = apply;
        this.close = close;
    }

    @Override protected void init() {
        setWindowSize(Math.min(490, width - 12), Math.min(250, height - 12));
        super.init(); clearWidgets(); setFocused(null);
        int rows = Math.max(1, (imageHeight - 94) / 22);
        int pages = Math.max(1, Math.ceilDiv(choices.size(), rows));
        page = Math.clamp(page, 0, pages - 1);
        int start = page * rows;
        for (int i = start; i < Math.min(start + rows, choices.size()); i++) {
            var next = choices.get(i);
            var added = defaults.apply(next);
            var label = text("procedure_upgrade_revision", next.key().revision());
            var row = button(8, 36 + 22 * (i - start), imageWidth - 16, label, () -> {
                try { apply.accept(next); }
                catch (RuntimeException invalid) { notice = text("invalid", String.valueOf(invalid.getMessage())); }
            });
            row.active = added.isPresent();
            row.setTooltip(Tooltip.create(added.map(defaults -> defaults.isEmpty() ? text("procedure_upgrade_same")
                : text("procedure_upgrade_added", String.join(", ", defaults.keySet()))).orElseGet(() -> text("procedure_upgrade_incompatible"))));
        }
        int y = imageHeight - 28;
        if (pages > 1) {
            button(8, y, 26, Component.literal("<"), () -> { page--; init(); }).active = page > 0;
            button(38, y, 26, Component.literal(">"), () -> { page++; init(); }).active = page + 1 < pages;
        }
        button(imageWidth - 88, y, 80, text("cancel"), this::onClose);
    }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        g.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 20), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        g.drawString(font, font.plainSubstrByWidth(notice.getString(), imageWidth - 16), leftPos + 8, topPos + imageHeight - 48, 0xffffddaa, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}
    @Override public void onClose() { close.run(); }
    @Override public void removed() {}
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args);
    }
}
