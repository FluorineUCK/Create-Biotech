package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;

/** Strict durable schema for system request metadata, separate from player records. */
public final class RpcArchiveCodec {
	private RpcArchiveCodec() {}

	public static DataValue.RecordValue encode(RpcState state) {
		return Objects.requireNonNull(state).value();
	}

	public static RpcState decode(DataValue value) {
		try {
			requireFields(value, Set.of("version", "pending", "staged", "request", "reply"));
			long version = number(at(value, "version"));
			if (version < 1 || version > 2)
				throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported request schema version");
			return new RpcState(readNamed(at(value, "pending"), pending -> readPending(pending, version)),
				readNamed(at(value, "staged"), pending -> readPending(pending, version)),
				at(value, "request") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(readRequest(at(value, "request"))),
				at(value, "reply") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(readReply(at(value, "reply"), version)));
		} catch (ComputationException | IllegalArgumentException invalid) {
			if (invalid instanceof ArchiveException archive) throw archive;
			throw ArchiveException.invalid("Invalid request state: " + invalid.getMessage());
		}
	}

	static RequestCorrelation readCorrelation(DataValue value) {
		requireFields(value, Set.of("requester", "event_id", "ordinal"));
		return new RequestCorrelation(uuid(at(value, "requester")), uuid(at(value, "event_id")), index(at(value, "ordinal")));
	}

	static RequestContext readRequest(DataValue value) {
		requireFields(value, Set.of("correlation", "return_endpoint"));
		return new RequestContext(readCorrelation(at(value, "correlation")), string(at(value, "return_endpoint")));
	}

	private static RpcState.Pending readPending(DataValue value, long version) {
		requireFields(value, version == 1 ? Set.of("correlation", "reply_entry") : Set.of("correlation", "reply_entry", "continuation"));
		return new RpcState.Pending(readCorrelation(at(value, "correlation")), string(at(value, "reply_entry")),
			version == 1 || at(value, "continuation") == DataValue.NullValue.INSTANCE ? Optional.empty()
				: Optional.of(readContinuation(at(value, "continuation"))));
	}
	private static RequestContinuation readContinuation(DataValue value) {
		requireFields(value, Set.of("origin", "input", "captured"));
		return new RequestContinuation(readRequest(at(value, "origin")), string(at(value, "input")),
			at(value, "captured") == DataValue.NullValue.INSTANCE ? Optional.empty() : Optional.of(record(at(value, "captured"))));
	}

	private static ReplyContext readReply(DataValue value, long version) {
		requireFields(value, Set.of("slot", "pending"));
		return new ReplyContext(string(at(value, "slot")), readPending(at(value, "pending"), version));
	}

	private static void requireFields(DataValue value, Set<String> expected) {
		if (!fields(value).keySet().equals(expected)) throw ArchiveException.invalid("Unexpected request state fields");
	}

	private static UUID uuid(DataValue value) {
		String text = string(value);
		UUID id = UUID.fromString(text);
		if (!id.toString().equalsIgnoreCase(text)) throw ArchiveException.invalid("Expected canonical UUID");
		return id;
	}
}
