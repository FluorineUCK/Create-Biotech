package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator.Preparation;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry.Lookup;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry.LookupStatus;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Checked production binding operations. Never owns a Computer program, memory, or world mutation capability. */
public final class ProductionBindingTransactions<M> {
    public enum Result {
        OK, NO_SOURCE, ACTIVE, DIMENSION, PERMISSION, EMPTY_NETWORKS, TOO_MANY_BINDINGS,
        AUTHORITY_OFFLINE, STALE_BINDING, PARTICIPANT_REJECTED, CONFLICT;
        public boolean succeeded() { return this == OK; }
    }
    public enum Reconcile { CURRENT, UPDATED, AUTHORITY_OFFLINE, CONFLICT }
    public enum Availability { READY, AUTHORITY_OFFLINE, CONFLICT }

    /** Internal host ports, not operations exposed to user programs. Commit must honor a successful preparation. */
    public interface Access<M> extends ProductionMemberRegistry.Access<M> {
        String dimensionId(M member);
        List<UUID> logisticsIds(M member);
        boolean canRebind(M member);
        Preparation prepareRebind(M member, ProductionBinding proposed);
        default Preparation prepareLogistics(M member, ProductionBinding proposed) { return prepareRebind(member, proposed); }
        void commit(M member, ProductionBinding prepared);
    }

    /** Call-scoped world facts. Authority readiness may reconcile loaded production replicas. */
    public interface EntryChecks<M> {
        boolean formedProductionMember(M member);
        boolean current(M member);
        boolean currentForPlayer(M member);
        boolean hasStructuralConflict(UUID group);
        boolean authorityReady(M member);
        List<M> members(UUID group, Role role);
        Lookup<M> lookup(Role role, UUID memberId);
    }

    private final Access<M> access;
    public ProductionBindingTransactions(Access<M> access) { this.access = Objects.requireNonNull(access); }

    /** Attach only the unbound Library; do not revise or restart the source production group. */
    public Result attachFirstLibrary(M source, M target, EntryChecks<M> checks,
        Predicate<UUID> mayAdministrate, Runnable afterCommit) {
        var state = access.binding(source);
        if (access.role(source) != Role.PANEL || access.role(target) != Role.LIBRARY
            || !checks.formedProductionMember(source) || !access.validBinding(source) || state == null
            || state.authority() == null || state.authority().role() != Role.COORDINATOR
            || !checks.current(source) || !checks.current(target) || !access.validBinding(target))
            return Result.CONFLICT;
        if (!access.dimensionId(source).equals(access.dimensionId(target))) return Result.DIMENSION;
        if (access.binding(target) != null || !access.canRebind(target)) return Result.ACTIVE;
        if (checks.hasStructuralConflict(state.groupId())) return Result.CONFLICT;
        if (!checks.authorityReady(source)) return Result.AUTHORITY_OFFLINE;
        if (checks.members(state.groupId(), Role.LIBRARY).stream().anyMatch(member -> member != target))
            return Result.CONFLICT;
        var identity = checks.lookup(Role.LIBRARY, access.memberId(target));
        if (identity.status() == LookupStatus.CONFLICT
            || identity.status() == LookupStatus.FOUND && identity.member() != target) return Result.CONFLICT;
        if (!state.logisticsIds().stream().allMatch(mayAdministrate)) return Result.PERMISSION;
        var prepared = prepareRebind(target, state);
        if (!prepared.succeeded()) return prepared;
        if (!checks.formedProductionMember(source) || !state.equals(access.binding(source))
            || access.binding(target) != null) return Result.CONFLICT;
        access.commit(target, state);
        afterCommit.run();
        return Result.OK;
    }

