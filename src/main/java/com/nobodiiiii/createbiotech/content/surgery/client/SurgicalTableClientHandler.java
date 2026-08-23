package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableInteractionPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class SurgicalTableClientHandler {
	private static final int HIGHLIGHT_DARK_COLOR = 0x68C586;
	private static final int HIGHLIGHT_LIGHT_COLOR = 0x88E5A6;
	private static final float HIGHLIGHT_LINE_WIDTH = 1.0f / 32.0f;
	private static final double MIN_SELECTION_THRESHOLD = 2.0d / 16.0d;
	private static final double MAX_SELECTION_THRESHOLD = 3.0d / 16.0d;
	private static final Object[] SEAM_OUTLINE_SLOTS = {
		new Object(), new Object(), new Object(), new Object(),
		new Object(), new Object(), new Object(), new Object(),
		new Object(), new Object(), new Object(), new Object(),
		new Object(), new Object(), new Object(), new Object()
	};
	private static final Map<BlockPos, TableGeometry> TABLES = new HashMap<>();
	private static int highlightedEdgeCount;
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
		Object profileIdentity = table.getProfile();
		TableGeometry previous = TABLES.get(table.getBlockPos());
		List<SurgicalAssembly.Seam> seams;
		List<SurgicalClientTopology.Contact> contacts;
		if (table.getCubeCount() == cubeCount) {
			seams = table.getSeams();
			contacts = SurgicalClientTopology.contactsFor(seams, snapshot.cubes());
		} else if (previous != null && previous.profileIdentity == profileIdentity
			&& previous.observedCubeCount == cubeCount) {
			seams = previous.seams;
			contacts = SurgicalClientTopology.contactsFor(seams, snapshot.cubes());
		} else {
			SurgicalClientTopology.ContactTopology topology =
				SurgicalClientTopology.buildContactTopology(cubeCount, snapshot.cubes());
			seams = topology.seams();
			contacts = topology.contacts();
		}
		BitSet present = table.getPresentCubesForRender(cubeCount);
		BitSet cutSeams = table.getCutSeamsForRender();
		Map<Integer, Vec3> offsets = SurgicalClientTopology.componentOffsets(cubeCount, present, seams,
			cutSeams, snapshot.cubes());
		TABLES.put(table.getBlockPos(), new TableGeometry(profileIdentity, cubeCount, snapshot.cubes(), seams,
			seamIds(seams), contacts, present, cutSeams, offsets, table.getLevel().getGameTime()));
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
	}

	@SubscribeEvent
	public static void onRenderFrame(RenderFrameEvent.Pre event) {
		updateSelections();
	}

	private static void updateSelections() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.screen != null) {
			clearSelections();
			return;
		}

		boolean holdingShears = player.getMainHandItem().is(Items.SHEARS)
			|| player.getOffhandItem().is(Items.SHEARS);
		boolean holdingEmptyBox = isEmptyBox(player.getMainHandItem()) || isEmptyBox(player.getOffhandItem());
		seamSelection = holdingShears ? findSeamSelection(player, level) : null;
		cubeSelection = holdingEmptyBox ? findCubeSelection(player, level) : null;
		if (seamSelection != null)
			highlightSeam(seamSelection);
		else
			clearSeamHighlight();
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
		ClientLevel level = minecraft.level;
		if (level == null)
			return;
		SurgicalTableInteractionPacket.Action action;
		Selection selected;
		if (held.is(Items.SHEARS)) {
			action = SurgicalTableInteractionPacket.Action.CUT;
			selected = findSeamSelection(minecraft.player, level);
			seamSelection = selected;
		} else if (isEmptyBox(held)) {
			action = SurgicalTableInteractionPacket.Action.PACK;
			selected = findCubeSelection(minecraft.player, level);
			cubeSelection = selected;
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
		CubeHit cubeHit = findNearestCubeHit(player, level, ray);
		if (cubeHit == null)
			return null;

		TableGeometry geometry = cubeHit.geometry;
		Selection best = null;
		double bestScore = Double.MAX_VALUE;
		double threshold = selectionThreshold(Math.sqrt(cubeHit.distanceSqr));
		for (SurgicalClientTopology.Contact contact : geometry.contacts) {
			SurgicalAssembly.Seam seam = contact.seam();
			if (seam.first() != cubeHit.cubeId && seam.second() != cubeHit.cubeId)
				continue;
			Integer seamId = geometry.seamIds.get(seam);
			if (seamId == null || geometry.cutSeams.get(seamId)
				|| !geometry.presentCubes.get(seam.first()) || !geometry.presentCubes.get(seam.second()))
				continue;
			double score = pointToPolygonDistance(cubeHit.location, contact.polygon()) / threshold;
			if (score > 1.0d || score >= bestScore)
				continue;
			bestScore = score;
			best = new Selection(cubeHit.tablePos, seamId, geometry.observedCubeCount,
				geometry.seams, contact.polygon());
		}
		return best;
	}

	@Nullable
	private static Selection findCubeSelection(LocalPlayer player, ClientLevel level) {
		Ray ray = playerRay(player);
		CubeHit hit = findNearestCubeHit(player, level, ray);
		return hit == null ? null : new Selection(hit.tablePos, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of());
	}

	@Nullable
	private static CubeHit findNearestCubeHit(LocalPlayer player, ClientLevel level, Ray ray) {
		CubeHit best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<BlockPos, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos pos = entry.getKey();
			if (!(level.getBlockEntity(pos) instanceof SurgicalTableBlockEntity table) || !table.hasSubject())
				continue;
			TableGeometry geometry = entry.getValue();
			if (!table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				continue;
			for (SurgicalModelRenderContext.CubeGeometry cube : geometry.cubes) {
				if (!geometry.presentCubes.get(cube.cubeId()))
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
					best = new CubeHit(pos, geometry, cube.cubeId(), hit, distance);
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

	private static Map<SurgicalAssembly.Seam, Integer> seamIds(List<SurgicalAssembly.Seam> seams) {
		Map<SurgicalAssembly.Seam, Integer> result = new HashMap<>();
		for (int seamId = 0; seamId < seams.size(); seamId++)
			result.put(seams.get(seamId), seamId);
		return Map.copyOf(result);
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

	private static double selectionThreshold(double distance) {
		return Math.max(MIN_SELECTION_THRESHOLD, Math.min(MAX_SELECTION_THRESHOLD, distance * 0.015d));
	}

	private static double pointToPolygonDistance(Vec3 point, List<Vec3> polygon) {
		if (polygon.size() < 3)
			return Double.MAX_VALUE;
		Vec3 origin = polygon.getFirst();
		Vec3 normal = Vec3.ZERO;
		for (int i = 1; i + 1 < polygon.size(); i++) {
			normal = polygon.get(i).subtract(origin).cross(polygon.get(i + 1).subtract(origin));
			if (normal.lengthSqr() > 1.0e-12d)
				break;
		}
		if (normal.lengthSqr() <= 1.0e-12d)
			return Double.MAX_VALUE;
		normal = normal.normalize();
		double planeDistance = point.subtract(origin).dot(normal);
		Vec3 projected = point.subtract(normal.scale(planeDistance));
		if (insideConvexPolygon(projected, polygon, normal))
			return Math.abs(planeDistance);

		double best = Double.MAX_VALUE;
		for (int edge = 0; edge < polygon.size(); edge++)
			best = Math.min(best, pointToSegmentDistance(point, polygon.get(edge),
				polygon.get((edge + 1) % polygon.size())));
		return best;
	}

	private static boolean insideConvexPolygon(Vec3 point, List<Vec3> polygon, Vec3 normal) {
		double winding = 0.0d;
		for (int edge = 0; edge < polygon.size(); edge++) {
			Vec3 start = polygon.get(edge);
			Vec3 end = polygon.get((edge + 1) % polygon.size());
			double side = end.subtract(start).cross(point.subtract(start)).dot(normal);
			if (Math.abs(side) <= 1.0e-9d)
				continue;
			if (winding == 0.0d)
				winding = Math.signum(side);
			else if (Math.signum(side) != winding)
				return false;
		}
		return true;
	}

	private static double pointToSegmentDistance(Vec3 point, Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		double lengthSquared = segment.lengthSqr();
		if (lengthSquared <= 1.0e-12d)
			return point.distanceTo(start);
		double amount = point.subtract(start).dot(segment) / lengthSquared;
		amount = Math.max(0.0d, Math.min(1.0d, amount));
		return point.distanceTo(start.add(segment.scale(amount)));
	}

	private static void highlightSeam(Selection selection) {
		int edgeCount = Math.min(selection.polygon.size(), SEAM_OUTLINE_SLOTS.length);
		if (edgeCount < 3)
			return;
		int color = AnimationTickHolder.getTicks() % 16 < 8
			? HIGHLIGHT_DARK_COLOR : HIGHLIGHT_LIGHT_COLOR;
		for (int edge = 0; edge < edgeCount; edge++)
			Outliner.getInstance()
				.showLine(SEAM_OUTLINE_SLOTS[edge], selection.polygon.get(edge),
					selection.polygon.get((edge + 1) % edgeCount))
				.lineWidth(HIGHLIGHT_LINE_WIDTH)
				.disableLineNormals()
				.colored(color);
		for (int edge = edgeCount; edge < highlightedEdgeCount; edge++)
			Outliner.getInstance().remove(SEAM_OUTLINE_SLOTS[edge]);
		highlightedEdgeCount = edgeCount;
	}

	private static void clearSeamHighlight() {
		for (int edge = 0; edge < highlightedEdgeCount; edge++)
			Outliner.getInstance().remove(SEAM_OUTLINE_SLOTS[edge]);
		highlightedEdgeCount = 0;
	}

	private static boolean isEmptyBox(ItemStack stack) {
		return CapturedEntityBoxItem.isBox(stack) && !CapturedEntityBoxItem.hasCapturedEntity(stack);
	}

	private static void clearSelections() {
		seamSelection = null;
		cubeSelection = null;
		clearSeamHighlight();
	}

	private record TableGeometry(Object profileIdentity, int observedCubeCount,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, List<SurgicalAssembly.Seam> seams,
		Map<SurgicalAssembly.Seam, Integer> seamIds, List<SurgicalClientTopology.Contact> contacts,
		BitSet presentCubes, BitSet cutSeams, Map<Integer, Vec3> offsets, long lastSeenTick) {
		private TableGeometry {
			cubes = List.copyOf(cubes);
			seams = List.copyOf(seams);
			seamIds = Map.copyOf(seamIds);
			contacts = List.copyOf(contacts);
			presentCubes = (BitSet) presentCubes.clone();
			cutSeams = (BitSet) cutSeams.clone();
			offsets = Map.copyOf(offsets);
		}
	}

	private record Selection(BlockPos tablePos, int targetId, int observedCubeCount,
		List<SurgicalAssembly.Seam> seams, List<Vec3> polygon) {}

	private record Ray(Vec3 start, Vec3 end) {}

	private record CubeHit(BlockPos tablePos, TableGeometry geometry, int cubeId,
		Vec3 location, double distanceSqr) {}
}
