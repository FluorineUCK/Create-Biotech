package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

/** Immutable, bounded observations captured on the Minecraft server thread. */
public sealed interface WorldFact permits WorldFact.ClusterEpochFact, WorldFact.MemberLoadState,
	WorldFact.LibraryReply, WorldFact.RadioMessage, WorldFact.StockObservation,
	WorldFact.PackageAccepted, WorldFact.NativeOrderProgress, WorldFact.OutputObserved,
	WorldFact.Signal, WorldFact.PlayerCommand, WorldFact.IntentResult {
	int MAX_LIST_ENTRIES = 256;
	int MAX_TEXT_LENGTH = 4096;

	UUID clusterId();
	UUID epochId();
	long generation();

	record ClusterEpochFact(UUID clusterId, UUID epochId, long generation) implements WorldFact {
		public ClusterEpochFact { common(clusterId, epochId, generation); }
	}

	record MemberLoadState(UUID clusterId, UUID epochId, long generation, UUID computerId,
		ProductionResourceProfile profile, boolean loaded, long routeGeneration, long fence) implements WorldFact {
		public MemberLoadState {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(computerId, "computerId");
			Objects.requireNonNull(profile, "profile");
			if (routeGeneration < 0 || fence < 0) throw new IllegalArgumentException("negative member fence");
		}
	}

	record LibraryReply(UUID clusterId, UUID epochId, long generation, UUID queryId,
		List<UUID> patternIds) implements WorldFact {
		public LibraryReply {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(queryId, "queryId");
			patternIds = boundedList(patternIds, "patternIds");
		}
	}

	record RadioMessage(UUID clusterId, UUID epochId, long generation, UUID sourceComputerId,
		String payload) implements WorldFact {
		public RadioMessage {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(sourceComputerId, "sourceComputerId");
			payload = boundedText(payload, "payload");
		}
	}

	record StockObservation(UUID clusterId, UUID epochId, long generation, UUID requestId,
		CreateRouteKey route, Map<String, Long> quantities) implements WorldFact {
		public StockObservation {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(requestId, "requestId");
			Objects.requireNonNull(route, "route");
			quantities = boundedPositiveMap(quantities);
		}
	}

	record PackageAccepted(UUID clusterId, UUID epochId, long generation, UUID attemptId,
		UUID packageId) implements WorldFact {
		public PackageAccepted {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(attemptId, "attemptId"); Objects.requireNonNull(packageId, "packageId");
		}
	}

	record NativeOrderProgress(UUID clusterId, UUID epochId, long generation, UUID attemptId,
		long ordered, long completed, boolean inputClosed) implements WorldFact {
		public NativeOrderProgress {
			common(clusterId, epochId, generation);
			Objects.requireNonNull(attemptId, "attemptId");
			if (ordered < 0 || completed < 0 || completed > ordered) throw new IllegalArgumentException("invalid native order progress");
		}
	}

	record OutputObserved(UUID clusterId, UUID epochId, long generation, UUID runId,
		Map<String, Long> quantities) implements WorldFact {
		public OutputObserved {
			common(clusterId, epochId, generation); Objects.requireNonNull(runId, "runId");
			quantities = boundedPositiveMap(quantities);
		}
	}

	record Signal(UUID clusterId, UUID epochId, long generation, String name, long value) implements WorldFact {
		public Signal { common(clusterId, epochId, generation); name = boundedText(name, "name"); }
	}

	record PlayerCommand(UUID clusterId, UUID epochId, long generation, Command command) implements WorldFact {
		public PlayerCommand { common(clusterId, epochId, generation); Objects.requireNonNull(command, "command"); }
	}

	record IntentResult(UUID clusterId, UUID epochId, long generation, UUID intentId,
		boolean committed, String detail) implements WorldFact {
		public IntentResult {
			common(clusterId, epochId, generation); Objects.requireNonNull(intentId, "intentId");
			detail = boundedText(detail, "detail");
		}
	}

	sealed interface Command permits Command.SubmitGoal, Command.ChangePriority,
		Command.DispatchCreate, Command.CancelGoal, Command.CreditActualStock {
		record SubmitGoal(GoalRecord goal, ProcessKind processKind) implements Command {
			public SubmitGoal { Objects.requireNonNull(goal, "goal"); Objects.requireNonNull(processKind, "processKind"); }
		}
		record ChangePriority(UUID goalId, PolicySnapshot policy) implements Command {
			public ChangePriority { Objects.requireNonNull(goalId, "goalId"); Objects.requireNonNull(policy, "policy"); }
		}
		record DispatchCreate(UUID processId, UUID runId, CreateRouteKey route,
			List<OrderedIngredient> ingredients) implements Command {
			public DispatchCreate {
				Objects.requireNonNull(processId, "processId"); Objects.requireNonNull(runId, "runId");
				Objects.requireNonNull(route, "route");
				ingredients = boundedList(ingredients, "ingredients");
			}
		}
		record CancelGoal(UUID goalId) implements Command {
			public CancelGoal { Objects.requireNonNull(goalId, "goalId"); }
		}
		record CreditActualStock(UUID goalId, long actualCount) implements Command {
			public CreditActualStock {
				Objects.requireNonNull(goalId, "goalId");
				if (actualCount < 0) throw new IllegalArgumentException("Actual stock cannot be negative");
			}
		}
	}

	private static void common(UUID clusterId, UUID epochId, long generation) {
		Objects.requireNonNull(clusterId, "clusterId"); Objects.requireNonNull(epochId, "epochId");
		if (generation < 0) throw new IllegalArgumentException("negative generation");
	}

	private static String boundedText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException(name + " exceeds bound");
		return value;
	}

	private static <T> List<T> boundedList(List<T> values, String name) {
		values = List.copyOf(Objects.requireNonNull(values, name));
		if (values.size() > MAX_LIST_ENTRIES) throw new IllegalArgumentException(name + " exceeds bound");
		return values;
	}

	private static Map<String, Long> boundedPositiveMap(Map<String, Long> values) {
		values = Map.copyOf(Objects.requireNonNull(values, "quantities"));
		if (values.size() > MAX_LIST_ENTRIES) throw new IllegalArgumentException("quantities exceed bound");
		values.forEach((key, value) -> {
			boundedText(key, "quantity key");
			if (value == null || value < 0) throw new IllegalArgumentException("negative quantity");
		});
		return values;
	}
}
