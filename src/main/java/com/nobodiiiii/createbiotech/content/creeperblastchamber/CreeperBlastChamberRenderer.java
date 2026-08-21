package com.nobodiiiii.createbiotech.content.creeperblastchamber;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.render.BoundedRenderEntityCache;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;
import com.nobodiiiii.createbiotech.mixin.client.CreeperAccessor;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CreeperBlastChamberRenderer implements BlockEntityRenderer<CreeperBlastChamberBlockEntity> {

	private static final float CREEPER_ANIMATION_Y_OFFSET = .12f;
	/** Scale a creeper pops in from, and shrinks back to on the way out. */
	private static final float CREEPER_ENTRY_START_SCALE = .35f;
	private static final float CREEPER_EXIT_END_SCALE = .35f;
	private static final float CREEPER_FINAL_HEIGHT_SCALE = 1f / 1.8f;
	private static final float CREEPER_MAX_SPREAD = .2f;

	/** Highest swell value fed to the model; {@code Creeper.maxSwell - 2} is what vanilla divides by. */
	private static final float MAX_RENDER_SWELL = 24f;
	private static final float SWELL_DIVISOR = 28f;
	/**
	 * {@code CreeperRenderer.scale} multiplies in its own swell-driven bulge on top of whatever the
	 * caller already applied. These mirror its coefficients so the compression below can divide them
	 * back out and land on the width and height actually asked for.
	 */
	private static final float VANILLA_SWELL_SPREAD = .4f;
	private static final float VANILLA_SWELL_RISE = .1f;

	private static final float IDLE_WALK_SPEED = .3f;
	private static final float IDLE_WALK_AMPLITUDE = .28f;
	private static final float IDLE_HEAD_SWAY_DEGREES = 11f;

	/**
	 * Charged creepers get their energy swirl UV from {@code tickCount}, and every distinct value
	 * builds its own {@code RenderType}. Bucketing the offsets keeps a full chamber down to a handful
	 * of extra batches instead of one per creeper.
	 */
	private static final int SWIRL_PHASE_BUCKETS = 3;
	private static final int SWIRL_PHASE_SPACING = 13;

	private static final double CREEPER_RENDER_DISTANCE = 32d;
	private static final int MAX_CACHED_CREEPERS = 16 * 9;
	private static final PartialModel DISPLAY_PANEL =
		PartialModel.of(CreateBiotech.asResource("block/blast_chamber_display/panel"));
	private static final PartialModel DISPLAY_DIAL =
		PartialModel.of(CreateBiotech.asResource("block/blast_chamber_display/dial"));
	private static final PartialModel CREEPER_FACE =
		PartialModel.of(CreateBiotech.asResource("block/blast_chamber_display/creeper_face"));
	private static final BoundedRenderEntityCache<CreeperCacheKey, Creeper> CREEPER_CACHE =
		new BoundedRenderEntityCache<>(MAX_CACHED_CREEPERS, (level, key) -> {
			Entity entity = CapturedEntityBoxHelper.createCapturedEntity(key.payload, level);
			if (!(entity instanceof Creeper creeper))
				return null;
			// The key pins the packager, so the position never changes for the life of this entry.
			creeper.setPos(key.packagerPos.getX() + .5d, key.packagerPos.getY() + 1d, key.packagerPos.getZ() + .5d);
			return creeper;
		});

	public CreeperBlastChamberRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public AABB getRenderBoundingBox(CreeperBlastChamberBlockEntity blockEntity) {
		return blockEntity.getRenderBoundingBox();
	}

	@Override
	public void render(CreeperBlastChamberBlockEntity be, float partialTicks, PoseStack poseStack,
		MultiBufferSource buffer, int light, int overlay) {
		Level level = be.getLevel();
		if (level == null)
			return;

		BlockState blockState = be.getBlockState();
		VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
		float progress = be.displayGauge.getValue(partialTicks);
		if (be.isStructureValid()) {
			renderFormedPanels(be, poseStack, vertices, blockState, level, progress);
			if (be.shouldRenderCreeperFace())
				renderCreeperFace(be, poseStack, vertices, blockState, level);
			renderContainedCreepers(be, partialTicks, poseStack, buffer);
			return;
		}
		renderStandalonePanels(be, poseStack, vertices, blockState, level, progress);
	}

	private void renderFormedPanels(CreeperBlastChamberBlockEntity be, PoseStack poseStack, VertexConsumer vertices,
		BlockState blockState, Level level, float progress) {
		BlockPos origin = be.getStructureOrigin();
		if (origin == null)
			return;
		int size = be.getStructureSize();
		double centerLine = size / 2d;
		for (Direction direction : Iterate.horizontalDirections) {
			if (isFormedPanelBlocked(level, origin, size, direction))
				continue;
			double renderX = origin.getX() + (direction.getAxis() == Direction.Axis.X
				? direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size - .5d : .5d : centerLine);
			double renderY = origin.getY() + .5d;
			double renderZ = origin.getZ() + (direction.getAxis() == Direction.Axis.Z
				? direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size - .5d : .5d : centerLine);
			BlockPos lightPos = origin.offset(
				direction.getAxis() == Direction.Axis.X
					? direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size - 1 : 0
					: Mth.clamp(Mth.floor(centerLine), 0, size - 1), 0,
				direction.getAxis() == Direction.Axis.Z
					? direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size - 1 : 0
					: Mth.clamp(Mth.floor(centerLine), 0, size - 1));
			poseStack.pushPose();
			poseStack.translate(renderX - be.getBlockPos().getX(), renderY - be.getBlockPos().getY(),
				renderZ - be.getBlockPos().getZ());
			renderGauge(poseStack, vertices, blockState, direction,
				LevelRenderer.getLightColor(level, lightPos.relative(direction)), progress);
			poseStack.popPose();
		}
	}

	private boolean isFormedPanelBlocked(Level level, BlockPos origin, int size, Direction side) {
		int lowerCenter = (size - 1) / 2;
		int upperCenter = size / 2;
		int x = side.getAxis() == Direction.Axis.X
			? origin.getX() + (side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size : -1) : 0;
		int z = side.getAxis() == Direction.Axis.Z
			? origin.getZ() + (side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size : -1) : 0;
		if (side.getAxis() == Direction.Axis.X) {
			for (int zOffset = lowerCenter; zOffset <= upperCenter; zOffset++)
				if (!level.isEmptyBlock(new BlockPos(x, origin.getY(), origin.getZ() + zOffset)))
					return true;
			return false;
		}
		for (int xOffset = lowerCenter; xOffset <= upperCenter; xOffset++)
			if (!level.isEmptyBlock(new BlockPos(origin.getX() + xOffset, origin.getY(), z)))
				return true;
		return false;
	}

	private void renderStandalonePanels(CreeperBlastChamberBlockEntity be, PoseStack poseStack,
		VertexConsumer vertices, BlockState blockState, Level level, float progress) {
		BlockPos pos = be.getBlockPos();
		for (Direction direction : Iterate.horizontalDirections) {
			poseStack.pushPose();
			poseStack.translate(.5, .5, .5);
			renderGauge(poseStack, vertices, blockState, direction,
				LevelRenderer.getLightColor(level, pos.relative(direction)), progress);
			poseStack.popPose();
		}
	}

	private void renderGauge(PoseStack poseStack, VertexConsumer vertices, BlockState blockState, Direction side,
		int light, float progress) {
		float dialPivotY = 6f / 16;
		float dialPivotZ = 8f / 16;
		float yRot = -side.toYRot() - 90;
		CachedBuffers.partial(DISPLAY_PANEL, blockState).rotateYDegrees(yRot).uncenter()
			.translate(.5f - 6f / 16f, 0, 0).light(light).renderInto(poseStack, vertices);
		CachedBuffers.partial(DISPLAY_DIAL, blockState).rotateYDegrees(yRot).uncenter()
			.translate(.5f - 6f / 16f, 0, 0).translate(0, dialPivotY, dialPivotZ)
			.rotateXDegrees(-145 * progress + 90).translate(0, -dialPivotY, -dialPivotZ)
			.light(light).renderInto(poseStack, vertices);
	}

	private void renderCreeperFace(CreeperBlastChamberBlockEntity be, PoseStack poseStack, VertexConsumer vertices,
		BlockState blockState, Level level) {
		BlockPos pos = be.getBlockPos();
		for (Direction direction : Iterate.horizontalDirections) {
			poseStack.pushPose();
			poseStack.translate(.5, .5, .5);
			CachedBuffers.partial(CREEPER_FACE, blockState).rotateYDegrees(-direction.toYRot() - 90).uncenter()
				.light(LevelRenderer.getLightColor(level, pos.relative(direction))).renderInto(poseStack, vertices);
			poseStack.popPose();
		}
	}

	private void renderContainedCreepers(CreeperBlastChamberBlockEntity be, float partialTicks,
		PoseStack poseStack, MultiBufferSource buffer) {
		if (isBeyondCreeperRenderDistance(be))
			return;

		var working = be.getWorkingRenderCreepers();
		var animations = be.getRenderAnimations();
		if (working.isEmpty() && animations.isEmpty())
			return;

		// One fancy-graphics scope and one lambda for the whole chamber rather than one per creeper.
		EntityRenderHelper.batch(() -> {
			for (CreeperBlastChamberBlockEntity.RenderManagedCreeper subject : working)
				renderProxy(be, subject.packagerPos(), subject.payload(), subject.renderSeed(), partialTicks,
					0, 1, false, be.getWorkingCreeperCompression(subject.packagerPos(), partialTicks), poseStack,
					buffer);

			for (CreeperBlastChamberBlockEntity.RenderCreeperAnimation animation : animations)
				renderProxy(be, animation.packagerPos(), animation.payload(), animation.renderSeed(), partialTicks,
					animation.ticksRemaining(), animation.totalTicks(), animation.exiting(), 0, poseStack, buffer);
		});
	}

	/**
	 * The chamber is a sealed box, so the creepers inside it are barely legible from far away while
	 * still costing a full model plus, for charged ones, an extra swirl pass.
	 */
	private boolean isBeyondCreeperRenderDistance(CreeperBlastChamberBlockEntity be) {
		Vec3 camera = Minecraft.getInstance()
			.gameRenderer.getMainCamera()
			.getPosition();
		BlockPos pos = be.getBlockPos();
		return camera.distanceToSqr(pos.getX() + .5d, pos.getY() + .5d, pos.getZ() + .5d)
			> CREEPER_RENDER_DISTANCE * CREEPER_RENDER_DISTANCE;
	}

	private void renderProxy(CreeperBlastChamberBlockEntity be, BlockPos packagerPos, ItemStack payload,
		long renderSeed, float partialTicks, int ticksRemaining, int totalTicks, boolean exiting, float compression,
		PoseStack poseStack, MultiBufferSource buffer) {
		Level level = be.getLevel();
		if (level == null || payload.isEmpty())
			return;
		Creeper creeper = CREEPER_CACHE.get(level,
			new CreeperCacheKey(be.getBlockPos(), packagerPos, renderSeed, payload));
		if (creeper == null)
			return;

		float scale = 1;
		float yOffset = 0;
		if (totalTicks > 1) {
			float linear = Mth.clamp((totalTicks - ticksRemaining + partialTicks) / totalTicks, 0, 1);
			float progress = linear * linear * (3 - 2 * linear);
			scale = exiting ? Mth.lerp(progress, 1, CREEPER_EXIT_END_SCALE)
				: Mth.lerp(progress, CREEPER_ENTRY_START_SCALE, 1);
			yOffset = exiting ? Mth.lerp(progress, 0, -CREEPER_ANIMATION_Y_OFFSET)
				: Mth.lerp(progress, -CREEPER_ANIMATION_Y_OFFSET, 0);
		}

		float renderTime = AnimationTickHolder.getRenderTime(level);
		float pulse = .5f + .5f * Mth.sin(renderTime * .9f + (renderSeed & 31) * .07f);
		float swellValue = Mth.clamp(compression * Mth.lerp(pulse, .55f, 1f), 0, 1) * MAX_RENDER_SWELL;
		int swellFloor = Mth.floor(swellValue);
		float swellFraction = swellValue - swellFloor;

		CreeperAccessor accessor = (CreeperAccessor) creeper;
		int oldSwell = accessor.createBiotech$getOldSwell();
		int swell = accessor.createBiotech$getSwell();
		boolean charged = creeper.isPowered();

		// Straddling two swell values lets Mth.lerp inside Creeper.getSwelling recover the fraction,
		// which turns the strobe in getWhiteOverlayProgress from 25 steps into a continuous ramp.
		// Charged creepers keep real partial ticks instead, because their swirl layer reads them too.
		float renderPartialTicks = charged ? partialTicks : swellFraction;
		accessor.createBiotech$setOldSwell(swellFloor);
		accessor.createBiotech$setSwell(charged ? swellFloor : swellFloor + 1);

		float appliedSwell = Mth.clamp((charged ? swellFloor : swellValue) / SWELL_DIVISOR, 0f, 1f);
		float swellBulge = appliedSwell * appliedSwell * appliedSwell * appliedSwell;
		float horizontal = (1 + CREEPER_MAX_SPREAD * compression) / (1 + VANILLA_SWELL_SPREAD * swellBulge);
		float vertical = (1 + (CREEPER_FINAL_HEIGHT_SCALE - 1) * compression) / (1 + VANILLA_SWELL_RISE * swellBulge);

		float yaw = Math.floorMod(renderSeed, 360L);
		float idlePhase = renderTime * IDLE_WALK_SPEED + (renderSeed & 63) * .1f;
		float settled = 1 - compression;
		float headSway = Mth.sin(idlePhase * .5f) * IDLE_HEAD_SWAY_DEGREES * settled;

		poseStack.pushPose();
		poseStack.translate(packagerPos.getX() - be.getBlockPos().getX() + .5d,
			packagerPos.getY() - be.getBlockPos().getY() + 1d + yOffset,
			packagerPos.getZ() - be.getBlockPos().getZ() + .5d);
		poseStack.scale(scale, scale, scale);
		poseStack.scale(horizontal, vertical, horizontal);

		EntityRenderHelper.RenderSettings<Creeper> settings = EntityRenderHelper.settings(creeper)
			.packedLight(LevelRenderer.getLightColor(level, packagerPos.above()))
			.partialTicks(renderPartialTicks)
			.yaw(yaw)
			.bodyYaw(yaw)
			.headYaw(yaw + headSway)
			.pitch(0)
			.walkAnimation(idlePhase, IDLE_WALK_AMPLITUDE * settled)
			.flushBuffers(false);
		if (charged)
			settings.ticks(Mth.floor(renderTime)
				+ (int) Math.floorMod(renderSeed, SWIRL_PHASE_BUCKETS) * SWIRL_PHASE_SPACING);
		EntityRenderHelper.render(settings, poseStack, buffer);
		poseStack.popPose();

		accessor.createBiotech$setOldSwell(oldSwell);
		accessor.createBiotech$setSwell(swell);
	}

	@Override
	public boolean shouldRenderOffScreen(CreeperBlastChamberBlockEntity be) {
		return false;
	}

	private static final class CreeperCacheKey {
		private final BlockPos controllerPos;
		private final BlockPos packagerPos;
		private final long renderSeed;
		private final ItemStack payload;

		private CreeperCacheKey(BlockPos controllerPos, BlockPos packagerPos, long renderSeed, ItemStack payload) {
			this.controllerPos = controllerPos;
			this.packagerPos = packagerPos;
			this.renderSeed = renderSeed;
			this.payload = payload;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof CreeperCacheKey key && renderSeed == key.renderSeed
				&& controllerPos.equals(key.controllerPos) && packagerPos.equals(key.packagerPos);
		}

		@Override
		public int hashCode() {
			int result = controllerPos.hashCode();
			result = 31 * result + packagerPos.hashCode();
			return 31 * result + Long.hashCode(renderSeed);
		}
	}
}
