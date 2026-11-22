package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryServiceData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Server-run identity and server tick, never a block entity's local tick counter. */
public record LibraryWriteBatch(UUID epoch, long tick) {
    public LibraryWriteBatch { Objects.requireNonNull(epoch); if (tick < 0) throw new IllegalArgumentException("Negative tick"); }
    public boolean closedAt(LibraryWriteBatch now) { return !epoch.equals(now.epoch) || tick < now.tick; }
    /** Shared competition key across the slow device and the transitional service. */
    public record Target(LibraryWriteBatch batch, String volume, String entry) {
        public Target { Objects.requireNonNull(batch); Objects.requireNonNull(volume); Objects.requireNonNull(entry); }
    }
    public record Admission(LibraryWriteBatch batch, String verdict) {
        public Admission { Objects.requireNonNull(batch); Objects.requireNonNull(verdict); }
        public DataValue value() { return obj("epoch", batch.epoch.toString(), "tick", batch.tick, "verdict", verdict); }
        public static Admission decode(DataValue value) {
            var r = fields(value, "epoch", "tick", "verdict"); String id = text(r.field("epoch"));
            var uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw new IllegalArgumentException("Noncanonical batch epoch");
            return new Admission(new LibraryWriteBatch(uuid, number(r.field("tick"))), text(r.field("verdict")));
        }
    }
}
