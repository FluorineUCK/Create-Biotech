package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Explicit local package exchange. Import changes the draft only; no network install or execution. */
public final class ComputerSubgraphScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    static final int MAX_TEXT = ((ProgramEditorWire.MAX_BYTES + 2) / 3) * 4;
    private final ProgramWorkspace workspace;
    private final ModuleDefinition.Key selected;
    private final Runnable applied, close;
    private Supplier<String> clipboardRead = () -> minecraft.keyboardHandler.getClipboard();
    private Consumer<String> clipboardWrite = value -> minecraft.keyboardHandler.setClipboard(value);
    private String payloadText = "";
    private EditBox payload;
    private Component notice = text("subgraph_hint");

    public ComputerSubgraphScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
            ProgramWorkspace workspace, ModuleDefinition.Key selected, Runnable applied, Runnable close) {
        super(menu, inventory, title);
        this.workspace = Objects.requireNonNull(workspace); this.selected = selected;
        this.applied = Objects.requireNonNull(applied); this.close = Objects.requireNonNull(close);
    }
    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(260, height - 12)); super.init(); clearWidgets(); setFocused(null);
        int third = (imageWidth - 24) / 3;
        button(8, 30, third, "export_subgraph", () -> act(() -> {
            var document = workspace.exportSubgraph(Objects.requireNonNull(selected), limits());
            payloadText = Base64.getEncoder().encodeToString(SubgraphDocumentWire.encode(document, limits()));
            notice = text("subgraph_exported", document.root().id(), document.root().revision(), document.modules().size());
        })).active = selected != null && menu.response().isPresent();
        button(12 + third, 30, third, "copy_package", () -> act(() -> clipboardWrite.accept(payloadText))).active = !payloadText.isEmpty();
        button(16 + third * 2, 30, third, "paste_package", () -> act(() -> {
            var copied = Objects.requireNonNull(clipboardRead.get()); checkLength(copied); payloadText = copied;
            notice = text("subgraph_hint");
        }));
        payload = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 62, imageWidth - 16, 18, text("subgraph_package")));
        // Keep one overflow character so direct text paste cannot turn an oversized value into a valid prefix.
        payload.setMaxLength(MAX_TEXT + 1); payload.setValue(payloadText); payload.setResponder(value -> payloadText = value);
        int half = (imageWidth - 20) / 2;
        button(8, imageHeight - 27, half, "import_subgraph", () -> act(() -> {
            checkLength(payloadText);
            var document = SubgraphDocumentWire.decode(Base64.getDecoder().decode(payloadText), limits());
            workspace.importSubgraph(document, limits()); applied.run();
            notice = text("subgraph_imported", document.root().id(), document.root().revision(), document.modules().size());
        })).active = menu.response().isPresent();
        button(12 + half, imageHeight - 27, half, "back", this::onClose);
    }
    private ArchiveLimits limits() { return menu.response().orElseThrow(() -> new IllegalStateException("Editor limits unavailable")).archiveLimits(); }
    private static void checkLength(String value) {
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException("Subgraph package text exceeds the editor transport limit");
    }
    private void act(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | ComputationException failure) {
            notice = text("invalid_parameter", String.valueOf(failure.getMessage()));
        }
        init();
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, text(key), action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, text("subgraph_package"), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        graphics.drawWordWrap(font, notice, leftPos + 8, topPos + 92, imageWidth - 16, 0xffcccccc);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { close.run(); }
    @Override public void removed() {}
    private static Component text(String key, Object... arguments) { return Component.translatable("create_biotech.biocomputing.editor." + key, arguments); }
}
