package com.nobodiiiii.createbiotech.content.squidprinter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nobodiiiii.createbiotech.registry.CBParticleTypes;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Carries how far a single ink particle may sink before it vanishes.
 *
 * <p>The limit travels with the particle rather than living in the particle
 * class because the printer releases ink from two different heights, and both
 * streams have to stop at the same depth below the machine.
 *
 * @param fallLimit distance in blocks, measured down from where the ink spawned
 */
public record SquidPrinterInkParticleOption(float fallLimit) implements ParticleOptions {

	public static final MapCodec<SquidPrinterInkParticleOption> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(Codec.FLOAT.fieldOf("fall_limit")
			.forGetter(SquidPrinterInkParticleOption::fallLimit))
			.apply(instance, SquidPrinterInkParticleOption::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, SquidPrinterInkParticleOption> STREAM_CODEC =
		ByteBufCodecs.FLOAT.map(SquidPrinterInkParticleOption::new, SquidPrinterInkParticleOption::fallLimit)
			.cast();

	public SquidPrinterInkParticleOption {
		fallLimit = Math.max(0.0f, fallLimit);
	}

	@Override
	public ParticleType<?> getType() {
		return CBParticleTypes.SQUID_PRINTER_INK.get();
	}
}
