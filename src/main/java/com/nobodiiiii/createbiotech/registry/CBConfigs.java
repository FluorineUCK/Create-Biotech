package com.nobodiiiii.createbiotech.registry;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;


public class CBConfigs {
	public static final Client CLIENT;
	public static final ModConfigSpec CLIENT_SPEC;
	public static final Common COMMON;
	public static final ModConfigSpec COMMON_SPEC;
	public static final Server SERVER;
	public static final ModConfigSpec SERVER_SPEC;

	static {
		Pair<Client, ModConfigSpec> clientSpecPair =
			new ModConfigSpec.Builder().configure(Client::new);
		CLIENT = clientSpecPair.getLeft();
		CLIENT_SPEC = clientSpecPair.getRight();

		Pair<Common, ModConfigSpec> commonSpecPair =
			new ModConfigSpec.Builder().configure(Common::new);
		COMMON = commonSpecPair.getLeft();
		COMMON_SPEC = commonSpecPair.getRight();

		Pair<Server, ModConfigSpec> serverSpecPair =
			new ModConfigSpec.Builder().configure(Server::new);
		SERVER = serverSpecPair.getLeft();
		SERVER_SPEC = serverSpecPair.getRight();
	}

	private CBConfigs() {}

	public static void register(ModContainer modContainer) {
		modContainer.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
		modContainer.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
		modContainer.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
	}

	public enum EntityListMode {
		ALLOW_ALL,
		ALLOWLIST,
		DENYLIST
	}

	public static class Common {
		Common(ModConfigSpec.Builder builder) {
		}
	}

	public static class Client {
		public final ModConfigSpec.BooleanValue enableShulkerTeleporterCameraOffset;
		public final ModConfigSpec.BooleanValue enableShulkerTeleporterPlayerClipping;
		public final ClientCreeperBlastChamber creeperBlastChamber;
		public final ClientUniversalJoint universalJoint;
		public final BeltParticles beltParticles;

		Client(ModConfigSpec.Builder builder) {
			enableShulkerTeleporterCameraOffset = builder.define("enableShulkerTeleporterCameraOffset", true);
			enableShulkerTeleporterPlayerClipping = builder.define("enableShulkerTeleporterPlayerClipping", true);
			creeperBlastChamber = new ClientCreeperBlastChamber(builder);
			universalJoint = new ClientUniversalJoint(builder);
			beltParticles = new BeltParticles(builder);
		}
	}

	public static class Server {
		public final Experience experience;
		public final CreeperBlastChamber creeperBlastChamber;
		public final PowerBelt powerBelt;
		public final PetriDish petriDish;
		public final SpiderAssemblyTable spiderAssemblyTable;
		public final CardboardBox cardboardBox;
		public final SlimeMimic slimeMimic;
		public final GhastHotAirBalloon ghastHotAirBalloon;
		public final ButterCat butterCat;
		public final Automation automation;
		public final UniversalJoint universalJoint;
		public final SquidPrinter squidPrinter;
		public final EvokerEnchantingChamber evokerEnchantingChamber;
		public final SchrodingersCat schrodingersCat;
		public final BoneRatchet boneRatchet;
		public final BasinEntityProcessing basinEntityProcessing;
		public final SlimeClutch slimeClutch;
		public final LiquidLivingSlime liquidLivingSlime;
		public final FixedCarrotFishingRod fixedCarrotFishingRod;
		public final BufferPad bufferPad;
		public final ShulkerPackager shulkerPackager;
		public final FrogStomach frogStomach;

		Server(ModConfigSpec.Builder builder) {
			experience = new Experience(builder);
			creeperBlastChamber = new CreeperBlastChamber(builder);
			powerBelt = new PowerBelt(builder);
			petriDish = new PetriDish(builder);
			spiderAssemblyTable = new SpiderAssemblyTable(builder);
			cardboardBox = new CardboardBox(builder);
			slimeMimic = new SlimeMimic(builder);
			ghastHotAirBalloon = new GhastHotAirBalloon(builder);
			butterCat = new ButterCat(builder);
			automation = new Automation(builder);
			universalJoint = new UniversalJoint(builder);
			squidPrinter = new SquidPrinter(builder);
			evokerEnchantingChamber = new EvokerEnchantingChamber(builder);
			schrodingersCat = new SchrodingersCat(builder);
			boneRatchet = new BoneRatchet(builder);
			basinEntityProcessing = new BasinEntityProcessing(builder);
			slimeClutch = new SlimeClutch(builder);
			liquidLivingSlime = new LiquidLivingSlime(builder);
			fixedCarrotFishingRod = new FixedCarrotFishingRod(builder);
			bufferPad = new BufferPad(builder);
			shulkerPackager = new ShulkerPackager(builder);
			frogStomach = new FrogStomach(builder);
		}
	}

