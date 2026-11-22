package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowControl;
import net.minecraft.nbt.*;
import java.util.*;

/** Optional physical-owner history around an unchanged event runtime, under one aggregate archive budget. */
public final class EventHostNbtCodec {
    public static final String MARKER = "EventOwnerHistoryVersion";
    private EventHostNbtCodec() {}
    public record Saved(ComputerRuntime.Snapshot snapshot, FlowControl control) {
        public Saved {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(control);
            if (control.peers().containsKey(snapshot.id())) throw new IllegalArgumentException("Computer cannot be its own peer");
        }
    }
    public static CompoundTag encode(ComputerRuntime.Snapshot snapshot, FlowControl control, ArchiveLimits limits) {
        new Saved(snapshot, control);
        if (control.pristine()) return ComputerNbtCodec.encode(snapshot, limits);
        var tag = new CompoundTag(); tag.putInt(MARKER, control.taskAware() ? 3 : control.wideOperations() ? 2 : 1);
        tag.put("Data", ValueNbtCodec.encode(obj("event", ComputerNbtCodec.value(snapshot), "control", control.value()), limits));
        return tag;
    }
    public static Saved decode(CompoundTag tag, ArchiveLimits limits) {
        if (!tag.contains(MARKER)) return new Saved(ComputerNbtCodec.decode(tag, limits), FlowControl.INITIAL);
        try {
            if (!tag.getAllKeys().equals(Set.of(MARKER, "Data")) || !tag.contains(MARKER, Tag.TAG_INT) || !tag.contains("Data", Tag.TAG_COMPOUND))
                throw ArchiveException.invalid("Invalid event owner history fields");
            int version = tag.getInt(MARKER);
            if (version < 1 || version > 3) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported event owner history version");
            var value = ValueNbtCodec.decode(tag.getCompound("Data"), limits);
            if (!fields(value).keySet().equals(Set.of("event", "control"))) throw ArchiveException.invalid("Invalid event owner history data");
            return new Saved(ComputerNbtCodec.readValue(at(value, "event")), FlowControlCodec.decode(at(value, "control"), version >= 2, version >= 3));
        } catch (RuntimeException invalid) {
            if (invalid instanceof ArchiveException archive) throw archive;
            throw ArchiveException.invalid("Invalid event owner history: " + invalid.getMessage());
        }
    }
}
