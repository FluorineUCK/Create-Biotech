package com.nobodiiiii.createbiotech.content.surgery;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public record SurgicalTableInteractionPacket(BlockPos pos, InteractionHand hand, Action action,
	int targetId, int observedCubeCount, List<SurgicalAssembly.Seam> seams) {

	public SurgicalTableInteractionPacket {
		seams = List.copyOf(seams);
	}

	public SurgicalTableInteractionPacket(FriendlyByteBuf buffer) {
		this(buffer.readBlockPos(), buffer.readEnum(InteractionHand.class), buffer.readEnum(Action.class),
			buffer.readVarInt(), buffer.readVarInt(), readSeams(buffer));
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
	}

	public void handle(ServerPlayer player) {
		if (player == null || player.isSpectator() || !player.mayBuild()
			|| targetId < 0 || !SurgicalAssembly.validTopology(observedCubeCount, seams)
			|| !player.level().isLoaded(pos))
			return;

		double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0d;
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) > range * range)
			return;
		if (!(player.level().getBlockEntity(pos) instanceof SurgicalTableBlockEntity table)
			|| !table.hasSubject() || !table.matchesObservedTopology(observedCubeCount, seams))
			return;

		ItemStack held = player.getItemInHand(hand);
		switch (action) {
		case CUT -> {
			if (targetId < seams.size() && held.is(Items.SHEARS))
				table.cutSeam(player, held, hand, targetId, observedCubeCount, seams);
		}
		case PACK -> {
			if (targetId < observedCubeCount
				&& com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem.isBox(held)
				&& !com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem.hasCapturedEntity(held))
				table.packComponent(player, held, targetId, observedCubeCount, seams);
		}
		}
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

	public enum Action {
		CUT,
		PACK
	}
}