	public static class FrogStomach {
		public final ModConfigSpec.IntValue boxSize;

		FrogStomach(ModConfigSpec.Builder builder) {
			builder.push("frogStomach");
			boxSize = builder.defineInRange("boxSize", 48, 5, 256);
			builder.pop();
		}
	}

	public static class Experience {
		public final ModConfigSpec.IntValue xpPerNugget;
		public final ModConfigSpec.IntValue clusterXpValue;
		public final ModConfigSpec.IntValue largeBudXpValue;
		public final ModConfigSpec.IntValue mediumBudXpValue;
		public final ModConfigSpec.IntValue smallBudXpValue;
		public final ModConfigSpec.IntValue buddingGrowthChance;
		public final ModConfigSpec.IntValue clusterMaxOrbsPerPinch;
		public final ModConfigSpec.IntValue clusterMinXpPerSplitOrb;

		Experience(ModConfigSpec.Builder builder) {
			builder.push("experience");
			xpPerNugget = builder.defineInRange("xpPerNugget", 3, 1, Integer.MAX_VALUE);
			clusterXpValue = builder.defineInRange("clusterXpValue", 128, 1, Integer.MAX_VALUE);
			largeBudXpValue = builder.defineInRange("largeBudXpValue", 64, 1, Integer.MAX_VALUE);
			mediumBudXpValue = builder.defineInRange("mediumBudXpValue", 32, 1, Integer.MAX_VALUE);
			smallBudXpValue = builder.defineInRange("smallBudXpValue", 16, 1, Integer.MAX_VALUE);
			buddingGrowthChance = builder.defineInRange("buddingGrowthChance", 20, 1, Integer.MAX_VALUE);
			clusterMaxOrbsPerPinch = builder.defineInRange("clusterMaxOrbsPerPinch", 5, 1, 64);
			clusterMinXpPerSplitOrb = builder.defineInRange("clusterMinXpPerSplitOrb", 37, 1, Integer.MAX_VALUE);
			builder.pop();
		}
	}

	public static class CreeperBlastChamber {
		public final ModConfigSpec.IntValue minSize;
		public final ModConfigSpec.IntValue maxSize;
		public final ModConfigSpec.IntValue overloadThresholdRpm;
		public final ModConfigSpec.IntValue overloadPointsCap;
		public final ModConfigSpec.IntValue overloadDecayPointsPerSecond;
		public final ModConfigSpec.IntValue overloadTntEquivalentPerCreeper;
		public final ModConfigSpec.IntValue chargedCreeperEquivalentMultiplier;
		public final ModConfigSpec.DoubleValue tntExplosionPower;
		public final ModConfigSpec.IntValue readyOutputTimeout;
		public final ModConfigSpec.BooleanValue enableOverloadExplosions;

		CreeperBlastChamber(ModConfigSpec.Builder builder) {
			builder.push("creeperBlastChamber");
			minSize = builder.defineInRange("minSize", 3, 1, 16);
			maxSize = builder.defineInRange("maxSize", 5, 1, 32);
			overloadThresholdRpm = builder.defineInRange("overloadThresholdRpm", 128, 1, Integer.MAX_VALUE);
			overloadPointsCap = builder.defineInRange("overloadPointsCap", 128 * 64, 1, Integer.MAX_VALUE);
			overloadDecayPointsPerSecond = builder.defineInRange("overloadDecayPointsPerSecond", 128, 0, Integer.MAX_VALUE);
			overloadTntEquivalentPerCreeper = builder.defineInRange("overloadTntEquivalentPerCreeper", 2, 0, Integer.MAX_VALUE);
			chargedCreeperEquivalentMultiplier = builder.defineInRange("chargedCreeperEquivalentMultiplier", 2, 1, Integer.MAX_VALUE);
			tntExplosionPower = builder.defineInRange("tntExplosionPower", 4.0d, 0.0d, Double.MAX_VALUE);
			readyOutputTimeout = builder.defineInRange("readyOutputTimeout", 20 * 5, 1, Integer.MAX_VALUE);
			enableOverloadExplosions = builder.define("enableOverloadExplosions", true);
			builder.pop();
		}
	}

