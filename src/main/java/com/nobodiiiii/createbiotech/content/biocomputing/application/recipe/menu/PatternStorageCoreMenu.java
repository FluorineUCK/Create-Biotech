package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipeBrowserHost;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.*;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Canonical Pattern Browser. Its sole mutation is the server-authoritative original-book page edit.
 */
public final class PatternStorageCoreMenu extends AbstractContainerMenu {
	public static final int VERSION = 5;
	private final java.util.Optional<UUID> editorPattern;
	private final int editorPage;
	private static final java.util.Map<net.minecraft.server.level.ServerPlayer, Long> LAST_SERVICE_TICK = new java.util.WeakHashMap<>();
	private final BrowserFence openingFence;
	private final PatternBrowserClientSession clientSession;
	private final net.minecraft.core.HolderLookup.Provider registries;
	@Nullable private net.minecraft.server.level.ServerPlayer serverPlayer;
	@Nullable private PatternBrowserRequestPacket pendingRequest;
	private long editResponseVersion;
	private String editResult = "";
	private long editIntent;
	private long lastHandledMutationIntent = -1;
    private long lastPreviewTick = Long.MIN_VALUE;
    private UUID previewIntent;
    private RecipePreviewPackets.Reply previewReply;
	private long lastIntent = -1;
	private final RecipeSourceLocation coreAddress;
	private final UUID libraryId;
	private final CommunicationFrequency frequency;
	private final int priority;
	private final long revision;
	@Nullable private final RecipeBrowserHost serverCore;

	public PatternStorageCoreMenu(int containerId, Inventory inventory,
		RegistryFriendlyByteBuf buffer) {
		this(containerId, readOpening(buffer), null, inventory.player.registryAccess());
	}

	public PatternStorageCoreMenu(int containerId, Inventory inventory,
		RecipeBrowserHost core) {
		this(containerId, inventory, core, java.util.Optional.empty());
	}

	public PatternStorageCoreMenu(int containerId, Inventory inventory,
		RecipeBrowserHost core, java.util.Optional<UUID> editorPattern) {
		this(containerId, Opening.capture(core, editorPattern), core, inventory.player.registryAccess());
		if (inventory.player instanceof net.minecraft.server.level.ServerPlayer player) serverPlayer = player;
	}

	private PatternStorageCoreMenu(int containerId, Opening opening,
		@Nullable RecipeBrowserHost serverCore, net.minecraft.core.HolderLookup.Provider registries) {
		super(CBMenuTypes.PATTERN_STORAGE_CORE.get(), containerId);
		this.serverCore = serverCore;
		this.registries = registries;
		editorPattern = opening.editorPattern(); editorPage = opening.editorPage();
		coreAddress = opening.coreAddress();
		libraryId = opening.libraryId();
		frequency = opening.frequency();
		priority = opening.priority();
		revision = opening.revision();
		openingFence = opening.fence();
		clientSession = new PatternBrowserClientSession(containerId, openingFence);
	}

