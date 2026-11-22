package com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect;

import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;

/** Checked production effect dispatch; not a generic Computer world-write service. */
public final class ProductionEffectCommitter {
    private static final String PUBLICATION_PREFIX = "panel_publication/";

    /** Exact application facts and operations; the live driver validates epoch/member/fences first. */
    public interface Access {
        boolean acceptsWork();
        boolean isServerThread();
        Optional<GoalObservationState> observation(UUID requestId);
        StockObservationService.Observation observe(StockObservationScope scope);
        boolean publish(WorldFact.StockObservation fact);
        boolean create(ProductionEffect.CreateDispatch request);
        boolean output(ProductionEffect.OutputPackageRequest request);
        void persist(String key, ProductionEffect.PanelSnapshotPublication publication);
    }

    private final Access access;

    public ProductionEffectCommitter(Access access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    public boolean commit(ProductionEffect effect) {
        Objects.requireNonNull(effect, "effect");
        if (!access.acceptsWork() || !access.isServerThread() || !ProductionEffectPolicy.supported(effect))
            return false;
        return switch (effect) {
            case ProductionEffect.CreateDispatch request -> access.create(request);
            case ProductionEffect.StockSampleRequest request -> sample(request);
            case ProductionEffect.RadioSend ignored -> false;
            case ProductionEffect.AgentTicketOffer ignored -> false;
            case ProductionEffect.PanelSnapshotPublication publication -> {
                access.persist(publicationKey(publication.intentId()), publication);
                yield true;
            }
            case ProductionEffect.LibraryQuery ignored -> false;
            case ProductionEffect.OutputPackageRequest request -> access.output(request);
        };
    }

    private boolean sample(ProductionEffect.StockSampleRequest sample) {
        GoalObservationState evidence = access.observation(sample.requestId()).orElse(null);
        if (evidence == null || !evidence.clusterId().equals(sample.clusterId())) return false;
        if (evidence.scope() instanceof StockObservationScope.NetworkSummary network
            && !network.logisticsNetworkId().equals(sample.route().logisticsNetworkId())) return false;
        StockObservationService.Observation observation = access.observe(evidence.scope());
        if (observation.status() == StockObservationService.Status.WAIT_STOCK_OBSERVATION)
            return false;
        return access.publish(new WorldFact.StockObservation(sample.clusterId(),
            sample.fence().epochId(), sample.generation(), sample.requestId(), sample.route(),
            Map.of(evidence.itemKey(), observation.actualCount())));
    }

    public static String publicationKey(UUID intentId) {
        return PUBLICATION_PREFIX + Objects.requireNonNull(intentId, "intentId");
    }
}
