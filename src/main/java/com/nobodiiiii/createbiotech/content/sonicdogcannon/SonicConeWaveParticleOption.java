package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.registry.CBParticleTypes;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

import org.joml.Vector3f;

public record SonicConeWaveParticleOption(Vector3f direction, int delay) implements ParticleOptions {

	public static final MapCodec<SonicConeWaveParticleOption> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			ExtraCodecs.VECTOR3F.fieldOf("direction").forGetter(SonicConeWaveParticleOption::direction),
			Codec.INT.fieldOf("delay").forGetter(SonicConeWaveParticleOption::delay))
			.apply(instance, SonicConeWaveParticleOption::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, SonicConeWaveParticleOption> STREAM_CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VECTOR3F, SonicConeWaveParticleOption::direction,
			ByteBufCodecs.VAR_INT, SonicConeWaveParticleOption::delay,
			SonicConeWaveParticleOption::new);

	public SonicConeWaveParticleOption {
		direction = new Vector3f(direction);
		delay = Math.max(0, delay);
	}

	@Override
	public ParticleType<?> getType() {
		return CBParticleTypes.SONIC_CONE_WAVE.get();
	}
}