	public RecipeSourceLocation coreAddress() { return coreAddress; }
	public UUID libraryId() { return libraryId; }
	public CommunicationFrequency frequency() { return frequency; }
	public int priority() { return priority; }
	public long revision() { return revision; }
	public BrowserFence openingFence() { return openingFence; }
	public java.util.Optional<UUID> editorPattern() { return editorPattern; }
	public int editorPage() { return editorPage; }
	public PatternBrowserClientSession browser() { return clientSession; }
	/** Temporary doorway only; no generic entry storage or indexing is implemented by this legacy menu. */
	@Override public boolean clickMenuButton(Player player, int button) {
		return button == 0 && player == serverPlayer && player.containerMenu == this && serverCore != null
			&& stillValid(player) && serverCore.openBookLibrary(serverPlayer);
	}
	public long editResponseVersion() { return editResponseVersion; }
    UUID requestPreview(net.minecraft.resources.ResourceLocation recipe) {
        previewIntent = UUID.randomUUID();
        previewReply = null;
        CBPackets.sendToServer(new RecipePreviewPackets.Request(containerId, openingFence.sessionId(), previewIntent, recipe));
        return previewIntent;
    }
    public void receivePreview(RecipePreviewPackets.Reply reply) {
        if (containerId == reply.container() && openingFence.sessionId().equals(reply.session()) && reply.intent().equals(previewIntent))
            previewReply = reply;
    }
    @Nullable RecipePreviewPackets.Reply takePreview(UUID intent) {
        if (previewReply == null || !previewReply.intent().equals(intent)) return null;
        var reply = previewReply;
        previewReply = null;
        previewIntent = null;
        return reply;
    }
    public void preview(net.minecraft.server.level.ServerPlayer player, RecipePreviewPackets.Request request) {
        if (player != serverPlayer || player.containerMenu != this || request.container() != containerId
            || !openingFence.sessionId().equals(request.session())) return;
        var denied = RecipePreviewPackets.Outcome.DENIED;
        RecipePreviewPackets.Reply reply;
        if (serverCore == null || !stillValid(player) || !serverCore.canPlayerEdit(player)) reply = request.result(denied);
        else if (lastPreviewTick == player.level().getGameTime()) reply = request.result(RecipePreviewPackets.Outcome.BUSY);
        else {
            lastPreviewTick = player.level().getGameTime();
            var holder = player.serverLevel().getRecipeManager().byKey(request.recipe()).orElse(null);
            if (holder == null) reply = request.result(RecipePreviewPackets.Outcome.MISSING);
            else {
                try {
                    var preview = com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeDraftPreview.from(holder.value(), player.registryAccess());
                    var json = preview.encode(player.registryAccess());
                    reply = json.length() > PatternEditRequestPacket.MAX_JSON ? request.result(RecipePreviewPackets.Outcome.TOO_LARGE)
                        : new RecipePreviewPackets.Reply(containerId, request.session(), request.intent(), RecipePreviewPackets.Outcome.READY, json);
                } catch (com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeDraftPreview.Refused ex) {
                    reply = request.result(switch (ex.reason()) {
                        case UNSUPPORTED_RECIPE -> RecipePreviewPackets.Outcome.UNSUPPORTED_RECIPE;
                        case UNSUPPORTED_SELECTOR -> RecipePreviewPackets.Outcome.UNSUPPORTED_SELECTOR;
                        case GRID_REQUIRED -> RecipePreviewPackets.Outcome.GRID_REQUIRED;
                        case REMAINDER -> RecipePreviewPackets.Outcome.REMAINDER;
                        case CUSTOM_REMAINDER -> RecipePreviewPackets.Outcome.CUSTOM_REMAINDER;
                        case TOO_LARGE -> RecipePreviewPackets.Outcome.TOO_LARGE;
                    });
                } catch (RuntimeException invalidOutput) {
                    reply = request.result(RecipePreviewPackets.Outcome.UNSUPPORTED_OUTPUT);
                }
            }
        }
        CBPackets.sendToPlayer(reply, player);
    }
	public String editResult() { return editResult; }

	public void submitEdit(PatternRecord original, PatternEditData edited) {
		try {
			long intent = ++editIntent;
			CBPackets.sendToServer(new PatternEditRequestPacket(containerId, clientSession.fence(), intent,
				original.patternId(), edited.toJson(registries)));
		} catch (RuntimeException exception) {
			acceptEditResult(new PatternEditResultPacket(containerId, clientSession.fence(), editIntent, "invalid"));
		}
	}

	public void submitCreate(PatternEditData edited) {
		try {
			long intent = ++editIntent;
			CBPackets.sendToServer(new PatternCreateRequestPacket(containerId, clientSession.fence(), intent,
				edited.toJson(registries)));
		} catch (RuntimeException exception) {
			acceptEditResult(new PatternEditResultPacket(containerId, clientSession.fence(), editIntent, "invalid"));
		}
	}

	public void acceptEditResult(PatternEditResultPacket packet) {
		if (packet.containerId() != containerId || !openingFence.sameSession(packet.fence())
			|| packet.intentId() != editIntent) return;
		editResult = packet.result(); editResponseVersion++;
	}

