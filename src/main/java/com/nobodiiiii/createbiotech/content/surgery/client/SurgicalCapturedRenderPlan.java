package com.nobodiiiii.createbiotech.content.surgery.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nobodiiiii.createbiotech.mixin.client.CompositeRenderStateAccessor;
import com.nobodiiiii.createbiotech.mixin.client.CompositeRenderTypeAccessor;
import com.nobodiiiii.createbiotech.mixin.client.TextureStateShardAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable, model-library-neutral output of one living-entity render.
 *
 * <p>The source renderer is executed once into a recording buffer. Consecutive six-quad
 * cuboids are recovered from the final vertex stream, deduplicated across base and layer
 * passes, and assigned stable surgical ids. Everything else is retained as original
 * geometry. This deliberately observes only the public {@link VertexConsumer} contract;
 * vanilla {@code ModelPart}, third-party model libraries and hand-written render layers require no
 * individual model bridge.</p>
 */
public final class SurgicalCapturedRenderPlan {
	private static final ResourceLocation SLIME_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/entity/slime/slime.png");
	private static final RenderType INNER_RENDER_TYPE = RenderType.entityCutoutNoCull(SLIME_TEXTURE);
	private static final RenderType OUTER_RENDER_TYPE = RenderType.entityTranslucent(SLIME_TEXTURE);
	private static final float SLIME_CENTER_Y = 20.0f / 16.0f;
	private static final float OUTER_INFLATE = 0.1f / 16.0f;
	private static final float THIN_EDGE = 0.05f / 16.0f;
	private static final float OVERLAY_EXPANSION_MAX = 1.1f / 16.0f;
	private static final float OVERLAY_CENTER_EPSILON = 0.1f / 16.0f;
	private static final float PARALLEL_DOT_MIN = 0.999f;
	private static final float POSITION_EPSILON = 2.0e-5f;
	private static final float POSITION_QUANTIZATION = 100_000.0f;

	private static final Map<ResourceLocation, AlphaMask> ALPHA_MASKS = new HashMap<>();
	private static ModelPart innerCube;
	private static ModelPart outerCube;
	private static final BitSet ALL_COMPONENTS = new BitSet();
	private static final Map<Integer, Vec3> NO_OFFSETS = Map.of();

	private final List<Component> components;
	private final List<SourceBatch> extras;

	private SurgicalCapturedRenderPlan(List<Component> components, List<SourceBatch> extras) {
		this.components = List.copyOf(components);
		this.extras = List.copyOf(extras);
	}

	static SurgicalCapturedRenderPlan capture(EntityRenderer<LivingEntity> renderer, LivingEntity preview,
		float yaw, float partialTick, int packedLight) {
		RecordingBuffer recording = new RecordingBuffer();
		PoseStack neutralPose = new PoseStack();
		try {
			renderer.render(preview, yaw, partialTick, neutralPose, recording, packedLight);
		} finally {
			recording.finish();
		}
		return build(recording.streams);
	}

	/**
	 * Replaces a live entity's complete renderer output without knowing which model
	 * library produced it. This entry point is intentionally above
	 * {@code LivingEntityRenderer}: independently owned layer models are part of the
	 * same capture and therefore become normal, separately recoverable components.
	 */
	@SuppressWarnings("unchecked")
	public static void renderSlimeMimic(EntityRenderer<?> renderer, LivingEntity entity, float yaw,
		float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		SurgicalCapturedRenderPlan frame = capture((EntityRenderer<LivingEntity>) renderer, entity,
			yaw, partialTick, packedLight);
		frame.render(poseStack, buffer, packedLight, 0, ALL_COMPONENTS, NO_OFFSETS,
			false, null, false);
	}

	int cubeCount() {
		return components.size();
	}

