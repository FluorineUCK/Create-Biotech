package com.nobodiiiii.createbiotech.compat.jei;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.gui.GuiEntityElement;
import com.simibubi.create.AllItems;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.compat.jei.category.animations.AnimatedKinetics;
import com.simibubi.create.foundation.gui.AllGuiTextures;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.level.Level;

public class BiologicalItemApplicationJeiCategory
	extends AbstractRecipeCategory<BiologicalItemApplicationJeiRecipe> {

	public static final RecipeType<BiologicalItemApplicationJeiRecipe> TYPE =
		RecipeType.create(CreateBiotech.MOD_ID, "biological_item_application", BiologicalItemApplicationJeiRecipe.class);

	private static final int WIDTH = 177;
	private static final int HEIGHT = 60;
	private static final int ENTITY_X = 74;
	private static final int ENTITY_Y = 51;
	private static final int ENTITY_Z = 100;
	private static final float ENTITY_RENDER_SCALE = 20.0f;
	private static final double ENTITY_X_ROTATION = -15.5d;
	private static final double ENTITY_Y_ROTATION = 22.5d;
	private static final double ENTITY_LOCAL_X = 0.5d;
	private static final double ENTITY_LOCAL_Y = 0.0d;
	private static final double ENTITY_LOCAL_Z = 0.5d;

	@Nullable
	private Chicken cachedEntity;
	@Nullable
	private EntityType<? extends Chicken> cachedEntityType;
	@Nullable
	private Level cachedLevel;

	public BiologicalItemApplicationJeiCategory() {
		super(TYPE, Component.translatable("create_biotech.recipe.biological_item_application"),
			new ItemIconDrawable(AllItems.BRASS_HAND.asStack()), WIDTH, HEIGHT);
	}

	@Override
	public void setRecipe(IRecipeLayoutBuilder builder, BiologicalItemApplicationJeiRecipe recipe,
		IFocusGroup focuses) {
		builder.addSlot(RecipeIngredientRole.INPUT, 27, 38)
			.setBackground(CreateRecipeCategory.getRenderedSlot(), -1, -1)
			.addItemStack(recipe.input().copy());

		builder.addSlot(RecipeIngredientRole.INPUT, 51, 5)
			.setBackground(CreateRecipeCategory.getRenderedSlot(), -1, -1)
			.addItemStack(recipe.heldItem().copy());

		var outputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 38)
			.setBackground(CreateRecipeCategory.getRenderedSlot(), -1, -1)
			.addItemStack(recipe.output().copy());
		if (recipe.voicePackOutput()) {
			outputSlot.addRichTooltipCallback((view, tooltip) -> {
				tooltip.add(CommonComponents.EMPTY);
				tooltip.add(Component.translatable("item.create_biotech.sonic_dog_cannon.upgrades")
					.withStyle(ChatFormatting.DARK_GRAY));
				tooltip.add(CommonComponents.space()
					.append(Component.translatable("item.create_biotech.sonic_dog_cannon.upgrade.voice_pack")
						.withStyle(ChatFormatting.GRAY)));
			});
		}
	}

	@Override
	public void draw(BiologicalItemApplicationJeiRecipe recipe, IRecipeSlotsView recipeSlotsView,
		GuiGraphics graphics, double mouseX, double mouseY) {
		AllGuiTextures.JEI_SHADOW.render(graphics, 62, 47);
		AllGuiTextures.JEI_DOWN_ARROW.render(graphics, 74, 10);

		Chicken entity = getOrCreateEntity(recipe.displayedEntityType());
		if (entity == null)
			return;

		PoseStack poseStack = graphics.pose();
		poseStack.pushPose();
		poseStack.translate(ENTITY_X, ENTITY_Y, ENTITY_Z);
		poseStack.mulPose(Axis.XP.rotationDegrees((float) ENTITY_X_ROTATION));
		poseStack.mulPose(Axis.YP.rotationDegrees((float) ENTITY_Y_ROTATION));
		GuiEntityElement.of(entity)
			.lighting(AnimatedKinetics.DEFAULT_LIGHTING)
			.atLocal(ENTITY_LOCAL_X, ENTITY_LOCAL_Y, ENTITY_LOCAL_Z)
			.scale(ENTITY_RENDER_SCALE)
			.packedLight(LightTexture.FULL_BRIGHT)
			.partialTicks(1.0f)
			.render(graphics);
		poseStack.popPose();
	}

	@Override
	public ResourceLocation getRegistryName(BiologicalItemApplicationJeiRecipe recipe) {
		return recipe.id();
	}

	private @Nullable Chicken getOrCreateEntity(EntityType<? extends Chicken> entityType) {
		Level level = Minecraft.getInstance().level;
		if (level == null)
			return null;
		if (cachedEntity != null && cachedLevel == level && cachedEntityType == entityType)
			return cachedEntity;

		Chicken entity = entityType.create(level);
		if (entity == null)
			return null;
		entity.setNoAi(true);
		entity.setSilent(true);
		entity.setOnGround(true);
		entity.tickCount = 0;
		entity.hurtTime = 0;
		entity.deathTime = 0;

		cachedLevel = level;
		cachedEntityType = entityType;
		cachedEntity = entity;
		return entity;
	}
}
