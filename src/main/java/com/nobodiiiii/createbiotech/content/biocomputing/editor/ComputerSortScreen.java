package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local key-subgraph configuration. Choosing or opening a module never installs or executes a program. */
public final class ComputerSortScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
    public record Selection(ModuleDefinition.Key module, String item, String result, boolean descending) {}

    private final ProgramWorkspace workspace;
    private final Consumer<Selection> apply;
    private final Consumer<ModuleDefinition.Key> open;
    private final Runnable back;
    private ModuleDefinition.Key module;
    private String item;
    private String result;
    private boolean descending;

    public ComputerSortScreen(ComputerProgramMenu menu, Inventory inventory, Component title,
        ProgramWorkspace workspace, Selection selection, Consumer<Selection> apply,
        Consumer<ModuleDefinition.Key> open, Runnable back) {
        super(menu, inventory, title);
        this.workspace = Objects.requireNonNull(workspace);
        this.apply = Objects.requireNonNull(apply);
        this.open = Objects.requireNonNull(open);
        this.back = Objects.requireNonNull(back);
        if (selection != null) {
            module = selection.module(); item = selection.item(); result = selection.result(); descending = selection.descending();
        }
        normalize();
    }

    private void normalize() {
        var keys = workspace.keys();
        if (module == null || !keys.contains(module)) module = keys.stream().findFirst().orElse(null);
        item = choose(inputs(), item);
        result = choose(outputs(), result);
    }
    private List<String> inputs() { return module == null ? List.of() : List.copyOf(workspace.graph(module).inputs().keySet()); }
    private List<String> outputs() { return module == null ? List.of() : List.copyOf(workspace.graph(module).outputs().keySet()); }
    private static String choose(List<String> choices, String current) {
        return current != null && choices.contains(current) ? current : choices.stream().findFirst().orElse(null);
    }
    private static <T> T next(List<T> choices, T current) {
        return choices.isEmpty() ? null : choices.get((choices.indexOf(current) + 1) % choices.size());
    }

    @Override protected void init() {
        setWindowSize(Math.min(580, width - 12), Math.min(228, height - 12));
        super.init(); setFocused(null); clearWidgets(); normalize();
        int w = imageWidth - 16;
        button(8, 58, w, module == null ? text("missing_module") : text("module", module.id(), module.revision()), () -> {
            module = next(workspace.keys(), module); init();
        }).active = module != null;
        button(8, 82, w, text("item", port(item)), () -> { item = next(inputs(), item); init(); }).active = !inputs().isEmpty();
        button(8, 106, w, text("result", port(result)), () -> { result = next(outputs(), result); init(); }).active = !outputs().isEmpty();
        button(8, 130, w, text("direction", text(descending ? "descending" : "ascending")), () -> { descending = !descending; init(); });
        button(8, 154, w, text("open"), () -> { if (module != null) open.accept(module); }).active = module != null;
        int half = (w - 4) / 2;
        button(8, 190, half, text("apply"), () -> {
            if (valid()) apply.accept(new Selection(module, item, result, descending));
        }).active = valid();
        button(12 + half, 190, w - half - 4, text("back"), back);
    }
    private boolean valid() {
        return module != null && item != null && result != null && inputs().contains(item) && outputs().contains(result);
    }
    private static Component port(String value) { return value == null ? text("missing_port") : Component.literal(value); }
    private static Component text(String key, Object... arguments) {
        return Component.translatable("create_biotech.biocomputing.sort." + key, arguments);
    }
    private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
        return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
        PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
        graphics.drawString(font, text("title"), leftPos + 8, topPos + 10, 0xffeeeeee, false);
        graphics.drawWordWrap(font, text("hint"), leftPos + 8, topPos + 34, imageWidth - 16, 0xffcccccc);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}
    @Override public void onClose() { back.run(); }
    @Override public void removed() {}
}
