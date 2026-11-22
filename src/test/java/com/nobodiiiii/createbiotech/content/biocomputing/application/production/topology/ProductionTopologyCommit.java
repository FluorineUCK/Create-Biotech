package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.util.List;
import java.util.Objects;

/** Server-internal prepared production transaction; these capabilities are not programmable world-write nodes. */
public final class ProductionTopologyCommit {
    private ProductionTopologyCommit() {}
    public enum Outcome { COMMITTED, STALE, ROLLED_BACK, RECOVERY_FAILED }

    /** Each port owns immutable before/after production values and an exact host/version witness. */
    public interface Replica {
        boolean matchesExpected();
        boolean published();
        boolean withdrawBeforeCommit();
        void withdraw();
        void stage();
        void restore();
        void changed();
        void republish();
        void markUnavailable();
    }
    public interface Publication {
        boolean publish();
        boolean restore();
        void withdraw();
    }

    public static Outcome commit(List<Replica> replicas, Publication publication) {
        replicas=List.copyOf(replicas); Objects.requireNonNull(publication);
        if (replicas.isEmpty() || replicas.stream().anyMatch(replica -> !replica.matchesExpected())) return Outcome.STALE;
        var previouslyPublished=replicas.stream().map(Replica::published).toList();
        for (var replica : replicas)
            if (replica.published() && replica.withdrawBeforeCommit()) replica.withdraw();
        replicas.forEach(Replica::stage);
        replicas.forEach(Replica::changed);
        if (publication.publish()) return Outcome.COMMITTED;

        for (int i=0;i<replicas.size();i++)
            if (!previouslyPublished.get(i) && replicas.get(i).published()) replicas.get(i).withdraw();
        // Observers are notified only after every prepared member has regained its old production values.
        replicas.forEach(Replica::restore);
        replicas.forEach(Replica::changed);
        for (int i=0;i<replicas.size();i++) if (previouslyPublished.get(i)) replicas.get(i).republish();
        if (publication.restore()) return Outcome.ROLLED_BACK;

        publication.withdraw();
        for (var replica : replicas) {
            replica.withdraw();
            replica.markUnavailable();
        }
        replicas.forEach(Replica::changed);
        return Outcome.RECOVERY_FAILED;
    }
}
