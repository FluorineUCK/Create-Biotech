package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import java.util.Map;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import net.createmod.catnip.platform.CatnipServices;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public class GhastHelmClientHandler {

	private static final double DETECT_RADIUS = 16d;
	private static final double ENGAGED_RADIUS = 24d;
	private static final int SCAN_PERIOD_TICKS = 10;
	private static final int KEEP_ALIVE_PERIOD_TICKS = 4;

	private static boolean previousSprintDown;
	private static boolean controllingGhastBalloon;

	private static BlockPos nearestStation;
	private static BlockPos engagedStation;
	private static int scanCooldown;
	private static int keepAliveCooldown;

	private GhastHelmClientHandler() {}

	public static void startControlling(GhastHotAirBalloonEntity entity) {
		controllingGhastBalloon = entity != null;
		previousSprintDown = false;
		nearestStation = null;
		engagedStation = null;
		scanCooldown = 0;
		keepAliveCooldown = 0;
	}

	public static boolean shouldShowMagnetPrompt() {
		return controllingGhastBalloon && nearestStation != null;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!controllingGhastBalloon)
			return;

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			reset();
			return;
		}

		AbstractContraptionEntity contraption = ControlsHandler.getContraption();
		BlockPos controlsPos = ControlsHandler.getControlsPos();
		if (contraption == null || controlsPos == null) {
			disengageIfEngaged(contraption);
			reset();
			return;
		}

		boolean sprintDown = minecraft.options.keySprint.isDown();
		if (sprintDown && !previousSprintDown) {
			disengageIfEngaged(contraption);
			CatnipServices.NETWORK.sendToServer(
				new ControlsInputPacket(ControlsHandler.currentlyPressed, false, contraption.getId(), controlsPos, true));
			ControlsHandler.stopControlling();
			reset();
			return;
		}
		previousSprintDown = sprintDown;

		tickMagnetSnap(minecraft, contraption);
	}

	private static void tickMagnetSnap(Minecraft minecraft, AbstractContraptionEntity contraption) {
		if (!(contraption instanceof GhastHotAirBalloonEntity balloon)) {
			disengageIfEngaged(contraption);
			nearestStation = null;
			return;
		}
		if (!(balloon.getVehicle() instanceof Ghast ghast) || !ghast.isAlive()) {
			disengageIfEngaged(contraption);
			nearestStation = null;
			return;
		}

		if (--scanCooldown <= 0) {
			double radius = engagedStation != null ? ENGAGED_RADIUS : DETECT_RADIUS;
			nearestStation = findClosestValidStation(minecraft.level, ghast.position(), radius);
			scanCooldown = SCAN_PERIOD_TICKS;
		}

		boolean altDown = minecraft.screen == null && Screen.hasAltDown();
		if (altDown && nearestStation != null) {
			boolean targetChanged = engagedStation == null || !engagedStation.equals(nearestStation);
			if (targetChanged || --keepAliveCooldown <= 0) {
				CBPackets.sendToServer(new GhastBalloonMagnetTargetPacket(balloon.getId(), nearestStation));
				engagedStation = nearestStation;
				keepAliveCooldown = KEEP_ALIVE_PERIOD_TICKS;
			}
		} else if (engagedStation != null) {
			CBPackets.sendToServer(new GhastBalloonMagnetTargetPacket(balloon.getId(), null));
			engagedStation = null;
			keepAliveCooldown = 0;
		}
	}

	private static void disengageIfEngaged(AbstractContraptionEntity contraption) {
		if (engagedStation == null)
			return;
		if (contraption instanceof GhastHotAirBalloonEntity)
			CBPackets.sendToServer(new GhastBalloonMagnetTargetPacket(contraption.getId(), null));
		engagedStation = null;
		keepAliveCooldown = 0;
	}

	private static BlockPos findClosestValidStation(ClientLevel level, Vec3 center, double radius) {
		double r2 = radius * radius;
		int minCX = SectionPos.blockToSectionCoord((int) Math.floor(center.x - radius));
		int maxCX = SectionPos.blockToSectionCoord((int) Math.ceil(center.x + radius));
		int minCZ = SectionPos.blockToSectionCoord((int) Math.floor(center.z - radius));
		int maxCZ = SectionPos.blockToSectionCoord((int) Math.ceil(center.z + radius));

		BlockPos closest = null;
		double closestDistSqr = Double.MAX_VALUE;
		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				if (!level.hasChunk(cx, cz))
					continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					if (!(entry.getValue() instanceof GhastHotAirBalloonAssemblyStationBlockEntity station))
						continue;
					if (!station.isReadyToAccept())
						continue;
					BlockPos pos = entry.getKey();
					Vec3 stationCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
					double dsq = stationCenter.distanceToSqr(center);
					if (dsq > r2)
						continue;
					if (dsq < closestDistSqr) {
						closestDistSqr = dsq;
						closest = pos;
					}
				}
			}
		}
		return closest;
	}

	private static void reset() {
		controllingGhastBalloon = false;
		previousSprintDown = false;
		nearestStation = null;
		engagedStation = null;
		scanCooldown = 0;
		keepAliveCooldown = 0;
	}
}
