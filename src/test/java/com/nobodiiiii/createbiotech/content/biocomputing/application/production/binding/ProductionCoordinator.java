package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionComputerTopology;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import java.util.*;
import javax.annotation.Nullable;

/** Production publication and two-phase binding owner, not general Cluster membership or shared Computer state. */
public final class ProductionCoordinator<H> {
    public enum Availability { READY, AUTHORITY_OFFLINE, CONFLICT }
    public enum Preparation { READY, IDENTITY, REVISION, CAPACITY, ACTIVE }

    public record MemberState(@Nullable UUID computerId, @Nullable ProductionResourceProfile profile,
        boolean persistenceAvailable, ProductionComputerTopology.State<?> topology) {
        public MemberState { Objects.requireNonNull(topology); }
    }

    /** Internal host capabilities. These references never enter program data or serialized archives. */
    public interface Access<H> {
        MemberState capture(H host);
        /** Resolve the coordinator belonging to this exact host instance, never by UUID alone. */
        ProductionCoordinator<H> coordinator(H host);
        ProductionLayout.Address address(H host);
        /** World qualification only; this owner independently checks the publication attachment. */
        boolean authoritativeLoaded(H host);
        void register(H host);
        void unregister(H host);
        void dependencyChanged(H host);
        void stageBinding(List<H> hosts, ProductionBinding binding);
        void publishBinding(H host);
        void indexedMutation(Runnable mutation);
    }

    private final H owner;
    private final Access<H> access;
    private boolean published;
    @Nullable private ProductionCoordinator<H> attachedTo;
    private List<H> replicas = List.of();
    private Set<UUID> requiredIds = Set.of();
    private boolean complete;
    @Nullable private Prepared<H> prepared;
    private Availability availability = Availability.CONFLICT;

    public ProductionCoordinator(H owner, Access<H> access) {
        this.owner = Objects.requireNonNull(owner); this.access = Objects.requireNonNull(access);
    }

    public boolean isPublished() { return published; }
    public boolean isAttachedTo(ProductionCoordinator<H> publication) { return attachedTo == publication; }

    /** Refresh membership before exposing the elected coordinator to the existing production registry. */
    public void publish(List<H> exact, Set<UUID> required) {
        var state = access.capture(owner); var record = state.topology().structure();
        if (state.computerId() == null || record == null || !record.coordinatorId().equals(state.computerId())) return;
        accept(exact, required);
        if (published) return;
        published = true;
        access.register(owner);
    }

    public void withdraw() {
        if (!published) return;
        access.unregister(owner);
        published = false;
        clear();
    }

    /** Called before unloading, replacing identity, or changing the resident of this host. */
    public void invalidatePublication() {
        var previous = attachedTo;
        if (previous != null) previous.invalidate(owner);
        withdraw();
    }

    void attachTo(ProductionCoordinator<H> publication) {
        if (attachedTo != null && attachedTo != publication) attachedTo.invalidate(owner);
        attachedTo = publication;
    }

    void detachFrom(ProductionCoordinator<H> publication) {
        if (attachedTo == publication) attachedTo = null;
    }

    public void refreshLoadedReplicas() {
        replicas.stream().filter(host -> access.coordinator(host).isAttachedTo(this))
            .filter(access::authoritativeLoaded).toList().forEach(access::dependencyChanged);
    }

    public boolean canRebind() {
        if (!published) return false;
        var state = access.capture(owner).topology();
        return state.epoch() == null && state.faults().isEmpty() && validReplicas()
            && (state.binding() == null || availability == Availability.READY);
    }

