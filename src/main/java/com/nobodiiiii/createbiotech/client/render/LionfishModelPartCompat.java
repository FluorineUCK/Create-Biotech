package com.nobodiiiii.createbiotech.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.util.FastColor;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

/**
 * Optional, reflection-only bridge for Lionfish API's model hierarchy.
 *
 * <p>Lionfish's {@code AdvancedModelBox} deliberately does not extend vanilla's
 * {@code ModelPart}, so the normal slime-mimic ModelPart mixin cannot observe its cubes.
 * Keeping every Lionfish type behind reflection lets Create: Biotech remain loadable when
 * Lionfish and Cataclysm are absent.</p>
 */
final class LionfishModelPartCompat {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ADVANCED_MODEL_BOX =
		"com.github.L_Ender.lionfishapi.client.model.tools.AdvancedModelBox";
	private static final String BASIC_MODEL_PART =
		"com.github.L_Ender.lionfishapi.client.model.tools.BasicModelPart";
	private static final String BASIC_ENTITY_MODEL =
		"com.github.L_Ender.lionfishapi.client.model.tools.BasicEntityModel";
	private static final String MODEL_BOX =
		"com.github.L_Ender.lionfishapi.client.model.tools.LionfishModelRenderUtils$ModelBox";
	private static final String TEXTURED_QUAD =
		"com.github.L_Ender.lionfishapi.client.model.tools.LionfishModelRenderUtils$TexturedQuad";
	private static final String POSITION_TEXTURE_VERTEX =
		"com.github.L_Ender.lionfishapi.client.model.tools.LionfishModelRenderUtils$PositionTextureVertex";

	private static volatile Access access;
	private static volatile boolean accessResolved;
	private static volatile boolean disabled;
	private static final Map<Object, List<?>> CUBES = new IdentityHashMap<>();
	private static final Map<Object, List<?>> CHILDREN = new IdentityHashMap<>();
	private static final Map<Object, Object> MODELS = new IdentityHashMap<>();
	private static final Map<Object, Object> ROOTS = new IdentityHashMap<>();
	private static final Map<Object, CubeData> CUBE_DATA = new IdentityHashMap<>();

	private LionfishModelPartCompat() {}

	static boolean supports(Object part) {
		Access resolved = access(part);
		return resolved != null && resolved.advancedModelBox().isInstance(part);
	}

	static boolean isVisible(Object part) {
		return readBoolean(accessRequired(part).showModel(), part);
	}

	static List<?> cubes(Object part) {
		return CUBES.computeIfAbsent(part,
			ignored -> immutableElements(read(accessRequired(part).cubeList(), part), "cubeList"));
	}

	static List<?> children(Object part) {
		return CHILDREN.computeIfAbsent(part,
			ignored -> immutableElements(read(accessRequired(part).childModels(), part), "childModels"));
	}

	static Object model(Object part) {
		return MODELS.computeIfAbsent(part, ignored -> invoke(accessRequired(part).getModel(), part));
	}

	static Object root(Object model) {
		return ROOTS.computeIfAbsent(model, ignored -> invoke(accessRequired(model).root(), model));
	}

	static void translateAndRotate(Object part, PoseStack poseStack) {
		invoke(accessRequired(part).translateAndRotate(), part, poseStack);
	}

	static boolean scaleChildren(Object part) {
		return readBoolean(accessRequired(part).scaleChildren(), part);
	}

	static float xScale(Object part) {
		return readFloat(accessRequired(part).xScale(), part);
	}

	static float yScale(Object part) {
		return readFloat(accessRequired(part).yScale(), part);
	}

	static float zScale(Object part) {
		return readFloat(accessRequired(part).zScale(), part);
	}

	static CubeBounds bounds(Object cube) {
		return cubeData(cube).bounds();
	}

	static boolean cubeHasVisiblePixels(Object cube, NativeImage image) {
		if (!image.format().hasAlpha())
			return true;

		for (QuadData quad : cubeData(cube).quads()) {
			float minU = Float.POSITIVE_INFINITY;
			float minV = Float.POSITIVE_INFINITY;
			float maxU = Float.NEGATIVE_INFINITY;
			float maxV = Float.NEGATIVE_INFINITY;
			for (VertexData vertex : quad.vertices()) {
				float u = vertex.u();
				float v = vertex.v();
				minU = Math.min(minU, u);
				minV = Math.min(minV, v);
				maxU = Math.max(maxU, u);
				maxV = Math.max(maxV, v);
			}
			if (uvRangeHasVisiblePixels(image, minU, minV, maxU, maxV))
				return true;
		}
		return false;
	}

