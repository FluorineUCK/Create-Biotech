package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.List;
import java.util.Set;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Ticking block entity for the Giant Frog Portal (巨型青蛙传送门). When a living entity stands on the
 * block it is teleported into this portal's private room in the Frog Stomach dimension. On the first
 * teleport a room is allocated and built; the resulting space index is stored here (and travels with
 * the dropped item via a data component) so the binding is permanent.
 */
public class GiantFrogPortalBlockEntity extends BlockEntity {

	private static final int TELEPORT_COOLDOWN_TICKS = 60;

	private boolean hasSpace = false;
	private long spaceIndex = -1L;

	public GiantFrogPortalBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.GIANT_FROG_PORTAL.get(), pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, GiantFrogPortalBlockEntity be) {
		be.tick();
	}

	private void tick() {
		if (!(level instanceof ServerLevel serverLevel))
			return;
		MinecraftServer server = serverLevel.getServer();
		ServerLevel frogLevel = server.getLevel(FrogStomachDimensions.FROG_STOMACH);
		if (frogLevel == null)
			return;

		AABB area = triggerArea();
		List<LivingEntity> entities = serverLevel.getEntitiesOfClass(LivingEntity.class, area,
			e -> e.isAlive() && !e.isSpectator());
		if (entities.isEmpty())
			return;

		for (LivingEntity entity : entities) {
			if (FrogTeleportCooldowns.isActive(serverLevel, entity.getUUID()))
				continue;
			ensureRoom(server, frogLevel);
			teleportIn(serverLevel, frogLevel, entity);
		}
	}

	private void ensureRoom(MinecraftServer server, ServerLevel frogLevel) {
		if (!hasSpace) {
			spaceIndex = FrogStomachSavedData.get(server).allocateSpace();
			hasSpace = true;
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
			setChanged();
		} else if (!FrogStomachSpace.isBuilt(frogLevel, spaceIndex)) {
			FrogStomachSpace.buildRoom(frogLevel, spaceIndex);
		}
	}

	private void teleportIn(ServerLevel from, ServerLevel frogLevel, LivingEntity entity) {
		FrogStomachSavedData.get(from.getServer()).setReturn(entity.getUUID(), from.dimension(), worldPosition);

		BlockPos spawn = FrogStomachSpace.spawnPos(spaceIndex);
		frogLevel.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, new ChunkPos(spawn), 1, entity.getId());

		entity.resetFallDistance();
		boolean teleported = entity.teleportTo(frogLevel, spawn.getX() + 0.5d, spawn.getY(), spawn.getZ() + 0.5d,
			Set.<RelativeMovement>of(), entity.getYRot(), entity.getXRot());
		if (!teleported)
			return;

		FrogTeleportCooldowns.mark(from, entity.getUUID(), TELEPORT_COOLDOWN_TICKS);
		playTeleportEffects(from, worldPosition);
		playTeleportEffects(frogLevel, spawn);
	}

	static void playTeleportEffects(ServerLevel level, BlockPos pos) {
		double x = pos.getX() + 0.5d;
		double y = pos.getY() + 0.5d;
		double z = pos.getZ() + 0.5d;
		level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 0.6f);
		level.sendParticles(ParticleTypes.PORTAL, x, y, z, 32, 0.45d, 0.6d, 0.45d, 0.2d);
	}

	private AABB triggerArea() {
		return new AABB(worldPosition.getX(), worldPosition.getY() + 1, worldPosition.getZ(),
			worldPosition.getX() + 1, worldPosition.getY() + 2, worldPosition.getZ() + 1);
	}

	public boolean hasSpace() {
		return hasSpace;
	}

	public long getSpaceIndex() {
		return spaceIndex;
	}

	/** Re-bind this portal to an existing room (used when placing a block that carries a space id). */
	public void setSpaceIndex(long index) {
		this.spaceIndex = index;
		this.hasSpace = true;
		setChanged();
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
