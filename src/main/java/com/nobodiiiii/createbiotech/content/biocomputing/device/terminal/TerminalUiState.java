package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalInteraction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Layout ownership is separate from an installed program. Only an immutable page and its identity survive reload. */
public final class TerminalUiState {
    // Format ceilings, not balancing defaults. Stored pages remain readable when runtime limits are lowered.
    public static final int MAX_WIDGETS = 128, MAX_EVENTS = 32, MAX_INPUT_CHARS = 512;
    static final int MAX_INSTANCE_CHARS = 8192;
    public static final SerialRecordCodec.Limits PAGE_FORMAT = new SerialRecordCodec.Limits(12 * 1024, 128 * 1024,
        new ArchiveLimits(1024, 6144, 8192));
    private TerminalUiState() {}

    public record Limits(int widgets, int events, int inputChars, SerialRecordCodec.Limits page, SerialRecordCodec.Limits event) {
        public Limits {
            Objects.requireNonNull(page);
            Objects.requireNonNull(event);
            if (widgets < 1 || widgets > MAX_WIDGETS || events < 1 || events > MAX_EVENTS
                || inputChars < 1 || inputChars > MAX_INPUT_CHARS
                || page.maxEncodedBytes() > PAGE_FORMAT.maxEncodedBytes()
                || page.values().maxNodes() > PAGE_FORMAT.values().maxNodes()
                || page.values().maxStringChars() > PAGE_FORMAT.values().maxStringChars()
                || page.values().maxValueUnits() > PAGE_FORMAT.values().maxValueUnits()
                || event.maxEncodedBytes() > PAGE_FORMAT.maxEncodedBytes())
                throw new IllegalArgumentException("invalid_terminal_limits");
        }
    }

    record Page(TerminalDefinition layout, DataValue.RecordValue roots) {
        DataValue.RecordValue encode() { return record(Map.of("layout", TerminalCodec.encode(layout), "roots", roots)); }
    }
    record Event(long sequence, long layoutRevision, String kind, String instance, String name, DataValue.RecordValue values) {
        DataValue.RecordValue encode() {
            return record(Map.of("sequence", number(sequence), "layout_revision", number(layoutRevision), "kind", text(kind),
                "instance", text(instance), "name", text(name), "values", values));
        }
    }
    record State(UUID device, UUID session, long revision, Optional<UUID> controller, OptionalInt ccPublisher, Optional<Page> page,
                 long nextSequence, List<Event> events) {
        State {
            Objects.requireNonNull(device); Objects.requireNonNull(session);
            Objects.requireNonNull(controller); Objects.requireNonNull(ccPublisher); Objects.requireNonNull(page);
            events = List.copyOf(events);
            if (revision < 0 || (revision > 0) != page.isPresent() || nextSequence < 1
                || ccPublisher.isPresent() && (ccPublisher.getAsInt() < 0 || controller.isPresent())
                || events.size() > MAX_EVENTS
                || !events.isEmpty() && (page.isEmpty() || controller.isEmpty() && ccPublisher.isEmpty()))
                throw new IllegalArgumentException("invalid_terminal_state");
        }
        boolean hasPublisher() { return controller.isPresent() || ccPublisher.isPresent(); }
    }
    record Update(State state, String code) {}

    static State closed(UUID device, UUID session) {
        return new State(device, session, 0, Optional.empty(), OptionalInt.empty(), Optional.empty(), 1, List.of());
    }
    static State grant(State before, Optional<UUID> controller, UUID session) {
        if (controller.isPresent() && before.ccPublisher().isPresent()) throw new IllegalArgumentException("publisher_in_use");
        return newSession(before, controller, before.ccPublisher(), session);
    }
    static State grantCc(State before, OptionalInt ccPublisher, UUID session) {
        if (ccPublisher.isPresent() && before.controller().isPresent()) throw new IllegalArgumentException("publisher_in_use");
        return newSession(before, before.controller(), ccPublisher, session);
    }
    private static State newSession(State before, Optional<UUID> controller, OptionalInt ccPublisher, UUID session) {
        if (before.session().equals(session)) throw new IllegalArgumentException("session_reused");
        return new State(before.device(), session, before.revision(), controller, ccPublisher, before.page(), 1, List.of());
    }
    static State restart(State before, UUID session) { return grant(before, before.controller(), session); }

    static Update publish(State before, DataValue layout, DataValue roots, Limits limits) {
        if (before.revision() == Long.MAX_VALUE) return reject(before, "version_exhausted");
        try {
            var page = page(record(Map.of("layout", layout, "roots", roots)), limits.widgets(), limits.page());
            return new Update(new State(before.device(), before.session(), before.revision() + 1, before.controller(), before.ccPublisher(),
                Optional.of(page), before.nextSequence(), before.events()), "published");
        } catch (SerialRecordCodec.Failure size) {
            return reject(before, capacity(size) ? "view_capacity" : "invalid_view");
        } catch (ViewCapacityException exceeded) {
            return reject(before, "view_capacity");
        } catch (IllegalArgumentException invalid) {
            return reject(before, "invalid_view");
        }
    }

