package com.nobodiiiii.createbiotech.registry;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.yision.allay.item.allaycourier.AllayCourierCargo;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CBDataComponents {
	private static final DeferredRegister.DataComponents COMPONENTS =
		DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CreateBiotech.MOD_ID);

	public static final Supplier<DataComponentType<AllayCourierCargo>> ALLAY_COURIER_CARGO =
		COMPONENTS.registerComponentType("allay_courier_cargo",
			builder -> builder.persistent(AllayCourierCargo.CODEC)
				.networkSynchronized(AllayCourierCargo.STREAM_CODEC));

	public static final Supplier<DataComponentType<Integer>> ALLAY_COURIER_HEADING =
		COMPONENTS.registerComponentType("allay_courier_heading",
			builder -> builder.persistent(Codec.INT)
				.networkSynchronized(ByteBufCodecs.INT));

	private CBDataComponents() {
	}

	public static void register(IEventBus modEventBus) {
		COMPONENTS.register(modEventBus);
	}
}
