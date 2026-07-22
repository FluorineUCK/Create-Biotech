package com.nobodiiiii.createbiotech.content.giantfrog;

import java.util.Comparator;
import java.util.List;

import com.nobodiiiii.createbiotech.content.frogportal.FrogPortalBehaviour;
import com.nobodiiiii.createbiotech.content.frogportal.FrogStomachDimensions;
import com.nobodiiiii.createbiotech.content.frogportal.FrogStomachSavedData;
import com.nobodiiiii.createbiotech.content.frogportal.FrogStomachSpace;
import com.nobodiiiii.createbiotech.content.processing.basin.BasinEntityProcessing;
import com.nobodiiiii.createbiotech.content.slimearmor.SlimeArmorHandler;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GiantFrogBlockEntity extends BlockEntity {
	private static final int EAT_INTERVAL = 20;
	private static final int EAT_ANIMATION_TICKS = 20;
	private static final double TONGUE_REACH = 1.25d;

	private int eatCooldown;
	private int eatAnimationTicks;
	private int eatAnimationAge;
	private boolean hasSpace;
	private long spaceIndex = -1L;

	public GiantFrogBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.GIANT_FROG.get(), pos, state);
		eatCooldown = levelRandomOffset(pos);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, GiantFrogBlockEntity be) {
		if (level.isClientSide) {
			be.tickClientAnimation();
			return;
		}

		if (be.eatCooldown > 0) {
			be.eatCooldown--;
			return;
		}

		be.eatCooldown = EAT_INTERVAL;
		if (be.tryEatPlayer(level, pos))
			return;
		be.tryEatSmallSlime(level, pos);
	}

	private boolean tryEatPlayer(Level level, BlockPos pos) {
		Player player = findSlimeDisguisedPlayer(level, pos);
		if (player == null)
			return false;
		if (!(level instanceof ServerLevel serverLevel))
			return false;
		ServerLevel frogLevel = level.getServer().getLevel(FrogStomachDimensions.FROG_STOMACH);
		if (frogLevel == null)
			return false;

		long index = ensureRoom(level.getServer(), frogLevel);
		FrogStomachSavedData.get(level.getServer())
			.setReturn(player.getUUID(), index, level.dimension(), player.position());

		playEatEffects(serverLevel, pos);
		Entity changed = player.changeDimension(FrogPortalBehaviour.transitionTo(frogLevel, player,
			Vec3.atBottomCenterOf(FrogStomachSpace.spawnPos(index))));
		if (changed != null)
			changed.setPortalCooldown();
		return true;
	}

	private void tryEatSmallSlime(Level level, BlockPos pos) {
		Slime slime = findSmallSlime(level, pos);
		if (slime == null)
			return;

		Vec3 dropPos = slime.position();
		slime.discard();
		level.addFreshEntity(new ItemEntity(level, dropPos.x, dropPos.y, dropPos.z, new ItemStack(Items.SLIME_BALL)));

		playEatEffects(level, pos);
	}

	private void playEatEffects(Level level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.FROG_TONGUE, SoundSource.NEUTRAL, 1.0f,
			0.9f + level.random.nextFloat() * 0.2f);
		level.playSound(null, pos, SoundEvents.FROG_EAT, SoundSource.NEUTRAL, 1.0f,
			0.9f + level.random.nextFloat() * 0.2f);
		if (level instanceof ServerLevel serverLevel)
			CBPackets.sendToTrackingChunk(new GiantFrogEatPacket(pos), serverLevel, pos);
	}

	public void startEatAnimation() {
		eatAnimationTicks = EAT_ANIMATION_TICKS;
		eatAnimationAge = 0;
	}

	public boolean isEating() {
		return eatAnimationTicks > 0;
	}

	public float getEatAnimationAge(float partialTicks) {
		return isEating() ? eatAnimationAge + partialTicks : 0.0f;
	}

	private void tickClientAnimation() {
		if (eatAnimationTicks <= 0)
			return;
		eatAnimationTicks--;
		eatAnimationAge++;
	}

	public long ensureRoom(MinecraftServer server, ServerLevel frogLevel) {
		if (!hasSpace) {
			spaceIndex = FrogStomachSavedData.get(server).allocateSpace();
			hasSpace = true;
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
			setChanged();
		} else if (!FrogStomachSpace.isBuilt(frogLevel, spaceIndex)) {
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
		}
		return spaceIndex;
	}

	public boolean hasSpace() {
		return hasSpace;
	}

	public long getSpaceIndex() {
		return spaceIndex;
	}

	public void setSpaceIndex(long index) {
		spaceIndex = index;
		hasSpace = true;
		setChanged();
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

	private static Player findSlimeDisguisedPlayer(Level level, BlockPos pos) {
		AABB bounds = GiantFrogBlock.getBodyBounds(pos).inflate(TONGUE_REACH, 0.5d, TONGUE_REACH);
		Vec3 frogCenter = Vec3.atCenterOf(pos);
		List<Player> players = level.getEntitiesOfClass(Player.class, bounds, GiantFrogBlockEntity::canEatPlayer);
		return players.stream()
			.min(Comparator.comparingDouble(player -> player.distanceToSqr(frogCenter)))
			.orElse(null);
	}

	private static boolean canEatPlayer(Player player) {
		return player.isAlive() && !player.isSpectator() && player.canUsePortal(false)
			&& SlimeArmorHandler.testForSlimeStealth(player);
	}

	private static int levelRandomOffset(BlockPos pos) {
		return Math.floorMod(pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, EAT_INTERVAL);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean("HasSpace", hasSpace);
		tag.putLong("SpaceIndex", spaceIndex);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		hasSpace = tag.getBoolean("HasSpace");
		spaceIndex = tag.getLong("SpaceIndex");
	}
}
