package com.nobodiiiii.createbiotech.content.buttercat.item;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class SuperButterMelody {
	private static final float VOLUME = 1.0f;

	// 5' 2'' 1'' 3' 0 1'' 2'' 3'' 2'' 1'' 2'' | 5' 2'' 1'' 3'
	// Eighth notes are four ticks, sixteenth notes are two ticks, and 0 is an eighth rest.
	private static final Note[] NOTES = {
		new Note(0, 13),
		new Note(4, 20),
		new Note(8, 18),
		new Note(12, 10),
		new Note(20, 18),
		new Note(22, 20),
		new Note(24, 22),
		new Note(26, 20),
		new Note(28, 18),
		new Note(30, 20),
		new Note(32, 13),
		new Note(36, 20),
		new Note(40, 18),
		new Note(44, 10)
	};

	private static final List<Playback> PLAYBACKS = new ArrayList<>();

	private SuperButterMelody() {}

	public static void start(LivingEntity source) {
		if (!(source.level() instanceof ServerLevel level))
			return;
		PLAYBACKS.add(new Playback(source, level.getServer().getTickCount()));
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		MinecraftServer server = event.getServer();
		int currentTick = server.getTickCount();
		Iterator<Playback> iterator = PLAYBACKS.iterator();
		while (iterator.hasNext()) {
			Playback playback = iterator.next();
			if (!playback.tick(server, currentTick))
				iterator.remove();
		}
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		PLAYBACKS.clear();
	}

	private record Note(int tick, int noteBlockPitch) {
		private float soundPitch() {
			return (float) Math.pow(2.0, (noteBlockPitch - 12) / 12.0);
		}
	}

	private static final class Playback {
		private final LivingEntity source;
		private final int startTick;
		private int nextNote;

		private Playback(LivingEntity source, int startTick) {
			this.source = source;
			this.startTick = startTick;
		}

		private boolean tick(MinecraftServer server, int currentTick) {
			if (source.isRemoved() || !(source.level() instanceof ServerLevel level)
				|| level.getServer() != server)
				return false;

			int elapsedTicks = currentTick - startTick;
			while (nextNote < NOTES.length && NOTES[nextNote].tick() <= elapsedTicks) {
				Note note = NOTES[nextNote++];
				level.playSound(null, source.getX(), source.getY(), source.getZ(),
					SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, VOLUME, note.soundPitch());
			}
			return nextNote < NOTES.length;
		}
	}
}
