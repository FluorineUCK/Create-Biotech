package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/** Production coordination facts, not membership or shared resources of a general Computer Cluster. */
public record ProductionBinding(UUID groupId, long revision, @Nullable Authority authority,
    List<UUID> logisticsIds) {
    /** Bound of the existing v2 production attachment format, not a new storage capacity. */
    public static final int MAX_NETWORKS = 32;

    public ProductionBinding {
        Objects.requireNonNull(groupId, "groupId");
        if (revision < 0) throw new IllegalArgumentException("negative binding revision");
        var ordered = new LinkedHashSet<UUID>();
        for (UUID network : Objects.requireNonNull(logisticsIds, "logisticsIds"))
            ordered.add(Objects.requireNonNull(network, "network"));
        if (ordered.size() > MAX_NETWORKS) throw new IllegalArgumentException("too many logistics networks");
        logisticsIds = List.copyOf(ordered);
    }

    public record Authority(Role role, UUID memberId) {
        public Authority { Objects.requireNonNull(role); Objects.requireNonNull(memberId); }
    }

    public enum Role { PANEL, LIBRARY, COORDINATOR }
}
