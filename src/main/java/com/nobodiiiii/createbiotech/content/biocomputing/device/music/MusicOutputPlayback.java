package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.NoteBlockEvent;

/** Only the adjacent note block can emit a note. Neither this block nor its neighbor is retuned. */
final class MusicOutputPlayback {
    private MusicOutputPlayback() {}

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
            && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    static String targetStatus(ServerLevel level, BlockPos target) {
        if (!loaded(level, target)) return "target_unloaded";
        return level.getBlockState(target).is(Blocks.NOTE_BLOCK) ? "ready" : "target_missing";
    }

    static String play(ServerLevel level, BlockPos target, MusicOutputState.Note note, BooleanSupplier authorized) {
        var status = targetStatus(level, target);
        if (!status.equals("ready")) return status;
        var above = target.above();
        if (!loaded(level, above)) return "target_unloaded";
        var actual = level.getBlockState(target);
        if (!unobstructed(note.instrument(), level.getBlockState(above))) return "obstructed";

        var event = new NoteBlockEvent.Play(level, target, actual, note.pitch(), note.instrument());
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) return "cancelled";
        if (!authorized.getAsBoolean()) return "access_denied";
        if (!loaded(level, target) || !loaded(level, above)) return "target_unloaded";
        if (level.getBlockState(target) != actual) return "target_changed";

        var instrument = event.getInstrument();
        int pitch = event.getVanillaNoteId();
        if (instrument == null || pitch < 0 || pitch > 24) return "invalid_note";
        if (!unobstructed(instrument, level.getBlockState(above))) return "obstructed";
        Holder<SoundEvent> sound;
        if (instrument.hasCustomSound()) {
            if (!(level.getBlockEntity(above) instanceof SkullBlockEntity head)
                || head.getNoteBlockSound() == null) return "missing_head_sound";
            sound = Holder.direct(SoundEvent.createVariableRangeEvent(head.getNoteBlockSound()));
        } else sound = instrument.getSoundEvent();

        double x = target.getX() + 0.5, y = target.getY() + 0.5, z = target.getZ() + 0.5;
        level.playSeededSound(null, x, y, z, sound, SoundSource.RECORDS, NoteBlock.NOTE_VOLUME,
            instrument.isTunable() ? NoteBlock.getPitchFromNote(pitch) : 1.0F, level.random.nextLong());
        if (instrument.isTunable())
            level.sendParticles(ParticleTypes.NOTE, x, target.getY() + 1.2, z, 0, pitch / 24.0, 0, 0, 1);
        level.gameEvent(null, GameEvent.NOTE_BLOCK_PLAY, target);
        return "played";
    }

    private static boolean unobstructed(NoteBlockInstrument instrument, BlockState above) {
        return instrument.worksAboveNoteBlock() || above.isAir();
    }
}
