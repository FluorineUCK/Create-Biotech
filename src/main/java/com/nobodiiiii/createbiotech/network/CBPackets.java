package com.nobodiiiii.createbiotech.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biopackager.BioPackagerContraptionAnimationPacket;
import com.nobodiiiii.createbiotech.content.giantfrog.GiantFrogEatPacket;
import com.nobodiiiii.createbiotech.content.ghasthotairballoon.GhastBalloonMagnetTargetPacket;
import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltEntityAnimationPacket;
import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltSurfaceMovementPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDraftRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDraftResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.library.inventory.LibraryCreativeInventoryRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramControlRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControlResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryBookRequest;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryBookResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramStatusResponse;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsAliasRenamePacket;
import com.nobodiiiii.createbiotech.content.shulkerpackager.ShulkerPackagerPlacementPacket;
import com.nobodiiiii.createbiotech.content.shulkerteleporter.ShulkerTeleporterConfigPacket;
import com.nobodiiiii.createbiotech.content.smartglue.SmartSuperGlueRemovalPacket;
import com.nobodiiiii.createbiotech.content.smartglue.SmartSuperGlueSelectionPacket;
import com.yision.allay.block.allayport.AllayPortConfigurationPacket;
import com.yision.allay.block.allayport.AllayPortFlapPacket;
import com.yision.allay.logistics.courier.hud.AllayCourierHudPacket;
import com.yision.allay.network.allay.AllayCourierConfirmPacket;

