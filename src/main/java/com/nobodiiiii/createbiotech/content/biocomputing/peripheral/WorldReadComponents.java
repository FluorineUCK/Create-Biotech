package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import net.minecraft.world.level.LightLayer;

/** General world and target-position facts, captured as ordinary immutable values. */
public final class WorldReadComponents {
    public static final String WORLD = "create_biotech:world", ENVIRONMENT = "create_biotech:environment";

    private WorldReadComponents() {}

    public static void register(ReadComponentRegistry.Builder<BlockReadContext> builder) {
        var weather = new DataType.RecordType(Map.of("raining", BOOLEAN, "thundering", BOOLEAN,
            "rain_level", DECIMAL, "thunder_level", DECIMAL));
        builder.register(BuiltinReadComponents.provider(WORLD, new DataType.RecordType(Map.of(
            "dimension", TEXT, "game_time", INTEGER, "day_time", INTEGER, "day_cycle_tick", INTEGER,
            "is_day", BOOLEAN, "is_night", BOOLEAN, "weather", weather)),
            ReadComponentDescriptor.UpdateHint.TICK, WorldReadComponents::world));
        builder.register(BuiltinReadComponents.provider(ENVIRONMENT, new DataType.RecordType(Map.of(
            "biome", ANY, "block_light", INTEGER, "sky_light", INTEGER, "light", INTEGER, "sky_visible", BOOLEAN)),
            ReadComponentDescriptor.UpdateHint.TICK, WorldReadComponents::environment));
    }

    private static DataValue.RecordValue world(BlockReadContext context) {
        var level = context.level();
        long dayTime = level.getDayTime();
        return new DataValue.RecordValue(Map.of(
            "dimension", new DataValue.TextValue(level.dimension().location().toString()),
            "game_time", new DataValue.IntegerValue(level.getGameTime()),
            "day_time", new DataValue.IntegerValue(dayTime),
            // This is the raw day-time remainder, not the sky angle of fixed-time dimensions.
            "day_cycle_tick", new DataValue.IntegerValue(Math.floorMod(dayTime, 24000L)),
            "is_day", new DataValue.BooleanValue(level.isDay()),
            "is_night", new DataValue.BooleanValue(level.isNight()),
            "weather", new DataValue.RecordValue(Map.of(
                "raining", new DataValue.BooleanValue(level.isRaining()),
                "thundering", new DataValue.BooleanValue(level.isThundering()),
                "rain_level", new DataValue.DecimalValue(level.getRainLevel(1.0F)),
                "thunder_level", new DataValue.DecimalValue(level.getThunderLevel(1.0F))))));
    }

    private static DataValue.RecordValue environment(BlockReadContext context) {
        var level = context.level();
        var target = context.target();
        DataValue biome = level.getBiome(target).unwrapKey()
            .<DataValue>map(key -> new DataValue.TextValue(key.location().toString()))
            .orElseGet(() -> new DataValue.ErrorValue("unregistered_biome", ""));
        return new DataValue.RecordValue(Map.of(
            "biome", biome,
            "block_light", new DataValue.IntegerValue(level.getBrightness(LightLayer.BLOCK, target)),
            "sky_light", new DataValue.IntegerValue(level.getBrightness(LightLayer.SKY, target)),
            "light", new DataValue.IntegerValue(level.getMaxLocalRawBrightness(target)),
            "sky_visible", new DataValue.BooleanValue(level.canSeeSky(target))));
    }
}
