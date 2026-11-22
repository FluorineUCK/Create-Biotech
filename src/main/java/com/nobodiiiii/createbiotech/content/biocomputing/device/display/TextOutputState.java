package com.nobodiiiii.createbiotech.content.biocomputing.device.display;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;

/** An adapter-owned page, not a claim that a powered target has displayed it. */
public final class TextOutputState {
    private TextOutputState() {}
    public static final int MAX_LINES = 256, MAX_BYTES = 65536;
    public record Limits(int lines, int bytes) {
        public Limits { if (lines < 1 || lines > MAX_LINES || bytes < 1 || bytes > MAX_BYTES) throw new IllegalArgumentException("invalid_text_limits"); }
    }
    public record State(UUID device, UUID session, long revision, Optional<UUID> controller, List<String> lines) {
        public State {
            Objects.requireNonNull(device); Objects.requireNonNull(session); Objects.requireNonNull(controller);
            if (revision < 0) throw new IllegalArgumentException("invalid_text_state");
            lines = List.copyOf(lines);
            if (lines.size() > MAX_LINES) throw new IllegalArgumentException("text_capacity");
            long bytes = 0;
            for (String line : lines) {
                if (line.length() > MAX_BYTES) throw new IllegalArgumentException("text_capacity");
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (Character.isISOControl(c)) throw new IllegalArgumentException("invalid_text");
                    if (Character.isHighSurrogate(c)) {
                        if (++i >= line.length() || !Character.isLowSurrogate(line.charAt(i))) throw new IllegalArgumentException("invalid_text");
                    } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid_text");
                }
                bytes += line.getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("text_capacity");
        }
        public long bytes() { return lines.stream().mapToLong(line -> line.getBytes(StandardCharsets.UTF_8).length).sum(); }
    }
    public record Update(String code, State state) {}
    public static State closed(UUID device, UUID session) { return new State(device, session, 0, Optional.empty(), List.of()); }
    public static State grant(State before, Optional<UUID> controller, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        if (before.revision() == Long.MAX_VALUE && controller.isPresent()) throw new IllegalArgumentException("version_exhausted");
        return new State(before.device(), session, before.revision() == Long.MAX_VALUE ? Long.MAX_VALUE : before.revision() + 1, controller, before.lines());
    }
    public static Update replace(State before, UUID source, UUID device, UUID session, long revision, List<String> lines, Limits limits) {
        String error = before.controller().isEmpty() ? "closed"
            : !before.controller().orElseThrow().equals(source) ? "denied"
            : !before.device().equals(device) ? "device_changed"
            : !before.session().equals(session) ? "stale_session"
            : before.revision() != revision ? "changed"
            : before.revision() == Long.MAX_VALUE ? "version_exhausted" : null;
        if (error != null) return new Update(error, before);
        var next = new State(before.device(), before.session(), before.revision() + 1, before.controller(), lines);
        if (lines.size() > limits.lines() || next.bytes() > limits.bytes()) return new Update("text_capacity", before);
        return new Update("accepted", next);
    }
    public static State restart(State before, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        return new State(before.device(), session, before.revision(), before.controller(), before.lines());
    }
    public static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", 1); tag.putUUID("Device", state.device()); tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision()); state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        var lines = new ListTag(); state.lines().forEach(line -> lines.add(StringTag.valueOf(line))); tag.put("Lines", lines);
        return tag;
    }
    /** Decode against format bounds, not lowered balance limits; never truncate existing pages. */
    public static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision", "Lines"));
        if (tag.contains("Controller")) keys.add("Controller");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || !(tag.get("Lines") instanceof ListTag lines) || lines.size() > MAX_LINES
            || !lines.isEmpty() && lines.getElementType() != Tag.TAG_STRING
            || tag.contains("Controller") && !tag.hasUUID("Controller")) throw new IllegalArgumentException("invalid_text_archive");
        var values = new ArrayList<String>(); lines.forEach(line -> values.add(line.getAsString()));
        return restart(new State(tag.getUUID("Device"), tag.getUUID("Session"), tag.getLong("Revision"),
            tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.empty(), values), session);
    }
}
