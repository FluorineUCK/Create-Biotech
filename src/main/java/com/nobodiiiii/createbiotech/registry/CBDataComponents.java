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

	public static final Supplier<DataComponentType<net.minecraft.world.item.component.CustomData>> BIOCOMPUTING_FILTER_RULES =
		COMPONENTS.registerComponentType("biocomputing_filter_rules",
			builder -> builder.persistent(net.minecraft.world.item.component.CustomData.CODEC)
				.networkSynchronized(net.minecraft.world.item.component.CustomData.STREAM_CODEC));

	/** Physical volume name, independent of the book's existing contents and original pages. */
	public static final Supplier<DataComponentType<String>> BIOCOMPUTING_LIBRARY_VOLUME =
		COMPONENTS.registerComponentType("biocomputing_library_volume",
			builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

	/** Raw carrier preserves unknown schemas; services validate entries without rewriting malformed data. */
	public static final Supplier<DataComponentType<net.minecraft.world.item.component.CustomData>> BIOCOMPUTING_LIBRARY_CONTENTS =
		COMPONENTS.registerComponentType("biocomputing_library_contents",
			builder -> builder.persistent(net.minecraft.world.item.component.CustomData.CODEC)
				.networkSynchronized(net.minecraft.world.item.component.CustomData.STREAM_CODEC));

	/** Server-owned service archive; item viewers receive only a presence marker, never queued requests. */
	public static final Supplier<DataComponentType<net.minecraft.world.item.component.CustomData>> BIOCOMPUTING_LIBRARY_SERVICE =
		COMPONENTS.registerComponentType("biocomputing_library_service",
			builder -> builder.persistent(net.minecraft.world.item.component.CustomData.CODEC)
				.networkSynchronized(net.minecraft.network.codec.StreamCodec.of((buffer, value) -> {},
					buffer -> net.minecraft.world.item.component.CustomData.EMPTY)));

	public static final Supplier<DataComponentType<AllayCourierCargo>> ALLAY_COURIER_CARGO =
		COMPONENTS.registerComponentType("allay_courier_cargo",
			builder -> builder.persistent(AllayCourierCargo.CODEC)
				.networkSynchronized(AllayCourierCargo.STREAM_CODEC));

	public static final Supplier<DataComponentType<Integer>> ALLAY_COURIER_HEADING =
		COMPONENTS.registerComponentType("allay_courier_heading",
			builder -> builder.persistent(Codec.INT)
				.networkSynchronized(ByteBufCodecs.INT));

	/**
	 * The Frog Stomach room index bound to a {@code GIANT_FROG}. Travels on the dropped item so
	 * breaking and re-placing the frog keeps the same private room.
	 */
	public static final Supplier<DataComponentType<Long>> FROG_STOMACH_SPACE =
		COMPONENTS.registerComponentType("frog_stomach_space",
			builder -> builder.persistent(Codec.LONG)
				.networkSynchronized(ByteBufCodecs.VAR_LONG));

	private CBDataComponents() {
	}

	public static void register(IEventBus modEventBus) {
		COMPONENTS.register(modEventBus);
	}
}
