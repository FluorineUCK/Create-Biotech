package com.nobodiiiii.createbiotech.foundation.feature;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class CBFeatureRecipeFilter {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String RECIPE_PREFIX = "recipe/";
	private static final String JSON_SUFFIX = ".json";
	private static final String CONDITIONS_KEY = "neoforge:conditions";
	private static final String CONDITION_TYPE = CreateBiotech.MOD_ID + ":feature_enabled";

	private CBFeatureRecipeFilter() {}

	@SubscribeEvent
	public static void onServerAboutToStart(ServerAboutToStartEvent event) {
		Map<ResourceLocation, EnumSet<CBFeature>> recipeFeatures =
			findFeatureRecipes(event.getServer().getResourceManager());
		if (recipeFeatures.isEmpty())
			return;

		RecipeManager recipeManager = event.getServer().getRecipeManager();
		List<RecipeHolder<?>> enabledRecipes = recipeManager.getRecipes()
			.stream()
			.filter(recipe -> isEnabled(recipe.id(), recipeFeatures))
			.toList();
		int removed = recipeManager.getRecipes().size() - enabledRecipes.size();
		if (removed == 0)
			return;

		recipeManager.replaceRecipes(enabledRecipes);
		LOGGER.info("Removed {} recipes disabled by Create: Biotech feature switches", removed);
	}

	private static boolean isEnabled(ResourceLocation recipeId,
		Map<ResourceLocation, EnumSet<CBFeature>> recipeFeatures) {
		EnumSet<CBFeature> features = recipeFeatures.get(recipeId);
		return features == null || features.stream().allMatch(CBFeature::isEnabled);
	}

	private static Map<ResourceLocation, EnumSet<CBFeature>> findFeatureRecipes(ResourceManager resourceManager) {
		Map<ResourceLocation, EnumSet<CBFeature>> recipeFeatures = new HashMap<>();
		Map<ResourceLocation, Resource> resources = resourceManager.listResources("recipe", id ->
			CreateBiotech.MOD_ID.equals(id.getNamespace()) && id.getPath().endsWith(JSON_SUFFIX));

		for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
			ResourceLocation resourceId = entry.getKey();
			ResourceLocation recipeId = toRecipeId(resourceId);
			if (recipeId == null)
				continue;

			try (BufferedReader reader = entry.getValue().openAsReader()) {
				readFeatures(JsonParser.parseReader(reader), recipeId, recipeFeatures);
			} catch (IOException | RuntimeException exception) {
				LOGGER.warn("Could not inspect feature conditions in recipe resource {}", resourceId, exception);
			}
		}
		return recipeFeatures;
	}

	private static ResourceLocation toRecipeId(ResourceLocation resourceId) {
		String path = resourceId.getPath();
		if (!path.startsWith(RECIPE_PREFIX) || !path.endsWith(JSON_SUFFIX))
			return null;
		String recipePath = path.substring(RECIPE_PREFIX.length(), path.length() - JSON_SUFFIX.length());
		return ResourceLocation.fromNamespaceAndPath(resourceId.getNamespace(), recipePath);
	}

	private static void readFeatures(JsonElement json, ResourceLocation recipeId,
		Map<ResourceLocation, EnumSet<CBFeature>> recipeFeatures) {
		if (!json.isJsonObject())
			return;
		JsonElement conditionsElement = json.getAsJsonObject().get(CONDITIONS_KEY);
		if (conditionsElement == null || !conditionsElement.isJsonArray())
			return;

		for (JsonElement element : conditionsElement.getAsJsonArray()) {
			if (!element.isJsonObject())
				continue;
			JsonObject condition = element.getAsJsonObject();
			if (!condition.has("type") || !CONDITION_TYPE.equals(condition.get("type").getAsString())
				|| !condition.has("feature"))
				continue;
			CBFeature feature = CBFeature.bySerializedName(condition.get("feature").getAsString());
			if (feature != null)
				recipeFeatures.computeIfAbsent(recipeId, ignored -> EnumSet.noneOf(CBFeature.class)).add(feature);
		}
	}
}
