package com.nobodiiiii.createbiotech.content.smartglue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.base.Objects;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.content.contraptions.chassis.AbstractChassisBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueItem;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.foundation.utility.RaycastHelper;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class SmartSuperGlueSelectionHandler {

	private static final int PASSIVE = 0x4D9162;
	private static final int HIGHLIGHT = 0x68C586;
	private static final int FAIL = 0xC5B548;

	private final Object selectionClusterOutlineSlot = new Object();
	private final Object selectionBoxOutlineSlot = new Object();
	private final Object mergedGroupOutlineSlot = new Object();

	private int clusterCooldown;
	private BlockPos firstPos;
	private BlockPos hoveredPos;
	private Set<BlockPos> currentCluster;
	private int glueRequired;
	private SuperGlueEntity selected;
	private Set<BlockPos> selectedGroupBlocks = Set.of();
	private BlockPos soundSourceForRemoval;
	private int selectedGroupAnchorId = -1;
	private boolean singleSelectionRender;

	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			clear();
			return;
		}

		BlockPos hovered = null;
		ItemStack stack = player.getMainHandItem();
		if (!isGlue(stack)) {
			if (firstPos != null)
				discard();
			selected = null;
			selectedGroupBlocks = Set.of();
			selectedGroupAnchorId = -1;
			return;
		}

		if (clusterCooldown > 0) {
			if (clusterCooldown == 25)
				player.displayClientMessage(CommonComponents.EMPTY, true);
			Outliner.getInstance().keep(selectionClusterOutlineSlot);
			clusterCooldown--;
		}

		AABB scanArea = player.getBoundingBox().inflate(32, 16, 32);
		List<SuperGlueEntity> glueNearby = mc.level.getEntitiesOfClass(SuperGlueEntity.class, scanArea).stream()
			.filter(SmartSuperGlueHelper::isSmartGlueCompatible)
			.toList();

		selected = null;
		if (firstPos == null) {
			double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1;
			Vec3 traceOrigin = player.getEyePosition();
			Vec3 traceTarget = RaycastHelper.getTraceTarget(player, range, traceOrigin);

			double bestDistance = Double.MAX_VALUE;
			for (SuperGlueEntity glueEntity : glueNearby) {
				Optional<Vec3> clip = glueEntity.getBoundingBox().clip(traceOrigin, traceTarget);
				if (clip.isEmpty())
					continue;

				Vec3 hit = clip.get();
				double distanceToSqr = hit.distanceToSqr(traceOrigin);
				if (distanceToSqr > bestDistance)
					continue;

				selected = glueEntity;
				soundSourceForRemoval = BlockPos.containing(hit);
				bestDistance = distanceToSqr;
			}

			boolean singleRender = player.isShiftKeyDown();
			updateSelectedGroup(mc.level, singleRender);
			renderExistingGlue(glueNearby, singleRender);
		}

		HitResult hitResult = mc.hitResult;
		if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
			hovered = ((BlockHitResult) hitResult).getBlockPos();

		if (hovered == null) {
			hoveredPos = null;
			return;
		}

		if (firstPos != null && !firstPos.closerThan(hovered, 24)) {
			CreateLang.translate("super_glue.too_far").color(FAIL).sendStatus(player);
			return;
		}

		boolean cancel = player.isShiftKeyDown();
		if (cancel && firstPos == null)
			return;

		AABB currentSelectionBox = getCurrentSelectionBox();
		boolean unchanged = Objects.equal(hovered, hoveredPos);
		if (unchanged) {
			if (currentCluster != null) {
				boolean canReach = currentCluster.contains(hovered);
				boolean canAfford = SmartSuperGlueHelper.collectGlueFromInventory(player, glueRequired, true);
				int color = HIGHLIGHT;
				String key = "super_glue.click_to_confirm";

				if (!canReach) {
					color = FAIL;
					key = "super_glue.cannot_reach";
				} else if (!canAfford) {
					color = FAIL;
					key = "super_glue.not_enough";
				} else if (cancel) {
					color = FAIL;
					key = "super_glue.click_to_discard";
				}

				CreateLang.translate(key).color(color).sendStatus(player);

				if (currentSelectionBox != null)
					Outliner.getInstance().showAABB(selectionBoxOutlineSlot, currentSelectionBox)
						.colored(canReach && canAfford && !cancel ? HIGHLIGHT : FAIL)
						.withFaceTextures(AllSpecialTextures.GLUE, AllSpecialTextures.GLUE)
						.disableLineNormals()
						.lineWidth(1 / 16f);

				Outliner.getInstance().showCluster(selectionClusterOutlineSlot, currentCluster)
					.colored(PASSIVE)
					.disableLineNormals()
					.lineWidth(1 / 64f);
			}

			return;
		}

		hoveredPos = hovered;
		currentCluster = SuperGlueSelectionHelper.searchGlueGroup(mc.level, firstPos, hoveredPos, true);
		glueRequired = 1;
	}

	public boolean onMouseInput(boolean attack) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !isGlue(player.getMainHandItem()) || !player.mayBuild())
			return false;

		if (attack) {
			if (selected == null || soundSourceForRemoval == null)
				return false;
			CBPackets.sendToServer(
				new SmartSuperGlueRemovalPacket(selected.getId(), soundSourceForRemoval, !player.isShiftKeyDown()));
			resetSelectionAfterRemoval();
			return true;
		}

		if (player.isShiftKeyDown()) {
			if (firstPos != null) {
				discard();
				return true;
			}
			return false;
		}

		if (hoveredPos == null)
			return false;

		Direction face = null;
		if (mc.hitResult instanceof BlockHitResult blockHitResult) {
			face = blockHitResult.getDirection();
			BlockState blockState = level.getBlockState(hoveredPos);
			if (blockState.getBlock() instanceof AbstractChassisBlock chassisBlock
				&& chassisBlock.getGlueableSide(blockState, blockHitResult.getDirection()) != null)
				return false;
		}

		if (firstPos != null && currentCluster != null) {
			boolean canReach = currentCluster.contains(hoveredPos);
			boolean canAfford = SmartSuperGlueHelper.collectGlueFromInventory(player, glueRequired, true);
			if (!canReach || !canAfford)
				return true;

			confirm();
			return true;
		}

		firstPos = hoveredPos;
		if (face != null)
			SuperGlueItem.spawnParticles(level, firstPos, face, true);
		CreateLang.translate("super_glue.first_pos").sendStatus(player);
		AllSoundEvents.SLIME_ADDED.playAt(level, firstPos, 0.5F, 0.85F, false);
		level.playSound(player, firstPos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1);
		return true;
	}

	public void discard() {
		LocalPlayer player = Minecraft.getInstance().player;
		currentCluster = null;
		firstPos = null;
		hoveredPos = null;
		if (player != null)
			CreateLang.translate("super_glue.abort").sendStatus(player);
		clusterCooldown = 0;
	}

	public void clear() {
		currentCluster = null;
		firstPos = null;
		hoveredPos = null;
		selected = null;
		selectedGroupBlocks = Set.of();
		soundSourceForRemoval = null;
		clusterCooldown = 0;
		selectedGroupAnchorId = -1;
		singleSelectionRender = false;
	}

	private void confirm() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || hoveredPos == null || firstPos == null) {
			clear();
			return;
		}

		CBPackets.sendToServer(new SmartSuperGlueSelectionPacket(firstPos, hoveredPos));
		AllSoundEvents.SLIME_ADDED.playAt(player.level(), hoveredPos, 0.5F, 0.95F, false);
		player.level().playSound(player, hoveredPos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1);

		if (currentCluster != null)
			Outliner.getInstance().showCluster(selectionClusterOutlineSlot, currentCluster)
				.colored(0xB5F2C6)
				.withFaceTextures(AllSpecialTextures.GLUE, AllSpecialTextures.HIGHLIGHT_CHECKERED)
				.disableLineNormals()
				.lineWidth(1 / 24f);

		discard();
		CreateLang.translate("super_glue.success").sendStatus(player);
		clusterCooldown = 40;
	}

	private void updateSelectedGroup(ClientLevel level, boolean singleRender) {
		int currentSelectedId = selected == null ? -1 : selected.getId();
		if (currentSelectedId == selectedGroupAnchorId && singleRender == singleSelectionRender)
			return;

		selectedGroupAnchorId = currentSelectedId;
		singleSelectionRender = singleRender;
		if (selected == null || singleRender) {
			selectedGroupBlocks = Set.of();
			return;
		}

		selectedGroupBlocks =
			SmartSuperGlueHelper.collectCoveredBlocks(SmartSuperGlueHelper.findConnectedGlueEntities(level, selected));
	}

	private void renderExistingGlue(List<SuperGlueEntity> glueNearby, boolean singleRender) {
		if (!singleRender) {
			renderConnectedGlueGroups(Minecraft.getInstance().level, glueNearby);
			return;
		}

		for (SuperGlueEntity glueEntity : glueNearby) {
			boolean highlighted = clusterCooldown == 0 && glueEntity == selected;
			AllSpecialTextures faceTexture = highlighted ? AllSpecialTextures.GLUE : null;
			Outliner.getInstance().showAABB(glueEntity, glueEntity.getBoundingBox())
				.colored(highlighted ? HIGHLIGHT : PASSIVE)
				.withFaceTextures(faceTexture, faceTexture)
				.disableLineNormals()
				.lineWidth(highlighted ? 1 / 16f : 1 / 64f);
		}
	}

	private boolean isGlue(ItemStack stack) {
		return stack.getItem() instanceof SmartSuperGlueItem;
	}

	private void renderConnectedGlueGroups(ClientLevel level, List<SuperGlueEntity> glueNearby) {
		if (level == null)
			return;

		boolean highlightSelectedGroup = clusterCooldown == 0 && !selectedGroupBlocks.isEmpty();
		for (SmartSuperGlueHelper.ConnectedGlueGroup group :
			SmartSuperGlueHelper.findConnectedGlueGroups(level, glueNearby)) {
			boolean isSelectedGroup = selected != null && group.entities().contains(selected);
			if (isSelectedGroup) {
				AllSpecialTextures faceTexture = highlightSelectedGroup ? AllSpecialTextures.GLUE : null;
				Outliner.getInstance().showCluster(mergedGroupOutlineSlot, selectedGroupBlocks)
					.colored(highlightSelectedGroup ? HIGHLIGHT : PASSIVE)
					.withFaceTextures(faceTexture, faceTexture)
					.disableLineNormals()
					.lineWidth(highlightSelectedGroup ? 1 / 16f : 1 / 64f);
				continue;
			}

			Outliner.getInstance().showCluster(getPassiveGroupOutlineKey(group.anchorId()), group.coveredBlocks())
				.colored(PASSIVE)
				.disableLineNormals()
				.lineWidth(1 / 64f);
		}
	}

	private String getPassiveGroupOutlineKey(int anchorId) {
		return "create_biotech.smart_super_glue_group." + anchorId;
	}

	private void resetSelectionAfterRemoval() {
		selected = null;
		selectedGroupBlocks = Set.of();
		soundSourceForRemoval = null;
		selectedGroupAnchorId = -1;
		clusterCooldown = 0;
	}

	private AABB getCurrentSelectionBox() {
		if (firstPos == null || hoveredPos == null)
			return null;
		return new AABB(Vec3.atLowerCornerOf(firstPos), Vec3.atLowerCornerOf(hoveredPos)).expandTowards(1, 1, 1);
	}
}
