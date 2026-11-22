package com.nobodiiiii.createbiotech.content.cardboardbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class CapturedEntityBoxHelperTest {
	private static final UUID CAPTURED_UUID =
		UUID.fromString("34567890-1234-5678-9abc-def012345678");

	@Test
	void preservingPlanKeepsUuidAndCapturedHealthWhenThereIsNoCollision() {
		CompoundTag stored = capturedEntity(CAPTURED_UUID);

		CompoundTag prepared = CapturedEntityBoxHelper.prepareEntityDataForLoad(
			stored, uuid -> false, false);

		assertNotNull(prepared);
		assertEquals(CAPTURED_UUID, prepared.getUUID("UUID"));
		assertEquals(CAPTURED_UUID, stored.getUUID("UUID"));
		assertEquals(7.25F, CapturedEntityBoxHelper.clampCapturedHealth(7.25F, 20.0F));
	}

	@Test
	void preservingPlanRefusesCollisionWithoutMutatingStoredNbt() {
		CompoundTag stored = capturedEntity(CAPTURED_UUID);
		CompoundTag before = stored.copy();

		CompoundTag prepared = CapturedEntityBoxHelper.prepareEntityDataForLoad(
			stored, CAPTURED_UUID::equals, false);

		assertNull(prepared);
		assertEquals(before, stored);
	}

	@Test
	void legacyPlanReseedsCollisionAndRestoresExactClampedHealth() {
		CompoundTag stored = capturedEntity(CAPTURED_UUID);

		CompoundTag prepared = CapturedEntityBoxHelper.prepareEntityDataForLoad(
			stored, CAPTURED_UUID::equals, true);

		assertNotNull(prepared);
		assertNotEquals(CAPTURED_UUID, prepared.getUUID("UUID"));
		assertEquals(CAPTURED_UUID, stored.getUUID("UUID"));
		assertEquals(5.75F, CapturedEntityBoxHelper.clampCapturedHealth(5.75F, 20.0F));
		assertEquals(20.0F, CapturedEntityBoxHelper.clampCapturedHealth(27.0F, 20.0F));
	}

	@Test
	void passengerCollisionReseedsTheWholeEntityTree() {
		UUID passengerUuid = UUID.fromString("45678901-2345-6789-abcd-ef0123456789");
		CompoundTag stored = capturedEntity(CAPTURED_UUID);
		CompoundTag passenger = capturedEntity(passengerUuid);
		ListTag passengers = new ListTag();
		passengers.add(passenger);
		stored.put("Passengers", passengers);

		CompoundTag prepared = CapturedEntityBoxHelper.prepareEntityDataForLoad(
			stored, passengerUuid::equals, true);

		assertNotNull(prepared);
		assertNotEquals(CAPTURED_UUID, prepared.getUUID("UUID"));
		assertNotEquals(passengerUuid,
			prepared.getList("Passengers", CompoundTag.TAG_COMPOUND).getCompound(0).getUUID("UUID"));
		assertEquals(passengerUuid,
			stored.getList("Passengers", CompoundTag.TAG_COMPOUND).getCompound(0).getUUID("UUID"));
	}

	private static CompoundTag capturedEntity(UUID uuid) {
		CompoundTag entity = new CompoundTag();
		entity.putString("id", "minecraft:villager");
		entity.putUUID("UUID", uuid);
		return entity;
	}
}
