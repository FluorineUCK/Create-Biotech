package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualButton;
import com.nobodiiiii.createbiotech.content.biocomputing.manual.ManualFile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.ComputerMediaScreen;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.*;
import java.io.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/** Whole-document exchange, distinct from merging a reusable subgraph package. Never installs a program. */
public final class ComputerProgramDocumentScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    static final int MAX_TEXT = ((ProgramEditorWire.MAX_BYTES + 2) / 3) * 4;
    private final Supplier<ProgramDocument> source;
    private final Consumer<ProgramDocument> apply;
    private final Runnable close;
    private final Inventory editorInventory;
    private Supplier<String> clipboardRead = () -> minecraft.keyboardHandler.getClipboard();
    private Consumer<String> clipboardWrite = value -> minecraft.keyboardHandler.setClipboard(value);
    private String payloadText = "";
    private EditBox payload;
    private Component notice = text("program_hint");
    private boolean examplesOpen, examplesPending;
    private Optional<FlowBlockCatalog.SensingSection> sensingFilter = Optional.empty();
    private boolean newDraftConfirmation;
    private Function<ResourceLocation, InputStream> exampleResourceOpener = id -> {
        try { return minecraft.getResourceManager().open(id); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    };
    private List<ProgramExampleCatalog.Example> examples = List.of();
    private ProgramExampleCatalog.Example selectedExample;
    private ProgramDocument preview;
    private String exampleQuery = "";
    private final List<CreateTextButton> exampleWidgets = new ArrayList<>();
    private int examplePage, descriptionPage;
    private List<FormattedCharSequence> descriptionLines = List.of();
    private int descriptionX, descriptionWidth, linesPerPage;

    public ComputerProgramDocumentScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
            Supplier<ProgramDocument> source, Consumer<ProgramDocument> apply, Runnable close) {
        super(menu, inventory, title);
        this.source = Objects.requireNonNull(source); this.apply = Objects.requireNonNull(apply);
        this.close = Objects.requireNonNull(close);
        editorInventory = inventory;
    }
    void sensingExamples(FlowBlockCatalog.SensingSection section) {
        sensingFilter = Optional.of(section);
        examplesOpen = examplesPending = true;
    }
    @Override protected void init() {
        if (examplesPending) { examplesPending=false; readExamples(); }
        setWindowSize(Math.min(580, width - 12), Math.min(examplesOpen ? 340 : 260, height - 12)); super.init(); clearWidgets(); setFocused(null);
        addRenderableWidget(ManualButton.at(font, leftPos + imageWidth - 76, topPos + 5, 68, 20, ManualFile.QUICK));
        exampleWidgets.clear();
        if (newDraftConfirmation) { initNewDraft(); return; }
        int tab = (imageWidth - 28) / 4;
        button(8, 30, tab, "document_exchange", () -> { examplesOpen = false; init(); }).active = examplesOpen;
        button(12 + tab, 30, tab, sensingFilter.isPresent() ? "examples_all" : "examples", () -> {
            sensingFilter=Optional.empty();
            examplesOpen = true; reloadExamples();
        }).active = !examplesOpen || sensingFilter.isPresent();
        button(16 + tab * 2, 30, tab, "new_program", () -> {
            newDraftConfirmation = true; notice = text("new_program_hint"); init();
        }).active = menu.response().isPresent();
        button(20 + tab * 3, 30, tab, "media_library", () ->
            minecraft.setScreen(new ComputerMediaScreen(menu, editorInventory, () -> minecraft.setScreen(this))));
        if (examplesOpen) initExamples(); else initExchange();
    }
    private void initNewDraft() {
        int half = (imageWidth - 20) / 2;
        button(8, imageHeight - 27, half, "new_program_confirm", () -> act(() -> {
            var empty = new ProgramWorkspace(); empty.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
            var checked = ProgramDocumentWire.decode(ProgramDocumentWire.encode(empty.snapshot(), limits()), limits());
            apply.accept(checked); newDraftConfirmation = false; examplesOpen = false; notice = text("program_imported");
        })).active = menu.response().isPresent();
        button(12 + half, imageHeight - 27, half, "new_program_cancel", this::cancelNewDraft);
    }
    private void cancelNewDraft() { newDraftConfirmation = false; notice = text("program_hint"); init(); }
    private void initExchange() {
        int third = (imageWidth - 24) / 3;
        button(8, 58, third, "export_program", () -> act(() -> {
            payloadText = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(source.get(), limits()));
            notice = text("program_exported");
        })).active = menu.response().isPresent();
        button(12 + third, 58, third, "copy_package", () -> act(() -> clipboardWrite.accept(payloadText))).active = !payloadText.isEmpty();
        button(16 + third * 2, 58, third, "paste_package", () -> act(() -> {
            var copied = Objects.requireNonNull(clipboardRead.get()); checkLength(copied); payloadText = copied;
            notice = text("program_hint");
        }));
        payload = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 90, imageWidth - 16, 18, text("program_package")));
        // Preserve an overflow sentinel: direct paste must not silently become an accepted prefix.
        payload.setMaxLength(MAX_TEXT + 1); payload.setValue(payloadText); payload.setResponder(value -> payloadText = value);
        int half = (imageWidth - 20) / 2;
        button(8, imageHeight - 27, half, "import_program", () -> act(() -> {
            checkLength(payloadText);
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(payloadText), limits());
            apply.accept(document); notice = text("program_imported");
        })).active = menu.response().isPresent();
        button(12 + half, imageHeight - 27, half, "back", this::onClose);
    }
    private void reloadExamples() { readExamples(); init(); }
    private void readExamples() {
        // Never keep a stale valid preview after a failed reload or resource-pack change.
        preview = null; selectedExample = null; examples = List.of(); examplePage = descriptionPage = 0;
        perform(() -> {
            var all = ProgramExampleCatalog.load(exampleResourceOpener);
            examples = sensingFilter.map(section -> all.stream().filter(e -> e.tags().contains(section.exampleTag())).toList()).orElse(all);
            notice = text(sensingFilter.isPresent() && examples.isEmpty() ? "sensing_examples_empty" : "examples_hint");
        });
    }
    private int exampleListWidth() { return Math.min(180, (imageWidth - 24) / 2); }
    private void initExamples() {
        var search = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 60, exampleListWidth(), 18,
            text("examples_search")));
        search.setMaxLength(96);
        search.setHint(text("examples_search"));
        search.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("examples_search_hint")));
        search.setValue(exampleQuery);
        search.setResponder(value -> {
            exampleQuery = value;
            examplePage = descriptionPage = 0;
            preview = null;
            selectedExample = null;
            if (!examples.isEmpty()) notice = text("examples_hint");
            refreshExampleResults();
        });
        refreshExampleResults();
    }

    private static String searchText(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').strip().replaceAll("\\s+", " ");
    }

    private void refreshExampleResults() {
        exampleWidgets.forEach(this::removeWidget);
        exampleWidgets.clear();
        var query = searchText(exampleQuery);
        var matches = query.isEmpty() ? examples : examples.stream().filter(example ->
            searchText(example.id()).contains(query) || searchText(Component.translatable(example.title()).getString()).contains(query)).toList();
        if (!examples.isEmpty() && matches.isEmpty()) notice = text("examples_no_matches");
        int listWidth = exampleListWidth();
        int rows = Math.max(1, (imageHeight - 168) / 24);
        int lastPage = Math.max(0, (matches.size() - 1) / rows);
        examplePage = Math.clamp(examplePage, 0, lastPage);
        for (int i = 0; i < rows && examplePage * rows + i < matches.size(); i++) {
            var example = matches.get(examplePage * rows + i);
            var row = addRenderableWidget(new CreateTextButton(font, leftPos + 8, topPos + 84 + i * 24,
                listWidth, 20, Component.translatable(example.title()), () -> {
                    preview = null; selectedExample = example; descriptionPage = 0;
                    act(() -> {
                        preview = ProgramExampleCatalog.preview(example, exampleResourceOpener, limits());
                        notice = text("examples_preview");
                    });
                }, true));
            exampleWidgets.add(row);
            row.active = example != selectedExample;
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(example.title())));
        }
        int halfList = (listWidth - 4) / 2;
        exampleButton(8, imageHeight - 79, halfList, "examples_previous", () -> { examplePage--; init(); }).active = examplePage > 0;
        exampleButton(12 + halfList, imageHeight - 79, halfList, "examples_next", () -> { examplePage++; init(); }).active = examplePage < lastPage;
        exampleButton(8, imageHeight - 55, listWidth, "examples_reload", this::reloadExamples);
        descriptionX = 16 + listWidth; descriptionWidth = imageWidth - descriptionX - 8;
        var body = Component.empty().append(notice);
        if (selectedExample != null) {
            body.append("\n\n").append(Component.translatable(selectedExample.title()));
            for (String key : selectedExample.instructions()) body.append("\n\n").append(Component.translatable(key));
        }
        descriptionLines = font.split(body, descriptionWidth);
        linesPerPage = Math.max(1, (imageHeight - 140) / font.lineHeight);
        int lastDescriptionPage = Math.max(0, (descriptionLines.size() - 1) / linesPerPage);
        descriptionPage = Math.clamp(descriptionPage, 0, lastDescriptionPage);
        int halfDescription = (descriptionWidth - 4) / 2;
        exampleButton(descriptionX, imageHeight - 55, halfDescription, "instructions_previous", () -> { descriptionPage--; init(); }).active = descriptionPage > 0;
        exampleButton(descriptionX + halfDescription + 4, imageHeight - 55, halfDescription, "instructions_next", () -> { descriptionPage++; init(); }).active = descriptionPage < lastDescriptionPage;
        int half = (imageWidth - 20) / 2;
        exampleButton(8, imageHeight - 27, half, "example_replace", () -> act(() -> {
            // Recheck the current server limits, then use exactly the clipboard import callback.
            if (preview == null) throw new IllegalStateException("No valid example selected");
            var checked = ProgramDocumentWire.decode(ProgramDocumentWire.encode(preview, limits()), limits());
            apply.accept(checked); notice = text("program_imported"); descriptionPage = 0;
        })).active = preview != null && menu.response().isPresent();
        exampleButton(12 + half, imageHeight - 27, half, "back", this::onClose);
    }
    private CreateTextButton exampleButton(int x, int y, int width, String key, Runnable action) {
        var control = button(x, y, width, key, action);
        exampleWidgets.add(control);
        return control;
    }
    private ArchiveLimits limits() { return menu.response().orElseThrow(() -> new IllegalStateException("Editor limits unavailable")).archiveLimits(); }
    private static void checkLength(String value) {
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException("Program document text exceeds the editor transport limit");
    }
    private void act(Runnable action) { perform(action); init(); }
    private void perform(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | ComputationException | UncheckedIOException | com.google.gson.JsonParseException failure) {
            notice = text("invalid_parameter", String.valueOf(failure.getMessage())); descriptionPage = 0;
        }
    }
    private CreateTextButton button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, text(key), action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, font.plainSubstrByWidth(text("program_package").getString(), imageWidth - 90), leftPos + 10, topPos + 10, 0xffeeeeee, false);
        if (newDraftConfirmation) graphics.drawWordWrap(font, notice, leftPos + 8, topPos + 60, imageWidth - 16, 0xffcccccc);
        else if (examplesOpen) {
            int start = descriptionPage * linesPerPage;
            for (int i = start; i < Math.min(descriptionLines.size(), start + linesPerPage); i++)
                graphics.drawString(font, descriptionLines.get(i), leftPos + descriptionX, topPos + 60 + (i - start) * font.lineHeight, 0xffcccccc, false);
            var page = text("instructions_page", descriptionPage + 1, Math.max(1, (descriptionLines.size() + linesPerPage - 1) / linesPerPage));
            graphics.drawString(font, page, leftPos + descriptionX, topPos + imageHeight - 69, 0xffaaaaaa, false);
        } else graphics.drawWordWrap(font, notice, leftPos + 8, topPos + 120, imageWidth - 16, 0xffcccccc);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { if (newDraftConfirmation) cancelNewDraft(); else close.run(); }
    @Override public void removed() {}
    private static Component text(String key, Object... arguments) { return Component.translatable("create_biotech.biocomputing.editor." + key, arguments); }
}
