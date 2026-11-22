package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A paged list of draft locations. Selection only pans the editor. */
final class ComputerScratchReferencesScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private final List<ScratchProcedureReferences.Reference> references;
    private final Consumer<ScratchProcedureReferences.Reference> open;
    private final Runnable close;
    private int page;

    ComputerScratchReferencesScreen(ComputerProgramMenu menu, Inventory inventory, ModuleDefinition.Key key,
            List<ScratchProcedureReferences.Reference> references, Consumer<ScratchProcedureReferences.Reference> open, Runnable close) {
        super(menu, inventory, text("procedure_references_title", key.id(), key.revision()));
        this.references = List.copyOf(references);
        this.open = open;
        this.close = close;
    }

    @Override protected void init() {
        setWindowSize(Math.min(490, width - 12), Math.min(280, height - 12));
        super.init(); clearWidgets(); setFocused(null);
        int count = Math.max(1, (imageHeight - 76) / 22);
        int pages = Math.max(1, Math.ceilDiv(references.size(), count));
        page = Math.clamp(page, 0, pages - 1);
        int start = page * count, end = Math.min(start + count, references.size());
        for (int i = start; i < end; i++) {
            var ref = references.get(i);
            var caption = text("procedure_reference_" + ref.kind().name().toLowerCase(Locale.ROOT),
                ref.source(), ref.node() == null ? "" : ref.node());
            button(8, 36 + 22 * (i - start), imageWidth - 16, caption, () -> open.accept(ref)).setTooltip(Tooltip.create(caption));
        }
        int y = imageHeight - 28;
        if (pages > 1) {
            button(8, y, 26, Component.literal("<"), () -> { page--; init(); }).active = page > 0;
            button(38, y, 26, Component.literal(">"), () -> { page++; init(); }).active = page + 1 < pages;
        }
        button(imageWidth - 88, y, 80, text("back"), this::onClose);
    }

    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 20), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        if (references.isEmpty()) graphics.drawString(font, text("procedure_references_empty"), leftPos + 8, topPos + 42, 0xffdddddd, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { close.run(); }
    @Override public void removed() {}
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args);
    }
}
