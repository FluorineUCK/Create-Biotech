package com.nobodiiiii.createbiotech.network;

import com.nobodiiiii.createbiotech.client.render.ContainedEntityHandoffManager;

import net.createmod.catnip.annotations.ClientOnly;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Clientbound ordering bridge between a contained render proxy and a spawned entity. */
public final class ContainedEntityHandoffPacket {
	private static final int DEFAULT_WAIT_TICKS = 10;

	private final boolean cancelled;
	private final int entityId;
	private final ResourceLocation entityType;
	private final BlockPos sourcePos;
	private final BlockPos slotPos;
	private final Vec3 position;
	private final float yaw;
	private final float pitch;
	private final long renderSeed;
	private final float animationPhase;
	private final int maximumWaitTicks;
	private final boolean charged;

	public ContainedEntityHandoffPacket(Entity entity, BlockPos sourcePos, BlockPos slotPos,
		long renderSeed, float animationPhase) {
		this(false, entity.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), sourcePos, slotPos,
			entity.position(), entity.getYRot(), entity.getXRot(), renderSeed, animationPhase,
			DEFAULT_WAIT_TICKS, entity instanceof Creeper creeper && creeper.isPowered());
	}

	private ContainedEntityHandoffPacket(boolean cancelled, int entityId, ResourceLocation entityType,
		BlockPos sourcePos, BlockPos slotPos, Vec3 position, float yaw, float pitch, long renderSeed,
		float animationPhase, int maximumWaitTicks, boolean charged) {
		this.cancelled = cancelled;
		this.entityId = entityId;
		this.entityType = entityType;
		this.sourcePos = sourcePos.immutable();
		this.slotPos = slotPos.immutable();
		this.position = position;
		this.yaw = yaw;
		this.pitch = pitch;
		this.renderSeed = renderSeed;
		this.animationPhase = animationPhase;
		this.maximumWaitTicks = maximumWaitTicks;
		this.charged = charged;
	}

	public ContainedEntityHandoffPacket(RegistryFriendlyByteBuf buffer) {
		this(buffer.readBoolean(), buffer.readVarInt(), buffer.readResourceLocation(), buffer.readBlockPos(),
			buffer.readBlockPos(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
			buffer.readFloat(), buffer.readFloat(), buffer.readLong(), buffer.readFloat(), buffer.readVarInt(),
			buffer.readBoolean());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeBoolean(cancelled);
		buffer.writeVarInt(entityId);
		buffer.writeResourceLocation(entityType);
		buffer.writeBlockPos(sourcePos);
		buffer.writeBlockPos(slotPos);
		buffer.writeDouble(position.x);
		buffer.writeDouble(position.y);
		buffer.writeDouble(position.z);
		buffer.writeFloat(yaw);
		buffer.writeFloat(pitch);
		buffer.writeLong(renderSeed);
		buffer.writeFloat(animationPhase);
		buffer.writeVarInt(maximumWaitTicks);
		buffer.writeBoolean(charged);
	}

	@ClientOnly
	@OnlyIn(Dist.CLIENT)
	public void handle(LocalPlayer player) {
		ContainedEntityHandoffManager.handle(this, player);
	}

	public static ContainedEntityHandoffPacket cancelled(Entity entity, BlockPos sourcePos) {
		ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return new ContainedEntityHandoffPacket(true, entity.getId(), type, sourcePos, sourcePos,
			entity.position(), entity.getYRot(), entity.getXRot(), 0, 0, 0, false);
	}

	public static void announce(ServerLevel level, Entity entity, BlockPos sourcePos, BlockPos slotPos,
		long renderSeed, float animationPhase) {
		CBPackets.sendToTrackingChunk(new ContainedEntityHandoffPacket(entity, sourcePos, slotPos,
			renderSeed, animationPhase), level,
			BlockPos.containing(entity.position()));
	}

	public static void cancel(ServerLevel level, Entity entity, BlockPos sourcePos) {
		CBPackets.sendToTrackingChunk(cancelled(entity, sourcePos), level, BlockPos.containing(entity.position()));
	}

	public boolean cancelled() { return cancelled; }
	public int entityId() { return entityId; }
	public ResourceLocation entityType() { return entityType; }
	public BlockPos sourcePos() { return sourcePos; }
	public BlockPos slotPos() { return slotPos; }
	public Vec3 position() { return position; }
	public float yaw() { return yaw; }
	public float pitch() { return pitch; }
	public long renderSeed() { return renderSeed; }
	public float animationPhase() { return animationPhase; }
	public int maximumWaitTicks() { return maximumWaitTicks; }
	public boolean charged() { return charged; }
}
