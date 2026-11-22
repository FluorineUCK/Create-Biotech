package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Immutable request bookkeeping. Only a successful event publishes staged generations. */
public record RpcState(Map<String, Pending> pending, Map<String, Pending> staged,
	Optional<RequestContext> request, Optional<ReplyContext> reply) {

	public record Pending(RequestCorrelation correlation, String replyEntry, Optional<RequestContinuation> continuation) {
		public Pending {
			Objects.requireNonNull(correlation);
			requireName(replyEntry);
			Objects.requireNonNull(continuation);
		}
		public Pending(RequestCorrelation correlation, String replyEntry) { this(correlation, replyEntry, Optional.empty()); }
		public Pending capture(DataValue.RecordValue value) {
			return new Pending(correlation, replyEntry, Optional.of(continuation.orElseThrow().capture(value)));
		}

		public DataValue.RecordValue value() {
			return new DataValue.RecordValue(Map.of("correlation", correlation.value(),
				"reply_entry", new DataValue.TextValue(replyEntry),
				"continuation", continuation.<DataValue>map(RequestContinuation::value).orElse(DataValue.NullValue.INSTANCE)));
		}
	}

	public RpcState {
		pending = copyNamed(pending);
		staged = copyNamed(staged);
		Objects.requireNonNull(request);
		Objects.requireNonNull(reply);
		if (reply.isPresent() && !request.equals(reply.orElseThrow().pending().continuation().map(RequestContinuation::origin)))
			throw new IllegalArgumentException("Callback origin must match its pending continuation");
		if (pending.values().stream().anyMatch(value -> value.continuation().filter(c -> c.captured().isEmpty()).isPresent())
			|| reply.stream().anyMatch(value -> value.pending().continuation().filter(c -> c.captured().isEmpty()).isPresent()))
			throw new IllegalArgumentException("Committed continuation is missing its capture");
		// An accepted staged state must also be publishable. Validate the projected result,
		// allowing collisions that are actually removed by consuming/replacing the old slot.
		if (!staged.isEmpty()) requireUnique(mergedPending(pending, staged, reply).values());
	}

	public static RpcState empty() {
		return new RpcState(Map.of(), Map.of(), Optional.empty(), Optional.empty());
	}

	public Optional<ReplyContext> matching(RequestCorrelation correlation) {
		Objects.requireNonNull(correlation);
		return pending.entrySet().stream().filter(entry -> entry.getValue().correlation().equals(correlation))
			.map(entry -> new ReplyContext(entry.getKey(), entry.getValue())).findFirst();
	}

	public RpcState begin(Optional<RequestContext> request, Optional<ReplyContext> reply, Map<String, Pending> staged) {
		reply.ifPresent(context -> {
			if (!context.pending().equals(pending.get(context.slot())))
				throw new IllegalArgumentException("Reply no longer matches the pending request");
		});
		return new RpcState(pending, staged, request, reply);
	}

	public RpcState commit() {
		return new RpcState(mergedPending(pending, staged, reply), Map.of(), request, reply);
	}
	public RpcState commit(Map<String, Pending> captured) {
		return new RpcState(pending, captured, request, reply).commit();
	}

	public RpcState discardStaged() {
		return staged.isEmpty() ? this : new RpcState(pending, Map.of(), request, reply);
	}

	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(2),
			"pending", namedValue(pending), "staged", namedValue(staged),
			"request", request.<DataValue>map(RequestContext::value).orElse(DataValue.NullValue.INSTANCE),
			"reply", reply.<DataValue>map(ReplyContext::value).orElse(DataValue.NullValue.INSTANCE)));
	}

	public long storageUnits() {
		return pending.isEmpty() && staged.isEmpty() && request.isEmpty() && reply.isEmpty() ? 0 : value().storageUnits();
	}

	static void requireName(String name) {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Expected a nonblank literal name");
	}

	private static Map<String, Pending> copyNamed(Map<String, Pending> source) {
		var copy = new LinkedHashMap<String, Pending>();
		source.forEach((slot, value) -> {
			requireName(slot);
			Objects.requireNonNull(value);
			copy.put(slot, value);
		});
		requireUnique(copy.values());
		return Collections.unmodifiableMap(copy);
	}

	private static Map<String, Pending> mergedPending(Map<String, Pending> pending, Map<String, Pending> staged,
		Optional<ReplyContext> reply) {
		var next = new LinkedHashMap<>(pending);
		reply.ifPresent(context -> next.remove(context.slot(), context.pending()));
		next.putAll(staged);
		return next;
	}

	private static void requireUnique(Collection<Pending> values) {
		var correlations = new HashSet<RequestCorrelation>();
		for (var value : values)
			if (!correlations.add(value.correlation()))
				throw new IllegalArgumentException("A request correlation cannot occupy multiple slots");
	}

	private static DataValue.RecordValue namedValue(Map<String, Pending> values) {
		var fields = new LinkedHashMap<String, DataValue>();
		values.forEach((slot, value) -> fields.put(slot, value.value()));
		return new DataValue.RecordValue(fields);
	}
}
