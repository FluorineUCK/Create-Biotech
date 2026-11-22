package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ScratchProcedureReferences.ExtensionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ScratchProcedureReferences.Reference;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Per-draft extension selection and reference recovery; no hardware calls live here. */
final class ComputerScratchExtensionScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    private record Row(ExtensionRevision version, ScratchExtension descriptor) {}
    private final Map<String, Long> enabled;
    private final BiConsumer<String, Long> apply;
    private final Runnable close;
    private final Inventory inventory;
    private final Consumer<Screen> navigate;
    private final Consumer<Reference> open;
    private final Map<ExtensionRevision, List<Reference>> references;
    private final boolean registerExecution;
    private final List<Row> rows;
    private List<Row> matches = List.of();
    private final List<CreateTextButton> resultWidgets = new ArrayList<>();
    private String query = "";
    private Optional<FlowBlockCatalog.SensingSection> sensingGroup = Optional.empty();
    private boolean groupOnly;
    private int page, dependencyPage;
    private Row dependencyOwner, focusedRequirement;
    private List<Row> required = List.of();
    private record ReturnPoint(String query, int page, Row focused, ExtensionRevision owner) {}
    private final Deque<ReturnPoint> returnPath = new ArrayDeque<>();
    private Component notice = text("extension_library_hint");

    ComputerScratchExtensionScreen(ComputerProgramMenu menu, Inventory inventory, Component title, FlowDraft draft,
                                   BiConsumer<String, Long> apply, Consumer<Screen> navigate, Consumer<Reference> open, Runnable close) {
        super(menu, inventory, title);
        var body = draft.snapshot();
        enabled = new TreeMap<>(body.canvas().extensions());
        this.inventory = inventory;
        this.apply = apply;
        this.navigate = navigate;
        this.open = open;
        this.close = close;
        references = ScratchProcedureReferences.extensions(draft);
        registerExecution = body.execution() == com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Execution.REGISTER_V1;
        var choices = new LinkedHashMap<ExtensionRevision, Row>();
        for (var extension : ScratchExtensions.all()) {
            var version = new ExtensionRevision(extension.id().toString(), extension.revision());
            choices.put(version, new Row(version, extension));
        }
        enabled.forEach((id, revision) -> {
            var version = new ExtensionRevision(id, revision);
            choices.putIfAbsent(version, new Row(version, null));
        });
        // A missing add-on may already have been disabled; its existing visible blocks still count.
        references.keySet().forEach(version -> choices.putIfAbsent(version, new Row(version, null)));
        // Dependencies remain inspectable even when the exact version is not installed or referenced.
        for (var row : List.copyOf(choices.values())) if (row.descriptor() != null) {
            row.descriptor().requires().forEach((id, revision) -> {
                var version = new ExtensionRevision(id.toString(), revision);
                choices.putIfAbsent(version, new Row(version, null));
            });
        }
        rows = List.copyOf(choices.values());
    }

    void sensingExtensions(FlowBlockCatalog.SensingSection group) {
        sensingGroup = Optional.of(group);
        groupOnly = true;
    }

    @Override protected void init() {
        setWindowSize(Math.min(530, width - 12), Math.min(240, height - 12)); super.init(); clearWidgets(); setFocused(null);
        resultWidgets.clear();
        if (dependencyOwner != null) {
            initDependencies();
            return;
        }
        var search = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 31, imageWidth - 16, 18,
            text("extension_library_search")));
        search.setMaxLength(96);
        search.setHint(text("extension_library_search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            focusedRequirement = null;
            page = 0;
            notice = text("extension_library_hint");
            refreshRows();
        });
        button(imageWidth / 2 + 2, imageHeight - 28, imageWidth - imageWidth / 2 - 10,
            returnPath.isEmpty() ? "back" : "extension_return_origin", this::onClose);
        refreshRows();
    }

    private void showDependencies(Row owner) {
        dependencyOwner = owner;
        var needs = owner.descriptor().requires();
        required = rows.stream().filter(row -> Objects.equals(needs.get(net.minecraft.resources.ResourceLocation.parse(row.version().id())),
                row.version().revision()))
            .sorted(Comparator.comparing(row -> row.version().id())).toList();
        dependencyPage = 0;
        init();
    }

    private void initDependencies() {
        int half = (imageWidth - 20) / 2;
        button(8, 55, half, "palette_previous", () -> { dependencyPage--; init(); }).active = dependencyPage > 0;
        button(12 + half, 55, half, "palette_next", () -> { dependencyPage++; init(); }).active = dependencyPage + 1 < required.size();
        var target = required.isEmpty() ? null : required.get(dependencyPage);
        boolean cycle = target != null && (target.version().equals(dependencyOwner.version())
            || returnPath.stream().anyMatch(point -> point.owner().equals(target.version())));
        var openRequirement = button(8, imageHeight - 28, half, "extension_open_requirement", () -> {
            returnPath.push(new ReturnPoint(query, page, focusedRequirement, dependencyOwner.version()));
            focusedRequirement = target;
            query = target.version().id() + " @ " + target.version().revision();
            page = 0;
            dependencyOwner = null;
            notice = text("extension_library_hint");
            init();
        });
        openRequirement.active = target != null && !cycle;
        if (cycle) {
            var hint = text("extension_requirement_already_on_path");
            openRequirement.setTooltip(Tooltip.create(hint));
            openRequirement.setToolTip(hint);
        }
        button(12 + half, imageHeight - 28, half, "back", this::onClose);
    }

    private List<Row> findRows() {
        if (focusedRequirement != null) return List.of(focusedRequirement);
        String phrase = searchText(query);
        String revision = "";
        int at = phrase.lastIndexOf('@');
        if (at >= 0) {
            revision = phrase.substring(at + 1).strip();
            phrase = phrase.substring(0, at).strip();
        }
        String term = phrase, fixed = revision;
        return rows.stream().filter(this::inSensingGroup)
            .filter(row -> (fixed.isEmpty() || Long.toString(row.version().revision()).equals(fixed))
            && (searchText(row.version().id()).contains(term)
                || row.descriptor() != null && searchText(Component.translatable(row.descriptor().titleKey()).getString()).contains(term))).toList();
    }

    private boolean inSensingGroup(Row row) {
        if (!groupOnly || sensingGroup.isEmpty()) return true;
        // Unknown versions have no category metadata; the unfiltered library still exposes them.
        return row.descriptor() != null && row.descriptor().commands().stream()
            .anyMatch(command -> FlowBlockCatalog.SensingSection.from(command.sensingRole()) == sensingGroup.orElseThrow());
    }

    private static String searchText(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').strip().replaceAll("\\s+", " ");
    }

    private void refreshRows() {
        resultWidgets.forEach(this::removeWidget);
        resultWidgets.clear();
        if (sensingGroup.isPresent() && returnPath.isEmpty()) {
            var width = imageWidth / 2 - 10;
            var toggle = resultButton(imageWidth - width - 8, 5, width,
                groupOnly ? "extension_browse_all" : "extension_browse_group", () -> {
                    groupOnly = !groupOnly;
                    page = 0;
                    refreshRows();
                });
            FlowBlockSkin.fitToolboxCaption(toggle, font);
        }
        matches = findRows();
        page = Math.clamp(page, 0, Math.max(0, matches.size() - 1));
        int third = (imageWidth - 24) / 3;
        resultButton(8, 55, third, "palette_previous", () -> { page--; refreshRows(); }).active = page > 0;
        resultButton(12 + third, 55, third, "palette_next", () -> { page++; refreshRows(); }).active = page + 1 < matches.size();
        if (matches.isEmpty()) return;
        var row = matches.get(page);
        boolean active = isEnabled(row.version());
        var uses = references.getOrDefault(row.version(), List.of());
        if (!uses.isEmpty()) resultButton(16 + 2 * third, 55, third, "extension_find_uses", () ->
            navigate.accept(new ComputerScratchReferencesScreen(menu, inventory,
                new ModuleDefinition.Key(row.version().id(), row.version().revision()), uses, open, () -> navigate.accept(this))));
        if (hasDependencies(row))
            resultButton(8, 109, imageWidth - 16, "extension_dependencies", () -> showDependencies(row));
        var control = resultButton(8, imageHeight - 28, (imageWidth - 20) / 2,
            active ? "extension_disable" : "extension_enable", () -> toggle(row, active));
        control.active = active || canEnable(row);
    }

    private static boolean hasDependencies(Row row) {
        return row.descriptor() != null && !row.descriptor().requires().isEmpty();
    }

    private boolean canEnable(Row row) {
        if (!registerExecution || row.descriptor() == null || enabled.containsKey(row.version().id())) return false;
        var prospective = new TreeMap<>(enabled);
        prospective.put(row.version().id(), row.version().revision());
        try {
            ScratchExtensions.requireEnabled(row.descriptor(), prospective);
            return true;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private CreateTextButton resultButton(int x, int y, int width, String key, Runnable action) {
        var control = button(x, y, width, key, action);
        resultWidgets.add(control);
        return control;
    }

    private boolean isEnabled(ExtensionRevision version) {
        return Objects.equals(enabled.get(version.id()), version.revision());
    }
    private void toggle(Row row, boolean active) {
        var version = row.version();
        try {
            var candidate = new TreeMap<>(enabled);
            if (active) candidate.remove(version.id());
            else {
                if (candidate.containsKey(version.id())) throw new IllegalArgumentException(text("extension_other_revision").getString());
                candidate.put(version.id(), version.revision());
                ScratchExtensions.requireEnabled(row.descriptor(), candidate);
            }
            apply.accept(version.id(), active ? null : version.revision());
            enabled.clear(); enabled.putAll(candidate);
            notice = text(active ? "extension_disabled_notice" : "extension_enabled_notice");
            refreshRows();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            notice = text("invalid", String.valueOf(invalid.getMessage()));
        }
    }
    private CreateTextButton button(int x, int y, int w, String key, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, text(key), action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        boolean inspecting = dependencyOwner != null;
        boolean showGroup = !inspecting && returnPath.isEmpty() && groupOnly && sensingGroup.isPresent();
        var titleText = showGroup ? text("extension_library_group", text(sensingGroup.orElseThrow().key()))
            : text("extension_library");
        int titleWidth = !inspecting && returnPath.isEmpty() && sensingGroup.isPresent() ? imageWidth / 2 - 12 : imageWidth - 18;
        graphics.drawString(font, font.plainSubstrByWidth(titleText.getString(), titleWidth), leftPos + 9, topPos + 10, 0xffeeeeee, false);
        if (inspecting) {
            var heading = text("extension_dependencies_for", dependencyOwner.version().id(), dependencyOwner.version().revision());
            graphics.drawString(font, font.plainSubstrByWidth(heading.getString(), imageWidth - 16), leftPos + 8, topPos + 38, 0xffdddddd, false);
        }
        var visible = inspecting ? required : matches;
        if (!visible.isEmpty()) {
            var row = visible.get(inspecting ? dependencyPage : page);
            var version = row.version();
            var title = row.descriptor() == null ? Component.literal(version.id()) : Component.translatable(row.descriptor().titleKey());
            graphics.drawString(font, font.plainSubstrByWidth(title.getString(), imageWidth - 16), leftPos + 8, topPos + 83, 0xffeeeeee, false);
            var state = text(row.descriptor() == null ? "extension_status_missing" :
                isEnabled(version) ? "extension_status_enabled" :
                    enabled.containsKey(version.id()) ? "extension_status_other_revision" : "extension_status_disabled");
            graphics.drawString(font, font.plainSubstrByWidth(version.id() + " @ " + version.revision() + " / " + state.getString(), imageWidth - 16),
                leftPos + 8, topPos + 94, 0xffdddddd, false);
            var desc = row.descriptor() == null ? text("extension_missing") : Component.translatable(row.descriptor().descriptionKey());
            int descriptionTop = !inspecting && hasDependencies(row) ? 135 : 109;
            if (descriptionTop < imageHeight - 52) {
                graphics.enableScissor(leftPos + 8, topPos + descriptionTop, leftPos + imageWidth - 8, topPos + imageHeight - 52);
                graphics.drawWordWrap(font, desc, leftPos + 8, topPos + descriptionTop, imageWidth - 16, 0xffeeeeee);
                graphics.disableScissor();
            }
        } else if (!inspecting && (showGroup || !query.isBlank())) {
            graphics.drawWordWrap(font, text(showGroup ? "extension_library_group_empty" : "extension_library_search_empty"),
                leftPos + 8, topPos + 83, imageWidth - 16, 0xffeeeeee);
        }
        var message = registerExecution ? notice : text("extension_requires_isa");
        graphics.drawString(font, font.plainSubstrByWidth(message.getString(), imageWidth - 16),
            leftPos + 8, topPos + imageHeight - 43, 0xffffddaa, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {}
    @Override public void onClose() {
        if (dependencyOwner != null) {
            dependencyOwner = null; init();
        } else if (!returnPath.isEmpty()) {
            var point = returnPath.pop();
            query = point.query();
            page = point.page();
            focusedRequirement = point.focused();
            notice = text("extension_library_hint");
            init();
        } else close.run();
    }
    @Override public void removed() {}
    private static Component text(String key, Object... args) {
        return Component.translatable("create_biotech.biocomputing.flow_editor." + key, args);
    }
}