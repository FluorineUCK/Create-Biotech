package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CBSoundEvents {
	private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
		DeferredRegister.create(Registries.SOUND_EVENT, CreateBiotech.MOD_ID);

	/** A controllable event containing only vanilla {@code mob/wolf/growl1}. */
	public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_DOG_CANNON_GROWL1 =
		register("sonic_dog_cannon.growl1");
	public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_DOG_CANNON_VOICE_PACK_CHARGE_START =
		register("sonic_dog_cannon.voice_pack.charge_start");
	public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_DOG_CANNON_VOICE_PACK_CHARGE_LOOP =
		register("sonic_dog_cannon.voice_pack.charge_loop");
	public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_DOG_CANNON_VOICE_PACK_FIRE_FULL =
		register("sonic_dog_cannon.voice_pack.fire_full");
	public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_DOG_CANNON_VOICE_PACK_FIRE_PARTIAL =
		register("sonic_dog_cannon.voice_pack.fire_partial");

	private CBSoundEvents() {}

	private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
		return SOUND_EVENTS.register(name,
			() -> SoundEvent.createVariableRangeEvent(CreateBiotech.asResource(name)));
	}

	public static void register(IEventBus modEventBus) {
		SOUND_EVENTS.register(modEventBus);
	}
}
