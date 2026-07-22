package com.nobodiiiii.createbiotech.registry;

import net.minecraft.core.registries.Registries;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.boneratchet.BoneRatchetBlock;
import com.nobodiiiii.createbiotech.content.biopackager.BioPackagerBlock;
import com.nobodiiiii.createbiotech.content.buttercat.block.ButterCatEngineBlock;
import com.nobodiiiii.createbiotech.content.buttercat.register.ModBlocks;
import com.nobodiiiii.createbiotech.content.bufferpad.BufferPadBlock;
import com.nobodiiiii.createbiotech.content.evokerenchantingchamber.EvokerEnchantingChamberBlock;
import com.nobodiiiii.createbiotech.content.experience.BuddingExperienceBlock;
import com.nobodiiiii.createbiotech.content.experience.ExperienceClusterBlock;
import com.nobodiiiii.createbiotech.content.experience.ExperienceConstants;
import com.nobodiiiii.createbiotech.content.experience.ExperiencePumpBlock;
import com.nobodiiiii.createbiotech.content.explosionproofitemvault.ExplosionProofItemVaultBlock;
import com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod.FixedCarrotFishingRodBlock;
import com.nobodiiiii.createbiotech.content.frogportal.FrogEsophagusBlock;
import com.nobodiiiii.createbiotech.content.frogportal.GiantFrogPortalBlock;
import com.nobodiiiii.createbiotech.content.giantfrog.GiantFrogBlock;
import com.nobodiiiii.createbiotech.content.ghasthotairballoon.GhastHotAirBalloonAssemblyStationBlock;
import com.nobodiiiii.createbiotech.content.ghasthotairballoon.GhastHelmBlock;
import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlock;
import com.nobodiiiii.createbiotech.content.petridish.PetriDishBlock;
import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.content.slimeclutch.SlimeClutchBlock;
import com.nobodiiiii.createbiotech.content.squidprinter.SquidPrinterBlock;
import com.nobodiiiii.createbiotech.content.schrodingerscat.SchrodingersCatBlock;
import com.nobodiiiii.createbiotech.content.shulkerpackager.ShulkerPackagerBlock;
import com.nobodiiiii.createbiotech.content.shulkerteleporter.ShulkerTeleporterBlock;
import com.nobodiiiii.createbiotech.content.spiderassemblytable.SpiderAssemblyTableBlock;
import com.nobodiiiii.createbiotech.content.spiderassemblytable.SpiderAssemblyTableCogBlock;
import com.nobodiiiii.createbiotech.content.universaljoint.UniversalJointBlock;
import com.yision.allay.block.allayport.AllayPortBlock;
import com.nobodiiiii.createbiotech.content.creeperblastchamber.BlastProofChainDriveBlock;
import com.nobodiiiii.createbiotech.content.creeperblastchamber.CreeperBlastChamberBlock;
import com.nobodiiiii.createbiotech.content.creeperblastchamber.ExplosionProofCasingBlock;
import com.simibubi.create.content.decoration.encasing.CasingBlock;
import com.simibubi.create.content.decoration.palettes.ConnectedGlassBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.registries.DeferredHolder;
import com.tterrag.registrate.util.entry.BlockEntry;

public class CBBlocks {

	public static final DeferredRegister<Block> BLOCKS =
		DeferredRegister.create(Registries.BLOCK, CreateBiotech.MOD_ID);

	public static final DeferredHolder<Block, SlimeBeltBlock> SLIME_BELT = BLOCKS.register("slime_belt",
		() -> new SlimeBeltBlock(Block.Properties.of()
			.sound(SoundType.WOOL)
			.strength(0.8f)
			.mapColor(MapColor.COLOR_LIGHT_GREEN)
			.noOcclusion()));

	public static final DeferredHolder<Block, MagmaBeltBlock> MAGMA_BELT = BLOCKS.register("magma_belt",
		() -> new MagmaBeltBlock(Block.Properties.of()
			.sound(SoundType.WOOL)
			.strength(0.8f)
			.mapColor(MapColor.COLOR_RED)
			.noOcclusion()));

	public static final DeferredHolder<Block, PowerBeltBlock> POWER_BELT = BLOCKS.register("power_belt",
		() -> new PowerBeltBlock(Block.Properties.of()
			.sound(SoundType.WOOL)
			.strength(0.8f)
			.mapColor(MapColor.COLOR_GRAY)
			.noOcclusion()));

	public static final DeferredHolder<Block, EvokerEnchantingChamberBlock> EVOKER_ENCHANTING_CHAMBER =
		BLOCKS.register("evoker_enchanting_chamber",
			() -> new EvokerEnchantingChamberBlock(Block.Properties.of()
				.sound(SoundType.COPPER)
				.strength(2.5f)
				.mapColor(MapColor.METAL)
				.noOcclusion()));