	public static class ClientCreeperBlastChamber {
		public final ModConfigSpec.BooleanValue enableExplosionParticles;

		ClientCreeperBlastChamber(ModConfigSpec.Builder builder) {
			builder.push("creeperBlastChamber");
			enableExplosionParticles = builder.define("enableExplosionParticles", true);
			builder.pop();
		}
	}

	public static class PowerBelt {
		public final ModConfigSpec.DoubleValue surfaceMetersPerSecondToRpm;
		public final ModConfigSpec.DoubleValue maxGeneratedRpm;
		public final ModConfigSpec.DoubleValue stressCapacityPerRpm;
		public final ModConfigSpec.DoubleValue maxStressCapacityPerSegment;
		public final ModConfigSpec.IntValue surfaceSpeedDetectionInterval;
		public final ModConfigSpec.DoubleValue maxPlayerSurfaceSpeed;

		PowerBelt(ModConfigSpec.Builder builder) {
			builder.push("powerBelt");
			surfaceMetersPerSecondToRpm = builder.defineInRange("surfaceMetersPerSecondToRpm", 24.0d, 0.0d, Double.MAX_VALUE);
			maxGeneratedRpm = builder.defineInRange("maxGeneratedRpm", 256.0d, 0.0d, Double.MAX_VALUE);
			stressCapacityPerRpm = builder.defineInRange("stressCapacityPerRpm", 4.0d, 0.0d, Double.MAX_VALUE);
			maxStressCapacityPerSegment = builder.defineInRange("maxStressCapacityPerSegment", 1024.0d, 0.0d, Double.MAX_VALUE);
			surfaceSpeedDetectionInterval = builder.defineInRange("surfaceSpeedDetectionInterval", 10, 1, 20 * 60);
			maxPlayerSurfaceSpeed = builder.defineInRange("maxPlayerSurfaceSpeed", 1.0d, 0.0d, Double.MAX_VALUE);
			builder.pop();
		}
	}

	public static class PetriDish {
		public final ModConfigSpec.IntValue scanInterval;
		public final ModConfigSpec.IntValue scanRadius;
		public final ModConfigSpec.IntValue fluidPerHealth;
		public final ModConfigSpec.IntValue tankCapacity;
		public final ModConfigSpec.BooleanValue requireNearbyMatchingEntity;

		PetriDish(ModConfigSpec.Builder builder) {
			builder.push("petriDish");
			scanInterval = builder.defineInRange("scanInterval", 20, 1, Integer.MAX_VALUE);
			scanRadius = builder.defineInRange("scanRadius", 2, 0, 64);
			fluidPerHealth = builder.defineInRange("fluidPerHealth", 250, 1, Integer.MAX_VALUE);
			tankCapacity = builder.defineInRange("tankCapacity", 51200, 1, Integer.MAX_VALUE);
			requireNearbyMatchingEntity = builder.define("requireNearbyMatchingEntity", true);
			builder.pop();
		}
	}

	public static class SpiderAssemblyTable {
		public final ModConfigSpec.IntValue fluidCapacityPerLeg;
		public final ModConfigSpec.DoubleValue deployerBaseDuration;
		public final ModConfigSpec.IntValue sawFallbackDuration;
		public final ModConfigSpec.DoubleValue sawSpeedDivisor;

		SpiderAssemblyTable(ModConfigSpec.Builder builder) {
			builder.push("spiderAssemblyTable");
			fluidCapacityPerLeg = builder.defineInRange("fluidCapacityPerLeg", 1000, 1, Integer.MAX_VALUE);
			deployerBaseDuration = builder.defineInRange("deployerBaseDuration", 2000.0d, 1.0d, Double.MAX_VALUE);
			sawFallbackDuration = builder.defineInRange("sawFallbackDuration", 50, 1, Integer.MAX_VALUE);
			sawSpeedDivisor = builder.defineInRange("sawSpeedDivisor", 24.0d, 0.0001d, Double.MAX_VALUE);
			builder.pop();
		}
	}