	static void compileCube(Object cube, PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
		int overlay, float red, float green, float blue, float alpha, float normalOffset) {
		Matrix4f poseMatrix = pose.pose();
		Matrix3f normalMatrix = pose.normal();

		for (QuadData quad : cubeData(cube).quads()) {
			Vector3f transformedNormal = normalMatrix.transform(
				new Vector3f(quad.normalX(), quad.normalY(), quad.normalZ()), new Vector3f());
			if (transformedNormal.lengthSquared() > 1.0e-7f)
				transformedNormal.normalize();
			else
				transformedNormal.zero();

			float normalX = transformedNormal.x();
			float normalY = transformedNormal.y();
			float normalZ = transformedNormal.z();
			for (VertexData vertex : quad.vertices()) {
				Vector4f transformedPosition = poseMatrix.transform(new Vector4f(
					vertex.x() / 16.0f,
					vertex.y() / 16.0f,
					vertex.z() / 16.0f,
					1.0f));
				consumer.addVertex(
						transformedPosition.x() + normalX * normalOffset,
						transformedPosition.y() + normalY * normalOffset,
						transformedPosition.z() + normalZ * normalOffset)
					.setColor(red, green, blue, alpha)
					.setUv(vertex.u(), vertex.v())
					.setOverlay(overlay)
					.setLight(packedLight)
					.setNormal(normalX, normalY, normalZ);
			}
		}
	}

	static void disable(Throwable exception) {
		synchronized (LionfishModelPartCompat.class) {
			if (!disabled)
				LOGGER.warn("Disabling Lionfish slime-mimic model compatibility after a reflection failure.", exception);
			access = null;
			accessResolved = true;
			disabled = true;
			clearCaches();
		}
	}

	static void clearCaches() {
		CUBES.clear();
		CHILDREN.clear();
		MODELS.clear();
		ROOTS.clear();
		CUBE_DATA.clear();
	}

	private static Access accessRequired(Object instance) {
		Access resolved = access(instance);
		if (resolved == null)
			throw new IllegalStateException("Lionfish model compatibility is unavailable");
		return resolved;
	}

	private static Access access(Object instance) {
		if (disabled)
			return null;
		if (accessResolved)
			return access;

		synchronized (LionfishModelPartCompat.class) {
			if (disabled)
				return null;
			if (accessResolved)
				return access;
			try {
				access = Access.create(instance.getClass().getClassLoader());
			} catch (RuntimeException e) {
				disable(e);
				return null;
			}
			accessResolved = true;
			return access;
		}
	}