	public static final DeferredHolder<Block, ExperiencePumpBlock> EXPERIENCE_PUMP = BLOCKS.register("experience_pump",
		() -> new ExperiencePumpBlock(Block.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
			.mapColor(MapColor.STONE)));

	public static final DeferredHolder<Block, BuddingExperienceBlock> BUDDING_EXPERIENCE =
		BLOCKS.register("budding_experience",
			() -> new BuddingExperienceBlock(Block.Properties.of()
				.sound(SoundType.AMETHYST)
				.strength(1.5f)
				.mapColor(MapColor.COLOR_PURPLE)
				.randomTicks()));

	public static final DeferredHolder<Block, ExperienceClusterBlock> SMALL_EXPERIENCE_BUD =
		BLOCKS.register("small_experience_bud",
			() -> new ExperienceClusterBlock(3, 4, ExperienceConstants::smallBudXpValue, clusterProperties()));

	public static final DeferredHolder<Block, ExperienceClusterBlock> MEDIUM_EXPERIENCE_BUD =
		BLOCKS.register("medium_experience_bud",
			() -> new ExperienceClusterBlock(4, 3, ExperienceConstants::mediumBudXpValue, clusterProperties()));

	public static final DeferredHolder<Block, ExperienceClusterBlock> LARGE_EXPERIENCE_BUD =
		BLOCKS.register("large_experience_bud",
			() -> new ExperienceClusterBlock(5, 3, ExperienceConstants::largeBudXpValue, clusterProperties()));

	public static final DeferredHolder<Block, ExperienceClusterBlock> EXPERIENCE_CLUSTER =
		BLOCKS.register("experience_cluster",
			() -> new ExperienceClusterBlock(7, 3, ExperienceConstants::clusterXpValue, clusterProperties()));

	public static final DeferredHolder<Block, SquidPrinterBlock> SQUID_PRINTER = BLOCKS.register("squid_printer",
		() -> new SquidPrinterBlock(Block.Properties.of()
			.sound(SoundType.COPPER)
			.strength(2.0f)
			.mapColor(MapColor.TERRACOTTA_BLUE)
			.noOcclusion()));

	public static final DeferredHolder<Block, PetriDishBlock> PETRI_DISH = BLOCKS.register("petri_dish",
		() -> new PetriDishBlock(Block.Properties.of()
			.sound(SoundType.GLASS)
			.strength(1.5f)
			.mapColor(MapColor.METAL)
			.noOcclusion()));

	public static final DeferredHolder<Block, UniversalJointBlock> UNIVERSAL_JOINT = BLOCKS.register("universal_joint",
		() -> new UniversalJointBlock(Block.Properties.of()
			.sound(SoundType.STONE)
			.strength(0.8f)
			.mapColor(MapColor.METAL)
			.noOcclusion()));

	public static final DeferredHolder<Block, SlimeClutchBlock> SLIME_CLUTCH = BLOCKS.register("slime_clutch",
		() -> new SlimeClutchBlock(Block.Properties.of()
			.sound(SoundType.WOOD)
			.strength(0.8f)
			.mapColor(MapColor.PODZOL)
			.noOcclusion()));

	public static final DeferredHolder<Block, BoneRatchetBlock> BONE_RATCHET = BLOCKS.register("bone_ratchet",
		() -> new BoneRatchetBlock(Block.Properties.of()
			.sound(SoundType.BONE_BLOCK)
			.strength(0.8f)
			.mapColor(MapColor.SAND)
			.noOcclusion()));

	public static final DeferredHolder<Block, FixedCarrotFishingRodBlock> FIXED_CARROT_FISHING_ROD =
		BLOCKS.register("fixed_carrot_fishing_rod",
			() -> new FixedCarrotFishingRodBlock(Block.Properties.of()
				.sound(SoundType.WOOD)
				.strength(0.4f)
				.mapColor(MapColor.WOOD)
				.noOcclusion()));

	public static final DeferredHolder<Block, GhastHotAirBalloonAssemblyStationBlock> GHAST_HOT_AIR_BALLOON_ASSEMBLY_STATION =
		BLOCKS.register("ghast_hot_air_balloon_assembly_station",
			() -> new GhastHotAirBalloonAssemblyStationBlock(Block.Properties.of()
				.sound(SoundType.WOOD)
				.strength(2.0f)
				.mapColor(MapColor.WOOD)
				.noOcclusion()));

	public static final DeferredHolder<Block, GhastHelmBlock> GHAST_HELM = BLOCKS.register("ghast_helm",
		() -> new GhastHelmBlock(Block.Properties.of()
			.sound(SoundType.WOOD)
			.strength(2.0f)
			.mapColor(MapColor.WOOD)
			.noOcclusion()));

