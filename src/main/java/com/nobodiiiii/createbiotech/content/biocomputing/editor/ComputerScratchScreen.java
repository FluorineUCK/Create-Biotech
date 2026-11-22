package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Registered draft owner. Programming opens Scratch; only the device wiring tools use graph canvases. */
public final class ComputerScratchScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> implements ProgramSourceEditor {
    private final Inventory editorInventory;
    private final Consumer<ProgramEditorRequest> sourceSender;
    private Consumer<Screen> navigator = screen -> minecraft.setScreen(screen);
    private ProgramWorkspace workspace;
    private Consumer<ProgramDraftRequest> draftSender = com.nobodiiiii.createbiotech.network.CBPackets::sendToServer;
    private ProgramDocument openedBaseline, savedBaseline, savingDocument;
    private long seenDraftResponse;
    private Component saveError;
    private boolean draftModified;
    private CreateTextButton saveButton;
    private boolean requested;
    private long responseVersion;
    private long recoveredSourceVersion;
    private Component notice = text("loading");

    public ComputerScratchScreen(ComputerProgramMenu menu, Inventory inventory, Component title) {
        this(menu, inventory, title, com.nobodiiiii.createbiotech.network.CBPackets::sendToServer);
    }
    ComputerScratchScreen(ComputerProgramMenu menu, Inventory inventory, Component title, Consumer<ProgramEditorRequest> sourceSender) {
        super(menu, inventory, title); editorInventory = inventory; this.sourceSender = Objects.requireNonNull(sourceSender);
    }

    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(260, height - 12));
        super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.QUICK));
        int column = (imageWidth - 20) / 2;
        button(8, 32, column, text("flow"), this::openBlocks).active = workspace != null;
        button(12 + column, 32, column, text("program_package"), () -> openDocuments(() -> navigator.accept(this)))
            .active = workspace != null;
        button(8, 58, column, text("debugger"), this::openDebugger).active = menu.response().isPresent();
        button(12 + column, 58, column, Component.translatable("create_biotech.biocomputing.terminal.edit"), () -> navigator.accept(
            new ComputerTerminalEditorScreen(menu, editorInventory, title, this, workspace, this::changed, () -> navigator.accept(this))))
            .active = workspace != null;
        saveButton = button(8, 84, column, text("save_draft"), this::saveDraft);
        saveButton.active = workspace != null && menu.canSaveDraft();
        button(12 + column, 84, column, text("media_library"), () -> navigator.accept(
            new com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.ComputerMediaScreen(menu, editorInventory,
                () -> navigator.accept(this))));
        button(imageWidth - 84, imageHeight - 27, 76, text("close"), this::onClose);
    }

    private void openBlocks() {
        if (workspace == null) return;
        navigator.accept(new ComputerFlowScreen(menu, editorInventory, title, workspace, this::importDocument, this::changed,
            () -> navigator.accept(this), (section, resume) -> openDocuments(resume, this::openBlocks, section), this::openDebugger)
            .withDraftSaving(this::saveDraft, this::draftStatus, menu::canSaveDraft));
    }
    private void openDocuments(Runnable close) { openDocuments(close, close, Optional.empty()); }
    private void openDocuments(Runnable resume, Runnable reopen, Optional<FlowBlockCatalog.SensingSection> section) {
        if (workspace == null) return;
        // Preview must keep the open editor and its undo history. An explicit import replaces the owning workspace.
        var before = workspace;
        var page = new ComputerProgramDocumentScreen(menu, editorInventory, title,
            () -> workspace.snapshot(), this::importDocument, () -> (workspace == before ? resume : reopen).run());
        section.ifPresent(page::sensingExamples);
        navigator.accept(page);
    }
    private void openDebugger() { navigator.accept(new ComputerExecutionScreen(menu, editorInventory, title, this)); }

    private void saveDraft() {
        if (workspace == null) return;
        try {
            var candidate = workspace.snapshot();
            menu.saveDraft(candidate, draftSender); savingDocument = candidate; saveError = null;
        } catch (RuntimeException invalid) { saveError = text("draft_invalid", String.valueOf(invalid.getMessage())); }
    }
    private void observeDraftSave() {
        if (seenDraftResponse == menu.draftResponseVersion()) return;
        seenDraftResponse = menu.draftResponseVersion();
        menu.draftResponse().ifPresent(reply -> {
            if (reply.code().equals("saved") && savingDocument != null) {
                savedBaseline = savingDocument; saveError = null; draftModified = unsavedChanges();
            } else if (reply.code().equals("stale")) saveError = text("draft_stale");
            else if (reply.code().equals("invalid")) saveError = text("draft_invalid", reply.detail());
            savingDocument = null;
        });
    }
    private boolean unsavedChanges() {
        if (workspace == null) return false;
        try { return !workspace.snapshot().equals(savedBaseline == null ? openedBaseline : savedBaseline); }
        catch (RuntimeException invalid) { return true; }
    }
    private Component draftStatus() {
        observeDraftSave();
        if (menu.draftPending()) return text("draft_saving");
        if (saveError != null) return saveError;
        return text(draftModified ? "draft_modified" : savedBaseline == null ? "draft_from_installed" : "draft_saved");
    }
    @Override public void onClose() {
        observeDraftSave();
        if (unsavedChanges() || menu.draftPending()) {
            navigator.accept(new net.minecraft.client.gui.screens.ConfirmScreen(discard -> {
                if (discard) super.onClose(); else navigator.accept(this);
            }, text("draft_close_title"), text("draft_close_message"), text("draft_discard"), text("draft_keep")));
        } else super.onClose();
    }

    /** Explicit import replaces only this draft. It never installs, converts or discards legacy effects. */
    void importDocument(ProgramDocument document) {
        if (workspace == null || menu.response().isEmpty()) throw new IllegalStateException("Program draft is unavailable");
        var candidate = new ProgramWorkspace(document); candidate.snapshot();
        workspace = candidate; changed(); init();
    }
    private void changed() {
        if (workspace == null) return;
        draftModified = unsavedChanges(); // Recompute on edits/ACKs, not by rebuilding a whole document every render frame.
        notice = text(workspace.flow().isPresent() ? "scratch_hint" : "legacy_scratch_pending");
        try {
            var response = menu.response().orElseThrow();
            workspace.snapshot().validate(response.hardware(), response.pins());
        } catch (IllegalArgumentException | IllegalStateException | ComputationException invalid) {
            notice = notice.copy().append("\n").append(text("invalid_parameter", String.valueOf(invalid.getMessage())));
        }
    }
    @Override public Component valuePathActionTitle() {
        return Component.translatable("create_biotech.biocomputing.tree.generate_block");
    }
    @Override public void importValuePath(DataValue.RecordValue frozenInputs, ValuePath path) {
        if (workspace == null || workspace.flow().isEmpty()) throw new IllegalStateException(text("legacy_scratch_pending").getString());
        workspace.createPathBlock(frozenInputs, path);
        changed(); notice = text("path_block_generated"); init();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (!requested) { requested = true; menu.requestInitial(); menu.requestSavedDraft(draftSender); }
        observeDraftSave();
        if (saveButton != null) saveButton.active = workspace != null && menu.canSaveDraft();
        if (responseVersion != menu.responseVersion()) {
            responseVersion = menu.responseVersion();
            menu.response().ifPresent(reply -> {
                if (workspace == null && reply.code().equals("ready")) {
                    if (menu.draftLoaded()) initializeDraft(reply);
                }
                else {
                    notice = text("result." + reply.code());
                    if (!reply.detail().isEmpty()) notice = notice.copy().append(": " + reply.detail());
                    init();
                }
            });
        }
        // The two authorized reads may arrive in either order. Never replace an existing edited workspace.
        if (workspace == null && menu.draftLoaded())
            menu.response().filter(reply -> reply.code().equals("ready")).ifPresent(this::initializeDraft);
        // A reset can recover an initially unavailable source while a child screen owns this menu.
        // Hydrate only a missing draft; source-only reads must not overwrite edits or install feedback.
        if (workspace == null && menu.response().filter(reply -> reply.code().equals("unavailable")).isPresent()) {
            menu.controlResponse().filter(reply -> reply.code().equals("ok")).ifPresent(control -> {
                // RESET may arrive after the debugger closes. An unavailable REFRESH is also "ok",
                // so only a usable runtime may initiate a read (and consume this revision's attempt).
                if (menu.status().filter(status -> new DataValue.BooleanValue(true).equals(status.fields().get("available"))).isPresent())
                    menu.requestInstalledSource(control.revision(), sourceSender);
                if (recoveredSourceVersion != menu.installedSourceVersion())
                    menu.installedSource(control.revision()).ifPresent(reply -> {
                        recoveredSourceVersion = menu.installedSourceVersion();
                        initializeDraft(reply);
                    });
            });
        }
    }
    private void initializeDraft(ProgramEditorResponse reply) {
        try {
            byte[] saved = menu.draftResponse().filter(value -> value.code().equals("ready"))
                .map(ProgramDraftResponse::document).orElseGet(() -> new byte[0]);
            byte[] source = saved.length == 0 ? reply.archive() : saved;
            var candidate = source.length == 0 ? new ProgramWorkspace()
                : new ProgramWorkspace(ProgramDocumentWire.decode(source, reply.archiveLimits()));
            if (source.length == 0) candidate.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
            workspace = candidate; openedBaseline = candidate.snapshot();
            savedBaseline = saved.length == 0 ? null : openedBaseline; changed();
        } catch (IllegalArgumentException invalid) { notice = text("invalid_parameter", String.valueOf(invalid.getMessage())); }
        init();
        if (workspace != null && workspace.flow().isPresent()) openBlocks();
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        var button = addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
        button.setTooltip(Tooltip.create(label)); return button;
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 90), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        graphics.enableScissor(leftPos + 8, topPos + 110, leftPos + imageWidth - 8, topPos + imageHeight - 36);
        var saveStatus = draftStatus();
        graphics.drawWordWrap(font, saveStatus, leftPos + 8, topPos + 110, imageWidth - 16, 0xfff1dca7);
        int statusHeight = font.split(saveStatus, imageWidth - 16).size() * font.lineHeight;
        graphics.drawWordWrap(font, notice, leftPos + 8, topPos + 118 + statusHeight, imageWidth - 16, 0xffcccccc);
        graphics.disableScissor();
    }
    @Override protected void renderLabels(GuiGraphics graphics, int x, int y) {}
    @Override public void removed() {} // Child tools share the same authorized menu session.
    private static Component text(String key, Object... args) { return Component.translatable("create_biotech.biocomputing.editor." + key, args); }
}