    /** Authorize item-carried network configuration only; never install its previous authority. */
    public Result authorizePanelImport(List<? extends M> panels, EntryChecks<M> checks,
        Predicate<UUID> mayAdministrate) {
        if (panels.isEmpty()) return Result.NO_SOURCE;
        List<UUID> configured = null;
        for (var panel : panels) {
            if (access.role(panel) != Role.PANEL || !checks.currentForPlayer(panel) || !access.validBinding(panel)
                || checks.formedProductionMember(panel) || access.binding(panel) == null) return Result.CONFLICT;
            var identity = checks.lookup(Role.PANEL, access.memberId(panel));
            if (identity.status() == LookupStatus.CONFLICT
                || identity.status() == LookupStatus.FOUND && identity.member() != panel) return Result.CONFLICT;
            var networks = access.logisticsIds(panel);
            if (networks.isEmpty()) continue;
            if (configured != null && !configured.equals(networks)) return Result.CONFLICT;
            configured = networks;
            if (!networks.stream().allMatch(mayAdministrate)) return Result.PERMISSION;
        }
        return Result.OK;
    }

    public Result validate(M source, M target, Predicate<UUID> mayAdministrate, Collection<M> loaded) {
        return mergePlan(source, target, loaded, mayAdministrate).result();
    }
    public Result bind(M source, M target, Collection<M> loaded, Predicate<UUID> mayAdministrate,
        boolean conflict, Runnable afterCommit) {
        if (access.binding(source) == null) return Result.NO_SOURCE;
        if (conflict) return Result.CONFLICT;
        var plan = mergePlan(source, target, loaded, mayAdministrate);
        if (!plan.result().succeeded()) return plan.result();
        commit(plan); afterCommit.run(); return Result.OK;
    }
    public Result replace(M source, List<UUID> requested, Collection<M> loaded,
        Predicate<UUID> mayAdministrate, boolean conflict) {
        var state = access.binding(source);
        if (state == null) return Result.NO_SOURCE;
        if (conflict) return Result.CONFLICT;
        var members = loaded.stream().filter(member -> state.groupId().equals(access.groupId(member))).toList();
        var participants = participants(source, null, members);
        var common = validateParticipants(access.dimensionId(participants.getFirst()), participants);
        if (!common.succeeded()) return common;
        var plan = proposedPlan(state.groupId(), participants, requested, mayAdministrate, true);
        if (!plan.result().succeeded()) return plan.result();
        commit(plan); return Result.OK;
    }

    /** Narrow network-list edit of an already formed production group; does not rebind its identity or authority. */
    public Result replaceFormed(M target, List<UUID> requested, Collection<M> loaded,
        Predicate<UUID> mayAdministrate, boolean conflict, boolean formedAuthority) {
        Objects.requireNonNull(target);
        var networks = normalize(Objects.requireNonNull(requested));
        var participants = List.copyOf(Objects.requireNonNull(loaded));
        Objects.requireNonNull(mayAdministrate);
        var current = access.binding(target);
        if (!formedAuthority || (access.role(target) != Role.PANEL && access.role(target) != Role.LIBRARY)
            || current == null || current.authority() == null || current.authority().role() != Role.COORDINATOR)
            return Result.AUTHORITY_OFFLINE;
        if (networks.size() > ProductionBinding.MAX_NETWORKS) return Result.TOO_MANY_BINDINGS;
        if (conflict) return Result.CONFLICT;
        var authority = findAuthority(current.authority(), participants);
        if (authority == null || access.binding(authority) == null || !access.binding(authority).equals(current))
            return Result.AUTHORITY_OFFLINE;
        if (current.revision() == Long.MAX_VALUE) return Result.STALE_BINDING;
        var administered = new LinkedHashSet<>(current.logisticsIds()); administered.addAll(networks);
        if (!administered.stream().allMatch(mayAdministrate)) return Result.PERMISSION;
        if (participants.isEmpty() || participants.stream().anyMatch(member ->
            !access.validBinding(member) || !current.equals(access.binding(member)))) return Result.CONFLICT;
        var proposed = new ProductionBinding(current.groupId(), current.revision() + 1, current.authority(), networks);
        for (var member : participants) {
            Preparation preparation;
            try { preparation = access.prepareLogistics(member, proposed); }
            catch (RuntimeException refused) { return Result.PARTICIPANT_REJECTED; }
            if (preparation != Preparation.READY) return result(preparation);
        }
        participants.forEach(member -> access.commit(member, proposed));
        return Result.OK;
    }

