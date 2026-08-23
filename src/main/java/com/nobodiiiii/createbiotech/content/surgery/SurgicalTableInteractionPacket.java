package com.nobodiiiii.createbiotech.content.surgery;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public record SurgicalTableInteractionPacket(BlockPos pos, InteractionHand hand, Action action,
	int targetId, int observedCubeCount, List<SurgicalAssembly.Seam> seams,
	double originOffsetX, double originOffsetZ, SurgicalTableLayout.Proposal layout) {

	public SurgicalTableInteractionPacket {
		seams = List.copyOf(seams);
		layout = layout == null ? SurgicalTableLayout.Proposal.EMPTY : layout;
	}

	public SurgicalTableInteractionPacket(FriendlyByteBuf buffer) {
		this(buffer.readBlockPos(), buffer.readEnum(InteractionHand.class), buffer.readEnum(Action.class),
			buffer.readVarInt(), buffer.readVarInt(), readSeams(buffer), buffer.readDouble(), buffer.readDouble(),
			readLayout(buffer));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeEnum(hand);
		buffer.writeEnum(action);
		buffer.writeVarInt(targetId);
		buffer.writeVarInt(observedCubeCount);
		buffer.writeVarInt(seams.size());
		for (SurgicalAssembly.Seam seam : seams) {
			buffer.writeVarInt(seam.first());
			buffer.writeVarInt(seam.second());
		}
		buffer.writeDouble(originOffsetX);
		buffer.writeDouble(originOffsetZ);
		buffer.writeVarInt(layout.offsets().size());
		for (SurgicalTableLayout.CubeOffset offset : layout.offsets()) {
			buffer.writeVarInt(offset.cubeId());
			buffer.writeDouble(offset.x());
			buffer.writeDouble(offset.z());
		}
		buffer.writeVarInt(layout.footprints().size());
		for (SurgicalTableLayout.Footprint footprint : layout.footprints()) {
			buffer.writeVarInt(footprint.componentRoot());
			buffer.writeDouble(footprint.minX());
			buffer.writeDouble(footprint.minZ());
			buffer.writeDouble(footprint.maxX());
			buffer.writeDouble(footprint.maxZ());
			buffer.writeInt(footprint.gridX());
			buffer.writeInt(footprint.gridZ());
		}
	}

	public void handle(ServerPlayer player) {
		if (player == null || player.isSpectator() || !player.mayBuild() || !player.level().isLoaded(pos))
			return;

		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0d;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(player.level(), pos);
		boolean placement = action == Action.PLACE;
		if (!plane.valid() || (placement ? plane.owner() != null : !pos.equals(plane.owner()))
			|| plane.tiles().stream()
			.noneMatch(tile -> player.distanceToSqr(Vec3.atCenterOf(tile)) <= range * range))
			return;
		if (!(player.level().getBlockEntity(pos) instanceof SurgicalTableBlockEntity table))
			return;

		ItemStack held = player.getItemInHand(hand);
		if (placement) {
			if (held.getItem() instanceof com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem
				&& com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper.hasCapturedEntity(held)
				&& !table.tryPlaceSubject(held, plane, originOffsetX, originOffsetZ, layout))
				noSpace(player);
			return;
		}
		if (targetId < 0 || !SurgicalAssembly.validTopology(observedCubeCount, seams)
			|| !table.hasSubject() || !table.matchesObservedTopology(observedCubeCount, seams))
			return;
		switch (action) {
		case CUT -> {
			if (targetId < seams.size() && held.is(Items.SHEARS)) {
				if (!table.cutSeam(player, held, hand, targetId, observedCubeCount, seams, plane, layout))
					noSpace(player);
			}
		}
		case PACK -> {
			if (targetId < observedCubeCount
				&& com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem.isBox(held)
				&& !com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem.hasCapturedEntity(held))
				table.packComponent(player, held, targetId, observedCubeCount, seams);
		}
		case CUT_CUBE_CONNECTIONS -> {
			if (targetId < observedCubeCount && held.is(Items.SHEARS)) {
				if (!table.cutCubeConnections(player, held, hand, targetId, observedCubeCount, seams, plane, layout))
					noSpace(player);
			}
		}
		case PLACE -> {}
		}
	}

	private static void noSpace(ServerPlayer player) {
		player.displayClientMessage(Component.translatable("message.create_biotech.surgical_table.no_space"), true);
	}

	private static List<SurgicalAssembly.Seam> readSeams(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		if (size < 0 || size > SurgicalAssembly.MAX_SEAMS)
			throw new IllegalArgumentException("Invalid surgical seam count " + size);
		List<SurgicalAssembly.Seam> seams = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
			seams.add(SurgicalAssembly.Seam.of(buffer.readVarInt(), buffer.readVarInt()));
		return seams;
	}

	private static SurgicalTableLayout.Proposal readLayout(FriendlyByteBuf buffer) {
		int offsetCount = buffer.readVarInt();
		if (offsetCount < 0 || offsetCount > SurgicalAssembly.MAX_CUBES)
			throw new IllegalArgumentException("Invalid surgical offset count " + offsetCount);
		List<SurgicalTableLayout.CubeOffset> offsets = new ArrayList<>(offsetCount);
		for (int index = 0; index < offsetCount; index++)
			offsets.add(new SurgicalTableLayout.CubeOffset(buffer.readVarInt(), buffer.readDouble(),
				buffer.readDouble()));
		int footprintCount = buffer.readVarInt();
		if (footprintCount < 0 || footprintCount > SurgicalAssembly.MAX_CUBES)
			throw new IllegalArgumentException("Invalid surgical footprint count " + footprintCount);
		List<SurgicalTableLayout.Footprint> footprints = new ArrayList<>(footprintCount);
		for (int index = 0; index < footprintCount; index++)
			footprints.add(new SurgicalTableLayout.Footprint(buffer.readVarInt(), buffer.readDouble(),
				buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readInt(), buffer.readInt()));
		return new SurgicalTableLayout.Proposal(offsets, footprints);
	}

	public enum Action {
		PLACE,
		CUT,
		PACK,
		CUT_CUBE_CONNECTIONS
	}
}
