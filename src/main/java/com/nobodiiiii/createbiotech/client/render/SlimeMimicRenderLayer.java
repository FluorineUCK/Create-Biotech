package com.nobodiiiii.createbiotech.client.render;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.mixin.client.CompositeRenderStateAccessor;
import com.nobodiiiii.createbiotech.mixin.client.CompositeRenderTypeAccessor;
import com.nobodiiiii.createbiotech.mixin.client.ModelPartAccessor;
import com.nobodiiiii.createbiotech.mixin.client.TextureStateShardAccessor;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

public class SlimeMimicRenderLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ResourceLocation SLIME_TEXTURE = ResourceLocation.parse("textures/entity/slime/slime.png");
	private static final float SLIME_MODEL_WIDTH = 8.0f;
	private static final float SLIME_MODEL_CENTER_Y = 20.0f / 16.0f;
	private static final float OUTER_CUBE_INFLATE_PIXELS = 0.1f;
	private static final float FLAT_CUBE_THRESHOLD_PIXELS = 0.05f;
	private static final float FLAT_CUBE_FILTER_ALPHA = 0.55f;
	private static final float FLAT_CUBE_FILTER_NORMAL_OFFSET = 1.0f / 1024.0f;
	private static final float INNER_RED = 1.0f;
	private static final float INNER_GREEN = 1.0f;
	private static final float INNER_BLUE = 1.0f;
	private static final float INNER_ALPHA = 1.0f;
	private static final float OUTER_RED = 1.0f;
	private static final float OUTER_GREEN = 1.0f;
	private static final float OUTER_BLUE = 1.0f;
	private static final float OUTER_ALPHA = 1.0f;
	private static final float OVERLAY_RED = 0.72f;
	private static final float OVERLAY_GREEN = 1.0f;
	private static final float OVERLAY_BLUE = 0.72f;
	private static final float OVERLAY_ALPHA = 0.28f;

	private static final ThreadLocal<Deque<RenderContext>> RENDER_CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<Integer> INTERNAL_RENDER_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final Map<ResourceLocation, NativeImage> TEXTURE_IMAGE_CACHE = new HashMap<>();
	private static final Map<ResourceLocation, IdentityHashMap<Object, Boolean>> CUBE_VISIBILITY_CACHE =
		new HashMap<>();
	private static final IdentityHashMap<Object, Boolean> SUPPORTED_LIONFISH_TREES = new IdentityHashMap<>();
	private static final IdentityHashMap<Object, IdentityHashMap<Object, Map<ResourceLocation, LionfishLayerPlan>>>
		LIONFISH_LAYER_PLANS = new IdentityHashMap<>();
	private static volatile ReflectionAccess reflectionAccess;
	private static volatile boolean reflectionAccessResolved;
	private static volatile boolean reflectionAccessDisabled;

	private static ModelPart innerCube;
	private static ModelPart outerCube;
	private static int activeRenderContextCount;

	public SlimeMimicRenderLayer(RenderLayerParent<T, M> renderer) {
		super(renderer);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing,
		float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
		if (!SlimeMimicHandler.isSlimeMimic(entity) || entity.isInvisible())
			return;
		if (SurgicalModelRenderContext.isRenderingSourceGeometry())
			return;

		beginFallbackOverlay(buffer);
		try {
			renderFallbackOverlay(getParentModel(), entity, poseStack, buffer, packedLight, overlay(entity));
		} finally {
			endPartInterception();
		}
	}

	public static void registerOnAll(net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers event) {
		for (net.minecraft.client.resources.PlayerSkin.Model skin : event.getSkins())
			registerOn(event.getSkin(skin));
		for (net.minecraft.world.entity.EntityType<?> entityType : event.getEntityTypes())
			registerOn(event.getRenderer(entityType));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void registerOn(EntityRenderer<?> entityRenderer) {
		if (!(entityRenderer instanceof LivingEntityRenderer<?, ?> livingRenderer))
			return;
		livingRenderer.addLayer((RenderLayer) new SlimeMimicRenderLayer<>(livingRenderer));
	}

	public static void beginBodyPartReplacement(MultiBufferSource buffer, LivingEntity entity,
		VertexConsumer sourceConsumer, int sourceColor) {
		RenderMode mode = SurgicalModelRenderContext.isRenderingSourceGeometry()
			? RenderMode.SOURCE_MODEL_PARTS : RenderMode.SLIMEIFY_MODEL_PARTS;
		pushContext(new RenderContext(mode, buffer, lookupTextureLocation(entity), sourceConsumer, sourceColor));
	}

	public static void beginFallbackOverlay(MultiBufferSource buffer) {
		pushContext(new RenderContext(RenderMode.SKIP_MODEL_PARTS, buffer, null));
	}

	public static void endPartInterception() {
		Deque<RenderContext> contexts = RENDER_CONTEXTS.get();
		if (!contexts.isEmpty()) {
			contexts.pop();
			activeRenderContextCount = Math.max(0, activeRenderContextCount - 1);
		}
		if (contexts.isEmpty())
			RENDER_CONTEXTS.remove();
	}

	public static boolean interceptModelPart(ModelPart part, PoseStack poseStack, int packedLight, int overlay) {
		if (activeRenderContextCount == 0)
			return false;
		if (INTERNAL_RENDER_DEPTH.get() > 0)
			return false;

		RenderContext context = currentContext();
		if (context == null)
			return false;

		if (context.mode == RenderMode.SKIP_MODEL_PARTS)
			return true;
		if (context.mode == RenderMode.SOURCE_MODEL_PARTS) {
			renderSourcePartRecursive(part, poseStack, context, packedLight, overlay, null);
			return true;
		}

		context.deferredParts()
			.add(new DeferredPart(part, new Matrix4f(poseStack.last().pose()),
				new Matrix3f(poseStack.last().normal()), packedLight, overlay));
		renderPartRecursive(part, poseStack, context, packedLight, overlay, RenderPass.INNER);
		return true;
	}

	/** Tracks the texture associated with each consumer returned to an entity RenderLayer. */
	public static MultiBufferSource trackRenderLayerBuffer(MultiBufferSource buffer) {
		if (!SurgicalModelRenderContext.isRenderLayerActive())
			return buffer;
		return renderType -> {
			VertexConsumer consumer = buffer.getBuffer(renderType);
			SurgicalModelRenderContext.recordRenderLayerConsumer(consumer, renderTypeTexture(renderType));
			return consumer;
		};
	}

	/**
	 * Optional entry point used by the pseudo-mixin for Lionfish API's AdvancedModelBox.
	 * Any incompatibility falls back to the original Lionfish renderer instead of escaping
	 * into the render loop.
	 */
	public static boolean interceptLionfishModelPart(Object part, PoseStack poseStack, VertexConsumer consumer,
		int packedLight, int overlay, int color) {
		if (activeRenderContextCount == 0 && !SurgicalModelRenderContext.isActive())
			return false;
		if (INTERNAL_RENDER_DEPTH.get() > 0)
			return false;

		RenderContext context = currentContext();
		boolean originalLayer = context == null && SurgicalModelRenderContext.isRenderLayerActive();
		if (context == null && !originalLayer)
			return false;

		try {
			if (!isSupportedLionfishTree(part))
				return false;
			if (originalLayer) {
				inferLionfishLayerOwner(part, consumer);
				renderOriginalLionfishLayerPartRecursive(part, poseStack, consumer, packedLight, overlay, color);
				return true;
			}
			if (context == null)
				return false;
			if (context.mode == RenderMode.SKIP_MODEL_PARTS)
				return true;
			if (context.mode == RenderMode.SOURCE_MODEL_PARTS) {
				renderSourceLionfishPartRecursive(part, poseStack, context, packedLight, overlay);
				return true;
			}

			context.deferredLionfishParts()
				.add(new DeferredLionfishPart(part, new Matrix4f(poseStack.last().pose()),
					new Matrix3f(poseStack.last().normal()), packedLight, overlay));
			renderLionfishPartRecursive(part, poseStack, context, packedLight, overlay, RenderPass.INNER);
			return true;
		} catch (RuntimeException | LinkageError e) {
			if (context != null)
				context.deferredLionfishParts().clear();
			LionfishModelPartCompat.disable(e);
			return false;
		}
	}

	private static void inferLionfishLayerOwner(Object layerRoot, VertexConsumer consumer) {
		Object sourceModel = SurgicalModelRenderContext.currentRenderLayerSourceModel();
		if (sourceModel == null)
			return;
		Object layerModel = LionfishModelPartCompat.model(layerRoot);
		if (layerModel == null || layerModel == sourceModel || layerModel.getClass() != sourceModel.getClass())
			return;
		if (LionfishModelPartCompat.root(layerModel) != layerRoot)
			return;

		Object sourceRoot = LionfishModelPartCompat.root(sourceModel);
		if (!LionfishModelPartCompat.supports(sourceRoot))
			return;
		ResourceLocation texture = SurgicalModelRenderContext.currentRenderLayerTexture(consumer);
		if (texture == null)
			return;
		Object sourceCube = lionfishLayerPlan(sourceRoot, layerRoot, texture).resolveSourceCube();
		if (sourceCube != null)
			SurgicalModelRenderContext.bindCurrentRenderLayerToSourceCube(sourceCube);
	}

	private static LionfishLayerPlan lionfishLayerPlan(Object sourceRoot, Object layerRoot, ResourceLocation texture) {
		IdentityHashMap<Object, Map<ResourceLocation, LionfishLayerPlan>> byLayer = LIONFISH_LAYER_PLANS
			.computeIfAbsent(sourceRoot, ignored -> new IdentityHashMap<>());
		Map<ResourceLocation, LionfishLayerPlan> byTexture = byLayer.computeIfAbsent(layerRoot,
			ignored -> new HashMap<>());
		return byTexture.computeIfAbsent(texture, ignored -> buildLionfishLayerPlan(sourceRoot, layerRoot, texture));
	}

	private static LionfishLayerPlan buildLionfishLayerPlan(Object sourceRoot, Object layerRoot,
		ResourceLocation texture) {
		NativeImage image = textureImage(texture);
		if (image == null || !image.format().hasAlpha())
			return LionfishLayerPlan.EMPTY;
		List<LionfishLayerPart> parts = new ArrayList<>();
		boolean matched = collectLionfishLayerPlan(sourceRoot, layerRoot, texture, new ArrayList<>(), -1, parts);
		return matched ? new LionfishLayerPlan(List.copyOf(parts)) : LionfishLayerPlan.EMPTY;
	}

	private static boolean collectLionfishLayerPlan(Object sourcePart, Object layerPart, ResourceLocation texture,
		List<Object> sourceChain, int parentIndex, List<LionfishLayerPart> parts) {
		List<?> sourceCubes = LionfishModelPartCompat.cubes(sourcePart);
		List<?> layerCubes = LionfishModelPartCompat.cubes(layerPart);
		if (sourceCubes.size() != layerCubes.size())
			return false;
		for (int cubeIndex = 0; cubeIndex < sourceCubes.size(); cubeIndex++) {
			if (!sameBounds(LionfishModelPartCompat.bounds(sourceCubes.get(cubeIndex)),
				LionfishModelPartCompat.bounds(layerCubes.get(cubeIndex))))
				return false;
		}

		boolean contributes = false;
		for (Object layerCube : layerCubes)
			if (lionfishCubeHasVisiblePixels(layerCube, texture)) {
				contributes = true;
				break;
			}

		sourceChain.add(sourcePart);
		int partIndex = parts.size();
		parts.add(new LionfishLayerPart(layerPart, parentIndex,
			contributes ? sourceChain.toArray(Object[]::new) : null));
		List<?> sourceChildren = LionfishModelPartCompat.children(sourcePart);
		List<?> layerChildren = LionfishModelPartCompat.children(layerPart);
		if (sourceChildren.size() != layerChildren.size())
			return removeIncompletePlan(sourceChain, parts, partIndex);
		for (int childIndex = 0; childIndex < sourceChildren.size(); childIndex++) {
			if (!LionfishModelPartCompat.supports(sourceChildren.get(childIndex))
				|| !LionfishModelPartCompat.supports(layerChildren.get(childIndex))
				|| !collectLionfishLayerPlan(sourceChildren.get(childIndex), layerChildren.get(childIndex), texture,
					sourceChain, partIndex, parts))
				return removeIncompletePlan(sourceChain, parts, partIndex);
		}
		sourceChain.remove(sourceChain.size() - 1);
		return true;
	}

	private static boolean removeIncompletePlan(List<Object> sourceChain, List<LionfishLayerPart> parts,
		int firstPartIndex) {
		sourceChain.remove(sourceChain.size() - 1);
		parts.subList(firstPartIndex, parts.size()).clear();
		return false;
	}

	private static boolean sameBounds(LionfishModelPartCompat.CubeBounds first,
		LionfishModelPartCompat.CubeBounds second) {
		return Math.abs(first.minX() - second.minX()) <= 1.0e-4f
			&& Math.abs(first.minY() - second.minY()) <= 1.0e-4f
			&& Math.abs(first.minZ() - second.minZ()) <= 1.0e-4f
			&& Math.abs(first.maxX() - second.maxX()) <= 1.0e-4f
			&& Math.abs(first.maxY() - second.maxY()) <= 1.0e-4f
			&& Math.abs(first.maxZ() - second.maxZ()) <= 1.0e-4f;
	}

	public static void renderDeferredOuterParts() {
		RenderContext context = currentContext();
		if (context == null || context.mode() != RenderMode.SLIMEIFY_MODEL_PARTS)
			return;

		for (DeferredPart deferredPart : context.deferredParts()) {
			PoseStack poseStack = new PoseStack();
			poseStack.last().pose().set(deferredPart.pose());
			poseStack.last().normal().set(deferredPart.normal());
			renderPartRecursive(deferredPart.part(), poseStack, context, deferredPart.packedLight(),
				deferredPart.overlay(), RenderPass.OUTER);
		}
		context.deferredParts().clear();

		try {
			for (DeferredLionfishPart deferredPart : context.deferredLionfishParts()) {
				PoseStack poseStack = new PoseStack();
				poseStack.last().pose().set(deferredPart.pose());
				poseStack.last().normal().set(deferredPart.normal());
				renderLionfishPartRecursive(deferredPart.part(), poseStack, context, deferredPart.packedLight(),
					deferredPart.overlay(), RenderPass.OUTER);
			}
		} catch (RuntimeException | LinkageError e) {
			LionfishModelPartCompat.disable(e);
		} finally {
			context.deferredLionfishParts().clear();
		}
	}

	private static boolean isSupportedLionfishTree(Object part) {
		Boolean cached = SUPPORTED_LIONFISH_TREES.get(part);
		if (cached != null)
			return cached;
		boolean supported = LionfishModelPartCompat.supports(part);
		if (supported)
			for (Object child : LionfishModelPartCompat.children(part))
				if (!isSupportedLionfishTree(child)) {
					supported = false;
					break;
				}
		SUPPORTED_LIONFISH_TREES.put(part, supported);
		return supported;
	}

	private static void renderLionfishPartRecursive(Object part, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay, RenderPass pass) {
		if (!LionfishModelPartCompat.isVisible(part))
			return;

		poseStack.pushPose();
		try {
			LionfishModelPartCompat.translateAndRotate(part, poseStack);
			for (Object cube : LionfishModelPartCompat.cubes(part))
				renderLionfishCube(cube, poseStack, context, packedLight, overlay, pass);

			if (!LionfishModelPartCompat.scaleChildren(part)) {
				poseStack.scale(
					1.0f / Math.max(LionfishModelPartCompat.xScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.yScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.zScale(part), 1.0e-4f));
			}
			for (Object child : LionfishModelPartCompat.children(part))
				renderLionfishPartRecursive(child, poseStack, context, packedLight, overlay, pass);
		} finally {
			poseStack.popPose();
		}
	}

	private static void renderOriginalLionfishLayerPartRecursive(Object part, PoseStack poseStack,
		VertexConsumer consumer, int packedLight, int overlay, int color) {
		if (!LionfishModelPartCompat.isVisible(part))
			return;

		poseStack.pushPose();
		try {
			LionfishModelPartCompat.translateAndRotate(part, poseStack);
			for (Object cube : LionfishModelPartCompat.cubes(part)) {
				poseStack.pushPose();
				try {
					if (!SurgicalModelRenderContext.prepareOriginalLayerCube(cube, poseStack))
						continue;
					LionfishModelPartCompat.compileCube(cube, poseStack.last(), consumer, packedLight, overlay,
						colorComponent(color, 16), colorComponent(color, 8), colorComponent(color, 0),
						colorComponent(color, 24), 0.0f);
				} finally {
					poseStack.popPose();
				}
			}

			if (!LionfishModelPartCompat.scaleChildren(part)) {
				poseStack.scale(
					1.0f / Math.max(LionfishModelPartCompat.xScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.yScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.zScale(part), 1.0e-4f));
			}
			for (Object child : LionfishModelPartCompat.children(part))
				renderOriginalLionfishLayerPartRecursive(child, poseStack, consumer, packedLight, overlay, color);
		} finally {
			poseStack.popPose();
		}
	}

	private static void renderPartRecursive(ModelPart part, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay, RenderPass pass) {
		renderPartRecursive(part, poseStack, context, packedLight, overlay, pass, null);
	}

	private static void renderPartRecursive(ModelPart part, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay, RenderPass pass, Integer inheritedAnchor) {
		if (!part.visible)
			return;

		poseStack.pushPose();
		part.translateAndRotate(poseStack);

		ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
		Integer partAnchor = inheritedAnchor;
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
				if (!cubeHasVisiblePixels(cube, context.texture()))
					continue;
				renderCube(cube, poseStack, context, packedLight, overlay, pass);
				Integer cubeId = SurgicalModelRenderContext.registeredCubeId(cube);
				if (partAnchor == null && cubeId != null)
					partAnchor = cubeId;
			}
			if (partAnchor != null) {
				for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
					if (!cubeHasVisiblePixels(cube, context.texture()))
						SurgicalModelRenderContext.associateLayerCube(cube, partAnchor);
				}
			}
		}

		Integer childAnchor = partAnchor;
		accessor.createBiotech$getChildren().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(child -> renderPartRecursive(child.getValue(), poseStack, context, packedLight, overlay, pass,
				childAnchor));

		poseStack.popPose();
	}

	private static void renderCube(ModelPart.Cube cube, PoseStack poseStack, RenderContext context, int packedLight,
		int overlay, RenderPass pass) {
		if (!cubeHasVisiblePixels(cube, context.texture()))
			return;

		float width = cube.maxX - cube.minX;
		float height = cube.maxY - cube.minY;
		float depth = cube.maxZ - cube.minZ;
		if (width < 0 || height < 0 || depth < 0)
			return;

		poseStack.pushPose();
		if (!SurgicalModelRenderContext.prepareCube(cube, poseStack, pass == RenderPass.INNER)) {
			poseStack.popPose();
			return;
		}

		boolean flatCube = isFlatCube(width, height, depth);

		float centerX = (cube.minX + cube.maxX) * 0.5f / 16.0f;
		float centerY = (cube.minY + cube.maxY) * 0.5f / 16.0f;
		float centerZ = (cube.minZ + cube.maxZ) * 0.5f / 16.0f;

		if (flatCube) {
			if (pass == RenderPass.INNER)
				renderFlatCubeBase(cube, poseStack, context, packedLight, overlay);
			else
				renderFlatCubeFilter(cube, poseStack, context, packedLight, overlay);
			poseStack.popPose();
			return;
		}

		if (pass == RenderPass.INNER) {
			VertexConsumer innerConsumer = context.buffer()
				.getBuffer(RenderType.entityCutoutNoCull(SLIME_TEXTURE));
			renderSlimeCube(innerCube(), poseStack, innerConsumer, packedLight, overlay, centerX, centerY, centerZ,
				width, height, depth, color(INNER_RED, INNER_GREEN, INNER_BLUE, INNER_ALPHA));
			poseStack.popPose();
			return;
		}

		VertexConsumer outerConsumer = context.buffer()
			.getBuffer(RenderType.entityTranslucent(SLIME_TEXTURE));
		renderSlimeCube(outerCube(), poseStack, outerConsumer, packedLight, overlay, centerX, centerY, centerZ,
			width + 2.0f * OUTER_CUBE_INFLATE_PIXELS, height + 2.0f * OUTER_CUBE_INFLATE_PIXELS,
			depth + 2.0f * OUTER_CUBE_INFLATE_PIXELS, color(OUTER_RED, OUTER_GREEN, OUTER_BLUE, OUTER_ALPHA));
		poseStack.popPose();
	}

	private static void renderSourcePartRecursive(ModelPart part, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay, Integer inheritedAnchor) {
		if (!part.visible)
			return;

		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		ModelPartAccessor accessor = (ModelPartAccessor) (Object) part;
		Integer partAnchor = inheritedAnchor;
		if (!part.skipDraw) {
			for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
				if (!cubeHasVisiblePixels(cube, context.texture()))
					continue;
				poseStack.pushPose();
				if (SurgicalModelRenderContext.prepareCube(cube, poseStack, true)) {
					cube.compile(poseStack.last(), context.sourceConsumer(), packedLight, overlay,
						context.sourceColor());
				}
				poseStack.popPose();
				Integer cubeId = SurgicalModelRenderContext.registeredCubeId(cube);
				if (partAnchor == null && cubeId != null)
					partAnchor = cubeId;
			}
			if (partAnchor != null) {
				for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
					if (!cubeHasVisiblePixels(cube, context.texture()))
						SurgicalModelRenderContext.associateLayerCube(cube, partAnchor);
				}
			}
		}

		Integer childAnchor = partAnchor;
		accessor.createBiotech$getChildren().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(child -> renderSourcePartRecursive(child.getValue(), poseStack, context, packedLight,
				overlay, childAnchor));
		poseStack.popPose();
	}

	private static void renderSourceLionfishPartRecursive(Object part, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay) {
		if (!LionfishModelPartCompat.isVisible(part))
			return;

		poseStack.pushPose();
		try {
			LionfishModelPartCompat.translateAndRotate(part, poseStack);
			for (Object cube : LionfishModelPartCompat.cubes(part)) {
				if (!lionfishCubeHasVisiblePixels(cube, context.texture()))
					continue;
				LionfishModelPartCompat.CubeBounds bounds = LionfishModelPartCompat.bounds(cube);
				poseStack.pushPose();
				try {
					if (!SurgicalModelRenderContext.prepareCube(cube, poseStack, true,
						bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()))
						continue;
					LionfishModelPartCompat.compileCube(cube, poseStack.last(), context.sourceConsumer(),
						packedLight, overlay, colorComponent(context.sourceColor(), 16),
						colorComponent(context.sourceColor(), 8), colorComponent(context.sourceColor(), 0),
						colorComponent(context.sourceColor(), 24), 0.0f);
				} finally {
					poseStack.popPose();
				}
			}
			if (!LionfishModelPartCompat.scaleChildren(part)) {
				poseStack.scale(
					1.0f / Math.max(LionfishModelPartCompat.xScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.yScale(part), 1.0e-4f),
					1.0f / Math.max(LionfishModelPartCompat.zScale(part), 1.0e-4f));
			}
			for (Object child : LionfishModelPartCompat.children(part))
				renderSourceLionfishPartRecursive(child, poseStack, context, packedLight, overlay);
		} finally {
			poseStack.popPose();
		}
	}

	private static void renderLionfishCube(Object cube, PoseStack poseStack, RenderContext context, int packedLight,
		int overlay, RenderPass pass) {
		if (!lionfishCubeHasVisiblePixels(cube, context.texture()))
			return;

		LionfishModelPartCompat.CubeBounds bounds = LionfishModelPartCompat.bounds(cube);
		float width = bounds.maxX() - bounds.minX();
		float height = bounds.maxY() - bounds.minY();
		float depth = bounds.maxZ() - bounds.minZ();
		if (width < 0 || height < 0 || depth < 0)
			return;

		poseStack.pushPose();
		if (!SurgicalModelRenderContext.prepareCube(cube, poseStack, pass == RenderPass.INNER,
			bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
			poseStack.popPose();
			return;
		}

		boolean flatCube = isFlatCube(width, height, depth);
		float centerX = (bounds.minX() + bounds.maxX()) * 0.5f / 16.0f;
		float centerY = (bounds.minY() + bounds.maxY()) * 0.5f / 16.0f;
		float centerZ = (bounds.minZ() + bounds.maxZ()) * 0.5f / 16.0f;

		if (flatCube) {
			if (pass == RenderPass.INNER)
				renderLionfishFlatCubeBase(cube, poseStack, context, packedLight, overlay);
			else
				renderLionfishFlatCubeFilter(cube, poseStack, context, packedLight, overlay);
			poseStack.popPose();
			return;
		}

		if (pass == RenderPass.INNER) {
			VertexConsumer innerConsumer = context.buffer()
				.getBuffer(RenderType.entityCutoutNoCull(SLIME_TEXTURE));
			renderSlimeCube(innerCube(), poseStack, innerConsumer, packedLight, overlay, centerX, centerY, centerZ,
				width, height, depth, color(INNER_RED, INNER_GREEN, INNER_BLUE, INNER_ALPHA));
			poseStack.popPose();
			return;
		}

		VertexConsumer outerConsumer = context.buffer()
			.getBuffer(RenderType.entityTranslucent(SLIME_TEXTURE));
		renderSlimeCube(outerCube(), poseStack, outerConsumer, packedLight, overlay, centerX, centerY, centerZ,
			width + 2.0f * OUTER_CUBE_INFLATE_PIXELS, height + 2.0f * OUTER_CUBE_INFLATE_PIXELS,
			depth + 2.0f * OUTER_CUBE_INFLATE_PIXELS, color(OUTER_RED, OUTER_GREEN, OUTER_BLUE, OUTER_ALPHA));
		poseStack.popPose();
	}

	private static void renderSlimeCube(ModelPart slimeCube, PoseStack poseStack, VertexConsumer consumer,
		int packedLight, int overlay, float centerX, float centerY, float centerZ, float width, float height,
		float depth, int color) {
		runWithoutPartInterception(() -> {
			poseStack.pushPose();
			poseStack.translate(centerX, centerY, centerZ);
			poseStack.scale(width / SLIME_MODEL_WIDTH, height / SLIME_MODEL_WIDTH, depth / SLIME_MODEL_WIDTH);
			poseStack.translate(0.0f, -SLIME_MODEL_CENTER_Y, 0.0f);
			slimeCube.render(poseStack, consumer, packedLight, overlay, color);
			poseStack.popPose();
		});
	}

	private static void renderFlatCubeBase(ModelPart.Cube cube, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay) {
		ResourceLocation texture = context.texture();
		if (texture == null)
			return;

		VertexConsumer baseConsumer = context.buffer()
			.getBuffer(RenderType.entityCutoutNoCull(texture));
		runWithoutPartInterception(() ->
			cube.compile(poseStack.last(), baseConsumer, packedLight, overlay, 0xFFFFFFFF));
	}

	private static void renderFlatCubeFilter(ModelPart.Cube cube, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay) {
		ResourceLocation texture = context.texture();
		if (texture == null)
			return;

		VertexConsumer filterConsumer = context.buffer()
			.getBuffer(RenderType.entityTranslucent(texture));

		runWithoutPartInterception(() ->
			compileCubeWithNormalOffset(cube, poseStack.last(), filterConsumer, packedLight, overlay,
				OVERLAY_RED, OVERLAY_GREEN, OVERLAY_BLUE, FLAT_CUBE_FILTER_ALPHA, FLAT_CUBE_FILTER_NORMAL_OFFSET));
	}

	private static void renderLionfishFlatCubeBase(Object cube, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay) {
		ResourceLocation texture = context.texture();
		if (texture == null)
			return;

		VertexConsumer baseConsumer = context.buffer()
			.getBuffer(RenderType.entityCutoutNoCull(texture));
		runWithoutPartInterception(() -> LionfishModelPartCompat.compileCube(cube, poseStack.last(), baseConsumer,
			packedLight, overlay, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f));
	}

	private static void renderLionfishFlatCubeFilter(Object cube, PoseStack poseStack, RenderContext context,
		int packedLight, int overlay) {
		ResourceLocation texture = context.texture();
		if (texture == null)
			return;

		VertexConsumer filterConsumer = context.buffer()
			.getBuffer(RenderType.entityTranslucent(texture));
		runWithoutPartInterception(() -> LionfishModelPartCompat.compileCube(cube, poseStack.last(), filterConsumer,
			packedLight, overlay, OVERLAY_RED, OVERLAY_GREEN, OVERLAY_BLUE, FLAT_CUBE_FILTER_ALPHA,
			FLAT_CUBE_FILTER_NORMAL_OFFSET));
	}

	private static boolean isFlatCube(float width, float height, float depth) {
		return width <= FLAT_CUBE_THRESHOLD_PIXELS || height <= FLAT_CUBE_THRESHOLD_PIXELS
			|| depth <= FLAT_CUBE_THRESHOLD_PIXELS;
	}

	private static void renderFallbackOverlay(EntityModel<?> model, LivingEntity entity, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int overlay) {
		VertexConsumer overlayConsumer = buffer.getBuffer(RenderType.entityTranslucent(lookupTextureLocation(entity)));
		model.renderToBuffer(poseStack, overlayConsumer, packedLight, overlay,
			color(OVERLAY_RED, OVERLAY_GREEN, OVERLAY_BLUE, OVERLAY_ALPHA));
	}

	private static int overlay(LivingEntity entity) {
		return LivingEntityRenderer.getOverlayCoords(entity, 0.0f);
	}

	private static ResourceLocation lookupTextureLocation(LivingEntity entity) {
		EntityRenderer<? super LivingEntity> renderer = Minecraft.getInstance()
			.getEntityRenderDispatcher()
			.getRenderer(entity);
		return renderer.getTextureLocation(entity);
	}

	public static void clearCachedTextureData() {
		for (NativeImage image : TEXTURE_IMAGE_CACHE.values())
			image.close();
		TEXTURE_IMAGE_CACHE.clear();
		CUBE_VISIBILITY_CACHE.clear();
		SUPPORTED_LIONFISH_TREES.clear();
		LIONFISH_LAYER_PLANS.clear();
		LionfishModelPartCompat.clearCaches();
	}

	private static void pushContext(RenderContext context) {
		RENDER_CONTEXTS.get()
			.push(context);
		activeRenderContextCount++;
	}

	private static RenderContext currentContext() {
		return RENDER_CONTEXTS.get()
			.peek();
	}

	private static ModelPart innerCube() {
		if (innerCube == null) {
			innerCube = Minecraft.getInstance()
				.getEntityModels()
				.bakeLayer(ModelLayers.SLIME)
				.getChild("cube");
		}
		return innerCube;
	}

	private static ModelPart outerCube() {
		if (outerCube == null) {
			outerCube = Minecraft.getInstance()
				.getEntityModels()
				.bakeLayer(ModelLayers.SLIME_OUTER)
				.getChild("cube");
		}
		return outerCube;
	}

	private static void runWithoutPartInterception(Runnable runnable) {
		INTERNAL_RENDER_DEPTH.set(INTERNAL_RENDER_DEPTH.get() + 1);
		try {
			runnable.run();
		} finally {
			int depth = INTERNAL_RENDER_DEPTH.get() - 1;
			if (depth <= 0) {
				INTERNAL_RENDER_DEPTH.remove();
				return;
			}
			INTERNAL_RENDER_DEPTH.set(depth);
		}
	}

	private enum RenderMode {
		SLIMEIFY_MODEL_PARTS,
		SOURCE_MODEL_PARTS,
		SKIP_MODEL_PARTS
	}

	private enum RenderPass {
		INNER,
		OUTER
	}

	private record RenderContext(RenderMode mode, MultiBufferSource buffer, ResourceLocation texture,
		VertexConsumer sourceConsumer, int sourceColor,
		List<DeferredPart> deferredParts, List<DeferredLionfishPart> deferredLionfishParts) {
		private RenderContext(RenderMode mode, MultiBufferSource buffer, ResourceLocation texture) {
			this(mode, buffer, texture, null, 0xFFFFFFFF, new ArrayList<>(), new ArrayList<>());
		}

		private RenderContext(RenderMode mode, MultiBufferSource buffer, ResourceLocation texture,
			VertexConsumer sourceConsumer, int sourceColor) {
			this(mode, buffer, texture, sourceConsumer, sourceColor, new ArrayList<>(), new ArrayList<>());
		}
	}

	private record DeferredPart(ModelPart part, Matrix4f pose, Matrix3f normal, int packedLight, int overlay) {
	}

	private record DeferredLionfishPart(Object part, Matrix4f pose, Matrix3f normal, int packedLight, int overlay) {
	}

	private record LionfishLayerPart(Object layerPart, int parentIndex, Object[] sourceChain) {
	}

	private record LionfishLayerPlan(List<LionfishLayerPart> parts) {
		private static final LionfishLayerPlan EMPTY = new LionfishLayerPlan(List.of());

		private Object resolveSourceCube() {
			if (parts.isEmpty())
				return null;

			boolean[] visibleParts = new boolean[parts.size()];
			Object[] commonChain = null;
			int commonLength = 0;
			for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
				LionfishLayerPart part = parts.get(partIndex);
				boolean parentVisible = part.parentIndex() < 0 || visibleParts[part.parentIndex()];
				boolean visible = parentVisible && LionfishModelPartCompat.isVisible(part.layerPart());
				visibleParts[partIndex] = visible;
				Object[] sourceChain = part.sourceChain();
				if (!visible || sourceChain == null)
					continue;

				if (commonChain == null) {
					commonChain = sourceChain;
					commonLength = sourceChain.length;
					continue;
				}
				commonLength = Math.min(commonLength, sourceChain.length);
				int chainIndex = 0;
				while (chainIndex < commonLength && commonChain[chainIndex] == sourceChain[chainIndex])
					chainIndex++;
				commonLength = chainIndex;
			}

			if (commonChain == null)
				return null;
			// The visible layer subtree is an attachment. Its nearest registered
			// external ancestor owns the independent render layer.
			for (int partIndex = commonLength - 2; partIndex >= 0; partIndex--)
				for (Object sourceCube : LionfishModelPartCompat.cubes(commonChain[partIndex]))
					if (SurgicalModelRenderContext.registeredCubeId(sourceCube) != null)
						return sourceCube;
			return null;
		}
	}

	private static boolean cubeHasVisiblePixels(ModelPart.Cube cube, ResourceLocation texture) {
		if (texture == null)
			return true;

		IdentityHashMap<Object, Boolean> visibilityByCube =
			CUBE_VISIBILITY_CACHE.computeIfAbsent(texture, key -> new IdentityHashMap<>());
		Boolean cachedVisibility = visibilityByCube.get(cube);
		if (cachedVisibility != null)
			return cachedVisibility;

		NativeImage image = textureImage(texture);
		boolean visible = image == null || cubeHasVisiblePixels(cube, image);
		visibilityByCube.put(cube, visible);
		return visible;
	}

	private static boolean lionfishCubeHasVisiblePixels(Object cube, ResourceLocation texture) {
		if (texture == null)
			return true;

		IdentityHashMap<Object, Boolean> visibilityByCube =
			CUBE_VISIBILITY_CACHE.computeIfAbsent(texture, key -> new IdentityHashMap<>());
		Boolean cachedVisibility = visibilityByCube.get(cube);
		if (cachedVisibility != null)
			return cachedVisibility;

		NativeImage image = textureImage(texture);
		boolean visible = image == null || LionfishModelPartCompat.cubeHasVisiblePixels(cube, image);
		visibilityByCube.put(cube, visible);
		return visible;
	}

	private static boolean cubeHasVisiblePixels(ModelPart.Cube cube, NativeImage image) {
		ReflectionAccess access = reflectionAccess();
		if (access == null || !image.format().hasAlpha())
			return true;

		try {
			for (Object polygon : cubePolygons(access, cube)) {
				float minU = Float.POSITIVE_INFINITY;
				float minV = Float.POSITIVE_INFINITY;
				float maxU = Float.NEGATIVE_INFINITY;
				float maxV = Float.NEGATIVE_INFINITY;

				for (Object vertex : polygonVertices(access, polygon)) {
					float u = vertexU(access, vertex);
					float v = vertexV(access, vertex);
					minU = Math.min(minU, u);
					minV = Math.min(minV, v);
					maxU = Math.max(maxU, u);
					maxV = Math.max(maxV, v);
				}

				if (uvRangeHasVisiblePixels(image, minU, minV, maxU, maxV))
					return true;
			}
		} catch (RuntimeException e) {
			disableReflection("Failed to inspect slime mimic cube texture visibility; falling back to vanilla cube rendering.",
				e);
			return true;
		}

		return false;
	}

	private static boolean uvRangeHasVisiblePixels(NativeImage image, float minU, float minV, float maxU, float maxV) {
		int width = image.getWidth();
		int height = image.getHeight();
		int minX = clampTextureCoord((int) Math.floor(Math.min(minU, maxU) * width), width);
		int minY = clampTextureCoord((int) Math.floor(Math.min(minV, maxV) * height), height);
		int maxX = clampTextureCoord((int) Math.ceil(Math.max(minU, maxU) * width) - 1, width);
		int maxY = clampTextureCoord((int) Math.ceil(Math.max(minV, maxV) * height) - 1, height);
		if (maxX < minX || maxY < minY)
			return false;

		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				if (FastColor.ABGR32.alpha(image.getPixelRGBA(x, y)) > 0)
					return true;
			}
		}

		return false;
	}

	private static int clampTextureCoord(int value, int maxExclusive) {
		if (maxExclusive <= 0)
			return 0;
		return Math.max(0, Math.min(value, maxExclusive - 1));
	}

	private static NativeImage textureImage(ResourceLocation texture) {
		NativeImage cached = TEXTURE_IMAGE_CACHE.get(texture);
		if (cached != null)
			return cached;

		NativeImage loaded = loadTextureImage(texture);
		if (loaded != null)
			TEXTURE_IMAGE_CACHE.put(texture, loaded);
		return loaded;
	}

	private static NativeImage loadTextureImage(ResourceLocation texture) {
		try {
			Resource resource = Minecraft.getInstance()
				.getResourceManager()
				.getResource(texture)
				.orElse(null);
			if (resource == null)
				return null;
			try (InputStream stream = resource.open()) {
				return NativeImage.read(stream);
			}
		} catch (IOException e) {
			return null;
		}
	}

	private static void compileCubeWithNormalOffset(ModelPart.Cube cube, PoseStack.Pose pose, VertexConsumer consumer,
		int packedLight, int overlay, float red, float green, float blue, float alpha, float normalOffset) {
		ReflectionAccess access = reflectionAccess();
		if (access == null) {
			cube.compile(pose, consumer, packedLight, overlay, color(red, green, blue, alpha));
			return;
		}

		Matrix4f poseMatrix = pose.pose();
		Matrix3f normalMatrix = pose.normal();

		try {
			for (Object polygon : cubePolygons(access, cube)) {
				Vector3f transformedNormal = normalMatrix.transform(polygonNormal(access, polygon), new Vector3f());
				if (transformedNormal.lengthSquared() > 1.0e-7f)
					transformedNormal.normalize();
				else
					transformedNormal.zero();

				float normalX = transformedNormal.x();
				float normalY = transformedNormal.y();
				float normalZ = transformedNormal.z();

				for (Object vertex : polygonVertices(access, polygon)) {
					Vector3f localPos = vertexPos(access, vertex);
					Vector4f transformedPos = poseMatrix.transform(
						new Vector4f(localPos.x() / 16.0f, localPos.y() / 16.0f, localPos.z() / 16.0f, 1.0f));
					consumer.addVertex(
							transformedPos.x() + normalX * normalOffset,
							transformedPos.y() + normalY * normalOffset,
							transformedPos.z() + normalZ * normalOffset)
						.setColor(red, green, blue, alpha)
						.setUv(vertexU(access, vertex), vertexV(access, vertex))
						.setOverlay(overlay)
						.setLight(packedLight)
						.setNormal(normalX, normalY, normalZ);
				}
			}
		} catch (RuntimeException e) {
			disableReflection("Failed to offset slime mimic flat-cube overlay; falling back to vanilla overlay rendering.",
				e);
			cube.compile(pose, consumer, packedLight, overlay, color(red, green, blue, alpha));
		}
	}

	private static int color(float red, float green, float blue, float alpha) {
		return (Math.round(alpha * 255.0f) << 24)
			| (Math.round(red * 255.0f) << 16)
			| (Math.round(green * 255.0f) << 8)
			| Math.round(blue * 255.0f);
	}

	private static float colorComponent(int color, int shift) {
		return (color >>> shift & 0xff) / 255.0f;
	}

	private static ResourceLocation renderTypeTexture(RenderType renderType) {
		if (!(renderType instanceof CompositeRenderTypeAccessor compositeAccessor))
			return null;
		RenderType.CompositeState state = compositeAccessor.createBiotech$getState();
		CompositeRenderStateAccessor stateAccessor = (CompositeRenderStateAccessor) (Object) state;
		Object textureState = stateAccessor.createBiotech$getTextureState();
		TextureStateShardAccessor textureAccessor = (TextureStateShardAccessor) textureState;
		return textureAccessor.createBiotech$getTexture().orElse(null);
	}

	private static ReflectionAccess reflectionAccess() {
		if (reflectionAccessDisabled)
			return null;
		if (reflectionAccessResolved)
			return reflectionAccess;

		synchronized (SlimeMimicRenderLayer.class) {
			if (reflectionAccessDisabled)
				return null;
			if (reflectionAccessResolved)
				return reflectionAccess;
			try {
				reflectionAccess = ReflectionAccess.create();
			} catch (RuntimeException e) {
				disableReflection("Failed to initialize slime mimic model reflection; flat-cube fixes will use vanilla rendering.",
					e);
				return null;
			}
			reflectionAccessResolved = true;
			return reflectionAccess;
		}
	}

	private static void disableReflection(String message, RuntimeException exception) {
		synchronized (SlimeMimicRenderLayer.class) {
			if (!reflectionAccessDisabled)
				LOGGER.warn(message, exception);
			reflectionAccess = null;
			reflectionAccessResolved = true;
			reflectionAccessDisabled = true;
		}
	}

	private static Object[] cubePolygons(ReflectionAccess access, ModelPart.Cube cube) {
		return (Object[]) readField(access.cubePolygonsField, cube);
	}

	private static Object[] polygonVertices(ReflectionAccess access, Object polygon) {
		return (Object[]) readField(access.polygonVerticesField, polygon);
	}

	private static Vector3f polygonNormal(ReflectionAccess access, Object polygon) {
		return new Vector3f((Vector3f) readField(access.polygonNormalField, polygon));
	}

	private static Vector3f vertexPos(ReflectionAccess access, Object vertex) {
		return (Vector3f) readField(access.vertexPosField, vertex);
	}

	private static float vertexU(ReflectionAccess access, Object vertex) {
		return readFloatField(access.vertexUField, vertex);
	}

	private static float vertexV(ReflectionAccess access, Object vertex) {
		return readFloatField(access.vertexVField, vertex);
	}

	private static Object readField(Field field, Object target) {
		try {
			return field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Failed to read field " + field.getName(), e);
		}
	}

	private static float readFloatField(Field field, Object target) {
		try {
			return field.getFloat(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Failed to read field " + field.getName(), e);
		}
	}

	private static Class<?> classForName(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Failed to resolve class " + name, e);
		}
	}

	private static Field accessibleField(Field field) {
		field.setAccessible(true);
		return field;
	}

	private static Field findSingleField(Class<?> owner, Predicate<Field> predicate, String description) {
		List<Field> matches = findFields(owner, predicate);
		if (matches.size() != 1)
			throw new IllegalStateException("Expected exactly 1 " + description + " field on " + owner.getName()
				+ ", found " + matches.size());
		return accessibleField(matches.get(0));
	}

	private static List<Field> findFields(Class<?> owner, Predicate<Field> predicate) {
		List<Field> matches = new ArrayList<>();
		for (Field field : owner.getDeclaredFields()) {
			if (predicate.test(field))
				matches.add(field);
		}
		return matches;
	}

	private static boolean isArrayOf(Field field, Class<?> componentType) {
		return field.getType().isArray() && field.getType().getComponentType() == componentType;
	}

	private static final class ReflectionAccess {
		private final Field cubePolygonsField;
		private final Field polygonVerticesField;
		private final Field polygonNormalField;
		private final Field vertexPosField;
		private final Field vertexUField;
		private final Field vertexVField;

		private ReflectionAccess(Field cubePolygonsField, Field polygonVerticesField, Field polygonNormalField,
			Field vertexPosField, Field vertexUField, Field vertexVField) {
			this.cubePolygonsField = cubePolygonsField;
			this.polygonVerticesField = polygonVerticesField;
			this.polygonNormalField = polygonNormalField;
			this.vertexPosField = vertexPosField;
			this.vertexUField = vertexUField;
			this.vertexVField = vertexVField;
		}

		private static ReflectionAccess create() {
			Class<?> polygonClass = classForName("net.minecraft.client.model.geom.ModelPart$Polygon");
			Class<?> vertexClass = classForName("net.minecraft.client.model.geom.ModelPart$Vertex");
			Field cubePolygonsField =
				findSingleField(ModelPart.Cube.class, field -> isArrayOf(field, polygonClass), "cube polygon array");
			Field polygonVerticesField =
				findSingleField(polygonClass, field -> isArrayOf(field, vertexClass), "polygon vertex array");
			Field polygonNormalField =
				findSingleField(polygonClass, field -> field.getType() == Vector3f.class, "polygon normal");
			Field vertexPosField =
				findSingleField(vertexClass, field -> field.getType() == Vector3f.class, "vertex position");
			List<Field> vertexFloatFields = findFields(vertexClass, field -> field.getType() == float.class);
			if (vertexFloatFields.size() != 2)
				throw new IllegalStateException(
					"Expected exactly 2 float fields on " + vertexClass.getName() + ", found " + vertexFloatFields.size());
			return new ReflectionAccess(cubePolygonsField, polygonVerticesField, polygonNormalField, vertexPosField,
				accessibleField(vertexFloatFields.get(0)), accessibleField(vertexFloatFields.get(1)));
		}
	}

}