	public static class CardboardBox {
		public final ModConfigSpec.ConfigValue<List<? extends String>> smallBoxEntityAllowlist;
		public final ModConfigSpec.BooleanValue largeBoxCreativeOnly;
		public final ModConfigSpec.BooleanValue lethalCaptureEnabled;
		public final ModConfigSpec.EnumValue<EntityListMode> largeBoxEntityListMode;
		public final ModConfigSpec.ConfigValue<List<? extends String>> largeBoxEntityAllowlist;
		public final ModConfigSpec.ConfigValue<List<? extends String>> largeBoxEntityDenylist;

		CardboardBox(ModConfigSpec.Builder builder) {
			builder.push("cardboardBox");
			smallBoxEntityAllowlist = defineResourceLocationList(builder, "smallBoxEntityAllowlist", List.of(
				"minecraft:slime",
				"minecraft:cat",
				"minecraft:bat",
				"minecraft:chicken",
				"minecraft:rabbit",
				"minecraft:silverfish",
				"minecraft:endermite",
				"minecraft:bee",
				"minecraft:parrot",
				"minecraft:allay",
				"minecraft:frog",
				"minecraft:ocelot",
				"minecraft:vex",
				"minecraft:magma_cube"));
			largeBoxCreativeOnly = builder.define("largeBoxCreativeOnly", true);
			lethalCaptureEnabled = builder.define("lethalCaptureEnabled", true);
			largeBoxEntityListMode = builder.defineEnum("largeBoxEntityListMode", EntityListMode.ALLOW_ALL);
			largeBoxEntityAllowlist = defineResourceLocationList(builder, "largeBoxEntityAllowlist", List.of());
			largeBoxEntityDenylist = defineResourceLocationList(builder, "largeBoxEntityDenylist", List.of());
			builder.pop();
		}
	}

	public static class SlimeMimic {
		public final ModConfigSpec.IntValue hauntCycleTicks;
		public final ModConfigSpec.BooleanValue replaceDropsWithSlime;
		public final ModConfigSpec.BooleanValue rewriteVillagerTrades;
		public final ModConfigSpec.IntValue villagerTradeMinSlimeBalls;
		public final ModConfigSpec.IntValue villagerTradeMaxSlimeBalls;
		public final ModConfigSpec.BooleanValue allowSpawnInjection;
		public final ModConfigSpec.EnumValue<EntityListMode> entityListMode;
		public final ModConfigSpec.ConfigValue<List<? extends String>> entityAllowlist;
		public final ModConfigSpec.ConfigValue<List<? extends String>> entityDenylist;

		SlimeMimic(ModConfigSpec.Builder builder) {
			builder.push("slimeMimic");
			hauntCycleTicks = builder.defineInRange("hauntCycleTicks", 100, 1, Integer.MAX_VALUE);
			replaceDropsWithSlime = builder.define("replaceDropsWithSlime", true);
			rewriteVillagerTrades = builder.define("rewriteVillagerTrades", true);
			villagerTradeMinSlimeBalls = builder.defineInRange("villagerTradeMinSlimeBalls", 1, 1, 64);
			villagerTradeMaxSlimeBalls = builder.defineInRange("villagerTradeMaxSlimeBalls", 3, 1, 64);
			allowSpawnInjection = builder.define("allowSpawnInjection", true);
			entityListMode = builder.defineEnum("entityListMode", EntityListMode.ALLOW_ALL);
			entityAllowlist = defineResourceLocationList(builder, "entityAllowlist", List.of());
			entityDenylist = defineResourceLocationList(builder, "entityDenylist", List.of());
			builder.pop();
		}
	}

	public static class GhastHotAirBalloon {
		public final ModConfigSpec.DoubleValue forwardAcceleration;
		public final ModConfigSpec.DoubleValue backwardAcceleration;
		public final ModConfigSpec.DoubleValue verticalAcceleration;
		public final ModConfigSpec.DoubleValue horizontalDrag;
		public final ModConfigSpec.DoubleValue verticalDrag;
		public final ModConfigSpec.DoubleValue maxHorizontalSpeed;
		public final ModConfigSpec.DoubleValue maxVerticalSpeed;
		public final ModConfigSpec.DoubleValue turnAcceleration;
		public final ModConfigSpec.DoubleValue turnBrake;
		public final ModConfigSpec.DoubleValue turnDirectionChangeBrake;
		public final ModConfigSpec.DoubleValue maxTurnSpeed;
		public final ModConfigSpec.IntValue inputTimeoutTicks;
		public final ModConfigSpec.IntValue magnetTimeoutTicks;
		public final ModConfigSpec.DoubleValue magnetBrakeDistance;
		public final ModConfigSpec.DoubleValue magnetMaxDistance;
		public final ModConfigSpec.DoubleValue assemblyStationSpeed;
		public final ModConfigSpec.IntValue attractPeriodTicks;
		public final ModConfigSpec.DoubleValue maxVelocityForAttractSqr;

