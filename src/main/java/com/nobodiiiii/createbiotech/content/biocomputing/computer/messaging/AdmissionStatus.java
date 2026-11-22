package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

public enum AdmissionStatus {
	ACCEPTED, DUPLICATE, STALE, IDENTITY_CONFLICT, QUEUE_FULL, SOURCE_LIMIT,
	CLOSED, UNAVAILABLE, NO_PROGRAM, TYPE_MISMATCH, MEMORY_LIMIT, BUSY, ARCHIVE_LIMIT, UNSUPPORTED, STALE_REPLY;

	public boolean accepted() {
		return this == ACCEPTED || this == DUPLICATE || this == STALE_REPLY;
	}
}
