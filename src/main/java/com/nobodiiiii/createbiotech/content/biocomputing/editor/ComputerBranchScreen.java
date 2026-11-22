package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Local branch selector. It edits a draft through callbacks and never installs or executes it. */
public final class ComputerBranchScreen extends AbstractSimiContainerScreen<ComputerProgramMenu> {
	private final List<ModuleDefinition.Key> modules;
	private final BiConsumer<ModuleDefinition.Key, ModuleDefinition.Key> apply;
	private final Consumer<ModuleDefinition.Key> open;
	private final Runnable back;
	private ModuleDefinition.Key whenTrue;
	private ModuleDefinition.Key whenFalse;

	public ComputerBranchScreen(ComputerProgramMenu menu, Inventory inventory, Component title, List<ModuleDefinition.Key> modules, ModuleDefinition.Key whenTrue,
		ModuleDefinition.Key whenFalse, BiConsumer<ModuleDefinition.Key, ModuleDefinition.Key> apply,
		Consumer<ModuleDefinition.Key> open, Runnable back) {
		super(menu, inventory, title);
		this.modules = List.copyOf(modules);
		this.apply = Objects.requireNonNull(apply);
		this.open = Objects.requireNonNull(open);
		this.back = Objects.requireNonNull(back);
		this.whenTrue = choose(whenTrue);
		this.whenFalse = choose(whenFalse);
	}

	@Override
	protected void init() {
		setWindowSize(Math.min(580, width - 12), Math.min(230, height - 12));
		super.init();
		setFocused(null);
		clearWidgets();
		int w = imageWidth - 16, x = 8, y = 16;
		button(x, y + 46, w, modules.isEmpty()
			? Component.translatable("create_biotech.biocomputing.branch.missing_module")
			: Component.translatable("create_biotech.biocomputing.branch.when_true", label(whenTrue)), () -> {
				whenTrue = next(whenTrue);
				init();
			}).active = !modules.isEmpty();
		button(x, y + 70, w, modules.isEmpty()
			? Component.translatable("create_biotech.biocomputing.branch.missing_module")
			: Component.translatable("create_biotech.biocomputing.branch.when_false", label(whenFalse)), () -> {
				whenFalse = next(whenFalse);
				init();
			}).active = !modules.isEmpty();
		int half = (w - 4) / 2;
		button(x, y + 94, half, Component.translatable("create_biotech.biocomputing.branch.open_true"),
			() -> open.accept(whenTrue)).active = whenTrue != null;
		button(x + half + 4, y + 94, w - half - 4, Component.translatable("create_biotech.biocomputing.branch.open_false"),
			() -> open.accept(whenFalse)).active = whenFalse != null;
		button(x, y + 142, half, Component.translatable("create_biotech.biocomputing.branch.apply"),
			() -> apply.accept(whenTrue, whenFalse)).active = whenTrue != null && whenFalse != null;
		button(x + half + 4, y + 142, w - half - 4, Component.translatable("create_biotech.biocomputing.branch.back"), back);
	}

	private CreateTextButton button(int x, int y, int width, Component label, Runnable action) {
		return addRenderableWidget(new CreateTextButton(font, leftPos + x, topPos + y, width, 20, label, action, true));
	}

	private ModuleDefinition.Key choose(ModuleDefinition.Key key) {
		return key != null && modules.contains(key) ? key : modules.stream().findFirst().orElse(null);
	}

	private ModuleDefinition.Key next(ModuleDefinition.Key key) {
		return modules.isEmpty() ? null : modules.get((modules.indexOf(key) + 1) % modules.size());
	}

	private static String label(ModuleDefinition.Key key) {
		return key == null ? "?" : key.id();
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		PatternGuiSkin.blit(graphics, PatternGuiSkin.SURFACE, leftPos, topPos, imageWidth, imageHeight);
		PatternGuiSkin.blit(graphics, PatternGuiSkin.HEADER, leftPos + 4, topPos + 4, imageWidth - 8, 22);
		graphics.drawString(font, Component.translatable("create_biotech.biocomputing.branch.title"),
			leftPos + 8, topPos + 10, 0xffeeeeee, false);
		graphics.drawWordWrap(font, Component.translatable("create_biotech.biocomputing.branch.hint"),
			leftPos + 8, topPos + 34, imageWidth - 16, 0xffcccccc);
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

	@Override
	public void onClose() {
		back.run();
	}

	@Override
	public void removed() {}
}
