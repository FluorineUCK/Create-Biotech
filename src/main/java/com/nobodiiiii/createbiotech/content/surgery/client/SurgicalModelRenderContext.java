package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.mixin.client.ModelPartAccessor;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Client-only filter and geometry collector around the existing slime-mimic cube renderer. */
public final class SurgicalModelRenderContext {
	private static final ThreadLocal<Deque<Context>> CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);
	/**
	 * Render hooks also run for every ordinary living entity. Keep their no-surgery path to one
	 * volatile read instead of touching a ThreadLocal (or allocating a forwarding lambda) per cube.
	 */
	private static volatile int activeContextCount;

	private SurgicalModelRenderContext() {}

	public static void begin(PoseStack poseStack, int expectedCubeCount, BitSet presentCubes,
		Map<Integer, Vec3> cubeOffsets,
		boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		begin(poseStack, expectedCubeCount, presentCubes, cubeOffsets, collectGeometry, cameraPosition, false);
	}

	public static void begin(PoseStack poseStack, int expectedCubeCount, BitSet presentCubes,
		Map<Integer, Vec3> cubeOffsets, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry) {
		begin(poseStack, expectedCubeCount, presentCubes, cubeOffsets, collectGeometry, cameraPosition,
			renderSourceGeometry, new CubeIdCache());
	}

	static void begin(PoseStack poseStack, int expectedCubeCount, BitSet presentCubes,
		Map<Integer, Vec3> cubeOffsets, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry, CubeIdCache cubeIds) {
		Context context = new Context(expectedCubeCount, presentCubes, cubeOffsets,
			collectGeometry, cameraPosition, renderSourceGeometry, cubeIds);
		CONTEXTS.get().push(context);
		activeContextCount++;
	}

	public static Snapshot end() {
		Deque<Context> contexts = CONTEXTS.get();
		if (contexts.isEmpty())
			return Snapshot.EMPTY;
		Context context = contexts.pop();
		activeContextCount = Math.max(0, activeContextCount - 1);
		return context.snapshot();
	}

	/** Fast guard for mixins placed on global entity-rendering hot paths. */
	public static boolean isActive() {
		return activeContextCount > 0;
	}

	/**
	 * Opens the scope of one vanilla {@code RenderLayer}. Cubes already seen in the source model
	 * retain their individual owners. Cubes from a separately baked model share one stable source
	 * owner for the entire layer, preventing that model from being repeated on every separated
	 * component.
	 */
	public static void beginRenderLayer(Object sourceModel) {
		Context context = current();
		if (context != null)
			context.beginRenderLayer(sourceModel);
	}

	public static void endRenderLayer() {
		Context context = current();
		if (context != null)
			context.endRenderLayer();
	}

	/**
	 * Overrides the generic layer owner with a logical source cube selected by a compatibility
	 * adapter. The cube must already belong to the source model rendered in this context.
	 */
	public static boolean bindCurrentRenderLayerToSourceCube(Object sourceCube) {
		Context context = current();
		return context != null && context.bindCurrentRenderLayerTo(sourceCube);
	}

	/** Selects the first registered direct cube of a vanilla model part as the layer owner. */
	public static boolean bindCurrentRenderLayerToPart(ModelPart sourcePart) {
		ModelPartAccessor accessor = (ModelPartAccessor) (Object) sourcePart;
		for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
			if (bindCurrentRenderLayerToSourceCube(cube))
				return true;
		}
		return false;
	}

	/** Whether rendering is currently inside a vanilla entity RenderLayer. */
	public static boolean isRenderLayerActive() {
		Context context = current();
		return context != null && context.isRenderLayerActive();
	}

	/** Whether the selected surgical cubes should use their source-model geometry and texture. */
	public static boolean isRenderingSourceGeometry() {
		Context context = current();
		return context != null && context.renderSourceGeometry;
	}

	public static void recordRenderLayerConsumer(VertexConsumer consumer, @Nullable ResourceLocation texture) {
		Context context = current();
		if (context != null)
			context.recordRenderLayerConsumer(consumer, texture);
	}

	@Nullable
	public static Object currentRenderLayerSourceModel() {
		Context context = current();
		return context == null ? null : context.currentRenderLayerSourceModel();
	}

	@Nullable
	public static ResourceLocation currentRenderLayerTexture(VertexConsumer consumer) {
		Context context = current();
		return context == null ? null : context.currentRenderLayerTexture(consumer);
	}

	/**
	 * Applies an existing source owner to one original-model cube without registering that cube
	 * in the surgical topology. Model-library bridges use this for separately rendered models.
	 */
	public static boolean prepareOriginalLayerCube(Object cube, PoseStack poseStack) {
		Context context = current();
		if (context == null)
			return true;

		Integer cubeId = context.ownerForOriginalLayerCube(cube);
		if (cubeId == null)
			return true;
		if (cubeId < 0 || !context.isPresent(cubeId))
			return false;
		context.applyOffset(cubeId, poseStack);
		return true;
	}

	/** Called only after the existing texture-visibility check accepted the cube. */
	public static boolean prepareCube(ModelPart.Cube cube, PoseStack poseStack, boolean innerPass) {
		return prepareCube(cube, poseStack, innerPass,
			cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ);
	}

	/** Optional-model overload used by compatibility renderers that expose equivalent cube bounds. */
	public static boolean prepareCube(Object cube, PoseStack poseStack, boolean innerPass,
		float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		Context context = current();
		if (context == null)
			return true;

		int cubeId = context.idFor(cube);
		if (!context.isPresent(cubeId))
			return false;

		context.applyOffset(cubeId, poseStack);
		if (innerPass && context.collectGeometry)
			context.capture(cubeId, poseStack, minX, minY, minZ, maxX, maxY, maxZ);
		return true;
	}

	/**
	 * Applies the state of an already-registered source cube to a later vanilla model pass.
	 * Layers such as villager clothing and warden emissive textures reuse the source model's
	 * cube instances, so this keeps those pixels on the same separated component without
	 * admitting layer-only geometry into the surgical topology.
	 */
	public static void renderOriginalLayerCube(ModelPart.Cube cube, PoseStack.Pose pose, Runnable draw) {
		Context context = current();
		if (context == null) {
			draw.run();
			return;
		}

		Integer cubeId = context.ownerForOriginalLayerCube(cube);
		if (cubeId == null) {
			draw.run();
			return;
		}
		if (cubeId < 0)
			return;
		if (!context.isPresent(cubeId))
			return;

		Vec3 offset = context.offsetFor(cubeId);
		if (offset == null || offset.lengthSqr() < 1.0e-12d) {
			draw.run();
			return;
		}

		Matrix4f poseMatrix = pose.pose();
		Matrix4f originalPose = new Matrix4f(poseMatrix);
		try {
			poseMatrix.translateLocal((float) offset.x, (float) offset.y, (float) offset.z);
			draw.run();
		} finally {
			poseMatrix.set(originalPose);
		}
	}

	@Nullable
	public static Integer registeredCubeId(Object cube) {
		Context context = current();
		return context == null ? null : context.registeredIdFor(cube);
	}

	/** Associates a texture-transparent model cube with the visible source cube that owns it. */
	public static void associateLayerCube(Object cube, int ownerCubeId) {
		Context context = current();
		if (context != null)
			context.associate(cube, ownerCubeId);
	}

	/**
	 * Anchors independently rendered geometry to the first direct source cube of a model
	 * part. This is intended for explicit adapters, not for discovering arbitrary layers.
	 */
	public static boolean prepareAttachment(ModelPart anchor, PoseStack poseStack) {
		Context context = current();
		if (context == null)
			return true;

		ModelPartAccessor accessor = (ModelPartAccessor) (Object) anchor;
		for (ModelPart.Cube cube : accessor.createBiotech$getCubes()) {
			Integer cubeId = context.registeredIdFor(cube);
			if (cubeId == null)
				continue;
			if (!context.isPresent(cubeId))
				return false;
			context.applyOffset(cubeId, poseStack);
			return true;
		}
		return true;
	}

	@Nullable
	private static Context current() {
		return CONTEXTS.get().peek();
	}

	public record Snapshot(int observedCubeCount, List<CubeGeometry> cubes) {
		private static final Snapshot EMPTY = new Snapshot(0, List.of());

		public Snapshot {
			cubes = List.copyOf(cubes);
		}
	}

	public record CubeGeometry(int cubeId, List<Vec3> corners) {
		public CubeGeometry {
			corners = List.copyOf(corners);
			if (corners.size() != 8)
				throw new IllegalArgumentException("A cube geometry requires exactly 8 corners");
		}
	}

	private static final class Context {
		private static final int NO_LAYER_OWNER = -1;

		private final CubeIdCache cubeIds;
		@Nullable
		private Deque<RenderLayerState> renderLayers;
		private int observedCubeCount;
		private final int expectedCubeCount;
		private final BitSet presentCubes;
		private final Map<Integer, Vec3> cubeOffsets;
		private final boolean collectGeometry;
		private final boolean renderSourceGeometry;
		@Nullable
		private final Vec3 cameraPosition;
		@Nullable
		private final List<CubeGeometry> geometry;
		@Nullable
		private final BitSet capturedGeometry;

		private Context(int expectedCubeCount, BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
			boolean collectGeometry, @Nullable Vec3 cameraPosition, boolean renderSourceGeometry,
			CubeIdCache cubeIds) {
			this.expectedCubeCount = expectedCubeCount;
			// A context cannot escape its synchronous render call. The caller-owned values
			// remain unchanged until end(), so copying them on every frame only creates garbage.
			this.presentCubes = presentCubes;
			this.cubeOffsets = cubeOffsets;
			this.collectGeometry = collectGeometry;
			this.cameraPosition = cameraPosition;
			this.renderSourceGeometry = renderSourceGeometry;
			this.cubeIds = cubeIds;
			geometry = collectGeometry ? new ArrayList<>() : null;
			capturedGeometry = collectGeometry ? new BitSet() : null;
		}

		private int idFor(Object cube) {
			int cubeId = cubeIds.idFor(cube);
			observedCubeCount = Math.max(observedCubeCount, cubeId + 1);
			return cubeId;
		}

		@Nullable
		private Integer registeredIdFor(Object cube) {
			return cubeIds.get(cube);
		}

		private void beginRenderLayer(Object sourceModel) {
			// Never choose from presentCubes: that would select a different owner for each
			// separated render and make the independent model appear on every component again.
			if (renderLayers == null)
				renderLayers = new ArrayDeque<>();
			renderLayers.push(new RenderLayerState(sourceModel,
				observedCubeCount > 0 ? 0 : NO_LAYER_OWNER));
		}

		private void endRenderLayer() {
			if (renderLayers != null && !renderLayers.isEmpty())
				renderLayers.pop();
		}

		private boolean isRenderLayerActive() {
			return renderLayers != null && !renderLayers.isEmpty();
		}

		private void recordRenderLayerConsumer(VertexConsumer consumer, @Nullable ResourceLocation texture) {
			RenderLayerState layer = renderLayers == null ? null : renderLayers.peek();
			if (layer != null)
				layer.recordConsumer(consumer, texture);
		}

		@Nullable
		private Object currentRenderLayerSourceModel() {
			RenderLayerState layer = renderLayers == null ? null : renderLayers.peek();
			return layer == null ? null : layer.sourceModel;
		}

		@Nullable
		private ResourceLocation currentRenderLayerTexture(VertexConsumer consumer) {
			RenderLayerState layer = renderLayers == null ? null : renderLayers.peek();
			return layer == null ? null : layer.texture(consumer);
		}

		private boolean bindCurrentRenderLayerTo(Object sourceCube) {
			RenderLayerState layer = renderLayers == null ? null : renderLayers.peek();
			if (layer == null)
				return false;
			Integer cubeId = registeredIdFor(sourceCube);
			if (cubeId == null)
				return false;
			layer.ownerCubeId = cubeId;
			return true;
		}

		@Nullable
		private Integer ownerForOriginalLayerCube(Object cube) {
			Integer registered = registeredIdFor(cube);
			if (registered != null)
				return registered;
			RenderLayerState layer = renderLayers == null ? null : renderLayers.peek();
			return layer == null ? null : layer.ownerCubeId;
		}

		private void associate(Object cube, int ownerCubeId) {
			if (ownerCubeId >= 0 && ownerCubeId < observedCubeCount)
				cubeIds.associate(cube, ownerCubeId);
		}

		private boolean isPresent(int cubeId) {
			return expectedCubeCount <= 0 || presentCubes.get(cubeId);
		}

		private void applyOffset(int cubeId, PoseStack poseStack) {
			Vec3 offset = offsetFor(cubeId);
			if (offset == null || offset.lengthSqr() < 1.0e-12d)
				return;
			poseStack.last().pose().translateLocal((float) offset.x, (float) offset.y, (float) offset.z);
		}

		@Nullable
		private Vec3 offsetFor(int cubeId) {
			return cubeOffsets.get(cubeId);
		}

		private void capture(int cubeId, PoseStack poseStack,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			if (capturedGeometry == null || geometry == null)
				return;
			if (capturedGeometry.get(cubeId))
				return;
			capturedGeometry.set(cubeId);
			Matrix4f pose = poseStack.last().pose();
			minX /= 16.0f;
			minY /= 16.0f;
			minZ /= 16.0f;
			maxX /= 16.0f;
			maxY /= 16.0f;
			maxZ /= 16.0f;

			List<Vec3> corners = new ArrayList<>(8);
			for (int z = 0; z < 2; z++) {
				for (int y = 0; y < 2; y++) {
					for (int x = 0; x < 2; x++) {
						Vector3f transformed = pose.transformPosition(new Vector3f(
							x == 0 ? minX : maxX,
							y == 0 ? minY : maxY,
							z == 0 ? minZ : maxZ));
						double cameraX = cameraPosition == null ? 0.0d : cameraPosition.x;
						double cameraY = cameraPosition == null ? 0.0d : cameraPosition.y;
						double cameraZ = cameraPosition == null ? 0.0d : cameraPosition.z;
						corners.add(new Vec3(transformed.x() + cameraX, transformed.y() + cameraY,
							transformed.z() + cameraZ));
					}
				}
			}
			geometry.add(new CubeGeometry(cubeId, corners));
		}

		private Snapshot snapshot() {
			return new Snapshot(observedCubeCount, geometry == null ? List.of() : geometry);
		}

	}

	/** Stable source/layer cube ids reused by repeated renders of the same preview model. */
	static final class CubeIdCache {
		private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
		private int nextId;

		private int idFor(Object cube) {
			Integer existing = ids.get(cube);
			if (existing != null)
				return existing;
			int cubeId = nextId++;
			ids.put(cube, cubeId);
			return cubeId;
		}

		@Nullable
		private Integer get(Object cube) {
			return ids.get(cube);
		}

		private void associate(Object cube, int ownerCubeId) {
			ids.putIfAbsent(cube, ownerCubeId);
		}
	}

	private static final class RenderLayerState {
		private final Object sourceModel;
		private final IdentityHashMap<VertexConsumer, ResourceLocation> texturesByConsumer = new IdentityHashMap<>();
		private int ownerCubeId;
		@Nullable
		private ResourceLocation lastTexture;

		private RenderLayerState(Object sourceModel, int ownerCubeId) {
			this.sourceModel = sourceModel;
			this.ownerCubeId = ownerCubeId;
		}

		private void recordConsumer(VertexConsumer consumer, @Nullable ResourceLocation texture) {
			lastTexture = texture;
			if (texture == null)
				texturesByConsumer.remove(consumer);
			else
				texturesByConsumer.put(consumer, texture);
		}

		@Nullable
		private ResourceLocation texture(VertexConsumer consumer) {
			return texturesByConsumer.getOrDefault(consumer, lastTexture);
		}
	}
}