		GhastHotAirBalloon(ModConfigSpec.Builder builder) {
			builder.push("ghastHotAirBalloon");
			forwardAcceleration = builder.defineInRange("forwardAcceleration", 0.04d, 0.0d, Double.MAX_VALUE);
			backwardAcceleration = builder.defineInRange("backwardAcceleration", 0.02d, 0.0d, Double.MAX_VALUE);
			verticalAcceleration = builder.defineInRange("verticalAcceleration", 0.03d, 0.0d, Double.MAX_VALUE);
			horizontalDrag = builder.defineInRange("horizontalDrag", 0.9d, 0.0d, 1.0d);
			verticalDrag = builder.defineInRange("verticalDrag", 0.85d, 0.0d, 1.0d);
			maxHorizontalSpeed = builder.defineInRange("maxHorizontalSpeed", 0.35d, 0.0d, Double.MAX_VALUE);
			maxVerticalSpeed = builder.defineInRange("maxVerticalSpeed", 0.25d, 0.0d, Double.MAX_VALUE);
			turnAcceleration = builder.defineInRange("turnAcceleration", 1.0d, 0.0d, Double.MAX_VALUE);
			turnBrake = builder.defineInRange("turnBrake", 4.0d, 0.0d, Double.MAX_VALUE);
			turnDirectionChangeBrake = builder.defineInRange("turnDirectionChangeBrake", 6.0d, 0.0d, Double.MAX_VALUE);
			maxTurnSpeed = builder.defineInRange("maxTurnSpeed", 6.0d, 0.0d, Double.MAX_VALUE);
			inputTimeoutTicks = builder.defineInRange("inputTimeoutTicks", 8, 1, Integer.MAX_VALUE);
			magnetTimeoutTicks = builder.defineInRange("magnetTimeoutTicks", 12, 1, Integer.MAX_VALUE);
			magnetBrakeDistance = builder.defineInRange("magnetBrakeDistance", 2.5d, 0.0001d, Double.MAX_VALUE);
			magnetMaxDistance = builder.defineInRange("magnetMaxDistance", 32.0d, 0.0d, Double.MAX_VALUE);
			assemblyStationSpeed = builder.defineInRange("assemblyStationSpeed", 0.5d, 0.0001d, Double.MAX_VALUE);
			attractPeriodTicks = builder.defineInRange("attractPeriodTicks", 20, 1, Integer.MAX_VALUE);
			maxVelocityForAttractSqr = builder.defineInRange("maxVelocityForAttractSqr", 1.0E-3d, 0.0d, Double.MAX_VALUE);
			builder.pop();
		}
	}

	public static class ButterCat {
		public final ModConfigSpec.IntValue maxButterCount;
		public final ModConfigSpec.IntValue butterDecayTicks;
		public final ModConfigSpec.IntValue butterForMaxRpm;
		public final ModConfigSpec.DoubleValue rpmPerButter;
		public final ModConfigSpec.DoubleValue maxGeneratedRpm;
		public final ModConfigSpec.DoubleValue stressCapacityPerRpm;
		public final ModConfigSpec.DoubleValue maxStressCapacity;
		public final ModConfigSpec.DoubleValue rotationAngularSpeed;
		public final ModConfigSpec.IntValue butterNutrition;
		public final ModConfigSpec.DoubleValue butterSaturation;
		public final ModConfigSpec.IntValue superButterNutrition;
		public final ModConfigSpec.DoubleValue superButterSaturation;
		public final ModConfigSpec.IntValue superButterRotationDuration;
		public final ModConfigSpec.IntValue superButterRotationAmplifier;
		public final ModConfigSpec.IntValue superButterLevitationDuration;
		public final ModConfigSpec.IntValue superButterLevitationAmplifier;
		public final ModConfigSpec.IntValue incompleteSuperButterNutrition;
		public final ModConfigSpec.DoubleValue incompleteSuperButterSaturation;
		public final ModConfigSpec.IntValue incompleteSuperButterRotationDuration;
		public final ModConfigSpec.IntValue incompleteSuperButterRotationAmplifier;

