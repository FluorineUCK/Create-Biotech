package com.nobodiiiii.createbiotech.content.experience;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.registries.BuiltInRegistries;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBFluids;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;


public final class ExperienceFluidHelper {
	private static final String CEI_MOD_ID = "create_enchantment_industry";
	private static final ResourceLocation OWN_EXPERIENCE = CreateBiotech.asResource("experience");
	private static final ResourceLocation OWN_FLOWING_EXPERIENCE = CreateBiotech.asResource("flowing_experience");
	private static final ResourceLocation CEI_EXPERIENCE =
		ResourceLocation.fromNamespaceAndPath("create_enchantment_industry", "experience");
	private static final ResourceLocation CEI_FLOWING_EXPERIENCE =
		ResourceLocation.fromNamespaceAndPath("create_enchantment_industry", "flowing_experience");

	private ExperienceFluidHelper() {
	}

	public static FluidStack experienceStack(int amount) {
		if (amount <= 0)
			return FluidStack.EMPTY;
		return new FluidStack(generatedExperienceFluid(), amount);
	}

	public static boolean isExperience(FluidStack stack) {
		return !stack.isEmpty() && isExperience(stack.getFluid());
	}

	public static boolean isExperience(Fluid fluid) {
		ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
		return OWN_EXPERIENCE.equals(id)
			|| OWN_FLOWING_EXPERIENCE.equals(id)
			|| CEI_EXPERIENCE.equals(id)
			|| CEI_FLOWING_EXPERIENCE.equals(id);
	}

	public static boolean isOwnExperience(FluidStack stack) {
		if (stack.isEmpty())
			return false;
		ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
		return OWN_EXPERIENCE.equals(id) || OWN_FLOWING_EXPERIENCE.equals(id);
	}

	public static boolean isGeneratedExperience(FluidStack stack) {
		return !stack.isEmpty() && stack.getFluid() == generatedExperienceFluid();
	}

	private static Fluid generatedExperienceFluid() {
		if (ModList.get().isLoaded(CEI_MOD_ID)) {
			return BuiltInRegistries.FLUID.getOptional(CEI_EXPERIENCE)
				.orElseGet(CBFluids.EXPERIENCE::get);
		}
		return CBFluids.EXPERIENCE.get();
	}

	public static int fluidAmountToXp(int amount) {
		return Math.max(0, amount);
	}

	public static int xpToFluidAmount(int xp) {
		return Math.max(0, xp);
	}
}
