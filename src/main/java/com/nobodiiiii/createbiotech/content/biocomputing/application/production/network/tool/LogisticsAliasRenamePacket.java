package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Exact held-tool/menu-session CAS command for the global logistics alias. */
public record LogisticsAliasRenamePacket(SessionFence sessionFence,
	Optional<String> customAlias) {
	public static final int VERSION = 1;
	public static final int MAX_ALIAS_LENGTH = 64;

	public LogisticsAliasRenamePacket {
		sessionFence = Objects.requireNonNull(sessionFence, "session fence");
		customAlias = Objects.requireNonNull(customAlias, "custom alias")
			.map(String::strip).filter(alias -> !alias.isEmpty());
		if (customAlias.filter(alias -> alias.length() > MAX_ALIAS_LENGTH).isPresent())
			throw new IllegalArgumentException("alias exceeds 64 characters");
	}

	public LogisticsAliasRenamePacket(RegistryFriendlyByteBuf buffer) { this(read(buffer)); }
	private LogisticsAliasRenamePacket(LogisticsAliasRenamePacket decoded) {
		this(decoded.sessionFence(), decoded.customAlias());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(VERSION);
		writeFence(buffer, sessionFence);
		buffer.writeBoolean(customAlias.isPresent());
		customAlias.ifPresent(alias -> buffer.writeUtf(alias, MAX_ALIAS_LENGTH));
	}

	private static LogisticsAliasRenamePacket read(RegistryFriendlyByteBuf buffer) {
		if (buffer.readVarInt() != VERSION)
			throw new IllegalArgumentException("unsupported logistics alias packet version");
		SessionFence fence = readFence(buffer);
		Optional<String> alias = buffer.readBoolean()
			? Optional.of(buffer.readUtf(MAX_ALIAS_LENGTH)) : Optional.empty();
		return new LogisticsAliasRenamePacket(fence, alias);
	}

	public boolean handle(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		return player.containerMenu instanceof LogisticsAliasMenu menu
			&& menu.apply(player, this);
	}

	static boolean authorizedSession(SessionFence captured, SessionFence current) {
		return Objects.requireNonNull(captured, "captured fence")
			.equals(Objects.requireNonNull(current, "current fence"));
	}

	static boolean authorizedMutation(boolean session, boolean mayAdministrate,
		LogisticsNetworkAliasService.RenameResult result) {
		return session && mayAdministrate
			&& result == LogisticsNetworkAliasService.RenameResult.APPLIED;
	}

	public record SessionFence(int containerId, InteractionHand hand, int slot,
		String stackFingerprint, UUID networkId, long expectedRevision) {
		public SessionFence {
			hand = Objects.requireNonNull(hand, "hand");
			stackFingerprint = Objects.requireNonNull(stackFingerprint, "stack fingerprint");
			networkId = Objects.requireNonNull(networkId, "network id");
			boolean slotMatches = hand == InteractionHand.MAIN_HAND
				? slot >= 0 && slot < 9 : slot == 40;
			if (containerId < 0 || !slotMatches || expectedRevision < 0
				|| !stackFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("invalid logistics alias session fence");
		}
	}

	static void writeFence(RegistryFriendlyByteBuf buffer, SessionFence fence) {
		fence = Objects.requireNonNull(fence, "session fence");
		buffer.writeVarInt(fence.containerId());
		buffer.writeEnum(fence.hand());
		buffer.writeVarInt(fence.slot());
		buffer.writeUtf(fence.stackFingerprint(), 64);
		buffer.writeUUID(fence.networkId());
		buffer.writeVarLong(fence.expectedRevision());
	}

	static SessionFence readFence(RegistryFriendlyByteBuf buffer) {
		return new SessionFence(buffer.readVarInt(), buffer.readEnum(InteractionHand.class),
			buffer.readVarInt(), buffer.readUtf(64), buffer.readUUID(), buffer.readVarLong());
	}
}
