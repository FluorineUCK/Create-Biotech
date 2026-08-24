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
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalGlueJoint;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLayPose;
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
import net.neoforged.bus.api.EventPriority;
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
	private static final double MAX_SELECTION_THRESHOLD = 3.0d / 16.0d;
	private static final double GLUE_EDIT_TRANSLATION_STEP = 1.0d / 16.0d;
	private static final double GLUE_EDIT_ROTATION_STEP = 15.0d;
	private static final int GLUE_EDIT_CIRCLE_SEGMENTS = 32;
	private static final double GLUE_EDIT_GUIDE_MARGIN = 1.0d / 16.0d;
	private static final int ASYNC_TOPOLOGY_CUBE_THRESHOLD = 32;
	private static final InteractionHand[] HANDS = { InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND };
	private static final OutlineState SEAM_OUTLINE = new OutlineState();
	private static final OutlineState CUBE_OUTLINE = new OutlineState();
	private static final OutlineState GLUE_EDIT_OUTLINE = new OutlineState();
	private static final Object PLACEMENT_OUTLINE_SLOT = new Object();
	private static final Map<SubjectKey, TableGeometry> TABLES = new HashMap<>();
	private static long lastPlacementOutlineTick = Long.MIN_VALUE;
	private static long lastPlacementPromptTick = Long.MIN_VALUE;
	private static long lastGlueEditPromptTick = Long.MIN_VALUE;
	private static long geometryGeneration;
	private static long lastSelectionTick = Long.MIN_VALUE;
	private static long lastSelectionGeneration = Long.MIN_VALUE;
	private static int lastSelectionMode = Integer.MIN_VALUE;
	@Nullable
	private static Ray lastSelectionRay;
	@Nullable
	private static PendingGlue lastSelectionPendingGlue;
	@Nullable
	private static Selection seamSelection;
	@Nullable
	private static Selection cubeSelection;
	@Nullable
	private static Selection componentSelection;
	@Nullable
	private static PendingCut pendingCut;
	@Nullable
	private static PendingGlueCut pendingGlueCut;
	@Nullable
	private static PendingGlue pendingGlue;
	@Nullable
	private static GluePreview gluePreview;
	@Nullable
	private static GlueEditor glueEditor;
	@Nullable
	private static PlacementSource placementSource;
	@Nullable
	private static PlacementPreview placementPreview;
	@Nullable
	private static CubeSelectionCache connectedSelectionCache;
	@Nullable
	private static CubeSelectionCache directSelectionCache;

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
		if (geometry.refresh(table, subject))
			geometryGeneration++;
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
		TableGeometry geometry = new TableGeometry(subject.id(), profile, subject.layPose(),
			subject.originOffsetX(), subject.originOffsetZ(), cubeCount, snapshot.cubes(), seams, contacts, pendingTopology,
			table.getLevel().getGameTime());
		geometry.refresh(table, subject);
		TABLES.put(new SubjectKey(table.getBlockPos(), subject.id()), geometry);
		geometryGeneration++;
	}

	public static Map<Integer, Vec3> offsetsFor(SurgicalTableBlockEntity table, SurgicalSubject subject) {
		TableGeometry geometry = TABLES.get(new SubjectKey(table.getBlockPos(), subject.id()));
		if (geometry == null || !geometry.matchesModel(table, subject))
			return Map.of();
		geometry.markSeen(table);
		return geometry.offsets;
	}

	public static Map<Integer, SurgicalCubeRotation> rotationsFor(SurgicalTableBlockEntity table,
		SurgicalSubject subject) {
		TableGeometry geometry = TABLES.get(new SubjectKey(table.getBlockPos(), subject.id()));
		if (geometry == null || !geometry.matchesModel(table, subject))
			return Map.of();
		geometry.markSeen(table);
		return geometry.rotations;
	}

	public static BitSet presentCubesFor(SurgicalTableBlockEntity table, SurgicalSubject subject,
		int observedCubeCount) {
		TableGeometry geometry = TABLES.get(new SubjectKey(table.getBlockPos(), subject.id()));
		BitSet present = geometry != null && geometry.observedCubeCount == observedCubeCount
			&& geometry.matchesModel(table, subject)
			? geometry.presentCubes : subject.presentCubesForRender(observedCubeCount);
		if (gluePreview == null || !gluePreview.ownerPos.equals(table.getBlockPos()))
			return present;
		BitSet visible = null;
		for (GlueSubjectPreview moved : gluePreview.subjects) {
			if (moved.subjectId != subject.id())
				continue;
			if (visible == null)
				visible = (BitSet) present.clone();
			visible.andNot(moved.cubes);
		}
		return visible == null ? present : visible;
	}

	public static void clear() {
		pendingCut = null;
		pendingGlueCut = null;
		pendingGlue = null;
		gluePreview = null;
		glueEditor = null;
		lastGlueEditPromptTick = Long.MIN_VALUE;
		placementSource = null;
		clearPlacementPreview();
		TABLES.clear();
		geometryGeneration++;
		lastSelectionRay = null;
		lastSelectionPendingGlue = null;
		SurgicalTablePoseResolver.clear();
		GLUE_EDIT_OUTLINE.clear();
		clearSelections();
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.screen != null) {
			abortPendingCut();
			abortPendingGlueCut();
			pendingGlue = null;
			gluePreview = null;
			glueEditor = null;
			lastGlueEditPromptTick = Long.MIN_VALUE;
			GLUE_EDIT_OUTLINE.clear();
			clearPlacementPreview();
			clearSelections();
			return;
		}

		long now = level.getGameTime();
		int geometryCount = TABLES.size();
		TABLES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenTick > 5
			|| !(level.getBlockEntity(entry.getKey().tablePos) instanceof SurgicalTableBlockEntity table)
			|| !table.hasSubject(entry.getKey().subjectId));
		if (TABLES.size() != geometryCount)
			geometryGeneration++;
		if (pendingCut != null && !TABLES.containsKey(new SubjectKey(pendingCut.tablePos, pendingCut.subjectId)))
			abortPendingCut();
		if (pendingGlueCut != null
			&& !TABLES.containsKey(new SubjectKey(pendingGlueCut.tablePos, pendingGlueCut.subjectId)))
			abortPendingGlueCut();
		if (pendingGlue != null && (!TABLES.containsKey(new SubjectKey(pendingGlue.selection.tablePos,
			pendingGlue.selection.subjectId)) || !isSurgicalGlue(player.getItemInHand(pendingGlue.hand))))
			clearPendingGlue();
		if (glueEditor != null) {
			if (!(level.getBlockEntity(glueEditor.preview.ownerPos) instanceof SurgicalTableBlockEntity table)
				|| table.clientDataRevision() != glueEditor.preview.tableRevision
				|| !isSmartGlue(player.getItemInHand(glueEditor.hand))) {
				clearPendingGlue();
			} else {
				showGlueEditPrompt(player, level);
				refreshGlueEditGuide(player, level, glueEditor);
			}
		}
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
		if (player == null || level == null || minecraft.screen != null
			|| pendingCut != null || pendingGlueCut != null
			|| !(minecraft.hitResult instanceof BlockHitResult hit)
			|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof SurgicalTableBlock)) {
			clearPlacementPreview();
			return;
		}

		InteractionHand hand = null;
		PlacementSource source = null;
		for (InteractionHand candidate : HANDS) {
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

		SurgicalTablePlane.Plane plane = clientPlane(level, hit.getBlockPos());
		if (!plane.valid() || plane.source() == null || plane.workArea().isEmpty()) {
			clearPlacementPreview();
			return;
		}
		Vec3 target = tableSurfaceTarget(playerRay(player), plane.workArea().y() + 1.01d);
		BlockPos ownerPos = plane.source();
		Direction placementFacing = player.getDirection();
		SurgicalTableBlockEntity controller = level.getBlockEntity(ownerPos) instanceof SurgicalTableBlockEntity table
			? table : null;
		boolean projectSourceGeometry = controller != null
			? controller.clientProjectsSourceGeometry()
			: SurgicalTableRenderer.projectsSourceGeometry(level, plane);
		int tableRevision = controller == null ? Integer.MIN_VALUE : controller.clientDataRevision();
		PlacementGeometry placementGeometry = source.measure(placementFacing, projectSourceGeometry);
		if (placementGeometry == null) {
			clearPlacementPreview();
			return;
		}
		PlacementPreview previous = placementPreview;
		if (previous != null && previous.ownerPos.equals(ownerPos) && previous.hand == hand
			&& previous.source == source && previous.facing == placementFacing
			&& previous.projectSourceGeometry == projectSourceGeometry
			&& previous.tableRevision == tableRevision && previous.workArea == plane.workArea()
			&& sameHorizontalTarget(target, previous.targetX, previous.targetZ)) {
			refreshPlacementFeedback(player, level);
			return;
		}
		List<SurgicalTableLayout.Footprint> occupied = SurgicalTablePlane.occupiedFootprints(level, plane, -1);
		if (occupied == null) {
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

		placementPreview = new PlacementPreview(ownerPos, hand, source, placementFacing,
			placementGeometry.layPose(), plan,
			placementGeometry.cubeOffsets(), placementGeometry.sources(), sourceLayouts, projectSourceGeometry,
			plane.workArea(), target.x, target.z, tableRevision);
		SurgicalTableLayout.Footprint footprint = plan.proposal().footprints().getFirst();
		Outliner.getInstance().showAABB(PLACEMENT_OUTLINE_SLOT,
			new AABB(footprint.minX(), plane.workArea().y() + 1.002d, footprint.minZ(),
				footprint.maxX(), plane.workArea().y() + 1.012d, footprint.maxZ()))
			.colored(PonderPalette.GREEN.getColor())
			.disableLineNormals()
			.lineWidth(HIGHLIGHT_LINE_WIDTH);
		lastPlacementOutlineTick = level.getGameTime();
		showPlacementPrompt(player, level);
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
		if (placementPreview == null && lastPlacementOutlineTick == Long.MIN_VALUE)
			return;
		placementPreview = null;
		Outliner.getInstance().remove(PLACEMENT_OUTLINE_SLOT);
		lastPlacementOutlineTick = Long.MIN_VALUE;
		lastPlacementPromptTick = Long.MIN_VALUE;
	}

	private static void refreshPlacementFeedback(LocalPlayer player, ClientLevel level) {
		long tick = level.getGameTime();
		if (tick != lastPlacementOutlineTick) {
			Outliner.getInstance().keep(PLACEMENT_OUTLINE_SLOT);
			lastPlacementOutlineTick = tick;
		}
		showPlacementPrompt(player, level);
	}

	private static void showPlacementPrompt(LocalPlayer player, ClientLevel level) {
		long tick = level.getGameTime();
		if (tick == lastPlacementPromptTick)
			return;
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.place_subject"), true);
		lastPlacementPromptTick = tick;
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
				SurgicalTablePoseResolver.resolve(placedSource.layPose()).apply(poseStack);
				SurgicalSourceModelRenderer.render(preview, assemblySource.cubeCount(),
					assemblySource.presentCubes(), sourceGeometry.renderOffsets(), sourceGeometry.renderRotations(),
					poseStack, buffer, light,
					0.0f, partialTicks, false, camera, placement.projectSourceGeometry);
				poseStack.popPose();
			}
		} else {
			LivingEntity preview = placement.source.preview();
			if (preview != null) {
				SurgicalTablePoseResolver.resolve(placement.layPose).apply(poseStack);
				SurgicalSourceModelRenderer.render(preview, placement.source.cubeCount(),
					placement.source.presentCubes(), placement.cubeOffsets, poseStack, buffer, light,
					0.0f, partialTicks, false, camera, placement.projectSourceGeometry);
			}
		}
		poseStack.popPose();
		buffer.endBatch();
	}

	@SubscribeEvent
	public static void renderGluePreview(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || gluePreview == null)
			return;
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		GluePreview preview = gluePreview;
		if (level == null
			|| !(level.getBlockEntity(preview.ownerPos) instanceof SurgicalTableBlockEntity table))
			return;

		Vec3 camera = event.getCamera().getPosition();
		PoseStack poseStack = event.getPoseStack();
		MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
		int light = LevelRenderer.getLightColor(level, preview.ownerPos.above());
		float partialTicks = AnimationTickHolder.getPartialTicks();
		boolean projectSourceGeometry = SurgicalTableRenderer.projectsSourceGeometry(table);
		for (GlueSubjectPreview moved : preview.subjects) {
			SurgicalSubject subject = table.getSubject(moved.subjectId);
			if (subject == null)
				continue;
			LivingEntity entity = SurgicalSourceModelRenderer.preview(subject, subject.profile());
			if (entity == null)
				continue;
			poseStack.pushPose();
			poseStack.translate(preview.ownerPos.getX() - camera.x + subject.originOffsetX(),
				preview.ownerPos.getY() - camera.y,
				preview.ownerPos.getZ() - camera.z + subject.originOffsetZ());
			SurgicalTablePoseResolver.resolve(moved.pose).apply(poseStack);
			SurgicalSourceModelRenderer.render(entity, subject.cubeCount(), moved.cubes, moved.offsets,
				moved.rotations,
				poseStack, buffer, light, 0.0f, partialTicks, false, camera, projectSourceGeometry);
			poseStack.popPose();
		}
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
		if (pendingGlueCut != null) {
			updatePendingGlueCut(player, level);
			return;
		}
		if (glueEditor != null) {
			seamSelection = null;
			cubeSelection = null;
			componentSelection = null;
			if (!highlightGluePreview())
				clearSeamHighlight();
			showGlueEditPrompt(player, level);
			refreshGlueEditGuide(player, level, glueEditor);
			return;
		}

		boolean holdingShears = player.getMainHandItem().is(Items.SHEARS)
			|| player.getOffhandItem().is(Items.SHEARS);
		boolean holdingEmptyBox = isEmptyBox(player.getMainHandItem()) || isEmptyBox(player.getOffhandItem());
		boolean holdingEmptyLargeBox = isEmptyLargeBox(player.getMainHandItem())
			|| isEmptyLargeBox(player.getOffhandItem());
		boolean holdingGlue = isSurgicalGlue(player.getMainHandItem()) || isSurgicalGlue(player.getOffhandItem());
		boolean highlightingDirectConnections = holdingShears && player.isShiftKeyDown();
		if (!holdingShears && !holdingEmptyBox && !holdingEmptyLargeBox && !holdingGlue) {
			gluePreview = null;
			seamSelection = null;
			cubeSelection = null;
			componentSelection = null;
			lastSelectionMode = Integer.MIN_VALUE;
			lastSelectionRay = null;
			clearSeamHighlight();
			return;
		}
		Ray ray = playerRay(player);
		int selectionMode = (holdingShears ? 1 : 0) | (holdingEmptyBox ? 2 : 0)
			| (holdingEmptyLargeBox ? 4 : 0) | (holdingGlue ? 8 : 0)
			| (highlightingDirectConnections ? 16 : 0);
		if (lastSelectionTick == level.getGameTime() && lastSelectionGeneration == geometryGeneration
			&& lastSelectionMode == selectionMode && ray.equals(lastSelectionRay)
			&& pendingGlue == lastSelectionPendingGlue) {
			refreshCurrentSelectionHighlight();
			return;
		}
		lastSelectionTick = level.getGameTime();
		lastSelectionGeneration = geometryGeneration;
		lastSelectionMode = selectionMode;
		lastSelectionRay = ray;
		lastSelectionPendingGlue = pendingGlue;
		CubeHit cubeHit = holdingShears || holdingEmptyBox || holdingGlue
			? findNearestCubeHit(player, level, ray) : null;
		if (pendingGlue == null || cubeHit == null) {
			gluePreview = null;
		} else {
			int revision = level.getBlockEntity(cubeHit.tablePos) instanceof SurgicalTableBlockEntity table
				? table.clientDataRevision() : Integer.MIN_VALUE;
			if (gluePreview == null || !gluePreview.matches(pendingGlue, cubeHit, revision))
				gluePreview = planGluePreview(level, pendingGlue, cubeHit);
		}
		seamSelection = holdingShears && !highlightingDirectConnections
			? findSeamSelection(player, level, ray, cubeHit) : null;
		cubeSelection = holdingEmptyBox ? findConnectedComponentSelection(cubeHit) : null;
		componentSelection = holdingGlue
			? findConnectedComponentSelection(cubeHit)
			: highlightingDirectConnections
			? findDirectConnectionSelection(cubeHit)
			: !holdingShears && holdingEmptyLargeBox ? findConnectedComponentSelection(cubeHit) : null;
		refreshCurrentSelectionHighlight();
	}

	private static void refreshCurrentSelectionHighlight() {
		if (highlightGluePreview())
			return;
		if (componentSelection != null)
			highlightSelection(componentSelection);
		else if (seamSelection != null)
			highlightSelection(seamSelection);
		else if (cubeSelection != null)
			highlightSelection(cubeSelection);
		else
			clearSeamHighlight();
	}

	private static boolean highlightGluePreview() {
		if (gluePreview == null)
			return false;
		List<SurgicalClientTopology.Edge> edges = new ArrayList<>();
		for (GlueSubjectPreview subject : gluePreview.subjects) {
			for (int cubeId = subject.cubes.nextSetBit(0); cubeId >= 0;
				cubeId = subject.cubes.nextSetBit(cubeId + 1)) {
				SurgicalModelRenderContext.CubeGeometry cube = previewCube(subject, cubeId);
				if (cube != null)
					edges.addAll(SurgicalClientTopology.cubeEdges(cube));
			}
		}
		if (edges.isEmpty())
			return false;
		SEAM_OUTLINE.clear();
		CUBE_OUTLINE.show(edges, CUBE_HIGHLIGHT_COLOR);
		return true;
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null || minecraft.player == null)
			return;
		KeyMapping key = event.getKeyMapping();
		if ((pendingCut != null || pendingGlueCut != null) && key == minecraft.options.keyAttack
			&& minecraft.player.isShiftKeyDown()) {
			abortPendingCut();
			abortPendingGlueCut();
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
		if (pendingGlueCut != null) {
			confirmPendingGlueCut(minecraft.player);
			consumeInteraction(event, hand);
			return;
		}
		if (pendingGlue != null && glueEditor == null && minecraft.player.isShiftKeyDown()) {
			clearPendingGlue();
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
		if (isSurgicalGlue(held)) {
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
				if (selected.glueJoint)
					beginGlueCut(level, selected, hand);
				else
					beginSingleCut(level, selected, hand);
			}
		} else if (isEmptyBox(held)) {
			selected = findConnectedComponentSelection(cubeHit);
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
		if (glueEditor != null) {
			if (glueEditor.hand != hand || !isSmartGlue(player.getItemInHand(hand))) {
				clearPendingGlue();
				return true;
			}
			confirmGlue(player, level, glueEditor);
			return true;
		}
		if (hit == null)
			return pendingGlue != null;
		Selection selected = findConnectedComponentSelection(hit);
		if (selected == null)
			return false;
		Vec3 localHit = hit.location.subtract(Vec3.atLowerCornerOf(hit.tablePos));
		if (pendingGlue == null) {
			pendingGlue = new PendingGlue(selected, localHit, hand, hit.faceIndex);
			componentSelection = findConnectedComponentSelection(hit);
			player.displayClientMessage(Component.translatable(
				"message.create_biotech.surgical_table.glue_first"), true);
			AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(hit.location), 0.5f, 0.85f, false);
			return true;
		}

		PendingGlue first = pendingGlue;
		if (first.hand != hand) {
			clearPendingGlue();
			return true;
		}
		if (!first.selection.tablePos.equals(selected.tablePos)) {
			clearPendingGlue();
			showNoSpace(player);
			return true;
		}
		GluePreview preview = planGluePreview(level, first, hit);
		SurgicalTableLayout.Proposal firstLayout = currentGlueLayout(level, first.selection);
		SurgicalTableLayout.Proposal secondLayout = currentGlueLayout(level, selected);
		if (preview == null || firstLayout == null || secondLayout == null) {
			clearPendingGlue();
			showNoSpace(player);
			return true;
		}
		SurgicalTableGluePacket.Endpoint firstEndpoint = glueEndpoint(first.selection, first.hit, firstLayout);
		SurgicalTableGluePacket.Endpoint secondEndpoint = glueEndpoint(selected, localHit, secondLayout);
		if (isSmartGlue(player.getItemInHand(hand))) {
			SurgicalModelRenderContext.CubeGeometry editCube = previewCube(preview.subjects,
				firstEndpoint.subjectId(), firstEndpoint.cubeId());
			Vec3 editAxis = cubeFaceAxis(editCube, first.faceIndex);
			Vec3 axisCenter = editCube == null ? null : cubeCenter(editCube);
			Vec3 faceCenter = cubeFaceCenter(editCube, first.faceIndex);
			if (editAxis == null || axisCenter == null || faceCenter == null
				|| !glueEndpointsActuallyIntersect(preview.subjects, firstEndpoint, secondEndpoint)) {
				clearPendingGlue();
				showNoSpace(player);
				return true;
			}
			gluePreview = preview;
			glueEditor = new GlueEditor(hand, firstEndpoint, secondEndpoint, preview,
				axisCenter, faceCenter, editAxis, glueEditGuideRadius(editCube, axisCenter, editAxis));
			showGlueEditPrompt(player, level);
			refreshGlueEditGuide(player, level, glueEditor);
			AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(hit.location), 0.5f, 0.9f, false);
			return true;
		}
		CBPackets.sendToServer(new SurgicalTableGluePacket(selected.tablePos, hand,
			firstEndpoint, secondEndpoint, preview.targetPose, preview.groundLiftY,
			preview.moves, preview.anchorMoves));
		AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(hit.location), 0.5f, 0.95f, false);
		clearPendingGlue();
		return true;
	}

	private static void confirmGlue(LocalPlayer player, ClientLevel level, GlueEditor editor) {
		GluePreview preview = editor.preview;
		if (!glueEndpointsActuallyIntersect(preview.subjects, editor.first, editor.second)) {
			clearPendingGlue();
			showNoSpace(player);
			return;
		}
		CBPackets.sendToServer(new SurgicalTableGluePacket(preview.ownerPos, editor.hand,
			editor.first, editor.second, preview.targetPose, preview.groundLiftY,
			preview.moves, preview.anchorMoves));
		AllSoundEvents.SLIME_ADDED.playAt(level, BlockPos.containing(preview.targetHit), 0.5f, 0.95f, false);
		clearPendingGlue();
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (glueEditor == null || player == null || level == null || minecraft.screen != null)
			return;
		if (!isSmartGlue(player.getItemInHand(glueEditor.hand))) {
			clearPendingGlue();
			return;
		}
		double scroll = event.getScrollDeltaY();
		if (Math.abs(scroll) <= 1.0e-9d)
			return;
		if (!updateGlueEditAxis(player, level, glueEditor)) {
			GLUE_EDIT_OUTLINE.clear();
			showGlueEditPrompt(player, level);
			event.setCanceled(true);
			return;
		}
		Vec3 editDirection = scroll < 0.0d ? glueEditor.axis.scale(-1.0d) : glueEditor.axis;
		Vec3 translation = Vec3.ZERO;
		SurgicalCubeRotation rotation = SurgicalCubeRotation.IDENTITY;
		if (player.isShiftKeyDown())
			rotation = SurgicalCubeRotation.around(editDirection, GLUE_EDIT_ROTATION_STEP);
		else
			translation = editDirection.scale(GLUE_EDIT_TRANSLATION_STEP);
		GluePreview adjusted = adjustGluePreview(level, glueEditor, translation, rotation);
		if (adjusted != null) {
			glueEditor.preview = adjusted;
			gluePreview = adjusted;
			AllSoundEvents.SCROLL_VALUE.playAt(level, BlockPos.containing(glueEditor.axisCenter),
				0.35f, player.isShiftKeyDown() ? 0.85f : 1.0f, false);
		}
		showGlueEditPrompt(player, level);
		refreshGlueEditGuide(player, level, glueEditor);
		event.setCanceled(true);
	}

	private static void clearPendingGlue() {
		pendingGlue = null;
		gluePreview = null;
		glueEditor = null;
		lastGlueEditPromptTick = Long.MIN_VALUE;
		GLUE_EDIT_OUTLINE.clear();
		CUBE_OUTLINE.clear();
	}

	private static void showGlueEditPrompt(LocalPlayer player, ClientLevel level) {
		long tick = level.getGameTime();
		if (tick == lastGlueEditPromptTick)
			return;
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.smart_glue_edit"), true);
		lastGlueEditPromptTick = tick;
	}

	private static void refreshGlueEditGuide(LocalPlayer player, ClientLevel level, GlueEditor editor) {
		if (!updateGlueEditAxis(player, level, editor)) {
			GLUE_EDIT_OUTLINE.clear();
			return;
		}
		GLUE_EDIT_OUTLINE.show(glueEditGuideEdges(editor, player.isShiftKeyDown()), CUBE_HIGHLIGHT_COLOR);
	}

	private static boolean updateGlueEditAxis(LocalPlayer player, ClientLevel level, GlueEditor editor) {
		GluePreviewCubeHit hit = findNearestGluePreviewHit(player, level, editor);
		Vec3 axis = mappedOriginalFaceNormal(hit);
		if (hit == null || axis == null)
			return false;
		editor.axis = axis;
		editor.axisCenter = cubeCenter(hit.geometry);
		Vec3 faceCenter = cubeFaceCenter(hit.geometry, hit.faceIndex);
		if (faceCenter == null)
			return false;
		editor.faceCenter = faceCenter;
		editor.guideRadius = glueEditGuideRadius(hit.geometry, editor.axisCenter, axis);
		return true;
	}

	@Nullable
	private static GluePreviewCubeHit findNearestGluePreviewHit(LocalPlayer player, ClientLevel level,
		GlueEditor editor) {
		GluePreview preview = editor.preview;
		Ray ray = playerRay(player);
		GluePreviewCubeHit best = null;
		double bestDistance = Double.MAX_VALUE;
		GlueSubjectPreview subject = previewSubject(preview.subjects,
			editor.first.subjectId(), editor.first.cubeId());
		SurgicalModelRenderContext.CubeGeometry cube = subject == null ? null
			: previewCube(subject, editor.first.cubeId());
		if (subject == null || cube == null || !rayIntersectsBounds(ray, cubeBounds(cube), 1.0e-6d))
			return null;
		for (int faceIndex = 0; faceIndex < SurgicalClientTopology.CUBE_FACES.length; faceIndex++) {
			Vec3 location = intersectQuad(ray.start, ray.end, cube,
				SurgicalClientTopology.CUBE_FACES[faceIndex]);
			if (location == null)
				continue;
			double distance = ray.start.distanceToSqr(location);
			if (distance >= bestDistance)
				continue;
			bestDistance = distance;
			best = new GluePreviewCubeHit(subject, cube, editor.first.cubeId(), faceIndex, location);
		}
		return best != null && isOccluded(level, player, ray.start, best.location, preview.ownerPos)
			? null : best;
	}

	private static List<SurgicalClientTopology.Edge> glueEditGuideEdges(GlueEditor editor, boolean rotating) {
		List<SurgicalClientTopology.Edge> edges = new ArrayList<>();
		Vec3 axis = editor.axis;
		Vec3 center = editor.axisCenter;
		Vec3 axisU = perpendicularAxis(axis);
		Vec3 axisV = axis.cross(axisU).normalize();
		double radius = editor.guideRadius;
		if (!rotating) {
			double faceDistance = Math.max(0.0d, editor.faceCenter.subtract(center).dot(axis));
			double length = Math.max(faceDistance + 0.25d, radius * 1.25d);
			double headLength = Math.min(0.25d, length * 0.3d);
			double headRadius = headLength * 0.55d;
			Vec3 end = center.add(axis.scale(length));
			Vec3 headBase = end.subtract(axis.scale(headLength));
			edges.add(new SurgicalClientTopology.Edge(center, end));
			edges.add(new SurgicalClientTopology.Edge(end, headBase.add(axisU.scale(headRadius))));
			edges.add(new SurgicalClientTopology.Edge(end, headBase.subtract(axisU.scale(headRadius))));
			edges.add(new SurgicalClientTopology.Edge(end, headBase.add(axisV.scale(headRadius))));
			edges.add(new SurgicalClientTopology.Edge(end, headBase.subtract(axisV.scale(headRadius))));
			return List.copyOf(edges);
		}

		double axisLength = Math.max(0.5d, radius * 1.15d);
		edges.add(new SurgicalClientTopology.Edge(center.subtract(axis.scale(axisLength)),
			center.add(axis.scale(axisLength))));
		Vec3 previous = center.add(axisU.scale(radius));
		for (int segment = 1; segment <= GLUE_EDIT_CIRCLE_SEGMENTS; segment++) {
			double angle = Math.PI * 2.0d * segment / GLUE_EDIT_CIRCLE_SEGMENTS;
			Vec3 current = center.add(axisU.scale(Math.cos(angle) * radius))
				.add(axisV.scale(Math.sin(angle) * radius));
			edges.add(new SurgicalClientTopology.Edge(previous, current));
			previous = current;
		}
		return List.copyOf(edges);
	}

	private static Vec3 perpendicularAxis(Vec3 axis) {
		Vec3 helper = Math.abs(axis.y) < 0.9d ? new Vec3(0.0d, 1.0d, 0.0d)
			: new Vec3(1.0d, 0.0d, 0.0d);
		return axis.cross(helper).normalize();
	}

	@Nullable
	private static Vec3 mappedOriginalFaceNormal(@Nullable GluePreviewCubeHit hit) {
		if (hit == null)
			return null;
		SurgicalModelRenderContext.CubeGeometry base = cubeById(hit.subject.baseCubes, hit.cubeId);
		Vec3 original = cubeFaceAxis(base, hit.faceIndex);
		if (original == null)
			return null;
		SurgicalCubeRotation rotation = hit.subject.rotations
			.getOrDefault(hit.cubeId, SurgicalCubeRotation.IDENTITY);
		Vec3 mapped = rotation.rotate(original).normalize();
		Vec3 visible = cubeFaceAxis(hit.geometry, hit.faceIndex);
		if (visible == null)
			return mapped;
		if (mapped.dot(visible) < 0.0d)
			mapped = mapped.scale(-1.0d);
		return mapped.dot(visible) >= 1.0d - 1.0e-8d ? mapped : visible;
	}

	@Nullable
	private static Vec3 cubeFaceAxis(@Nullable SurgicalModelRenderContext.CubeGeometry cube, int faceIndex) {
		if (cube == null || faceIndex < 0 || faceIndex >= SurgicalClientTopology.CUBE_FACES.length)
			return null;
		Vec3 faceCenter = cubeFaceCenter(cube, faceIndex);
		Vec3 axis = faceCenter == null ? Vec3.ZERO : faceCenter.subtract(cubeCenter(cube));
		if (axis.lengthSqr() <= 1.0e-18d)
			return null;
		return axis.normalize();
	}

	@Nullable
	private static Vec3 cubeFaceCenter(@Nullable SurgicalModelRenderContext.CubeGeometry cube, int faceIndex) {
		if (cube == null || faceIndex < 0 || faceIndex >= SurgicalClientTopology.CUBE_FACES.length)
			return null;
		int[] face = SurgicalClientTopology.CUBE_FACES[faceIndex];
		List<Vec3> corners = cube.corners();
		return corners.get(face[0]).add(corners.get(face[1])).add(corners.get(face[2]))
			.add(corners.get(face[3])).scale(0.25d);
	}

	private static double glueEditGuideRadius(SurgicalModelRenderContext.CubeGeometry cube,
		Vec3 center, Vec3 axis) {
		double radius = 0.0d;
		for (Vec3 corner : cube.corners()) {
			Vec3 relative = corner.subtract(center);
			Vec3 radial = relative.subtract(axis.scale(relative.dot(axis)));
			radius = Math.max(radius, radial.length());
		}
		return Math.max(0.25d, radius + GLUE_EDIT_GUIDE_MARGIN);
	}

	private static boolean glueEndpointsActuallyIntersect(List<GlueSubjectPreview> previews,
		SurgicalTableGluePacket.Endpoint first, SurgicalTableGluePacket.Endpoint second) {
		SurgicalModelRenderContext.CubeGeometry firstCube = previewCube(previews,
			first.subjectId(), first.cubeId());
		SurgicalModelRenderContext.CubeGeometry secondCube = previewCube(previews,
			second.subjectId(), second.cubeId());
		return SurgicalClientTopology.cubesActuallyIntersect(firstCube, secondCube);
	}

	@Nullable
	private static SurgicalModelRenderContext.CubeGeometry previewCube(List<GlueSubjectPreview> previews,
		int subjectId, int cubeId) {
		GlueSubjectPreview preview = previewSubject(previews, subjectId, cubeId);
		return preview == null ? null : previewCube(preview, cubeId);
	}

	@Nullable
	private static GlueSubjectPreview previewSubject(List<GlueSubjectPreview> previews,
		int subjectId, int cubeId) {
		for (GlueSubjectPreview preview : previews)
			if (preview.subjectId == subjectId && preview.cubes.get(cubeId))
				return preview;
		return null;
	}

	@Nullable
	private static SurgicalModelRenderContext.CubeGeometry previewCube(GlueSubjectPreview preview, int cubeId) {
		return preview.transformedCubes.get(cubeId);
	}

	@Nullable
	private static SurgicalModelRenderContext.CubeGeometry transformedPreviewCube(
		List<SurgicalModelRenderContext.CubeGeometry> baseCubes, Map<Integer, Vec3> offsets,
		Map<Integer, SurgicalCubeRotation> rotations, int cubeId) {
		SurgicalModelRenderContext.CubeGeometry base = cubeById(baseCubes, cubeId);
		if (base == null)
			return null;
		SurgicalCubeRotation rotation = rotations
			.getOrDefault(cubeId, SurgicalCubeRotation.IDENTITY);
		Vec3 offset = offsets.getOrDefault(cubeId, Vec3.ZERO);
		Vec3 center = cubeCenter(base);
		return new SurgicalModelRenderContext.CubeGeometry(cubeId, base.corners().stream()
			.map(corner -> center.add(rotation.rotate(corner.subtract(center))).add(offset)).toList());
	}

	private static AABB cubeBounds(SurgicalModelRenderContext.CubeGeometry cube) {
		Vec3 first = cube.corners().getFirst();
		AABB bounds = new AABB(first, first);
		for (int corner = 1; corner < cube.corners().size(); corner++) {
			Vec3 point = cube.corners().get(corner);
			bounds = bounds.minmax(new AABB(point, point));
		}
		return bounds;
	}

	private static SurgicalTableGluePacket.Endpoint glueEndpoint(Selection selection, Vec3 hit,
		SurgicalTableLayout.Proposal layout) {
		return new SurgicalTableGluePacket.Endpoint(selection.subjectId, selection.targetId,
			selection.observedCubeCount, selection.seams, hit, layout);
	}

	@Nullable
	private static SurgicalTableLayout.Proposal currentGlueLayout(ClientLevel level, Selection selection) {
		TableGeometry geometry = TABLES.get(new SubjectKey(selection.tablePos, selection.subjectId));
		SurgicalTablePlane.Plane plane = clientPlane(level, selection.tablePos);
		if (geometry == null || !plane.valid() || !selection.tablePos.equals(plane.source()))
			return null;
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			selection.tablePos, selection.subjectId);
		if (occupied == null)
			return null;
		SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.currentLayout(
			geometry.observedCubeCount, geometry.presentCubes, geometry.seams, geometry.cutSeams,
			geometry.layoutCubes, geometry.serverOffsets, plane.workArea(), occupied);
		return planned == null ? null : planned.proposal();
	}

	@Nullable
	private static GluePreview planGluePreview(ClientLevel level, PendingGlue first, CubeHit targetHit) {
		if (!first.selection.tablePos.equals(targetHit.tablePos)
			|| !(level.getBlockEntity(targetHit.tablePos) instanceof SurgicalTableBlockEntity table))
			return null;
		SurgicalSubject targetSubject = table.getSubject(targetHit.geometry.subjectId);
		if (targetSubject == null)
			return null;
		Map<Integer, BitSet> moving = table.connectedComponents(first.selection.subjectId,
			first.selection.targetId, first.selection.observedCubeCount, first.selection.seams);
		Map<Integer, BitSet> anchored = table.connectedComponents(targetHit.geometry.subjectId,
			targetHit.cubeId, targetHit.geometry.observedCubeCount, targetHit.geometry.seams);
		if (moving.isEmpty() || anchored.isEmpty() || componentMapsIntersect(moving, anchored))
			return null;

		SurgicalTablePlane.Plane plane = clientPlane(level, targetHit.tablePos);
		if (!plane.valid() || !targetHit.tablePos.equals(plane.source()))
			return null;
		List<SurgicalTableLayout.Footprint> obstacles = gluePreviewObstacles(table, moving, anchored);
		Vec3 firstPoint = Vec3.atLowerCornerOf(targetHit.tablePos).add(first.hit);
		Vec3 secondPoint = targetHit.location;
		SurgicalLayPose targetPose = targetSubject.layPose();
		List<GluePlanningSubject> planning = new ArrayList<>();
		double combinedLowestY = Double.POSITIVE_INFINITY;

		for (Map.Entry<Integer, BitSet> entry : moving.entrySet()) {
			SurgicalSubject subject = table.getSubject(entry.getKey());
			TableGeometry geometry = TABLES.get(new SubjectKey(targetHit.tablePos, entry.getKey()));
			if (subject == null || geometry == null || !geometry.topologyReady()
				|| !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				return null;
			BitSet cubes = (BitSet) entry.getValue().clone();
			Vec3 subjectOrigin = Vec3.atLowerCornerOf(targetHit.tablePos)
				.add(subject.originOffsetX(), 0.0d, subject.originOffsetZ());
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> baseTarget = new HashMap<>();
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> desired = new HashMap<>();
			Map<Integer, SurgicalCubeRotation> targetRotations = new HashMap<>();
			for (int cube = cubes.nextSetBit(0); cube >= 0; cube = cubes.nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry base = geometry.baseCubesById.get(cube);
				SurgicalModelRenderContext.CubeGeometry current = geometry.cubesById.get(cube);
				if (base == null || current == null)
					return null;
				SurgicalModelRenderContext.CubeGeometry reframedBase = reframeBaseCube(base, subjectOrigin,
					subject.layPose(), targetPose);
				SurgicalModelRenderContext.CubeGeometry reframedCurrent = reframeCurrentCube(current,
					firstPoint, secondPoint, subject.layPose(), targetPose);
				baseTarget.put(cube, reframedBase);
				desired.put(cube, reframedCurrent);
				targetRotations.put(cube, geometry.serverRotations
					.getOrDefault(cube, SurgicalCubeRotation.IDENTITY)
					.reframe(subject.layPose(), targetPose));
				combinedLowestY = Math.min(combinedLowestY, lowestY(reframedCurrent));
			}
			planning.add(new GluePlanningSubject(subject, cubes, List.copyOf(baseTarget.values()),
				Map.copyOf(desired), Map.copyOf(targetRotations)));
		}
		for (Map.Entry<Integer, BitSet> entry : anchored.entrySet()) {
			SurgicalSubject subject = table.getSubject(entry.getKey());
			TableGeometry geometry = TABLES.get(new SubjectKey(targetHit.tablePos, entry.getKey()));
			if (subject == null || geometry == null || !geometry.topologyReady()
				|| !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				return null;
			for (int cube = entry.getValue().nextSetBit(0); cube >= 0;
				cube = entry.getValue().nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry current = geometry.cubesById.get(cube);
				if (current == null)
					return null;
				combinedLowestY = Math.min(combinedLowestY, lowestY(current));
			}
		}
		double surfaceY = plane.workArea().y() + 1.0d + SurgicalTablePoseResolver.TABLE_CLEARANCE;
		double groundLiftY = Math.max(0.0d, surfaceY - combinedLowestY);
		if (!Double.isFinite(groundLiftY) || groundLiftY > SurgicalTablePlane.MAX_TILES + 2.0d)
			return null;
		List<GlueSubjectPreview> previews = new ArrayList<>(planning.size());
		List<SurgicalTableGluePacket.Move> moves = new ArrayList<>(planning.size());
		List<SurgicalTableGluePacket.AnchorMove> anchorMoves = new ArrayList<>(anchored.size());
		for (Map.Entry<Integer, BitSet> entry : anchored.entrySet()) {
			SurgicalSubject subject = table.getSubject(entry.getKey());
			TableGeometry geometry = TABLES.get(new SubjectKey(targetHit.tablePos, entry.getKey()));
			if (subject == null || geometry == null)
				return null;
			List<SurgicalTableGluePacket.CubeTranslation> translations =
				new ArrayList<>(entry.getValue().cardinality());
			Map<Integer, Vec3> previewOffsets = new HashMap<>();
			Map<Integer, SurgicalCubeRotation> previewRotations = new HashMap<>();
			for (int cube = entry.getValue().nextSetBit(0); cube >= 0;
				cube = entry.getValue().nextSetBit(cube + 1)) {
				Vec3 offset = geometry.offsets.getOrDefault(cube, Vec3.ZERO).add(0.0d, groundLiftY, 0.0d);
				SurgicalCubeRotation rotation = geometry.serverRotations
					.getOrDefault(cube, SurgicalCubeRotation.IDENTITY);
				SurgicalTableGluePacket.CubeTranslation translation = new SurgicalTableGluePacket.CubeTranslation(
					cube, offset, rotation);
				if (!translation.valid())
					return null;
				translations.add(translation);
				previewOffsets.put(cube, offset);
				previewRotations.put(cube, rotation);
			}
			anchorMoves.add(new SurgicalTableGluePacket.AnchorMove(entry.getKey(), translations));
			previews.add(new GlueSubjectPreview(entry.getKey(), entry.getValue(), subject.layPose(),
				geometry.baseCubes, previewOffsets, previewRotations, false));
		}
		for (GluePlanningSubject source : planning) {
			Map<Integer, Vec3> confirmedOffsets = new HashMap<>();
			for (int cube = source.cubes.nextSetBit(0); cube >= 0; cube = source.cubes.nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry base = cubeById(source.baseTarget, cube);
				SurgicalModelRenderContext.CubeGeometry desired = source.desired.get(cube);
				if (base == null || desired == null)
					return null;
				Vec3 previewOffset = cubeCenter(desired).subtract(cubeCenter(base))
					.add(0.0d, groundLiftY, 0.0d);
				confirmedOffsets.put(cube, previewOffset);
			}
			List<SurgicalModelRenderContext.CubeGeometry> rotatedBase = transformCubes(
				source.baseTarget, source.targetRotations, Map.of());
			SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.preserveCompositeLayout(
				source.subject.cubeCount(), source.cubes, source.subject.seams(),
				source.subject.cutSeamsForRender(), rotatedBase, confirmedOffsets,
				plane.workArea(), obstacles);
			if (planned == null)
				return null;
			List<SurgicalTableGluePacket.CubeTranslation> translations = new ArrayList<>(source.cubes.cardinality());
			for (int cube = source.cubes.nextSetBit(0); cube >= 0; cube = source.cubes.nextSetBit(cube + 1))
				translations.add(new SurgicalTableGluePacket.CubeTranslation(cube,
					planned.offsets().getOrDefault(cube, Vec3.ZERO), source.targetRotations.getOrDefault(cube,
						SurgicalCubeRotation.IDENTITY)));
			moves.add(new SurgicalTableGluePacket.Move(source.subject.id(), translations, planned.proposal()));
			previews.add(new GlueSubjectPreview(source.subject.id(), source.cubes, targetPose,
				source.baseTarget, planned.offsets(), source.targetRotations, true));
		}
		return new GluePreview(first, targetHit.tablePos, targetHit.geometry.subjectId, targetHit.cubeId,
			targetHit.location, table.clientDataRevision(), targetPose, groundLiftY, plane.workArea(), obstacles,
			previews, moves, anchorMoves);
	}

	@Nullable
	private static GluePreview adjustGluePreview(ClientLevel level, GlueEditor editor, Vec3 translation,
		SurgicalCubeRotation deltaRotation) {
		GluePreview current = editor.preview;
		if (!(level.getBlockEntity(current.ownerPos) instanceof SurgicalTableBlockEntity table)
			|| table.clientDataRevision() != current.tableRevision
			|| !glueEndpointsActuallyIntersect(current.subjects, editor.first, editor.second))
			return null;
		List<GlueSubjectPreview> previews = new ArrayList<>(current.subjects.size());
		List<SurgicalTableGluePacket.Move> moves = new ArrayList<>(current.moves.size());
		for (GlueSubjectPreview preview : current.subjects) {
			if (!preview.editable) {
				previews.add(preview);
				continue;
			}
			SurgicalSubject subject = table.getSubject(preview.subjectId);
			if (subject == null)
				return null;
			Map<Integer, Vec3> offsets = new HashMap<>();
			Map<Integer, SurgicalCubeRotation> rotations = new HashMap<>();
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> bases = indexCubes(preview.baseCubes);
			for (int cube = preview.cubes.nextSetBit(0); cube >= 0; cube = preview.cubes.nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry base = bases.get(cube);
				if (base == null)
					return null;
				Vec3 oldOffset = preview.offsets.getOrDefault(cube, Vec3.ZERO);
				SurgicalCubeRotation oldRotation = preview.rotations
					.getOrDefault(cube, SurgicalCubeRotation.IDENTITY);
				Vec3 newOffset = oldOffset.add(translation);
				SurgicalCubeRotation newRotation = oldRotation;
				if (!deltaRotation.isIdentity()) {
					Vec3 baseCenter = cubeCenter(base);
					Vec3 currentCenter = baseCenter.add(oldOffset);
					Vec3 rotatedCenter = editor.axisCenter.add(
						deltaRotation.rotate(currentCenter.subtract(editor.axisCenter)));
					newOffset = rotatedCenter.subtract(baseCenter);
					newRotation = oldRotation.then(deltaRotation);
				}
				offsets.put(cube, newOffset);
				rotations.put(cube, newRotation);
			}

			List<SurgicalModelRenderContext.CubeGeometry> rotatedBase = transformCubes(
				preview.baseCubes, rotations, Map.of());
			if (!validEditedVertical(preview.cubes, rotatedBase, offsets, current.workArea.y() + 1.0d
				+ SurgicalTablePoseResolver.TABLE_CLEARANCE))
				return null;
			SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.preserveCompositeLayout(
				subject.cubeCount(), preview.cubes, subject.seams(), subject.cutSeamsForRender(),
				rotatedBase, offsets, current.workArea, current.obstacles);
			if (planned == null)
				return null;
			List<SurgicalTableGluePacket.CubeTranslation> transforms =
				new ArrayList<>(preview.cubes.cardinality());
			for (int cube = preview.cubes.nextSetBit(0); cube >= 0; cube = preview.cubes.nextSetBit(cube + 1)) {
				SurgicalTableGluePacket.CubeTranslation transform = new SurgicalTableGluePacket.CubeTranslation(
					cube, planned.offsets().getOrDefault(cube, Vec3.ZERO), rotations.get(cube));
				if (!transform.valid())
					return null;
				transforms.add(transform);
			}
			moves.add(new SurgicalTableGluePacket.Move(preview.subjectId, transforms, planned.proposal()));
			previews.add(new GlueSubjectPreview(preview.subjectId, preview.cubes, preview.pose,
				preview.baseCubes, planned.offsets(), rotations, true));
		}
		if (moves.size() != current.moves.size())
			return null;
		if (!glueEndpointsActuallyIntersect(previews, editor.first, editor.second))
			return null;
		return new GluePreview(current.request, current.ownerPos, current.targetSubjectId, current.targetCubeId,
			current.targetHit, current.tableRevision, current.targetPose, current.groundLiftY,
			current.workArea, current.obstacles, previews, moves, current.anchorMoves);
	}

	private static boolean validEditedVertical(BitSet cubes,
		List<SurgicalModelRenderContext.CubeGeometry> rotatedBase, Map<Integer, Vec3> offsets, double surfaceY) {
		double range = SurgicalTablePlane.MAX_TILES + 2.0d;
		double minimumY = surfaceY - range;
		double maximumY = surfaceY + range;
		for (SurgicalModelRenderContext.CubeGeometry cube : rotatedBase) {
			if (!cubes.get(cube.cubeId()))
				continue;
			Vec3 offset = offsets.getOrDefault(cube.cubeId(), Vec3.ZERO);
			for (Vec3 corner : cube.corners()) {
				double y = corner.y + offset.y;
				if (!Double.isFinite(y) || y < minimumY || y > maximumY)
					return false;
			}
		}
		return true;
	}

	private static double lowestY(SurgicalModelRenderContext.CubeGeometry geometry) {
		double lowest = Double.POSITIVE_INFINITY;
		for (Vec3 corner : geometry.corners())
			lowest = Math.min(lowest, corner.y);
		return lowest;
	}

	private static boolean componentMapsIntersect(Map<Integer, BitSet> first, Map<Integer, BitSet> second) {
		for (Map.Entry<Integer, BitSet> entry : first.entrySet()) {
			BitSet other = second.get(entry.getKey());
			if (other != null && entry.getValue().intersects(other))
				return true;
		}
		return false;
	}

	private static List<SurgicalTableLayout.Footprint> gluePreviewObstacles(SurgicalTableBlockEntity table,
		Map<Integer, BitSet> moving, Map<Integer, BitSet> anchored) {
		List<SurgicalTableLayout.Footprint> obstacles = new ArrayList<>();
		for (SurgicalSubject subject : table.getSubjects()) {
			BitSet moved = moving.get(subject.id());
			BitSet fixed = anchored.get(subject.id());
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints())
				if (!subject.containsFootprint(moved, footprint)
					&& !subject.containsFootprint(fixed, footprint))
					obstacles.add(footprint);
		}
		return List.copyOf(obstacles);
	}

	private static SurgicalModelRenderContext.CubeGeometry reframeBaseCube(
		SurgicalModelRenderContext.CubeGeometry cube, Vec3 subjectOrigin, SurgicalLayPose source,
		SurgicalLayPose target) {
		Vec3 sourceAnchor = subjectOrigin.add(source.translation());
		Vec3 targetAnchor = subjectOrigin.add(target.translation());
		List<Vec3> corners = cube.corners().stream()
			.map(corner -> targetAnchor.add(source.rotateInto(target, corner.subtract(sourceAnchor))))
			.toList();
		return new SurgicalModelRenderContext.CubeGeometry(cube.cubeId(), corners);
	}

	private static SurgicalModelRenderContext.CubeGeometry reframeCurrentCube(
		SurgicalModelRenderContext.CubeGeometry cube, Vec3 firstPoint, Vec3 secondPoint,
		SurgicalLayPose source, SurgicalLayPose target) {
		List<Vec3> corners = cube.corners().stream()
			.map(corner -> secondPoint.add(source.rotateInto(target, corner.subtract(firstPoint))))
			.toList();
		return new SurgicalModelRenderContext.CubeGeometry(cube.cubeId(), corners);
	}

	@Nullable
	private static SurgicalModelRenderContext.CubeGeometry cubeById(
		List<SurgicalModelRenderContext.CubeGeometry> cubes, int cubeId) {
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			if (cube.cubeId() == cubeId)
				return cube;
		return null;
	}

	private static Vec3 cubeCenter(SurgicalModelRenderContext.CubeGeometry cube) {
		Vec3 sum = Vec3.ZERO;
		for (Vec3 corner : cube.corners())
			sum = sum.add(corner);
		return sum.scale(1.0d / cube.corners().size());
	}

	private static Map<Integer, SurgicalModelRenderContext.CubeGeometry> indexCubes(
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> indexed = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			indexed.put(cube.cubeId(), cube);
		return Map.copyOf(indexed);
	}

	private static List<SurgicalModelRenderContext.CubeGeometry> transformCubes(
		List<SurgicalModelRenderContext.CubeGeometry> cubes,
		Map<Integer, SurgicalCubeRotation> rotations, Map<Integer, Vec3> offsets) {
		if (rotations.isEmpty() && offsets.isEmpty())
			return cubes;
		List<SurgicalModelRenderContext.CubeGeometry> transformed = new ArrayList<>(cubes.size());
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes) {
			SurgicalCubeRotation rotation = rotations.getOrDefault(cube.cubeId(), SurgicalCubeRotation.IDENTITY);
			Vec3 offset = offsets.getOrDefault(cube.cubeId(), Vec3.ZERO);
			Vec3 center = cubeCenter(cube);
			List<Vec3> corners = cube.corners().stream()
				.map(corner -> center.add(rotation.rotate(corner.subtract(center))).add(offset)).toList();
			transformed.add(new SurgicalModelRenderContext.CubeGeometry(cube.cubeId(), corners));
		}
		return List.copyOf(transformed);
	}

	/** Prevents Create's block-area glue selector from consuming clicks aimed at surgical parts. */
	public static boolean shouldOverrideCreateGlue(ItemStack stack) {
		if (!isSurgicalGlue(stack))
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
		sendInteraction(selected, hand, action, proposal, 0.0d, 0.0d);
	}

	private static void sendInteraction(Selection selected, InteractionHand hand,
		SurgicalTableInteractionPacket.Action action, SurgicalTableLayout.Proposal proposal,
		double originOffsetX, double originOffsetZ) {
		CBPackets.sendToServer(new SurgicalTableInteractionPacket(selected.tablePos, hand, action,
			selected.subjectId, selected.targetId, selected.observedCubeCount, selected.seams,
			originOffsetX, originOffsetZ, proposal));
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
			placement.plan.originOffsetX(), placement.plan.originOffsetZ(), placement.layPose,
			placement.plan.proposal(),
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
		SurgicalTablePlane.Plane plane = clientPlane(level, selected.tablePos);
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
				geometry.layoutCubes, geometry.serverOffsets, plane.workArea(), occupied);
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

	private static void beginGlueCut(ClientLevel level, Selection selected, InteractionHand hand) {
		if (!(level.getBlockEntity(selected.tablePos) instanceof SurgicalTableBlockEntity table))
			return;
		SurgicalTableBlockEntity.GlueCutPlan plan = table.glueCutPlan(selected.subjectId, selected.targetId);
		if (plan == null)
			return;
		if (!plan.separates()) {
			sendInteraction(selected, hand, SurgicalTableInteractionPacket.Action.CUT_GLUE,
				SurgicalTableLayout.Proposal.EMPTY);
			return;
		}
		pendingGlueCut = new PendingGlueCut(selected, hand, plan.joint(), plan.movingComponents(), null);
		updatePendingGlueCut(Minecraft.getInstance().player, level);
	}

	private static void updatePendingGlueCut(@Nullable LocalPlayer player, ClientLevel level) {
		PendingGlueCut pending = pendingGlueCut;
		if (pending == null || player == null)
			return;
		if (!(level.getBlockEntity(pending.tablePos) instanceof SurgicalTableBlockEntity table)
			|| !player.getItemInHand(pending.hand).is(Items.SHEARS)) {
			abortPendingGlueCut();
			return;
		}
		SurgicalTableBlockEntity.GlueCutPlan current = table.glueCutPlan(pending.subjectId, pending.targetId);
		if (current == null || !current.separates() || !pending.joint.equals(current.joint())
			|| !pending.movingComponents.equals(current.movingComponents())) {
			abortPendingGlueCut();
			return;
		}
		SurgicalTablePlane.Plane plane = clientPlane(level, pending.tablePos);
		if (!plane.valid() || !pending.tablePos.equals(plane.source())) {
			abortPendingGlueCut();
			return;
		}
		Vec3 target = tableSurfaceTarget(playerRay(player), plane.workArea().y() + 1.01d);
		if (pending.planned != null && componentSelection != null
			&& pending.plannedTableRevision == table.clientDataRevision()
			&& sameHorizontalTarget(target, pending.lastTargetX, pending.lastTargetZ)) {
			highlightSelection(componentSelection);
			return;
		}
		FootprintGroups footprints = glueCutFootprints(table, pending.movingComponents);
		if (footprints == null) {
			abortPendingGlueCut();
			return;
		}
		SurgicalClientTopology.ConnectedPlacement planned = SurgicalClientTopology.snapConnectedGroup(
			footprints.moving, plane.workArea(), target.x, target.z, footprints.occupied);
		if (planned == null) {
			showNoSpace(player);
			abortPendingGlueCut();
			return;
		}

		List<SurgicalClientTopology.Edge> highlighted = new ArrayList<>();
		for (Map.Entry<Integer, BitSet> entry : pending.movingComponents.entrySet()) {
			SurgicalSubject subject = table.getSubject(entry.getKey());
			TableGeometry geometry = TABLES.get(new SubjectKey(pending.tablePos, entry.getKey()));
			if (subject == null || geometry == null || !geometry.topologyReady()
				|| !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams)) {
				abortPendingGlueCut();
				return;
			}
			Map<Integer, Vec3> offsets = new HashMap<>(geometry.serverOffsets);
			for (int cube = entry.getValue().nextSetBit(0); cube >= 0;
				cube = entry.getValue().nextSetBit(cube + 1))
				offsets.put(cube, offsets.getOrDefault(cube, Vec3.ZERO).add(planned.delta()));
			geometry.applyPreview(table, Map.copyOf(offsets), geometry.cutSeams);
			highlighted.addAll(geometry.componentCubeEdges(entry.getValue()));
		}
		pending.planned = planned;
		pending.lastTargetX = target.x;
		pending.lastTargetZ = target.z;
		pending.plannedTableRevision = table.clientDataRevision();
		seamSelection = null;
		cubeSelection = null;
		componentSelection = new Selection(pending.tablePos, pending.subjectId, pending.targetId,
			pending.observedCubeCount, pending.seams, List.of(), List.copyOf(highlighted), true);
		highlightSelection(componentSelection);
		player.displayClientMessage(Component.translatable(
			"message.create_biotech.surgical_table.place_cut"), true);
	}

	private static void confirmPendingGlueCut(LocalPlayer player) {
		PendingGlueCut pending = pendingGlueCut;
		if (pending == null)
			return;
		if (!player.getItemInHand(pending.hand).is(Items.SHEARS) || pending.planned == null) {
			showNoSpace(player);
			abortPendingGlueCut();
			return;
		}
		Selection selected = new Selection(pending.tablePos, pending.subjectId, pending.targetId,
			pending.observedCubeCount, pending.seams, List.of(), List.of(), true);
		Vec3 delta = pending.planned.delta();
		sendInteraction(selected, pending.hand, SurgicalTableInteractionPacket.Action.CUT_GLUE,
			SurgicalTableLayout.Proposal.EMPTY, delta.x, delta.z);
		abortPendingGlueCut();
	}

	private static void abortPendingGlueCut() {
		PendingGlueCut pending = pendingGlueCut;
		pendingGlueCut = null;
		if (pending == null)
			return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null
			&& minecraft.level.getBlockEntity(pending.tablePos) instanceof SurgicalTableBlockEntity table)
			for (int subjectId : pending.movingComponents.keySet()) {
				TableGeometry geometry = TABLES.get(new SubjectKey(pending.tablePos, subjectId));
				if (geometry != null && table.hasSubject(subjectId))
					geometry.clearPreview(table);
			}
		componentSelection = null;
	}

	@Nullable
	private static FootprintGroups glueCutFootprints(SurgicalTableBlockEntity table,
		Map<Integer, BitSet> movingComponents) {
		List<SurgicalTableLayout.Footprint> moving = new ArrayList<>();
		List<SurgicalTableLayout.Footprint> occupied = new ArrayList<>();
		for (SurgicalSubject subject : table.getSubjects()) {
			if (subject.occupiedFootprints().isEmpty())
				return null;
			BitSet component = movingComponents.get(subject.id());
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints()) {
				if (subject.containsFootprint(component, footprint))
					moving.add(footprint);
				else
					occupied.add(footprint);
			}
		}
		return moving.isEmpty() ? null : new FootprintGroups(List.copyOf(moving), List.copyOf(occupied));
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
		SurgicalTablePlane.Plane plane = clientPlane(level, selected.tablePos);
		if (!plane.valid() || !selected.tablePos.equals(plane.source()))
			return null;
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			selected.tablePos, selected.subjectId);
		if (occupied == null)
			return null;
		return moving.isEmpty()
			? SurgicalClientTopology.currentLayout(geometry.observedCubeCount, geometry.presentCubes,
				geometry.seams, proposedCuts, geometry.layoutCubes, geometry.serverOffsets, plane.workArea(), occupied)
			: SurgicalClientTopology.autoSnapComponents(geometry.observedCubeCount, geometry.presentCubes,
				geometry.seams, proposedCuts, geometry.layoutCubes, geometry.serverOffsets, plane.workArea(), moving,
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
		SurgicalTablePlane.Plane plane = clientPlane(level, pending.tablePos);
		if (!plane.valid() || !pending.tablePos.equals(plane.source())) {
			abortPendingCut();
			return;
		}
		Vec3 target = tableSurfaceTarget(playerRay(player), plane.workArea().y() + 1.01d);
		if (pending.planned != null && componentSelection != null
			&& pending.plannedTableRevision == table.clientDataRevision()
			&& sameHorizontalTarget(target, pending.lastTargetX, pending.lastTargetZ)) {
			highlightSelection(componentSelection);
			return;
		}
		List<SurgicalTableLayout.Footprint> occupied = occupiedOutsideEditingGroup(level, plane,
			pending.tablePos, pending.subjectId);
		if (occupied == null) {
			showNoSpace(player);
			abortPendingCut();
			return;
		}
		SurgicalClientTopology.PlannedLayout planned = SurgicalClientTopology.snapComponent(
			geometry.observedCubeCount, geometry.presentCubes, geometry.seams, pending.proposedCuts,
			geometry.layoutCubes, geometry.serverOffsets, plane.workArea(), pending.movingComponent,
			target.x, target.z, occupied);
		if (planned == null) {
			showNoSpace(player);
			abortPendingCut();
			return;
		}
		pending.planned = planned;
		pending.lastTargetX = target.x;
		pending.lastTargetZ = target.z;
		pending.plannedTableRevision = table.clientDataRevision();
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

	private static boolean sameHorizontalTarget(Vec3 target, double previousX, double previousZ) {
		return Math.abs(target.x - previousX) <= 1.0e-7d
			&& Math.abs(target.z - previousZ) <= 1.0e-7d;
	}

	private static SurgicalTablePlane.Plane clientPlane(ClientLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof SurgicalTableBlockEntity table) {
			SurgicalTablePlane.Plane cached = table.getClientPlane();
			if (cached != null)
				return cached;
		}
		return SurgicalTablePlane.scan(level, pos);
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
		SurgicalTablePlane.Plane plane = clientPlane(level, target);
		return plane.workArea().containsTile(selection.tablePos);
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
		double bestDistance = Double.MAX_VALUE;
		for (SurgicalClientTopology.Contact contact : geometry.contactsFor(cubeHit.cubeId)) {
			SurgicalAssembly.Seam seam = contact.seam();
			if (seam.first() != cubeHit.cubeId && seam.second() != cubeHit.cubeId)
				continue;
			Integer seamId = geometry.seamIds.get(seam);
			if (seamId == null || geometry.cutSeams.get(seamId)
				|| !geometry.presentCubes.get(seam.first()) || !geometry.presentCubes.get(seam.second()))
				continue;
			double distance = pointToContactDistance(cubeHit.location, contact);
			if (distance >= bestDistance)
				continue;
			bestDistance = distance;
			best = new Selection(cubeHit.tablePos, geometry.subjectId, seamId, geometry.observedCubeCount,
				geometry.seams, contact.edges(), geometry.cubeEdges(seam));
		}
		return best != null ? best : findGlueJointSelection(cubeHit);
	}

	@Nullable
	private static Selection findDirectSeamSelection(LocalPlayer player, ClientLevel level, Ray ray) {
		Selection best = null;
		Vec3 bestHit = null;
		BlockPos bestTablePos = null;
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
			if (geometry.bounds == null
				|| !rayIntersectsBounds(ray, geometry.bounds, MAX_SELECTION_THRESHOLD))
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
				if (distance >= bestDistance)
					continue;
				bestDistance = distance;
				bestHit = hit;
				bestTablePos = tablePos;
				best = new Selection(tablePos, geometry.subjectId, seamId, geometry.observedCubeCount,
					geometry.seams, contact.edges(), geometry.cubeEdges(seam));
			}
			for (int jointId = 0; jointId < subject.glueJoints().size(); jointId++) {
				GlueJointSelection glue = glueJointSelection(tablePos, table, subject, geometry, jointId);
				if (glue == null || glue.contact == null)
					continue;
				Vec3 hit = intersectContact(ray, glue.contact);
				if (hit == null)
					continue;
				double distance = ray.start.distanceToSqr(hit);
				if (distance >= bestDistance)
					continue;
				bestDistance = distance;
				bestHit = hit;
				bestTablePos = tablePos;
				best = glue.selection;
			}
		}
		return best != null && bestHit != null && bestTablePos != null
			&& isOccluded(level, player, ray.start, bestHit, bestTablePos) ? null : best;
	}

	@Nullable
	private static Selection findGlueJointSelection(CubeHit hit) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !(level.getBlockEntity(hit.tablePos) instanceof SurgicalTableBlockEntity table))
			return null;
		SurgicalSubject subject = table.getSubject(hit.geometry.subjectId);
		if (subject == null)
			return null;
		Selection best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int jointId = 0; jointId < subject.glueJoints().size(); jointId++) {
			SurgicalGlueJoint joint = subject.glueJoints().get(jointId);
			if (!joint.touches(subject.persistentId(), hit.cubeId))
				continue;
			GlueJointSelection candidate = glueJointSelection(hit.tablePos, table, subject,
				hit.geometry, jointId);
			if (candidate == null)
				continue;
			double distance = candidate.contact == null ? 0.0d
				: pointToContactDistance(hit.location, candidate.contact);
			if (distance >= bestDistance)
				continue;
			bestDistance = distance;
			best = candidate.selection;
		}
		return best;
	}

	@Nullable
	private static GlueJointSelection glueJointSelection(BlockPos tablePos, SurgicalTableBlockEntity table,
		SurgicalSubject subject, TableGeometry geometry, int jointId) {
		if (jointId < 0 || jointId >= subject.glueJoints().size())
			return null;
		SurgicalGlueJoint joint = subject.glueJoints().get(jointId);
		if (!joint.touches(subject.persistentId()))
			return null;
		SurgicalSubject firstSubject = table.getSubjectByPersistentId(joint.first().subjectKey());
		SurgicalSubject secondSubject = table.getSubjectByPersistentId(joint.second().subjectKey());
		if (firstSubject == null || secondSubject == null)
			return null;
		TableGeometry firstGeometry = TABLES.get(new SubjectKey(tablePos, firstSubject.id()));
		TableGeometry secondGeometry = TABLES.get(new SubjectKey(tablePos, secondSubject.id()));
		if (firstGeometry == null || secondGeometry == null || !firstGeometry.topologyReady()
			|| !secondGeometry.topologyReady()
			|| !firstSubject.matchesObservedTopology(firstGeometry.observedCubeCount, firstGeometry.seams)
			|| !secondSubject.matchesObservedTopology(secondGeometry.observedCubeCount, secondGeometry.seams)
			|| !firstGeometry.presentCubes.get(joint.first().cubeId())
			|| !secondGeometry.presentCubes.get(joint.second().cubeId()))
			return null;
		SurgicalModelRenderContext.CubeGeometry first = firstGeometry.cubesById.get(joint.first().cubeId());
		SurgicalModelRenderContext.CubeGeometry second = secondGeometry.cubesById.get(joint.second().cubeId());
		if (first == null || second == null)
			return null;

		SurgicalClientTopology.Contact contact = SurgicalClientTopology.contactBetween(
			SurgicalAssembly.Seam.of(0, 1), List.of(
				new SurgicalModelRenderContext.CubeGeometry(0, first.corners()),
				new SurgicalModelRenderContext.CubeGeometry(1, second.corners())));
		List<SurgicalClientTopology.Edge> cubeEdges = new ArrayList<>(24);
		cubeEdges.addAll(SurgicalClientTopology.cubeEdges(first));
		cubeEdges.addAll(SurgicalClientTopology.cubeEdges(second));
		Selection selection = new Selection(tablePos, geometry.subjectId, jointId,
			geometry.observedCubeCount, geometry.seams, contact == null ? List.of() : contact.edges(),
			List.copyOf(cubeEdges), true);
		return new GlueJointSelection(selection, contact);
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
	private static Selection findConnectedComponentSelection(@Nullable CubeHit hit) {
		if (hit == null)
			return null;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !(level.getBlockEntity(hit.tablePos) instanceof SurgicalTableBlockEntity table))
			return null;
		if (connectedSelectionCache != null && connectedSelectionCache.matches(hit, table.clientDataRevision()))
			return connectedSelectionCache.selection;
		Map<Integer, BitSet> components = table.connectedComponents(hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams);
		if (components.isEmpty())
			return null;
		List<SurgicalClientTopology.Edge> edges = new ArrayList<>();
		for (Map.Entry<Integer, BitSet> entry : components.entrySet()) {
			SurgicalSubject subject = table.getSubject(entry.getKey());
			TableGeometry geometry = TABLES.get(new SubjectKey(hit.tablePos, entry.getKey()));
			if (subject == null || geometry == null || !geometry.topologyReady()
				|| !subject.matchesObservedTopology(geometry.observedCubeCount, geometry.seams))
				return null;
			edges.addAll(geometry.componentCubeEdges(entry.getValue()));
		}
		Selection selection = new Selection(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of(), List.copyOf(edges));
		connectedSelectionCache = new CubeSelectionCache(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			table.clientDataRevision(), hit.geometry.renderRevision, selection);
		return selection;
	}

	@Nullable
	private static Selection findDirectConnectionSelection(@Nullable CubeHit hit) {
		if (hit == null)
			return null;
		ClientLevel level = Minecraft.getInstance().level;
		SurgicalTableBlockEntity table = level != null
			&& level.getBlockEntity(hit.tablePos) instanceof SurgicalTableBlockEntity found ? found : null;
		if (table != null && directSelectionCache != null
			&& directSelectionCache.matches(hit, table.clientDataRevision()))
			return directSelectionCache.selection;
		List<SurgicalClientTopology.Edge> edges = new ArrayList<>(
			hit.geometry.directConnectionCubeEdges(hit.cubeId));
		SurgicalSubject subject = subjectFor(hit);
		if (subject != null) {
			SurgicalGlueJoint.Endpoint endpoint = new SurgicalGlueJoint.Endpoint(subject.persistentId(), hit.cubeId);
			if (table != null) {
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
		Selection selection = new Selection(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
			hit.geometry.observedCubeCount, hit.geometry.seams, List.of(), List.copyOf(edges));
		if (table != null)
			directSelectionCache = new CubeSelectionCache(hit.tablePos, hit.geometry.subjectId, hit.cubeId,
				table.clientDataRevision(), hit.geometry.renderRevision, selection);
		return selection;
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
			if (geometry.bounds == null || !rayIntersectsBounds(ray, geometry.bounds, 1.0e-6d))
				continue;
			for (CubeTarget target : geometry.cubeTargets) {
				SurgicalModelRenderContext.CubeGeometry cube = target.geometry;
				if (!geometry.presentCubes.get(cube.cubeId()))
					continue;
				if (!rayIntersectsBounds(ray, target.bounds, 1.0e-6d))
					continue;
				for (int faceIndex = 0; faceIndex < SurgicalClientTopology.CUBE_FACES.length; faceIndex++) {
					int[] faceIndices = SurgicalClientTopology.CUBE_FACES[faceIndex];
					Vec3 hit = intersectQuad(ray.start, ray.end, cube, faceIndices);
					if (hit == null)
						continue;
					double distance = ray.start.distanceToSqr(hit);
					if (distance >= bestDistance)
						continue;
					bestDistance = distance;
					best = new CubeHit(pos, geometry, cube.cubeId(), faceIndex, hit);
				}
			}
		}
		return best != null && isOccluded(level, player, ray.start, best.location, best.tablePos) ? null : best;
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
			SurgicalTablePlane.Plane plane = clientPlane(level, obstruction);
			if (plane.workArea().containsTile(tablePos))
				return false;
		}
		return true;
	}

	/** Allocation-free segment/AABB broad phase used before oriented cube/contact intersection. */
	private static boolean rayIntersectsBounds(Ray ray, AABB bounds, double inflation) {
		double startX = ray.start.x;
		double startY = ray.start.y;
		double startZ = ray.start.z;
		double deltaX = ray.end.x - startX;
		double deltaY = ray.end.y - startY;
		double deltaZ = ray.end.z - startZ;
		double entry = 0.0d;
		double exit = 1.0d;

		double min = bounds.minX - inflation;
		double max = bounds.maxX + inflation;
		if (Math.abs(deltaX) <= 1.0e-12d) {
			if (startX < min || startX > max)
				return false;
		} else {
			double first = (min - startX) / deltaX;
			double second = (max - startX) / deltaX;
			if (first > second) {
				double swap = first;
				first = second;
				second = swap;
			}
			entry = Math.max(entry, first);
			exit = Math.min(exit, second);
			if (entry > exit)
				return false;
		}

		min = bounds.minY - inflation;
		max = bounds.maxY + inflation;
		if (Math.abs(deltaY) <= 1.0e-12d) {
			if (startY < min || startY > max)
				return false;
		} else {
			double first = (min - startY) / deltaY;
			double second = (max - startY) / deltaY;
			if (first > second) {
				double swap = first;
				first = second;
				second = swap;
			}
			entry = Math.max(entry, first);
			exit = Math.min(exit, second);
			if (entry > exit)
				return false;
		}

		min = bounds.minZ - inflation;
		max = bounds.maxZ + inflation;
		if (Math.abs(deltaZ) <= 1.0e-12d)
			return startZ >= min && startZ <= max;
		double first = (min - startZ) / deltaZ;
		double second = (max - startZ) / deltaZ;
		if (first > second) {
			double swap = first;
			first = second;
			second = swap;
		}
		entry = Math.max(entry, first);
		exit = Math.min(exit, second);
		return entry <= exit;
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
		SEAM_OUTLINE.show(selection.edges, SEAM_HIGHLIGHT_COLOR);
		CUBE_OUTLINE.show(selection.cubeEdges, CUBE_HIGHLIGHT_COLOR);
	}

	private static void clearSeamHighlight() {
		SEAM_OUTLINE.clear();
		CUBE_OUTLINE.clear();
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

	private static boolean isSmartGlue(ItemStack stack) {
		return stack.getItem() instanceof SmartSuperGlueItem;
	}

	private static boolean isSurgicalGlue(ItemStack stack) {
		return isStandardGlue(stack) || isSmartGlue(stack);
	}

	private static void clearSelections() {
		seamSelection = null;
		cubeSelection = null;
		componentSelection = null;
		connectedSelectionCache = null;
		directSelectionCache = null;
		lastSelectionMode = Integer.MIN_VALUE;
		lastSelectionRay = null;
		lastSelectionPendingGlue = null;
		clearSeamHighlight();
	}

	private static final class TableGeometry {
		private final int subjectId;
		private final MimicProfile profile;
		private final SurgicalLayPose layPose;
		private final double originOffsetX;
		private final double originOffsetZ;
		private final int observedCubeCount;
		private final List<SurgicalModelRenderContext.CubeGeometry> baseCubes;
		private final Map<Integer, SurgicalModelRenderContext.CubeGeometry> baseCubesById;
		private List<SurgicalModelRenderContext.CubeGeometry> layoutCubes;
		private List<SurgicalModelRenderContext.CubeGeometry> cubes;
		private Map<Integer, SurgicalModelRenderContext.CubeGeometry> cubesById;
		private List<CubeTarget> cubeTargets;
		@Nullable
		private AABB bounds;
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
		private Map<Integer, SurgicalCubeRotation> serverRotations = Map.of();
		private Map<Integer, SurgicalCubeRotation> rotations = Map.of();
		private final Map<Integer, List<SurgicalClientTopology.Edge>> connectedCubeEdgeCache = new HashMap<>();
		private final Map<Integer, List<SurgicalClientTopology.Edge>> directCubeEdgeCache = new HashMap<>();
		private int renderRevision = Integer.MIN_VALUE;
		private long lastSeenTick;

		private TableGeometry(int subjectId, MimicProfile profile, SurgicalLayPose layPose, double originOffsetX,
			double originOffsetZ, int observedCubeCount,
			List<SurgicalModelRenderContext.CubeGeometry> cubes, List<SurgicalAssembly.Seam> seams,
			List<SurgicalClientTopology.Contact> contacts,
			@Nullable CompletableFuture<SurgicalClientTopology.ContactTopology> pendingTopology,
			long lastSeenTick) {
			this.subjectId = subjectId;
			this.profile = profile;
			this.layPose = layPose;
			this.originOffsetX = originOffsetX;
			this.originOffsetZ = originOffsetZ;
			this.observedCubeCount = observedCubeCount;
			this.baseCubes = List.copyOf(cubes);
			this.baseCubesById = indexCubes(this.baseCubes);
			this.layoutCubes = this.baseCubes;
			this.cubes = this.baseCubes;
			this.cubesById = indexCubes(this.cubes);
			this.cubeTargets = cubeTargets(this.cubes);
			this.bounds = boundsFor(this.cubeTargets);
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
				&& subject.layPose().equals(layPose)
				&& Double.doubleToLongBits(subject.originOffsetX()) == Double.doubleToLongBits(originOffsetX)
				&& Double.doubleToLongBits(subject.originOffsetZ()) == Double.doubleToLongBits(originOffsetZ)
				&& (subject.cubeCount() == 0 || subject.cubeCount() == observedCubeCount);
		}

		private boolean refresh(SurgicalTableBlockEntity table, SurgicalSubject subject) {
			markSeen(table);
			boolean topologyChanged = resolvePendingTopology();
			int revision = subject.clientRenderRevision();
			if (revision == renderRevision && !topologyChanged)
				return false;

			if (subject.cubeCount() == observedCubeCount && !seams.equals(subject.seams())) {
				seams = List.copyOf(subject.seams());
				seamIds = seamIds(seams);
				baseContacts = SurgicalClientTopology.contactsFor(seams, baseCubes);
			}
			presentCubes = subject.presentCubesForRender(observedCubeCount);
			cutSeams = subject.cutSeamsForRender();
			serverOffsets = Map.copyOf(subject.componentOffsetsForRender());
			serverRotations = Map.copyOf(subject.componentRotationsForRender());
			applyTransforms(table, serverOffsets, serverRotations);
			renderRevision = revision;
			return true;
		}

		private void applyPreview(SurgicalTableBlockEntity table, Map<Integer, Vec3> previewOffsets,
			BitSet previewCutSeams) {
			applyTransforms(table, previewOffsets, serverRotations, previewCutSeams);
		}

		private void clearPreview(SurgicalTableBlockEntity table) {
			applyTransforms(table, serverOffsets, serverRotations);
		}

		private void applyTransforms(SurgicalTableBlockEntity table, Map<Integer, Vec3> appliedOffsets,
			Map<Integer, SurgicalCubeRotation> appliedRotations) {
			applyTransforms(table, appliedOffsets, appliedRotations, cutSeams);
		}

		private void applyTransforms(SurgicalTableBlockEntity table, Map<Integer, Vec3> appliedOffsets,
			Map<Integer, SurgicalCubeRotation> appliedRotations,
			BitSet appliedCutSeams) {
			double surfaceY = table.getBlockPos().getY() + 1.0d + SurgicalTablePoseResolver.TABLE_CLEARANCE;
			SurgicalSubject subject = table.getSubject(subjectId);
			layoutCubes = SurgicalTableClientHandler.transformCubes(baseCubes, appliedRotations, Map.of());
			offsets = subject != null && !subject.glueJoints().isEmpty()
				? Map.copyOf(appliedOffsets)
				: SurgicalClientTopology.groundComponents(observedCubeCount, presentCubes,
					seams, appliedCutSeams, layoutCubes, appliedOffsets, surfaceY);
			rotations = Map.copyOf(appliedRotations);
			cubes = SurgicalTableClientHandler.transformCubes(baseCubes, rotations, offsets);
			cubesById = indexCubes(cubes);
			cubeTargets = cubeTargets(cubes);
			bounds = boundsFor(cubeTargets);
			connectedCubeEdgeCache.clear();
			directCubeEdgeCache.clear();
			contacts = transformContacts(baseContacts, baseCubesById, rotations, offsets);
			contactsByCube = contactsByCube(observedCubeCount, contacts);
			if (bounds != null)
				table.includeClientRenderBounds(bounds);
		}

		private static List<CubeTarget> cubeTargets(
			List<SurgicalModelRenderContext.CubeGeometry> cubes) {
			List<CubeTarget> targets = new ArrayList<>(cubes.size());
			for (SurgicalModelRenderContext.CubeGeometry cube : cubes) {
				double minX = Double.POSITIVE_INFINITY;
				double minY = Double.POSITIVE_INFINITY;
				double minZ = Double.POSITIVE_INFINITY;
				double maxX = Double.NEGATIVE_INFINITY;
				double maxY = Double.NEGATIVE_INFINITY;
				double maxZ = Double.NEGATIVE_INFINITY;
				for (Vec3 corner : cube.corners()) {
					minX = Math.min(minX, corner.x);
					minY = Math.min(minY, corner.y);
					minZ = Math.min(minZ, corner.z);
					maxX = Math.max(maxX, corner.x);
					maxY = Math.max(maxY, corner.y);
					maxZ = Math.max(maxZ, corner.z);
				}
				if (minX != Double.POSITIVE_INFINITY)
					targets.add(new CubeTarget(cube, new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
			}
			return List.copyOf(targets);
		}

		@Nullable
		private static AABB boundsFor(List<CubeTarget> targets) {
			AABB result = null;
			for (CubeTarget target : targets)
				result = result == null ? target.bounds : result.minmax(target.bounds);
			return result;
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

		private static List<SurgicalClientTopology.Contact> transformContacts(
			List<SurgicalClientTopology.Contact> contacts,
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> baseCubes,
			Map<Integer, SurgicalCubeRotation> rotations, Map<Integer, Vec3> offsets) {
			if (rotations.isEmpty() && offsets.isEmpty())
				return contacts;
			List<SurgicalClientTopology.Contact> translated = new ArrayList<>(contacts.size());
			for (SurgicalClientTopology.Contact contact : contacts) {
				Vec3 offset = offsets.getOrDefault(contact.anchorCubeId(), Vec3.ZERO);
				SurgicalCubeRotation rotation = rotations.getOrDefault(contact.anchorCubeId(),
					SurgicalCubeRotation.IDENTITY);
				SurgicalModelRenderContext.CubeGeometry base = baseCubes.get(contact.anchorCubeId());
				if (base == null || offset.equals(Vec3.ZERO) && rotation.isIdentity()) {
					translated.add(contact);
					continue;
				}
				Vec3 center = cubeCenter(base);
				List<List<Vec3>> faces = new ArrayList<>(contact.faces().size());
				for (List<Vec3> face : contact.faces()) {
					List<Vec3> translatedFace = new ArrayList<>(face.size());
					for (Vec3 point : face)
						translatedFace.add(center.add(rotation.rotate(point.subtract(center))).add(offset));
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

	/** Reuses Create outline objects and only rewrites geometry/colour when the selection changes. */
	private static final class OutlineState {
		private final List<Object> slots = new ArrayList<>();
		private List<SurgicalClientTopology.Edge> edges = List.of();
		private long lastRefreshTick = Long.MIN_VALUE;

		private void show(List<SurgicalClientTopology.Edge> nextEdges, int color) {
			ClientLevel level = Minecraft.getInstance().level;
			long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
			if (edges.equals(nextEdges)) {
				if (tick != lastRefreshTick) {
					for (int edge = 0; edge < edges.size(); edge++)
						Outliner.getInstance().keep(slots.get(edge));
					lastRefreshTick = tick;
				}
				return;
			}

			int previousCount = edges.size();
			int edgeCount = nextEdges.size();
			while (slots.size() < edgeCount)
				slots.add(new Object());
			for (int edge = 0; edge < edgeCount; edge++)
				Outliner.getInstance()
					.showLine(slots.get(edge), nextEdges.get(edge).start(), nextEdges.get(edge).end())
					.lineWidth(HIGHLIGHT_LINE_WIDTH)
					.disableLineNormals()
					.colored(color);
			for (int edge = edgeCount; edge < previousCount; edge++)
				Outliner.getInstance().remove(slots.get(edge));
			edges = List.copyOf(nextEdges);
			lastRefreshTick = tick;
		}

		private void clear() {
			for (int edge = 0; edge < edges.size(); edge++)
				Outliner.getInstance().remove(slots.get(edge));
			edges = List.of();
			lastRefreshTick = Long.MIN_VALUE;
		}
	}

	private record SubjectKey(BlockPos tablePos, int subjectId) {}

	private record Selection(BlockPos tablePos, int subjectId, int targetId, int observedCubeCount,
		List<SurgicalAssembly.Seam> seams, List<SurgicalClientTopology.Edge> edges,
		List<SurgicalClientTopology.Edge> cubeEdges, boolean glueJoint) {
		private Selection(BlockPos tablePos, int subjectId, int targetId, int observedCubeCount,
			List<SurgicalAssembly.Seam> seams, List<SurgicalClientTopology.Edge> edges,
			List<SurgicalClientTopology.Edge> cubeEdges) {
			this(tablePos, subjectId, targetId, observedCubeCount, seams, edges, cubeEdges, false);
		}
	}

	private record GlueJointSelection(Selection selection,
		@Nullable SurgicalClientTopology.Contact contact) {}

	private record Ray(Vec3 start, Vec3 end) {}

	private record CubeHit(BlockPos tablePos, TableGeometry geometry, int cubeId, int faceIndex, Vec3 location) {}

	private record GluePreviewCubeHit(GlueSubjectPreview subject,
		SurgicalModelRenderContext.CubeGeometry geometry, int cubeId, int faceIndex, Vec3 location) {}

	private record CubeTarget(SurgicalModelRenderContext.CubeGeometry geometry, AABB bounds) {}

	private record CubeSelectionCache(BlockPos tablePos, int subjectId, int cubeId, int tableRevision,
		int renderRevision, Selection selection) {
		private boolean matches(CubeHit hit, int revision) {
			return tablePos.equals(hit.tablePos) && subjectId == hit.geometry.subjectId && cubeId == hit.cubeId
				&& tableRevision == revision && renderRevision == hit.geometry.renderRevision;
		}
	}


	private record PlacementPreview(BlockPos ownerPos, InteractionHand hand, PlacementSource source,
		Direction facing, SurgicalLayPose layPose, SurgicalClientTopology.PlacementPlan plan,
		Map<Integer, Vec3> cubeOffsets,
		List<SourcePlacementGeometry> sourceGeometries,
		List<SurgicalTableLayout.Proposal> sourceLayouts, boolean projectSourceGeometry,
		SurgicalTablePlane.WorkArea workArea, double targetX, double targetZ, int tableRevision) {
		private PlacementPreview {
			cubeOffsets = Map.copyOf(cubeOffsets);
			sourceGeometries = List.copyOf(sourceGeometries);
			sourceLayouts = List.copyOf(sourceLayouts);
		}
	}

	private record PlacementGeometry(EntityGeometry.Bounds bounds, SurgicalLayPose layPose,
		Map<Integer, Vec3> cubeOffsets,
		List<SourcePlacementGeometry> sources) {
		private PlacementGeometry {
			cubeOffsets = Map.copyOf(cubeOffsets);
			sources = List.copyOf(sources);
		}
	}

	private record SourcePlacementGeometry(SurgicalAssembly.PlacedSource placedSource,
		List<SurgicalModelRenderContext.CubeGeometry> baseCubes, Map<Integer, Vec3> renderOffsets,
		Map<Integer, SurgicalCubeRotation> renderRotations) {
		private SourcePlacementGeometry {
			baseCubes = List.copyOf(baseCubes);
			renderOffsets = Map.copyOf(renderOffsets);
			renderRotations = Map.copyOf(renderRotations);
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
			SurgicalTablePoseResolver.SurgicalPose resolved =
				SurgicalTablePoseResolver.resolve(this, profile, entity, facing);
			resolved.apply(poseStack);
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
				resolved.apply(poseStack);
				SurgicalSourceModelRenderer.render(entity, cubeCount(), presentCubes(), cubeOffsets, poseStack,
					measuringBuffer, LightTexture.FULL_BRIGHT, 0.0f, 0.0f, false, null,
					projectSourceGeometry);
				if (!sink.hasVertices())
					return null;
				bounds = sink.bounds();
			}
			measuredFacing = facing;
			measuredSourceGeometry = projectSourceGeometry;
			measuredGeometry = new PlacementGeometry(bounds, resolved.layPose(), cubeOffsets, List.of());
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
					source.cubeCount(), source.presentCubes(), Map.of(), placedSource.cubeRotations(),
					poseStack, discardedBuffer,
					LightTexture.FULL_BRIGHT, 0.0f, 0.0f, true, null, projectSourceGeometry);
				if (snapshot.observedCubeCount() != source.cubeCount()
					|| snapshot.cubes().size() != source.presentCubes().cardinality())
					return null;
				// A composite assembly promises to preserve its packed layout. In particular, keep
				// the saved vertical component offsets: the server restores these exact values when
				// the sources become table subjects, so grounding them again here would make the
				// placement preview disagree with the confirmed result.
				Map<Integer, Vec3> renderOffsets = placedSource.cubeOffsets();
				geometries.add(new SourcePlacementGeometry(placedSource, snapshot.cubes(), renderOffsets,
					placedSource.cubeRotations()));
				discarded.reset();

				poseStack = sourcePose(placedSource, entity);
				SurgicalSourceModelRenderer.render(entity, source.cubeCount(), source.presentCubes(),
					renderOffsets, placedSource.cubeRotations(), poseStack, combinedBuffer,
					LightTexture.FULL_BRIGHT,
					0.0f, 0.0f, false, null, projectSourceGeometry);
			}
			return combined.hasVertices()
				? new PlacementGeometry(combined.bounds(), assembly.placedLayPose(facing), Map.of(), geometries) : null;
		}

		private PoseStack sourcePose(SurgicalAssembly.PlacedSource placedSource, LivingEntity entity) {
			SurgicalAssembly.Source source = placedSource.source();
			PoseStack poseStack = new PoseStack();
			poseStack.translate(placedSource.originOffset().x, placedSource.originOffset().y,
				placedSource.originOffset().z);
			SurgicalTablePoseResolver.resolve(placedSource.layPose()).apply(poseStack);
			return poseStack;
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
		private double lastTargetX = Double.NaN;
		private double lastTargetZ = Double.NaN;
		private int plannedTableRevision = Integer.MIN_VALUE;

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

	private static final class PendingGlueCut {
		private final BlockPos tablePos;
		private final int subjectId;
		private final InteractionHand hand;
		private final int targetId;
		private final int observedCubeCount;
		private final List<SurgicalAssembly.Seam> seams;
		private final SurgicalGlueJoint joint;
		private final Map<Integer, BitSet> movingComponents;
		@Nullable
		private SurgicalClientTopology.ConnectedPlacement planned;
		private double lastTargetX = Double.NaN;
		private double lastTargetZ = Double.NaN;
		private int plannedTableRevision = Integer.MIN_VALUE;

		private PendingGlueCut(Selection selection, InteractionHand hand, SurgicalGlueJoint joint,
			Map<Integer, BitSet> movingComponents,
			@Nullable SurgicalClientTopology.ConnectedPlacement planned) {
			tablePos = selection.tablePos;
			subjectId = selection.subjectId;
			this.hand = hand;
			targetId = selection.targetId;
			observedCubeCount = selection.observedCubeCount;
			seams = List.copyOf(selection.seams);
			this.joint = joint;
			Map<Integer, BitSet> frozen = new HashMap<>();
			movingComponents.forEach((key, value) -> frozen.put(key, (BitSet) value.clone()));
			this.movingComponents = Map.copyOf(frozen);
			this.planned = planned;
		}
	}

	private record FootprintGroups(List<SurgicalTableLayout.Footprint> moving,
		List<SurgicalTableLayout.Footprint> occupied) {}

	private record PendingGlue(Selection selection, Vec3 hit, InteractionHand hand, int faceIndex) {}

	private record GluePlanningSubject(SurgicalSubject subject, BitSet cubes,
		List<SurgicalModelRenderContext.CubeGeometry> baseTarget,
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> desired,
		Map<Integer, SurgicalCubeRotation> targetRotations) {
		private GluePlanningSubject {
			cubes = (BitSet) cubes.clone();
			baseTarget = List.copyOf(baseTarget);
			desired = Map.copyOf(desired);
			targetRotations = Map.copyOf(targetRotations);
		}
	}

	private static final class GlueSubjectPreview {
		private final int subjectId;
		private final BitSet cubes;
		private final SurgicalLayPose pose;
		private final List<SurgicalModelRenderContext.CubeGeometry> baseCubes;
		private final Map<Integer, Vec3> offsets;
		private final Map<Integer, SurgicalCubeRotation> rotations;
		private final boolean editable;
		private final Map<Integer, SurgicalModelRenderContext.CubeGeometry> transformedCubes;

		private GlueSubjectPreview(int subjectId, BitSet cubes, SurgicalLayPose pose,
			List<SurgicalModelRenderContext.CubeGeometry> baseCubes, Map<Integer, Vec3> offsets,
			Map<Integer, SurgicalCubeRotation> rotations, boolean editable) {
			this.subjectId = subjectId;
			this.cubes = (BitSet) cubes.clone();
			this.pose = pose;
			this.baseCubes = List.copyOf(baseCubes);
			this.offsets = Map.copyOf(offsets);
			this.rotations = Map.copyOf(rotations);
			this.editable = editable;
			Map<Integer, SurgicalModelRenderContext.CubeGeometry> transformed = new HashMap<>();
			for (int cube = this.cubes.nextSetBit(0); cube >= 0; cube = this.cubes.nextSetBit(cube + 1)) {
				SurgicalModelRenderContext.CubeGeometry geometry = transformedPreviewCube(
					this.baseCubes, this.offsets, this.rotations, cube);
				if (geometry != null)
					transformed.put(cube, geometry);
			}
			this.transformedCubes = Map.copyOf(transformed);
		}
	}

	private record GluePreview(PendingGlue request, BlockPos ownerPos, int targetSubjectId, int targetCubeId,
		Vec3 targetHit, int tableRevision, SurgicalLayPose targetPose, double groundLiftY,
		SurgicalTablePlane.WorkArea workArea, List<SurgicalTableLayout.Footprint> obstacles,
		List<GlueSubjectPreview> subjects, List<SurgicalTableGluePacket.Move> moves,
		List<SurgicalTableGluePacket.AnchorMove> anchorMoves) {
		private GluePreview {
			obstacles = List.copyOf(obstacles);
			subjects = List.copyOf(subjects);
			moves = List.copyOf(moves);
			anchorMoves = List.copyOf(anchorMoves);
		}

		private boolean matches(PendingGlue pending, CubeHit hit, int revision) {
			return request == pending && ownerPos.equals(hit.tablePos)
				&& targetSubjectId == hit.geometry.subjectId && targetCubeId == hit.cubeId
				&& tableRevision == revision && targetHit.distanceToSqr(hit.location) <= 1.0e-14d;
		}
	}

	private static final class GlueEditor {
		private final InteractionHand hand;
		private final SurgicalTableGluePacket.Endpoint first;
		private final SurgicalTableGluePacket.Endpoint second;
		private Vec3 axis;
		private double guideRadius;
		private GluePreview preview;
		private Vec3 axisCenter;
		private Vec3 faceCenter;

		private GlueEditor(InteractionHand hand, SurgicalTableGluePacket.Endpoint first,
			SurgicalTableGluePacket.Endpoint second, GluePreview preview, Vec3 axisCenter,
			Vec3 faceCenter, Vec3 axis, double guideRadius) {
			this.hand = hand;
			this.first = first;
			this.second = second;
			this.preview = preview;
			this.axisCenter = axisCenter;
			this.faceCenter = faceCenter;
			this.axis = axis.normalize();
			this.guideRadius = guideRadius;
		}
	}
}
