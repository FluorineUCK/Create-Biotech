package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

class SpaceAddressTest {
	private static final UUID SUB_LEVEL =
		UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void outerAndSublevelAddressesRoundTripWithoutLosingIdentity() {
		SpaceAddress outer = new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(1, 2, 3));
		SpaceAddress sublevel = new SpaceAddress(Level.OVERWORLD, SUB_LEVEL,
			new BlockPos(-7, 80, 12));

		assertEquals(outer, SpaceAddress.tryLoad(outer.save()).orElseThrow());
		assertEquals(sublevel, SpaceAddress.tryLoad(sublevel.save()).orElseThrow());
	}

	@Test
	void malformedAddressFieldsAreRejectedInsteadOfDefaultingToOriginOrOuterSpace() {
		List<Consumer<CompoundTag>> corruptions = List.of(
			tag -> tag.remove("Dimension"),
			tag -> tag.putLong("Dimension", 0),
			tag -> tag.putString("Dimension", ""),
			tag -> tag.putString("Dimension", "bad dimension"),
			tag -> tag.remove("Pos"),
			tag -> tag.putString("Pos", "0"),
			tag -> tag.putString("SubLevel", SUB_LEVEL.toString()));

		for (Consumer<CompoundTag> corruption : corruptions) {
			CompoundTag tag = new SpaceAddress(Level.OVERWORLD, SUB_LEVEL,
				BlockPos.ZERO).save();
			corruption.accept(tag);
			assertTrue(SpaceAddress.tryLoad(tag).isEmpty());
		}
	}

	@Test
	void expectedRootDimensionMismatchIsRejected() {
		CompoundTag tag = new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO).save();
		assertTrue(SpaceAddress.tryLoad(tag, Level.NETHER).isEmpty());
		assertEquals(Level.OVERWORLD,
			SpaceAddress.tryLoad(tag, Level.OVERWORLD).orElseThrow().dimension());
	}
}