import net.createmod.catnip.annotations.ClientOnly;
import net.createmod.catnip.net.base.BasePacketPayload.PacketTypeProvider;
import net.createmod.catnip.net.base.CatnipPacketRegistry;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public final class CBPackets {

    private static final String NETWORK_VERSION = "114";
	private static final List<ServerRegistration<?>> SERVERBOUND = new ArrayList<>();
	private static final List<ClientRegistration<?>> CLIENTBOUND = new ArrayList<>();
	private static final Map<Class<?>, Integer> SERVERBOUND_IDS = new HashMap<>();
	private static final Map<Class<?>, Integer> CLIENTBOUND_IDS = new HashMap<>();
	private static final Map<ServerPlayer, PanelDeliveryTrace<ClientboundPacketPayload>> PANEL_DELIVERIES =
		new WeakHashMap<>();
	private static boolean registered;
	private static final Map<CustomPacketPayload.Type<?>, StreamCodec<RegistryFriendlyByteBuf, ?>> PANEL_CODECS =
		new HashMap<>();

	private static <T> StreamCodec<RegistryFriendlyByteBuf, T> panelCodec(PacketTypeProvider type,
		StreamCodec<RegistryFriendlyByteBuf, T> codec) {
		PANEL_CODECS.put(type.getType(), codec);
		return codec;
	}

	/** Exercises exactly the codec installed into the production Catnip registration. */
	@SuppressWarnings("unchecked")
	public static <T extends net.createmod.catnip.net.base.BasePacketPayload> T roundTripPanelForTest(
		T packet, RegistryFriendlyByteBuf wire) {
		var codec = (StreamCodec<RegistryFriendlyByteBuf, T>) PANEL_CODECS.get(packet.getTypeProvider().getType());
		if (codec == null) throw new IllegalStateException("Panel payload has no live typed registration");
		codec.encode(wire, packet);
		return codec.decode(wire);
	}

	private CBPackets() {}

	public static void register() {
		if (registered)
			return;
		registered = true;

		registerServer(PowerBeltSurfaceMovementPacket.class, PowerBeltSurfaceMovementPacket::new,
			PowerBeltSurfaceMovementPacket::write, PowerBeltSurfaceMovementPacket::handle);
		registerServer(GhastBalloonMagnetTargetPacket.class, GhastBalloonMagnetTargetPacket::new,
			GhastBalloonMagnetTargetPacket::write, GhastBalloonMagnetTargetPacket::handle);
		registerServer(SmartSuperGlueSelectionPacket.class, SmartSuperGlueSelectionPacket::new,
			SmartSuperGlueSelectionPacket::write, SmartSuperGlueSelectionPacket::handle);
		registerServer(SmartSuperGlueRemovalPacket.class, SmartSuperGlueRemovalPacket::new,
			SmartSuperGlueRemovalPacket::write, SmartSuperGlueRemovalPacket::handle);
		registerServer(ShulkerPackagerPlacementPacket.class, ShulkerPackagerPlacementPacket::new,
			ShulkerPackagerPlacementPacket::write, ShulkerPackagerPlacementPacket::handle);
		registerServer(ShulkerTeleporterConfigPacket.class, ShulkerTeleporterConfigPacket::new,
			ShulkerTeleporterConfigPacket::write, ShulkerTeleporterConfigPacket::handle);
		registerServer(AllayCourierConfirmPacket.class, AllayCourierConfirmPacket::new,
			AllayCourierConfirmPacket::write, AllayCourierConfirmPacket::handle);
		registerServer(AllayPortConfigurationPacket.class, AllayPortConfigurationPacket::new,
			AllayPortConfigurationPacket::write, AllayPortConfigurationPacket::handle);
		// The union is retained only for unrelated historical packets. Panel protocol
		// payloads below each own a distinct Catnip type and codec.
		// Union slot 8 belonged to the production-only listener editor. Never reuse it:
		// decodeServer rejects a retired slot before reading any operation or address.
		SERVERBOUND.add(null);
		// Append-only: every pre-19 serverbound id above remains stable.
		registerServer(LogisticsAliasRenamePacket.class,
			LogisticsAliasRenamePacket::new, LogisticsAliasRenamePacket::write,
			LogisticsAliasRenamePacket::handle);
		// Append-only: every pre-20 serverbound id above remains stable.
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorPreferencePacket.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorPreferencePacket::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorPreferencePacket::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorPreferencePacket::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaChunk.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaChunk::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaChunk::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaChunk::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPageRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPageRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPageRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPageRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaStoreRequest.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaStoreRequest::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaStoreRequest::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaStoreRequest::handle);
        registerServer(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPreference.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPreference::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPreference::write,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPreference::handle);

		registerClient(PowerBeltEntityAnimationPacket.class, PowerBeltEntityAnimationPacket::new,
			PowerBeltEntityAnimationPacket::write);
		registerClient(BioPackagerContraptionAnimationPacket.class, BioPackagerContraptionAnimationPacket::new,
			BioPackagerContraptionAnimationPacket::write);
		registerClient(ShulkerPackagerPlacementPacket.ClientBoundRequest.class,
			ShulkerPackagerPlacementPacket.ClientBoundRequest::new,
			ShulkerPackagerPlacementPacket.ClientBoundRequest::write);
		registerClient(AllayPortFlapPacket.class, AllayPortFlapPacket::new,
			AllayPortFlapPacket::write);
		registerClient(AllayCourierHudPacket.class, AllayCourierHudPacket::new,
			AllayCourierHudPacket::write);
		registerClient(GiantFrogEatPacket.class, GiantFrogEatPacket::new,
			GiantFrogEatPacket::write);
		// Keep every existing packet id stable; new packets are appended.
		registerClient(ShulkerPackagerPlacementPacket.ClientBoundResult.class,
			ShulkerPackagerPlacementPacket.ClientBoundResult::new,
			ShulkerPackagerPlacementPacket.ClientBoundResult::write);
		registerClient(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuResponse.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuResponse::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuResponse::write);
        registerClient(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse::write);
        registerClient(com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechPacket.class, com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechPacket::new, com.nobodiiiii.createbiotech.content.biocomputing.device.narrator.NarratorSpeechPacket::write);
        registerClient(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPage.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPage::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerMediaPage::write);
        registerClient(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPacket.class,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPacket::new,
            com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.SpeakerPlaybackPacket::write);
        registerClient(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls.class,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls::new,
            com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls::write);
        CatnipPacketRegistry registry = new CatnipPacketRegistry(CreateBiotech.MOD_ID, NETWORK_VERSION);
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(
			PacketType.SERVERBOUND.type(), ServerPayload.class, ServerPayload.STREAM_CODEC));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(
			PacketType.CLIENTBOUND.type(), ClientPayload.class, ClientPayload.STREAM_CODEC));
		registerPanelPackets(registry);
		registry.registerAllPackets();
	}

	private static void registerPanelPackets(CatnipPacketRegistry registry) {
        registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_DRAFT_REQUEST.type(),
            ProgramDraftRequest.class, panelCodec(PanelPacketType.PROGRAM_DRAFT_REQUEST,
                StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramDraftRequest::new))));
        registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_DRAFT_RESPONSE.type(),
            ProgramDraftResponse.class, panelCodec(PanelPacketType.PROGRAM_DRAFT_RESPONSE,
                StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramDraftResponse::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.LIBRARY_CREATIVE_INVENTORY_REQUEST.type(),
			LibraryCreativeInventoryRequest.class, panelCodec(PanelPacketType.LIBRARY_CREATIVE_INVENTORY_REQUEST,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), LibraryCreativeInventoryRequest::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_EDITOR_REQUEST.type(),
			ProgramEditorRequest.class, panelCodec(PanelPacketType.PROGRAM_EDITOR_REQUEST,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramEditorRequest::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_EDITOR_RESPONSE.type(),
			ProgramEditorResponse.class, panelCodec(PanelPacketType.PROGRAM_EDITOR_RESPONSE,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramEditorResponse::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_CONTROL_REQUEST.type(),
			ProgramControlRequest.class, panelCodec(PanelPacketType.PROGRAM_CONTROL_REQUEST,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramControlRequest::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.BIOCOMPUTING_CLUSTER_REQUEST.type(),
			ClusterControlRequest.class, panelCodec(PanelPacketType.BIOCOMPUTING_CLUSTER_REQUEST,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ClusterControlRequest::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.BIOCOMPUTING_LIBRARY_REQUEST.type(),
			LibraryBookRequest.class, panelCodec(PanelPacketType.BIOCOMPUTING_LIBRARY_REQUEST,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), LibraryBookRequest::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.BIOCOMPUTING_LIBRARY_RESPONSE.type(),
			LibraryBookResponse.class, panelCodec(PanelPacketType.BIOCOMPUTING_LIBRARY_RESPONSE,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), LibraryBookResponse::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.BIOCOMPUTING_CLUSTER_RESPONSE.type(),
			ClusterControlResponse.class, panelCodec(PanelPacketType.BIOCOMPUTING_CLUSTER_RESPONSE,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ClusterControlResponse::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PROGRAM_STATUS_RESPONSE.type(),
			ProgramStatusResponse.class, panelCodec(PanelPacketType.PROGRAM_STATUS_RESPONSE,
				StreamCodec.of((buffer, packet) -> packet.write(buffer), ProgramStatusResponse::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PATTERN_BROWSER_REQUEST.type(),
			com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserRequestPacket.class,
			panelCodec(PanelPacketType.PATTERN_BROWSER_REQUEST, StreamCodec.of((buffer, packet) -> packet.write(buffer),
				com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserRequestPacket::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PATTERN_BROWSER_RESPONSE.type(),
			com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket.class,
			panelCodec(PanelPacketType.PATTERN_BROWSER_RESPONSE, StreamCodec.of((buffer, packet) -> packet.write(buffer),
				com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PATTERN_EDIT_REQUEST.type(),
			com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditRequestPacket.class,
			panelCodec(PanelPacketType.PATTERN_EDIT_REQUEST, StreamCodec.of((buffer, packet) -> packet.write(buffer),
				com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditRequestPacket::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PATTERN_CREATE_REQUEST.type(),
			com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternCreateRequestPacket.class,
			panelCodec(PanelPacketType.PATTERN_CREATE_REQUEST, StreamCodec.of((buffer, packet) -> packet.write(buffer),
				com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternCreateRequestPacket::new))));
		registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.PATTERN_EDIT_RESULT.type(),
			com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditResultPacket.class,
			panelCodec(PanelPacketType.PATTERN_EDIT_RESULT, StreamCodec.of((buffer, packet) -> packet.write(buffer),
				com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditResultPacket::new))));
        registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.RECIPE_PREVIEW_REQUEST.type(),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipePreviewPackets.Request.class,
            panelCodec(PanelPacketType.RECIPE_PREVIEW_REQUEST, StreamCodec.of((b, p) -> p.write(b),
                com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipePreviewPackets.Request::new))));
        registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.RECIPE_PREVIEW_REPLY.type(),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipePreviewPackets.Reply.class,
            panelCodec(PanelPacketType.RECIPE_PREVIEW_REPLY, StreamCodec.of((b, p) -> p.write(b),
                com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.RecipePreviewPackets.Reply::new))));
  registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.ADVANCED_GOGGLE_REQUEST.type(),com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleRequestPacket.class,panelCodec(PanelPacketType.ADVANCED_GOGGLE_REQUEST,com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleRequestPacket.STREAM_CODEC)));
  registry.registerPacket(new CatnipPacketRegistry.PacketType<>(PanelPacketType.ADVANCED_GOGGLE_RESPONSE.type(),com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleResponsePacket.class,panelCodec(PanelPacketType.ADVANCED_GOGGLE_RESPONSE,com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.AdvancedGoggleResponsePacket.STREAM_CODEC)));

	}

	public static void sendToServer(Object packet) {
		if (packet instanceof ServerboundPacketPayload payload)
			CatnipServices.NETWORK.sendToServer(payload);
		else CatnipServices.NETWORK.sendToServer(new ServerPayload(packet));
	}

	public static void sendToTrackingEntity(Object packet, Entity entity) {
		CatnipServices.NETWORK.sendToClientsTrackingEntity(entity, new ClientPayload(packet));
	}

	public static void sendToTrackingChunk(Object packet, ServerLevel level, BlockPos pos) {
		CatnipServices.NETWORK.sendToClientsTrackingChunk(level, new ChunkPos(pos), new ClientPayload(packet));
	}

	public static void sendToPlayer(Object packet, ServerPlayer player) {
		if (packet instanceof ClientboundPacketPayload payload) {
			if (isPanelResponse(payload)) synchronized (PANEL_DELIVERIES) {
				PanelDeliveryTrace<ClientboundPacketPayload> trace = PANEL_DELIVERIES.get(player);
				if (trace != null) trace.record(payload);
			}
			CatnipServices.NETWORK.sendToClient(player, payload);
		} else CatnipServices.NETWORK.sendToClient(player, new ClientPayload(packet));
	}

	/** Observes the exact payload that crossed the production player-delivery boundary. */
	public static Optional<ClientboundPacketPayload> takePanelDeliveryForTest(ServerPlayer player) {
		synchronized (PANEL_DELIVERIES) {
			PanelDeliveryTrace<ClientboundPacketPayload> trace = PANEL_DELIVERIES.get(player);
			return trace == null ? Optional.empty() : trace.take();
		}
	}

	/** Arm a bounded per-player event sequence before exercising a production handler. */
	public static void beginPanelDeliveryObservationForTest(ServerPlayer player) {
		synchronized (PANEL_DELIVERIES) { PANEL_DELIVERIES.put(player, new PanelDeliveryTrace<>()); }
	}

	private static boolean isPanelResponse(ClientboundPacketPayload payload) {
		return payload instanceof ProgramEditorResponse
            || payload instanceof ProgramDraftResponse
			|| payload instanceof ProgramStatusResponse
			|| payload instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket
			|| payload instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditResultPacket;
	}

	public static PacketTypeProvider serverboundType() {
		return PacketType.SERVERBOUND;
	}

	public static PacketTypeProvider clientboundType() {
		return PacketType.CLIENTBOUND;
	}

	private static <T> void registerServer(Class<T> type, Function<RegistryFriendlyByteBuf, T> decoder,
		BiConsumer<T, RegistryFriendlyByteBuf> encoder, BiConsumer<T, ServerPlayer> handler) {
		SERVERBOUND_IDS.put(type, SERVERBOUND.size());
		SERVERBOUND.add(new ServerRegistration<>(type, decoder, encoder, handler));
	}

	private static <T> void registerClient(Class<T> type, Function<RegistryFriendlyByteBuf, T> decoder,
		BiConsumer<T, RegistryFriendlyByteBuf> encoder) {
		CLIENTBOUND_IDS.put(type, CLIENTBOUND.size());
		CLIENTBOUND.add(new ClientRegistration<>(type, decoder, encoder));
	}

	private static ServerPayload decodeServer(RegistryFriendlyByteBuf buffer) {
		int id = buffer.readVarInt();
		if (id < 0 || id >= SERVERBOUND.size())
			throw new IllegalArgumentException("Unknown Create Biotech serverbound packet id " + id);
		ServerRegistration<?> registration = SERVERBOUND.get(id);
		if (registration == null)
			throw new IllegalArgumentException("Retired Create Biotech serverbound packet id " + id);
		return new ServerPayload(registration.decode(buffer));
	}

	private static void encodeServer(RegistryFriendlyByteBuf buffer, ServerPayload payload) {
		Integer id = SERVERBOUND_IDS.get(payload.packet.getClass());
		if (id == null)
			throw new IllegalArgumentException("Unregistered Create Biotech serverbound packet "
				+ payload.packet.getClass().getName());
		buffer.writeVarInt(id);
		SERVERBOUND.get(id).encode(payload.packet, buffer);
	}

	private static ClientPayload decodeClient(RegistryFriendlyByteBuf buffer) {
		int id = buffer.readVarInt();
		if (id < 0 || id >= CLIENTBOUND.size())
			throw new IllegalArgumentException("Unknown Create Biotech clientbound packet id " + id);
		return new ClientPayload(CLIENTBOUND.get(id).decode(buffer));
	}

	private static void encodeClient(RegistryFriendlyByteBuf buffer, ClientPayload payload) {
		Integer id = CLIENTBOUND_IDS.get(payload.packet.getClass());
		if (id == null)
			throw new IllegalArgumentException("Unregistered Create Biotech clientbound packet "
				+ payload.packet.getClass().getName());
		buffer.writeVarInt(id);
		CLIENTBOUND.get(id).encode(payload.packet, buffer);
	}

	private record ServerPayload(Object packet) implements ServerboundPacketPayload {
		private static final StreamCodec<RegistryFriendlyByteBuf, ServerPayload> STREAM_CODEC =
			StreamCodec.of(CBPackets::encodeServer, CBPackets::decodeServer);

		@Override
		public void handle(ServerPlayer player) {
			Integer id = SERVERBOUND_IDS.get(packet.getClass());
			if (id == null)
				throw new IllegalArgumentException("Unregistered Create Biotech serverbound packet "
					+ packet.getClass().getName());
			SERVERBOUND.get(id).handle(packet, player);
		}

		@Override
		public PacketTypeProvider getTypeProvider() {
			return PacketType.SERVERBOUND;
		}
	}

	private record ClientPayload(Object packet) implements ClientboundPacketPayload {
		private static final StreamCodec<RegistryFriendlyByteBuf, ClientPayload> STREAM_CODEC =
			StreamCodec.of(CBPackets::encodeClient, CBPackets::decodeClient);

		@Override
		@ClientOnly
		@OnlyIn(Dist.CLIENT)
		public void handle(LocalPlayer player) {
			Integer id = CLIENTBOUND_IDS.get(packet.getClass());
			if (id == null)
				throw new IllegalArgumentException("Unregistered Create Biotech clientbound packet "
					+ packet.getClass().getName());
			CBClientPacketHandlers.handle(packet, player);
		}

		@Override
		public PacketTypeProvider getTypeProvider() {
			return PacketType.CLIENTBOUND;
		}
	}

	private record ServerRegistration<T>(Class<T> type, Function<RegistryFriendlyByteBuf, T> decoder,
										 BiConsumer<T, RegistryFriendlyByteBuf> encoder,
										 BiConsumer<T, ServerPlayer> handler) {
		private Object decode(RegistryFriendlyByteBuf buffer) {
			return decoder.apply(buffer);
		}

		private void encode(Object packet, RegistryFriendlyByteBuf buffer) {
			encoder.accept(type.cast(packet), buffer);
		}

		private void handle(Object packet, ServerPlayer player) {
			handler.accept(type.cast(packet), player);
		}
	}

	private record ClientRegistration<T>(Class<T> type, Function<RegistryFriendlyByteBuf, T> decoder,
										 BiConsumer<T, RegistryFriendlyByteBuf> encoder) {
		private Object decode(RegistryFriendlyByteBuf buffer) {
			return decoder.apply(buffer);
		}

		private void encode(Object packet, RegistryFriendlyByteBuf buffer) {
			encoder.accept(type.cast(packet), buffer);
		}
	}

	private enum PacketType implements PacketTypeProvider {
		SERVERBOUND("serverbound"),
		CLIENTBOUND("clientbound");

		private final CustomPacketPayload.Type<? extends CustomPacketPayload> type;

		PacketType(String path) {
			type = new CustomPacketPayload.Type<>(CreateBiotech.asResource(path));
		}

		@SuppressWarnings("unchecked")
		private <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type() {
			return (CustomPacketPayload.Type<T>) type;
		}

		@Override
		public <T extends CustomPacketPayload> CustomPacketPayload.Type<T> getType() {
			return type();
		}
	}

	public static PacketTypeProvider programEditorRequestType() { return PanelPacketType.PROGRAM_EDITOR_REQUEST; }
    public static PacketTypeProvider programDraftRequestType() { return PanelPacketType.PROGRAM_DRAFT_REQUEST; }
    public static PacketTypeProvider programDraftResponseType() { return PanelPacketType.PROGRAM_DRAFT_RESPONSE; }
	public static PacketTypeProvider programEditorResponseType() { return PanelPacketType.PROGRAM_EDITOR_RESPONSE; }
	public static PacketTypeProvider programControlRequestType() { return PanelPacketType.PROGRAM_CONTROL_REQUEST; }
	public static PacketTypeProvider clusterControlRequestType() { return PanelPacketType.BIOCOMPUTING_CLUSTER_REQUEST; }
	public static PacketTypeProvider libraryBookRequestType() { return PanelPacketType.BIOCOMPUTING_LIBRARY_REQUEST; }
	public static PacketTypeProvider libraryCreativeInventoryRequestType() { return PanelPacketType.LIBRARY_CREATIVE_INVENTORY_REQUEST; }
	public static PacketTypeProvider libraryBookResponseType() { return PanelPacketType.BIOCOMPUTING_LIBRARY_RESPONSE; }
	public static PacketTypeProvider clusterControlResponseType() { return PanelPacketType.BIOCOMPUTING_CLUSTER_RESPONSE; }
	public static PacketTypeProvider programStatusResponseType() { return PanelPacketType.PROGRAM_STATUS_RESPONSE; }
	public static PacketTypeProvider patternBrowserRequestType() { return PanelPacketType.PATTERN_BROWSER_REQUEST; }
	public static PacketTypeProvider patternBrowserResponseType() { return PanelPacketType.PATTERN_BROWSER_RESPONSE; }
	public static PacketTypeProvider patternEditRequestType() { return PanelPacketType.PATTERN_EDIT_REQUEST; }
	public static PacketTypeProvider patternCreateRequestType() { return PanelPacketType.PATTERN_CREATE_REQUEST; }
	public static PacketTypeProvider patternEditResultType() { return PanelPacketType.PATTERN_EDIT_RESULT; }
    public static PacketTypeProvider recipePreviewRequestType() { return PanelPacketType.RECIPE_PREVIEW_REQUEST; }
    public static PacketTypeProvider recipePreviewReplyType() { return PanelPacketType.RECIPE_PREVIEW_REPLY; }

	public static PacketTypeProvider advancedGoggleRequestType() {return PanelPacketType.ADVANCED_GOGGLE_REQUEST;}
 public static PacketTypeProvider advancedGoggleResponseType() {return PanelPacketType.ADVANCED_GOGGLE_RESPONSE;}
	 private enum PanelPacketType implements PacketTypeProvider {
		LIBRARY_CREATIVE_INVENTORY_REQUEST("biocomputing_library/creative_inventory"),
		BIOCOMPUTING_CLUSTER_REQUEST("biocomputing_cluster/request"), BIOCOMPUTING_CLUSTER_RESPONSE("biocomputing_cluster/response"),
		BIOCOMPUTING_LIBRARY_REQUEST("biocomputing_library/request"), BIOCOMPUTING_LIBRARY_RESPONSE("biocomputing_library/response"),
		PROGRAM_EDITOR_REQUEST("biocomputing_program/request"), PROGRAM_EDITOR_RESPONSE("biocomputing_program/response"),
        PROGRAM_DRAFT_REQUEST("biocomputing_draft/request"), PROGRAM_DRAFT_RESPONSE("biocomputing_draft/response"),
		PROGRAM_CONTROL_REQUEST("biocomputing_program/control"), PROGRAM_STATUS_RESPONSE("biocomputing_program/status"),
		PATTERN_BROWSER_REQUEST("pattern_browser/request"), PATTERN_BROWSER_RESPONSE("pattern_browser/response"),
		PATTERN_EDIT_REQUEST("pattern_browser/edit_request"), PATTERN_EDIT_RESULT("pattern_browser/edit_result"),
  ADVANCED_GOGGLE_REQUEST("factory_goggles/request"), ADVANCED_GOGGLE_RESPONSE("factory_goggles/response"),














		PATTERN_CREATE_REQUEST("pattern_browser/create_request"),
        RECIPE_PREVIEW_REQUEST("pattern_browser/preview_request"),
        RECIPE_PREVIEW_REPLY("pattern_browser/preview_reply");

		private final CustomPacketPayload.Type<? extends CustomPacketPayload> type;
		PanelPacketType(String path) { type = new CustomPacketPayload.Type<>(CreateBiotech.asResource(path)); }
		@SuppressWarnings("unchecked")
		private <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type() {
			return (CustomPacketPayload.Type<T>) type;
		}
		@Override public <T extends CustomPacketPayload> CustomPacketPayload.Type<T> getType() {
			return type();
		}
	}
}