		ButterCat(ModConfigSpec.Builder builder) {
			builder.push("butterCat");
			maxButterCount = builder.defineInRange("maxButterCount", 16, 1, 8192);
			butterDecayTicks = builder.defineInRange("butterDecayTicks", 20 * 16, 1, Integer.MAX_VALUE);
			butterForMaxRpm = builder.defineInRange("butterForMaxRpm", 16, 1, Integer.MAX_VALUE);
			rpmPerButter = builder.defineInRange("rpmPerButter", 16.0d, 0.0d, Double.MAX_VALUE);
			maxGeneratedRpm = builder.defineInRange("maxGeneratedRpm", 256.0d, 0.0d, Double.MAX_VALUE);
			stressCapacityPerRpm = builder.defineInRange("stressCapacityPerRpm", 64.0d, 0.0d, Double.MAX_VALUE);
			maxStressCapacity = builder.defineInRange("maxStressCapacity", 16384.0d, 0.0d, Double.MAX_VALUE);
			rotationAngularSpeed = builder.defineInRange("rotationAngularSpeed", Math.PI / 2.0d, 0.0d, Double.MAX_VALUE);
			butterNutrition = builder.defineInRange("butterNutrition", 1, 0, 20);
			butterSaturation = builder.defineInRange("butterSaturation", 0.5d, 0.0d, 20.0d);
			superButterNutrition = builder.defineInRange("superButterNutrition", 18, 0, 20);
			superButterSaturation = builder.defineInRange("superButterSaturation", 0.5d, 0.0d, 20.0d);
			superButterRotationDuration = builder.defineInRange("superButterRotationDuration", 90, 0, Integer.MAX_VALUE);
			superButterRotationAmplifier = builder.defineInRange("superButterRotationAmplifier", 1, 0, 255);
			superButterLevitationDuration = builder.defineInRange("superButterLevitationDuration", 90, 0, Integer.MAX_VALUE);
			superButterLevitationAmplifier = builder.defineInRange("superButterLevitationAmplifier", 0, 0, 255);
			incompleteSuperButterNutrition = builder.defineInRange("incompleteSuperButterNutrition", 2, 0, 20);
			incompleteSuperButterSaturation = builder.defineInRange("incompleteSuperButterSaturation", 0.5d, 0.0d, 20.0d);
			incompleteSuperButterRotationDuration =
				builder.defineInRange("incompleteSuperButterRotationDuration", 30, 0, Integer.MAX_VALUE);
			incompleteSuperButterRotationAmplifier =
				builder.defineInRange("incompleteSuperButterRotationAmplifier", 2, 0, 255);
			builder.pop();
		}
	}

	public static class Automation {
		Automation(ModConfigSpec.Builder builder) {
			builder.push("automation");
			builder.pop();
		}
	}

	public static class UniversalJoint {
		public final ModConfigSpec.IntValue maxConnectionRange;
		public final ModConfigSpec.IntValue itemCooldownTicks;

		UniversalJoint(ModConfigSpec.Builder builder) {
			builder.push("universalJoint");
			maxConnectionRange = builder.defineInRange("maxConnectionRange", 2, 0, 64);
			itemCooldownTicks = builder.defineInRange("itemCooldownTicks", 5, 0, Integer.MAX_VALUE);
			builder.pop();
		}
	}

	public static class ClientUniversalJoint {
		public final ModConfigSpec.IntValue previewRange;

		ClientUniversalJoint(ModConfigSpec.Builder builder) {
			builder.push("universalJoint");
			previewRange = builder.defineInRange("previewRange", 16, 0, 256);
			builder.pop();
		}
	}

	public static class SquidPrinter {
		public final ModConfigSpec.IntValue cycleTicks;
		public final ModConfigSpec.IntValue cycleWaterCost;
		public final ModConfigSpec.IntValue tankCapacity;
		public final ModConfigSpec.IntValue finishingTicks;

		SquidPrinter(ModConfigSpec.Builder builder) {
			builder.push("squidPrinter");
			cycleTicks = builder.defineInRange("cycleTicks", 20, 1, Integer.MAX_VALUE);
			cycleWaterCost = builder.defineInRange("cycleWaterCost", 50, 0, Integer.MAX_VALUE);
			tankCapacity = builder.defineInRange("tankCapacity", 1000, 1, Integer.MAX_VALUE);
			finishingTicks = builder.defineInRange("finishingTicks", 5, 0, Integer.MAX_VALUE);
			builder.pop();
		}
	}