    public Reconcile reconcile(M loading, Collection<M> loaded) {
        var all = participants(loading, null, loaded);
        return reconcile(loading, all, authority -> lookupStable(authority, all));
    }
    public Reconcile reconcile(M loading, Collection<M> loaded, Function<ProductionBinding.Authority, Lookup<M>> lookup) {
        var identity = lookup.apply(identity(loading));
        if (identity.status() != LookupStatus.FOUND || identity.member() != loading) return Reconcile.CONFLICT;
        var initial = access.binding(loading);
        if (initial == null || !access.validBinding(loading) || initial.authority() == null) return Reconcile.CONFLICT;
        var all = participants(loading, null, loaded).stream()
            .filter(member -> initial.groupId().equals(access.groupId(member))).toList();
        var canonical = resolveAuthority(initial, lookup);
        if (canonical.result() != Reconcile.CURRENT) return canonical.result();
        var authoritative = canonical.state(); var stale = new ArrayList<M>();
        for (var member : all) {
            var state = access.binding(member);
            if (state == null || !access.validBinding(member)) return Reconcile.CONFLICT;
            var resolved = resolveAuthority(state, lookup);
            if (resolved.result() != Reconcile.CURRENT) return resolved.result();
            if (!canonical.authority().equals(resolved.authority()) || !authoritative.equals(resolved.state())
                || state.revision() > authoritative.revision()
                || (state.revision() == authoritative.revision() && !state.equals(authoritative))) return Reconcile.CONFLICT;
            if (!state.equals(authoritative)) stale.add(member);
        }
        for (var member : stale) if (prepareRebind(member, authoritative) != Result.OK) return Reconcile.CONFLICT;
        stale.forEach(member -> access.commit(member, authoritative));
        return stale.isEmpty() ? Reconcile.CURRENT : Reconcile.UPDATED;
    }

    public Availability snapshotAccess(M member, Collection<M> loaded) {
        Objects.requireNonNull(member);
        var all = participants(member, null, Objects.requireNonNull(loaded));
        if (all.stream().filter(m -> access.role(m) == Role.LIBRARY).count() > 1
            || all.stream().filter(m -> access.role(m) == Role.COORDINATOR).count() > 1) return Availability.CONFLICT;
        return snapshotAccess(member, all, authority -> lookupStable(authority, all));
    }
    /** Pure proof: never prepares, commits or reconciles any participant. Caller supplies current loaded membership. */
    public Availability snapshotAccess(M member, Collection<M> loaded, Function<ProductionBinding.Authority, Lookup<M>> lookup) {
        var identity = lookup.apply(identity(member));
        if (identity.status() != LookupStatus.FOUND || identity.member() != member) return Availability.CONFLICT;
        var local = access.binding(member);
        if (local == null || !access.validBinding(member) || local.authority() == null) return Availability.CONFLICT;
        var canonical = resolveAuthority(local, lookup);
        if (canonical.result() == Reconcile.AUTHORITY_OFFLINE) return Availability.AUTHORITY_OFFLINE;
        if (canonical.result() != Reconcile.CURRENT || canonical.state() == null || !local.equals(canonical.state()))
            return Availability.CONFLICT;
        for (var participant : loaded) {
            var state = access.binding(participant); var found = lookup.apply(identity(participant));
            if (found.status() != LookupStatus.FOUND || found.member() != participant || !access.validBinding(participant)
                || state == null || !local.groupId().equals(state.groupId()) || !canonical.state().equals(state))
                return Availability.CONFLICT;
        }
        return Availability.READY;
    }

    public Result prepareRebind(M member, ProductionBinding proposed) {
        try { return result(access.prepareRebind(member, proposed)); }
        catch (RuntimeException refused) { return Result.PARTICIPANT_REJECTED; }
    }
    private static Result result(Preparation prepared) {
        return switch (prepared) {
            case READY -> Result.OK;
            case ACTIVE -> Result.ACTIVE;
            case CAPACITY -> Result.TOO_MANY_BINDINGS;
            case IDENTITY, REVISION -> Result.PARTICIPANT_REJECTED;
        };
    }

