package com.nobodiiiii.createbiotech.content.biocomputing.application.production.output;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Durable per-event proof that sealed output left every Agent and Packager buffer. */
public record ProductionOutputLedger(UUID runId, UUID goalId, long fence,
	UUID outputObservationEpoch, long outputPolicyGeneration,
	CreateRouteKey returnWarehouseRoute, Map<UUID, DispatchedEvent> dispatchedEvents,
	Map<CreateRouteKey, Map<StackKey, Long>> dispatchedOutputs,
	Map<StackKey, Long> creditedOutputs, SealFact sealFact) {
	public ProductionOutputLedger {
		runId = Objects.requireNonNull(runId, "runId");
		goalId = Objects.requireNonNull(goalId, "goalId");
		outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
			"outputObservationEpoch");
		returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute,
			"returnWarehouseRoute");
		if (fence < 0 || outputPolicyGeneration < 0)
			throw new IllegalArgumentException("Output ledger generations cannot be negative");
		dispatchedEvents = immutableEvents(dispatchedEvents);
		dispatchedOutputs = immutableNestedCounts(dispatchedOutputs);
		creditedOutputs = immutableCounts(creditedOutputs);
		sealFact = Objects.requireNonNull(sealFact, "sealFact");
		if (!aggregateDispatched(dispatchedEvents).equals(dispatchedOutputs))
			throw new IllegalArgumentException("Dispatched aggregate violates per-event conservation");
		if (!aggregateCredited(dispatchedEvents).equals(creditedOutputs))
			throw new IllegalArgumentException("Credited aggregate violates per-event classification");
	}

	static Map<CreateRouteKey, Map<StackKey, Long>> aggregateDispatched(
		Map<UUID, DispatchedEvent> events) {
		LinkedHashMap<CreateRouteKey, LinkedHashMap<StackKey, Long>> mutable = new LinkedHashMap<>();
		for (DispatchedEvent event : events.values())
			mutable.computeIfAbsent(event.destination(), ignored -> new LinkedHashMap<>())
				.merge(event.stack(), event.count(), Math::addExact);
		LinkedHashMap<CreateRouteKey, Map<StackKey, Long>> result = new LinkedHashMap<>();
		mutable.forEach((route, counts) -> result.put(route, Map.copyOf(counts)));
		return Map.copyOf(result);
	}

	private static Map<StackKey, Long> aggregateCredited(Map<UUID, DispatchedEvent> events) {
		LinkedHashMap<StackKey, Long> result = new LinkedHashMap<>();
		for (DispatchedEvent event : events.values())
			if (event.credited()) result.merge(event.stack(), event.count(), Math::addExact);
		return Map.copyOf(result);
	}

	private static Map<UUID, DispatchedEvent> immutableEvents(Map<UUID, DispatchedEvent> values) {
		LinkedHashMap<UUID, DispatchedEvent> copied = new LinkedHashMap<>();
		Objects.requireNonNull(values, "dispatchedEvents").forEach((eventId, event) -> {
			Objects.requireNonNull(eventId, "eventId");
			Objects.requireNonNull(event, "dispatched event");
			if (!eventId.equals(event.eventId()))
				throw new IllegalArgumentException("Dispatch event key differs from its identity");
			copied.put(eventId, event);
		});
		return Map.copyOf(copied);
	}

	private static Map<CreateRouteKey, Map<StackKey, Long>> immutableNestedCounts(
		Map<CreateRouteKey, Map<StackKey, Long>> values) {
		LinkedHashMap<CreateRouteKey, Map<StackKey, Long>> copied = new LinkedHashMap<>();
		Objects.requireNonNull(values, "dispatchedOutputs").forEach((route, counts) ->
			copied.put(Objects.requireNonNull(route, "dispatch route"), immutableCounts(counts)));
		return Map.copyOf(copied);
	}

	private static Map<StackKey, Long> immutableCounts(Map<StackKey, Long> values) {
		LinkedHashMap<StackKey, Long> copied = new LinkedHashMap<>();
		Objects.requireNonNull(values, "counts").forEach((stack, count) -> {
			Objects.requireNonNull(stack, "stack");
			if (count == null || count <= 0)
				throw new IllegalArgumentException("Output counts must be positive");
			copied.put(stack, count);
		});
		return Map.copyOf(copied);
	}

	public record DispatchedEvent(UUID eventId, UUID nativeOperationId,
		CreateRouteKey destination, StackKey stack, long count, boolean credited) {
		public DispatchedEvent {
			eventId = Objects.requireNonNull(eventId, "eventId");
			nativeOperationId = Objects.requireNonNull(nativeOperationId, "nativeOperationId");
			destination = Objects.requireNonNull(destination, "destination");
			stack = Objects.requireNonNull(stack, "stack");
			if (count <= 0) throw new IllegalArgumentException("Dispatched count must be positive");
		}
	}

	public record SealFact(long primaryCount, boolean probabilisticShortfall,
		boolean requiresAnotherRun, int consecutiveNoProgressRuns,
		boolean probabilityStalled, CompletionBoundary completionBoundary) {
		public SealFact {
			completionBoundary = Objects.requireNonNull(completionBoundary, "completionBoundary");
			if (primaryCount < 0 || consecutiveNoProgressRuns < 0)
				throw new IllegalArgumentException("Seal fact counts cannot be negative");
			if (probabilityStalled && requiresAnotherRun)
				throw new IllegalArgumentException("Stalled probability cannot request another Run");
		}
	}

	/** Durable evidence for the exact output-completion boundary which authorized sealing. */
	public record CompletionBoundary(AgentCompletionMode completionMode, BoundaryReason reason,
		boolean inputEnded, boolean signalObserved, boolean firstOutputObserved,
		long firstOutputElapsedTicks, long quietElapsedTicks, long probabilityElapsedTicks,
		long readinessObservationSequence, UUID outputObservationEpoch,
		Map<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> endpointReadiness) {
		public CompletionBoundary {
			completionMode = Objects.requireNonNull(completionMode, "completionMode");
			reason = Objects.requireNonNull(reason, "reason");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			endpointReadiness = Map.copyOf(Objects.requireNonNull(endpointReadiness,
				"endpointReadiness"));
			if (firstOutputElapsedTicks < 0 || quietElapsedTicks < 0
				|| probabilityElapsedTicks < 0 || readinessObservationSequence < -1)
				throw new IllegalArgumentException("Completion boundary clocks cannot be negative");
		}
	}

	public enum BoundaryReason {
		TARGET_REACHED,
		SIGNAL_OBSERVED,
		QUIET_WINDOW,
		NO_OUTPUT_TIMEOUT,
		PROBABILITY_TIMEOUT
	}
}
