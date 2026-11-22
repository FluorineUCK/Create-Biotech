package com.nobodiiiii.createbiotech.content.factorycluster;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingCodec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;

/** Temporary value projection for legacy production consumers; the application owns normalization and persistence. */
public record ClusterBinding(UUID clusterId, long revision,
    @Nullable ClusterAuthority authority, List<LogisticsBinding> logisticsBindings) {
    public static final int DATA_VERSION = ProductionBindingCodec.VERSION;
    public static final int MAX_BINDINGS = ProductionBinding.MAX_NETWORKS;

    public ClusterBinding {
        var value = toProduction(clusterId, revision, authority, logisticsBindings);
        logisticsBindings = value.logisticsIds().stream().map(LogisticsBinding::new).toList();
    }

    public ProductionBinding productionBinding() {
        return toProduction(clusterId, revision, authority, logisticsBindings);
    }

    private static ProductionBinding toProduction(UUID group, long revision, @Nullable ClusterAuthority authority,
        List<LogisticsBinding> networks) {
        return new ProductionBinding(group, revision, authority == null ? null : new ProductionBinding.Authority(
            switch (authority.type()) {
                case PANEL -> ProductionBinding.Role.PANEL;
                case PATTERN_CORE -> ProductionBinding.Role.LIBRARY;
                case COMPUTER_COORDINATOR -> ProductionBinding.Role.COORDINATOR;
            }, authority.memberId()), Objects.requireNonNull(networks, "logisticsBindings").stream()
                .filter(Objects::nonNull).map(LogisticsBinding::logisticsNetworkId).toList());
    }

    public static ClusterBinding fromProduction(ProductionBinding value) {
        var authority = value.authority();
        return new ClusterBinding(value.groupId(), value.revision(), authority == null ? null : new ClusterAuthority(
            switch (authority.role()) {
                case PANEL -> ClusterMemberType.PANEL;
                case LIBRARY -> ClusterMemberType.PATTERN_CORE;
                case COORDINATOR -> ClusterMemberType.COMPUTER_COORDINATOR;
            }, authority.memberId()), value.logisticsIds().stream().map(LogisticsBinding::new).toList());
    }

    public CompoundTag save() { return ProductionBindingCodec.save(productionBinding()); }

    public static Optional<ClusterBinding> tryLoad(CompoundTag tag) {
        return ProductionBindingCodec.load(tag).map(ClusterBinding::fromProduction);
    }
}
