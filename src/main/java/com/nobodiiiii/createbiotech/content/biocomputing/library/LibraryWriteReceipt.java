package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.Set;

/** One book-owned completion journal, with explicit legacy-message and device-operation identities. */
public interface LibraryWriteReceipt {
    String key();
    DataValue.RecordValue reply();
    DataValue.RecordValue value();

    static LibraryWriteReceipt decode(DataValue value) {
        if (value instanceof DataValue.RecordValue record && record.fields().keySet().equals(Set.of("operation", "reply")))
            return LibraryDeviceWrite.Receipt.decode(value);
        return LibraryMutation.Receipt.decodeValue(value);
    }
    static LibraryMutation.Version version(DataValue.RecordValue reply) {
        if (!reply.fields().keySet().equals(Set.of("code", "data"))
            || !reply.field("code").equals(new DataValue.TextValue("ok"))
            || !(reply.field("data") instanceof DataValue.RecordValue data)
            || !data.fields().keySet().equals(Set.of("version")))
            throw new IllegalArgumentException("Only completed mutations have book receipts");
        return LibraryMutation.Version.decode(data.field("version"));
    }
    static void validate(LibraryMutation mutation, DataValue.RecordValue reply) {
        var version = version(reply);
        if (!version.medium().equals(mutation.expected().medium()) || !version.entry().equals(mutation.entry())
            || version.revision() <= mutation.expected().revision())
            throw new IllegalArgumentException("Invalid completion version");
    }
}
