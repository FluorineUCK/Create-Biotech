package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Client-only filter and geometry collector around the existing slime-mimic cube renderer. */
public final class SurgicalModelRenderContext {
	private static final ThreadLocal<Deque<Context>> CONTEXTS = ThreadLocal.withInitial(ArrayDeque::new);

	private SurgicalModelRenderContext() {}

	public static void begin(PoseStack poseStack, int expectedCubeCount, BitSet presentCubes,
		Map<Integer, Vec3> cubeOffsets,
		boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		CONTEXTS.get().push(new Context(expectedCubeCount, presentCubes, cubeOffsets,
			collectGeometry, cameraPosition));
	}

	public static Snapshot end() {
		Deque<Context> contexts = CONTEXTS.get();
		if (contexts.isEmpty())
			return Snapshot.EMPTY;
		Context context = contexts.pop();
		if (contexts.isEmpty())
			CONTEXTS.remove();
		return context.snapshot();
	}

	/** Called only after the existing texture-visibility check accepted the cube. */
	public static boolean prepareCube(ModelPart.Cube cube, PoseStack poseStack, boolean innerPass) {
		Context context = current();
		if (context == null)
			return true;

		int cubeId = context.idFor(cube);
		if (!context.isPresent(cubeId))
			return false;

		context.applyOffset(cubeId, poseStack);
		if (innerPass && context.collectGeometry)
			context.capture(cubeId, cube, poseStack);
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
		private final IdentityHashMap<ModelPart.Cube, Integer> cubeIds = new IdentityHashMap<>();
		private final int expectedCubeCount;
		private final BitSet presentCubes;
		private final Map<Integer, Vec3> cubeOffsets;
		private final boolean collectGeometry;
		@Nullable
		private final Vec3 cameraPosition;
		private final List<CubeGeometry> geometry = new ArrayList<>();
		private final BitSet capturedGeometry = new BitSet();

		private Context(int expectedCubeCount, BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
			boolean collectGeometry, @Nullable Vec3 cameraPosition) {
			this.expectedCubeCount = expectedCubeCount;
			this.presentCubes = (BitSet) presentCubes.clone();
			this.cubeOffsets = Map.copyOf(cubeOffsets);
			this.collectGeometry = collectGeometry;
			this.cameraPosition = cameraPosition;
		}

		private int idFor(ModelPart.Cube cube) {
			return cubeIds.computeIfAbsent(cube, ignored -> cubeIds.size());
		}

		private boolean isPresent(int cubeId) {
			return expectedCubeCount <= 0 || presentCubes.get(cubeId);
		}

		private void applyOffset(int cubeId, PoseStack poseStack) {
			Vec3 offset = cubeOffsets.get(cubeId);
			if (offset == null || offset.lengthSqr() < 1.0e-12d)
				return;
			poseStack.last().pose().translateLocal((float) offset.x, (float) offset.y, (float) offset.z);
		}

		private void capture(int cubeId, ModelPart.Cube cube, PoseStack poseStack) {
			if (capturedGeometry.get(cubeId))
				return;
			capturedGeometry.set(cubeId);
			Matrix4f pose = poseStack.last().pose();
			float minX = cube.minX / 16.0f;
			float minY = cube.minY / 16.0f;
			float minZ = cube.minZ / 16.0f;
			float maxX = cube.maxX / 16.0f;
			float maxY = cube.maxY / 16.0f;
			float maxZ = cube.maxZ / 16.0f;

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
			return new Snapshot(cubeIds.size(), geometry);
		}

	}
}