	public void handleEdit(net.minecraft.server.level.ServerPlayer player, PatternEditRequestPacket request) {
		if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
			|| serverCore == null || !stillValid(player) || !openingFence.sameSession(request.fence())
			|| request.intentId() <= lastHandledMutationIntent) return;
		lastHandledMutationIntent = request.intentId();
		String result = serverCore.canPlayerEdit(player)
			? serverCore.editPattern(player, request.fence(), request.patternId(), request.fence().generation(), request.editedJson())
			: "denied";
		CBPackets.sendToPlayer(new PatternEditResultPacket(containerId, request.fence(), request.intentId(), result), player);
	}

	public void handleCreate(net.minecraft.server.level.ServerPlayer player, PatternCreateRequestPacket request) {
		if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
			|| serverCore == null || !stillValid(player) || !openingFence.sameSession(request.fence())
			|| request.intentId() <= lastHandledMutationIntent) return;
		lastHandledMutationIntent = request.intentId();
		String result = serverCore.canPlayerEdit(player)
			? serverCore.createPattern(player, request.fence(), request.editedJson()) : "denied";
		CBPackets.sendToPlayer(new PatternEditResultPacket(containerId, request.fence(), request.intentId(), result), player);
	}

	/** Last intent wins within one tick; reopening does not reset the per-player read budget. */
	public boolean enqueue(net.minecraft.server.level.ServerPlayer player, PatternBrowserRequestPacket request) {
		if (player != serverPlayer || player.containerMenu != this || request.containerId() != containerId
			|| !openingFence.sameSession(request.fence()) || !stillValid(player) || request.intentId() <= lastIntent) return false;
		lastIntent = request.intentId(); pendingRequest = request; return true;
	}

	@Override public void broadcastChanges() {
		super.broadcastChanges();
		if (serverPlayer == null || serverCore == null || pendingRequest == null || serverPlayer.containerMenu != this
			|| !stillValid(serverPlayer)) return;
		long tick = serverPlayer.serverLevel().getGameTime();
		if (LAST_SERVICE_TICK.getOrDefault(serverPlayer, Long.MIN_VALUE) == tick) return;
		LAST_SERVICE_TICK.put(serverPlayer, tick);
		var request = pendingRequest; pendingRequest = null;
		var page = serverCore.browserService(openingFence).page(request.fence(), request.filter(), request.pageIndex(), request.selection());
		var response = new PatternBrowserResponsePacket(containerId, request.intentId(), page, serverCore.browserDiagnostics());
		CBPackets.sendToPlayer(response.bounded(serverPlayer.registryAccess()), serverPlayer);
	}

	public boolean ownsServerCore(RecipeBrowserHost core) {
		return serverCore == core;
	}

	public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
		buffer.writeVarInt(VERSION);
		writeAddress(buffer, coreAddress);
		buffer.writeUUID(libraryId);
		frequency.write(buffer);
		buffer.writeInt(priority);
		buffer.writeLong(revision);
		PatternBrowserWire.writeFence(buffer, openingFence);
		buffer.writeBoolean(editorPattern.isPresent()); editorPattern.ifPresent(buffer::writeUUID);
		buffer.writeVarInt(editorPage);
	}

	@Override
	public boolean stillValid(Player player) {
		return serverCore == null || serverCore.libraryId().equals(libraryId)
			&& serverCore.libraryLocation().equals(coreAddress)
			&& serverCore.canPlayerUse(player);
	}

	@Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

	private static Opening readOpening(RegistryFriendlyByteBuf buffer) {
		if (buffer.readVarInt() != VERSION)
			throw new IllegalArgumentException("unsupported Pattern Storage Core menu opening version");
		return new Opening(readAddress(buffer), buffer.readUUID(),
			CommunicationFrequency.read(buffer), buffer.readInt(), buffer.readLong(), PatternBrowserWire.readFence(buffer),
			buffer.readBoolean() ? java.util.Optional.of(buffer.readUUID()) : java.util.Optional.empty(), buffer.readVarInt());
	}

	private static void writeAddress(RegistryFriendlyByteBuf buffer, RecipeSourceLocation address) {
		buffer.writeResourceLocation(address.dimension().location());
		buffer.writeBoolean(address.subLevelId() != null);
		if (address.subLevelId() != null) buffer.writeUUID(address.subLevelId());
		buffer.writeBlockPos(address.localPos());
	}

	private static RecipeSourceLocation readAddress(RegistryFriendlyByteBuf buffer) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
			buffer.readResourceLocation());
		UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
		BlockPos pos = buffer.readBlockPos();
		return new RecipeSourceLocation(dimension, subLevel, pos);
	}

	private record Opening(RecipeSourceLocation coreAddress, UUID libraryId,
		CommunicationFrequency frequency, int priority, long revision, BrowserFence fence,
		java.util.Optional<UUID> editorPattern, int editorPage) {
		private Opening {
			coreAddress = Objects.requireNonNull(coreAddress, "coreAddress");
			libraryId = Objects.requireNonNull(libraryId, "libraryId");
			frequency = Objects.requireNonNull(frequency, "frequency");
			Objects.requireNonNull(editorPattern);
			if (editorPage < 0) throw new IllegalArgumentException("Negative editor page");
			if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
			if (!fence.coreAddress().equals(coreAddress) || !fence.libraryId().equals(libraryId))
				throw new IllegalArgumentException("foreign browser opening fence");
		}

		private static Opening capture(RecipeBrowserHost core, java.util.Optional<UUID> editorPattern) {
			core = Objects.requireNonNull(core, "core");
			var routing = core.routingConfiguration();
			int index = 0;
			var snapshot = core.recipeSnapshot();
			var entries = snapshot.records();
			if (editorPattern.isPresent()) for (int i = 0; i < entries.size(); i++)
				if (entries.get(i).patternId().equals(editorPattern.get())) { index = i; break; }
			return new Opening(core.libraryLocation(), core.libraryId(), routing.frequency(),
				routing.priority(), routing.revision(), new BrowserFence(UUID.randomUUID(), core.libraryLocation(),
					core.libraryId(), snapshot.generation()), editorPattern, index / PatternBrowserPage.PAGE_SIZE);
		}

	}
}
