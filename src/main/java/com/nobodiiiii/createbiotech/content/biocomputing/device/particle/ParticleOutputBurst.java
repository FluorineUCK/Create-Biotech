package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Validated particle data, relative to the emitter. Never decodes a registry-supplied payload. */
public final class ParticleOutputBurst {
    public static final int MAX_COUNT = 128, MAX_RADIUS = 8, MAX_SPEED = 2, MAX_SIMPLE_TYPES = 128;
    private static final int MAX_PARTICLE_ID_CHARS = 128;
    private static final ResourceLocation DUST = ResourceLocation.withDefaultNamespace("dust");
    private static final double SPREAD_TO_RADIUS = Math.sqrt(3);
    private static final Set<String> FIELDS = Set.of("kind", "count", "offset", "spread", "speed", "options");

    public record Limits(int count, double radius, double speed, Set<ResourceLocation> allowedSimple) {
        public Limits {
            allowedSimple = Set.copyOf(allowedSimple);
            if (count < 1 || count > MAX_COUNT || !(radius > 0 && radius <= MAX_RADIUS)
                || !(speed >= 0 && speed <= MAX_SPEED) || allowedSimple.size() > MAX_SIMPLE_TYPES)
                throw new IllegalArgumentException("invalid_particle_limits");
        }
    }

    private final ParticleOptions particle;
    private final int count;
    private final Vec3 offset;
    private final double spread, speed;

    private ParticleOutputBurst(ParticleOptions particle, int count, Vec3 offset, double spread, double speed) {
        this.particle = particle;
        this.count = count;
        this.offset = offset;
        this.spread = spread;
        this.speed = speed;
    }

    public ParticleOptions particle() { return particle; }
    public int count() { return count; }
    public Vec3 offset() { return offset; }
    public double spread() { return spread; }
    public double speed() { return speed; }

    /** Uniform scatter has a strict bound, unlike the vanilla bulk packet's Gaussian offsets. */
    Vec3 sampleOffset(RandomSource random) {
        return spread == 0 ? offset : offset.add(scatter(random, spread));
    }

    Vec3 sampleVelocity(RandomSource random) {
        return speed == 0 ? Vec3.ZERO : scatter(random, speed / SPREAD_TO_RADIUS);
    }

    private static Vec3 scatter(RandomSource random, double radius) {
        return new Vec3((2 * random.nextDouble() - 1) * radius,
            (2 * random.nextDouble() - 1) * radius, (2 * random.nextDouble() - 1) * radius);
    }

    public static boolean validParticleId(Object value) {
        return value instanceof String text && particleId(text) != null;
    }

    private static ResourceLocation particleId(String value) {
        var id = value.length() <= MAX_PARTICLE_ID_CHARS ? ResourceLocation.tryParse(value) : null;
        return id != null && id.toString().equals(value) ? id : null;
    }

    public static ParticleOutputBurst decode(DataValue.RecordValue record, Limits limits,
            Function<ResourceLocation, Optional<ParticleType<?>>> registry) {
        if (!record.fields().keySet().equals(FIELDS)
            || !(record.field("kind") instanceof DataValue.TextValue kind)
            || !(record.field("count") instanceof DataValue.IntegerValue count)
            || !(record.field("offset") instanceof DataValue.RecordValue offset)
            || !offset.fields().keySet().equals(Set.of("x", "y", "z"))
            || !(record.field("options") instanceof DataValue.RecordValue options))
            throw new IllegalArgumentException("invalid_particle_parameters");

        var relative = new Vec3(number(offset.field("x")), number(offset.field("y")), number(offset.field("z")));
        double spread = number(record.field("spread")), speed = number(record.field("speed"));
        if (count.value() < 1 || count.value() > limits.count())
            throw new IllegalArgumentException("particle_count_limit");
        if (spread < 0 || relative.length() + spread * SPREAD_TO_RADIUS > limits.radius())
            throw new IllegalArgumentException("particle_range_limit");
        if (speed < 0 || speed > limits.speed())
            throw new IllegalArgumentException("particle_speed_limit");

        var id = particleId(kind.value());
        if (id == null) throw new IllegalArgumentException("invalid_particle_kind");
        ParticleOptions particle;
        if (id.equals(DUST)) {
            particle = dust(options);
        } else {
            if (!limits.allowedSimple().contains(id)) throw new IllegalArgumentException("particle_not_allowed");
            var type = registry.apply(id).orElseThrow(() -> new IllegalArgumentException("unknown_particle"));
            if (!(type instanceof SimpleParticleType simple)) throw new IllegalArgumentException("unsupported_particle_parameters");
            if (!options.fields().isEmpty()) throw new IllegalArgumentException("invalid_particle_parameters");
            particle = simple;
        }
        return new ParticleOutputBurst(particle, (int)count.value(), relative, spread, speed);
    }

    private static DustParticleOptions dust(DataValue.RecordValue options) {
        if (!options.fields().keySet().equals(Set.of("red", "green", "blue", "scale")))
            throw new IllegalArgumentException("invalid_particle_parameters");
        float red = channel(options.field("red")), green = channel(options.field("green")), blue = channel(options.field("blue"));
        double scale = number(options.field("scale"));
        // Reject rather than silently applying vanilla's clamp.
        if (scale < 0.01 || scale > 4) throw new IllegalArgumentException("invalid_particle_parameters");
        return new DustParticleOptions(new Vector3f(red, green, blue), (float)scale);
    }

    private static float channel(DataValue value) {
        double channel = number(value);
        if (channel < 0 || channel > 1) throw new IllegalArgumentException("invalid_particle_parameters");
        return (float)channel;
    }

    private static double number(DataValue value) {
        if (value instanceof DataValue.IntegerValue integer) return integer.value();
        if (value instanceof DataValue.DecimalValue decimal) return decimal.value();
        throw new IllegalArgumentException("invalid_particle_parameters");
    }
}
