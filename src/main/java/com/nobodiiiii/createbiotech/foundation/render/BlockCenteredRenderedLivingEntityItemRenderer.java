package com.nobodiiiii.createbiotech.foundation.render;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.foundation.item.RenderedLivingEntityItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * Renders an entity in block-model coordinates. The center of the entity's actual
 * rendered vertices is mapped to the center of a unit cube, so vanilla block-item
 * transforms rotate and offset every display context around the same point.
 */
public class BlockCenteredRenderedLivingEntityItemRenderer<T extends LivingEntity>
	extends BlockEntityWithoutLevelRenderer {

	private static final Vector3f BLOCK_CENTER = new Vector3f(0.5f, 0.5f, 0.5f);
	private static final float DEFAULT_ENTITY_Y_ROTATION = 90.0f;
	private static final float FIXED_ENTITY_Y_ROTATION = 180.0f;

	private final RenderedLivingEntityItem<T> item;

	@Nullable
	private T cachedEntity;
	@Nullable
	private Level cachedLevel;

	public static <T extends LivingEntity> IClientItemExtensions create(RenderedLivingEntityItem<T> item) {
		return new IClientItemExtensions() {
			private final BlockEntityWithoutLevelRenderer renderer =
				new BlockCenteredRenderedLivingEntityItemRenderer<>(item);

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return renderer;
			}
		};
	}

	private BlockCenteredRenderedLivingEntityItemRenderer(RenderedLivingEntityItem<T> item) {
		super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
		this.item = item;
	}

	@Override
	public void renderByItem(ItemStack stack, ItemDisplayContext transformType, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int overlay) {
		Level level = Minecraft.getInstance().level;
		if (level == null)
			return;

		T entity = getOrCreateEntity(level);
		if (entity == null)
			return;

		item.configureRenderedEntity(entity, stack, transformType);
		Vector3f geometryCenter = measureGeometryCenter(entity);
		float scaleMultiplier = item.getRenderedEntityScaleMultiplier();
		float yRotation = getBaseYRotation(transformType);
		yRotation += item.getRenderedEntityYRotation(stack, transformType);

		poseStack.pushPose();
		poseStack.translate(BLOCK_CENTER.x, BLOCK_CENTER.y, BLOCK_CENTER.z);
		poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));
		poseStack.scale(scaleMultiplier, scaleMultiplier, scaleMultiplier);
		poseStack.translate(-geometryCenter.x, -geometryCenter.y, -geometryCenter.z);
		renderEntity(entity, poseStack, buffer, packedLight);
		poseStack.popPose();
	}

	private static float getBaseYRotation(ItemDisplayContext displayContext) {
		if (displayContext == ItemDisplayContext.FIXED)
			return FIXED_ENTITY_Y_ROTATION;
		if (displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
			|| displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
			return -DEFAULT_ENTITY_Y_ROTATION;
		return DEFAULT_ENTITY_Y_ROTATION;
	}

	private static Vector3f measureGeometryCenter(LivingEntity entity) {
		GeometryBounds bounds = new GeometryBounds();
		MultiBufferSource measuringBuffer = renderType -> new GeometryBoundsVertexConsumer(bounds);
		renderEntity(entity, new PoseStack(), measuringBuffer, LightTexture.FULL_BRIGHT);
		if (bounds.hasVertices())
			return bounds.center();

		EntityDimensions dimensions = entity.getDimensions(entity.getPose());
		return new Vector3f(0, dimensions.height() / 2.0f, 0);
	}

	private static void renderEntity(LivingEntity entity, PoseStack poseStack, MultiBufferSource buffer,
		int packedLight) {
		EntityRenderHelper.render(EntityRenderHelper.settings(entity)
			.packedLight(packedLight)
			.partialTicks(1.0f)
			.dispatcherYaw(0.0f)
			.yaw(0.0f)
			.bodyYaw(0.0f)
			.headYaw(0.0f)
			.pitch(0.0f)
			.flushBuffers(false), poseStack, buffer);
	}

	@Nullable
	private T getOrCreateEntity(Level level) {
		if (cachedEntity != null && cachedLevel == level)
			return cachedEntity;

		T entity = item.getRenderedEntityType().create(level);
		if (entity == null)
			return null;

		item.configureRenderedEntity(entity);
		if (entity instanceof Mob mob)
			mob.setNoAi(true);
		entity.setSilent(true);
		entity.setOnGround(true);
		entity.tickCount = 0;
		entity.hurtTime = 0;
		entity.deathTime = 0;
		entity.setYRot(0.0f);
		entity.yRotO = 0.0f;
		entity.setXRot(0.0f);
		entity.xRotO = 0.0f;
		entity.setYBodyRot(0.0f);
		entity.yBodyRotO = 0.0f;
		entity.yHeadRot = 0.0f;
		entity.yHeadRotO = 0.0f;

		cachedLevel = level;
		cachedEntity = entity;
		return entity;
	}

	private static class GeometryBounds {
		private float minX = Float.POSITIVE_INFINITY;
		private float minY = Float.POSITIVE_INFINITY;
		private float minZ = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;
		private float maxY = Float.NEGATIVE_INFINITY;
		private float maxZ = Float.NEGATIVE_INFINITY;

		private void include(Vector3f vertex) {
			minX = Math.min(minX, vertex.x());
			minY = Math.min(minY, vertex.y());
			minZ = Math.min(minZ, vertex.z());
			maxX = Math.max(maxX, vertex.x());
			maxY = Math.max(maxY, vertex.y());
			maxZ = Math.max(maxZ, vertex.z());
		}

		private boolean hasVertices() {
			return minX != Float.POSITIVE_INFINITY;
		}

		private Vector3f center() {
			return new Vector3f((minX + maxX) / 2.0f, (minY + maxY) / 2.0f, (minZ + maxZ) / 2.0f);
		}
	}

	private static class GeometryBoundsVertexConsumer implements VertexConsumer {
		private final GeometryBounds bounds;

		private GeometryBoundsVertexConsumer(GeometryBounds bounds) {
			this.bounds = bounds;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			bounds.include(new Vector3f(x, y, z));
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}
	}
}
