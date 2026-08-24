package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** Immutable table-local vertex stream captured from one pinned preview render. */
final class SurgicalTableRenderMesh {
	private static final int FLOATS_PER_VERTEX = 8;
	private static final int INTS_PER_VERTEX = 3;
	private static final Vector3f POSITION_SCRATCH = new Vector3f();
	private static final Vector3f NORMAL_SCRATCH = new Vector3f();

	private final RenderType[] renderTypes;
	private final int[] groupVertexEnds;
	private final float[] geometry;
	private final int[] attributes;

	private SurgicalTableRenderMesh(RenderType[] renderTypes, int[] groupVertexEnds, float[] geometry,
		int[] attributes) {
		this.renderTypes = renderTypes;
		this.groupVertexEnds = groupVertexEnds;
		this.geometry = geometry;
		this.attributes = attributes;
	}

	void render(PoseStack poseStack, MultiBufferSource buffer) {
		PoseStack.Pose pose = poseStack.last();
		Matrix4f matrix = pose.pose();
		int vertex = 0;
		for (int group = 0; group < renderTypes.length; group++) {
			VertexConsumer consumer = buffer.getBuffer(renderTypes[group]);
			for (int end = groupVertexEnds[group]; vertex < end; vertex++) {
				int floatBase = vertex * FLOATS_PER_VERTEX;
				int intBase = vertex * INTS_PER_VERTEX;
				matrix.transformPosition(geometry[floatBase], geometry[floatBase + 1], geometry[floatBase + 2],
					POSITION_SCRATCH);
				pose.transformNormal(geometry[floatBase + 5], geometry[floatBase + 6], geometry[floatBase + 7],
					NORMAL_SCRATCH);
				int color = attributes[intBase];
				consumer.addVertex(POSITION_SCRATCH.x(), POSITION_SCRATCH.y(), POSITION_SCRATCH.z())
					.setColor(color >>> 24, color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF)
					.setUv(geometry[floatBase + 3], geometry[floatBase + 4])
					.setOverlay(attributes[intBase + 1])
					.setLight(attributes[intBase + 2])
					.setNormal(NORMAL_SCRATCH.x(), NORMAL_SCRATCH.y(), NORMAL_SCRATCH.z());
			}
		}
	}

	static Builder builder() {
		return new Builder();
	}

	static final class Builder implements MultiBufferSource {
		private static final int MAX_VERTICES = 262_144;
		private static final int INITIAL_VERTEX_CAPACITY = 256;
		private final Map<RenderType, VertexStore> stores = new LinkedHashMap<>();
		private int totalVertices;
		private boolean invalid;

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			return stores.computeIfAbsent(renderType, ignored -> new VertexStore());
		}

		SurgicalTableRenderMesh build() {
			List<Map.Entry<RenderType, VertexStore>> groups = new ArrayList<>();
			int vertexTotal = 0;
			for (Map.Entry<RenderType, VertexStore> entry : stores.entrySet()) {
				VertexStore store = entry.getValue();
				if (store.vertexStarted)
					invalid = true;
				if (store.vertices == 0)
					continue;
				groups.add(entry);
				vertexTotal += store.vertices;
			}
			if (invalid)
				return null;

			RenderType[] renderTypes = new RenderType[groups.size()];
			int[] groupVertexEnds = new int[groups.size()];
			float[] geometry = new float[vertexTotal * FLOATS_PER_VERTEX];
			int[] attributes = new int[vertexTotal * INTS_PER_VERTEX];
			int vertexOffset = 0;
			for (int group = 0; group < groups.size(); group++) {
				Map.Entry<RenderType, VertexStore> entry = groups.get(group);
				VertexStore store = entry.getValue();
				System.arraycopy(store.geometry, 0, geometry, vertexOffset * FLOATS_PER_VERTEX,
					store.vertices * FLOATS_PER_VERTEX);
				System.arraycopy(store.attributes, 0, attributes, vertexOffset * INTS_PER_VERTEX,
					store.vertices * INTS_PER_VERTEX);
				vertexOffset += store.vertices;
				renderTypes[group] = entry.getKey();
				groupVertexEnds[group] = vertexOffset;
			}
			return new SurgicalTableRenderMesh(renderTypes, groupVertexEnds, geometry, attributes);
		}

		private final class VertexStore implements VertexConsumer {
			private float[] geometry = new float[INITIAL_VERTEX_CAPACITY * FLOATS_PER_VERTEX];
			private int[] attributes = new int[INITIAL_VERTEX_CAPACITY * INTS_PER_VERTEX];
			private int vertices;
			private boolean vertexStarted;
			private float x;
			private float y;
			private float z;
			private float u;
			private float v;
			private int color;
			private int overlay;
			private int light;

			@Override
			public VertexConsumer addVertex(float x, float y, float z) {
				if (vertexStarted)
					invalid = true;
				vertexStarted = true;
				this.x = x;
				this.y = y;
				this.z = z;
				u = 0.0f;
				v = 0.0f;
				color = 0xFFFFFFFF;
				overlay = 0;
				light = 0;
				return this;
			}

			@Override
			public VertexConsumer setColor(int red, int green, int blue, int alpha) {
				color = (red & 0xFF) << 24 | (green & 0xFF) << 16 | (blue & 0xFF) << 8 | alpha & 0xFF;
				return this;
			}

			@Override
			public VertexConsumer setUv(float u, float v) {
				this.u = u;
				this.v = v;
				return this;
			}

			@Override
			public VertexConsumer setUv1(int u, int v) {
				overlay = u | v << 16;
				return this;
			}

			@Override
			public VertexConsumer setUv2(int u, int v) {
				light = u | v << 16;
				return this;
			}

			@Override
			public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
				if (!vertexStarted || totalVertices >= MAX_VERTICES) {
					invalid = true;
					vertexStarted = false;
					return this;
				}
				ensureCapacity(vertices + 1);
				int floatBase = vertices * FLOATS_PER_VERTEX;
				geometry[floatBase] = x;
				geometry[floatBase + 1] = y;
				geometry[floatBase + 2] = z;
				geometry[floatBase + 3] = u;
				geometry[floatBase + 4] = v;
				geometry[floatBase + 5] = normalX;
				geometry[floatBase + 6] = normalY;
				geometry[floatBase + 7] = normalZ;
				int intBase = vertices * INTS_PER_VERTEX;
				attributes[intBase] = color;
				attributes[intBase + 1] = overlay;
				attributes[intBase + 2] = light;
				vertices++;
				totalVertices++;
				vertexStarted = false;
				return this;
			}

			private void ensureCapacity(int requiredVertices) {
				if (requiredVertices * FLOATS_PER_VERTEX <= geometry.length)
					return;
				int capacity = Math.min(MAX_VERTICES, Math.max(requiredVertices, vertices * 2));
				float[] expandedGeometry = new float[capacity * FLOATS_PER_VERTEX];
				System.arraycopy(geometry, 0, expandedGeometry, 0, vertices * FLOATS_PER_VERTEX);
				geometry = expandedGeometry;
				int[] expandedAttributes = new int[capacity * INTS_PER_VERTEX];
				System.arraycopy(attributes, 0, expandedAttributes, 0, vertices * INTS_PER_VERTEX);
				attributes = expandedAttributes;
			}
		}
	}
}