	SurgicalModelRenderContext.Snapshot render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		int expectedCubeCount, BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
		boolean collectGeometry, @Nullable Vec3 cameraPosition, boolean renderSourceGeometry) {
		if (renderSourceGeometry) {
			for (Component component : components)
				if (isPresent(component.id, expectedCubeCount, presentCubes))
					component.renderSource(poseStack, buffer, packedLight, cubeOffsets.get(component.id));
		} else {
			for (Component component : components) {
				if (!isPresent(component.id, expectedCubeCount, presentCubes))
					continue;
				if (component.preserveSource)
					component.renderSource(poseStack, buffer, packedLight, cubeOffsets.get(component.id));
				else
					component.renderSlime(poseStack, buffer, packedLight, cubeOffsets.get(component.id), false);
			}
			for (Component component : components)
				if (!component.preserveSource && isPresent(component.id, expectedCubeCount, presentCubes))
					component.renderSlime(poseStack, buffer, packedLight, cubeOffsets.get(component.id), true);
		}

		// Lines, text, beams and genuinely non-cuboid meshes are visual effects rather than
		// surgical topology. Preserve them exactly and keep them out of cube numbering.
		for (SourceBatch extra : extras)
			extra.render(poseStack, buffer, packedLight, null);

		if (!collectGeometry)
			return new SurgicalModelRenderContext.Snapshot(components.size(), List.of());
		return snapshot(poseStack, expectedCubeCount, presentCubes, cubeOffsets, cameraPosition);
	}

	SurgicalModelRenderContext.Snapshot snapshot(PoseStack poseStack, int expectedCubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, @Nullable Vec3 cameraPosition) {
		List<SurgicalModelRenderContext.CubeGeometry> geometry = new ArrayList<>(components.size());
		for (Component component : components) {
			if (!isPresent(component.id, expectedCubeCount, presentCubes))
				continue;
			Vec3 offset = cubeOffsets.get(component.id);
			geometry.add(component.geometry(poseStack, offset, cameraPosition));
		}
		return new SurgicalModelRenderContext.Snapshot(components.size(), geometry);
	}

	static void clearResources() {
		ALPHA_MASKS.clear();
		innerCube = null;
		outerCube = null;
	}

	private static SurgicalCapturedRenderPlan build(List<CaptureStream> streams) {
		Map<GeometryKey, ComponentBuilder> recovered = new LinkedHashMap<>();
		List<SourceBatch> extras = new ArrayList<>();
		int order = 0;

		for (CaptureStream stream : streams) {
			List<CapturedVertex> vertices = stream.vertices;
			if (stream.renderType.mode() != VertexFormat.Mode.QUADS) {
				if (!vertices.isEmpty())
					extras.add(new SourceBatch(stream.renderType, List.copyOf(vertices)));
				continue;
			}

			int cursor = 0;
			while (cursor + 24 <= vertices.size()) {
				List<CapturedVertex> candidateVertices = vertices.subList(cursor, cursor + 24);
				RecoveredCuboid cuboid = recoverCuboid(candidateVertices);
				if (cuboid == null) {
					addVisibleQuadExtra(stream.renderType, vertices, cursor, extras);
					cursor += 4;
					continue;
				}

				GeometryKey key = GeometryKey.of(cuboid.corners);
				ComponentBuilder builder = recovered.get(key);
				if (builder == null) {
					builder = new ComponentBuilder(order++, cuboid);
					recovered.put(key, builder);
				}
				if (hasVisibleQuad(stream.renderType, candidateVertices))
					builder.batches.add(new SourceBatch(stream.renderType, List.copyOf(candidateVertices)));
				cursor += 24;
			}

			while (cursor + 4 <= vertices.size()) {
				addVisibleQuadExtra(stream.renderType, vertices, cursor, extras);
				cursor += 4;
			}
			if (cursor < vertices.size())
				extras.add(new SourceBatch(stream.renderType, List.copyOf(vertices.subList(cursor, vertices.size()))));
		}

		List<ComponentBuilder> visible = recovered.values().stream()
			.filter(builder -> !builder.batches.isEmpty())
			.sorted(Comparator.comparingInt(builder -> builder.order))
			.toList();
		List<Component> components = new ArrayList<>(visible.size());
		for (int id = 0; id < visible.size(); id++) {
			ComponentBuilder builder = visible.get(id);
			components.add(builder.build(id, shouldPreserveSource(builder, visible)));
		}
		return new SurgicalCapturedRenderPlan(components, extras);
	}

	/**
	 * Besides truly flat boxes, keep close-fitting shells in their source material.
	 * Vanilla clothing is usually authored as a normal cuboid inflated by 0.25-0.5
	 * pixels (the villager jacket is additionally extended downwards), so testing only
	 * the shortest edge would incorrectly turn it into a second solid slime body.
	 */
	private static boolean shouldPreserveSource(ComponentBuilder candidate, List<ComponentBuilder> components) {
		RecoveredCuboid cuboid = candidate.cuboid;
		float shortestEdge = Math.min(cuboid.a.length(), Math.min(cuboid.b.length(), cuboid.c.length()));
		if (shortestEdge <= THIN_EDGE)
			return true;

		for (ComponentBuilder base : components) {
			if (base.order >= candidate.order || base == candidate)
				continue;
			if (isCloseFittingOverlay(cuboid, base.cuboid))
				return true;
		}
		return false;
	}

	private static boolean isCloseFittingOverlay(RecoveredCuboid candidate, RecoveredCuboid base) {
		Vector3f[] candidateEdges = { candidate.a, candidate.b, candidate.c };
		Vector3f[] baseEdges = { base.a, base.b, base.c };
		boolean[] usedBaseEdges = new boolean[3];
		Vector3f centerDelta = center(candidate).sub(center(base));
		float candidateVolume = 1.0f;
		float baseVolume = 1.0f;
		int closeExpandedAxes = 0;

		for (Vector3f candidateEdge : candidateEdges) {
			float candidateLength = candidateEdge.length();
			if (candidateLength <= POSITION_EPSILON)
				return false;
			Vector3f candidateAxis = new Vector3f(candidateEdge).div(candidateLength);
			int match = -1;
			for (int baseIndex = 0; baseIndex < baseEdges.length; baseIndex++) {
				if (usedBaseEdges[baseIndex])
					continue;
				float baseLength = baseEdges[baseIndex].length();
				if (baseLength <= POSITION_EPSILON)
					continue;
				float dot = Math.abs(candidateAxis.dot(new Vector3f(baseEdges[baseIndex]).div(baseLength)));
				if (dot >= PARALLEL_DOT_MIN) {
					match = baseIndex;
					break;
				}
			}
			if (match < 0)
				return false;

			usedBaseEdges[match] = true;
			float baseLength = baseEdges[match].length();
			float axialCenterDelta = Math.abs(centerDelta.dot(candidateAxis));
			if (axialCenterDelta > (candidateLength + baseLength) * 0.5f + POSITION_EPSILON)
				return false;

			float expansion = candidateLength - baseLength;
			if (expansion > POSITION_EPSILON && expansion <= OVERLAY_EXPANSION_MAX
				&& axialCenterDelta <= OVERLAY_CENTER_EPSILON)
				closeExpandedAxes++;
			candidateVolume *= candidateLength;
			baseVolume *= baseLength;
		}

		return closeExpandedAxes >= 2 && candidateVolume > baseVolume + POSITION_EPSILON;
	}

	private static Vector3f center(RecoveredCuboid cuboid) {
		return new Vector3f(cuboid.corners.getFirst())
			.add(new Vector3f(cuboid.a).mul(0.5f))
			.add(new Vector3f(cuboid.b).mul(0.5f))
			.add(new Vector3f(cuboid.c).mul(0.5f));
	}

	private static void addVisibleQuadExtra(RenderType renderType, List<CapturedVertex> vertices, int cursor,
		List<SourceBatch> extras) {
		List<CapturedVertex> quad = vertices.subList(cursor, cursor + 4);
		if (quadVisible(renderType, quad))
			extras.add(new SourceBatch(renderType, List.copyOf(quad)));
	}

	private static boolean hasVisibleQuad(RenderType renderType, List<CapturedVertex> vertices) {
		for (int vertex = 0; vertex + 4 <= vertices.size(); vertex += 4)
			if (quadVisible(renderType, vertices.subList(vertex, vertex + 4)))
				return true;
		return false;
	}

	private static boolean quadVisible(RenderType renderType, List<CapturedVertex> quad) {
		boolean vertexVisible = false;
		float minU = Float.POSITIVE_INFINITY;
		float minV = Float.POSITIVE_INFINITY;
		float maxU = Float.NEGATIVE_INFINITY;
		float maxV = Float.NEGATIVE_INFINITY;
		for (CapturedVertex vertex : quad) {
			vertexVisible |= vertex.alpha > 0;
			minU = Math.min(minU, vertex.u);
			minV = Math.min(minV, vertex.v);
			maxU = Math.max(maxU, vertex.u);
			maxV = Math.max(maxV, vertex.v);
		}
		if (!vertexVisible)
			return false;
		ResourceLocation texture = renderTypeTexture(renderType);
		return texture == null || alphaMask(texture).hasVisiblePixels(minU, minV, maxU, maxV);
	}

	@Nullable
	private static ResourceLocation renderTypeTexture(RenderType renderType) {
		if (!(renderType instanceof CompositeRenderTypeAccessor compositeAccessor))
			return null;
		RenderType.CompositeState state = compositeAccessor.createBiotech$getState();
		CompositeRenderStateAccessor stateAccessor = (CompositeRenderStateAccessor) (Object) state;
		Object textureState = stateAccessor.createBiotech$getTextureState();
		TextureStateShardAccessor textureAccessor = (TextureStateShardAccessor) textureState;
		return textureAccessor.createBiotech$getTexture().orElse(null);
	}

	private static AlphaMask alphaMask(ResourceLocation texture) {
		return ALPHA_MASKS.computeIfAbsent(texture, SurgicalCapturedRenderPlan::loadAlphaMask);
	}

	private static AlphaMask loadAlphaMask(ResourceLocation texture) {
		Resource resource = Minecraft.getInstance().getResourceManager().getResource(texture).orElse(null);
		if (resource == null)
			return AlphaMask.OPAQUE;
		try (InputStream stream = resource.open(); NativeImage image = NativeImage.read(stream)) {
			if (!image.format().hasAlpha())
				return AlphaMask.OPAQUE;
			int width = image.getWidth();
			int height = image.getHeight();
			BitSet pixels = new BitSet(width * height);
			for (int y = 0; y < height; y++)
				for (int x = 0; x < width; x++)
					if ((image.getPixelRGBA(x, y) >>> 24 & 0xff) != 0)
						pixels.set(y * width + x);
			return new AlphaMask(width, height, pixels, false);
		} catch (IOException | RuntimeException ignored) {
			return AlphaMask.OPAQUE;
		}
	}

	@Nullable
	private static RecoveredCuboid recoverCuboid(List<CapturedVertex> vertices) {
		List<Vector3f> points = uniquePositions(vertices);
		if (points.size() == 8)
			return recoverSolid(points);
		if (points.size() == 4)
			return recoverFlat(points);
		return null;
	}

	@Nullable
	private static RecoveredCuboid recoverSolid(List<Vector3f> points) {
		RecoveredCuboid best = null;
		float bestScore = Float.POSITIVE_INFINITY;
		for (int originIndex = 0; originIndex < points.size(); originIndex++) {
			Vector3f origin = points.get(originIndex);
			List<Vector3f> ends = new ArrayList<>(points);
			ends.remove(originIndex);
			for (int aIndex = 0; aIndex < ends.size(); aIndex++) {
				Vector3f endA = ends.get(aIndex);
				Vector3f a = new Vector3f(endA).sub(origin);
				for (int bIndex = aIndex + 1; bIndex < ends.size(); bIndex++) {
					Vector3f endB = ends.get(bIndex);
					Vector3f b = new Vector3f(endB).sub(origin);
					for (int cIndex = bIndex + 1; cIndex < ends.size(); cIndex++) {
						Vector3f endC = ends.get(cIndex);
						Vector3f c = new Vector3f(endC).sub(origin);
						float determinant = new Vector3f(a).cross(b).dot(c);
						if (Math.abs(determinant) <= 1.0e-8f)
							continue;
						List<Vector3f> corners = parallelepiped(origin, a, b, c);
						if (!samePointSet(corners, points))
							continue;
						float score = a.lengthSquared() + b.lengthSquared() + c.lengthSquared();
						if (score < bestScore) {
							bestScore = score;
							best = new RecoveredCuboid(corners, a, b, c);
						}
					}
				}
			}
		}
		return best;
	}

	@Nullable
	private static RecoveredCuboid recoverFlat(List<Vector3f> points) {
		RecoveredCuboid best = null;
		float bestScore = Float.POSITIVE_INFINITY;
		for (int originIndex = 0; originIndex < points.size(); originIndex++) {
			Vector3f origin = points.get(originIndex);
			List<Vector3f> ends = new ArrayList<>(points);
			ends.remove(originIndex);
			for (int aIndex = 0; aIndex < ends.size(); aIndex++) {
				Vector3f endA = ends.get(aIndex);
				Vector3f a = new Vector3f(endA).sub(origin);
				for (int bIndex = aIndex + 1; bIndex < ends.size(); bIndex++) {
					Vector3f endB = ends.get(bIndex);
					Vector3f b = new Vector3f(endB).sub(origin);
					if (new Vector3f(a).cross(b).lengthSquared() <= 1.0e-8f)
						continue;
					List<Vector3f> face = List.of(new Vector3f(origin), new Vector3f(origin).add(a),
						new Vector3f(origin).add(b), new Vector3f(origin).add(a).add(b));
					if (!samePointSet(face, points))
						continue;
					float score = a.lengthSquared() + b.lengthSquared();
					if (score < bestScore) {
						bestScore = score;
						Vector3f zero = new Vector3f();
						best = new RecoveredCuboid(parallelepiped(origin, a, b, zero), a, b, zero);
					}
				}
			}
		}
		return best;
	}

	private static List<Vector3f> parallelepiped(Vector3f origin, Vector3f a, Vector3f b, Vector3f c) {
		return List.of(
			new Vector3f(origin),
			new Vector3f(origin).add(a),
			new Vector3f(origin).add(b),
			new Vector3f(origin).add(a).add(b),
			new Vector3f(origin).add(c),
			new Vector3f(origin).add(a).add(c),
			new Vector3f(origin).add(b).add(c),
			new Vector3f(origin).add(a).add(b).add(c));
	}

	private static List<Vector3f> uniquePositions(List<CapturedVertex> vertices) {
		List<Vector3f> points = new ArrayList<>(8);
		for (CapturedVertex vertex : vertices) {
			Vector3f point = new Vector3f(vertex.x, vertex.y, vertex.z);
			if (findPoint(points, point) == null)
				points.add(point);
		}
		return points;
	}

	private static boolean samePointSet(List<Vector3f> first, List<Vector3f> second) {
		if (first.size() != second.size())
			return false;
		for (Vector3f point : first)
			if (findPoint(second, point) == null)
				return false;
		return true;
	}

	@Nullable
	private static Vector3f findPoint(List<Vector3f> points, Vector3f target) {
		for (Vector3f point : points)
			if (Math.abs(point.x - target.x) <= POSITION_EPSILON
				&& Math.abs(point.y - target.y) <= POSITION_EPSILON
				&& Math.abs(point.z - target.z) <= POSITION_EPSILON)
				return point;
		return null;
	}

	private static boolean isPresent(int cubeId, int expectedCubeCount, BitSet presentCubes) {
		return expectedCubeCount <= 0 || presentCubes.get(cubeId);
	}

	private static ModelPart innerCube() {
		if (innerCube == null)
			innerCube = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SLIME).getChild("cube");
		return innerCube;
	}

	private static ModelPart outerCube() {
		if (outerCube == null)
			outerCube = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SLIME_OUTER).getChild("cube");
		return outerCube;
	}

	private static int packed(int low, int high) {
		return low & 0xffff | (high & 0xffff) << 16;
	}

	private static final class RecordingBuffer implements MultiBufferSource {
		private final List<CaptureStream> streams = new ArrayList<>();

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			CaptureStream stream = new CaptureStream(renderType);
			streams.add(stream);
			return stream.consumer;
		}

		private void finish() {
			for (CaptureStream stream : streams)
				stream.consumer.finish();
		}
	}

	private static final class CaptureStream {
		private final RenderType renderType;
		private final List<CapturedVertex> vertices = new ArrayList<>();
		private final RecordingConsumer consumer = new RecordingConsumer(vertices);

		private CaptureStream(RenderType renderType) {
			this.renderType = renderType;
		}
	}

	private static final class RecordingConsumer implements VertexConsumer {
		private final List<CapturedVertex> target;
		@Nullable
		private MutableVertex current;

		private RecordingConsumer(List<CapturedVertex> target) {
			this.target = target;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			finish();
			current = new MutableVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			if (current != null) {
				current.red = red;
				current.green = green;
				current.blue = blue;
				current.alpha = alpha;
			}
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			if (current != null) {
				current.u = u;
				current.v = v;
			}
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			if (current != null) {
				current.overlayU = u;
				current.overlayV = v;
			}
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			if (current != null) {
				current.lightU = u;
				current.lightV = v;
			}
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			if (current != null) {
				current.normalX = x;
				current.normalY = y;
				current.normalZ = z;
			}
			return this;
		}

		private void finish() {
			if (current == null)
				return;
			target.add(current.freeze());
			current = null;
		}
	}

	private static final class MutableVertex {
		private final float x;
		private final float y;
		private final float z;
		private int red = 255;
		private int green = 255;
		private int blue = 255;
		private int alpha = 255;
		private float u;
		private float v;
		private int overlayU;
		private int overlayV;
		private int lightU;
		private int lightV;
		private float normalX;
		private float normalY = 1.0f;
		private float normalZ;

		private MutableVertex(float x, float y, float z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private CapturedVertex freeze() {
			return new CapturedVertex(x, y, z, red, green, blue, alpha, u, v,
				overlayU, overlayV, lightU, lightV, normalX, normalY, normalZ);
		}
	}

	private record CapturedVertex(float x, float y, float z, int red, int green, int blue, int alpha,
		float u, float v, int overlayU, int overlayV, int lightU, int lightV,
		float normalX, float normalY, float normalZ) {}

	private static final class ComponentBuilder {
		private final int order;
		private final RecoveredCuboid cuboid;
		private final List<SourceBatch> batches = new ArrayList<>();

		private ComponentBuilder(int order, RecoveredCuboid cuboid) {
			this.order = order;
			this.cuboid = cuboid;
		}

		private Component build(int id, boolean preserveSource) {
			return new Component(id, cuboid.corners, cuboid.a, cuboid.b, cuboid.c,
				preserveSource, List.copyOf(batches));
		}
	}

	private record RecoveredCuboid(List<Vector3f> corners, Vector3f a, Vector3f b, Vector3f c) {}

	private record GeometryKey(List<QuantizedPoint> points) {
		private static GeometryKey of(List<Vector3f> corners) {
			List<QuantizedPoint> points = corners.stream()
				.map(QuantizedPoint::of)
				.distinct()
				.sorted()
				.toList();
			return new GeometryKey(points);
		}
	}

	private record QuantizedPoint(int x, int y, int z) implements Comparable<QuantizedPoint> {
		private static QuantizedPoint of(Vector3f point) {
			return new QuantizedPoint(Math.round(point.x * POSITION_QUANTIZATION),
				Math.round(point.y * POSITION_QUANTIZATION), Math.round(point.z * POSITION_QUANTIZATION));
		}

		@Override
		public int compareTo(QuantizedPoint other) {
			int compareX = Integer.compare(x, other.x);
			if (compareX != 0)
				return compareX;
			int compareY = Integer.compare(y, other.y);
			return compareY != 0 ? compareY : Integer.compare(z, other.z);
		}
	}

	private static final class Component {
		private final int id;
		private final List<Vector3f> corners;
		private final Vector3f a;
		private final Vector3f b;
		private final Vector3f c;
		private final boolean preserveSource;
		private final List<SourceBatch> batches;

		private Component(int id, List<Vector3f> corners, Vector3f a, Vector3f b, Vector3f c,
			boolean preserveSource, List<SourceBatch> batches) {
			this.id = id;
			this.corners = corners.stream().map(Vector3f::new).toList();
			this.a = new Vector3f(a);
			this.b = new Vector3f(b);
			this.c = new Vector3f(c);
			this.preserveSource = preserveSource;
			this.batches = batches;
		}

		private void renderSource(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
			@Nullable Vec3 offset) {
			for (SourceBatch batch : batches)
				batch.render(poseStack, buffer, packedLight, offset);
		}

		private void renderSlime(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
			@Nullable Vec3 offset, boolean outer) {
			PoseStack cubePose = new PoseStack();
			cubePose.mulPose(new Matrix4f(poseStack.last().pose()));
			if (offset != null)
				cubePose.last().pose().translateLocal((float) offset.x, (float) offset.y, (float) offset.z);

			Vector3f edgeA = inflated(a, outer);
			Vector3f edgeB = inflated(b, outer);
			Vector3f edgeC = inflated(c, outer);
			Vector3f center = new Vector3f(corners.getFirst())
				.add(new Vector3f(a).mul(0.5f))
				.add(new Vector3f(b).mul(0.5f))
				.add(new Vector3f(c).mul(0.5f));
			Matrix4f transform = new Matrix4f().identity();
			transform.m00(2.0f * edgeA.x).m01(2.0f * edgeA.y).m02(2.0f * edgeA.z);
			transform.m10(2.0f * edgeB.x).m11(2.0f * edgeB.y).m12(2.0f * edgeB.z);
			transform.m20(2.0f * edgeC.x).m21(2.0f * edgeC.y).m22(2.0f * edgeC.z);
			transform.m30(center.x).m31(center.y).m32(center.z);
			cubePose.mulPose(transform);
			cubePose.translate(0.0f, -SLIME_CENTER_Y, 0.0f);

			int overlay = batches.isEmpty() || batches.getFirst().vertices.isEmpty()
				? OverlayTexture.NO_OVERLAY
				: packed(batches.getFirst().vertices.getFirst().overlayU,
					batches.getFirst().vertices.getFirst().overlayV);
			VertexConsumer consumer = buffer.getBuffer(outer ? OUTER_RENDER_TYPE : INNER_RENDER_TYPE);
			(outer ? outerCube() : innerCube()).render(cubePose, consumer, packedLight, overlay, 0xFFFFFFFF);
		}

		private SurgicalModelRenderContext.CubeGeometry geometry(PoseStack poseStack, @Nullable Vec3 offset,
			@Nullable Vec3 cameraPosition) {
			Matrix4f pose = poseStack.last().pose();
			double offsetX = offset == null ? 0.0d : offset.x;
			double offsetY = offset == null ? 0.0d : offset.y;
			double offsetZ = offset == null ? 0.0d : offset.z;
			double cameraX = cameraPosition == null ? 0.0d : cameraPosition.x;
			double cameraY = cameraPosition == null ? 0.0d : cameraPosition.y;
			double cameraZ = cameraPosition == null ? 0.0d : cameraPosition.z;
			List<Vec3> transformed = new ArrayList<>(8);
			for (Vector3f corner : corners) {
				Vector3f point = pose.transformPosition(corner, new Vector3f());
				transformed.add(new Vec3(point.x + offsetX + cameraX, point.y + offsetY + cameraY,
					point.z + offsetZ + cameraZ));
			}
			return new SurgicalModelRenderContext.CubeGeometry(id, transformed);
		}

		private static Vector3f inflated(Vector3f edge, boolean outer) {
			if (!outer)
				return new Vector3f(edge);
			float length = edge.length();
			if (length <= 1.0e-7f)
				return new Vector3f(edge);
			return new Vector3f(edge).mul((length + 2.0f * OUTER_INFLATE) / length);
		}
	}

	private record SourceBatch(RenderType renderType, List<CapturedVertex> vertices) {
		private void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, @Nullable Vec3 offset) {
			VertexConsumer consumer = buffer.getBuffer(renderType);
			Matrix4f pose = poseStack.last().pose();
			Matrix3f normal = poseStack.last().normal();
			float offsetX = offset == null ? 0.0f : (float) offset.x;
			float offsetY = offset == null ? 0.0f : (float) offset.y;
			float offsetZ = offset == null ? 0.0f : (float) offset.z;
			Vector4f position = new Vector4f();
			Vector3f transformedNormal = new Vector3f();
			for (CapturedVertex vertex : vertices) {
				position.set(vertex.x, vertex.y, vertex.z, 1.0f);
				pose.transform(position);
				transformedNormal.set(vertex.normalX, vertex.normalY, vertex.normalZ);
				normal.transform(transformedNormal);
				if (transformedNormal.lengthSquared() > 1.0e-8f)
					transformedNormal.normalize();
				int capturedLight = packed(vertex.lightU, vertex.lightV);
				int light = capturedLight == LightTexture.FULL_BRIGHT ? capturedLight : packedLight;
				consumer.addVertex(position.x + offsetX, position.y + offsetY, position.z + offsetZ)
					.setColor(vertex.red, vertex.green, vertex.blue, vertex.alpha)
					.setUv(vertex.u, vertex.v)
					.setUv1(vertex.overlayU, vertex.overlayV)
					.setUv2(light & 0xffff, light >>> 16 & 0xffff)
					.setNormal(transformedNormal.x, transformedNormal.y, transformedNormal.z);
			}
		}
	}

	private record AlphaMask(int width, int height, BitSet pixels, boolean opaque) {
		private static final AlphaMask OPAQUE = new AlphaMask(0, 0, new BitSet(), true);

		private boolean hasVisiblePixels(float minU, float minV, float maxU, float maxV) {
			if (opaque)
				return true;
			int minX = clamp((int) Math.floor(Math.min(minU, maxU) * width), width);
			int minY = clamp((int) Math.floor(Math.min(minV, maxV) * height), height);
			int maxX = clamp((int) Math.ceil(Math.max(minU, maxU) * width) - 1, width);
			int maxY = clamp((int) Math.ceil(Math.max(minV, maxV) * height) - 1, height);
			for (int y = minY; y <= maxY; y++) {
				int set = pixels.nextSetBit(y * width + minX);
				if (set >= 0 && set <= y * width + maxX)
					return true;
			}
			return false;
		}

		private static int clamp(int value, int maxExclusive) {
			return maxExclusive <= 0 ? 0 : Math.max(0, Math.min(value, maxExclusive - 1));
		}
	}
}
