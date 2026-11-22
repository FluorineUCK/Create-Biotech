package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleOutputBurstTest {
    @Test void whitelistEnablesOnlyActualSimpleTypesAndCannotBypassTypedAdapters() {
        var extra = ResourceLocation.parse("example:marker");
        var complex = ResourceLocation.parse("example:complex");
        var unknown = ResourceLocation.parse("example:missing");
        var simpleType = new SimpleParticleType(false);
        var complexType = new ParticleType<DustParticleOptions>(false) {
            @Override public com.mojang.serialization.MapCodec<DustParticleOptions> codec() { return DustParticleOptions.CODEC; }
            @Override public net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, DustParticleOptions> streamCodec() {
                return DustParticleOptions.STREAM_CODEC;
            }
        };
        Map<ResourceLocation, ParticleType<?>> registered = Map.of(extra, simpleType, complex, complexType);
        var limits = new ParticleOutputBurst.Limits(16, 3, 1, Set.of(extra, complex, unknown));
        var allowed = ParticleOutputBurst.decode(request(extra, empty()), limits, id -> Optional.ofNullable(registered.get(id)));
        assertSame(simpleType, allowed.particle());
        assertFailure("unsupported_particle_parameters", complex, limits, registered);
        assertFailure("unknown_particle", unknown, limits, registered);
        assertFailure("particle_not_allowed", extra, new ParticleOutputBurst.Limits(16, 3, 1, Set.of()), registered);
    }

    @Test void checkedDustAndBoundedSamplingNeverChoosePositionsOutsideTheEmitterRadius() {
        var options = new DataValue.RecordValue(Map.of("red", new DataValue.DecimalValue(0.1), "green", new DataValue.IntegerValue(1),
            "blue", new DataValue.DecimalValue(0.25), "scale", new DataValue.DecimalValue(1.5)));
        var frame = request(ResourceLocation.withDefaultNamespace("dust"), options);
        var limits = new ParticleOutputBurst.Limits(16, 3, 1, Set.of());
        var data = new java.util.LinkedHashMap<>(frame.fields());
        data.put("offset", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(2),
            "y", new DataValue.IntegerValue(0), "z", new DataValue.IntegerValue(0))));
        data.put("spread", new DataValue.DecimalValue(0.5));
        data.put("speed", new DataValue.DecimalValue(0.75));
        var burst = ParticleOutputBurst.decode(new DataValue.RecordValue(data), limits, id -> fail("Dust must use the typed adapter"));
        var dust = assertInstanceOf(DustParticleOptions.class, burst.particle());
        assertEquals(1.5f, dust.getScale());
        assertEquals(1, dust.getColor().y(), 0);
        var random = net.minecraft.util.RandomSource.create(907L);
        for (int i = 0; i < 128; i++) {
            assertTrue(burst.sampleOffset(random).length() <= limits.radius());
            assertTrue(burst.sampleVelocity(random).length() <= burst.speed() + 1.0e-9);
        }

        data.put("spread", new DataValue.DecimalValue(1));
        var outOfRange = assertThrows(IllegalArgumentException.class,
            () -> ParticleOutputBurst.decode(new DataValue.RecordValue(data), limits, id -> Optional.empty()));
        assertEquals("particle_range_limit", outOfRange.getMessage());
        data.put("spread", new DataValue.DecimalValue(0.5));
        data.put("options", new DataValue.RecordValue(Map.of("red", new DataValue.IntegerValue(10))));
        var badDust = assertThrows(IllegalArgumentException.class,
            () -> ParticleOutputBurst.decode(new DataValue.RecordValue(data), limits, id -> Optional.empty()));
        assertEquals("invalid_particle_parameters", badDust.getMessage());
    }

    private static void assertFailure(String code, ResourceLocation id, ParticleOutputBurst.Limits limits,
            Map<ResourceLocation, ParticleType<?>> registered) {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> ParticleOutputBurst.decode(request(id, empty()), limits, key -> Optional.ofNullable(registered.get(key))));
        assertEquals(code, failure.getMessage());
    }

    static DataValue.RecordValue request(ResourceLocation id, DataValue.RecordValue options) {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue(id.toString()), "count", new DataValue.IntegerValue(3),
            "offset", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(0), "y", new DataValue.IntegerValue(0),
                "z", new DataValue.IntegerValue(0))),
            "spread", new DataValue.DecimalValue(0.25), "speed", new DataValue.IntegerValue(0), "options", options));
    }

    private static DataValue.RecordValue empty() { return new DataValue.RecordValue(Map.of()); }
}
