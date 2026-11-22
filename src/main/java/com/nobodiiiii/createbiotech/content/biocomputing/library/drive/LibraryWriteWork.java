package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryDeviceWrite;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryWriteBatch;
import java.util.*;

/** Device-owned finite work and commit-recovery witness, never Computer RAM or a live book reference. */
public record LibraryWriteWork(LibraryDeviceWrite operation, LibraryWriteBatch.Admission admission,
    long remaining, long total, boolean committing) {
    public LibraryWriteWork {
        Objects.requireNonNull(operation); Objects.requireNonNull(admission);
        if (total < 1 || remaining < 0 || remaining > total || !Set.of("", "eligible", "conflict").contains(admission.verdict())
            || committing && (remaining != 0 || !admission.verdict().equals("eligible")))
            throw new IllegalArgumentException("Invalid device write continuation");
    }
    public LibraryWriteWork elapsed() { return new LibraryWriteWork(operation, admission, Math.max(0, remaining - 1), total, committing); }
    public LibraryWriteWork seal(boolean conflict) {
        if (!admission.verdict().isEmpty()) return this;
        return new LibraryWriteWork(operation, new LibraryWriteBatch.Admission(admission.batch(), conflict ? "conflict" : "eligible"), remaining, total, false);
    }
    public LibraryWriteWork commitStarted() { return new LibraryWriteWork(operation, admission, 0, total, true); }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("operation", operation.value(), "batch", admission.value(),
            "remaining", new DataValue.IntegerValue(remaining), "total", new DataValue.IntegerValue(total), "committing", new DataValue.BooleanValue(committing)));
    }
    public static LibraryWriteWork decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue r) || !r.fields().keySet().equals(Set.of("operation", "batch", "remaining", "total", "committing"))
            || !(r.field("remaining") instanceof DataValue.IntegerValue remaining) || !(r.field("total") instanceof DataValue.IntegerValue total)
            || !(r.field("committing") instanceof DataValue.BooleanValue committing)) throw new IllegalArgumentException("Invalid device write continuation");
        return new LibraryWriteWork(LibraryDeviceWrite.decode(r.field("operation")), LibraryWriteBatch.Admission.decode(r.field("batch")),
            remaining.value(), total.value(), committing.value());
    }
}
