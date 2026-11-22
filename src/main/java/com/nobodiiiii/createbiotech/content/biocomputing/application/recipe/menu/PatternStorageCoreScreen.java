package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserSummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.PanelEditBox;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.nobodiiiii.createbiotech.foundation.gui.PatternIconButton;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Bounded catalog projected as movable nodes. Source edits share the recipe canvas with Stock Keeper. */
public final class PatternStorageCoreScreen extends AbstractSimiContainerScreen<PatternStorageCoreMenu> {
    private static final int CANVAS_TOP = 59, NODE_WIDTH = 72, NODE_HEIGHT = 42, SEARCH_DELAY_TICKS = 4;
    private final Inventory inventory;
    private final CanvasViewport viewport = new CanvasViewport();
    private final Map<UUID, Position> positions = new HashMap<>();
    private PanelEditBox search;
    private CreateTextButton ghost, kindButton, previous, next;
    private Optional<StackKey> ghostKey = Optional.empty();
    private Optional<PatternKind> kind = Optional.empty();
    private Optional<UUID> selection = Optional.empty();
    private PatternBrowserPage displayed;
    private int pageIndex, ticks, searchDelay;
    private boolean editOnOpen, panning, moved;
    private UUID dragged;
    private double pressX, pressY;
    private Component transportError;
    private record Position(double x, double y) {}

