package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioTopologyFrameTest {
	@Test
	void freezesTheCompleteSampleMapAtPublication() {
		RadioEndpoint.Key key = new RadioEndpoint.Key(id(1), id(2));
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> mutable = new LinkedHashMap<>();
		mutable.put(key, sample(4));

		RadioTopologyFrame frame = new RadioTopologyFrame(20, true, mutable);
		mutable.clear();

		assertEquals(sample(4), frame.sample(key));
		assertThrows(UnsupportedOperationException.class,
			() -> frame.samples().put(key, sample(5)));
	}

	@Test
	void copiesSamplePositionBeforePublication() {
		Vec3 supplied = new Vec3(12, 34, 56);
		RadioTopologyFrame.Sample sample = new RadioTopologyFrame.Sample(Level.OVERWORLD,
			new SpaceAddress(Level.OVERWORLD, id(3), BlockPos.ZERO), supplied, true, 7,
			Optional.of(id(3)));

		assertEquals(new Vec3(12, 34, 56), sample.worldCenter());
		assertEquals(Optional.of(id(3)), sample.physicalStructureId());
	}

	private static RadioTopologyFrame.Sample sample(long generation) {
		return new RadioTopologyFrame.Sample(Level.OVERWORLD,
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), new Vec3(0.5, 0.5, 0.5),
			true, generation, Optional.empty());
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