    /** The host must first validate the live menu, player and view's device identity. */
    static Update interact(State before, UUID session, long revision, String instance, Optional<String> submitted, Limits limits) {
        if (!before.session().equals(session) || before.revision() != revision) return reject(before, "stale_view");
        if (!before.hasPublisher()) return reject(before, "closed");
        if (before.page().isEmpty()) return reject(before, "no_view");
        if (instance.isEmpty() || instance.length() > MAX_INSTANCE_CHARS) return reject(before, "invalid_interaction");
        if (submitted.filter(value -> value.length() > limits.inputChars()).isPresent()) return reject(before, "input_capacity");
        if (before.events().size() >= limits.events()) return reject(before, "events_full");
        if (before.nextSequence() == Long.MAX_VALUE) return reject(before, "version_exhausted");

        var page = before.page().orElseThrow();
        try {
            var action = submitted.isPresent()
                ? TerminalInteraction.resolveSubmit(page.layout(), page.roots(), instance, submitted.orElseThrow())
                : TerminalInteraction.resolve(page.layout(), page.roots(), instance);
            var event = new Event(before.nextSequence(), revision, submitted.isPresent() ? "submit" : "click",
                instance, action.entry(), action.inputs());
            SerialRecordCodec.encode(event.encode(), limits.event());
            var queue = new ArrayList<>(before.events());
            queue.add(event);
            return new Update(new State(before.device(), before.session(), before.revision(), before.controller(), before.ccPublisher(), before.page(),
                before.nextSequence() + 1, queue), "queued");
        } catch (SerialRecordCodec.Failure size) {
            return reject(before, capacity(size) ? "event_capacity" : "invalid_interaction");
        } catch (IllegalArgumentException invalid) {
            return reject(before, "invalid_interaction");
        }
    }

    static State withoutHead(State before) {
        return new State(before.device(), before.session(), before.revision(), before.controller(), before.ccPublisher(), before.page(),
            before.nextSequence(), before.events().subList(1, before.events().size()));
    }

    static CompoundTag save(State state) {
        var tag = new CompoundTag();
        tag.putInt("Version", state.ccPublisher().isPresent() ? 2 : 1);
        tag.putUUID("Device", state.device());
        tag.putUUID("Session", state.session());
        tag.putLong("Revision", state.revision());
        state.controller().ifPresent(id -> tag.putUUID("Controller", id));
        state.ccPublisher().ifPresent(id -> tag.putInt("CcPublisher", id));
        state.page().ifPresent(page -> tag.put("View", ValueNbtCodec.encode(page.encode(), PAGE_FORMAT.values())));
        return tag;
    }

    static State load(CompoundTag tag, UUID session) {
        var keys = new HashSet<>(Set.of("Version", "Device", "Session", "Revision"));
        int version = tag.getInt("Version");
        if (tag.contains("Controller")) keys.add("Controller");
        if (version == 2) keys.add("CcPublisher");
        if (tag.contains("View")) keys.add("View");
        if (!tag.getAllKeys().equals(keys) || !tag.contains("Version", Tag.TAG_INT) || version < 1 || version > 2
            || !tag.hasUUID("Device") || !tag.hasUUID("Session") || !tag.contains("Revision", Tag.TAG_LONG)
            || tag.contains("Controller") && !tag.hasUUID("Controller")
            || version == 2 && (!tag.contains("CcPublisher", Tag.TAG_INT) || tag.getInt("CcPublisher") < 0 || tag.contains("Controller"))
            || tag.contains("View") && !tag.contains("View", Tag.TAG_COMPOUND)
            || tag.getUUID("Session").equals(session)) throw new IllegalArgumentException("invalid_terminal_archive");
        var controller = tag.hasUUID("Controller") ? Optional.of(tag.getUUID("Controller")) : Optional.<UUID>empty();
        var view = tag.contains("View") ? Optional.of(page(ValueNbtCodec.decode(tag.getCompound("View"), PAGE_FORMAT.values()),
            MAX_WIDGETS, PAGE_FORMAT)) : Optional.<Page>empty();
        var ccPublisher = version == 2 ? OptionalInt.of(tag.getInt("CcPublisher")) : OptionalInt.empty();
        return new State(tag.getUUID("Device"), session, tag.getLong("Revision"), controller, ccPublisher, view, 1, List.of());
    }

    private static Page page(DataValue payload, int widgets, SerialRecordCodec.Limits limits) {
        if (!(payload instanceof DataValue.RecordValue record) || record.localReferences() != 0
            || !record.fields().keySet().equals(Set.of("layout", "roots"))
            || !(record.field("roots") instanceof DataValue.RecordValue roots))
            throw new IllegalArgumentException("invalid_terminal_view");
        // Bound work before decoding the tree. Normalize older layout schemas for storage, with the same capacity check.
        SerialRecordCodec.encode(record, limits);
        var layout = TerminalCodec.decode(record.field("layout"));
        if (layout.hasTasks()) throw new IllegalArgumentException("terminal_view_is_not_program");
        if (layout.nodes().size() > widgets) throw new ViewCapacityException();
        var page = new Page(layout, roots);
        SerialRecordCodec.encode(page.encode(), limits);
        return page;
    }

    private static boolean capacity(SerialRecordCodec.Failure failure) {
        return failure.code() == SerialRecordCodec.Code.PAYLOAD_TOO_LARGE || failure.code() == SerialRecordCodec.Code.VALUE_LIMIT;
    }
    private static Update reject(State before, String code) { return new Update(before, code); }
    private static final class ViewCapacityException extends IllegalArgumentException {}
    static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