	public static final DeferredHolder<Block, SchrodingersCatBlock> SCHRODINGERS_CAT =
		BLOCKS.register("schrodingers_cat",
			() -> new SchrodingersCatBlock(Block.Properties.of()
				.sound(SoundType.WOOL)
				.strength(0.8f)
				.mapColor(MapColor.COLOR_BROWN)
				.noOcclusion()));

	public static final DeferredHolder<Block, SpiderAssemblyTableBlock> SPIDER_ASSEMBLY_TABLE =
		BLOCKS.register("spider_assembly_table",
			() -> new SpiderAssemblyTableBlock(Block.Properties.of()
				.sound(SoundType.WOOL)
				.strength(1.2f)
				.mapColor(MapColor.COLOR_BLACK)
				.noOcclusion()));

	public static final DeferredHolder<Block, SpiderAssemblyTableCogBlock> SPIDER_ASSEMBLY_TABLE_COG =
		BLOCKS.register("spider_assembly_table_cog",
			() -> new SpiderAssemblyTableCogBlock(Block.Properties.of()
				.sound(SoundType.WOOL)
				.strength(1.2f)
				.mapColor(MapColor.COLOR_BLACK)
				.noOcclusion()));

	public static final DeferredHolder<Block, CreeperBlastChamberBlock> CREEPER_BLAST_CHAMBER =
		BLOCKS.register("creeper_blast_chamber",
			() -> new CreeperBlastChamberBlock(Block.Properties.of()
				.sound(SoundType.NETHERITE_BLOCK)
				.strength(50.0f, 1200.0f)
				.requiresCorrectToolForDrops()
				.mapColor(MapColor.COLOR_GRAY)
				.noOcclusion()));

	public static final DeferredHolder<Block, CasingBlock> ASURINE_CASING =
		BLOCKS.register("asurine_casing",
			() -> new CasingBlock(Block.Properties.ofFullCopy(Blocks.ANDESITE)
				.sound(SoundType.WOOD)
				.mapColor(MapColor.COLOR_LIGHT_BLUE)));

	public static final DeferredHolder<Block, CasingBlock> BIOTECH_CASING =
		BLOCKS.register("biotech_casing",
			() -> new CasingBlock(Block.Properties.ofFullCopy(Blocks.ANDESITE)
				.sound(SoundType.WOOD)
				.mapColor(MapColor.COLOR_LIGHT_BLUE)));

	public static final DeferredHolder<Block, ExplosionProofCasingBlock> EXPLOSION_PROOF_CASING =
		BLOCKS.register("explosion_proof_casing",
			() -> new ExplosionProofCasingBlock(Block.Properties.of()
				.sound(SoundType.NETHERITE_BLOCK)
				.strength(50.0f, 1200.0f)
				.requiresCorrectToolForDrops()
				.mapColor(MapColor.COLOR_GRAY)));

	public static final DeferredHolder<Block, ExplosionProofItemVaultBlock> EXPLOSION_PROOF_ITEM_VAULT =
		BLOCKS.register("explosion_proof_item_vault",
			() -> new ExplosionProofItemVaultBlock(Block.Properties.ofFullCopy(Blocks.GOLD_BLOCK)
				.mapColor(MapColor.COLOR_GRAY)
				.sound(SoundType.NETHERITE_BLOCK)
				.explosionResistance(1200.0f)));

	public static final DeferredHolder<Block, TransparentBlock> BLAST_PROOF_GLASS =
		BLOCKS.register("blast_proof_glass",
			() -> new TransparentBlock(blastProofGlassProperties()));

	public static final DeferredHolder<Block, BlastProofChainDriveBlock> BLAST_PROOF_CHAIN_DRIVE =
			BLOCKS.register("blast_proof_chain_drive",
				() -> new BlastProofChainDriveBlock(Block.Properties.of()
					.sound(SoundType.NETHERITE_BLOCK)
					.strength(50.0f, 1200.0f)
					.requiresCorrectToolForDrops()
					.noOcclusion()
					.mapColor(MapColor.COLOR_GRAY)));

	public static final DeferredHolder<Block, BioPackagerBlock> BIO_PACKAGER = BLOCKS.register("bio_packager",
		() -> new BioPackagerBlock(Block.Properties.of()
			.sound(SoundType.WOOD)
			.strength(2.0f)
			.mapColor(MapColor.WOOD)
			.noOcclusion()));

	public static final DeferredHolder<Block, ShulkerPackagerBlock> SHULKER_PACKAGER = BLOCKS.register("shulker_packager",
		() -> new ShulkerPackagerBlock(Block.Properties.of()
			.sound(SoundType.WOOD)
			.strength(2.0f)
			.mapColor(MapColor.WOOD)
			.noOcclusion()));

	public static final DeferredHolder<Block, ShulkerTeleporterBlock> SHULKER_TELEPORTER =
		BLOCKS.register("shulker_teleporter",
			() -> new ShulkerTeleporterBlock(Block.Properties.of()
				.sound(SoundType.STONE)
				.strength(2.0f)
				.mapColor(MapColor.COLOR_PURPLE)
				.noOcclusion()));

