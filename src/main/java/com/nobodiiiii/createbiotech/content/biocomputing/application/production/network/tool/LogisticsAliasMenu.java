package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;


import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.NetworkNamesSavedData;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** One-shot editor bound to the exact held configuration tool and directory revision. */
public final class LogisticsAliasMenu extends AbstractContainerMenu {
	private final LogisticsAliasRenamePacket.SessionFence sessionFence;
	private final Optional<String> customAlias;
	@Nullable private final ItemStack serverStackSnapshot;
	@Nullable private final LogisticsNetworkAliasService serverAliases;

	public LogisticsAliasMenu(int containerId, Inventory inventory,
		RegistryFriendlyByteBuf buffer) {
		this(containerId, readOpening(buffer), null, null);
	}

	private LogisticsAliasMenu(int containerId, Opening opening,
		@Nullable ItemStack serverStackSnapshot,
		@Nullable LogisticsNetworkAliasService serverAliases) {
		super(CBMenuTypes.LOGISTICS_ALIAS.get(), containerId);
		if (opening.fence().containerId() != containerId)
			throw new IllegalArgumentException("foreign logistics alias container");
		sessionFence = opening.fence();
		customAlias = opening.customAlias();
		this.serverStackSnapshot = serverStackSnapshot;
		this.serverAliases = serverAliases;
	}

	public Optional<String> customAlias() { return customAlias; }
	public UUID networkId() { return sessionFence.networkId(); }
	public LogisticsAliasRenamePacket renamePacket(String alias) {
		return new LogisticsAliasRenamePacket(sessionFence,
			Optional.ofNullable(alias));
	}

	boolean apply(ServerPlayer player, LogisticsAliasRenamePacket packet) {
		if (serverAliases == null || serverStackSnapshot == null || !stillValid(player)
			|| !LogisticsAliasRenamePacket.authorizedSession(packet.sessionFence(), sessionFence))
			return false;
		ItemStack current = stackAt(player, sessionFence.hand(), sessionFence.slot());
		boolean exactStack = ItemStack.isSameItemSameComponents(current, serverStackSnapshot)
			&& current.getCount() == serverStackSnapshot.getCount()
			&& LogisticsToolData.fingerprint(current).equals(sessionFence.stackFingerprint())
			&& LogisticsToolData.read(current).networkId()
				.filter(sessionFence.networkId()::equals).isPresent();
		if (!exactStack) return false;
		boolean mayAdministrate = com.simibubi.create.Create.LOGISTICS
			.mayAdministrate(sessionFence.networkId(), player);
		if (!mayAdministrate) return false;
		LogisticsNetworkAliasService.RenameResult result = serverAliases.rename(player,
			sessionFence.networkId(), sessionFence.expectedRevision(), packet.customAlias());
		return LogisticsAliasRenamePacket.authorizedMutation(true, true, result);
	}

	@Override public boolean stillValid(Player player) {
		if (serverStackSnapshot == null) return true;
		ItemStack current = stackAt(player, sessionFence.hand(), sessionFence.slot());
		return current.is(CBItems.LOGISTICS_CONFIGURATION_TOOL.get())
			&& ItemStack.isSameItemSameComponents(current, serverStackSnapshot)
			&& current.getCount() == serverStackSnapshot.getCount();
	}

	@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

	public static OpeningProvider provider(ServerPlayer player, InteractionHand hand,
		ItemStack stack, UUID networkId) {
		return new OpeningProvider(player, hand, stack, networkId);
	}

	private static ItemStack stackAt(Player player, InteractionHand hand, int slot) {
		if (hand == InteractionHand.OFF_HAND) return player.getOffhandItem();
		return player.getInventory().selected == slot
			? player.getMainHandItem() : ItemStack.EMPTY;
	}

	private static Opening readOpening(RegistryFriendlyByteBuf buffer) {
		if (buffer.readVarInt() != LogisticsAliasRenamePacket.VERSION)
			throw new IllegalArgumentException("unsupported logistics alias menu version");
		LogisticsAliasRenamePacket.SessionFence fence =
			LogisticsAliasRenamePacket.readFence(buffer);
		Optional<String> alias = buffer.readBoolean()
			? Optional.of(buffer.readUtf(LogisticsAliasRenamePacket.MAX_ALIAS_LENGTH))
			: Optional.empty();
		return new Opening(fence, alias);
	}

	private record Opening(LogisticsAliasRenamePacket.SessionFence fence,
		Optional<String> customAlias) {
		private Opening {
			fence = Objects.requireNonNull(fence, "session fence");
			customAlias = Objects.requireNonNull(customAlias, "custom alias");
		}
	}

	public static final class OpeningProvider implements MenuProvider {
		private final ServerPlayer player;
		private final InteractionHand hand;
		private final ItemStack stack;
		private final UUID networkId;
		@Nullable private Opening opening;

		private OpeningProvider(ServerPlayer player, InteractionHand hand,
			ItemStack stack, UUID networkId) {
			this.player = Objects.requireNonNull(player, "player");
			this.hand = Objects.requireNonNull(hand, "hand");
			this.stack = Objects.requireNonNull(stack, "stack");
			this.networkId = Objects.requireNonNull(networkId, "network id");
		}

		@Override public Component getDisplayName() {
			return Component.translatable("create_biotech.logistics_alias.title");
		}
		@Override public AbstractContainerMenu createMenu(int containerId,
			Inventory inventory, Player ignored) {
			Opening captured = capture(containerId);
			LogisticsNetworkAliasService aliases = new LogisticsNetworkAliasService(
				NetworkNamesSavedData.directory(player.server).orElseThrow(() -> new IllegalStateException("Network names unavailable")));
			return new LogisticsAliasMenu(containerId, captured, stack.copy(), aliases);
		}
		public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
			Opening captured = Objects.requireNonNull(opening,
				"menu must be created before opening data is written");
			buffer.writeVarInt(LogisticsAliasRenamePacket.VERSION);
			LogisticsAliasRenamePacket.writeFence(buffer, captured.fence());
			buffer.writeBoolean(captured.customAlias().isPresent());
			captured.customAlias().ifPresent(alias -> buffer.writeUtf(alias,
				LogisticsAliasRenamePacket.MAX_ALIAS_LENGTH));
		}

		private Opening capture(int containerId) {
			if (opening != null) return opening;
			if (!stack.is(CBItems.LOGISTICS_CONFIGURATION_TOOL.get())
				|| LogisticsToolData.read(stack).networkId().filter(networkId::equals).isEmpty())
				throw new IllegalStateException("alias menu requires the captured tool");
			int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40;
			var directory = NetworkNamesSavedData.directory(player.server).orElseThrow(() -> new IllegalStateException("Network names unavailable"));
			LogisticsNetworkDirectoryEntry entry = directory.find(networkId).orElseThrow();
			opening = new Opening(new LogisticsAliasRenamePacket.SessionFence(containerId,
				hand, slot, LogisticsToolData.fingerprint(stack), networkId, entry.revision()),
				entry.customAlias());
			return opening;
		}
	}
}