	private static boolean uvRangeHasVisiblePixels(NativeImage image, float minU, float minV, float maxU,
		float maxV) {
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

	private static List<?> immutableElements(Object value, String name) {
		if (value instanceof Iterable<?> iterable) {
			List<Object> elements = new ArrayList<>();
			for (Object element : iterable)
				elements.add(element);
			return List.copyOf(elements);
		}
		throw new IllegalStateException("Lionfish " + name + " is not iterable");
	}

	private static CubeData cubeData(Object cube) {
		return CUBE_DATA.computeIfAbsent(cube, LionfishModelPartCompat::readCubeData);
	}

	private static CubeData readCubeData(Object cube) {
		Access resolved = accessRequired(cube);
		Object[] sourceQuads = objectArray(read(resolved.quads(), cube), "quads");
		List<QuadData> quads = new ArrayList<>(sourceQuads.length);
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (Object sourceQuad : sourceQuads) {
			Object[] sourceVertices = objectArray(read(resolved.vertices(), sourceQuad), "vertexPositions");
			List<VertexData> vertices = new ArrayList<>(sourceVertices.length);
			for (Object sourceVertex : sourceVertices) {
				Vector3f position = (Vector3f) read(resolved.position(), sourceVertex);
				VertexData vertex = new VertexData(position.x(), position.y(), position.z(),
					readFloat(resolved.textureU(), sourceVertex), readFloat(resolved.textureV(), sourceVertex));
				vertices.add(vertex);
				minX = Math.min(minX, vertex.x());
				minY = Math.min(minY, vertex.y());
				minZ = Math.min(minZ, vertex.z());
				maxX = Math.max(maxX, vertex.x());
				maxY = Math.max(maxY, vertex.y());
				maxZ = Math.max(maxZ, vertex.z());
			}
			Vector3f normal = (Vector3f) read(resolved.normal(), sourceQuad);
			quads.add(new QuadData(List.copyOf(vertices), normal.x(), normal.y(), normal.z()));
		}
		if (!Float.isFinite(minX) || !Float.isFinite(maxX))
			throw new IllegalStateException("Lionfish cube has no vertices");
		return new CubeData(List.copyOf(quads), new CubeBounds(minX, minY, minZ, maxX, maxY, maxZ));
	}

	private static Object[] objectArray(Object value, String name) {
		if (value instanceof Object[] array)
			return array;
		throw new IllegalStateException("Lionfish " + name + " is not an object array");
	}

	private static Object read(Field field, Object target) {
		try {
			return field.get(target);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to read Lionfish field " + field.getName(), e);
		}
	}

	private static boolean readBoolean(Field field, Object target) {
		try {
			return field.getBoolean(target);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to read Lionfish field " + field.getName(), e);
		}
	}

	private static float readFloat(Field field, Object target) {
		try {
			return field.getFloat(target);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to read Lionfish field " + field.getName(), e);
		}
	}

	private static void invoke(Method method, Object target, Object argument) {
		try {
			method.invoke(target, argument);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to invoke Lionfish method " + method.getName(), e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException("Lionfish method " + method.getName() + " failed", e.getCause());
		}
	}

	private static Object invoke(Method method, Object target) {
		try {
			return method.invoke(target);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new IllegalStateException("Failed to invoke Lionfish method " + method.getName(), e);
		}
	}

	private static Class<?> classForName(String name, ClassLoader loader) {
		try {
			return Class.forName(name, false, loader);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Failed to resolve Lionfish class " + name, e);
		}
	}

	private static Field field(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("Failed to resolve Lionfish field " + owner.getName() + "." + name, e);
		}
	}

	private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
		try {
			Method method = owner.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return method;
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("Failed to resolve Lionfish method " + owner.getName() + "." + name, e);
		}
	}

	record CubeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
	private record VertexData(float x, float y, float z, float u, float v) {}
	private record QuadData(List<VertexData> vertices, float normalX, float normalY, float normalZ) {}
	private record CubeData(List<QuadData> quads, CubeBounds bounds) {}

	private record Access(Class<?> advancedModelBox, Field cubeList, Field childModels, Field showModel,
		Field scaleChildren, Field xScale, Field yScale, Field zScale, Method translateAndRotate,
		Method getModel, Method root,
		Field quads, Field vertices, Field normal, Field position, Field textureU, Field textureV) {

		private static Access create(ClassLoader loader) {
			Class<?> advancedModelBox = classForName(ADVANCED_MODEL_BOX, loader);
			Class<?> basicModelPart = classForName(BASIC_MODEL_PART, loader);
			Class<?> basicEntityModel = classForName(BASIC_ENTITY_MODEL, loader);
			Class<?> modelBox = classForName(MODEL_BOX, loader);
			Class<?> texturedQuad = classForName(TEXTURED_QUAD, loader);
			Class<?> positionTextureVertex = classForName(POSITION_TEXTURE_VERTEX, loader);
			return new Access(
				advancedModelBox,
				field(advancedModelBox, "cubeList"),
				field(advancedModelBox, "childModels"),
				field(basicModelPart, "showModel"),
				field(advancedModelBox, "scaleChildren"),
				field(basicModelPart, "xScale"),
				field(basicModelPart, "yScale"),
				field(basicModelPart, "zScale"),
				method(advancedModelBox, "translateAndRotate", PoseStack.class),
				method(advancedModelBox, "getModel"),
				method(basicEntityModel, "root"),
				field(modelBox, "quads"),
				field(texturedQuad, "vertexPositions"),
				field(texturedQuad, "normal"),
				field(positionTextureVertex, "position"),
				field(positionTextureVertex, "textureU"),
				field(positionTextureVertex, "textureV"));
		}
	}
}
