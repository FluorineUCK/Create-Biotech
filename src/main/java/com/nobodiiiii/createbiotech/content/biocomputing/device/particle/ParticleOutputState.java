package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Only the device/session and last outcome persist. No burst parameters are kept in the archive. */
final class ParticleOutputState {
    private static final Set<String> RESULTS = Set.of("emitted", "no_viewers", "target_unavailable",
        "particle_budget_exhausted", "device_error", "interrupted");
    private ParticleOutputState() {}

    record State(UUID device, UUID session, long revision, Optional<UUID> controller, String last) {
        State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(controller);
            if (revision < 0 || !last.equals("idle") && !last.equals("accepted") && !RESULTS.contains(last)
                || last.equals("accepted") && controller.isEmpty())
                throw new IllegalArgumentException("invalid_particle_state");
        }

        boolean pending() { return last.equals("accepted"); }
    }

    static State closed(UUID device, UUID session) { return new State(device, session, 0, Optional.empty(), "idle"); }

    static State grant(State before, Optional<UUID> controller, UUID session) {
        var restarted = restart(before, session);
        if (before.revision() == Long.MAX_VALUE && controller.isPresent())
            throw new IllegalArgumentException("version_exhausted");
        return new State(before.device(), session,
            before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1, controller, restarted.last());
    }

    static State accept(State before) {
        if (before.pending() || before.controller().isEmpty() || before.revision() == Long.MAX_VALUE)
            throw new IllegalArgumentException("invalid_particle_admission");
        return new State(before.device(), before.session(), before.revision() + 1, before.controller(), "accepted");
    }

    static State finish(State before, String result) {
        if (!before.pending() || !RESULTS.contains(result)) throw new IllegalArgumentException("invalid_particle_completion");
        return new State(before.device(), before.session(), before.revision(), before.controller(), result);
    }

    static State restart(State before, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        return new State(before.device(), session, before.revision(), before.controller(),
            before.pending() ? "interrupted" : before.last());
    }

    static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putUUID("Device", state.device()); tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision()); tag.putString("Last", state.last());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        return tag;
    }

    static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Last"));
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Last", Tag.TAG_STRING) || tag.contains("Controller") && !tag.hasUUID("Controller"))
            throw new IllegalArgumentException("invalid_particle_archive");
        return restart(new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), tag.getString("Last")), session);
    }
}
