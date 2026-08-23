package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableInteractionPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class SurgicalTableClientHandler {
	private static final int HIGHLIGHT_COLOR = 0x68C586;
	private static final Map<BlockPos, TableGeometry> TABLES = new HashMap<>();
	@Nullable
	private static Selection seamSelection;
	@Nullable
	private static Selection cubeSelection;

	private SurgicalTableClientHandler() {}

	public static void updateGeometry(SurgicalTableBlockEntity table,
		SurgicalModelRenderContext.Snapshot snapshot) {
		if (table.getLevel() == null || snapshot.observedCubeCount() <= 0)
			return;
		int cubeCount = snapshot.observedCubeCount();
		List<SurgicalAssembly.Seam> seams = table.getCubeCount() == cubeCount
			? table.getSeams()
			: SurgicalClientTopology.buildMinimumSpanningTree(cubeCount, snapshot.cubes());
		BitSet present = table.getPresentCubesForRender(cubeCount);
		Map<Integer, Vec3> offsets = SurgicalClientTopology.componentOffsets(cubeCount, present, seams,
			table.getCutSeamsForRender(), snapshot.cubes());
		TABLES.put(table.getBlockPos(), new TableGeometry(cubeCount, snapshot.cubes(), seams, offsets,
			table.getLevel().getGameTime()));
	}

	public static Map<Integer, Vec3> offsetsFor(SurgicalTableBlockEntity table) {
		TableGeometry geometry = TABLES.get(table.getBlockPos());
		if (geometry == null || !table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
			return Map.of();
		return geometry.offsets;
	}

	public static void clear() {
		TABLES.clear();
		clearSelections();
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.screen != null) {
			clearSelections();
			return;
		}

		long now = level.getGameTime();
		TABLES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenTick > 5
			|| !(level.getBlockEntity(entry.getKey()) instanceof SurgicalTableBlockEntity));

		boolean holdingShears = player.getMainHandItem().is(Items.SHEARS)
			|| player.getOffhandItem().is(Items.SHEARS);
		boolean holdingEmptyBox = isEmptyBox(player.getMainHandItem()) || isEmptyBox(player.getOffhandItem());
		seamSelection = holdingShears ? findSeamSelection(player, level) : null;
		cubeSelection = holdingEmptyBox ? findCubeSelection(player, level) : null;
	}

	@SubscribeEvent
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null || minecraft.player == null || !event.isUseItem())
			return;
		KeyMapping key = event.getKeyMapping();
		if (key != minecraft.options.keyUse)
			return;

		InteractionHand hand = event.getHand();
		ItemStack held = minecraft.player.getItemInHand(hand);
		SurgicalTableInteractionPacket.Action action;
		Selection selected;
		if (held.is(Items.SHEARS)) {
			action = SurgicalTableInteractionPacket.Action.CUT;
			selected = seamSelection;
		} else if (isEmptyBox(held)) {
			action = SurgicalTableInteractionPacket.Action.PACK;
			selected = cubeSelection;
		} else {
			return;
		}
		if (selected == null)
			return;

		CBPackets.sendToServer(new SurgicalTableInteractionPacket(selected.tablePos, hand, action,
			selected.targetId, selected.observedCubeCount, selected.seams));
		minecraft.player.swing(hand);
		event.setSwingHand(false);
		event.setCanceled(true);
	}

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || seamSelection == null)
			return;
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || (!player.getMainHandItem().is(Items.SHEARS)
			&& !player.getOffhandItem().is(Items.SHEARS)))
			return;

		Vec3 camera = event.getCamera().getPosition();
		PoseStack poseStack = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);

		Vec3 normal = seamSelection.face.get(1).subtract(seamSelection.face.get(0))
			.cross(seamSelection.face.get(3).subtract(seamSelection.face.get(0))).normalize();
		List<Vec3> face = seamSelection.face.stream()
			.map(point -> point.add(normal.scale(1.0d / 512.0d))).toList();
		for (int i = 0; i < 4; i++)
			renderLine(poseStack.last(), lines, face.get(i), face.get((i + 1) & 3));

		poseStack.popPose();
		buffers.endBatch(RenderType.lines());
	}

	@SubscribeEvent
	public static void hideVanillaTableOutline(RenderHighlightEvent.Block event) {
		BlockPos target = event.getTarget().getBlockPos();
		if (seamSelection != null && seamSelection.tablePos.equals(target)
			|| cubeSelection != null && cubeSelection.tablePos.equals(target))
			event.setCanceled(true);
	}

	@SubscribeEvent
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (!event.getLevel().isClientSide())
			return;
		clear();
		SurgicalSourceModelRenderer.clear();
	}

	@Nullable
	private static Selection findSeamSelection(LocalPlayer player, ClientLevel level) {
		Ray ray = playerRay(player);
		Selection best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<BlockPos, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos pos = entry.getKey();
			if (!(level.getBlockEntity(pos) instanceof SurgicalTableBlockEntity table) || !table.hasSubject())
				continue;
			TableGeometry geometry = entry.getValue();
			if (!table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				continue;
			BitSet present = table.getPresentCubesForRender(geometry.observedCubeCount);
			BitSet cutSeams = table.getCutSeamsForRender();
			for (int seamId = 0; seamId < geometry.seams.size(); seamId++) {
				SurgicalAssembly.Seam seam = geometry.seams.get(seamId);
				if (cutSeams.get(seamId) || !present.get(seam.first()) || !present.get(seam.second()))
					continue;
				List<Vec3> face = SurgicalClientTopology.seamFace(seam, geometry.cubes);
				Vec3 hit = face.size() == 4 ? intersectQuad(ray.start, ray.end, face) : null;
				if (hit == null)
					continue;
				double distance = ray.start.distanceToSqr(hit);
				if (distance >= bestDistance || isOccluded(level, player, ray.start, hit, pos))
					continue;
				bestDistance = distance;
				best = new Selection(pos, seamId, geometry.observedCubeCount, geometry.seams, face);
			}
		}
		return best;
	}

	@Nullable
	private static Selection findCubeSelection(LocalPlayer player, ClientLevel level) {
		Ray ray = playerRay(player);
		Selection best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<BlockPos, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos pos = entry.getKey();
			if (!(level.getBlockEntity(pos) instanceof SurgicalTableBlockEntity table) || !table.hasSubject())
				continue;
			TableGeometry geometry = entry.getValue();
			if (!table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				continue;
			for (SurgicalModelRenderContext.CubeGeometry cube : geometry.cubes) {
				if (!table.isCubePresentForRender(cube.cubeId(), geometry.observedCubeCount))
					continue;
				for (int[] faceIndices : SurgicalClientTopology.CUBE_FACES) {
					List<Vec3> face = face(cube, faceIndices);
					Vec3 hit = intersectQuad(ray.start, ray.end, face);
					if (hit == null)
						continue;
					double distance = ray.start.distanceToSqr(hit);
					if (distance >= bestDistance || isOccluded(level, player, ray.start, hit, pos))
						continue;
					bestDistance = distance;
					best = new Selection(pos, cube.cubeId(), geometry.observedCubeCount, geometry.seams, face);
				}
			}
		}
		return best;
	}

	private static Ray playerRay(LocalPlayer player) {
		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 start = player.getEyePosition();
		return new Ray(start, start.add(player.getViewVector(1.0f).scale(range)));
	}

	private static List<Vec3> face(SurgicalModelRenderContext.CubeGeometry cube, int[] indices) {
		return List.of(cube.corners().get(indices[0]), cube.corners().get(indices[1]),
			cube.corners().get(indices[2]), cube.corners().get(indices[3]));
	}

	private static boolean isOccluded(ClientLevel level, LocalPlayer player, Vec3 start, Vec3 hit,
		BlockPos tablePos) {
		BlockHitResult blockHit = level.clip(new ClipContext(start, hit, ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, player));
		return blockHit.getType() == HitResult.Type.BLOCK && !blockHit.getBlockPos().equals(tablePos)
			&& start.distanceToSqr(blockHit.getLocation()) + 1.0e-5d < start.distanceToSqr(hit);
	}

	@Nullable
	private static Vec3 intersectQuad(Vec3 start, Vec3 end, List<Vec3> face) {
		Vec3 edgeU = face.get(1).subtract(face.get(0));
		Vec3 edgeV = face.get(3).subtract(face.get(0));
		Vec3 normal = edgeU.cross(edgeV);
		Vec3 ray = end.subtract(start);
		double denominator = normal.dot(ray);
		if (Math.abs(denominator) < 1.0e-9d)
			return null;
		double t = normal.dot(face.get(0).subtract(start)) / denominator;
		if (t < 0.0d || t > 1.0d)
			return null;

		Vec3 hit = start.add(ray.scale(t));
		Vec3 relative = hit.subtract(face.get(0));
		double uu = edgeU.dot(edgeU);
		double uv = edgeU.dot(edgeV);
		double vv = edgeV.dot(edgeV);
		double ru = relative.dot(edgeU);
		double rv = relative.dot(edgeV);
		double determinant = uu * vv - uv * uv;
		if (Math.abs(determinant) < 1.0e-12d)
			return null;
		double u = (ru * vv - rv * uv) / determinant;
		double v = (rv * uu - ru * uv) / determinant;
		return u >= -1.0e-5d && u <= 1.00001d && v >= -1.0e-5d && v <= 1.00001d ? hit : null;
	}

	private static void renderLine(PoseStack.Pose pose, VertexConsumer consumer, Vec3 from, Vec3 to) {
		float dx = (float) (to.x - from.x);
		float dy = (float) (to.y - from.y);
		float dz = (float) (to.z - from.z);
		float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
		if (length <= 1.0e-6f)
			return;
		dx /= length;
		dy /= length;
		dz /= length;
		float red = (HIGHLIGHT_COLOR >> 16 & 0xff) / 255.0f;
		float green = (HIGHLIGHT_COLOR >> 8 & 0xff) / 255.0f;
		float blue = (HIGHLIGHT_COLOR & 0xff) / 255.0f;
		consumer.addVertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
			.setColor(red, green, blue, 1.0f).setNormal(pose.copy(), dx, dy, dz);
		consumer.addVertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
			.setColor(red, green, blue, 1.0f).setNormal(pose.copy(), dx, dy, dz);
	}

	private static boolean isEmptyBox(ItemStack stack) {
		return CapturedEntityBoxItem.isBox(stack) && !CapturedEntityBoxItem.hasAnyContents(stack);
	}

	private static void clearSelections() {
		seamSelection = null;
		cubeSelection = null;
	}

	private record TableGeometry(int observedCubeCount,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, List<SurgicalAssembly.Seam> seams,
		Map<Integer, Vec3> offsets, long lastSeenTick) {
		private TableGeometry {
			cubes = List.copyOf(cubes);
			seams = List.copyOf(seams);
			offsets = Map.copyOf(offsets);
		}
	}

	private record Selection(BlockPos tablePos, int targetId, int observedCubeCount,
		List<SurgicalAssembly.Seam> seams, List<Vec3> face) {}

	private record Ray(Vec3 start, Vec3 end) {}
}
