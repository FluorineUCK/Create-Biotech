package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;

/** Test-source-only issuer; production consumers cannot compose an output authority. */
public final class OutputGatewayTestFixture {
	private OutputGatewayTestFixture() {}

	public static OutputLogisticsGateway gateway(ProductionOutputContract contract,
		boolean serverThread, boolean loaded, boolean drained, UUID epoch,
		boolean packagerBuffersEmpty) {
		return gateway(contract, serverThread, loaded, drained, epoch, packagerBuffersEmpty, 0);
	}

	public static OutputLogisticsGateway gateway(ProductionOutputContract contract,
		boolean serverThread, boolean loaded, boolean drained, UUID epoch,
		boolean packagerBuffersEmpty, long elapsedTicks) {
		OutputLogisticsGateway.Authority authority = new OutputLogisticsGateway.Authority() {
			@Override public ProductionOutputContract.Snapshot captureContract() {
				return contract.snapshot();
			}
			@Override public OutputLogisticsGateway.ReadinessState captureReadiness(
				OutputLogisticsGateway.ReadinessRequest request) {
				Map<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> endpoints =
					new LinkedHashMap<>();
				request.watchedOutputRoutes().forEach(route -> endpoints.put(route,
					new OutputLogisticsGateway.EndpointReadiness(loaded, epoch, drained)));
				return new OutputLogisticsGateway.ReadinessState(endpoints,
					request.afterObservationSequence() + 1, loaded ? elapsedTicks : 0,
					loaded && drained ? elapsedTicks : 0);
			}
			@Override public OutputLogisticsGateway.DrainState captureDrain(
				OutputLogisticsGateway.DrainRequest request) {
				Map<CreateRouteKey, OutputLogisticsGateway.EndpointDrain> endpoints =
					new LinkedHashMap<>();
				request.watchedOutputRoutes().forEach(route -> endpoints.put(route,
					new OutputLogisticsGateway.EndpointDrain(loaded, epoch, drained)));
				return new OutputLogisticsGateway.DrainState(endpoints, packagerBuffersEmpty);
			}
		};
		Map<UUID, OutputLogisticsGateway.PersistedCommit> commits = new LinkedHashMap<>();
		return new OutputLogisticsGateway(() -> serverThread, authority,
			ignored -> new OutputLogisticsGateway.NativeOperation(UUID.randomUUID()),
			new OutputLogisticsGateway.CommitStore() {
				@Override public Optional<OutputLogisticsGateway.PersistedCommit> load(UUID intentId) {
					return Optional.ofNullable(commits.get(intentId));
				}
				@Override public void persist(OutputLogisticsGateway.PersistedCommit commit) {
					commits.put(commit.instruction().intentId(), commit);
				}
			});
	}
}
