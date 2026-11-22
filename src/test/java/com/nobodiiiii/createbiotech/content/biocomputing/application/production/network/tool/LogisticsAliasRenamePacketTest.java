package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.world.InteractionHand;

class LogisticsAliasRenamePacketTest {
	private static final UUID NETWORK =
		UUID.fromString("50000000-0000-0000-0000-000000000001");
	private static final String FINGERPRINT = "a".repeat(64);

	@Test
	void exactContainerHandSlotFingerprintNetworkAndRevisionAuthorize() {
		LogisticsAliasRenamePacket.SessionFence fence = fence();
		assertTrue(LogisticsAliasRenamePacket.authorizedSession(fence, fence));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(8, InteractionHand.MAIN_HAND,
				2, FINGERPRINT, NETWORK, 4)));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.OFF_HAND,
				40, FINGERPRINT, NETWORK, 4)));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				3, FINGERPRINT, NETWORK, 4)));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				2, "b".repeat(64), NETWORK, 4)));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				2, FINGERPRINT, UUID.randomUUID(), 4)));
		assertFalse(LogisticsAliasRenamePacket.authorizedSession(fence,
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				2, FINGERPRINT, NETWORK, 5)));
	}

	@Test
	void constructorBoundsAliasAndFingerprintBeforeHandling() {
		assertThrows(IllegalArgumentException.class, () ->
			new LogisticsAliasRenamePacket(fence(), Optional.of("x".repeat(65))));
		assertThrows(IllegalArgumentException.class, () ->
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				2, "short", NETWORK, 4));
		assertThrows(IllegalArgumentException.class, () ->
			new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
				41, FINGERPRINT, NETWORK, 4));
	}

	@Test
	void administrationAndCasMustBothPassAfterSessionFence() {
		assertFalse(LogisticsAliasRenamePacket.authorizedMutation(true, false,
			LogisticsNetworkAliasService.RenameResult.APPLIED));
		assertFalse(LogisticsAliasRenamePacket.authorizedMutation(true, true,
			LogisticsNetworkAliasService.RenameResult.FORBIDDEN));
		assertFalse(LogisticsAliasRenamePacket.authorizedMutation(true, true,
			LogisticsNetworkAliasService.RenameResult.STALE));
		assertTrue(LogisticsAliasRenamePacket.authorizedMutation(true, true,
			LogisticsNetworkAliasService.RenameResult.APPLIED));
	}

	private static LogisticsAliasRenamePacket.SessionFence fence() {
		return new LogisticsAliasRenamePacket.SessionFence(7, InteractionHand.MAIN_HAND,
			2, FINGERPRINT, NETWORK, 4);
	}
}