    private Plan<M> mergePlan(M source, M target, Collection<M> loaded, Predicate<UUID> mayAdministrate) {
        var all = participants(source, target, loaded);
        if (access.binding(source) == null) return Plan.failed(Result.NO_SOURCE, all);
        var common = validateParticipants(access.dimensionId(source), all);
        if (!common.succeeded()) return Plan.failed(common, all);
        var authority = validateKnownAuthorities(all);
        if (!authority.succeeded()) return Plan.failed(authority, all);
        return proposedPlan(access.binding(source).groupId(), all, canonicalNetworks(all), mayAdministrate, false);
    }
    private Plan<M> proposedPlan(UUID group, List<M> all, List<UUID> requested, Predicate<UUID> mayAdministrate, boolean allowEmpty) {
        var networks = normalize(requested);
        if (!allowEmpty && networks.isEmpty()) return Plan.failed(Result.EMPTY_NETWORKS, all);
        if (networks.size() > ProductionBinding.MAX_NETWORKS) return Plan.failed(Result.TOO_MANY_BINDINGS, all);
        var known = validateKnownAuthorities(all);
        if (!known.succeeded()) return Plan.failed(known, all);
        var authority = all.stream().min(Comparator.comparingInt((M member) -> priority(access.role(member)))
            .thenComparing(member -> access.memberId(member).toString())).map(this::identity).orElse(null);
        if (authority == null) return Plan.failed(Result.CONFLICT, all);
        var ids = new LinkedHashSet<UUID>(); all.forEach(member -> ids.addAll(access.logisticsIds(member))); ids.addAll(networks);
        if (!ids.stream().allMatch(mayAdministrate)) return Plan.failed(Result.PERMISSION, all);
        long revision = all.stream().map(access::binding).filter(Objects::nonNull).mapToLong(ProductionBinding::revision).max().orElse(0);
        if (revision == Long.MAX_VALUE) return Plan.failed(Result.STALE_BINDING, all);
        var proposed = new ProductionBinding(group, revision + 1, authority, networks); var preparation = Result.OK;
        for (var member : all) {
            var prepared = prepareRebind(member, proposed);
            if (!prepared.succeeded() && preparation.succeeded()) preparation = prepared;
        }
        return preparation.succeeded() ? new Plan<>(Result.OK, all, proposed) : Plan.failed(preparation, all);
    }
    private Result validateParticipants(String dimension, List<M> all) {
        if (all.stream().anyMatch(member -> !access.validBinding(member))) return Result.CONFLICT;
        if (all.stream().anyMatch(member -> !access.canRebind(member))) return Result.ACTIVE;
        if (all.stream().anyMatch(member -> !dimension.equals(access.dimensionId(member)))) return Result.DIMENSION;
        long libraries = all.stream().filter(member -> access.role(member) == Role.LIBRARY).map(access::memberId).distinct().count();
        long coordinators = all.stream().filter(member -> access.role(member) == Role.COORDINATOR).map(access::memberId).distinct().count();
        return libraries > 1 || coordinators > 1 ? Result.CONFLICT : Result.OK;
    }
    private Result validateKnownAuthorities(List<M> all) {
        for (var member : all) {
            var state = access.binding(member);
            if (state == null) continue;
            if (state.authority() == null) return Result.STALE_BINDING;
            var authority = findAuthority(state.authority(), all);
            if (authority == null) return Result.AUTHORITY_OFFLINE;
            var authoritative = access.binding(authority);
            if (authoritative == null || !access.validBinding(authority) || !state.authority().equals(authoritative.authority())
                || !identifies(state.authority(), authority) || !state.groupId().equals(authoritative.groupId())) return Result.STALE_BINDING;
            if (state.revision() > authoritative.revision()
                || (state.revision() == authoritative.revision() && !state.equals(authoritative))) return Result.STALE_BINDING;
        }
        return Result.OK;
    }
    private List<UUID> canonicalNetworks(List<M> all) {
        var visited = new HashSet<UUID>(); var networks = new ArrayList<UUID>();
        for (var member : all) {
            var state = access.binding(member);
            if (state == null) { networks.addAll(access.logisticsIds(member)); continue; }
            if (visited.add(state.groupId())) networks.addAll(access.binding(findAuthority(state.authority(), all)).logisticsIds());
        }
        return normalize(networks);
    }
    private Resolution resolveAuthority(ProductionBinding initial, Function<ProductionBinding.Authority, Lookup<M>> lookup) {
        var authority = initial.authority();
        if (authority == null) return Resolution.failed(Reconcile.CONFLICT);
        var visited = new HashSet<ProductionBinding.Authority>(); var observed = initial;
        while (visited.add(authority)) {
            var found = lookup.apply(authority);
            if (found.status() == LookupStatus.MISSING) return Resolution.failed(Reconcile.AUTHORITY_OFFLINE);
            if (found.status() != LookupStatus.FOUND) return Resolution.failed(Reconcile.CONFLICT);
            var member = found.member(); var state = access.binding(member);
            if (!identifies(authority, member) || state == null || !access.validBinding(member) || state.authority() == null
                || state.revision() < observed.revision()
                || (state.revision() == observed.revision() && !state.equals(observed))) return Resolution.failed(Reconcile.CONFLICT);
            var next = state.authority();
            if (next.equals(authority)) return new Resolution(Reconcile.CURRENT, authority, state);
            observed = state; authority = next;
        }
        return Resolution.failed(Reconcile.CONFLICT);
    }
    private Lookup<M> lookupStable(ProductionBinding.Authority authority, Collection<M> loaded) {
        M found = null;
        for (var member : loaded) {
            if (!identifies(authority, member)) continue;
            if (found != null && found != member) return new Lookup<>(LookupStatus.CONFLICT, null);
            found = member;
        }
        return found == null ? new Lookup<>(LookupStatus.MISSING, null) : new Lookup<>(LookupStatus.FOUND, found);
    }
    @Nullable private M findAuthority(ProductionBinding.Authority authority, List<M> all) {
        return all.stream().filter(member -> identifies(authority, member)).findFirst().orElse(null);
    }
    private boolean identifies(ProductionBinding.Authority authority, M member) {
        return authority.role() == access.role(member) && authority.memberId().equals(access.memberId(member));
    }
    private ProductionBinding.Authority identity(M member) { return new ProductionBinding.Authority(access.role(member), access.memberId(member)); }
    private static int priority(Role role) { return switch (role) { case COORDINATOR -> 0; case LIBRARY -> 1; case PANEL -> 2; }; }
    private static List<UUID> normalize(List<UUID> networks) {
        var ordered = new LinkedHashSet<UUID>(); for (var id : networks) if (id != null) ordered.add(id);
        return List.copyOf(ordered);
    }
    private List<M> participants(M source, @Nullable M target, Collection<M> loaded) {
        Set<M> identities = Collections.newSetFromMap(new IdentityHashMap<>()); var all = new ArrayList<M>();
        if (identities.add(source)) all.add(source);
        if (target != null && identities.add(target)) all.add(target);
        for (var member : loaded) if (identities.add(member)) all.add(member);
        return List.copyOf(all);
    }
    private void commit(Plan<M> plan) { plan.participants().forEach(member -> access.commit(member, plan.proposed())); }
    private record Plan<M>(Result result, List<M> participants, @Nullable ProductionBinding proposed) {
        static <M> Plan<M> failed(Result result, List<M> participants) { return new Plan<>(result, participants, null); }
    }
    private record Resolution(Reconcile result, @Nullable ProductionBinding.Authority authority, @Nullable ProductionBinding state) {
        static Resolution failed(Reconcile result) { return new Resolution(result, null, null); }
    }
}