	public static class EvokerEnchantingChamber {
		public final ModConfigSpec.IntValue castDurationTicksPerLevel;
		public final ModConfigSpec.IntValue fluidPerLevel;
		public final ModConfigSpec.IntValue cacheCapacity;

		EvokerEnchantingChamber(ModConfigSpec.Builder builder) {
			builder.push("evokerEnchantingChamber");
			castDurationTicksPerLevel = builder.defineInRange("castDurationTicksPerLevel", 40, 1, Integer.MAX_VALUE);
			fluidPerLevel = builder.defineInRange("fluidPerLevel", 1000, 1, Integer.MAX_VALUE);
			cacheCapacity = builder.defineInRange("cacheCapacity", 4000, 1, Integer.MAX_VALUE);
			builder.pop();
		}
	}

	public static class SchrodingersCat {
		public final ModConfigSpec.IntValue defaultInterval;
		public final ModConfigSpec.IntValue maxInterval;
		public final ModConfigSpec.DoubleValue highSignalChance;

		SchrodingersCat(ModConfigSpec.Builder builder) {
			builder.push("schrodingersCat");
			defaultInterval = builder.defineInRange("defaultInterval", 20, 1, Integer.MAX_VALUE);
			maxInterval = builder.defineInRange("maxInterval", 60 * 20 * 60, 1, Integer.MAX_VALUE);
			highSignalChance = builder.defineInRange("highSignalChance", 0.5d, 0.0d, 1.0d);
			builder.pop();
		}
	}

	public static class BoneRatchet {
		public final ModConfigSpec.DoubleValue fallbackJamStressImpact;
		public final ModConfigSpec.DoubleValue creativeMotorMargin;

		BoneRatchet(ModConfigSpec.Builder builder) {
			builder.push("boneRatchet");
			fallbackJamStressImpact = builder.defineInRange("fallbackJamStressImpact", 20000.0d, 0.0d, Double.MAX_VALUE);
			creativeMotorMargin = builder.defineInRange("creativeMotorMargin", 1024.0d, 0.0d, Double.MAX_VALUE);
			builder.pop();
		}
	}

	public static class BasinEntityProcessing {
		public final ModConfigSpec.DoubleValue entityScanHeight;

		BasinEntityProcessing(ModConfigSpec.Builder builder) {
			builder.push("basinEntityProcessing");
			entityScanHeight = builder.defineInRange("entityScanHeight", 1.25d, 0.0d, 16.0d);
			builder.pop();
		}
	}

	public static class SlimeClutch {
		public final ModConfigSpec.IntValue recheckPeriod;
		public final ModConfigSpec.IntValue maxWalk;
		public final ModConfigSpec.BooleanValue enableSoftOverloadCheck;

		SlimeClutch(ModConfigSpec.Builder builder) {
			builder.push("slimeClutch");
			recheckPeriod = builder.defineInRange("recheckPeriod", 20, 1, Integer.MAX_VALUE);
			maxWalk = builder.defineInRange("maxWalk", 1024, 1, Integer.MAX_VALUE);
			enableSoftOverloadCheck = builder.define("enableSoftOverloadCheck", true);
			builder.pop();
		}
	}

	public static class LiquidLivingSlime {
		public final ModConfigSpec.IntValue sourceHitsToBreak;
		public final ModConfigSpec.BooleanValue dropSlimeBallWhenSourceBreaks;

		LiquidLivingSlime(ModConfigSpec.Builder builder) {
			builder.push("liquidLivingSlime");
			sourceHitsToBreak = builder.defineInRange("sourceHitsToBreak", 4, 1, 64);
			dropSlimeBallWhenSourceBreaks = builder.define("dropSlimeBallWhenSourceBreaks", true);
			builder.pop();
		}
	}

	public static class FixedCarrotFishingRod {
		public final ModConfigSpec.DoubleValue searchRange;
		public final ModConfigSpec.DoubleValue speedModifier;
		public final ModConfigSpec.DoubleValue stopDistance;
		public final ModConfigSpec.IntValue searchCooldown;
		public final ModConfigSpec.IntValue stopCooldown;

