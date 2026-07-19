package com.nobodiiiii.createbiotech.registry;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.registries.BuiltInRegistries;

import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.register.ModFluids;
import com.nobodiiiii.createbiotech.content.fluid.LiquidLivingSlimeFluidType;
import com.nobodiiiii.createbiotech.content.fluid.TeleportationFluid;
import com.nobodiiiii.createbiotech.content.fluid.TeleportationLiquidBlock;
import com.simibubi.create.content.fluids.VirtualFluid;
import com.tterrag.registrate.util.entry.FluidEntry;
import org.joml.Vector3f;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import net.neoforged.neoforge.registries.DeferredHolder;

public class CBFluids {

	public static final DeferredRegister<FluidType> FLUID_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CreateBiotech.MOD_ID);

	public static final DeferredRegister<Fluid> FLUIDS =
		DeferredRegister.create(BuiltInRegistries.FLUID, CreateBiotech.MOD_ID);

	public static final DeferredRegister<Block> FLUID_BLOCKS =
		DeferredRegister.create(Registries.BLOCK, CreateBiotech.MOD_ID);

	public static final DeferredRegister<Item> FLUID_ITEMS =
		DeferredRegister.create(BuiltInRegistries.ITEM, CreateBiotech.MOD_ID);

	private static final ResourceLocation EXPERIENCE_STILL_TEXTURE =
		CreateBiotech.asResource("fluid/experience_still");
	private static final ResourceLocation EXPERIENCE_FLOW_TEXTURE =
		CreateBiotech.asResource("fluid/experience_flow");
	private static final ResourceLocation NETHER_PORTAL_TEXTURE =
		ResourceLocation.fromNamespaceAndPath("minecraft", "block/nether_portal");
	private static final Vector3f TELEPORTATION_SUBMERGED_FOG_COLOR = new Vector3f(0.72F, 0.48F, 0.86F);
	private static final float TELEPORTATION_FOG_DISTANCE_MODIFIER = 1F / 10F;

	public static final DeferredHolder<FluidType, FluidType> EXPERIENCE_TYPE =
		FLUID_TYPES.register("experience",
			() -> new FluidType(FluidType.Properties.create()
				.lightLevel(15)) {
				@Override
				public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
					consumer.accept(new IClientFluidTypeExtensions() {
						@Override
						public ResourceLocation getStillTexture() {
							return EXPERIENCE_STILL_TEXTURE;
						}

						@Override
						public ResourceLocation getFlowingTexture() {
							return EXPERIENCE_FLOW_TEXTURE;
						}
					});
				}
			});

	public static final DeferredHolder<Fluid, VirtualFluid> EXPERIENCE =
		FLUIDS.register("experience", () -> VirtualFluid.createSource(experienceProperties()));

	public static final DeferredHolder<Fluid, VirtualFluid> EXPERIENCE_FLOWING =
		FLUIDS.register("flowing_experience", () -> VirtualFluid.createFlowing(experienceProperties()));

	public static final DeferredHolder<FluidType, FluidType> TELEPORTATION_TYPE =
		FLUID_TYPES.register("teleportation",
			() -> new FluidType(FluidType.Properties.create()
				.sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL_LAVA)
				.sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY_LAVA)
				.density(3000)
				.viscosity(6000)
				.lightLevel(11)) {
				@Override
				public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
					consumer.accept(new IClientFluidTypeExtensions() {
						@Override
						public ResourceLocation getStillTexture() {
							return NETHER_PORTAL_TEXTURE;
						}

						@Override
						public ResourceLocation getFlowingTexture() {
							return NETHER_PORTAL_TEXTURE;
						}

						@Override
						public Vector3f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
							int renderDistance, float darkenWorldAmount, Vector3f fluidFogColor) {
							return TELEPORTATION_SUBMERGED_FOG_COLOR;
						}

						@Override
						public void modifyFogRender(Camera camera, FogMode mode, float renderDistance, float partialTick,
							float nearDistance, float farDistance, FogShape shape) {
							RenderSystem.setShaderFogShape(FogShape.CYLINDER);
							RenderSystem.setShaderFogStart(-8.0F);
							RenderSystem.setShaderFogEnd(96.0F * TELEPORTATION_FOG_DISTANCE_MODIFIER);
						}
					});
				}
			});

	public static final DeferredHolder<Fluid, TeleportationFluid.Source> TELEPORTATION =
		FLUIDS.register("teleportation", () -> new TeleportationFluid.Source(teleportationProperties()));

	public static final DeferredHolder<Fluid, TeleportationFluid.Flowing> TELEPORTATION_FLOWING =
		FLUIDS.register("flowing_teleportation", () -> new TeleportationFluid.Flowing(teleportationProperties()));

	public static final DeferredHolder<Block, TeleportationLiquidBlock> TELEPORTATION_BLOCK =
		FLUID_BLOCKS.register("teleportation",
			() -> new TeleportationLiquidBlock(TELEPORTATION.get(), Block.Properties.of()
				.mapColor(MapColor.COLOR_PURPLE)
				.replaceable()
				.noCollission()
				.strength(100.0F)
				.lightLevel(state -> 11)
				.pushReaction(PushReaction.DESTROY)
				.noLootTable()
				.liquid()
				.sound(SoundType.EMPTY)));

	public static final DeferredHolder<Item, BucketItem> TELEPORTATION_BUCKET =
		FLUID_ITEMS.register("teleportation_bucket",
			() -> new BucketItem(TELEPORTATION.get(), new Item.Properties()
				.craftRemainder(Items.BUCKET)
				.stacksTo(1)));

	public static final DeferredHolder<FluidType, LiquidLivingSlimeFluidType> LIQUID_LIVING_SLIME_TYPE =
		FLUID_TYPES.register("liquid_living_slime",
			() -> new LiquidLivingSlimeFluidType(FluidType.Properties.create()
				.motionScale(0.004D)
				.fallDistanceModifier(0F)
				.sound(SoundActions.BUCKET_FILL, SoundEvents.SLIME_JUMP)
				.sound(SoundActions.BUCKET_EMPTY, SoundEvents.SLIME_JUMP)
				.viscosity(5000)
				.density(1400)));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> LIQUID_LIVING_SLIME =
		FLUIDS.register("liquid_living_slime",
			() -> new BaseFlowingFluid.Source(CBFluids.liquidLivingSlimeProperties()));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> LIQUID_LIVING_SLIME_FLOWING =
		FLUIDS.register("liquid_living_slime_flowing",
			() -> new BaseFlowingFluid.Flowing(CBFluids.liquidLivingSlimeProperties()));

	public static final DeferredHolder<Block, LiquidBlock> LIQUID_LIVING_SLIME_BLOCK =
		FLUID_BLOCKS.register("liquid_living_slime",
			() -> new LiquidBlock((net.minecraft.world.level.material.FlowingFluid) LIQUID_LIVING_SLIME.get(),
				Block.Properties.of()
				.noCollission()
				.sound(SoundType.SLIME_BLOCK)
				.strength(100f)
				.noLootTable()
				.liquid()));

	public static final DeferredHolder<Item, BucketItem> LIQUID_LIVING_SLIME_BUCKET =
		FLUID_ITEMS.register("liquid_living_slime_bucket",
			() -> new BucketItem(LIQUID_LIVING_SLIME.get(), new Item.Properties()
				.craftRemainder(Items.BUCKET)
				.stacksTo(1)));

	// Butter Cat content is registered through the shared ButterCat registrate, and re-exported
	// here so the project's primary fluid registry remains the place to inspect mod fluids.
	public static final FluidEntry<BaseFlowingFluid.Flowing> CREAM = ModFluids.CREAM;

	private static BaseFlowingFluid.Properties experienceProperties() {
		return new BaseFlowingFluid.Properties(EXPERIENCE_TYPE, EXPERIENCE, EXPERIENCE_FLOWING);
	}

	private static BaseFlowingFluid.Properties teleportationProperties() {
		return new BaseFlowingFluid.Properties(TELEPORTATION_TYPE, TELEPORTATION, TELEPORTATION_FLOWING)
			.bucket(TELEPORTATION_BUCKET)
			.block(TELEPORTATION_BLOCK)
			.levelDecreasePerBlock(2)
			.tickRate(30)
			.slopeFindDistance(2)
			.explosionResistance(100f);
	}

	private static BaseFlowingFluid.Properties liquidLivingSlimeProperties() {
		return new BaseFlowingFluid.Properties(
			LIQUID_LIVING_SLIME_TYPE,
			LIQUID_LIVING_SLIME,
			LIQUID_LIVING_SLIME_FLOWING)
			.bucket(LIQUID_LIVING_SLIME_BUCKET)
			.block(LIQUID_LIVING_SLIME_BLOCK)
			.levelDecreasePerBlock(2)
			.tickRate(60)
			.slopeFindDistance(4)
			.explosionResistance(100f);
	}

	private CBFluids() {}

	public static void register(IEventBus modEventBus) {
		FLUID_TYPES.register(modEventBus);
		FLUIDS.register(modEventBus);
		FLUID_BLOCKS.register(modEventBus);
		FLUID_ITEMS.register(modEventBus);
	}
}
