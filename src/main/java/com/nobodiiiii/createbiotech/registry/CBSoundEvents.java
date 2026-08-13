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
		SOUND_EVENTS.register("sonic_dog_cannon.growl1",
			() -> SoundEvent.createVariableRangeEvent(CreateBiotech.asResource("sonic_dog_cannon.growl1")));

	private CBSoundEvents() {}

	public static void register(IEventBus modEventBus) {
		SOUND_EVENTS.register(modEventBus);
	}
}
