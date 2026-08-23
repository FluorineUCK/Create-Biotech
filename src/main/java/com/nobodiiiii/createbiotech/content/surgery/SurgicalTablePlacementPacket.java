package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayList;
import java.util.List;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Server-validated placement data for both ordinary and laid-out multi-source bodies. */
public record SurgicalTablePlacementPacket(BlockPos pos, InteractionHand hand,
	 double originOffsetX, double originOffsetZ, SurgicalTableLayout.Proposal envelope,
	 List<SurgicalTableLayout.Proposal> sourceLayouts) {

	public SurgicalTablePlacementPacket {
		envelope = envelope == null ? SurgicalTableLayout.Proposal.EMPTY : envelope;
		sourceLayouts = sourceLayouts == null ? List.of() : List.copyOf(sourceLayouts);
		if (sourceLayouts.size() > SurgicalAssembly.MAX_SOURCES)
			throw new IllegalArgumentException("Too many surgical placement sources " + sourceLayouts.size());
	}

	public SurgicalTablePlacementPacket(FriendlyByteBuf buffer) {
		this(buffer.readBlockPos(), buffer.readEnum(InteractionHand.class), buffer.readDouble(),
			buffer.readDouble(), readLayout(buffer), readSourceLayouts(buffer));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
		buffer.writeEnum(hand);
		buffer.writeDouble(originOffsetX);
		buffer.writeDouble(originOffsetZ);
		writeLayout(buffer, envelope);
		buffer.writeVarInt(sourceLayouts.size());
		for (SurgicalTableLayout.Proposal sourceLayout : sourceLayouts)
			writeLayout(buffer, sourceLayout);
	}

	public void handle(ServerPlayer player) {
		if (player == null || player.isSpectator() || !player.mayBuild() || !player.level().isLoaded(pos))
			return;
		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0d;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(player.level(), pos);
		if (!plane.valid() || !pos.equals(plane.source()) || plane.tiles().stream()
			.noneMatch(tile -> player.distanceToSqr(Vec3.atCenterOf(tile)) <= range * range))
			return;
		SurgicalTableBlockEntity table = SurgicalTableBlockEntity.controller(player.level(), plane);
		ItemStack held = player.getItemInHand(hand);
		if (table == null || !(held.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(held))
			return;
		if (!table.tryPlaceSubject(held, plane, player.getDirection(), originOffsetX, originOffsetZ,
			envelope, sourceLayouts))
			player.displayClientMessage(Component.translatable(
				"message.create_biotech.surgical_table.no_space"), true);
	}

	private static List<SurgicalTableLayout.Proposal> readSourceLayouts(FriendlyByteBuf buffer) {
		int sourceCount = buffer.readVarInt();
		if (sourceCount < 0 || sourceCount > SurgicalAssembly.MAX_SOURCES)
			throw new IllegalArgumentException("Invalid surgical placement source count " + sourceCount);
		List<SurgicalTableLayout.Proposal> layouts = new ArrayList<>(sourceCount);
		int totalOffsets = 0;
		int totalFootprints = 0;
		for (int source = 0; source < sourceCount; source++) {
			SurgicalTableLayout.Proposal layout = readLayout(buffer);
			totalOffsets += layout.offsets().size();
			totalFootprints += layout.footprints().size();
			if (totalOffsets > SurgicalAssembly.MAX_CUBES
				|| totalFootprints > SurgicalAssembly.MAX_CUBES)
				throw new IllegalArgumentException("Oversized composite surgical placement layout");
			layouts.add(layout);
		}
		return List.copyOf(layouts);
	}

	private static void writeLayout(FriendlyByteBuf buffer, SurgicalTableLayout.Proposal layout) {
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

	private static SurgicalTableLayout.Proposal readLayout(FriendlyByteBuf buffer) {
		int offsetCount = buffer.readVarInt();
		if (offsetCount < 0 || offsetCount > SurgicalAssembly.MAX_CUBES)
			throw new IllegalArgumentException("Invalid surgical placement offset count " + offsetCount);
		List<SurgicalTableLayout.CubeOffset> offsets = new ArrayList<>(offsetCount);
		for (int index = 0; index < offsetCount; index++)
			offsets.add(new SurgicalTableLayout.CubeOffset(buffer.readVarInt(), buffer.readDouble(),
				buffer.readDouble()));
		int footprintCount = buffer.readVarInt();
		if (footprintCount < 0 || footprintCount > SurgicalAssembly.MAX_CUBES)
			throw new IllegalArgumentException("Invalid surgical placement footprint count " + footprintCount);
		List<SurgicalTableLayout.Footprint> footprints = new ArrayList<>(footprintCount);
		for (int index = 0; index < footprintCount; index++)
			footprints.add(new SurgicalTableLayout.Footprint(buffer.readVarInt(), buffer.readDouble(),
				buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readInt(), buffer.readInt()));
		return new SurgicalTableLayout.Proposal(offsets, footprints);
	}
}
