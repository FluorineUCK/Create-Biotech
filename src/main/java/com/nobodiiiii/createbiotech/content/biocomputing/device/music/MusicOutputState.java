package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/** One hardware action slot, not a sequencer, song store or sound-address interpreter. */
public final class MusicOutputState {
    private MusicOutputState() {}
    private static final Set<String> RESULTS = Set.of("idle", "accepted", "played", "cancelled", "obstructed",
        "target_missing", "target_unloaded", "target_changed", "missing_head_sound", "invalid_note", "interrupted", "device_error", "access_denied");
    public record Note(NoteBlockInstrument instrument, int pitch) {
        public Note {
            Objects.requireNonNull(instrument);
            if (pitch < 0 || pitch > 24) throw new IllegalArgumentException("invalid_note");
        }
        public static Note parse(String name, long pitch) {
            if (pitch < 0 || pitch > 24) throw new IllegalArgumentException("invalid_note");
            for (var instrument : NoteBlockInstrument.values())
                if (instrument.getSerializedName().equals(name)) return new Note(instrument, (int)pitch);
            throw new IllegalArgumentException("invalid_note");
        }
    }
    public record State(UUID device, UUID session, long revision, Optional<UUID> controller, Optional<Note> pending, String last) {
        public State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(controller); Objects.requireNonNull(pending);
            if (revision < 0 || !RESULTS.contains(last) || pending.isPresent() != last.equals("accepted")
                || pending.isPresent() && controller.isEmpty()) throw new IllegalArgumentException("invalid_music_state");
        }
    }
    public record Update(String code, State state) {}
    public static State closed(UUID device, UUID session) { return new State(device, session, 0, Optional.empty(), Optional.empty(), "idle"); }
    public static State grant(State before, Optional<UUID> controller, UUID session) {
        fresh(before, session);
        if (before.revision() == Long.MAX_VALUE && controller.isPresent()) throw new IllegalArgumentException("version_exhausted");
        return new State(before.device(), session, before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1,
            controller, Optional.empty(), before.pending().isPresent() ? "interrupted" : before.last());
    }
    public static Update accept(State before, UUID source, UUID device, UUID session, long revision, Note note) {
        String error = before.controller().isEmpty() ? "closed"
            : !before.controller().orElseThrow().equals(source) ? "denied"
            : !before.device().equals(device) ? "device_changed"
            : !before.session().equals(session) ? "stale_session"
            : before.revision() != revision ? "changed"
            : before.pending().isPresent() ? "busy"
            : before.revision() == Long.MAX_VALUE ? "version_exhausted" : null;
        if (error != null) return new Update(error, before);
        return new Update("accepted", new State(before.device(), before.session(), before.revision() + 1,
            before.controller(), Optional.of(note), "accepted"));
    }
    /** Only the physical host completes its current slot. Completion is not another command/revision. */
    public static State complete(State before, String result) {
        if (before.pending().isEmpty() || !RESULTS.contains(result) || result.equals("idle") || result.equals("accepted"))
            throw new IllegalArgumentException("invalid_music_completion");
        return new State(before.device(), before.session(), before.revision(), before.controller(), Optional.empty(), result);
    }
    public static State restart(State before, UUID session) {
        fresh(before, session);
        return new State(before.device(), session, before.revision(), before.controller(), Optional.empty(),
            before.pending().isPresent() ? "interrupted" : before.last());
    }
    private static void fresh(State before, UUID session) {
        if (session == null || before.session().equals(session)) throw new IllegalArgumentException("session_reused");
    }
    public static CompoundTag save(State state) {
        var tag = new CompoundTag(); tag.putInt("Version", 1);
        tag.putUUID("Device", state.device()); tag.putUUID("Session", state.session()); tag.putLong("Revision", state.revision());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id)); tag.putString("Last", state.last());
        state.pending().ifPresent(note -> {
            var pending = new CompoundTag(); pending.putString("Instrument", note.instrument().getSerializedName()); pending.putInt("Pitch", note.pitch());
            tag.put("Pending", pending);
        });
        return tag;
    }
    /** Saved pending actions become interrupted, never a playlist replayed on load. */
    public static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Last"));
        if (tag.contains("Controller")) keys.add("Controller"); if (tag.contains("Pending")) keys.add("Pending");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Last", Tag.TAG_STRING) || tag.contains("Controller") && !tag.hasUUID("Controller"))
            throw new IllegalArgumentException("invalid_music_archive");
        Optional<Note> pending = Optional.empty();
        if (tag.contains("Pending")) {
            if (!(tag.get("Pending") instanceof CompoundTag note) || !note.getAllKeys().equals(Set.of("Instrument", "Pitch"))
                || !note.contains("Instrument", Tag.TAG_STRING) || !note.contains("Pitch", Tag.TAG_INT)) throw new IllegalArgumentException("invalid_music_archive");
            pending = Optional.of(Note.parse(note.getString("Instrument"), note.getInt("Pitch")));
        }
        return restart(new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), pending, tag.getString("Last")), session);
    }
}
