package com.nobodiiiii.createbiotech.content.biocomputing.device.label;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** One adapter-owned literal line. No entity, target position or pending world action is stored here. */
public final class LabelOutputState {
    private LabelOutputState() {}
    public static final int FORMAT_MAX_BYTES = 256;
    private static final int ARCHIVE_VERSION = 2;

    public record State(UUID device, UUID session, long revision, Optional<UUID> controller, String label, LabelOffset offset) {
        public State {
            Objects.requireNonNull(device);
            Objects.requireNonNull(session);
            Objects.requireNonNull(controller);
            Objects.requireNonNull(offset);
            literalBytes(label);
            if (revision < 0 || controller.isEmpty() && !label.isEmpty() || label.isEmpty() && !offset.equals(LabelOffset.ZERO))
                throw new IllegalArgumentException("invalid_label_state");
        }

        public int bytes() {
            return label.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public static State closed(UUID device, UUID session) {
        return new State(device, session, 0, Optional.empty(), "", LabelOffset.ZERO);
    }

    /** A new grant starts blank; revocation must work even when the revision counter is exhausted. */
    public static State grant(State before, Optional<UUID> controller, UUID session) {
        requireNewSession(before, session);
        if (before.revision() == Long.MAX_VALUE && controller.isPresent())
            throw new IllegalArgumentException("version_exhausted");
        long revision = before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1;
        return new State(before.device(), session, revision, controller, "", LabelOffset.ZERO);
    }

    public static State restart(State before, UUID session) {
        requireNewSession(before, session);
        return new State(before.device(), session, before.revision(), before.controller(), before.label(), before.offset());
    }

    private static void requireNewSession(State before, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
    }

    /** A wire/archive ceiling, independent of current server balance settings. Also used by client sync. */
    static int literalBytes(String label) {
        if (label.length() > FORMAT_MAX_BYTES) throw new IllegalArgumentException("label_capacity");
        for (int i = 0; i < label.length();) {
            int codePoint = label.codePointAt(i);
            if (Character.isISOControl(codePoint) || codePoint == 0xA7
                || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                throw new IllegalArgumentException("invalid_label");
            i += Character.charCount(codePoint);
        }
        int bytes = label.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > FORMAT_MAX_BYTES) throw new IllegalArgumentException("label_capacity");
        return bytes;
    }

    public static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", ARCHIVE_VERSION);
        tag.putUUID("Device", state.device());
        tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision());
        tag.putString("Label", state.label());
        tag.put("Offset", state.offset().save());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        return tag;
    }

    public static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Label"));
        int version = tag.getInt("Version");
        if (version == ARCHIVE_VERSION) keys.add("Offset");
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || (version != 1 && version != ARCHIVE_VERSION)
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Label", Tag.TAG_STRING) || tag.contains("Controller") && !tag.hasUUID("Controller"))
            throw new IllegalArgumentException("invalid_label_archive");
        return restart(new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), tag.getString("Label"),
            version == 1 ? LabelOffset.ZERO : LabelOffset.load(tag.get("Offset"))), session);
    }
}