    public PatternStorageCoreScreen(PatternStorageCoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); this.inventory = inventory;
        selection = menu.editorPattern(); pageIndex = menu.editorPage(); editOnOpen = selection.isPresent();
    }
    @Override protected void init() {
        String oldSearch = search == null ? "" : search.getValue();
        setWindowSize(Math.min(480, width - 12), Math.min(316, height - 12)); super.init();
        search = addRenderableWidget(PanelEditBox.forBiocomputing(font, leftPos + 10, topPos + 31,
            Math.max(48, imageWidth - 220), 20, text("search")));
        search.setMaxLength(PatternBrowserFilter.MAX_SEARCH); search.setValue(oldSearch); search.setHint(text("search"));
        search.setResponder(value -> { resetSelection(); searchDelay = SEARCH_DELAY_TICKS; });
        ghost = button(imageWidth - 204, 31, 20, Component.empty(), () -> {
            ItemStack stack = menu.getCarried(); if (stack.isEmpty()) stack = inventory.player.getMainHandItem(); acceptGhost(stack);
        });
        ghost.setTooltip(Tooltip.create(text("ghost")));
        button(imageWidth - 181, 31, 18, Component.literal("×"), () -> acceptGhost(ItemStack.EMPTY));
        kindButton = button(imageWidth - 160, 31, 72, kindLabel(), () -> {
            kind = kind.isEmpty() ? Optional.of(PatternKind.STANDARD)
                : kind.get() == PatternKind.STANDARD ? Optional.of(PatternKind.SEQUENCED) : Optional.empty();
            kindButton.setMessage(kindLabel()); resetSelection(); request();
        });
        button(imageWidth - 84, 31, 74, canvasText("new"), () ->
            minecraft.setScreen(new PatternRecipeEditorScreen(this, menu, inventory)));
        previous = button(10, imageHeight - 49, 24, Component.literal("<"), () -> changePage(-1));
        next = button(38, imageHeight - 49, 24, Component.literal(">"), () -> changePage(1));
        var library = addRenderableWidget(new PatternIconButton(leftPos + 70, topPos + imageHeight - 49, AllIcons.I_CONFIG_OPEN));
        library.setTooltip(Tooltip.create(Component.translatable("create_biotech.biocomputing.library.title")));
        library.withCallback(() -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0));
        button(imageWidth - 174, imageHeight - 49, 68, canvasText("reset_view"), viewport::reset);
        button(imageWidth - 102, imageHeight - 49, 66, text("refresh"), this::request);
        var close = addRenderableWidget(new PatternIconButton(leftPos + imageWidth - 30,
            topPos + imageHeight - 49, AllIcons.I_CONFIRM));
        close.withCallback(this::onClose);
        panning = moved = false; dragged = null; displayed = null; request(); updateButtons();
    }
    private Component kindLabel() { return kind.map(k -> text(k.serializedName())).orElse(text("all_types")); }
    private CreateTextButton button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, w, 20, label, action, true));
    }
    private Rect2i canvas() { return new Rect2i(leftPos + 8, topPos + CANVAS_TOP, imageWidth - 16, Math.max(1, imageHeight - CANVAS_TOP - 68)); }
    private boolean inCanvas(double x, double y) { return canvas().contains((int)x, (int)y); }
    private List<PatternBrowserSummary> entries() {
        return displayed != null && displayed.status() == PatternBrowserPage.Status.OK ? displayed.entries() : List.of();
    }
    public void refreshAfterEdit() { resetSelection(); request(); }
    private void resetSelection() { pageIndex = 0; selection = Optional.empty(); editOnOpen = false; displayed = null; positions.clear(); viewport.reset(); }
    private void changePage(int delta) {
        pageIndex = Math.max(0, pageIndex + delta); selection = Optional.empty(); editOnOpen = false;
        positions.clear(); viewport.reset(); displayed = null; request();
    }
    public void acceptGhost(ItemStack stack) {
        ghostKey = stack.isEmpty() ? Optional.empty() : Optional.of(new StackKey(stack)); resetSelection(); request();
    }
    public Rect2i ghostTarget() { return new Rect2i(ghost.getX(), ghost.getY(), ghost.getWidth(), ghost.getHeight()); }
    public Optional<StackKey> ghostSelection() { return ghostKey; }
    private void request() {
        if (search == null) return;
        ticks = searchDelay = 0;
        var packet = menu.browser().request(PatternBrowserFilter.localized(search.getValue(), ghostKey, kind), pageIndex, selection);
        transportError = packet.sendIfBounded(inventory.player.registryAccess(), CBPackets::sendToServer) ? null : text("too_large");
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (searchDelay > 0) { if (--searchDelay == 0) request(); }
        else if (menu.browser().needsRefresh() || ++ticks >= menu.browser().refreshAfterTicks()) request();
        var latest = menu.browser().page().orElse(null);
        if (latest == displayed) return;
        displayed = latest;
        if (displayed != null && displayed.status() == PatternBrowserPage.Status.INVALID_PAGE && pageIndex != 0) {
            resetSelection(); request(); return;
        }
        int columns = Math.max(1, canvas().getWidth() / (NODE_WIDTH + 20));
        for (int i = 0; i < entries().size(); i++) {
            var entry = entries().get(i);
            positions.putIfAbsent(entry.patternId(), new Position(10 + i % columns * (NODE_WIDTH + 20), 10 + i / columns * (NODE_HEIGHT + 26)));
        }
        if (displayed != null && displayed.status() == PatternBrowserPage.Status.OK)
            positions.keySet().retainAll(entries().stream().map(PatternBrowserSummary::patternId).toList());
        updateButtons();
        if (editOnOpen && displayed != null && displayed.status() == PatternBrowserPage.Status.OK)
            displayed.detail().filter(entry -> selection.filter(entry.patternId()::equals).isPresent()).ifPresent(entry -> {
                editOnOpen = false;
                minecraft.setScreen(new PatternRecipeEditorScreen(this, menu, inventory, entry.pattern()));
            });
    }
    private void updateButtons() {
        previous.active = pageIndex > 0;
        next.active = displayed != null && displayed.status() == PatternBrowserPage.Status.OK
            && ((long) pageIndex + 1) * PatternBrowserPage.PAGE_SIZE < displayed.totalMatches();
    }
    @Override protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(g, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(g, PatternGuiSkin.HEADER, leftPos, topPos, imageWidth, 23);
        Rect2i area = canvas();
        g.enableScissor(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + area.getHeight());
        g.pose().pushPose();
        g.pose().translate(area.getX() + viewport.screenX(0), area.getY() + viewport.screenY(0), 0);
        g.pose().scale((float)viewport.scale(), (float)viewport.scale(), 1);
        for (var entry : entries()) {
            Position at = positions.get(entry.patternId()); if (at == null) continue;
            int x = (int)Math.round(at.x()), y = (int)Math.round(at.y());
            PatternGuiSkin.blit(g, selection.filter(entry.patternId()::equals).isPresent()
                ? PatternGuiSkin.SLOT_SELECTED : PatternGuiSkin.SLOT, x, y, NODE_WIDTH, NODE_HEIGHT);
            ItemStack output = entry.mainOutput().stack();
            PatternRecipeView.slot(g, font, x + 27, y + 3, output, entry.outputCount(), false);
            g.drawCenteredString(font, font.plainSubstrByWidth(output.getHoverName().getString(), NODE_WIDTH - 6), x + NODE_WIDTH / 2, y + 26, 0xffeeeeee);
        }
        g.pose().popPose(); g.disableScissor();
        ghostKey.ifPresent(key -> g.renderItem(key.stack(), ghost.getX() + 2, ghost.getY() + 2));
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 10, 8, 0xffeeeeee, false);
        if (transportError != null) draw(g, transportError, 16, CANVAS_TOP + 10);
        else if (displayed == null) draw(g, canvasText("loading"), 16, CANVAS_TOP + 10);
        else if (displayed.status() != PatternBrowserPage.Status.OK)
            draw(g, text(displayed.status().name().toLowerCase(Locale.ROOT)), 16, CANVAS_TOP + 10);
        else if (displayed.entries().isEmpty()) draw(g, canvasText("empty"), 16, CANVAS_TOP + 10);
        if (displayed != null) draw(g, text("page", pageIndex + 1,
            Math.max(1, (displayed.totalMatches() + (long)PatternBrowserPage.PAGE_SIZE - 1) / PatternBrowserPage.PAGE_SIZE),
            displayed.totalMatches(), displayed.totalPatterns(), displayed.invalidPages(), displayed.unsupportedPages()), 10, imageHeight - 63);
        draw(g, canvasText("hint"), 10, imageHeight - 23);
        draw(g, canvasText("session_layout"), 10, imageHeight - 12);
    }
    private void draw(GuiGraphics g, Component text, int x, int y) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), imageWidth - x - 8), x, y, 0xffdddddd, false);
    }
    private UUID hit(double x, double y) {
        if (!inCanvas(x, y)) return null;
        Rect2i area = canvas(); double wx = viewport.worldX(x - area.getX()), wy = viewport.worldY(y - area.getY());
        var nodes = entries();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            UUID id = nodes.get(i).patternId(); Position p = positions.get(id);
            if (p != null && wx >= p.x() && wx < p.x() + NODE_WIDTH && wy >= p.y() && wy < p.y() + NODE_HEIGHT) return id;
        }
        return null;
    }
    private void openNode(UUID id) { selection = Optional.of(id); editOnOpen = true; request(); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 1 && ghostTarget().contains((int)x, (int)y)) { acceptGhost(ItemStack.EMPTY); return true; }
        if (!inCanvas(x, y)) return super.mouseClicked(x, y, button);
        search.setFocused(false);
        if (button == 0 || button == 2) {
            dragged = button == 0 ? hit(x, y) : null;
            panning = dragged == null; moved = false; pressX = x; pressY = y; return true;
        }
        if (button == 1) { UUID id = hit(x, y); if (id != null) openNode(id); return true; }
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 || button == 2) {
            if (panning) { viewport.pan(dx, dy); return true; }
            if (dragged != null) {
                moved |= Math.abs(x - pressX) + Math.abs(y - pressY) > 3;
                if (moved) {
                    var p = positions.get(dragged);
                    if (p != null) positions.put(dragged, new Position(p.x() + dx / viewport.scale(), p.y() + dy / viewport.scale()));
                }
                return true;
            }
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (panning || dragged != null) {
            if (button == 0 && dragged != null && !moved && dragged.equals(hit(x, y))) openNode(dragged);
            panning = false; dragged = null; return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (inCanvas(x, y)) { Rect2i area = canvas(); viewport.zoomAt(x - area.getX(), y - area.getY(), (int)Math.signum(vertical)); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (search.isFocused() && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && key != org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
            return search.keyPressed(key, scan, modifiers) || search.canConsumeInput();
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
        UUID id = hit(mouseX, mouseY);
        if (id != null) entries().stream().filter(entry -> id.equals(entry.patternId())).findFirst().ifPresent(entry -> {
            var source = entry.source();
            g.renderComponentTooltip(font, List.of(entry.mainOutput().stack().getHoverName(),
                canvasText("node_hint"), text("kind", entry.kind().serializedName()),
                text("source", source.shelf().dimension().location(), source.shelf().subLevelId(),
                    source.shelf().localPos().toShortString(), source.slot() + 1, source.page() + 1)), mouseX, mouseY);
        });
        if (ghost.isHovered() && ghostKey.isPresent()) g.renderTooltip(font, ghostKey.get().stack(), mouseX, mouseY);
    }
    private static Component canvasText(String key, Object... values) {
        return Component.translatable("create_biotech.pattern_core.canvas." + key, values);
    }
    private static Component text(String key, Object... values) {
        return Component.translatable("create_biotech.pattern_core.browser." + key, Arrays.stream(values)
            .map(value -> value instanceof Component || value instanceof Number || value instanceof Boolean || value instanceof String
                ? value : String.valueOf(value)).toArray());
    }
}
