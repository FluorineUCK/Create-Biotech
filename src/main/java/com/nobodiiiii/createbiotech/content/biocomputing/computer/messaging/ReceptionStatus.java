package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

/** Direct handoff result, deliberately distinct from buffered admission. RECEIVED is not business completion. */
public enum ReceptionStatus {
    RECEIVED, WAITING_FOR_RECEIVER, PAUSED, TARGET_CHANGED, CLOSED, UNAVAILABLE, NO_PROGRAM,
    UNSUPPORTED, TYPE_MISMATCH, MEMORY_LIMIT, ARCHIVE_LIMIT, SOURCE_LIMIT, STALE, IDENTITY_CONFLICT,
    LEGACY_DELIVERY, FAILED;
}