		FixedCarrotFishingRod(ModConfigSpec.Builder builder) {
			builder.push("fixedCarrotFishingRod");
			searchRange = builder.defineInRange("searchRange", 10.0d, 0.0d, 128.0d);
			speedModifier = builder.defineInRange("speedModifier", 1.2d, 0.0d, Double.MAX_VALUE);
			stopDistance = builder.defineInRange("stopDistance", 2.5d, 0.0d, 64.0d);
			searchCooldown = builder.defineInRange("searchCooldown", 20, 0, Integer.MAX_VALUE);
			stopCooldown = builder.defineInRange("stopCooldown", 100, 0, Integer.MAX_VALUE);
			builder.pop();
		}
	}

	public static class BeltParticles {
		public final ModConfigSpec.DoubleValue slimeBeltBaseChance;
		public final ModConfigSpec.DoubleValue slimeBeltLengthChance;
		public final ModConfigSpec.DoubleValue slimeBeltSpeedChance;
		public final ModConfigSpec.DoubleValue slimeBeltMaxChance;
		public final ModConfigSpec.DoubleValue magmaBeltBaseChance;
		public final ModConfigSpec.DoubleValue magmaBeltLengthChance;
		public final ModConfigSpec.DoubleValue magmaBeltMaxChance;

		BeltParticles(ModConfigSpec.Builder builder) {
			builder.push("beltParticles");
			slimeBeltBaseChance = builder.defineInRange("slimeBeltBaseChance", 0.035d, 0.0d, 1.0d);
			slimeBeltLengthChance = builder.defineInRange("slimeBeltLengthChance", 0.008d, 0.0d, 1.0d);
			slimeBeltSpeedChance = builder.defineInRange("slimeBeltSpeedChance", 0.12d, 0.0d, 1.0d);
			slimeBeltMaxChance = builder.defineInRange("slimeBeltMaxChance", 0.18d, 0.0d, 1.0d);
			magmaBeltBaseChance = builder.defineInRange("magmaBeltBaseChance", 0.025d, 0.0d, 1.0d);
			magmaBeltLengthChance = builder.defineInRange("magmaBeltLengthChance", 0.006d, 0.0d, 1.0d);
			magmaBeltMaxChance = builder.defineInRange("magmaBeltMaxChance", 0.16d, 0.0d, 1.0d);
			builder.pop();
		}
	}

	public static class BufferPad {
		public final ModConfigSpec.DoubleValue escapePushSpeed;
		public final ModConfigSpec.DoubleValue movementEpsilon;

		BufferPad(ModConfigSpec.Builder builder) {
			builder.push("bufferPad");
			escapePushSpeed = builder.defineInRange("escapePushSpeed", 0.05d, 0.0d, Double.MAX_VALUE);
			movementEpsilon = builder.defineInRange("movementEpsilon", 1.0E-4d, 0.0d, 1.0d);
			builder.pop();
		}
	}

	public static class ShulkerPackager {
		public final ModConfigSpec.IntValue transferDelay;
		public final ModConfigSpec.IntValue connectionRange;

		ShulkerPackager(ModConfigSpec.Builder builder) {
			builder.push("shulkerPackager");
			transferDelay = builder.defineInRange("transferDelay", 8, 1, Integer.MAX_VALUE);
			connectionRange = builder.defineInRange("connectionRange", 5, 0, 64);
			builder.pop();
		}
	}

	private static ModConfigSpec.ConfigValue<List<? extends String>> defineResourceLocationList(
		ModConfigSpec.Builder builder, String path, List<? extends String> defaults) {
		return builder.defineListAllowEmpty(path, defaults, value -> value instanceof String string
			&& ResourceLocation.tryParse(string) != null);
	}

	public static boolean isEntityTypeAllowed(EntityType<?> type, EntityListMode mode,
		List<? extends String> allowlist, List<? extends String> denylist) {
		ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if (id == null)
			return mode == EntityListMode.ALLOW_ALL;
		return switch (mode) {
		case ALLOW_ALL -> true;
		case ALLOWLIST -> containsResourceLocation(allowlist, id);
		case DENYLIST -> !containsResourceLocation(denylist, id);
		};
	}

	public static boolean containsResourceLocation(List<? extends String> values, ResourceLocation id) {
		for (String value : values) {
			ResourceLocation parsed = ResourceLocation.tryParse(value);
			if (id.equals(parsed))
				return true;
		}
		return false;
	}
}
