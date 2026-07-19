package com.nobodiiiii.createbiotech.content.smartglue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class SmartSuperGlueHelper {

	private SmartSuperGlueHelper() {}

	public static boolean collectGlueFromInventory(Player player, int requiredAmount, boolean simulate) {
		if (player.getAbilities().instabuild || requiredAmount == 0)
			return true;

		NonNullList<ItemStack> items = player.getInventory().items;
		for (int i = -1; i < items.size(); i++) {
			int slot = i == -1 ? player.getInventory().selected : i;
			ItemStack stack = items.get(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof SmartSuperGlueItem))
				continue;

			int charges = Math.min(requiredAmount, stack.getMaxDamage() - stack.getDamageValue());
			if (!simulate)
				stack.hurtAndBreak(charges, player, LivingEntity.getSlotForHand(InteractionHand.MAIN_HAND));

			requiredAmount -= charges;
			if (requiredAmount <= 0)
				return true;
		}

		return false;
	}

	public static boolean isSmartGlueCompatible(SuperGlueEntity glueEntity) {
		return true;
	}

	public static Set<SuperGlueEntity> findConnectedGlueEntities(Level level, SuperGlueEntity seed) {
		if (!isSmartGlueCompatible(seed))
			return Set.of();

		LinkedHashSet<SuperGlueEntity> connected = new LinkedHashSet<>();
		Set<Integer> visited = new HashSet<>();
		ArrayDeque<SuperGlueEntity> frontier = new ArrayDeque<>();
		frontier.add(seed);
		visited.add(seed.getId());

		while (!frontier.isEmpty()) {
			SuperGlueEntity current = frontier.removeFirst();
			if (!current.isAlive() || !isSmartGlueCompatible(current))
				continue;

			connected.add(current);
			for (SuperGlueEntity candidate :
				level.getEntitiesOfClass(SuperGlueEntity.class, current.getBoundingBox().inflate(1.0E-3D))) {
				if (candidate == current || visited.contains(candidate.getId()) || !isSmartGlueCompatible(candidate))
					continue;
				if (!boxesShareCoveredBlocks(current.getBoundingBox(), candidate.getBoundingBox()))
					continue;

				visited.add(candidate.getId());
				frontier.add(candidate);
			}
		}

		return connected;
	}

	public static List<ConnectedGlueGroup> findConnectedGlueGroups(Level level, Collection<SuperGlueEntity> entities) {
		List<ConnectedGlueGroup> groups = new ArrayList<>();
		Set<Integer> visited = new HashSet<>();
		for (SuperGlueEntity entity : entities) {
			if (!isSmartGlueCompatible(entity) || !visited.add(entity.getId()))
				continue;

			Set<SuperGlueEntity> connected = findConnectedGlueEntities(level, entity);
			if (connected.isEmpty())
				continue;

			connected.forEach(glueEntity -> visited.add(glueEntity.getId()));
			groups.add(new ConnectedGlueGroup(entity.getId(), connected, collectCoveredBlocks(connected)));
		}

		return groups;
	}

	public static Set<AABB> findConnectedGlueBoxes(Collection<AABB> boxes, AABB seed) {
		LinkedHashSet<AABB> connected = new LinkedHashSet<>();
		ArrayDeque<AABB> frontier = new ArrayDeque<>();
		frontier.add(seed);

		while (!frontier.isEmpty()) {
			AABB current = frontier.removeFirst();
			if (!connected.add(current))
				continue;

			for (AABB candidate : boxes) {
				if (connected.contains(candidate) || !boxesShareCoveredBlocks(current, candidate))
					continue;
				frontier.add(candidate);
			}
		}

		return connected;
	}

	public static Set<BlockPos> collectCoveredBlocks(Collection<SuperGlueEntity> entities) {
		return collectCoveredBlocksFromBoxes(entities.stream().map(SuperGlueEntity::getBoundingBox).toList());
	}

	public static Set<BlockPos> collectCoveredBlocksFromBoxes(Collection<AABB> boxes) {
		LinkedHashSet<BlockPos> covered = new LinkedHashSet<>();
		for (AABB box : boxes) {
			int minX = coveredBlockMin(box.minX);
			int maxX = coveredBlockMax(box.maxX);
			int minY = coveredBlockMin(box.minY);
			int maxY = coveredBlockMax(box.maxY);
			int minZ = coveredBlockMin(box.minZ);
			int maxZ = coveredBlockMax(box.maxZ);

			for (int x = minX; x <= maxX; x++)
				for (int y = minY; y <= maxY; y++)
					for (int z = minZ; z <= maxZ; z++)
						covered.add(new BlockPos(x, y, z));
		}

		return covered;
	}

	public static boolean boxesShareCoveredBlocks(AABB first, AABB second) {
		return rangesOverlap(coveredBlockMin(first.minX), coveredBlockMax(first.maxX), coveredBlockMin(second.minX),
			coveredBlockMax(second.maxX))
			&& rangesOverlap(coveredBlockMin(first.minY), coveredBlockMax(first.maxY), coveredBlockMin(second.minY),
				coveredBlockMax(second.maxY))
			&& rangesOverlap(coveredBlockMin(first.minZ), coveredBlockMax(first.maxZ), coveredBlockMin(second.minZ),
				coveredBlockMax(second.maxZ));
	}

	private static int coveredBlockMin(double min) {
		return Mth.ceil(min - 0.5D);
	}

	private static int coveredBlockMax(double max) {
		return Mth.floor(max - 0.5D);
	}

	private static boolean rangesOverlap(int minA, int maxA, int minB, int maxB) {
		return maxA >= minB && maxB >= minA;
	}

	public record ConnectedGlueGroup(int anchorId, Set<SuperGlueEntity> entities, Set<BlockPos> coveredBlocks) {}
}
