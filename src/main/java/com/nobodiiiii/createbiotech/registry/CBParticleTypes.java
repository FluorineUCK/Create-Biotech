package com.nobodiiiii.createbiotech.registry;

import com.mojang.serialization.MapCodec;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicConeWaveParticleOption;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CBParticleTypes {

	public static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
		DeferredRegister.create(Registries.PARTICLE_TYPE, CreateBiotech.MOD_ID);

	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> STRAIGHT_ENCHANT =
		PARTICLE_TYPES.register("straight_enchant", () -> new SimpleParticleType(false));
	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ALLAY_COURIER_NOTE =
		PARTICLE_TYPES.register("allay_courier_note", () -> new SimpleParticleType(false));
	public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FROG_PORTAL =
		PARTICLE_TYPES.register("frog_portal", () -> new SimpleParticleType(false));
	public static final DeferredHolder<ParticleType<?>, ParticleType<SonicConeWaveParticleOption>> SONIC_CONE_WAVE =
		PARTICLE_TYPES.register("sonic_cone_wave", () -> new ParticleType<>(true) {
			@Override
			public MapCodec<SonicConeWaveParticleOption> codec() {
				return SonicConeWaveParticleOption.CODEC;
			}

			@Override
			public StreamCodec<? super RegistryFriendlyByteBuf, SonicConeWaveParticleOption> streamCodec() {
				return SonicConeWaveParticleOption.STREAM_CODEC;
			}
		});

	private CBParticleTypes() {
	}

	public static void register(IEventBus modEventBus) {
		PARTICLE_TYPES.register(modEventBus);
	}
}
