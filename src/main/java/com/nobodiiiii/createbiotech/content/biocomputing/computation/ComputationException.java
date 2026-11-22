package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.Objects;

/** Machine-readable failures, kept separate from an ErrorValue received as ordinary input data. */
public final class ComputationException extends RuntimeException {
	public enum Code {
		INVALID_GRAPH, MISSING_REFERENCE, GRAPH_CYCLE, UNKNOWN_MODULE, RECURSIVE_MODULE,
		TYPE_MISMATCH, MISSING_FIELD, INDEX_OUT_OF_BOUNDS, INVALID_ARGUMENT, WRITE_CONFLICT, PROGRAM_LIMIT, MEMORY_LIMIT,
		ARITHMETIC_OVERFLOW, DIVISION_BY_ZERO, BUSY, INVALID_CHECKPOINT, XBUS_PEER_PAUSED, XBUS_OPERATION_RETIRED,
		TASK_CANCELLED, TASK_CANCELLED_ADMITTED
	}

	private final Code code;
	private final String location;

	public ComputationException(Code code, String location, String message) {
		super(message);
		this.code = Objects.requireNonNull(code);
		this.location = Objects.requireNonNull(location);
	}

	public Code code() { return code; }
	public String location() { return location; }
}
