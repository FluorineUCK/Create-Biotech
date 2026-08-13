package com.nobodiiiii.createbiotech.client;

import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonItem;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicDogCannonUpgrade;
import com.nobodiiiii.createbiotech.registry.CBSoundEvents;

import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class SonicDogCannonChargeSound extends EntityBoundSoundInstance {
	private static final int USE_STATE_GRACE_TICKS = 3;

	private final Player player;
	private int age;

	public SonicDogCannonChargeSound(Player player) {
		super(CBSoundEvents.SONIC_DOG_CANNON_GROWL1.get(), SoundSource.PLAYERS,
			1.0f, 1.0f, player, player.getRandom().nextLong());
		this.player = player;
	}

	@Override
	public void tick() {
		super.tick();
		age++;
		if (isStopped() || age <= USE_STATE_GRACE_TICKS)
			return;

		if (!player.isUsingItem()
			|| !(player.getUseItem().getItem() instanceof SonicDogCannonItem)
			|| SonicDogCannonUpgrade.VOICE_PACK.isInstalled(player.getUseItem()))
			stopSound();
	}

	public void stopSound() {
		stop();
	}
}
