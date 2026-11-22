package com.nobodiiiii.createbiotech.registry;

import net.minecraft.core.registries.Registries;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsAliasMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu.ClusterControllerMenu;
import com.nobodiiiii.createbiotech.content.shulkerteleporter.ShulkerTeleporterMenu;
import com.nobodiiiii.createbiotech.content.spiderassemblytable.SpiderAssemblyTableMenu;
import com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestMenu;
import com.yision.allay.block.allayport.AllayPortMenu;
import com.yision.allay.item.allaycourier.AllayCourierMenu;

import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.registries.DeferredHolder;

public class CBMenuTypes {

	public static final DeferredRegister<MenuType<?>> MENU_TYPES =
		DeferredRegister.create(Registries.MENU, CreateBiotech.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputMenu>> REDSTONE_OUTPUT =
        MENU_TYPES.register("biocomputing_redstone_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.redstone.RedstoneOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenu>> STOCK_APPLICATION =
        MENU_TYPES.register("stock_application", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputMenu>> TEXT_OUTPUT =
        MENU_TYPES.register("biocomputing_text_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.display.TextOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputMenu>> MUSIC_OUTPUT =
        MENU_TYPES.register("biocomputing_music_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.music.MusicOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterMenu>> VIRTUAL_FILTER =
        MENU_TYPES.register("biocomputing_virtual_filter", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsMenu>> COMPONENT_SETTINGS =
        MENU_TYPES.register("biocomputing_component_settings", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputMenu>> LABEL_OUTPUT =
        MENU_TYPES.register("biocomputing_label_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.label.LabelOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputMenu>> PARTICLE_OUTPUT =
        MENU_TYPES.register("biocomputing_particle_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.particle.ParticleOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputMenu>> NARRATOR_OUTPUT =
        MENU_TYPES.register("biocomputing_narrator_output", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.localtext.LocalTextOutputMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiMenu>> TERMINAL_UI =
        MENU_TYPES.register("biocomputing_terminal_ui", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortMenu>> PRODUCTION_PORT =
        MENU_TYPES.register("biocomputing_production_port", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderMenu>> BLOCK_READER =
        MENU_TYPES.register("biocomputing_block_reader", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<SpiderAssemblyTableMenu>> SPIDER_ASSEMBLY_TABLE =
		MENU_TYPES.register("spider_assembly_table", () -> IMenuTypeExtension.create(SpiderAssemblyTableMenu::new));


	public static final DeferredHolder<MenuType<?>, MenuType<PatternStorageCoreMenu>>
		PATTERN_STORAGE_CORE = MENU_TYPES.register("pattern_storage_core",
			() -> IMenuTypeExtension.create(PatternStorageCoreMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<LogisticsAliasMenu>>
		LOGISTICS_ALIAS = MENU_TYPES.register("logistics_alias",
			() -> IMenuTypeExtension.create(LogisticsAliasMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<ComputerProgramMenu>>
		COMPUTER_PROGRAM = MENU_TYPES.register("biocomputing_program",
			() -> IMenuTypeExtension.create(ComputerProgramMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<ClusterControllerMenu>> BIOCOMPUTING_CLUSTER =
		MENU_TYPES.register("biocomputing_cluster", () -> IMenuTypeExtension.create(ClusterControllerMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryBookMenu>> BIOCOMPUTING_LIBRARY =
		MENU_TYPES.register("biocomputing_library", () -> IMenuTypeExtension.create(com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryBookMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<WirelessStockKeeperRequestMenu>> WIRELESS_STOCK_KEEPER_REQUEST =
		MENU_TYPES.register("wireless_stock_keeper_request",
			() -> IMenuTypeExtension.create(WirelessStockKeeperRequestMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<ShulkerTeleporterMenu>> SHULKER_TELEPORTER =
		MENU_TYPES.register("shulker_teleporter", () -> IMenuTypeExtension.create(ShulkerTeleporterMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<AllayPortMenu>> ALLAY_PORT =
		MENU_TYPES.register("allay_port", () -> IMenuTypeExtension.create(AllayPortMenu::new));

	public static final DeferredHolder<MenuType<?>, MenuType<AllayCourierMenu>> ALLAY_COURIER =
		MENU_TYPES.register("allay_courier", () -> IMenuTypeExtension.create(AllayCourierMenu::new));

	private CBMenuTypes() {}

	public static void register(IEventBus modEventBus) {
		MENU_TYPES.register(modEventBus);
	}
}
