package com.nobodiiiii.createbiotech.content.universaljoint;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Stable local identity shared by an intact universal-joint endpoint and the half shaft that can
 * replace it.
 *
 * <p>The block entity is the only persistent owner of this data. No world-level endpoint
 * directory reserves its address.</p>
 */
public abstract class UniversalJointEndpointBlockEntity extends KineticBlockEntity {

	private static final String TAG_ENDPOINT_ID = "EndpointId";
	private static final String TAG_MOVE_REVISION = "EndpointGeneration";
	private static final String TAG_EXPECTED_ADDRESS = "ExpectedOwnAddress";

	private UUID endpointId = UUID.randomUUID();
	private long moveRevision;
	@Nullable
	private EndpointAddress expectedOwnAddress;

	private boolean controlledReplacement;
	private boolean subLevelMoveSource;

	protected UniversalJointEndpointBlockEntity(BlockEntityType<?> type, BlockPos pos,
		BlockState state) {
		super(type, pos, state);
	}

	public final UUID getEndpointId() {
		return endpointId;
	}

	public final long getMoveRevision() {
		return moveRevision;
	}

	public final EndpointAddress getEndpointAddress() {
		if (level == null)
			throw new IllegalStateException("Endpoint is not attached to a level");
		return EndpointAddress.capture(level, worldPosition);
	}

	public final boolean isAtExpectedOwnAddress() {
		return expectedOwnAddress == null || level == null
			|| expectedOwnAddress.equals(getEndpointAddress());
	}

	public Vec3 getWorldCenter() {
		return level == null ? Vec3.atCenterOf(worldPosition)
			: SubLevelCompat.toWorld(level, worldPosition, Vec3.atCenterOf(worldPosition));
	}

	public final void setEndpointIdentity(UUID endpointId, long moveRevision) {
		Objects.requireNonNull(endpointId, "endpointId");
		if (moveRevision < 0)
			throw new IllegalArgumentException("moveRevision must not be negative");
		boolean changed = !endpointId.equals(this.endpointId)
			|| moveRevision != this.moveRevision;
		this.endpointId = endpointId;
		this.moveRevision = moveRevision;
		if (changed)
			synchronizeEndpoint();
	}

	public final void beginControlledReplacement() {
		controlledReplacement = true;
	}

	public final void endControlledReplacement() {
		controlledReplacement = false;
	}

	/**
	 * Captures the drop decision before vanilla removes the block entity.
	 */
	final boolean prepareEndpointRemoval() {
		return !controlledReplacement && !subLevelMoveSource;
	}

	/**
	 * Called by the optional Sable block adapter immediately before Sable serializes the source
	 * block entity.
	 */
	public final void createBiotech$beforeSubLevelMove(ServerLevel oldLevel,
		ServerLevel newLevel, BlockPos oldPos, BlockPos newPos) {
		EndpointAddress source = EndpointAddress.capture(oldLevel, oldPos);
		EndpointAddress destination = EndpointAddress.capture(newLevel, newPos);
		subLevelMoveSource = true;
		if (moveRevision < Long.MAX_VALUE)
			moveRevision++;
		expectedOwnAddress = destination;
		onEndpointAddressChanging(source, destination, moveRevision);
		synchronizeEndpoint();
	}

	/**
	 * Called by the optional Sable block adapter after the destination block entity has loaded the
	 * source NBT.
	 */
	public final void createBiotech$afterSubLevelMove(ServerLevel oldLevel,
		ServerLevel newLevel, BlockPos oldPos, BlockPos newPos) {
		EndpointAddress source = EndpointAddress.capture(oldLevel, oldPos);
		EndpointAddress destination = EndpointAddress.capture(newLevel, newPos);
		expectedOwnAddress = destination;
		subLevelMoveSource = false;
		onEndpointAddressChanged(source, destination, moveRevision);
		synchronizeEndpoint();
	}

	protected void onEndpointAddressChanging(EndpointAddress source,
		EndpointAddress destination, long newRevision) {}

	protected void onEndpointAddressChanged(EndpointAddress source,
		EndpointAddress destination, long newRevision) {}

