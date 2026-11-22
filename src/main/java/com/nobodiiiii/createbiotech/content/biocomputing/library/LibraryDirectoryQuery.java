package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** An explicit metadata page from one already selected book; never a volume search or automatic transfer. */
public record LibraryDirectoryQuery(Optional<String> after, int count) {
    public LibraryDirectoryQuery {
        Objects.requireNonNull(after);
        if (count < 1) throw new LibraryBookAccess.Failure("invalid_request");
        after.ifPresent(name -> {
            // A cursor is an existing entry name, not a volume name. Frame/archive limits bound its size.
            if (name.isBlank()) throw new LibraryBookAccess.Failure("invalid_request");
        });
    }
    public static LibraryDirectoryQuery decode(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of("after", "count"))
            || !(record.field("count") instanceof DataValue.IntegerValue count) || count.value() < 1 || count.value() > Integer.MAX_VALUE)
            throw new LibraryBookAccess.Failure("invalid_request");
        var after = record.field("after");
        if (after != DataValue.NullValue.INSTANCE && !(after instanceof DataValue.TextValue)) throw new LibraryBookAccess.Failure("invalid_request");
        return new LibraryDirectoryQuery(after == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(((DataValue.TextValue)after).value()), (int)count.value());
    }
}
