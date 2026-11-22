package com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Finite production effects; only checked application hosts may commit them. Not generic world writes. */
public sealed interface ProductionEffect permits ProductionEffect.LibraryQuery, ProductionEffect.RadioSend,
	ProductionEffect.StockSampleRequest, ProductionEffect.AgentTicketOffer, ProductionEffect.CreateDispatch,
	ProductionEffect.OutputPackageRequest, ProductionEffect.PanelSnapshotPublication {
	// Historical durable representation limits, not hardware capacity or execution budget.
	int MAX_LIST_ENTRIES = 256;
	int MAX_TEXT_LENGTH = 4096;

	UUID intentId();
	UUID clusterId();
	long generation();
	TargetFence fence();

	record TargetFence(UUID epochId, UUID targetId, long routeGeneration, long validationFence) {
		public TargetFence {
			Objects.requireNonNull(epochId, "epochId"); Objects.requireNonNull(targetId, "targetId");
			if (routeGeneration < 0 || validationFence < 0) throw new IllegalArgumentException("negative target fence");
		}
	}


	record PanelRow(UUID goalId, int priority, String state) {
		public PanelRow {
			Objects.requireNonNull(goalId, "goalId"); state = text(state, "state");
		}
	}

	record LibraryQuery(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID queryId, UUID patternId) implements ProductionEffect {
		public LibraryQuery { common(intentId, clusterId, generation, fence); Objects.requireNonNull(queryId); Objects.requireNonNull(patternId); }
	}

	record RadioSend(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID messageId, String payload) implements ProductionEffect {
		public RadioSend { common(intentId, clusterId, generation, fence); Objects.requireNonNull(messageId); payload = text(payload, "payload"); }
	}

	record StockSampleRequest(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID requestId, CreateRouteKey route) implements ProductionEffect {
		public StockSampleRequest { common(intentId, clusterId, generation, fence); Objects.requireNonNull(requestId); Objects.requireNonNull(route); }
	}

	record AgentTicketOffer(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID processId, UUID agentId) implements ProductionEffect {
		public AgentTicketOffer { common(intentId, clusterId, generation, fence); Objects.requireNonNull(processId); Objects.requireNonNull(agentId); }
	}

	record CreateDispatch(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID processId, UUID runId, CreateRouteKey route,
		List<OrderedIngredient> ingredients) implements ProductionEffect {
		public CreateDispatch(UUID intentId, UUID clusterId, long generation, TargetFence fence,
			UUID runId, CreateRouteKey route, List<OrderedIngredient> ingredients) {
			this(intentId, clusterId, generation, fence, runId, runId, route, ingredients);
		}
		public CreateDispatch {
			common(intentId, clusterId, generation, fence); Objects.requireNonNull(processId);
			Objects.requireNonNull(runId); Objects.requireNonNull(route);
			ingredients = list(ingredients, "ingredients");
			if (ingredients.isEmpty()) throw new IllegalArgumentException("Create dispatch needs ingredients");
		}
	}

	record OutputPackageRequest(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		UUID runId, CreateRouteKey destination, StackKey exactOutput) implements ProductionEffect {
		public OutputPackageRequest {
			common(intentId, clusterId, generation, fence); Objects.requireNonNull(runId);
			Objects.requireNonNull(destination); Objects.requireNonNull(exactOutput);
		}
	}

	record PanelSnapshotPublication(UUID intentId, UUID clusterId, long generation, TargetFence fence,
		List<PanelRow> rows) implements ProductionEffect {
		public PanelSnapshotPublication { common(intentId, clusterId, generation, fence); rows = list(rows, "rows"); }
	}

	private static void common(UUID intentId, UUID clusterId, long generation, TargetFence fence) {
		Objects.requireNonNull(intentId, "intentId"); Objects.requireNonNull(clusterId, "clusterId"); Objects.requireNonNull(fence, "fence");
		if (generation < 0) throw new IllegalArgumentException("negative intent generation");
	}
	private static String text(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException(name + " exceeds bound");
		return value;
	}
	private static <T> List<T> list(List<T> values, String name) {
		values = List.copyOf(Objects.requireNonNull(values, name));
		if (values.size() > MAX_LIST_ENTRIES) throw new IllegalArgumentException(name + " exceeds bound");
		return values;
	}
}
