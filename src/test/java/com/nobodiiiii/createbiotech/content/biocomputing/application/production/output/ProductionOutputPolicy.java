package com.nobodiiiii.createbiotech.content.biocomputing.application.production.output;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentSignalSelector;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.AttachedInventoryBindingResolver;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Immutable Agent output policy captured when a Run is admitted. */
public record ProductionOutputPolicy(long generation, AgentEndpointState.OutputMode deliveryMode,
	AgentCompletionMode completionMode, Optional<AgentSignalSelector> signalSelector,
	CreateRouteKey returnWarehouseRoute,
	Set<CreateRouteKey> observedOutputRoutes, Map<StackKey, RequiredOutput> requiredOutputs,
	Set<StackKey> incidentalOutputs, Optional<StackKey> primaryOutput, boolean noOutput,
	boolean probabilisticPrimary, int quietWindowTicks, int firstOutputTimeoutTicks,
	int maxNoProgressRuns) {
	public ProductionOutputPolicy {
		if (generation < 0) throw new IllegalArgumentException("Policy generation cannot be negative");
		deliveryMode = Objects.requireNonNull(deliveryMode, "deliveryMode");
		completionMode = Objects.requireNonNull(completionMode, "completionMode");
		signalSelector = Objects.requireNonNull(signalSelector, "signalSelector");
		if ((completionMode == AgentCompletionMode.SIGNAL) != signalSelector.isPresent())
			throw new IllegalArgumentException("SIGNAL policy needs exactly one frozen selector");
		returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute,
			"returnWarehouseRoute");
		observedOutputRoutes = Set.copyOf(Objects.requireNonNull(observedOutputRoutes,
			"observedOutputRoutes"));
		requiredOutputs = Map.copyOf(Objects.requireNonNull(requiredOutputs, "requiredOutputs"));
		incidentalOutputs = Set.copyOf(Objects.requireNonNull(incidentalOutputs,
			"incidentalOutputs"));
		primaryOutput = Objects.requireNonNull(primaryOutput, "primaryOutput");
		if (quietWindowTicks <= 0 || firstOutputTimeoutTicks <= 0 || maxNoProgressRuns <= 0)
			throw new IllegalArgumentException("Output timing budgets must be positive");
		if (observedOutputRoutes.isEmpty())
			throw new IllegalArgumentException("An output policy needs a watched output address");
		for (Map.Entry<StackKey, RequiredOutput> entry : requiredOutputs.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "required output item");
			RequiredOutput required = Objects.requireNonNull(entry.getValue(),
				"required output target");
			if (required.receiver() instanceof OutputReceiver.Bare bare) {
				StackKey proofItem = bare.proof() instanceof BareReceiptProof.Network network
					? network.scope().item()
					: ((BareReceiptProof.Attached) bare.proof()).scope().item();
				if (!proofItem.equals(entry.getKey()))
					throw new IllegalArgumentException("Bare output proof item differs from required output");
				if (bare.proof() instanceof BareReceiptProof.Network network) {
					if (!network.scope().logisticsNetworkId().equals(
						required.destination().logisticsNetworkId()))
						throw new IllegalArgumentException(
							"Network proof differs from output destination network");
				} else {
					BareReceiptProof.Attached attached = (BareReceiptProof.Attached) bare.proof();
					AttachedInventoryBindingResolver.BindingFact binding = attached.binding();
					if (!binding.destination().equals(required.destination())
						|| !binding.packager().equals(attached.scope().packager())
						|| !binding.inventory().equals(attached.scope().inventory()))
						throw new IllegalArgumentException(
							"Attached inventory proof differs from authoritative route binding");
				}
			}
		}
		if (noOutput && (!requiredOutputs.isEmpty() || primaryOutput.isPresent()
			|| probabilisticPrimary))
			throw new IllegalArgumentException("NO_OUTPUT cannot declare required output");
		if (!noOutput && completionMode == AgentCompletionMode.TARGET
			&& (primaryOutput.isEmpty() || !requiredOutputs.containsKey(primaryOutput.orElseThrow())))
			throw new IllegalArgumentException("TARGET needs a configured primary output");
		if (probabilisticPrimary && (primaryOutput.isEmpty()
			|| !requiredOutputs.containsKey(primaryOutput.orElseThrow())))
			throw new IllegalArgumentException("Probabilistic output needs a primary target");
	}

	public record RequiredOutput(CreateRouteKey destination, long targetCount,
		OutputReceiver receiver) {
		public RequiredOutput {
			destination = Objects.requireNonNull(destination, "destination");
			receiver = Objects.requireNonNull(receiver, "receiver");
			if (targetCount <= 0)
				throw new IllegalArgumentException("Required output target must be positive");
		}
	}

	/** Exactly one frozen receiver kind owns proof of delivery for a required output. */
	public sealed interface OutputReceiver {
		record Bare(BareReceiptProof proof) implements OutputReceiver {
			public Bare { proof = Objects.requireNonNull(proof, "proof"); }
			public StockObservationScope scope() { return proof.scope(); }
		}

		record Agent(UUID agentId) implements OutputReceiver {
			public Agent { agentId = Objects.requireNonNull(agentId, "agentId"); }
		}
	}

	public sealed interface BareReceiptProof {
		StockObservationScope scope();

		record Network(StockObservationScope.NetworkSummary scope) implements BareReceiptProof {
			public Network { scope = Objects.requireNonNull(scope, "scope"); }
		}

		record Attached(StockObservationScope.AttachedInventory scope,
			AttachedInventoryBindingResolver.BindingFact binding) implements BareReceiptProof {
			public Attached {
				scope = Objects.requireNonNull(scope, "scope");
				binding = Objects.requireNonNull(binding, "binding");
			}
		}
	}
}
