package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.nobodiiiii.createbiotech.foundation.gui.PatternGuiSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** The same real-item slot presentation in the browser and the recipe editor. */
public final class PatternRecipeView {
    private PatternRecipeView() {}

    /** Shared presentation facts for the Core and the in-place Stock Keeper details. */
    public record DetailLine(Component text, ItemStack stack, int amount, List<Component> hints) {}

    public static DetailLine message(Component text) {
        return new DetailLine(text, ItemStack.EMPTY, 1, List.of());
    }

    public static List<DetailLine> details(PatternRecord pattern, Component priority) {
        var lines = new ArrayList<DetailLine>();
        lines.add(message(PatternRecipeEditorScreen.tr("outputs")));
        for (var output : pattern.outputs()) {
            var stack = output.stack().stack();
            lines.add(new DetailLine(stack.getHoverName().copy().append(" ×" + output.count()), stack, output.count(), List.of()));
        }
        lines.add(message(PatternRecipeEditorScreen.tr("inputs")));
        for (var input : pattern.inputs()) lines.add(inputLine(input));
        if (pattern.body() instanceof StandardPatternBody standard)
            lines.add(message(browserText("address", standard.address())));
        if (pattern.body() instanceof SequencedPatternBody sequenced) for (var stage : sequenced.stages()) {
            lines.add(message(browserText("stage", stage.index() + 1, stage.address())));
            for (var input : stage.inputs()) lines.add(inputLine(input));
        }
        lines.add(message(priority));
        var source = pattern.source();
        lines.add(message(browserText("source", source.shelf().dimension().location(), source.shelf().subLevelId(),
            source.shelf().localPos().toShortString(), source.slot() + 1, source.page() + 1)));
        return List.copyOf(lines);
    }

    private static DetailLine inputLine(PatternIngredient input) {
        var stack = ingredient(input);
        Component name = input.tagId() == null ? stack.getHoverName() : Component.literal("#" + input.tagId());
        var componentIds = input.components().asPatch().entrySet().stream()
            .map(entry -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey())))
            .sorted().collect(Collectors.joining(", "));
        var hints = componentIds.isEmpty() ? List.<Component>of()
            : List.<Component>of(Component.translatable("create_biotech.pattern_stock.components", componentIds));
        return new DetailLine(name.copy().append(" ×" + input.count()), stack, input.count(), hints);
    }

    public static void renderLine(GuiGraphics graphics, Font font, DetailLine line, int x, int y, int width) {
        if (!line.stack().isEmpty()) { slot(graphics, font, x, y, line.stack(), line.amount(), false); x += 21; width -= 21; }
        graphics.drawString(font, font.plainSubstrByWidth(line.text().getString(), Math.max(1, width)), x, y + 5, 0xffdddddd, false);
    }

    public static void renderTooltip(GuiGraphics graphics, Font font, DetailLine line, boolean overSlot, int mouseX, int mouseY) {
        var tooltip = new ArrayList<Component>();
        var minecraft = Minecraft.getInstance();
        if (overSlot && !line.stack().isEmpty())
            tooltip.addAll(line.stack().getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL));
        else tooltip.add(line.text());
        tooltip.addAll(line.hints());
        graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    private static Component browserText(String key, Object... values) {
        return Component.translatable("create_biotech.pattern_core.browser." + key, Arrays.stream(values)
            .map(value -> value instanceof Number || value instanceof Component || value instanceof Boolean || value instanceof String
                ? value : String.valueOf(value)).toArray());
    }

    public static ItemStack ingredient(PatternIngredient input) {
        ItemStack stack = input.itemId() != null ? new ItemStack(BuiltInRegistries.ITEM.get(input.itemId()))
            : BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, input.tagId()))
                .flatMap(values -> values.stream().findFirst()).map(holder -> new ItemStack(holder.value()))
                .orElse(ItemStack.EMPTY);
        if (!stack.isEmpty()) stack.applyComponents(input.components().asPatch());
        return stack;
    }

    public static void slot(GuiGraphics graphics, Font font, int x, int y, ItemStack stack,
        int amount, boolean selected) {
        PatternGuiSkin.blit(graphics, selected ? PatternGuiSkin.SLOT_SELECTED : PatternGuiSkin.SLOT, x, y, 18, 18);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + 1, y + 1);
            graphics.renderItemDecorations(font, stack, x + 1, y + 1, amount == 1 ? "" : Integer.toString(amount));
        }
    }
}
