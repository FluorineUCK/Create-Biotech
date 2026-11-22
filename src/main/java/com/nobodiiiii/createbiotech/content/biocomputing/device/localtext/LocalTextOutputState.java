package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Admitted text is not retained by the effect. Only its identity, rate boundary and outcome are saved. */
final class LocalTextOutputState {
    private static final Set<String> RESULTS = Set.of("dispatched", "no_listeners", "interrupted",
        "speech_budget_exhausted", "chat_budget_exhausted", "no_recipients", "device_error");
    private LocalTextOutputState() {}

    record State(UUID device, UUID session, long revision, Optional<UUID> controller, String last, long lastAdmissionTick) {
        State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(controller);
            if (revision < 0 || lastAdmissionTick < -1 || !last.equals("idle") && !last.equals("accepted") && !RESULTS.contains(last)
                || last.equals("accepted") && (controller.isEmpty() || lastAdmissionTick < 0))
                throw new IllegalArgumentException("invalid_narrator_state");
        }
        boolean pending() { return last.equals("accepted"); }
        boolean canAdmitAt(long tick, int gap) {
            return lastAdmissionTick < 0 || tick >= lastAdmissionTick && tick - lastAdmissionTick >= gap;
        }
    }

    static State closed(UUID device, UUID session) { return new State(device, session, 0, Optional.empty(), "idle", -1); }

    static State grant(State before, Optional<UUID> controller, UUID session) {
        var restarted = restart(before, session);
        if (before.revision() == Long.MAX_VALUE && controller.isPresent()) throw new IllegalArgumentException("version_exhausted");
        return new State(before.device(), session, before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1,
            controller, restarted.last(), before.lastAdmissionTick());
    }

    static State accept(State before, long tick) {
        if (before.pending() || before.controller().isEmpty() || before.revision() == Long.MAX_VALUE)
            throw new IllegalArgumentException("invalid_narrator_admission");
        return new State(before.device(), before.session(), before.revision() + 1, before.controller(), "accepted", tick);
    }

    static State advanceControl(State before) {
        if (before.pending() || before.controller().isEmpty() || before.revision() == Long.MAX_VALUE)
            throw new IllegalArgumentException("invalid_speaker_admission");
        return new State(before.device(), before.session(), before.revision() + 1, before.controller(), before.last(), before.lastAdmissionTick());
    }

    static State finish(State before, String result) {
        if (!before.pending() || !RESULTS.contains(result)) throw new IllegalArgumentException("invalid_narrator_completion");
        return new State(before.device(), before.session(), before.revision(), before.controller(), result, before.lastAdmissionTick());
    }

    static State restart(State before, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        return new State(before.device(), session, before.revision(), before.controller(),
            before.pending() ? "interrupted" : before.last(), before.lastAdmissionTick());
    }

    static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putUUID("Device", state.device()); tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision()); tag.putString("Last", state.last());
        tag.putLong("LastAdmissionTick", state.lastAdmissionTick());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        return tag;
    }

    static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Last", "LastAdmissionTick"));
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Last", Tag.TAG_STRING) || !tag.contains("LastAdmissionTick", Tag.TAG_LONG)
            || tag.contains("Controller") && !tag.hasUUID("Controller"))
            throw new IllegalArgumentException("invalid_narrator_archive");
        return restart(new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), tag.getString("Last"),
            tag.getLong("LastAdmissionTick")), session);
    }
}
