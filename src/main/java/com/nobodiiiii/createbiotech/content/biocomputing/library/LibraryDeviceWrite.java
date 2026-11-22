package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Trusted device-owned operation identity. Never decoded from a player's claimed sender identity. */
public record LibraryDeviceWrite(UUID device, long sequence, LibraryMutation mutation) {
    public LibraryDeviceWrite {
        Objects.requireNonNull(device); Objects.requireNonNull(mutation);
        if (sequence < 1) throw new IllegalArgumentException("Device operation must have an admitted sequence");
    }
    public String key() { return "device:" + device + ":" + sequence; }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("device", new DataValue.TextValue(device.toString()),
            "sequence", new DataValue.IntegerValue(sequence), "mutation", mutation.value()));
    }
    public static LibraryDeviceWrite decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of("device", "sequence", "mutation"))
            || !(record.field("device") instanceof DataValue.TextValue id)
            || !(record.field("sequence") instanceof DataValue.IntegerValue sequence)
            || !(record.field("mutation") instanceof DataValue.RecordValue command))
            throw new IllegalArgumentException("Invalid device write");
        var device = UUID.fromString(id.value());
        if (!device.toString().equals(id.value())) throw new IllegalArgumentException("Noncanonical device identity");
        return new LibraryDeviceWrite(device, sequence.value(), LibraryMutation.decode(command));
    }
    public record Receipt(LibraryDeviceWrite operation, DataValue.RecordValue reply) implements LibraryWriteReceipt {
        public Receipt { Objects.requireNonNull(operation); LibraryWriteReceipt.validate(operation.mutation(), reply); }
        @Override public String key() { return operation.key(); }
        @Override public DataValue.RecordValue value() {
            return new DataValue.RecordValue(Map.of("operation", operation.value(), "reply", reply));
        }
        public static Receipt decode(DataValue value) {
            if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of("operation", "reply"))
                || !(record.field("reply") instanceof DataValue.RecordValue reply)) throw new IllegalArgumentException("Invalid device receipt");
            return new Receipt(LibraryDeviceWrite.decode(record.field("operation")), reply);
        }
        public DataValue.RecordValue replay(LibraryDeviceWrite expected) {
            if (!operation.equals(expected)) throw new LibraryBookAccess.Failure("identity_conflict");
            return reply;
        }
    }
}