	public static final DeferredHolder<Block, AllayPortBlock> ALLAY_PORT =
		BLOCKS.register("allay_port",
			() -> new AllayPortBlock(Block.Properties.of()
				.sound(SoundType.METAL)
				.strength(3.0f)
				.mapColor(MapColor.METAL)
				.requiresCorrectToolForDrops()
				.noOcclusion()));

	public static final DeferredHolder<Block, ConnectedGlassBlock> BLAST_PROOF_FRAMED_GLASS =
		BLOCKS.register("blast_proof_framed_glass",
			() -> new ConnectedGlassBlock(blastProofGlassProperties()));

	public static final Map<DyeColor, DeferredHolder<Block, BufferPadBlock>> BUFFER_PADS = registerBufferPads();
	public static final DeferredHolder<Block, BufferPadBlock> BUFFER_PAD = BUFFER_PADS.get(DyeColor.RED);

	public static final DeferredHolder<Block, GiantFrogPortalBlock> GIANT_FROG_PORTAL =
		BLOCKS.register("giant_frog_portal",
			() -> new GiantFrogPortalBlock(Block.Properties.of()
				.sound(SoundType.SLIME_BLOCK)
				.strength(2.0f)
				.mapColor(MapColor.COLOR_GREEN)
				.noCollission()
				.lightLevel(state -> 11)
				.noOcclusion()));

	public static final DeferredHolder<Block, GiantFrogBlock> GIANT_FROG =
		BLOCKS.register("giant_frog",
			() -> new GiantFrogBlock(Block.Properties.of()
				.sound(SoundType.SLIME_BLOCK)
				.strength(1.0f)
				.mapColor(MapColor.COLOR_GREEN)
				.noOcclusion()));

	// Indestructible shell of every Frog Stomach room; placed by FrogStomachSpace, never obtainable.
	public static final DeferredHolder<Block, Block> FROG_STOMACH_WALL =
		BLOCKS.register("frog_stomach_wall",
			() -> new Block(Block.Properties.ofFullCopy(Blocks.ORANGE_CONCRETE)
				.strength(-1.0f, 3600000.0f)
				.noLootTable()));

	// Indestructible return portal inside every Frog Stomach room; placed with the room, never obtainable.
	public static final DeferredHolder<Block, FrogEsophagusBlock> FROG_ESOPHAGUS =
		BLOCKS.register("frog_esophagus",
			() -> new FrogEsophagusBlock(Block.Properties.of()
				.sound(SoundType.SLIME_BLOCK)
				.strength(-1.0f, 3600000.0f)
				.mapColor(MapColor.COLOR_RED)
				.noCollission()
				.lightLevel(state -> 11)
				.noOcclusion()
				.noLootTable()));

	// Butter Cat content is registered through the shared ButterCat registrate, and re-exported
	// here so the project's primary block registry remains the place to inspect mod blocks.
	public static final BlockEntry<ButterCatEngineBlock> CUTE_CAT_ON_SHAFT = ModBlocks.CUTE_CAT_ON_SHAFT;
	public static final BlockEntry<ButterCatEngineBlock> BUTTER_CAT_ENGINE = ModBlocks.BUTTER_CAT_ENGINE;

	private static Block.Properties blastProofGlassProperties() {
		return Block.Properties.ofFullCopy(Blocks.GLASS)
			.strength(50.0f, 1200.0f);
	}

	private static Block.Properties clusterProperties() {
		return Block.Properties.of()
			.sound(SoundType.AMETHYST_CLUSTER)
			.strength(1.5f)
			.mapColor(MapColor.COLOR_PURPLE)
			.noOcclusion()
			.lightLevel(state -> 5);
	}

	private static Map<DyeColor, DeferredHolder<Block, BufferPadBlock>> registerBufferPads() {
		EnumMap<DyeColor, DeferredHolder<Block, BufferPadBlock>> bufferPads = new EnumMap<>(DyeColor.class);
		for (DyeColor color : DyeColor.values()) {
			bufferPads.put(color, BLOCKS.register(bufferPadId(color),
				() -> new BufferPadBlock(Block.Properties.of()
					.sound(SoundType.WOOL)
					.strength(0.4f)
					.mapColor(color.getMapColor())
					.noOcclusion())));
		}
		return Collections.unmodifiableMap(bufferPads);
	}

	public static String bufferPadId(DyeColor color) {
		return color == DyeColor.RED ? "buffer_pad" : color.getName() + "_buffer_pad";
	}

	public static Iterable<DeferredHolder<Block, BufferPadBlock>> allBufferPads() {
		return BUFFER_PADS.values();
	}

	private CBBlocks() {}

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
	}
}
