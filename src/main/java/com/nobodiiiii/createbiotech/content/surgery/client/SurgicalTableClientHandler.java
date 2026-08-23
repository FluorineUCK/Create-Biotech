package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.cardboardbox.LargeCardboardBoxItem;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalGlueJoint;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlock;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableLayout;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTablePlane;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableInteractionPacket;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableGluePacket;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTablePlacementPacket;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalSubject;
import com.nobodiiiii.createbiotech.content.smartglue.SmartSuperGlueItem;
import com.nobodiiiii.createbiotech.entity.SlimeBionicEntity;
import com.nobodiiiii.createbiotech.foundation.render.EntityGeometry;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.glue.SuperGlueItem;

import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.ponder.api.PonderPalette;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class SurgicalTableClientHandler {
	private static final int SEAM_HIGHLIGHT_COLOR = PonderPalette.RED.getColor();
	private static final int CUBE_HIGHLIGHT_COLOR = PonderPalette.BLUE.getColor();
	private static final float HIGHLIGHT_LINE_WIDTH = 1.0f / 32.0f;
	private static final double MIN_SELECTION_THRESHOLD = 2.0d / 16.0d;
	private static final double MAX_SELECTION_THRESHOLD = 3.0d / 16.0d;
	private static final int ASYNC_TOPOLOGY_CUBE_THRESHOLD = 32;
	private static final List<Object> SEAM_OUTLINE_SLOTS = new ArrayList<>();
	private static final List<Object> CUBE_OUTLINE_SLOTS = new ArrayList<>();
	private static final Object PLACEMENT_OUTLINE_SLOT = new Object();
	private static final Map<SubjectKey, TableGeometry> TABLES = new HashMap<>();
	private static int highlightedSeamEdgeCount;
	private static int highlightedCubeEdgeCount;
	@Nullable
	private static Selection seamSelection;
	@Nullable
	private static Selection cubeSelection;
	@Nullable
	private static Selection componentSelection;
	@Nullable
	private static PendingCut pendingCut;
	@Nullable
	private static PendingGlue pendingGlue;
	@Nullable
	private static PlacementSource placementSource;
	@Nullable
	private static PlacementPreview placementPreview;

	private SurgicalTableClientHandler() {}

	/** Returns true only when the immutable source-model geometry has to be captured again. */
	public static boolean needsGeometryUpdate(SurgicalTableBlockEntity table, SurgicalSubject subject) {
		if (table.getLevel() == null)
			return false;
		SubjectKey key = new SubjectKey(table.getBlockPos(), subject.id());
		TableGeometry geometry = TABLES.get(key);
		if (geometry == null)
			return true;
		if (!geometry.matchesModel(table, subject)) {
			TABLES.remove(key);
			return true;
		}
		geometry.refresh(table, subject);
		return false;
	}

	public static void updateGeometry(SurgicalTableBlockEntity table, SurgicalSubject subject,
		SurgicalModelRenderContext.Snapshot snapshot) {
		MimicProfile profile = subject.profile();
		if (table.getLevel() == null || snapshot.observedCubeCount() <= 0)
			return;
		int cubeCount = snapshot.observedCubeCount();
		List<SurgicalAssembly.Seam> seams;
		List<SurgicalClientTopology.Contact> contacts;
		CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology = null;
		if (subject.cubeCount() == cubeCount) {
			seams = subject.seams();
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
		TableGeometry geometry = new TableGeometry(subject.id(), profile, subject.placementFacing(),
			subject.originOffsetX(), subject.originOffsetZ(), cubeCount, snapshot.cubes(), seams, contacts, pendingTopology,
			table.getLevel().getGameTime());
		geometry.refresh(table, subject);
		TABLES.put(new SubjectKey(table.getBlockPos(), subject.id()), geometry);
	}

	public static Map<Integer, Vec3> offsetsFor(SurgicalTableBlockEntity table, SurgicalSubject subject) {
		TableGeometry geometry = TABLES.get(new SubjectKey(table.getBlockPos(), subject.id()));
		if (geometry == null || !geometry.matchesModel(table, subject))
			return Map.of();
		geometry.markSeen(table);
		return geometry.offsets;
	}

	public static BitSet presentCubesFor(SurgicalTableBlockEntity table, SurgicalSubject subject,
		int observedCubeCount) {
		TableGeometry geometry = TABLES.get(new SubjectKey(table.getBlockPos(), subject.id()));
		if (geometry != null && geometry.observedCubeCount == observedCubeCount
			&& geometry.matchesModel(table, subject))
			return geometry.presentCubes;
		return subject.presentCubesForRender(observedCubeCount);
	}

	public static void clear() {
		pendingCut = null;
		pendingGlue = null;
		placementSource = null;
		clearPlacementPreview();
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
			abortPendingCut();
			pendingGlue = null;
			clearPlacementPreview();
			clearSelections();
			return;
		}

		long now = level.getGameTime();
		TABLES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenTick > 5
			|| !(level.getBlockEntity(entry.getKey().tablePos) instanceof SurgicalTableBlockEntity table)
			|| !table.hasSubject(entry.getKey().subjectId));
		if (pendingCut != null && !TABLES.containsKey(new SubjectKey(pendingCut.tablePos, pendingCut.subjectId)))
			abortPendingCut();
		if (pendingGlue != null && (!TABLES.containsKey(new SubjectKey(pendingGlue.selection.tablePos,
			pendingGlue.selection.subjectId)) || !isStandardGlue(player.getItemInHand(pendingGlue.hand))))
			pendingGlue = null;
	}

	@SubscribeEvent
	public static void onRenderFrame(RenderFrameEvent.Pre event) {
		updatePlacementPreview();
		updateSelections();
	}

	private static void updatePlacementPreview() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.screen != null || pendingCut != null
			|| !(minecraft.hitResult instanceof BlockHitResult hit)
			|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof SurgicalTableBlock)) {
			clearPlacementPreview();
			return;
		}

		InteractionHand hand = null;
		PlacementSource source = null;
		for (InteractionHand candidate : InteractionHand.values()) {
			ItemStack held = player.getItemInHand(candidate);
			if (!(held.getItem() instanceof CapturedEntityBoxItem)
				|| !CapturedEntityBoxHelper.hasCapturedEntity(held))
				continue;
			PlacementSource prepared = placementSourceFor(held, level);
			if (prepared != null) {
				hand = candidate;
				source = prepared;
				break;
			}
		}
		if (hand == null || source == null) {
			clearPlacementPreview();
			return;
		}

		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, hit.getBlockPos());
		if (!plane.valid() || plane.source() == null || plane.workArea().isEmpty()) {
			clearPlacementPreview();
			return;
		}
		List<SurgicalTableLayout.Footprint> occupied = SurgicalTablePlane.occupiedFootprints(level, plane, -1);
		if (occupied == null) {
			clearPlacementPreview();
			return;
		}
		Vec3 target = tableSurfaceTarget(playerRay(player), plane.workArea().y() + 1.01d);
		BlockPos ownerPos = plane.source();
		Direction placementFacing = player.getDirection();
		boolean projectSourceGeometry = SurgicalTableRenderer.projectsSourceGeometry(level, plane);
		PlacementGeometry placementGeometry = source.measure(placementFacing, projectSourceGeometry);
		if (placementGeometry == null) {
			clearPlacementPreview();
			return;
		}
		EntityGeometry.Bounds localBounds = placementGeometry.bounds();
		SurgicalModelRenderContext.CubeGeometry renderedBounds = boundsGeometry(localBounds,
			Vec3.atLowerCornerOf(ownerPos));
		SurgicalClientTopology.PlacementPlan plan = SurgicalClientTopology.planInitialPlacement(
			List.of(renderedBounds), plane.workArea(), target.x, target.z, occupied);
		if (plan == null) {
			clearPlacementPreview();
			return;
		}
		List<SurgicalTableLayout.Proposal> sourceLayouts = compositeSourceLayouts(source, placementGeometry,
			ownerPos, plan, plane, occupied);
		if (sourceLayouts == null) {
			clearPlacementPreview();
			return;
		}

		placementPreview = new PlacementPreview(ownerPos, hand, source, placementFacing, plan,
			placementGeometry.cubeOffsets(), placementGeometry.sources(), sourceLayouts, projectSourceGeometry);
		SurgicalTableLayout.Footprint footprint = plan.proposal().footprints().getFirst();
		Outliner.getInstance().showAABB(PLACEMENT_OUTLINE_SLOT,
			new AABB(footprint.minX(), plane.workArea().y() + 1.002d, footprint.minZ(),
				footprint.maxX(), plane.workArea().y() + 1.012d, footprint.maxZ()))
			.colored(PonderPalette.GREEN.getColor())
			.disableLineNormals()
			.lineWidth(HIGHLIGHT_LINE_WIDTH);
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.place_subject"), true);
	}

	@Nullable
	private static List<SurgicalTableLayout.Proposal> compositeSourceLayouts(PlacementSource source,
		PlacementGeometry geometry, BlockPos ownerPos, SurgicalClientTopology.PlacementPlan plan,
		SurgicalTablePlane.Plane plane, List<SurgicalTableLayout.Footprint> occupied) {
		if (!source.isComposite())
			return List.of();
		if (source.assembly == null || geometry.sources().size() != source.assembly.sources().size())
			return null;
		Vec3 placementOffset = Vec3.atLowerCornerOf(ownerPos)
			.add(plan.originOffsetX(), 0.0d, plan.originOffsetZ());
		List<SurgicalTableLayout.Proposal> layouts = new ArrayList<>(geometry.sources().size());
		for (SourcePlacementGeometry sourceGeometry : geometry.sources()) {
			SurgicalAssembly.PlacedSource placedSource = sourceGeometry.placedSource();
			SurgicalAssembly.Source assemblySource = placedSource.source();
			List<SurgicalModelRenderContext.CubeGeometry> worldCubes = translateCubes(
				sourceGeometry.baseCubes(), placementOffset);
			SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.preserveCompositeLayout(
				assemblySource.cubeCount(), assemblySource.presentCubes(), assemblySource.seams(),
				assemblySource.cutSeams(), worldCubes, sourceGeometry.renderOffsets(), plane.workArea(), occupied);
			if (planned == null)
				return null;
			layouts.add(planned.proposal());
		}
		return List.copyOf(layouts);
	}

	@Nullable
	private static PlacementSource placementSourceFor(ItemStack held, ClientLevel level) {
		if (placementSource != null && ItemStack.isSameItemSameComponents(placementSource.box, held))
			return placementSource;
		Entity captured = CapturedEntityBoxHelper.createCapturedEntity(held, level);
		MimicProfile profile;
		SurgicalAssembly assembly = null;
		if (captured instanceof SlimeBionicEntity bionic) {
			assembly = bionic.getAssembly();
			if (assembly == null)
				return null;
			profile = assembly.profile();
		} else if (captured instanceof LivingEntity living && SlimeMimicHandler.isSlimeMimic(living)) {
			profile = MimicProfile.capture(living);
			if (profile == null)
				return null;
		} else {
			return null;
		}
		placementSource = new PlacementSource(held.copyWithCount(1), profile, assembly);
		return placementSource;
	}

	private static void clearPlacementPreview() {
		placementPreview = null;
		Outliner.getInstance().remove(PLACEMENT_OUTLINE_SLOT);
	}

	@SubscribeEvent
	public static void renderPlacementPreview(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || placementPreview == null)
			return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null)
			return;
		PlacementPreview placement = placementPreview;

		Vec3 camera = event.getCamera().getPosition();
		PoseStack poseStack = event.getPoseStack();
		poseStack.pushPose();
		poseStack.translate(placement.ownerPos.getX() - camera.x, placement.ownerPos.getY() - camera.y,
			placement.ownerPos.getZ() - camera.z);
		poseStack.translate(placement.plan.originOffsetX(), 0.0d, placement.plan.originOffsetZ());
		MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
		int light = LevelRenderer.getLightColor(minecraft.level, placement.ownerPos.above());
		float partialTicks = AnimationTickHolder.getPartialTicks();
		if (placement.source.isComposite()) {
			for (SourcePlacementGeometry sourceGeometry : placement.sourceGeometries) {
				SurgicalAssembly.PlacedSource placedSource = sourceGeometry.placedSource();
				SurgicalAssembly.Source assemblySource = placedSource.source();
				LivingEntity preview = placement.source.preview(assemblySource.profile());
				if (preview == null)
					continue;
				poseStack.pushPose();
				poseStack.translate(placedSource.originOffset().x, placedSource.originOffset().y,
					placedSource.originOffset().z);
				SurgicalTablePoseResolver.resolve(placement.source, assemblySource.profile(), preview,
					placedSource.facing()).apply(poseStack);
				SurgicalSourceModelRenderer.render(preview, assemblySource.cubeCount(),
					assemblySource.presentCubes(), sourceGeometry.renderOffsets(), poseStack, buffer, light,
					0.0f, partialTicks, false, camera, placement.projectSourceGeometry);
				poseStack.popPose();
			}
		} else {
			LivingEntity preview = placement.source.preview();
			if (preview != null) {
				SurgicalTablePoseResolver.resolve(placement.source, placement.source.profile, preview,
					placement.facing).apply(poseStack);
				SurgicalSourceModelRenderer.render(preview, placement.source.cubeCount(),
					placement.source.presentCubes(), placement.cubeOffsets, poseStack, buffer, light,
					0.0f, partialTicks, false, camera, placement.projectSourceGeometry);
			}
		}
		poseStack.popPose();
		buffer.endBatch();
	}

	private static void updateSelections() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.screen != null) {
			abortPendingCut();
			clearSelections();
			return;
		}
		if (pendingCut != null) {
			updatePendingCut(player, level);
			return;
		}

		boolean holdingShears = player.getMainHandItem().is(Items.SHEARS)
			|| player.getOffhandItem().is(Items.SHEARS);
		boolean holdingEmptyBox = isEmptyBox(player.getMainHandItem()) || isEmptyBox(player.getOffhandItem());
		boolean holdingEmptyLargeBox = isEmptyLargeBox(player.getMainHandItem())
			|| isEmptyLargeBox(player.getOffhandItem());
		boolean holdingGlue = isStandardGlue(player.getMainHandItem()) || isStandardGlue(player.getOffhandItem());
		boolean highlightingDirectConnections = holdingShears && player.isShiftKeyDown();
		Ray ray = playerRay(player);
		CubeHit cubeHit = holdingShears || holdingEmptyBox || holdingGlue
			? findNearestCubeHit(player, level, ray) : null;
		seamSelection = holdingShears && !highlightingDirectConnections
			? findSeamSelection(player, level, ray, cubeHit) : null;
		cubeSelection = holdingEmptyBox ? findCubeSelection(cubeHit) : null;
		componentSelection = holdingGlue
			? findConnectedComponentSelection(cubeHit)
			: highlightingDirectConnections
			? findDirectConnectionSelection(cubeHit)
			: !holdingShears && holdingEmptyLargeBox ? findConnectedComponentSelection(cubeHit) : null;
		if (componentSelection != null)
			highlightSelection(componentSelection);
		else if (seamSelection != null)
			highlightSelection(seamSelection);
		else
			clearSeamHighlight();
	}

	@SubscribeEvent
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null || minecraft.player == null)
			return;
		KeyMapping key = event.getKeyMapping();
		if (pendingCut != null && key == minecraft.options.keyAttack
			&& minecraft.player.isShiftKeyDown()) {
			abortPendingCut();
			event.setSwingHand(false);
			event.setCanceled(true);
			return;
		}
		if (key != minecraft.options.keyUse || !event.isUseItem())
			return;

		InteractionHand hand = event.getHand();
		ItemStack held = minecraft.player.getItemInHand(hand);
		ClientLevel level = minecraft.level;
		if (level == null)
			return;
		if (pendingCut != null) {
			confirmPendingCut(minecraft.player);
			consumeInteraction(event, hand);
			return;
		}
		if (pendingGlue != null && minecraft.player.isShiftKeyDown()) {
			pendingGlue = null;
			clearSelections();
			consumeInteraction(event, hand);
			return;
		}
		if (CapturedEntityBoxHelper.hasCapturedEntity(held)
			&& tryPlaceSubject(minecraft.player, level, hand, held)) {
			consumeInteraction(event, hand);
			return;
		}

		Selection selected;
		Ray ray = playerRay(minecraft.player);
		CubeHit cubeHit = findNearestCubeHit(minecraft.player, level, ray);
		if (isStandardGlue(held)) {
			if (handleGlueClick(minecraft.player, level, hand, cubeHit))
				consumeInteraction(event, hand);
			return;
		} else if (held.is(Items.SHEARS)) {
			if (minecraft.player.isShiftKeyDown()) {
				selected = findDirectConnectionCutSelection(cubeHit);
				componentSelection = selected;
				seamSelection = null;
				if (selected == null)
					return;
				SurgicalClientTopology.PlannedLayout planned = planBatchCut(level, selected);
				if (planned == null) {
					showNoSpace(minecraft.player);
					consumeInteraction(event, hand);
					return;
				}
				sendInteraction(selected, hand, SurgicalTableInteractionPacket.Action.CUT_CUBE_CONNECTIONS,
					planned.proposal());
			} else {
				selected = findSeamSelection(minecraft.player, level, ray, cubeHit);
				seamSelection = selected;
				if (selected == null)
					return;
				beginSingleCut(level, selected, hand);
			}
		} else if (isEmptyBox(held)) {
			selected = findCubeSelection(cubeHit);
			cubeSelection = selected;
			if (selected == null)
				return;
			sendInteraction(selected, hand, SurgicalTableInteractionPacket.Action.PACK,
				SurgicalTableLayout.Proposal.EMPTY);
		} else {
			return;
		}
		consumeInteraction(event, hand);
	}

	private static boolean handleGlueClick(LocalPlayer player, ClientLevel level, InteractionHand hand,
		@Nullable CubeHit hit) {
		if (hit == null)
			return pendingGlue != null;
		Selection selected = findCubeSelection(hit);
		if (selected == null)
			return false;
		Vec3 localHit = hit.location.subtract(Vec3.atLowerCornerOf(hit.tablePos));
		if (pendingGlue == null) {
			pendingGlue = new PendingGlue(selected, localHit, hand);
			componentSelection = findConnectedComponentSelection(hit);
			player.displayClientMessage(Component.translatable(
				"message.create_biotech.surgical_table.glue_first"), true);
			AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(hit.location), 0.5f, 0.85f, false);
			return true;
		}

		PendingGlue first = pendingGlue;
		if (first.hand != hand) {
			pendingGlue = null;
			return true;
		}
		if (!first.selection.tablePos.equals(selected.tablePos)) {
			pendingGlue = null;
			showNoSpace(player);
			return true;
		}
		SurgicalTableLayout.Proposal firstLayout = currentGlueLayout(level, first.selection);
		SurgicalTableLayout.Proposal secondLayout = currentGlueLayout(level, selected);
		if (firstLayout == null || secondLayout == null) {
			pendingGlue = null;
			showNoSpace(player);
			return true;
		}
		CBPackets.sendToServer(new SurgicalTableGluePacket(selected.tablePos, hand,
			glueEndpoint(first.selection, first.hit, firstLayout), glueEndpoint(selected, localHit, secondLayout)));
		AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(hit.location), 0.5f, 0.95f, false);
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.glue_success"), true);
		pendingGlue = null;
		return true;
	}

	private static SurgicalTableGluePacket.Endpoint glueEndpoint(Selection selection, Vec3 hit,
		SurgicalTableLayout.Proposal layout) {
		return new SurgicalTableGluePacket.Endpoint(selection.subjectId, selection.targetId,
			selection.observedCubeCount, selection.seams, hit, layout);
	}

	@Nullable
	private static SurgicalTableLayout.Proposal currentGlueLayout(ClientLevel level, Selection selection) {
		TableGeometry geometry = TABLES.get(new SubjectKey(selection.tablePos, selection.subjectId));
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, selection.tablePos);
		if (geometry == null || !plane.valid() || !selection.tablePos.equals(plane.source()))
			return null;
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			selection.tablePos, selection.subjectId);
		if (occupied == null)
			return null;
		SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.currentLayout(
			geometry.observedCubeCount, geometry.presentCubes, geometry.seams, geometry.cutSeams,
			geometry.baseCubes, geometry.serverOffsets, plane.workArea(), occupied);
		return planned == null ? null : planned.proposal();
	}

	/** Prevents Create's block-area glue selector from consuming clicks aimed at surgical parts. */
	public static boolean shouldOverrideCreateGlue(ItemStack stack) {
		if (!isStandardGlue(stack))
			return false;
		if (pendingGlue != null)
			return true;
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null && minecraft.level != null
			&& findNearestCubeHit(minecraft.player, minecraft.level, playerRay(minecraft.player)) != null;
	}

	private static void consumeInteraction(InputEvent.InteractionKeyMappingTriggered event, InteractionHand hand) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null)
			minecraft.player.swing(hand);
		event.setSwingHand(false);
		event.setCanceled(true);
	}

	private static void sendInteraction(Selection selected, InteractionHand hand,
		SurgicalTableInteractionPacket.Action action, SurgicalTableLayout.Proposal proposal) {
		CBPackets.sendToServer(new SurgicalTableInteractionPacket(selected.tablePos, hand, action,
			selected.subjectId, selected.targetId, selected.observedCubeCount, selected.seams,
			0.0d, 0.0d, proposal));
	}

	private static boolean tryPlaceSubject(LocalPlayer player, ClientLevel level, InteractionHand hand,
		ItemStack held) {
		if (!(held.getItem() instanceof CapturedEntityBoxItem)
			|| !(Minecraft.getInstance().hitResult instanceof BlockHitResult hit)
			|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof SurgicalTableBlock))
			return false;
		updatePlacementPreview();
		PlacementPreview placement = placementPreview;
		if (placement == null || placement.hand != hand
			|| !ItemStack.isSameItemSameComponents(placement.source.box, held)) {
			showNoSpace(player);
			return true;
		}

		CBPackets.sendToServer(new SurgicalTablePlacementPacket(placement.ownerPos, hand,
			placement.plan.originOffsetX(), placement.plan.originOffsetZ(), placement.plan.proposal(),
			placement.sourceLayouts));
		return true;
	}

	private static void beginSingleCut(ClientLevel level, Selection selected, InteractionHand hand) {
		TableGeometry geometry = TABLES.get(new SubjectKey(selected.tablePos, selected.subjectId));
		if (geometry == null || selected.targetId < 0 || selected.targetId >= geometry.seams.size())
			return;
		SurgicalAssembly.Seam seam = geometry.seams.get(selected.targetId);
		BitSet proposedCuts = (BitSet) geometry.cutSeams.clone();
		proposedCuts.set(selected.targetId);
		BitSet first = SurgicalAssembly.componentContaining(geometry.observedCubeCount, geometry.presentCubes,
			geometry.seams, proposedCuts, seam.first());
		BitSet second = SurgicalAssembly.componentContaining(geometry.observedCubeCount, geometry.presentCubes,
			geometry.seams, proposedCuts, seam.second());
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, selected.tablePos);
		if (!plane.valid() || !selected.tablePos.equals(plane.source()))
			return;
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			selected.tablePos, selected.subjectId);
		if (occupied == null) {
			showNoSpace(Minecraft.getInstance().player);
			return;
		}

		if (first.equals(second)) {
			SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.currentLayout(
				geometry.observedCubeCount, geometry.presentCubes, geometry.seams, proposedCuts,
				geometry.baseCubes, geometry.serverOffsets, plane.workArea(), occupied);
			if (planned == null)
				showNoSpace(Minecraft.getInstance().player);
			else
				sendInteraction(selected, hand, SurgicalTableInteractionPacket.Action.CUT, planned.proposal());
			return;
		}

		BitSet moving = first.cardinality() < second.cardinality() ? first : second;
		pendingCut = new PendingCut(selected.tablePos, selected.subjectId, hand, selected.targetId,
			selected.observedCubeCount, selected.seams, proposedCuts, (BitSet) moving.clone(),
			geometry.renderRevision, null);
		updatePendingCut(Minecraft.getInstance().player, level);
	}

	@Nullable
	private static SurgicalClientTopology.PlannedLayout planBatchCut(ClientLevel level, Selection selected) {
		TableGeometry geometry = TABLES.get(new SubjectKey(selected.tablePos, selected.subjectId));
		if (geometry == null || selected.targetId < 0 || selected.targetId >= geometry.observedCubeCount)
			return null;
		BitSet proposedCuts = (BitSet) geometry.cutSeams.clone();
		for (int seamId = 0; seamId < geometry.seams.size(); seamId++) {
			SurgicalAssembly.Seam seam = geometry.seams.get(seamId);
			if (!proposedCuts.get(seamId) && (seam.first() == selected.targetId
				|| seam.second() == selected.targetId))
				proposedCuts.set(seamId);
		}
		BitSet affected = SurgicalAssembly.componentContaining(geometry.observedCubeCount, geometry.presentCubes,
			geometry.seams, geometry.cutSeams, selected.targetId);
		List<BitSet> split = SurgicalAssembly.components(geometry.observedCubeCount, affected, geometry.seams,
			proposedCuts);
		List<BitSet> moving = split.size() <= 1 ? List.of() : split.subList(1, split.size());
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, selected.tablePos);
		if (!plane.valid() || !selected.tablePos.equals(plane.source()))
			return null;
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			selected.tablePos, selected.subjectId);
		if (occupied == null)
			return null;
		return moving.isEmpty()
			? SurgicalClientTopology.currentLayout(geometry.observedCubeCount, geometry.presentCubes,
				geometry.seams, proposedCuts, geometry.baseCubes, geometry.serverOffsets, plane.workArea(), occupied)
			: SurgicalClientTopology.autoSnapComponents(geometry.observedCubeCount, geometry.presentCubes,
				geometry.seams, proposedCuts, geometry.baseCubes, geometry.serverOffsets, plane.workArea(), moving,
				occupied);
	}

	private static void updatePendingCut(@Nullable LocalPlayer player, ClientLevel level) {
		PendingCut pending = pendingCut;
		if (pending == null || player == null)
			return;
		TableGeometry geometry = TABLES.get(new SubjectKey(pending.tablePos, pending.subjectId));
		if (!(level.getBlockEntity(pending.tablePos) instanceof SurgicalTableBlockEntity table)
			|| !table.hasSubject(pending.subjectId)
			|| geometry == null || geometry.renderRevision != pending.renderRevision
			|| !player.getItemInHand(pending.hand).is(Items.SHEARS)
			|| pending.targetId >= geometry.seams.size() || geometry.cutSeams.get(pending.targetId)) {
			abortPendingCut();
			return;
		}
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, pending.tablePos);
		if (!plane.valid() || !pending.tablePos.equals(plane.source())) {
			abortPendingCut();
			return;
		}
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			pending.tablePos, pending.subjectId);
		if (occupied == null) {
			showNoSpace(player);
			abortPendingCut();
			return;
		}
		Vec3 target = tableSurfaceTarget(playerRay(player), plane.workArea().y() + 1.01d);
		SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.snapComponent(
			geometry.observedCubeCount, geometry.presentCubes, geometry.seams, pending.proposedCuts,
			geometry.baseCubes, geometry.serverOffsets, plane.workArea(), pending.movingComponent,
			target.x, target.z, occupied);
		if (planned == null) {
			showNoSpace(player);
			abortPendingCut();
			return;
		}
		pending.planned = planned;
		geometry.applyPreview(table, planned.offsets(), pending.proposedCuts);
		seamSelection = null;
		cubeSelection = null;
		componentSelection = new Selection(pending.tablePos, pending.subjectId, pending.targetId,
			pending.observedCubeCount, pending.seams, List.of(), geometry.componentCubeEdges(pending.movingComponent));
		highlightSelection(componentSelection);
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.place_cut"), true);
	}

	private static void confirmPendingCut(LocalPlayer player) {
		PendingCut pending = pendingCut;
		if (pending == null)
			return;
		if (!player.getItemInHand(pending.hand).is(Items.SHEARS) || pending.planned == null) {
			showNoSpace(player);
			abortPendingCut();
			return;
		}
		Selection selected = new Selection(pending.tablePos, pending.subjectId, pending.targetId,
			pending.observedCubeCount, pending.seams, List.of(), List.of());
		sendInteraction(selected, pending.hand, SurgicalTableInteractionPacket.Action.CUT,
			pending.planned.proposal());
		abortPendingCut();
	}

	private static void abortPendingCut() {
		PendingCut pending = pendingCut;
		pendingCut = null;
		if (pending == null)
			return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.level.getBlockEntity(pending.tablePos) instanceof SurgicalTableBlockEntity table) {
			TableGeometry geometry = TABLES.get(new SubjectKey(pending.tablePos, pending.subjectId));
			if (geometry != null && table.hasSubject(pending.subjectId))
				geometry.clearPreview(table);
		}
		componentSelection = null;
	}

	private static Vec3 tableSurfaceTarget(Ray ray, double surfaceY) {
		double deltaY = ray.end.y - ray.start.y;
		if (Math.abs(deltaY) <= 1.0e-9d)
			return ray.end;
		double progress = (surfaceY - ray.start.y) / deltaY;
		if (!Double.isFinite(progress) || progress < 0.0d)
			return ray.end;
		return ray.start.add(ray.end.subtract(ray.start).scale(progress));
	}

	@Nullable
	private static List<SurgicalTableLayout.Footprint> occupiedOutsideEditingGroup(ClientLevel level,
		SurgicalTablePlane.Plane plane, BlockPos tablePos, int subjectId) {
		if (!(level.getBlockEntity(tablePos) instanceof SurgicalTableBlockEntity table))
			return null;
		return SurgicalTablePlane.occupiedFootprints(level, plane, table.glueConnectedSubjectIds(subjectId));
	}

	private static SurgicalModelRenderContext.CubeGeometry boundsGeometry(EntityGeometry.Bounds bounds,
		Vec3 worldOffset) {
		List<Vec3> corners = new ArrayList<>(8);
		for (int z = 0; z < 2; z++)
			for (int y = 0; y < 2; y++)
				for (int x = 0; x < 2; x++)
					corners.add(new Vec3(x == 0 ? bounds.minX() : bounds.maxX(),
						y == 0 ? bounds.minY() : bounds.maxY(), z == 0 ? bounds.minZ() : bounds.maxZ())
						.add(worldOffset));
		return new SurgicalModelRenderContext.CubeGeometry(0, corners);
	}

	private static List<SurgicalModelRenderContext.CubeGeometry> translateCubes(
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Vec3 offset) {
		if (offset.lengthSqr() <= 1.0e-24d)
			return cubes;
		List<SurgicalModelRenderContext.CubeGeometry> translated = new ArrayList<>(cubes.size());
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			translated.add(new SurgicalModelRenderContext.CubeGeometry(cube.cubeId(),
				cube.corners().stream().map(corner -> corner.add(offset)).toList()));
		return List.copyOf(translated);
	}

	private static void showNoSpace(@Nullable LocalPlayer player) {
		if (player != null)
			player.displayClientMessage(Component.translatable(
				"message.create_biotech.surgical_table.no_space"), true);
	}

	@SubscribeEvent
	public static void hideVanillaTableOutline(RenderHighlightEvent.Block event) {
		BlockPos target = event.getTarget().getBlockPos();
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && (belongsToSelectionPlane(level, target, seamSelection)
			|| belongsToSelectionPlane(level, target, cubeSelection)
			|| belongsToSelectionPlane(level, target, componentSelection)))
			event.setCanceled(true);
	}

	private static boolean belongsToSelectionPlane(ClientLevel level, BlockPos target,
		@Nullable Selection selection) {
		if (selection == null)
			return false;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, target);
		return plane.tiles().contains(selection.tablePos);
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
			best = new Selection(cubeHit.tablePos, geometry.subjectId, seamId, geometry.observedCubeCount,
				geometry.seams, contact.edges(), geometry.cubeEdges(seam));
		}
		return best;
	}

	@Nullable
	private static Selection findDirectSeamSelection(LocalPlayer player, ClientLevel level, Ray ray) {
		Selection best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<SubjectKey, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos tablePos = entry.getKey().tablePos;
			if (!(level.getBlockEntity(tablePos) instanceof SurgicalTableBlockEntity table)
				|| !table.hasSubject(entry.getKey().subjectId))
				continue;
			TableGeometry geometry = entry.getValue();
			SurgicalSubject subject = table.getSubject(geometry.subjectId);
			if (!geometry.topologyReady()
				|| subject == null || !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
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
				best = new Selection(tablePos, geometry.subjectId, seamId, geometry.observedCubeCount,
					geometry.seams, contact.edges(), geometry.cubeEdges(seam));
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
		return hit == null ? null : new Selection(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of(), List.of());
	}

	@Nullable
	private static Selection findConnectedComponentSelection(@Nullable CubeHit hit) {
		return hit == null ? null : new Selection(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of(),
			hit.geometry.connectedCubeEdges(hit.cubeId));
	}

	@Nullable
	private static Selection findDirectConnectionSelection(@Nullable CubeHit hit) {
		if (hit == null)
			return null;
		List<SurgicalClientTopology.Edge> edges = new ArrayList<>(
			hit.geometry.directConnectionCubeEdges(hit.cubeId));
		SurgicalSubject subject = subjectFor(hit);
		if (subject != null) {
			SurgicalGlueJoint.Endpoint endpoint = new SurgicalGlueJoint.Endpoint(subject.persistentId(), hit.cubeId);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null
				&& minecraft.level.getBlockEntity(hit.tablePos) instanceof SurgicalTableBlockEntity table) {
				for (SurgicalGlueJoint joint : subject.glueJoints()) {
					SurgicalGlueJoint.Endpoint other = joint.other(endpoint);
					if (other == null)
						continue;
					SurgicalSubject connected = table.getSubjectByPersistentId(other.subjectKey());
					if (connected == null)
						continue;
					TableGeometry connectedGeometry = TABLES.get(new SubjectKey(hit.tablePos, connected.id()));
					SurgicalModelRenderContext.CubeGeometry connectedCube = connectedGeometry == null
						? null : connectedGeometry.cubesById.get(other.cubeId());
					if (connectedCube != null)
						edges.addAll(SurgicalClientTopology.cubeEdges(connectedCube));
				}
			}
		}
		return new Selection(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of(), List.copyOf(edges));
	}

	@Nullable
	private static Selection findDirectConnectionCutSelection(@Nullable CubeHit hit) {
		SurgicalSubject subject = subjectFor(hit);
		return hit == null || !hit.geometry.hasUncutConnection(hit.cubeId)
			&& (subject == null || !subject.hasGlueConnection(hit.cubeId))
			? null : findDirectConnectionSelection(hit);
	}

	@Nullable
	private static SurgicalSubject subjectFor(@Nullable CubeHit hit) {
		if (hit == null)
			return null;
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && level.getBlockEntity(hit.tablePos) instanceof SurgicalTableBlockEntity table
			? table.getSubject(hit.geometry.subjectId) : null;
	}

	@Nullable
	private static CubeHit findNearestCubeHit(LocalPlayer player, ClientLevel level, Ray ray) {
		CubeHit best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<SubjectKey, TableGeometry> entry : TABLES.entrySet()) {
			BlockPos pos = entry.getKey().tablePos;
			if (!(level.getBlockEntity(pos) instanceof SurgicalTableBlockEntity table)
				|| !table.hasSubject(entry.getKey().subjectId))
				continue;
			TableGeometry geometry = entry.getValue();
			SurgicalSubject subject = table.getSubject(geometry.subjectId);
			if (!geometry.topologyReady()
				|| subject == null || !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
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
		if (blockHit.getType() != HitResult.Type.BLOCK
			|| start.distanceToSqr(blockHit.getLocation()) + 1.0e-5d >= start.distanceToSqr(hit))
			return false;
		BlockPos obstruction = blockHit.getBlockPos();
		if (level.getBlockState(obstruction).getBlock() instanceof SurgicalTableBlock) {
			SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, obstruction);
			if (plane.tiles().contains(tablePos))
				return false;
		}
		return true;
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

	private static void highlightSelection(Selection selection) {
		highlightedSeamEdgeCount = highlightEdges(SEAM_OUTLINE_SLOTS, selection.edges,
			highlightedSeamEdgeCount, SEAM_HIGHLIGHT_COLOR);
		highlightedCubeEdgeCount = highlightEdges(CUBE_OUTLINE_SLOTS, selection.cubeEdges,
			highlightedCubeEdgeCount, CUBE_HIGHLIGHT_COLOR);
	}

	private static int highlightEdges(List<Object> slots, List<SurgicalClientTopology.Edge> edges,
		int previousCount, int color) {
		int edgeCount = edges.size();
		while (slots.size() < edgeCount)
			slots.add(new Object());
		for (int edge = 0; edge < edgeCount; edge++)
			Outliner.getInstance()
				.showLine(slots.get(edge), edges.get(edge).start(), edges.get(edge).end())
				.lineWidth(HIGHLIGHT_LINE_WIDTH)
				.disableLineNormals()
				.colored(color);
		for (int edge = edgeCount; edge < previousCount; edge++)
			Outliner.getInstance().remove(slots.get(edge));
		return edgeCount;
	}

	private static void clearSeamHighlight() {
		for (int edge = 0; edge < highlightedSeamEdgeCount; edge++)
			Outliner.getInstance().remove(SEAM_OUTLINE_SLOTS.get(edge));
		for (int edge = 0; edge < highlightedCubeEdgeCount; edge++)
			Outliner.getInstance().remove(CUBE_OUTLINE_SLOTS.get(edge));
		highlightedSeamEdgeCount = 0;
		highlightedCubeEdgeCount = 0;
	}

	private static boolean isEmptyBox(ItemStack stack) {
		return CapturedEntityBoxItem.isBox(stack) && !CapturedEntityBoxItem.hasCapturedEntity(stack);
	}

	private static boolean isEmptyLargeBox(ItemStack stack) {
		return stack.getItem() instanceof LargeCardboardBoxItem
			&& !CapturedEntityBoxItem.hasCapturedEntity(stack);
	}

	private static boolean isStandardGlue(ItemStack stack) {
		return stack.getItem() instanceof SuperGlueItem && !(stack.getItem() instanceof SmartSuperGlueItem);
	}

	private static void clearSelections() {
		seamSelection = null;
		cubeSelection = null;
		componentSelection = null;
		clearSeamHighlight();
	}

	private static final class TableGeometry {
		private final int subjectId;
		private final MimicProfile profile;
		private final Direction facing;
		private final double originOffsetX;
		private final double originOffsetZ;
		private final int observedCubeCount;
		private final List<SurgicalModelRenderContext.CubeGeometry> baseCubes;
		private List<SurgicalModelRenderContext.CubeGeometry> cubes;
		private Map<Integer, SurgicalModelRenderContext.CubeGeometry> cubesById;
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
		private Map<Integer, Vec3> serverOffsets = Map.of();
		private Map<Integer, Vec3> offsets = Map.of();
		private final Map<Integer, List<SurgicalClientTopology.Edge>> connectedCubeEdgeCache = new HashMap<>();
		private final Map<Integer, List<SurgicalClientTopology.Edge>> directCubeEdgeCache = new HashMap<>();
		private int renderRevision = Integer.MIN_VALUE;
		private long lastSeenTick;

		private TableGeometry(int subjectId, MimicProfile profile, Direction facing, double originOffsetX,
			double originOffsetZ, int observedCubeCount,
			List<SurgicalModelRenderContext.CubeGeometry> cubes, List<SurgicalAssembly.Seam> seams,
			List<SurgicalClientTopology.Contact> contacts,
			@Nullable CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology,
			long lastSeenTick) {
			this.subjectId = subjectId;
			this.profile = profile;
			this.facing = facing;
			this.originOffsetX = originOffsetX;
			this.originOffsetZ = originOffsetZ;
			this.observedCubeCount = observedCubeCount;
			this.baseCubes = List.copyOf(cubes);
			this.cubes = this.baseCubes;
			this.cubesById = indexCubes(this.cubes);
			this.seams = List.copyOf(seams);
			this.seamIds = seamIds(this.seams);
			this.baseContacts = List.copyOf(contacts);
			this.contacts = this.baseContacts;
			this.contactsByCube = contactsByCube(observedCubeCount, this.contacts);
			this.pendingTopology = pendingTopology;
			this.topologyAvailable = pendingTopology == null;
			this.lastSeenTick = lastSeenTick;
		}

		private boolean matchesModel(SurgicalTableBlockEntity table, SurgicalSubject subject) {
			return table.hasSubject(subjectId) && subject.id() == subjectId && profile.equals(subject.profile())
				&& subject.placementFacing() == facing
				&& Double.doubleToLongBits(subject.originOffsetX()) == Double.doubleToLongBits(originOffsetX)
				&& Double.doubleToLongBits(subject.originOffsetZ()) == Double.doubleToLongBits(originOffsetZ)
				&& (subject.cubeCount() == 0 || subject.cubeCount() == observedCubeCount);
		}

		private void refresh(SurgicalTableBlockEntity table, SurgicalSubject subject) {
			markSeen(table);
			boolean topologyChanged = resolvePendingTopology();
			int revision = subject.clientRenderRevision();
			if (revision == renderRevision && !topologyChanged)
				return;

			if (subject.cubeCount() == observedCubeCount && !seams.equals(subject.seams())) {
				seams = List.copyOf(subject.seams());
				seamIds = seamIds(seams);
				baseContacts = SurgicalClientTopology.contactsFor(seams, baseCubes);
			}
			presentCubes = subject.presentCubesForRender(observedCubeCount);
			cutSeams = subject.cutSeamsForRender();
			serverOffsets = Map.copyOf(subject.componentOffsetsForRender());
			applyOffsets(table, serverOffsets);
			renderRevision = revision;
		}

		private void applyPreview(SurgicalTableBlockEntity table, Map<Integer, Vec3> previewOffsets,
			BitSet previewCutSeams) {
			applyOffsets(table, previewOffsets, previewCutSeams);
		}

		private void clearPreview(SurgicalTableBlockEntity table) {
			applyOffsets(table, serverOffsets);
		}

		private void applyOffsets(SurgicalTableBlockEntity table, Map<Integer, Vec3> appliedOffsets) {
			applyOffsets(table, appliedOffsets, cutSeams);
		}

		private void applyOffsets(SurgicalTableBlockEntity table, Map<Integer, Vec3> appliedOffsets,
			BitSet appliedCutSeams) {
			double surfaceY = table.getBlockPos().getY() + 1.0d + SurgicalTablePoseResolver.TABLE_CLEARANCE;
			offsets = SurgicalClientTopology.groundComponents(observedCubeCount, presentCubes,
				seams, appliedCutSeams, baseCubes, appliedOffsets, surfaceY);
			cubes = translateCubes(baseCubes, offsets);
			cubesById = indexCubes(cubes);
			connectedCubeEdgeCache.clear();
			directCubeEdgeCache.clear();
			contacts = translateContacts(baseContacts, offsets);
			contactsByCube = contactsByCube(observedCubeCount, contacts);
			updateRenderBounds(table, cubes);
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
				table.includeClientRenderBounds(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
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

		private boolean hasUncutConnection(int cubeId) {
			for (SurgicalClientTopology.Contact contact : contactsFor(cubeId)) {
				SurgicalAssembly.Seam seam = contact.seam();
				Integer seamId = seamIds.get(seam);
				if (seamId != null && !cutSeams.get(seamId)
					&& presentCubes.get(seam.first()) && presentCubes.get(seam.second()))
					return true;
			}
			return false;
		}

		private List<SurgicalClientTopology.Edge> cubeEdges(SurgicalAssembly.Seam seam) {
			List<SurgicalClientTopology.Edge> edges = new ArrayList<>(24);
			SurgicalModelRenderContext.CubeGeometry first = cubesById.get(seam.first());
			SurgicalModelRenderContext.CubeGeometry second = cubesById.get(seam.second());
			if (first != null)
				edges.addAll(SurgicalClientTopology.cubeEdges(first));
			if (second != null)
				edges.addAll(SurgicalClientTopology.cubeEdges(second));
			return List.copyOf(edges);
		}

		private List<SurgicalClientTopology.Edge> componentCubeEdges(BitSet component) {
			List<SurgicalClientTopology.Edge> edges = new ArrayList<>(component.cardinality() * 12);
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry geometry = cubesById.get(cube);
				if (geometry != null)
					edges.addAll(SurgicalClientTopology.cubeEdges(geometry));
			}
			return List.copyOf(edges);
		}

		private List<SurgicalClientTopology.Edge> connectedCubeEdges(int cubeId) {
			List<SurgicalClientTopology.Edge> cached = connectedCubeEdgeCache.get(cubeId);
			if (cached != null)
				return cached;

			BitSet component = SurgicalAssembly.componentContaining(observedCubeCount, presentCubes,
				seams, cutSeams, cubeId);
			List<SurgicalClientTopology.Edge> edges = new ArrayList<>(component.cardinality() * 12);
			for (int connected = component.nextSetBit(0); connected >= 0;
				connected = component.nextSetBit(connected + 1)) {
				SurgicalModelRenderContext.CubeGeometry cube = cubesById.get(connected);
				if (cube != null)
					edges.addAll(SurgicalClientTopology.cubeEdges(cube));
			}
			List<SurgicalClientTopology.Edge> result = List.copyOf(edges);
			for (int connected = component.nextSetBit(0); connected >= 0;
				connected = component.nextSetBit(connected + 1))
				connectedCubeEdgeCache.put(connected, result);
			return result;
		}

		private List<SurgicalClientTopology.Edge> directConnectionCubeEdges(int cubeId) {
			List<SurgicalClientTopology.Edge> cached = directCubeEdgeCache.get(cubeId);
			if (cached != null)
				return cached;

			BitSet directCubes = new BitSet(observedCubeCount);
			if (presentCubes.get(cubeId))
				directCubes.set(cubeId);
			for (SurgicalClientTopology.Contact contact : contactsFor(cubeId)) {
				SurgicalAssembly.Seam seam = contact.seam();
				Integer seamId = seamIds.get(seam);
				if (seamId == null || cutSeams.get(seamId)
					|| !presentCubes.get(seam.first()) || !presentCubes.get(seam.second()))
					continue;
				directCubes.set(seam.first());
				directCubes.set(seam.second());
			}

			List<SurgicalClientTopology.Edge> edges = new ArrayList<>(directCubes.cardinality() * 12);
			for (int direct = directCubes.nextSetBit(0); direct >= 0;
				direct = directCubes.nextSetBit(direct + 1)) {
				SurgicalModelRenderContext.CubeGeometry cube = cubesById.get(direct);
				if (cube != null)
					edges.addAll(SurgicalClientTopology.cubeEdges(cube));
			}
			List<SurgicalClientTopology.Edge> result = List.copyOf(edges);
			directCubeEdgeCache.put(cubeId, result);
			return result;
		}

		private static Map<Integer, SurgicalModelRenderContext.CubeGeometry> indexCubes(
			List<SurgicalModelRenderContext.CubeGeometry> cubes) {
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = new HashMap<>();
			for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
				byId.putIfAbsent(cube.cubeId(), cube);
			return Map.copyOf(byId);
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

	private record SubjectKey(BlockPos tablePos, int subjectId) {}

	private record Selection(BlockPos tablePos, int subjectId, int targetId, int observedCubeCount,
		List<SurgicalAssembly.Seam> seams, List<SurgicalClientTopology.Edge> edges,
		List<SurgicalClientTopology.Edge> cubeEdges) {}

	private record Ray(Vec3 start, Vec3 end) {}

	private record CubeHit(BlockPos tablePos, TableGeometry geometry, int cubeId,
		Vec3 location, double distanceSqr) {}


	private record PlacementPreview(BlockPos ownerPos, InteractionHand hand, PlacementSource source,
		Direction facing, SurgicalClientTopology.PlacementPlan plan, Map<Integer, Vec3> cubeOffsets,
		List<SourcePlacementGeometry> sourceGeometries,
		List<SurgicalTableLayout.Proposal> sourceLayouts, boolean projectSourceGeometry) {
		private PlacementPreview {
			cubeOffsets = Map.copyOf(cubeOffsets);
			sourceGeometries = List.copyOf(sourceGeometries);
			sourceLayouts = List.copyOf(sourceLayouts);
		}
	}

	private record PlacementGeometry(EntityGeometry.Bounds bounds, Map<Integer, Vec3> cubeOffsets,
		List<SourcePlacementGeometry> sources) {
		private PlacementGeometry {
			cubeOffsets = Map.copyOf(cubeOffsets);
			sources = List.copyOf(sources);
		}
	}

	private record SourcePlacementGeometry(SurgicalAssembly.PlacedSource placedSource,
		List<SurgicalModelRenderContext.CubeGeometry> baseCubes, Map<Integer, Vec3> renderOffsets) {
		private SourcePlacementGeometry {
			baseCubes = List.copyOf(baseCubes);
			renderOffsets = Map.copyOf(renderOffsets);
		}
	}

	private static final class PlacementSource {
		private final ItemStack box;
		private final MimicProfile profile;
		@Nullable
		private final SurgicalAssembly assembly;
		@Nullable
		private LivingEntity preview;
		@Nullable
		private Direction measuredFacing;
		private boolean measuredSourceGeometry;
		@Nullable
		private PlacementGeometry measuredGeometry;

		private PlacementSource(ItemStack box, MimicProfile profile, @Nullable SurgicalAssembly assembly) {
			this.box = box;
			this.profile = profile;
			this.assembly = assembly;
		}

		@Nullable
		private LivingEntity preview() {
			if (preview == null)
				preview = preview(profile);
			return preview;
		}

		@Nullable
		private LivingEntity preview(MimicProfile sourceProfile) {
			return SurgicalSourceModelRenderer.preview(this, sourceProfile);
		}

		private boolean isComposite() {
			return assembly != null && (assembly.preservesLayout() || assembly.sources().size() != 1);
		}

		@Nullable
		private PlacementGeometry measure(Direction facing, boolean projectSourceGeometry) {
			if (measuredGeometry != null && measuredFacing == facing
				&& measuredSourceGeometry == projectSourceGeometry)
				return measuredGeometry;
			if (isComposite()) {
				measuredGeometry = measureComposite(facing, projectSourceGeometry);
				measuredFacing = facing;
				measuredSourceGeometry = projectSourceGeometry;
				return measuredGeometry;
			}
			LivingEntity entity = preview();
			if (entity == null)
				return null;

			PoseStack poseStack = new PoseStack();
			SurgicalTablePoseResolver.resolve(this, profile, entity, facing).apply(poseStack);
			EntityGeometry.Collector sink = EntityGeometry.Collector.boundsOnly();
			MultiBufferSource measuringBuffer = renderType -> sink;
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(entity,
				cubeCount(), presentCubes(), Map.of(), poseStack, measuringBuffer, LightTexture.FULL_BRIGHT,
				0.0f, 0.0f, true, null, projectSourceGeometry);
			if (!sink.hasVertices())
				return null;

			Map<Integer, Vec3> cubeOffsets = groundedOffsets(snapshot);
			EntityGeometry.Bounds bounds = sink.bounds();
			if (!cubeOffsets.isEmpty()) {
				sink.reset();
				poseStack = new PoseStack();
				SurgicalTablePoseResolver.resolve(this, profile, entity, facing).apply(poseStack);
				SurgicalSourceModelRenderer.render(entity, cubeCount(), presentCubes(), cubeOffsets, poseStack,
					measuringBuffer, LightTexture.FULL_BRIGHT, 0.0f, 0.0f, false, null,
					projectSourceGeometry);
				if (!sink.hasVertices())
					return null;
				bounds = sink.bounds();
			}
			measuredFacing = facing;
			measuredSourceGeometry = projectSourceGeometry;
			measuredGeometry = new PlacementGeometry(bounds, cubeOffsets, List.of());
			return measuredGeometry;
		}

		@Nullable
		private PlacementGeometry measureComposite(Direction facing, boolean projectSourceGeometry) {
			if (assembly == null)
				return null;
			EntityGeometry.Collector combined = EntityGeometry.Collector.boundsOnly();
			MultiBufferSource combinedBuffer = renderType -> combined;
			EntityGeometry.Collector discarded = EntityGeometry.Collector.boundsOnly();
			MultiBufferSource discardedBuffer = renderType -> discarded;
			List<SourcePlacementGeometry> geometries = new ArrayList<>(assembly.sources().size());
			for (SurgicalAssembly.PlacedSource placedSource : assembly.placedSources(facing)) {
				SurgicalAssembly.Source source = placedSource.source();
				LivingEntity entity = preview(source.profile());
				if (entity == null)
					return null;
				PoseStack poseStack = sourcePose(placedSource, entity);
				SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(entity,
					source.cubeCount(), source.presentCubes(), Map.of(), poseStack, discardedBuffer,
					LightTexture.FULL_BRIGHT, 0.0f, 0.0f, true, null, projectSourceGeometry);
				if (snapshot.observedCubeCount() != source.cubeCount()
					|| snapshot.cubes().size() != source.presentCubes().cardinality())
					return null;
				Map<Integer, Vec3> renderOffsets = groundedOffsets(placedSource, snapshot);
				geometries.add(new SourcePlacementGeometry(placedSource, snapshot.cubes(), renderOffsets));
				discarded.reset();

				poseStack = sourcePose(placedSource, entity);
				SurgicalSourceModelRenderer.render(entity, source.cubeCount(), source.presentCubes(),
					renderOffsets, poseStack, combinedBuffer, LightTexture.FULL_BRIGHT,
					0.0f, 0.0f, false, null, projectSourceGeometry);
			}
			return combined.hasVertices()
				? new PlacementGeometry(combined.bounds(), Map.of(), geometries) : null;
		}

		private PoseStack sourcePose(SurgicalAssembly.PlacedSource placedSource, LivingEntity entity) {
			SurgicalAssembly.Source source = placedSource.source();
			PoseStack poseStack = new PoseStack();
			poseStack.translate(placedSource.originOffset().x, placedSource.originOffset().y,
				placedSource.originOffset().z);
			SurgicalTablePoseResolver.resolve(this, source.profile(), entity, placedSource.facing()).apply(poseStack);
			return poseStack;
		}

		private Map<Integer, Vec3> groundedOffsets(SurgicalAssembly.PlacedSource placedSource,
			SurgicalModelRenderContext.Snapshot snapshot) {
			SurgicalAssembly.Source source = placedSource.source();
			return SurgicalClientTopology.groundComponents(source.cubeCount(), source.presentCubes(),
				source.seams(), source.cutSeams(), snapshot.cubes(), placedSource.cubeOffsets(),
				1.0d + SurgicalTablePoseResolver.TABLE_CLEARANCE);
		}

		private Map<Integer, Vec3> groundedOffsets(SurgicalModelRenderContext.Snapshot snapshot) {
			if (assembly == null || snapshot.observedCubeCount() != assembly.cubeCount())
				return Map.of();
			return SurgicalClientTopology.groundComponents(assembly.cubeCount(), assembly.presentCubes(),
				assembly.seams(), assembly.cutSeams(), snapshot.cubes(), Map.of(),
				1.0d + SurgicalTablePoseResolver.TABLE_CLEARANCE);
		}

		private int cubeCount() {
			return assembly == null ? 0 : assembly.cubeCount();
		}

		private BitSet presentCubes() {
			return assembly == null ? new BitSet() : assembly.presentCubes();
		}
	}

	private static final class PendingCut {
		private final BlockPos tablePos;
		private final int subjectId;
		private final InteractionHand hand;
		private final int targetId;
		private final int observedCubeCount;
		private final List<SurgicalAssembly.Seam> seams;
		private final BitSet proposedCuts;
		private final BitSet movingComponent;
		private final int renderRevision;
		@Nullable
		private SurgicalClientTopology.PlannedLayout planned;

		private PendingCut(BlockPos tablePos, int subjectId, InteractionHand hand, int targetId,
			int observedCubeCount, List<SurgicalAssembly.Seam> seams, BitSet proposedCuts, BitSet movingComponent,
			int renderRevision, @Nullable SurgicalClientTopology.PlannedLayout planned) {
			this.tablePos = tablePos;
			this.subjectId = subjectId;
			this.hand = hand;
			this.targetId = targetId;
			this.observedCubeCount = observedCubeCount;
			this.seams = List.copyOf(seams);
			this.proposedCuts = (BitSet) proposedCuts.clone();
			this.movingComponent = (BitSet) movingComponent.clone();
			this.renderRevision = renderRevision;
			this.planned = planned;
		}
	}

	private record PendingGlue(Selection selection, Vec3 hit, InteractionHand hand) {}
}
