package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One event graph and one computer's declared memory. Writes and sends are explicit named-output effects. */
public record ProgramDefinition(ComputationGraph graph, Map<String, Cell> cells, List<Write> writes, List<Send> sends,
	Map<String, Entry> entries) {
	public ProgramDefinition {
		Objects.requireNonNull(graph);
		cells = ComputationGraph.named(cells);
		writes = List.copyOf(writes);
		sends = List.copyOf(sends);
		entries = ComputationGraph.named(entries);
	}
	public ProgramDefinition(ComputationGraph graph, Map<String, Cell> cells, List<Write> writes, List<Send> sends) {
		this(graph, cells, writes, sends, Map.of());
	}
	public ProgramDefinition(ComputationGraph graph, Map<String, Cell> cells, List<Write> writes) {
		this(graph, cells, writes, List.of(), Map.of());
	}
	public Entry entry(String name) {
		if (name == null) return new Entry(graph, writes, sends);
		var entry = entries.get(name);
		if (entry == null) throw new IllegalArgumentException("Unknown program entry: " + name);
		return entry;
	}
	public record Entry(ComputationGraph graph, List<Write> writes, List<Send> sends) {
		public Entry {
			Objects.requireNonNull(graph);
			writes = List.copyOf(writes);
			sends = List.copyOf(sends);
		}
	}
	public record Cell(DataType type, DataValue initial) {
		public Cell {
			Objects.requireNonNull(type);
			Objects.requireNonNull(initial); LocalReferences.portable(initial);
            if(LocalReferences.contains(type))throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"initial","References cannot be stored in public cells");
			if (!type.accepts(initial))
				throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, "initial", "Initial value does not fit cell");
		}
	}
	public record Write(String cell, String output) {
		public Write { ComputationGraph.requireName(cell); ComputationGraph.requireName(output); }
	}
	public enum Dispatch { MESSAGE, REQUEST, REPLY }
	public record Continuation(String captureOutput, String captureInput) {
		public Continuation { ComputationGraph.requireName(captureOutput); ComputationGraph.requireName(captureInput); }
	}
	public record Send(String endpointOutput, String payloadOutput, Dispatch dispatch, String requestSlot, String replyEntry,
		Optional<Continuation> continuation, Optional<String> conditionOutput) {
		public Send {
			Objects.requireNonNull(dispatch);
			Objects.requireNonNull(continuation);
			Objects.requireNonNull(conditionOutput).ifPresent(ComputationGraph::requireName);
			ComputationGraph.requireName(payloadOutput);
			boolean endpointRequired = dispatch != Dispatch.REPLY;
			boolean requestFieldsRequired = dispatch == Dispatch.REQUEST;
			if (endpointRequired) ComputationGraph.requireName(endpointOutput);
			if (requestFieldsRequired) {
				ComputationGraph.requireName(requestSlot);
				ComputationGraph.requireName(replyEntry);
			}
			if ((!endpointRequired && !"".equals(endpointOutput))
				|| (!requestFieldsRequired && (!"".equals(requestSlot) || !"".equals(replyEntry) || continuation.isPresent())))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, "send",
					"Dispatch contains fields that do not apply to its mode");
		}
		public Send(String endpointOutput, String payloadOutput, Dispatch dispatch, String requestSlot, String replyEntry,
			Optional<Continuation> continuation) {
			this(endpointOutput, payloadOutput, dispatch, requestSlot, replyEntry, continuation, Optional.empty());
		}
		public Send(String endpointOutput, String payloadOutput, Dispatch dispatch, String requestSlot, String replyEntry) {
			this(endpointOutput, payloadOutput, dispatch, requestSlot, replyEntry, Optional.empty());
		}
		public Send(String endpointOutput, String payloadOutput) {
			this(endpointOutput, payloadOutput, Dispatch.MESSAGE, "", "");
		}
		public static Send request(String endpointOutput, String payloadOutput, String slot, String replyEntry) {
			return new Send(endpointOutput, payloadOutput, Dispatch.REQUEST, slot, replyEntry);
		}
		public static Send reply(String payloadOutput) {
			return new Send("", payloadOutput, Dispatch.REPLY, "", "");
		}
		public static Send continueRequest(String endpointOutput, String payloadOutput, String slot, String replyEntry,
			String captureOutput, String captureInput) {
			return new Send(endpointOutput, payloadOutput, Dispatch.REQUEST, slot, replyEntry,
				Optional.of(new Continuation(captureOutput, captureInput)));
		}
		public boolean requiresRequest() { return dispatch == Dispatch.REPLY || continuation.isPresent(); }
		public Send when(String output) {
			return new Send(endpointOutput, payloadOutput, dispatch, requestSlot, replyEntry, continuation, Optional.of(output));
		}
		public boolean enabled(DataValue.RecordValue outputs) {
			if (conditionOutput.isEmpty()) return true;
			var value = outputs.field(conditionOutput.orElseThrow());
			if (value instanceof DataValue.BooleanValue condition) return condition.value();
			throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, conditionOutput.orElseThrow(),
				"Send condition must be boolean");
		}
	}
}
