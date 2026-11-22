package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;

/** Test-source issuer for an opaque server-thread input commit receipt. */
final class AgentInputTestFacts {
	private AgentInputTestFacts() {}

	static CreateLogisticsGateway.CommitReceipt committed(CreateDispatchCommand command,
		int orderId) {
		class Store implements CreateLogisticsGateway.CommitStore {
			private CreateLogisticsGateway.PersistedCommit value;
			@Override public Optional<CreateLogisticsGateway.PersistedCommit> load(UUID intentId) {
				return value == null ? Optional.empty() : Optional.of(value);
			}
			@Override public void persist(CreateLogisticsGateway.PersistedCommit commit) {
				value = commit;
			}
		}
		CreateLogisticsGateway gateway = new CreateLogisticsGateway(
			ignored -> new CreateLogisticsGateway.CommitContext(command.agentId(),
				command.agentResetGeneration(), command.fence(), command.route(), true,
				command.sourceInventorySnapshotGeneration(), AgentInputPhase.DISPATCH_INTENT,
				command),
			ignored -> new CreateLogisticsGateway.NativeDispatchFact(true,
				List.of(new NativeOrderLedger.NativeOrderIdentity(orderId, Set.of(0)))),
			() -> true, new Store());
		return gateway.commit(command).confirmationReceipt().orElseThrow();
	}
}
