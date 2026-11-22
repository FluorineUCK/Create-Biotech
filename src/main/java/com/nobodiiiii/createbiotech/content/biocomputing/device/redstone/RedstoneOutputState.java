package com.nobodiiiii.createbiotech.content.biocomputing.device.redstone;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** One physical output latch. No world objects, Computer RAM, transport or Create-specific operations. */
public final class RedstoneOutputState {
    private RedstoneOutputState() {}

    public record State(UUID device, UUID session, long revision, Optional<UUID> controller, int desired, int applied) {
        public State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(controller);
            if (revision < 0 || !level(desired) || !level(applied) || controller.isEmpty() && applied != 0)
                throw new IllegalArgumentException("invalid_output_state");
        }
    }
    public record Update(String code, State state, boolean signalChanged) {}

    public static State closed(UUID device, UUID session) {
        return new State(device, session, 0, Optional.empty(), 0, 0);
    }

    /** Trusted configuration boundary. The host must prove the selected physical controller first. */
    public static State grant(State before, Optional<UUID> controller, UUID nextSession) {
        Objects.requireNonNull(controller);
        if (before.controller().equals(controller)) return before;
        freshSession(before, nextSession);
        // Counter exhaustion may reject new commands, but must never prevent the owner from revoking power.
        // The fresh session fences old requests even when the exhausted revision cannot advance.
        if (before.revision() == Long.MAX_VALUE && controller.isPresent()) throw new IllegalArgumentException("version_exhausted");
        long nextRevision = before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1;
        return new State(before.device(), nextSession, nextRevision, controller, before.desired(), 0);
    }

    /** Source is a verified transport identity, never an identity trusted from the payload. */
    public static Update set(State before, UUID source, UUID device, UUID session, long expectedRevision, int value) {
        String error = before.controller().isEmpty() ? "closed"
            : !before.controller().orElseThrow().equals(source) ? "denied"
            : !before.device().equals(device) ? "device_changed"
            : !before.session().equals(session) ? "stale_session"
            : before.revision() != expectedRevision ? "changed"
            : !level(value) ? "out_of_range"
            : before.revision() == Long.MAX_VALUE ? "version_exhausted" : null;
        if (error != null) return new Update(error, before, false);
        var after = new State(before.device(), before.session(), before.revision() + 1, before.controller(), value, value);
        return new Update("applied", after, before.applied() != value);
    }

    /** Device unload/reload, not Computer pause/disconnection. Previously accepted levels are not commands. */
    public static State restart(State before, UUID nextSession) {
        freshSession(before, nextSession);
        return new State(before.device(), nextSession, before.revision(), before.controller(), before.desired(), 0);
    }
    private static void freshSession(State before, UUID next) {
        if (next == null || before.session().equals(next)) throw new IllegalArgumentException("session_reused");
    }
    private static boolean level(int value) { return value >= 0 && value <= 15; }

    /** Applied level is deliberately not persisted. Persisted desire never energizes a loaded device. */
    public static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putUUID("Device", state.device()); tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision()); tag.putInt("Desired", state.desired());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        return tag;
    }

    /** Strict decode; the physical host retains a rejected archive and remains unavailable. */
    public static State load(CompoundTag tag, UUID nextSession) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Desired"));
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Desired", Tag.TAG_INT) || tag.contains("Controller") && !tag.hasUUID("Controller"))
            throw new IllegalArgumentException("invalid_output_archive");
        var saved = new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), tag.getInt("Desired"), 0);
        return restart(saved, nextSession);
    }
}
