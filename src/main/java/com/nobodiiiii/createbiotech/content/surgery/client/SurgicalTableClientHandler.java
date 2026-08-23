package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTablePlane;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableInteractionPacket;
import com.nobodiiiii.createbiotech.network.CBPackets;

import net.createmod.catnip.outliner.Outliner;
import net.createmod.ponder.api.PonderPalette;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
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
	private static final int HIGHLIGHT_COLOR = PonderPalette.BLUE.getColor();
	private static final float HIGHLIGHT_LINE_WIDTH = 1.0f / 32.0f;
	private static final double MIN_SELECTION_THRESHOLD = 2.0d / 16.0d;
	private static final double MAX_SELECTION_THRESHOLD = 3.0d / 16.0d;
	private static final int ASYNC_TOPOLOGY_CUBE_THRESHOLD = 32;
	private static final List<Object> SEAM_OUTLINE_SLOTS = new ArrayList<>();
	private static final Map<BlockPos, TableGeometry> TABLES = new HashMap<>();
	private static int highlightedEdgeCount;
	@Nullable
	private static Selection seamSelection;
	@Nullable
	private static Selection cubeSelection;

	private SurgicalTableClientHandler() {}

	/** Returns true only when the immutable source-model geometry has to be captured again. */
	public static boolean needsGeometryUpdate(SurgicalTableBlockEntity table) {
		if (table.getLevel() == null || table.getProfile() == null)
			return false;
		TableGeometry geometry = TABLES.get(table.getBlockPos());
		if (geometry == null)
			return true;
		if (!geometry.matchesModel(table)) {
			TABLES.remove(table.getBlockPos());
			return true;
		}
		geometry.refresh(table);
		return false;
	}

	public static void updateGeometry(SurgicalTableBlockEntity table,
		SurgicalModelRenderContext.Snapshot snapshot) {
		MimicProfile profile = table.getProfile();
		if (table.getLevel() == null || profile == null || snapshot.observedCubeCount() <= 0)
			return;
		int cubeCount = snapshot.observedCubeCount();
		List<SurgicalAssembly.Seam> seams;
		List<SurgicalClientTopology.Contact> contacts;
		CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology = null;
		if (table.getCubeCount() == cubeCount) {
			seams = table.getSeams();
			if (cubeCount > ASYNC_TOPOLOGY_CUBE_THRESHOLD) {
				List<SurgicalAssembly.Seam> frozenSeams = List.copyOf(seams);
				List<SurgicalModelRenderContext.CubeGeometry> frozenCubes = List.copyOf(snapshot.cubes());
				contacts = List.of();
				pendingTopology = CompletableFuture.supplyAsync(() ->
					new SurgicalClientTopology.ContactTopology(frozenSeams,
						SurgicalClientTopology.contactsFor(frozenSeams, frozenCubes)));
			} else {
				contacts = SurgicalClientTopology.contactsFor(seams, snapshot.cubes());
			}
		} else {
			if (cubeCount > ASYNC_TOPOLOGY_CUBE_THRESHOLD) {
				List<SurgicalModelRenderContext.CubeGeometry> frozenCubes = List.copyOf(snapshot.cubes());
				seams = List.of();
				contacts = List.of();
				pendingTopology = CompletableFuture.supplyAsync(() ->
					SurgicalClientTopology.buildContactTopology(cubeCount, frozenCubes));
			} else {
				SurgicalClientTopology.ContactTopology topology =
					SurgicalClientTopology.buildContactTopology(cubeCount, snapshot.cubes());
				seams = topology.seams();
				contacts = topology.contacts();
			}
		}
		Direction facing = table.getBlockState().getValue(com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlock.FACING);
		TableGeometry geometry = new TableGeometry(profile, facing, cubeCount, snapshot.cubes(), seams, contacts,
			pendingTopology, table.getLevel().getGameTime());
		geometry.refresh(table);
		TABLES.put(table.getBlockPos(), geometry);
	}

	public static Map<Integer, Vec3> offsetsFor(SurgicalTableBlockEntity table) {
		TableGeometry geometry = TABLES.get(table.getBlockPos());
		if (geometry == null || !geometry.matchesModel(table))
			return Map.of();
		geometry.markSeen(table);
		return geometry.offsets;
	}

	public static BitSet presentCubesFor(SurgicalTableBlockEntity table, int observedCubeCount) {
		TableGeometry geometry = TABLES.get(table.getBlockPos());
		if (geometry != null && geometry.observedCubeCount == observedCubeCount && geometry.matchesModel(table))
			return geometry.presentCubes;
		return table.getPresentCubesForRender(observedCubeCount);
	}

	public static void clear() {
		TABLES.clear();
		SurgicalTablePoseResolver.clear();
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
		Ray ray = playerRay(player);
		CubeHit cubeHit = holdingShears || holdingEmptyBox
			? findNearestCubeHit(player, level, ray) : null;
		seamSelection = holdingShears ? findSeamSelection(player, level, ray, cubeHit) : null;
		cubeSelection = holdingEmptyBox ? findCubeSelection(cubeHit) : null;
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
		Ray ray = playerRay(minecraft.player);
		CubeHit cubeHit = findNearestCubeHit(minecraft.player, level, ray);
		if (held.is(Items.SHEARS)) {
			action = SurgicalTableInteractionPacket.Action.CUT;
			selected = findSeamSelection(minecraft.player, level, ray, cubeHit);
			seamSelection = selected;
		} else if (isEmptyBox(held)) {
			action = SurgicalTableInteractionPacket.Action.PACK;
			selected = findCubeSelection(cubeHit);
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
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && (belongsToSelectionPlane(level, target, seamSelection)
			|| belongsToSelectionPlane(level, target, cubeSelection)))
			event.setCanceled(true);
	}

	private static boolean belongsToSelectionPlane(ClientLevel level, BlockPos target,
		@Nullable Selection selection) {
		if (selection == null)
			return false;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, target);
		return selection.tablePos.equals(plane.owner());
	}

	@SubscribeEvent
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (!event.getLevel().isClientSide())
			return;
		clear();
		SurgicalSourceModelRenderer.clear();
	}

	@Nullable
	private static Selection findSeamSelection(LocalPlayer player, ClientLevel level, Ray ray,
		@Nullable CubeHit cubeHit) {
		Selection directHit = findDirectSeamSelection(player, level, ray);
		if (directHit != null)
			return directHit;
		if (cubeHit == null)
			return null;

		TableGeometry geometry = cubeHit.geometry;
		Selection best = null;
		double bestScore = Double.MAX_VALUE;
		double threshold = selectionThreshold(Math.sqrt(cubeHit.distanceSqr));
		for (SurgicalClientTopology.Contact contact : geometry.contactsFor(cubeHit.cubeId)) {
			SurgicalAssembly.Seam seam = contact.seam();
			if (seam.first() != cubeHit.cubeId && seam.second() != cubeHit.cubeId)
				continue;
			Integer seamId = geometry.seamIds.get(seam);
			if (seamId == null || geometry.cutSeams.get(seamId)
				|| !geometry.presentCubes.get(seam.first()) || !geometry.presentCubes.get(seam.second()))
				continue;
			double score = pointToContactDistance(cubeHit.location, contact) / threshold;
			if (score > 1.0d || score >= bestScore)
				continue;
			bestScore = score;
			best = new Selection(cubeHit.tablePos, seamId, geometry.observedCubeCount,
				geometry.seams, contact.edges());
		}
		return best;
	}

	@Nullable
	private static Selection findDirectSeamSelection(LocalPlayer player, ClientLevel level, Ray ray) {
		Selection best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<BlockPos, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos tablePos = entry.getKey();
			if (!(level.getBlockEntity(tablePos) instanceof SurgicalTableBlockEntity table)
				|| !table.hasSubject())
				continue;
			TableGeometry geometry = entry.getValue();
			if (!geometry.topologyReady()
				|| !table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				continue;
			for (SurgicalClientTopology.Contact contact : geometry.contacts) {
				SurgicalAssembly.Seam seam = contact.seam();
				Integer seamId = geometry.seamIds.get(seam);
				if (seamId == null || geometry.cutSeams.get(seamId)
					|| !geometry.presentCubes.get(seam.first()) || !geometry.presentCubes.get(seam.second()))
					continue;
				Vec3 hit = intersectContact(ray, contact);
				if (hit == null)
					continue;
				double distance = ray.start.distanceToSqr(hit);
				if (distance >= bestDistance || isOccluded(level, player, ray.start, hit, tablePos))
					continue;
				bestDistance = distance;
				best = new Selection(tablePos, seamId, geometry.observedCubeCount,
					geometry.seams, contact.edges());
			}
		}
		return best;
	}

	@Nullable
	private static Vec3 intersectContact(Ray ray, SurgicalClientTopology.Contact contact) {
		Vec3 nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (List<Vec3> face : contact.faces()) {
			Vec3 hit = intersectPolygon(ray.start, ray.end, face);
			if (hit == null)
				continue;
			double distance = ray.start.distanceToSqr(hit);
			if (distance < nearestDistance) {
				nearest = hit;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	@Nullable
	private static Selection findCubeSelection(@Nullable CubeHit hit) {
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
			if (!geometry.topologyReady()
				|| !table.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				continue;
			for (SurgicalModelRenderContext.CubeGeometry cube : geometry.cubes) {
				if (!geometry.presentCubes.get(cube.cubeId()))
					continue;
				for (int[] faceIndices : SurgicalClientTopology.CUBE_FACES) {
					Vec3 hit = intersectQuad(ray.start, ray.end, cube, faceIndices);
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
	private static Vec3 intersectQuad(Vec3 start, Vec3 end,
		SurgicalModelRenderContext.CubeGeometry cube, int[] indices) {
		Vec3 first = cube.corners().get(indices[0]);
		Vec3 edgeU = cube.corners().get(indices[1]).subtract(first);
		Vec3 edgeV = cube.corners().get(indices[3]).subtract(first);
		Vec3 normal = edgeU.cross(edgeV);
		Vec3 ray = end.subtract(start);
		double denominator = normal.dot(ray);
		if (Math.abs(denominator) < 1.0e-9d)
			return null;
		double t = normal.dot(first.subtract(start)) / denominator;
		if (t < 0.0d || t > 1.0d)
			return null;

		Vec3 hit = start.add(ray.scale(t));
		Vec3 relative = hit.subtract(first);
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

	@Nullable
	private static Vec3 intersectPolygon(Vec3 start, Vec3 end, List<Vec3> polygon) {
		if (polygon.size() < 3)
			return null;
		Vec3 origin = polygon.getFirst();
		Vec3 normal = Vec3.ZERO;
		for (int vertex = 1; vertex + 1 < polygon.size(); vertex++) {
			normal = polygon.get(vertex).subtract(origin)
				.cross(polygon.get(vertex + 1).subtract(origin));
			if (normal.lengthSqr() > 1.0e-12d)
				break;
		}
		if (normal.lengthSqr() <= 1.0e-12d)
			return null;
		normal = normal.normalize();
		Vec3 direction = end.subtract(start);
		double denominator = normal.dot(direction);
		if (Math.abs(denominator) <= 1.0e-9d)
			return null;
		double amount = normal.dot(origin.subtract(start)) / denominator;
		if (amount < 0.0d || amount > 1.0d)
			return null;
		Vec3 hit = start.add(direction.scale(amount));
		return insideConvexPolygon(hit, polygon, normal) ? hit : null;
	}

	private static double selectionThreshold(double distance) {
		return Math.max(MIN_SELECTION_THRESHOLD, Math.min(MAX_SELECTION_THRESHOLD, distance * 0.015d));
	}

	private static double pointToContactDistance(Vec3 point, SurgicalClientTopology.Contact contact) {
		double best = Double.MAX_VALUE;
		for (List<Vec3> face : contact.faces())
			best = Math.min(best, pointToPolygonDistance(point, face));
		return best;
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
		int edgeCount = selection.edges.size();
		if (edgeCount < 3)
			return;
		while (SEAM_OUTLINE_SLOTS.size() < edgeCount)
			SEAM_OUTLINE_SLOTS.add(new Object());
		for (int edge = 0; edge < edgeCount; edge++)
			Outliner.getInstance()
				.showLine(SEAM_OUTLINE_SLOTS.get(edge), selection.edges.get(edge).start(),
					selection.edges.get(edge).end())
				.lineWidth(HIGHLIGHT_LINE_WIDTH)
				.disableLineNormals()
				.colored(HIGHLIGHT_COLOR);
		for (int edge = edgeCount; edge < highlightedEdgeCount; edge++)
			Outliner.getInstance().remove(SEAM_OUTLINE_SLOTS.get(edge));
		highlightedEdgeCount = edgeCount;
	}

	private static void clearSeamHighlight() {
		for (int edge = 0; edge < highlightedEdgeCount; edge++)
			Outliner.getInstance().remove(SEAM_OUTLINE_SLOTS.get(edge));
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

	private static final class TableGeometry {
		private final MimicProfile profile;
		private final Direction facing;
		private final int observedCubeCount;
		private final List<SurgicalModelRenderContext.CubeGeometry> baseCubes;
		private List<SurgicalModelRenderContext.CubeGeometry> cubes;
		private List<SurgicalAssembly.Seam> seams;
		private Map<SurgicalAssembly.Seam, Integer> seamIds;
		private List<SurgicalClientTopology.Contact> baseContacts;
		private List<SurgicalClientTopology.Contact> contacts;
		private List<List<SurgicalClientTopology.Contact>> contactsByCube;
		@Nullable
		private CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology;
		private boolean topologyAvailable;
		private BitSet presentCubes = new BitSet();
		private BitSet cutSeams = new BitSet();
		private Map<Integer, Vec3> offsets = Map.of();
		private int renderRevision = Integer.MIN_VALUE;
		private long lastSeenTick;

		private TableGeometry(MimicProfile profile, Direction facing, int observedCubeCount,
			List<SurgicalModelRenderContext.CubeGeometry> cubes, List<SurgicalAssembly.Seam> seams,
			List<SurgicalClientTopology.Contact> contacts,
			@Nullable CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology,
			long lastSeenTick) {
			this.profile = profile;
			this.facing = facing;
			this.observedCubeCount = observedCubeCount;
			this.baseCubes = List.copyOf(cubes);
			this.cubes = this.baseCubes;
			this.seams = List.copyOf(seams);
			this.seamIds = seamIds(this.seams);
			this.baseContacts = List.copyOf(contacts);
			this.contacts = this.baseContacts;
			this.contactsByCube = contactsByCube(observedCubeCount, this.contacts);
			this.pendingTopology = pendingTopology;
			this.topologyAvailable = pendingTopology == null;
			this.lastSeenTick = lastSeenTick;
		}

		private boolean matchesModel(SurgicalTableBlockEntity table) {
			MimicProfile currentProfile = table.getProfile();
			return currentProfile != null && profile.equals(currentProfile)
				&& table.getBlockState().getValue(
					com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlock.FACING) == facing
				&& (table.getCubeCount() == 0 || table.getCubeCount() == observedCubeCount);
		}

		private void refresh(SurgicalTableBlockEntity table) {
			markSeen(table);
			boolean topologyChanged = resolvePendingTopology();
			int revision = table.getClientRenderRevision();
			if (revision == renderRevision && !topologyChanged)
				return;

			if (table.getCubeCount() == observedCubeCount && !seams.equals(table.getSeams())) {
				seams = List.copyOf(table.getSeams());
				seamIds = seamIds(seams);
				baseContacts = SurgicalClientTopology.contactsFor(seams, baseCubes);
			}
			presentCubes = table.getPresentCubesForRender(observedCubeCount);
			cutSeams = table.getCutSeamsForRender();
			Vec3 tableCenter = Vec3.atBottomCenterOf(table.getBlockPos()).add(0.0d, 1.01d, 0.0d);
			offsets = SurgicalClientTopology.componentOffsets(observedCubeCount, presentCubes, seams,
				cutSeams, baseCubes, table.getCutOrderForRender(), tableCenter);
			cubes = translateCubes(baseCubes, offsets);
			contacts = translateContacts(baseContacts, offsets);
			contactsByCube = contactsByCube(observedCubeCount, contacts);
			updateRenderBounds(table, cubes);
			renderRevision = revision;
		}

		private static void updateRenderBounds(SurgicalTableBlockEntity table,
			List<SurgicalModelRenderContext.CubeGeometry> cubes) {
			double minX = Double.POSITIVE_INFINITY;
			double minY = Double.POSITIVE_INFINITY;
			double minZ = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY;
			double maxY = Double.NEGATIVE_INFINITY;
			double maxZ = Double.NEGATIVE_INFINITY;
			for (SurgicalModelRenderContext.CubeGeometry cube : cubes) {
				for (Vec3 corner : cube.corners()) {
					minX = Math.min(minX, corner.x);
					minY = Math.min(minY, corner.y);
					minZ = Math.min(minZ, corner.z);
					maxX = Math.max(maxX, corner.x);
					maxY = Math.max(maxY, corner.y);
					maxZ = Math.max(maxZ, corner.z);
				}
			}
			if (minX != Double.POSITIVE_INFINITY)
				table.setClientRenderBounds(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
		}

		private boolean resolvePendingTopology() {
			if (pendingTopology == null || !pendingTopology.isDone())
				return false;
			SurgicalClientTopology.ContactTopology topology;
			try {
				topology = pendingTopology.join();
			} catch (RuntimeException exception) {
				pendingTopology = null;
				return false;
			}
			pendingTopology = null;
			seams = topology.seams();
			seamIds = seamIds(seams);
			baseContacts = topology.contacts();
			topologyAvailable = true;
			return true;
		}

		private boolean topologyReady() {
			return pendingTopology == null && topologyAvailable;
		}

		private void markSeen(SurgicalTableBlockEntity table) {
			if (table.getLevel() != null)
				lastSeenTick = table.getLevel().getGameTime();
		}

		private List<SurgicalClientTopology.Contact> contactsFor(int cubeId) {
			return cubeId >= 0 && cubeId < contactsByCube.size() ? contactsByCube.get(cubeId) : List.of();
		}

		private static List<SurgicalModelRenderContext.CubeGeometry> translateCubes(
			List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> offsets) {
			if (offsets.isEmpty())
				return cubes;
			List<SurgicalModelRenderContext.CubeGeometry> translated = new ArrayList<>(cubes.size());
			for (SurgicalModelRenderContext.CubeGeometry cube : cubes) {
				Vec3 offset = offsets.get(cube.cubeId());
				if (offset == null) {
					translated.add(cube);
					continue;
				}
				List<Vec3> corners = new ArrayList<>(cube.corners().size());
				for (Vec3 corner : cube.corners())
					corners.add(corner.add(offset));
				translated.add(new SurgicalModelRenderContext.CubeGeometry(cube.cubeId(), corners));
			}
			return List.copyOf(translated);
		}

		private static List<SurgicalClientTopology.Contact> translateContacts(
			List<SurgicalClientTopology.Contact> contacts, Map<Integer, Vec3> offsets) {
			if (offsets.isEmpty())
				return contacts;
			List<SurgicalClientTopology.Contact> translated = new ArrayList<>(contacts.size());
			for (SurgicalClientTopology.Contact contact : contacts) {
				Vec3 offset = offsets.get(contact.anchorCubeId());
				if (offset == null) {
					translated.add(contact);
					continue;
				}
				List<List<Vec3>> faces = new ArrayList<>(contact.faces().size());
				for (List<Vec3> face : contact.faces()) {
					List<Vec3> translatedFace = new ArrayList<>(face.size());
					for (Vec3 point : face)
						translatedFace.add(point.add(offset));
					faces.add(translatedFace);
				}
				translated.add(new SurgicalClientTopology.Contact(contact.seam(), contact.anchorCubeId(), faces));
			}
			return List.copyOf(translated);
		}

		private static List<List<SurgicalClientTopology.Contact>> contactsByCube(int cubeCount,
			List<SurgicalClientTopology.Contact> contacts) {
			List<List<SurgicalClientTopology.Contact>> byCube = new ArrayList<>(cubeCount);
			for (int cube = 0; cube < cubeCount; cube++)
				byCube.add(new ArrayList<>());
			for (SurgicalClientTopology.Contact contact : contacts) {
				SurgicalAssembly.Seam seam = contact.seam();
				if (seam.first() >= 0 && seam.first() < cubeCount)
					byCube.get(seam.first()).add(contact);
				if (seam.second() >= 0 && seam.second() < cubeCount)
					byCube.get(seam.second()).add(contact);
			}
			for (int cube = 0; cube < cubeCount; cube++)
				byCube.set(cube, List.copyOf(byCube.get(cube)));
			return List.copyOf(byCube);
		}
	}

	private record Selection(BlockPos tablePos, int targetId, int observedCubeCount,
		List<SurgicalAssembly.Seam> seams, List<SurgicalClientTopology.Edge> edges) {}

	private record Ray(Vec3 start, Vec3 end) {}

	private record CubeHit(BlockPos tablePos, TableGeometry geometry, int cubeId,
		Vec3 location, double distanceSqr) {}
}
