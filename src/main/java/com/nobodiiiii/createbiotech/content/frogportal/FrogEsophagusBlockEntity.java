package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.List;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * Ticking block entity for the {@link FrogEsophagusBlock}. Sends players standing on it back to the
 * location recorded when they entered the Frog Stomach (falling back to the overworld spawn).
 */
public class FrogEsophagusBlockEntity extends BlockEntity {

	private static final int TELEPORT_COOLDOWN_TICKS = 60;

	public FrogEsophagusBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.FROG_ESOPHAGUS.get(), pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, FrogEsophagusBlockEntity be) {
		be.tick();
	}

	private void tick() {
		if (!(level instanceof ServerLevel serverLevel))
			return;

		AABB area = triggerArea();
		List<Player> players = serverLevel.getEntitiesOfClass(Player.class, area,
			p -> p.isAlive() && !p.isSpectator());
		for (Player player : players) {
			if (FrogTeleportCooldowns.isActive(serverLevel, player.getUUID()))
				continue;
			returnPlayer(serverLevel, player);
		}
	}

	private void returnPlayer(ServerLevel from, Player player) {
		MinecraftServer server = from.getServer();
		FrogStomachSavedData data = FrogStomachSavedData.get(server);
		FrogStomachSavedData.Location loc = data.getReturn(player.getUUID());

		ServerLevel dest = null;
		double x, y, z;
		if (loc != null)
			dest = server.getLevel(loc.dimension());
		if (dest != null) {
			BlockPos pos = loc.pos();
			x = pos.getX() + 0.5d;
			y = pos.getY() + 1;
			z = pos.getZ() + 0.5d;
		} else {
			dest = server.overworld();
			BlockPos spawn = dest.getSharedSpawnPos();
			x = spawn.getX() + 0.5d;
			y = spawn.getY();
			z = spawn.getZ() + 0.5d;
		}

		dest.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, new ChunkPos(BlockPos.containing(x, y, z)), 1,
			player.getId());
		player.resetFallDistance();
		boolean teleported = player.teleportTo(dest, x, y, z, Set.<RelativeMovement>of(), player.getYRot(),
			player.getXRot());
		if (!teleported)
			return;

		FrogTeleportCooldowns.mark(from, player.getUUID(), TELEPORT_COOLDOWN_TICKS);
		GiantFrogPortalBlockEntity.playTeleportEffects(from, worldPosition);
		GiantFrogPortalBlockEntity.playTeleportEffects(dest, BlockPos.containing(x, y, z));
	}

	private AABB triggerArea() {
		return new AABB(worldPosition.getX(), worldPosition.getY() + 1, worldPosition.getZ(),
			worldPosition.getX() + 1, worldPosition.getY() + 2, worldPosition.getZ() + 1);
	}
}
