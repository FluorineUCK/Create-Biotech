package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Supplier;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.Address;

/** Server-thread, derived production shell index. Neither membership authorization nor persistent Cluster state. */
public final class ProductionShellMemberships<S, H> {
    private final Map<S, Index> scopes = new WeakHashMap<>();
    public enum Refresh { UPDATED, UNCHANGED, STALE }

    /** The host retains this handle. The registry never strongly retains the host, handle or scope. */
    public Registration registration(H host) { return new Registration(Objects.requireNonNull(host)); }

    public List<H> members(S scope, Address address) {
        Objects.requireNonNull(scope); Objects.requireNonNull(address);
        Index index;
        synchronized (scopes) { index = scopes.get(scope); }
        return index == null ? List.of() : index.members(address);
    }

    private Index index(S scope) {
        synchronized (scopes) { return scopes.computeIfAbsent(Objects.requireNonNull(scope), ignored -> new Index()); }
    }

    public final class Registration {
        private final WeakReference<H> host;
        private Index index;
        private Set<Address> shells = Set.of();
        private long version = Long.MIN_VALUE;
        private Object fence = new Object();

        private Registration(H host) { this.host = new WeakReference<>(host); }
        public boolean indexed() { return !shells.isEmpty() && host.get() != null; }

        /** Failed preparation leaves the old publication intact; a reentrant publication/clear wins. */
        public Refresh refresh(S scope, long version, Supplier<? extends Collection<Address>> prepare) {
            Objects.requireNonNull(prepare);
            var target = index(scope);
            if (index == target && this.version == version) return Refresh.UNCHANGED;
            Object expected = fence;
            var next = new LinkedHashSet<Address>();
            for (var address : Objects.requireNonNull(prepare.get())) next.add(Objects.requireNonNull(address));
            var frozen = Collections.unmodifiableSet(next);
            if (fence != expected) return Refresh.STALE;
            if (host.get() == null) { clear(); return Refresh.STALE; }
            unpublish();
            target.add(this, frozen);
            index = target; shells = frozen; this.version = version; fence = new Object();
            return Refresh.UPDATED;
        }

        /** Always detach from the original index, even when the host's current Level is gone or changed. */
        public void clear() {
            unpublish(); version = Long.MIN_VALUE; fence = new Object();
        }
        private void unpublish() {
            if (index != null) index.remove(this, shells);
            index = null; shells = Set.of();
        }
    }

    private final class Index {
        private final Map<Address, List<WeakReference<Registration>>> byShell = new LinkedHashMap<>();

        private void add(Registration registration, Set<Address> shells) {
            for (var shell : shells) {
                var registrations = byShell.computeIfAbsent(shell, ignored -> new ArrayList<>());
                registrations.removeIf(reference -> dead(reference.get()));
                if (registrations.stream().noneMatch(reference -> reference.get() == registration))
                    registrations.add(new WeakReference<>(registration));
            }
        }
        private void remove(Registration registration, Set<Address> shells) {
            for (var shell : shells) {
                var registrations = byShell.get(shell);
                if (registrations == null) continue;
                registrations.removeIf(reference -> reference.get() == registration || dead(reference.get()));
                if (registrations.isEmpty()) byShell.remove(shell);
            }
        }
        private List<H> members(Address shell) {
            var registrations = byShell.get(shell);
            if (registrations == null) return List.of();
            var result = new ArrayList<H>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<H, Boolean>());
            registrations.removeIf(reference -> {
                var registration = reference.get();
                H member = registration == null ? null : registration.host.get();
                if (member != null && seen.add(member)) result.add(member);
                return member == null;
            });
            if (registrations.isEmpty()) byShell.remove(shell);
            return List.copyOf(result);
        }
        private boolean dead(Registration registration) { return registration == null || registration.host.get() == null; }
    }
}