    /** Pure admission except for replacing the previous prepared transaction; no member binding is mutated. */
    public Preparation prepare(@Nullable ProductionBinding proposed, boolean logisticsOnly) {
        prepared = null;
        if (proposed == null || proposed.authority() == null || !validReplicas() || !published)
            return Preparation.IDENTITY;
        var ownerState = access.capture(owner).topology();
        var currentAuthority = ownerState.binding();
        if (logisticsOnly && (currentAuthority == null || !currentAuthority.groupId().equals(proposed.groupId())
            || !Objects.equals(currentAuthority.authority(), proposed.authority()))) return Preparation.IDENTITY;
        if ((!logisticsOnly && ownerState.epoch() != null) || !ownerState.faults().isEmpty()
            || currentAuthority != null && availability != Availability.READY) return Preparation.ACTIVE;
        if (proposed.logisticsIds().size() > ProductionBinding.MAX_NETWORKS) return Preparation.CAPACITY;
        ProductionLayout.Address address;
        try { address = access.address(owner); }
        catch (RuntimeException unavailable) { return Preparation.IDENTITY; }
        var exact = replicas;
        for (var host : exact) {
            var member = access.capture(host); var state = member.topology(); var record = state.structure();
            if (!member.persistenceAvailable() || !state.bindingValid() || member.computerId() == null || member.profile() == null
                || record == null || !record.structureId().equals(structureId()) || !record.coordinatorId().equals(coordinatorId()))
                return Preparation.IDENTITY;
            var members = record.structure().layout().members();
            var ids = new LinkedHashSet<UUID>(); members.forEach(node -> ids.add(node.computerId()));
            var node = members.stream().filter(candidate -> candidate.computerId().equals(member.computerId())).findFirst().orElse(null);
            if (!ids.equals(requiredIds) || node == null || !address.dimension().equals(node.address().dimension())
                || (!logisticsOnly && state.epoch() != null) || !state.faults().isEmpty()) return Preparation.IDENTITY;
            var current = state.binding();
            if (current != null && (proposed.revision() < current.revision()
                || proposed.revision() == current.revision() && !proposed.equals(current))) return Preparation.REVISION;
        }
        prepared = new Prepared<>(proposed, exact);
        return Preparation.READY;
    }

    /** Only the existing synchronous two-phase application transaction calls this, after successful prepare. */
    public void commit() {
        var accepted = Objects.requireNonNull(prepared, "No binding was prepared");
        access.indexedMutation(() -> access.stageBinding(accepted.replicas(), accepted.binding()));
        accepted.replicas().forEach(access::publishBinding);
        availability = Availability.READY;
        prepared = null;
    }

    public void accept(List<H> exact, Set<UUID> required) {
        Objects.requireNonNull(exact); Objects.requireNonNull(required);
        var copy = List.copyOf(exact);
        var retained = Collections.newSetFromMap(new IdentityHashMap<H,Boolean>()); retained.addAll(copy);
        for (var previous : replicas) if (!retained.contains(previous)) access.coordinator(previous).detachFrom(this);
        copy.forEach(host -> access.coordinator(host).attachTo(this));
        var requiredCopy = Set.copyOf(required);
        replicas = copy; requiredIds = requiredCopy; complete = true;
        prepared = null;
    }

    public void clear() {
        replicas.forEach(host -> access.coordinator(host).detachFrom(this));
        replicas = List.of(); requiredIds = Set.of(); complete = false;
        prepared = null; availability = Availability.CONFLICT;
    }

    public void invalidate(H host) {
        if (replicas.stream().noneMatch(replica -> replica == host)) return;
        complete = false; prepared = null;
        if (host == owner || access.capture(owner).topology().epoch() == null) withdraw();
    }

    /** Adopt only the ready authority's newer revision, staging all lower replicas before notifying any. */
    public boolean updateAccess(Availability next) {
        Objects.requireNonNull(next);
        if (next != Availability.READY) { availability = next; return false; }
        var authoritative = access.capture(owner).topology().binding();
        if (authoritative == null || !validReplicas()) { availability = Availability.CONFLICT; return false; }
        var lower = new ArrayList<H>();
        for (var host : replicas) {
            var state = access.capture(host).topology().binding();
            if (state == null || !state.groupId().equals(authoritative.groupId()) || state.revision() > authoritative.revision()
                || state.revision() == authoritative.revision() && !state.equals(authoritative)) {
                availability = Availability.CONFLICT;
                return false;
            }
            if (state.revision() < authoritative.revision()) lower.add(host);
        }
        access.stageBinding(List.copyOf(lower), authoritative);
        lower.forEach(access::publishBinding);
        availability = Availability.READY;
        return true;
    }

    private boolean validReplicas() {
        if (!complete || replicas.isEmpty()) return false;
        var actual = new LinkedHashSet<UUID>();
        for (var host : replicas) {
            if (!access.coordinator(host).isAttachedTo(this)) return false;
            var id = access.capture(host).computerId();
            if (id == null || !actual.add(id)) return false;
        }
        return actual.equals(requiredIds);
    }

    private UUID structureId() { return Optional.ofNullable(access.capture(owner).topology().structure()).orElseThrow().structureId(); }
    private UUID coordinatorId() { return Optional.ofNullable(access.capture(owner).computerId()).orElseThrow(); }
    private record Prepared<H>(ProductionBinding binding, List<H> replicas) {
        private Prepared { Objects.requireNonNull(binding); replicas = List.copyOf(replicas); }
    }
}
