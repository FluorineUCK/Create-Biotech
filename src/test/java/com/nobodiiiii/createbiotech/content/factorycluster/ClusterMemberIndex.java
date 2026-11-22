package com.nobodiiiii.createbiotech.content.factorycluster;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/** Temporary protocol adapter; the application owns the only scoped membership index and conflict algorithm. */
public final class ClusterMemberIndex {
    private static final ProductionMemberRegistry<MinecraftServer, ClusterMember> MEMBERS =
        new ProductionMemberRegistry<>(new ProductionMemberRegistry.Access<>() {
            @Override public UUID groupId(ClusterMember member) { return member.clusterId(); }
            @Override public ProductionBinding.Role role(ClusterMember member) { return roleOf(member.memberType()); }
            @Override public UUID memberId(ClusterMember member) { return member.memberId(); }
            @Override public boolean validBinding(ClusterMember member) { return member.hasValidBindingState(); }
            @Override public ProductionBinding binding(ClusterMember member) {
                var binding = member.bindingState();
                return binding == null ? null : binding.productionBinding();
            }
        });
    private ClusterMemberIndex() {}

    public static void register(MinecraftServer server, ClusterMember member) {
        MEMBERS.register(server, member);
        ClusterBindingService.reconcileLoaded(server, member);
    }
    public static void unregister(MinecraftServer server, ClusterMember member) { MEMBERS.unregister(server, member); }
    public static void rebind(MinecraftServer server, ClusterMember member, Runnable mutation) { MEMBERS.rebind(server, member, mutation); }
    public static List<ClusterMember> members(MinecraftServer server, UUID group, ClusterMemberType type) {
        return MEMBERS.members(server, group, roleOf(type));
    }
    public static ConflictReport conflicts(MinecraftServer server, UUID group) {
        var result = MEMBERS.conflicts(server, group);
        return new ConflictReport(result.libraryConflict(), result.coordinatorConflict(), false, result.bindingConflict());
    }
    static StableLookup lookupStable(MinecraftServer server, ClusterMemberType type, UUID memberId) {
        var result = MEMBERS.lookupStable(server, roleOf(type), memberId);
        return new StableLookup(LookupStatus.valueOf(result.status().name()), result.member());
    }
    private static ProductionBinding.Role roleOf(ClusterMemberType type) {
        return switch (type) {
            case PANEL -> ProductionBinding.Role.PANEL;
            case PATTERN_CORE -> ProductionBinding.Role.LIBRARY;
            case COMPUTER_COORDINATOR -> ProductionBinding.Role.COORDINATOR;
        };
    }
    enum LookupStatus { FOUND, MISSING, CONFLICT }
    record StableLookup(LookupStatus status, @Nullable ClusterMember member) {}
    public record ConflictReport(boolean patternConflict, boolean computerConflict, boolean panelConflict, boolean bindingConflict) {
        public boolean blocksNewTasks() { return patternConflict || computerConflict || bindingConflict; }
    }
}