	@Override
	public void tick() {
		super.tick();
		if (!(level instanceof ServerLevel serverLevel)
			|| isAtExpectedOwnAddress() || expectedOwnAddress == null)
			return;
		ServerLevel destinationLevel =
			serverLevel.getServer().getLevel(expectedOwnAddress.dimension());
		if (destinationLevel == null)
			return;
		BlockEntity rawDestination =
			resolveLoaded(destinationLevel, expectedOwnAddress);
		if (!(rawDestination instanceof UniversalJointEndpointBlockEntity destination)
			|| destination == this || !destination.getEndpointId().equals(endpointId)
			|| destination.getMoveRevision() < moveRevision)
			return;
		beginControlledReplacement();
		serverLevel.destroyBlock(worldPosition, false);
	}

	private void synchronizeEndpoint() {
		setChanged();
		if (level != null)
			sendData();
	}

	@Nullable
	public static BlockEntity resolveLoaded(Level level, EndpointAddress address) {
		if (!level.dimension().equals(address.dimension()))
			return null;
		return SubLevelCompat.resolveBlockEntityFast(level, address.rawPos(),
			address.subLevelId());
	}

	@Override
	protected void write(CompoundTag compound, HolderLookup.Provider registries,
		boolean clientPacket) {
		compound.putUUID(TAG_ENDPOINT_ID, endpointId);
		compound.putLong(TAG_MOVE_REVISION, moveRevision);
		if (expectedOwnAddress != null)
			compound.put(TAG_EXPECTED_ADDRESS, expectedOwnAddress.write());
		super.write(compound, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, HolderLookup.Provider registries,
		boolean clientPacket) {
		super.read(compound, registries, clientPacket);
		if (compound.hasUUID(TAG_ENDPOINT_ID))
			endpointId = compound.getUUID(TAG_ENDPOINT_ID);
		moveRevision = Math.max(0, compound.getLong(TAG_MOVE_REVISION));
		expectedOwnAddress = compound.contains(TAG_EXPECTED_ADDRESS, Tag.TAG_COMPOUND)
			? EndpointAddress.read(compound.getCompound(TAG_EXPECTED_ADDRESS)).orElse(null)
			: null;
	}

	public record EndpointAddress(ResourceKey<Level> dimension, @Nullable UUID subLevelId,
								  BlockPos rawPos) {

		private static final String TAG_DIMENSION = "Dimension";
		private static final String TAG_SUB_LEVEL = "SubLevel";
		private static final String TAG_POS = "Pos";

		public EndpointAddress {
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(rawPos, "rawPos");
			rawPos = rawPos.immutable();
		}

		public static EndpointAddress capture(Level level, BlockPos rawPos) {
			return new EndpointAddress(level.dimension(),
				SubLevelCompat.getSpaceId(level, rawPos), rawPos);
		}

		public CompoundTag write() {
			CompoundTag tag = new CompoundTag();
			tag.putString(TAG_DIMENSION, dimension.location().toString());
			if (subLevelId != null)
				tag.putUUID(TAG_SUB_LEVEL, subLevelId);
			tag.putLong(TAG_POS, rawPos.asLong());
			return tag;
		}

		public static Optional<EndpointAddress> read(CompoundTag tag) {
			if (!tag.contains(TAG_DIMENSION, Tag.TAG_STRING)
				|| !tag.contains(TAG_POS, Tag.TAG_LONG)
				|| tag.contains(TAG_SUB_LEVEL) && !tag.hasUUID(TAG_SUB_LEVEL))
				return Optional.empty();
			ResourceLocation dimensionId =
				ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
			if (dimensionId == null)
				return Optional.empty();
			ResourceKey<Level> dimension =
				ResourceKey.create(Registries.DIMENSION, dimensionId);
			UUID subLevelId =
				tag.hasUUID(TAG_SUB_LEVEL) ? tag.getUUID(TAG_SUB_LEVEL) : null;
			return Optional.of(new EndpointAddress(dimension, subLevelId,
				BlockPos.of(tag.getLong(TAG_POS))));
		}
	}
}
