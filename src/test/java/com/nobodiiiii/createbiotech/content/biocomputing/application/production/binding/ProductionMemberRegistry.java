package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import java.lang.ref.WeakReference;
import java.util.*;
import javax.annotation.Nullable;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role;

/** Derived, server-thread production membership. Not general Cluster membership or shared Computer resources. */
public final class ProductionMemberRegistry<S, M> {
    public interface Access<M> {
        @Nullable UUID groupId(M member);
        Role role(M member);
        UUID memberId(M member);
        boolean validBinding(M member);
        @Nullable ProductionBinding binding(M member);
    }

    public enum LookupStatus { FOUND, MISSING, CONFLICT }
    public record Lookup<M>(LookupStatus status, @Nullable M member) {}
    public record Conflicts(boolean libraryConflict, boolean coordinatorConflict, boolean bindingConflict) {
        public boolean blocksNewTasks() { return libraryConflict || coordinatorConflict || bindingConflict; }
    }

    private final Access<M> access;
    private final Map<S, Index> scopes = new WeakHashMap<>();

    /** Access must not retain a particular server scope or its loaded members. */
    public ProductionMemberRegistry(Access<M> access) { this.access = Objects.requireNonNull(access); }

    public void register(S scope, M member) { index(scope).register(member); }
    public void unregister(S scope, M member) {
        var index = existing(scope);
        if (index != null) index.unregister(member);
    }
    public void rebind(S scope, M member, Runnable mutation) {
        var index = index(scope);
        index.unregister(member);
        try { mutation.run(); } finally { index.register(member); }
    }
    public List<M> members(S scope, UUID groupId, Role role) {
        var index = existing(scope);
        return index == null ? List.of() : index.members(groupId, role);
    }
    public Lookup<M> lookupStable(S scope, Role role, UUID memberId) {
        var index = existing(scope);
        return index == null ? new Lookup<>(LookupStatus.MISSING, null) : index.lookup(new Key(role, memberId));
    }
    public Conflicts conflicts(S scope, UUID groupId) {
        var index = existing(scope);
        if (index == null) return new Conflicts(false, false, false);
        var panels = index.members(groupId, Role.PANEL);
        var libraries = index.members(groupId, Role.LIBRARY);
        var coordinators = index.members(groupId, Role.COORDINATOR);
        var all = new ArrayList<>(panels); all.addAll(libraries); all.addAll(coordinators);
        return new Conflicts(libraries.size() > 1, coordinators.size() > 1,
            !all.isEmpty() && loadedBindingConflict(all));
    }

    private Index existing(S scope) { synchronized (scopes) { return scopes.get(scope); } }
    private Index index(S scope) { synchronized (scopes) { return scopes.computeIfAbsent(scope, ignored -> new Index()); } }

    /** Capture one binding projection per participant; comparison never mutates or reconciles a member. */
    private boolean loadedBindingConflict(List<M> members) {
        var snapshots = new ArrayList<Snapshot<M>>();
        ProductionBinding.Authority expected = null;
        for (var member : members) {
            if (!access.validBinding(member)) return true;
            var state = access.binding(member);
            if (state == null || state.authority() == null) return true;
            if (expected == null) expected = state.authority();
            else if (!expected.equals(state.authority())) return true;
            snapshots.add(new Snapshot<>(member, state));
        }
        ProductionBinding authoritative = null;
        for (var snapshot : snapshots) {
            if (access.role(snapshot.member()) == expected.role()
                && expected.memberId().equals(access.memberId(snapshot.member()))) {
                authoritative = snapshot.binding(); break;
            }
        }
        if (authoritative == null) return true;
        for (var snapshot : snapshots) if (!snapshot.binding().equals(authoritative)) return true;
        return false;
    }

    private record Key(Role role, UUID memberId) {}
    private record Snapshot<M>(M member, ProductionBinding binding) {}

    private final class Index {
        private final Map<UUID, Map<Key, List<WeakReference<M>>>> groups = new HashMap<>();

        private void register(M member) {
            var group = access.groupId(member);
            if (group == null) return;
            var members = groups.computeIfAbsent(group, ignored -> new HashMap<>());
            var refs = members.computeIfAbsent(new Key(access.role(member), access.memberId(member)), ignored -> new ArrayList<>());
            if (refs.stream().map(WeakReference::get).noneMatch(loaded -> loaded == member)) refs.add(new WeakReference<>(member));
        }

        /** Exact instance only. The current identity/binding may already be changed or unreadable. */
        private void unregister(M member) {
            var groupIterator = groups.values().iterator();
            while (groupIterator.hasNext()) {
                var members = groupIterator.next();
                var entries = members.values().iterator();
                while (entries.hasNext()) {
                    var refs = entries.next();
                    refs.removeIf(reference -> { var loaded = reference.get(); return loaded == null || loaded == member; });
                    if (refs.isEmpty()) entries.remove();
                }
                if (members.isEmpty()) groupIterator.remove();
            }
        }

        private List<M> members(UUID group, Role role) {
            var members = groups.get(group);
            if (members == null) return List.of();
            var loaded = new ArrayList<M>(); var entries = members.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next(); var live = new ArrayList<M>();
                entry.getValue().removeIf(reference -> {
                    var member = reference.get();
                    if (member != null) live.add(member);
                    return member == null;
                });
                if (entry.getValue().isEmpty()) entries.remove();
                else if (entry.getKey().role() == role) loaded.addAll(live);
            }
            if (members.isEmpty()) groups.remove(group);
            return List.copyOf(loaded);
        }

        private Lookup<M> lookup(Key key) {
            M found = null; var groupIterator = groups.values().iterator();
            while (groupIterator.hasNext()) {
                var members = groupIterator.next(); var refs = members.get(key);
                if (refs == null) continue;
                var iterator = refs.iterator();
                while (iterator.hasNext()) {
                    var member = iterator.next().get();
                    if (member == null) { iterator.remove(); continue; }
                    if (found != null && found != member) return new Lookup<>(LookupStatus.CONFLICT, null);
                    found = member;
                }
                if (refs.isEmpty()) members.remove(key);
                if (members.isEmpty()) groupIterator.remove();
            }
            return found == null ? new Lookup<>(LookupStatus.MISSING, null) : new Lookup<>(LookupStatus.FOUND, found);
        }
    }
}
