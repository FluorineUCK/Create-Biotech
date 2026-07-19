package com.nobodiiiii.createbiotech.content.fluid;

import java.util.function.Consumer;

import org.joml.Vector3f;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

public class LiquidLivingSlimeFluidType extends FluidType {

	private static final ResourceLocation STILL_TEXTURE =
		ResourceLocation.fromNamespaceAndPath("create_biotech", "fluid/liquid_living_slime_still");
	private static final ResourceLocation FLOWING_TEXTURE =
		ResourceLocation.fromNamespaceAndPath("create_biotech", "fluid/liquid_living_slime_flow");
	private static final Vector3f SUBMERGED_FOG_COLOR = new Vector3f(0.48F, 0.86F, 0.42F);
	private static final float FOG_DISTANCE_MODIFIER = 1F / 10F;
	private static final float MOVE_SCALE = 0.011F;
	private static final float BASE_DRAG = 0.72F;
	private static final float SPRINT_DRAG = 0.78F;
	private static final double VERTICAL_DRAG = 0.75D;
	private static final double COLLISION_ASCENT = 0.2D;

	public LiquidLivingSlimeFluidType(Properties properties) {
		super(properties);
	}

	@Override
	public boolean move(FluidState state, LivingEntity entity, Vec3 movementVector, double gravity) {
		double startY = entity.getY();
		float horizontalDrag = entity.isSprinting() ? SPRINT_DRAG : BASE_DRAG;
		float movementScale = MOVE_SCALE * (float) entity.getAttributeValue(NeoForgeMod.SWIM_SPEED);

		entity.moveRelative(movementScale, movementVector);
		entity.move(MoverType.SELF, entity.getDeltaMovement());

		Vec3 movement = entity.getDeltaMovement();
		if (entity.horizontalCollision && entity.onClimbable()) {
			movement = new Vec3(movement.x, COLLISION_ASCENT, movement.z);
		}

		entity.setDeltaMovement(movement.multiply(horizontalDrag, VERTICAL_DRAG, horizontalDrag));
		Vec3 adjustedMovement =
			entity.getFluidFallingAdjustedMovement(gravity, entity.getDeltaMovement().y <= 0.0D, entity.getDeltaMovement());
		entity.setDeltaMovement(adjustedMovement);

		if (entity.horizontalCollision
			&& entity.isFree(adjustedMovement.x, adjustedMovement.y + 0.6D - entity.getY() + startY,
				adjustedMovement.z)) {
			entity.setDeltaMovement(adjustedMovement.x, COLLISION_ASCENT, adjustedMovement.z);
		}

		return true;
	}

	@Override
	public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
		consumer.accept(new IClientFluidTypeExtensions() {

			@Override
			public ResourceLocation getStillTexture() {
				return STILL_TEXTURE;
			}

			@Override
			public ResourceLocation getFlowingTexture() {
				return FLOWING_TEXTURE;
			}

			@Override
			public int getTintColor() {
				return 0xFFFFFFFF;
			}

			@Override
			public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
				return 0xFFFFFFFF;
			}

			@Override
			public int getTintColor(FluidStack stack) {
				return 0xFFFFFFFF;
			}

			@Override
			public Vector3f modifyFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance,
				float darkenWorldAmount, Vector3f fluidFogColor) {
				return SUBMERGED_FOG_COLOR;
			}

			@Override
			public void modifyFogRender(Camera camera, FogMode mode, float renderDistance, float partialTick,
				float nearDistance, float farDistance, FogShape shape) {
				RenderSystem.setShaderFogShape(FogShape.CYLINDER);
				RenderSystem.setShaderFogStart(-8.0F);
				RenderSystem.setShaderFogEnd(96.0F * FOG_DISTANCE_MODIFIER);
			}
		});
	}
}
