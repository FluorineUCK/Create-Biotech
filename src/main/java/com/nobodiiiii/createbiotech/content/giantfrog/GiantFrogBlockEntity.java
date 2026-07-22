package com.nobodiiiii.createbiotech.content.giantfrog;

import java.util.Comparator;
import java.util.List;

import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GiantFrogBlockEntity extends BlockEntity {
	private static final int EAT_INTERVAL = 20;
	private static final double TONGUE_REACH = 1.25d;

	private int eatCooldown;

	public GiantFrogBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.GIANT_FROG.get(), pos, state);
		eatCooldown = levelRandomOffset(pos);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, GiantFrogBlockEntity be) {
		if (level.isClientSide)
			return;

		if (be.eatCooldown > 0) {
			be.eatCooldown--;
			return;
		}

		be.eatCooldown = EAT_INTERVAL;
		be.tryEatSmallSlime(level, pos);
	}

	private void tryEatSmallSlime(Level level, BlockPos pos) {
		Slime slime = findSmallSlime(level, pos);
		if (slime == null)
			return;

		Vec3 dropPos = slime.position();
		slime.discard();
		level.addFreshEntity(new ItemEntity(level, dropPos.x, dropPos.y, dropPos.z, new ItemStack(Items.SLIME_BALL)));

		level.playSound(null, pos, SoundEvents.FROG_TONGUE, SoundSource.NEUTRAL, 1.0f,
			0.9f + level.random.nextFloat() * 0.2f);
		level.playSound(null, pos, SoundEvents.FROG_EAT, SoundSource.NEUTRAL, 1.0f,
			0.9f + level.random.nextFloat() * 0.2f);
	}

	private static Slime findSmallSlime(Level level, BlockPos pos) {
		AABB bounds = GiantFrogBlock.getBodyBounds(pos).inflate(TONGUE_REACH, 0.5d, TONGUE_REACH);
		Vec3 frogCenter = Vec3.atCenterOf(pos);
		List<Slime> slimes = level.getEntitiesOfClass(Slime.class, bounds, GiantFrogBlockEntity::canEat);
		return slimes.stream()
			.min(Comparator.comparingDouble(slime -> slime.distanceToSqr(frogCenter)))
			.orElse(null);
	}

	private static boolean canEat(Slime slime) {
		return slime.isAlive() && slime.getSize() == 1 && !BasinEntityProcessing.isCapturedSmallSlime(slime);
	}

	private static int levelRandomOffset(BlockPos pos) {
		return Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, EAT_INTERVAL);
	}
}
